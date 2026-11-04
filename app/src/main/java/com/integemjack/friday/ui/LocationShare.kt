package com.integemjack.friday.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 发给助手的一次定位（输入框「＋」→「位置」）。协议里没有专门的位置字段：就当一句话经 assistant.say 发过去，
 * 坐标、精度、定位时间、地址（有的话）、地图链接都写进去，大脑看得懂、要用时自己拿坐标去查。
 *
 * 坐标是手机定位给的 WGS-84（不是高德 / 百度的偏移坐标），文字里写明了；地图链接用高德的 `coordinate=wgs84`，
 * 点开位置是对的。
 */
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    /** 精度（米）；不知道是 null */
    val accuracy: Float?,
    /** 定位的时刻（毫秒） */
    val at: Long,
    /** 反查到的地址（手机没有地理编码服务时是 null，只发坐标） */
    val address: String?,
) {
    private val coordinates: String get() = "%.6f, %.6f".format(Locale.US, latitude, longitude)

    /** 输入框上面那条提示里写的 */
    val label: String get() = address ?: coordinates

    val accuracyText: String? get() = accuracy?.let { "精度约 ${it.toInt().coerceAtLeast(1)} 米" }

    /** 真正发给助手的那段话 */
    fun toMessage(): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        val detail = listOfNotNull("WGS-84", accuracyText, "$time 定位").joinToString("，")
        return buildString {
            append("【我的位置】").append(address ?: coordinates).append('\n')
            append("坐标：").append(coordinates).append("（").append(detail).append("）").append('\n')
            append("地图：https://uri.amap.com/marker?position=")
            append("%.6f,%.6f".format(Locale.US, longitude, latitude))
            append("&coordinate=wgs84")
        }
    }
}

/** 定不了位的原因（给用户看的一句话） */
class LocationProblem(message: String) : Exception(message)

/**
 * 「＋」里的「位置」：第一次要定位权限（只给大概位置也行），然后定一次位、反查地址，
 * 结果放进 [draft]（输入框上面出一条「位置：…」，可以再打几个字一起发，也可以 × 掉）。
 */
@Composable
fun rememberLocationShare(draft: ComposerDraft): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(draft)

    fun locate() {
        val target = current
        if (target.locating) return
        target.locating = true
        scope.launch {
            runCatching { locateOnce(context) }
                .onSuccess { target.location = it }
                .onFailure { Toast.makeText(context, it.message ?: "定位失败", Toast.LENGTH_LONG).show() }
            target.locating = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else Toast.makeText(context, "要发位置得给定位权限", Toast.LENGTH_SHORT).show()
    }
    return remember {
        {
            if (hasLocationPermission(context)) {
                locate()
            } else {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 定一次位：一分钟内、够准（100 米内）的上次定位直接用；不然 GPS / 网络一起问，谁先回来用谁（最多等 15 秒），
 * 都没回来就退回上次那个。不用 Google Play 服务（很多国产手机没有），只用系统的 LocationManager。
 */
@android.annotation.SuppressLint("MissingPermission")
suspend fun locateOnce(context: Context): SharedLocation {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (!LocationManagerCompat.isLocationEnabled(manager)) {
        throw LocationProblem("手机的定位没开：到系统设置里打开「位置信息」再试")
    }
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val providers = buildList {
        if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31 && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
    }
    if (providers.isEmpty()) throw LocationProblem("这台手机现在没有能用的定位方式（GPS、网络定位都关着）")

    val last = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    val fresh = last?.takeIf { System.currentTimeMillis() - it.time < 60_000 && it.hasAccuracy() && it.accuracy <= 100f }
    val location = fresh
        ?: withTimeoutOrNull(15_000) { firstFix(context, manager, providers) }
        ?: last
        ?: throw LocationProblem("定位超时了（在室内的话到窗边再试）")
    val address = withTimeoutOrNull(5_000) { reverseGeocode(context, location) }
    return SharedLocation(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = if (location.hasAccuracy()) location.accuracy else null,
        at = if (location.time > 0) location.time else System.currentTimeMillis(),
        address = address,
    )
}

/** 几个定位方式一起问，第一个回来的有效位置就用；都回 null 就是 null */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun firstFix(context: Context, manager: LocationManager, providers: List<String>): Location? =
    suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        var remaining = providers.size
        val executor = ContextCompat.getMainExecutor(context)
        providers.forEach { provider ->
            runCatching {
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                    if (!cont.isActive) return@getCurrentLocation
                    remaining--
                    if (location != null) {
                        signal.cancel()
                        cont.resume(location)
                    } else if (remaining <= 0) {
                        cont.resume(null)
                    }
                }
            }.onFailure {
                remaining--
                if (remaining <= 0 && cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { signal.cancel() }
    }

/** 坐标 → 地址（系统的 Geocoder；没有这个服务、或者查不到就是 null） */
private suspend fun reverseGeocode(context: Context, location: Location): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
    return if (Build.VERSION.SDK_INT >= 33) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull()?.let(::addressText))
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    } else {
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.let(::addressText)
            }.getOrNull()
        }
    }
}

/** 一行地址：有整行的用整行，没有就把省市区街道拼起来 */
private fun addressText(address: Address): String? {
    address.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return it }
    val parts = listOfNotNull(
        address.adminArea, address.locality, address.subLocality, address.thoroughfare, address.subThoroughfare, address.featureName,
    ).filter { it.isNotBlank() }.distinct()
    return parts.joinToString("").ifBlank { null }
}
