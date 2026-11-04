package com.integemjack.friday

/**
 * 演示数据的接入口：真正的假数据实现只在 debug 源集（`app/src/debug`）里，
 * 由那边的 DemoInstallProvider 在进程启动时塞进 [hook]。release 包里 hook 永远是 null，
 * `MainActivity` 的调用就是一个空判断。
 *
 * 用法（只在 debug 包里有效）：
 *   adb shell am start -n com.integemjack.friday/.MainActivity --es demo sessions
 *   adb shell am start -n com.integemjack.friday/.MainActivity --es demo chat
 *   adb shell am start -n com.integemjack.friday/.MainActivity --es demo activity
 *   adb shell am start -n com.integemjack.friday/.MainActivity --es demo settings
 */
object Demo {
    /** debug 包启动时被 DemoInstallProvider 赋值；null = 没有演示数据可用（release） */
    @Volatile
    var hook: ((AppModel, String) -> Unit)? = null

    /** 这次是从演示数据进来的，进主界面直接打开会话（聊天页） */
    @Volatile
    var startInChat: Boolean = false

    /** 演示数据已经装上了（这个进程里所有 RPC 都是假的） */
    @Volatile
    var active: Boolean = false

    /** 返回这次要用的场景名（没传 --es demo 就是空串，一切照常） */
    fun apply(model: AppModel, scene: String?): String {
        if (!BuildConfig.DEBUG || scene.isNullOrBlank()) return ""
        val fill = hook ?: return ""
        startInChat = scene.startsWith("chat")
        active = true
        fill(model, scene)
        return scene
    }
}