package com.integemjack.friday.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发给助手的那段位置文字：地址、WGS-84 坐标、精度、地图链接（高德，按 wgs84 解） */
class SharedLocationTest {

    @Test
    fun `有地址时先写地址，坐标精度和地图链接跟在后面`() {
        val text = SharedLocation(31.230416, 121.473701, 15.4f, 1_700_000_000_000L, "上海市黄浦区人民大道200号").toMessage()
        val lines = text.lines()
        assertEquals("【我的位置】上海市黄浦区人民大道200号", lines[0])
        assertTrue(lines[1], lines[1].startsWith("坐标：31.230416, 121.473701（WGS-84，精度约 15 米，"))
        assertTrue(lines[1], lines[1].endsWith(" 定位）"))
        // 高德的 position 是「经度,纬度」，坐标系写明 wgs84（手机给的不是高德的偏移坐标）
        assertEquals("地图：https://uri.amap.com/marker?position=121.473701,31.230416&coordinate=wgs84", lines[2])
    }

    @Test
    fun `没有地址就只写坐标，不知道精度就不写精度`() {
        val location = SharedLocation(-33.8688, 151.2093, null, 1_700_000_000_000L, null)
        assertEquals("-33.868800, 151.209300", location.label)
        assertEquals(null, location.accuracyText)
        val text = location.toMessage()
        assertTrue(text.startsWith("【我的位置】-33.868800, 151.209300\n坐标：-33.868800, 151.209300（WGS-84，"))
    }
}
