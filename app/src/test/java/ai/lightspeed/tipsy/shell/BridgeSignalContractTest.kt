package ai.lightspeed.tipsy.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨 Surface 刷新信号的**双向静态锁**（§2.51 建立的纪律：RN `?.()` 调用的
 * 方法名必须与 Android 桥 `AsyncFunction` 注册名一致 —— 桥方法名拼错或漏注册
 * 的表现是静默降级，「建群后列表不刷新」这类没人会报的缺陷）。
 *
 * 两个方向都锁：
 * - RN 调用点消失 → 出口契约变了，壳侧信号链成了死代码，测试红提醒清理；
 * - 桥未注册 → RN `?.()` 短路，信号永远到不了壳。
 */
class BridgeSignalContractTest {

    private val bridgeModule = File(
        "../tipsy-app/modules/tipsy-auth/android/src/main/java/expo/modules/tipsyauth/TipsyAuthModule.kt",
    ).readText()

    @Test
    fun `chattedListChanged 的 RN 调用点与桥注册一致`() {
        val callSites = listOf(
            "../tipsy-app/src/components/chatGroup/ChatGroupSettingsPanel.tsx",
            "../tipsy-app/src/app/chatList/chat-group-member-picker.tsx",
        )
        for (path in callSites) {
            assertTrue(
                "$path 不再调用 notifyChattedListChanged —— 建群刷新契约变了",
                File(path).readText().contains("notifyChattedListChanged?.("),
            )
        }
        assertTrue(
            "Android 桥未注册 notifyChattedListChanged（RN ?.() 静默降级，建群后原生列表不刷新）",
            bridgeModule.contains("AsyncFunction(\"notifyChattedListChanged\")"),
        )
    }

    @Test
    fun `createdCharactersChanged 的 RN 调用点与桥注册一致`() {
        assertTrue(
            "profileDetail.tsx 不再调用 notifyCreatedCharactersChanged —— 创建成功刷新契约变了",
            File("../tipsy-app/src/app/create/profileDetail.tsx").readText()
                .contains("notifyCreatedCharactersChanged?.("),
        )
        assertTrue(
            "Android 桥未注册 notifyCreatedCharactersChanged（创建成功后原生创作列表不刷新）",
            bridgeModule.contains("AsyncFunction(\"notifyCreatedCharactersChanged\")"),
        )
    }
}
