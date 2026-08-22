package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.recommendation.ScreenRecommendationConnectivityState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecommendationNetworkMonitorTest {

    @Test
    fun `初始未知或在线不误报 reconnect`() {
        assertFalse(ScreenRecommendationConnectivityState(null).update(true))
        assertFalse(ScreenRecommendationConnectivityState(true).update(true))
    }

    @Test
    fun `只在已知离线到在线边沿触发一次`() {
        val gate = ScreenRecommendationConnectivityState(false)

        assertFalse(gate.update(false))
        assertTrue(gate.update(true))
        assertFalse(gate.update(true))
        assertFalse(gate.update(false))
        assertTrue(gate.update(true))
    }

    @Test
    fun `当前连接状态可在线程边界读取`() {
        val state = ScreenRecommendationConnectivityState()

        assertTrue(state.isConnected() == null)
        state.update(false)
        assertFalse(state.isConnected()!!)
        state.update(true)
        assertTrue(state.isConnected()!!)
    }
}
