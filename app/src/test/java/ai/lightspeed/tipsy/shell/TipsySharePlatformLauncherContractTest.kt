package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.share.TipsyShareTargetedContract
import ai.lightspeed.tipsy.shell.share.TipsyShareTargetedDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class TipsySharePlatformLauncherContractTest {

    @Test
    fun `官方包已安装时 targeted 失败才走 scheme 与 JS Web`() {
        assertEquals(
            listOf(
                TipsyShareTargetedDestination.TARGETED_APP,
                TipsyShareTargetedDestination.SCHEME,
                TipsyShareTargetedDestination.JS_FALLBACK_WEB,
            ),
            TipsyShareTargetedContract.destinationOrder(packageInstalled = true),
        )
    }

    @Test
    fun `官方包未安装时先直达 Native default Web`() {
        assertEquals(
            listOf(
                TipsyShareTargetedDestination.NATIVE_DEFAULT_WEB,
                TipsyShareTargetedDestination.SCHEME,
                TipsyShareTargetedDestination.JS_FALLBACK_WEB,
            ),
            TipsyShareTargetedContract.destinationOrder(packageInstalled = false),
        )
    }

    @Test
    fun `targeted EXTRA TEXT 用 RN ShareIntent 的单空格拼接`() {
        assertEquals(
            "Come meet them https://tipsy.chat/reel/c1",
            TipsyShareTargetedContract.targetedExtraText(
                localizedMessage = "Come meet them",
                reelUrl = "https://tipsy.chat/reel/c1",
            ),
        )
        assertEquals(
            "https://tipsy.chat/reel/c1",
            TipsyShareTargetedContract.targetedExtraText(
                localizedMessage = "",
                reelUrl = "https://tipsy.chat/reel/c1",
            ),
        )
    }
}
