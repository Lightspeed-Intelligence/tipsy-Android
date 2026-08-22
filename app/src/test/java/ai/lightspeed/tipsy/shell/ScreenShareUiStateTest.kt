package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.share.ScreenShareUiState
import ai.lightspeed.tipsy.shell.pages.screen.share.canStartChannel
import ai.lightspeed.tipsy.shell.share.TipsyShareChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareUiStateTest {

    @Test
    fun `外部保存中 Copy 仍可执行 其它外部渠道互斥`() {
        val state = ScreenShareUiState(busyChannel = TipsyShareChannel.DISCORD)

        assertTrue(state.canStartChannel(TipsyShareChannel.COPY_LINK))
        assertFalse(state.canStartChannel(TipsyShareChannel.INSTAGRAM))
        assertFalse(state.canStartChannel(TipsyShareChannel.X))
    }

    @Test
    fun `空闲时所有渠道可执行`() {
        val state = ScreenShareUiState()

        TipsyShareChannel.entries.forEach { channel ->
            assertTrue(state.canStartChannel(channel))
        }
    }
}
