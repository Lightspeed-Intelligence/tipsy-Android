package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.share.TipsyShareChannel
import ai.lightspeed.tipsy.shell.share.TipsySharePlatformOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class TipsySharePlatformOrderTest {
    @Test
    fun `默认外部顺序对齐 Screen MediaShareModal`() {
        assertEquals(
            listOf(
                TipsyShareChannel.DISCORD,
                TipsyShareChannel.INSTAGRAM,
                TipsyShareChannel.TIKTOK,
                TipsyShareChannel.X,
                TipsyShareChannel.FACEBOOK,
            ),
            TipsySharePlatformOrder.orderedExternal(emptyList()),
        )
    }

    @Test
    fun `最近点击前置且失败与否不属于排序逻辑`() {
        val afterFacebook = TipsySharePlatformOrder.recordClick(emptyList(), TipsyShareChannel.FACEBOOK)
        val afterDiscord = TipsySharePlatformOrder.recordClick(afterFacebook, TipsyShareChannel.DISCORD)

        assertEquals(
            listOf("discord", "facebook", "instagram", "tiktok", "twitter"),
            afterDiscord,
        )
    }

    @Test
    fun `X 的持久化 id 沿用 twitter 但领域枚举仍是 X`() {
        val ids = TipsySharePlatformOrder.recordClick(emptyList(), TipsyShareChannel.X)

        assertEquals("twitter", ids.first())
        assertEquals(TipsyShareChannel.X, TipsyShareChannel.fromStorageId(ids.first()))
        assertEquals("x", TipsyShareChannel.X.trackingId)
    }

    @Test
    fun `未知重复与 copy id 被过滤 缺失平台按默认顺序补齐`() {
        assertEquals(
            listOf(
                TipsyShareChannel.TIKTOK,
                TipsyShareChannel.DISCORD,
                TipsyShareChannel.INSTAGRAM,
                TipsyShareChannel.X,
                TipsyShareChannel.FACEBOOK,
            ),
            TipsySharePlatformOrder.orderedExternal(
                listOf("unknown", "tiktok", "copy_link", "tiktok", "discord"),
            ),
        )
    }

    @Test
    fun `Copy 固定第一且点击它不改变外部顺序`() {
        val current = listOf("facebook", "discord", "instagram", "tiktok", "twitter")
        assertEquals(current, TipsySharePlatformOrder.recordClick(current, TipsyShareChannel.COPY_LINK))
    }
}
