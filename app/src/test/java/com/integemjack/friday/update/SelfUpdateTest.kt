package com.integemjack.friday.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用内更新：GitHub 接口被限流时改走网页跳转查版本（小米 17 卡在 1.0.30 的那次） */
class SelfUpdateTest {

    @Test
    fun `网页 releases latest 跳去的地址认出版本，安装包地址照 CI 的名字拼`() {
        val release = SelfUpdate.releaseFromLocation("https://github.com/integemjack/friday.android/releases/tag/v1.0.38")!!
        assertEquals("v1.0.38", release.tag)
        assertEquals("1.0.38", release.version)
        assertEquals(
            "https://github.com/integemjack/friday.android/releases/download/v1.0.38/friday-android-v1.0.38.apk",
            release.apkUrl,
        )
        assertEquals("https://github.com/integemjack/friday.android/releases/tag/v1.0.38", release.htmlUrl)
        assertEquals(0L, release.apkSize)
        // 带了参数、斜杠也认
        assertEquals("v2.0.1", SelfUpdate.releaseFromLocation("/integemjack/friday.android/releases/tag/v2.0.1/?x=1")?.tag)
        assertTrue(SelfUpdate.isNewer("1.0.30", release.version))
    }

    @Test
    fun `认不出的跳转不算`() {
        assertNull(SelfUpdate.releaseFromLocation(null))
        assertNull(SelfUpdate.releaseFromLocation(""))
        // 没有 Release 时跳回 releases 列表
        assertNull(SelfUpdate.releaseFromLocation("https://github.com/integemjack/friday.android/releases"))
        assertNull(SelfUpdate.releaseFromLocation("https://github.com/login?return_to=x"))
        assertNull(SelfUpdate.releaseFromLocation("https://github.com/integemjack/friday.android/releases/tag/"))
        assertNull(SelfUpdate.releaseFromLocation("https://github.com/integemjack/friday.android/releases/tag/nightly"))
    }

    @Test
    fun `接口回的 Release：有安装包才算`() {
        val body = """{"tag_name":"v1.0.38","body":"说明","html_url":"https://x/v1.0.38","published_at":"2026-11-25T12:00:00Z",
            "assets":[{"name":"friday-android-v1.0.38.apk","browser_download_url":"https://x/a.apk","size":35377317}]}"""
        val release = SelfUpdate.parseRelease(body)!!
        assertEquals("1.0.38", release.version)
        assertEquals(35377317L, release.apkSize)
        assertNull(SelfUpdate.parseRelease("""{"tag_name":"v1.0.39","assets":[]}"""))
        assertNull(SelfUpdate.parseRelease("""{"message":"API rate limit exceeded for 103.115.17.225."}"""))
        assertFalse(SelfUpdate.isNewer("1.0.38", "1.0.38"))
    }
}
