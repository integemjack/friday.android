package com.integemjack.friday.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.integemjack.friday.FridayApp

/** 「待确认」通知上的按钮（选项、回答…、批准、拒绝）：交给 [AttentionNotifier.handle]，发完再结束广播 */
class AttentionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AttentionNotifier.ACTION_ANSWER) return
        val model = (context.applicationContext as? FridayApp)?.model ?: return
        val pending = goAsync()
        model.answerFromNotification(intent) { pending.finish() }
    }
}
