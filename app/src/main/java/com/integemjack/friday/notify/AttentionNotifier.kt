package com.integemjack.friday.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.integemjack.friday.FridayApp
import com.integemjack.friday.R
import com.integemjack.friday.proto.PendingApproval

/**
 * 「待确认」那条通知（在后台时有调用等你批准、Friday 在问你）：
 *
 * - 提升成实时活动（设置里「灵动岛」，默认开，[LiveIsland]）：状态栏胶囊 / 小米超级岛上写「在问你」「待确认」「3项待确认」，
 *   常驻到答完——这时通知是 ongoing 的，划不掉
 * - 通知上直接答：提问是单选的点选项（2 个以内再加「回答…」自己写一句），多选 / 好几题只有「回答…」；
 *   普通调用是「批准 / 拒绝」；要密钥的（request_secret）只给「拒绝」。按钮都要先解锁（锁屏上别人点不了）
 * - 一次只摆一项（按钮只对得上一项），正文写「还有 N 项」；答完 / 别处答了换下一项，全没了收掉
 * - 新来的一项才响；换项、出错改字都不响
 *
 * 线程：[onAttention] / [onPendingChanged] 在协程里调，[handle] 在通知按钮的广播里调——改状态、发通知都加锁。
 */
class AttentionNotifier(
    private val app: Context,
    /** 设置里的「灵动岛」 */
    private val island: () -> Boolean,
    /** 现在等着的那几项（会话标题 + 调用） */
    private val pending: () -> List<Pair<String, PendingApproval>>,
    /** 发给那台：runner.resolveApproval */
    private val resolve: suspend (eventID: String, approved: Boolean, answers: List<String>?, reply: String?) -> Result<Unit>,
    /** 通知开关、POST_NOTIFICATIONS 都开着 */
    private val canNotify: () -> Boolean,
    /** 点通知本身：打开 App 的助手页 */
    private val contentIntent: () -> PendingIntent,
) {
    /** 通知上现在摆的是哪一项（null = 还不知道是哪项的占位） */
    private var shownEvent: String? = null
    private var shownIds: Set<String> = emptySet()

    /** attention 事件（App 在后台）：发 / 换成最新的，有新来的一项才响。返回发没发出去（调用方据此等提示音） */
    @Synchronized
    fun onAttention(): Boolean {
        if (!canNotify()) return false
        val list = pending()
        val ids = list.map { it.second.eventID }.toSet()
        val fresh = !isShowing() || ids.isEmpty() || !shownIds.containsAll(ids)
        post(list, alert = fresh, error = null)
        return true
    }

    /** 等着的列表变了（别处答了、来了新的、activity 晚到）：通知还在就跟着换，不响；全答完了收掉 */
    @Synchronized
    fun onPendingChanged(list: List<Pair<String, PendingApproval>>) {
        if (!isShowing()) return
        if (list.isEmpty()) {
            cancel()
            return
        }
        val ids = list.map { it.second.eventID }.toSet()
        if (list[0].second.eventID == shownEvent && ids == shownIds) return
        post(list, alert = false, error = null)
    }

    /** 通知上的按钮：发给那台，答上了换下一项 / 收掉；没发出去（没连上）就在通知上写原因、按钮留着 */
    suspend fun handle(intent: Intent) {
        val eventID = intent.getStringExtra(EXTRA_EVENT) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        val resolution = resolutionFrom(
            kind = intent.getStringExtra(EXTRA_KIND).orEmpty(),
            label = intent.getStringExtra(EXTRA_LABEL),
            questionCount = intent.getIntExtra(EXTRA_QUESTIONS, 1),
            reply = reply,
        )
        if (resolution == null) {
            // 「回答…」里什么都没写：照原样再发一遍，把系统的转圈收起来
            synchronized(this) { if (isShowing()) post(pending(), alert = false, error = null) }
            return
        }
        val result = resolve(eventID, resolution.approved, resolution.answers, resolution.reply)
        synchronized(this) {
            val now = pending()
            val stillWaiting = now.any { it.second.eventID == eventID }
            if (result.isFailure && stillWaiting) {
                val why = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "出错了"
                post(now, alert = false, error = "没发出去：$why")
                return
            }
            // 答上了，或者那台已经不在等了（别处答过）：这一项算完
            val rest = now.filter { it.second.eventID != eventID }
            if (rest.isEmpty()) cancel() else post(rest, alert = false, error = null)
        }
    }

    private fun post(list: List<Pair<String, PendingApproval>>, alert: Boolean, error: String?) {
        val card = attentionCard(list)
        val first = list.firstOrNull()?.second
        val onIsland = island()
        val builder = NotificationCompat.Builder(app, FridayApp.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(card.title)
            .setContentText(error ?: card.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (error != null) "$error\n\n${card.detail}" else card.detail))
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(!alert)
            // 灵动岛：实时活动要 ongoing（答完才收）；关着时照旧，点开就消失
            .setOngoing(onIsland)
            .setAutoCancel(!onIsland)
        if (first != null) {
            attentionActions(first).forEachIndexed { index, action ->
                builder.addAction(action(first, action, index))
            }
        }
        if (onIsland) LiveIsland.request(builder, card.chip)
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build()) }
        shownEvent = first?.eventID
        shownIds = list.map { it.second.eventID }.toSet()
    }

    private fun action(approval: PendingApproval, action: AttentionAction, index: Int): NotificationCompat.Action {
        val intent = Intent(app, AttentionActionReceiver::class.java)
            .setAction(ACTION_ANSWER)
            // PendingIntent 只按 action / data / 组件区分（不看 extras）：每个按钮一个 data，免得互相顶掉
            .setData(Uri.parse("friday-attention://${Uri.encode(approval.eventID)}/$index"))
            .putExtra(EXTRA_EVENT, approval.eventID)
            .putExtra(EXTRA_KIND, action.kind)
            .putExtra(EXTRA_LABEL, action.label)
            .putExtra(EXTRA_QUESTIONS, approval.questions?.size ?: 0)
        // 「回答…」要系统往 Intent 里填字：必须是可变的（Android 12+）；别的一律不可变
        val mutability = if (action is AttentionAction.Reply) {
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        val pi = PendingIntent.getBroadcast(app, index, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        val builder = NotificationCompat.Action.Builder(0, action.label, pi)
            // 批准、回答都是替你做决定：锁屏上先解锁才算
            .setAuthenticationRequired(true)
        if (action is AttentionAction.Reply) {
            builder.addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("回答 Friday").build())
                .setAllowGeneratedReplies(false)
        }
        return builder.build()
    }

    private fun isShowing(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
    }.getOrDefault(false)

    private fun cancel() {
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
        shownEvent = null
        shownIds = emptySet()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_ANSWER = "com.integemjack.friday.ATTENTION_ANSWER"
        const val EXTRA_EVENT = "eventID"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LABEL = "label"
        const val EXTRA_QUESTIONS = "questions"
        const val KEY_REPLY = "reply"
    }
}

/** 待确认那条通知上的一个按钮 */
internal sealed interface AttentionAction {
    val label: String
    val kind: String

    /** 单选题的一个选项：answers = [选项名] */
    data class Option(override val label: String) : AttentionAction {
        override val kind get() = "option"
    }

    /** 自己写一句（通知上的输入框）：reply */
    data object Reply : AttentionAction {
        override val label = "回答…"
        override val kind = "reply"
    }

    data object Approve : AttentionAction {
        override val label = "批准"
        override val kind = "approve"
    }

    data object Reject : AttentionAction {
        override val label = "拒绝"
        override val kind = "reject"
    }
}

/**
 * 这一项在通知上给哪几个按钮（系统最多摆 3 个）：
 * - 单选一题：选项 ≤ 2 个 → 选项 +「回答…」；3–4 个 → 摆 3 个选项（推荐的那个一定在里面）
 * - 多选、好几题：只有「回答…」（按钮选不全，写一句让它自己理解）
 * - 要密钥的（request_secret）：只有「拒绝」（批准得先在那台填密钥）
 * - 别的调用：「批准」「拒绝」
 */
internal fun attentionActions(approval: PendingApproval): List<AttentionAction> {
    val questions = approval.questions
    if (!questions.isNullOrEmpty()) {
        val question = questions.singleOrNull()
        if (question == null || question.multiple) return listOf(AttentionAction.Reply)
        val labels = question.options.map { it.label.trim() }.filter { it.isNotEmpty() }.distinct()
        if (labels.size <= 2) return labels.map { AttentionAction.Option(it) } + AttentionAction.Reply
        val shown = labels.take(3).toMutableList()
        // recommended 在活动事件里是 0 起的序号（服务端 AskUser 已经换算过），-1 = 没推荐
        val recommended = question.options.getOrNull(question.recommended)?.label?.trim()
        if (recommended != null && recommended in labels && recommended !in shown) shown[2] = recommended
        return shown.map { AttentionAction.Option(it) }
    }
    if (approval.tool == "request_secret") return listOf(AttentionAction.Reject)
    return listOf(AttentionAction.Approve, AttentionAction.Reject)
}

/** 按钮点下去发给那台的（runner.resolveApproval 的 approved / answer） */
internal data class Resolution(val approved: Boolean, val answers: List<String>?, val reply: String?)

/** 从按钮带回来的东西算出要发的；「回答…」没写字 = null（不发） */
internal fun resolutionFrom(kind: String, label: String?, questionCount: Int, reply: String?): Resolution? = when (kind) {
    "option" -> label?.takeIf { it.isNotBlank() }?.let { Resolution(true, listOf(it), null) }
    "reply" -> reply?.trim()?.takeIf { it.isNotEmpty() }?.let {
        // 每题一条、空 = 没选，话写在 reply（ASK.md「没点选项直接回了一句」）
        Resolution(true, List(questionCount.coerceAtLeast(1)) { "" }, it)
    }
    "approve" -> Resolution(true, null, null)
    "reject" -> Resolution(false, null, null)
    else -> null
}

/** 通知上写的：标题、一行正文、展开后的全文、胶囊上的几个字（少于 7 个字） */
internal data class AttentionCard(val title: String, val text: String, val detail: String, val chip: String)

internal fun attentionCard(approvals: List<Pair<String, PendingApproval>>): AttentionCard {
    if (approvals.isEmpty()) {
        val text = "远程会话里有调用在等你批准或回答"
        return AttentionCard("Friday 等你确认", text, text, "待确认")
    }
    val (session, first) = approvals[0]
    val where = session.ifBlank { "会话" }
    val more = approvals.size - 1
    val chip = when {
        more > 0 -> "${approvals.size}项待确认"
        first.isQuestion -> "在问你"
        else -> "待确认"
    }
    val tail = if (more > 0) "\n\n还有 $more 项等你确认" else ""
    return if (first.isQuestion) {
        val questions = first.questions.orEmpty()
        val question = questions.first().question.trim().ifEmpty { first.title.ifBlank { first.tool } }
        val text = "$where：$question"
        val detail = buildString {
            append(text)
            questions.drop(1).forEach { append("\n").append(it.question.trim()) }
            val options = questions.singleOrNull()?.options.orEmpty()
            if (options.isNotEmpty()) {
                append("\n")
                options.forEach { option ->
                    append("\n· ").append(option.label)
                    option.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
                }
            }
            append(tail)
        }
        AttentionCard(if (more > 0) "Friday 等你确认（${approvals.size} 项）" else "Friday 在问你", text, detail, chip)
    } else {
        val what = first.title.ifBlank { first.tool }
        val text = "$where：$what"
        val detail = buildString {
            append(text)
            first.summary.trim().takeIf { it.isNotEmpty() && it != what }?.let { append("\n").append(it.take(400)) }
            append(tail)
        }
        AttentionCard(if (more > 0) "Friday 等你确认（${approvals.size} 项）" else "Friday 等你确认", text, detail, chip)
    }
}
