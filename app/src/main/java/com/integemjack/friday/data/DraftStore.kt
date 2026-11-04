package com.integemjack.friday.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 输入框里打了一半的话：助手页一份、每段会话一份，App 被关掉 / 手机重启后还在。
 * 只存字（附件的本地副本、上传进度、定好的位置都留不住，也不该留：过一会儿就不准了）。
 * 发出去（输入框清空）就删掉那一项；退出 SChat 登录时全清。
 */
class DraftStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("drafts", Context.MODE_PRIVATE))

    fun load(key: String): String = prefs.getString(key, null).orEmpty()

    /** 每打一个字都会调：apply() 是异步合并写的，不卡输入 */
    fun save(key: String, text: String) {
        val editor = prefs.edit()
        if (text.isBlank()) editor.remove(key) else editor.putString(key, text)
        editor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val ASSISTANT = "assistant"

        fun chat(conversationId: String) = "chat:$conversationId"
    }
}
