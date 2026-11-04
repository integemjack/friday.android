package com.integemjack.friday.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话页、助手页的列表都从上往下排（最新的在最下面；助手页不够一屏时整体贴着输入框），「贴着底」是自己跟的：
 * 在最底下时，来了新的、最后一条流式变长，就跟到底；往上翻了、或者点开了什么，就不跟，
 * 直到又滑回最底下（或点「回到最新」）。
 *
 * 点开 / 收起一行时上面的不动、下面的往下推 / 往上收——正着排的列表天然就是这样，不用事后挪。
 * （以前倒着排：哪一条变高，它和它上面的都被往上顶，只能等它长完再滚回来，晚一帧，看着就是一跳一跳的）
 * 唯一要管的是跟随：在最底下点开最后一条时还跟着底，就又把它顶上去了，所以点开 / 收起先停掉跟随
 */
@Stable
class FollowBottom {
    var following by mutableStateOf(true)
}

/** 列表页给的（别的地方没有 = 什么也不做） */
val LocalFollowBottom = staticCompositionLocalOf<FollowBottom?> { null }

/** 点开 / 收起一行之前调：先不跟底，点的那一行就留在原地 */
@Composable
fun rememberHoldPosition(): () -> Unit {
    val follow = LocalFollowBottom.current
    return remember(follow) { { follow?.following = false } }
}

/**
 * 给 [list] 接上贴底跟随（[key] 变了重新从「跟着」开始，比如换了一段会话）：
 * - 用户自己滚、停下来时：在最底下就接着跟，不在就不跟
 * - 每次排版完：跟着、又没到底（新内容长出来了）就滚到底
 */
@Composable
fun rememberFollowBottom(list: LazyListState, key: Any?): FollowBottom {
    val follow = remember(key) { FollowBottom() }
    LaunchedEffect(list, follow) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow.following = !list.canScrollForward
        }
    }
    LaunchedEffect(list, follow) {
        snapshotFlow { list.layoutInfo }.collect {
            if (follow.following && list.canScrollForward && !list.isScrollInProgress) list.scrollToEnd()
        }
    }
    return follow
}

/**
 * 滚到最后一项的「偏移」：比任何一条都高。列表排版时偏移超出那一项就往回收，
 * 收到「最后一项的底贴着列表底」为止——一步到底，长的也是看到它的结尾，不是它的头。
 * 新建列表状态时也用它（`LazyListState(最后一项, LIST_END_OFFSET)`）：第一帧就在最底下
 */
const val LIST_END_OFFSET = 1_000_000

/** 滚到最底下（最后一项的结尾贴着列表底，也就是输入框上沿） */
suspend fun LazyListState.scrollToEnd() {
    val count = layoutInfo.totalItemsCount
    if (count > 0) scrollToItem(count - 1, LIST_END_OFFSET)
}
