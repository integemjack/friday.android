package com.integemjack.friday.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.integemjack.friday.Demo

/**
 * 只在 debug 包里存在（见 app/src/debug/AndroidManifest.xml）。
 * 进程启动时把演示数据的实现交给 [Demo.hook]，写界面的代码不用认识 demo 包。
 */
class DemoInstallProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Demo.hook = { model, scene -> DemoData.fill(model, scene) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}