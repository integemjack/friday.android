package com.integemjack.friday.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码：按模块一格一格画（不是先画成位图再缩放），多大都是锐的。
 * 纠错 M、留 2 格白边（SChat 扫一扫的相机在屏幕反光下也认得出）。
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, color: Color = Color.Black, background: Color = Color.White) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier) {
        drawRect(background)
        val cells = matrix.width
        val cell = size.minDimension / cells
        val left = (size.width - cell * cells) / 2
        val top = (size.height - cell * cells) / 2
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix.get(x, y)) {
                    // 多画 0.5 像素：相邻格子之间不留发丝缝
                    drawRect(color, Offset(left + x * cell, top + y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** 这台手机上的 SChat：没装 / 装了但还不认「通过 SChat 登录」的链接（老版本）/ 能用 */
enum class SchatApp { Missing, Outdated, Ready }

const val SCHAT_PACKAGE = "com.ireoo.schat"

/**
 * 看 SChat 装没装、认不认 `schat://login/<token>`（认不认 = 系统里有没有哪个界面接这个链接：
 * SChat 的老版本只接 schat://f、invite、room，这个链接没人接）。Android 11+ 要在清单里声明 queries 才看得见
 */
fun schatApp(context: Context): SchatApp {
    val pm = context.packageManager
    val installed = runCatching { pm.getPackageInfo(SCHAT_PACKAGE, 0); true }.getOrDefault(false)
    if (!installed) return SchatApp.Missing
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("schat://login/probe")).setPackage(SCHAT_PACKAGE)
    return if (pm.queryIntentActivities(probe, 0).isNotEmpty()) SchatApp.Ready else SchatApp.Outdated
}

/**
 * 交给 SChat 去确认的那条链接：`schat://login/<token>?s=<聊天服务器>`（和 SChat 自己的 `schat://f/<uuid>?s=<host>` 同一个写法）。
 * SChat 收到后走它扫到 `SCSH:1:login:<token>` 时同一条路：resolve → 确认页（显示是哪台设备在登录）→ approve；
 * 带上服务器是让它核对：自己登的不是这台服务器时直接说清楚，别去别的服务器上 resolve
 */
fun schatLoginIntent(token: String, server: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("schat://login/$token?s=" + Uri.encode(server)))
        .setPackage(SCHAT_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
