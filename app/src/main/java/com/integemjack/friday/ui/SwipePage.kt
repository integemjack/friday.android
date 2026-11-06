package com.integemjack.friday.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 松手时滑到底的那段：不回弹，接得住手指的速度 */
private val SETTLE_SPEC = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold = 0.001f)

/** 主屏跟着往左让出的比例（看着是「后面那页」，不是原地不动） */
private const val UNDER_SHIFT = 0.25f

/** 主屏全压暗时的透明度 */
private const val UNDER_DIM = 0.22f

/** 拖过屏宽的这个比例，松手就收走 */
private const val CLOSE_PAST = 0.4f

/** 子页面开到哪儿了；[rememberSwipePageState] 建，开关走 [open] / [close] */
@Stable
class SwipePageState internal constructor(private val scope: CoroutineScope) {
    /** 0 = 收着（整页在屏幕右边外面），1 = 全开。每帧都变：只在绘制阶段读，不引起重组 */
    var fraction by mutableFloatStateOf(0f)
        private set

    /** 页面摆不摆出来：开着、正在滑都算 */
    var shown by mutableStateOf(false)
        private set

    private var settling: Job? = null

    fun open() {
        shown = true
        settle(1f)
    }

    fun close() {
        if (shown) settle(0f)
    }

    /** 手指拖着：停下正在放的动画，页面直接到这个位置 */
    internal fun follow(value: Float) {
        settling?.cancel()
        fraction = value.coerceIn(0f, 1f)
    }

    /** 滑到 [target]（0 / 1），[velocity] 是接着手指的速度（每秒走多少个 [fraction]） */
    internal fun settle(target: Float, velocity: Float = 0f) {
        settling?.cancel()
        settling = scope.launch {
            animate(fraction, target, velocity, SETTLE_SPEC) { value, _ -> fraction = value }
            if (target == 0f) shown = false
        }
    }
}

@Composable
fun rememberSwipePageState(): SwipePageState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SwipePageState(scope) }
}

/**
 * 子页面的外壳：[page] 从右边滑进来盖住 [content]，手指往右拖时整页跟着手走，
 * 松手时拖过 [CLOSE_PAST] 或往右甩一下就收走，不然弹回去。整页哪儿都能拖（同侧栏）：
 * 页面里的纵向滚动由子级消费，不会走到这里。
 *
 * 系统的返回手势（屏幕左边缘划进来）也跟手：边缘那一下归系统，App 收不到触摸，要走预测式返回
 * （Android 14 起，清单里 MainActivity 开了 enableOnBackInvokedCallback）；更老的系统、返回键是松手才收。
 *
 * [content] 一直留着（助手页的列表、输入框的草稿都不动），子页面开合时往左让一点、压暗。
 */
@Composable
fun SwipePageHost(state: SwipePageState, page: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -size.width * UNDER_SHIFT * state.fraction }
                // 盖住了就不给读屏软件读
                .then(if (state.shown) Modifier.clearAndSetSemantics {} else Modifier)
        ) { content() }
        if (state.shown) {
            PredictiveBackHandler { progress ->
                val from = state.fraction
                try {
                    progress.collect { state.follow(from * (1f - it.progress)) }
                    state.close()
                } catch (e: CancellationException) {
                    // 手势划了一半又缩回去（页面已经被收走时不用管）
                    if (state.shown) state.settle(1f)
                    throw e
                }
            }
            // 压暗主屏；开合时露出来的那块主屏也别让点穿
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {}
                    .drawBehind { drawRect(Color.Black, alpha = UNDER_DIM * state.fraction) }
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * (1f - state.fraction)
                        shadowElevation = if (state.fraction < 1f) 12.dp.toPx() else 0f
                    }
                    .followSwipe(state)
            ) { page() }
        }
    }
}

/** 横向拖动带着页面走，松手看位置和速度决定收走还是弹回 */
private fun Modifier.followSwipe(state: SwipePageState): Modifier = pointerInput(state) {
    val tracker = VelocityTracker()
    val flick = 600.dp.toPx()
    detectHorizontalDragGestures(
        onDragStart = { _: Offset -> tracker.resetTracking() },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().x
            val close = when {
                velocity > flick -> true
                velocity < -flick -> false
                else -> state.fraction < 1f - CLOSE_PAST
            }
            state.settle(if (close) 0f else 1f, -velocity / size.width)
        },
        onDragCancel = { state.settle(1f) },
        onHorizontalDrag = { change, dragAmount ->
            tracker.addPointerInputChange(change)
            change.consume()
            state.follow(state.fraction - dragAmount / size.width)
        },
    )
}
