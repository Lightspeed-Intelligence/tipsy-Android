package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.NotificationSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import ai.lightspeed.tipsy.shell.router.AppRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NotificationSurface` 的壳侧契约与 RN 源码是否仍然一致（W4 批次 4）。
 *
 * 只验静态契约，**不替代** §9.1 设备矩阵。跨栈出口（Engagement tab 的
 * 头像/评论卡/作品图/回馈）另由 [ShellAuthProviderTest] 锁桥方法语义。
 */
class NotificationSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/NotificationSurface.tsx")
    private val letterItemFile = File("../tipsy-app/src/components/chatList/LetterItem.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val letterItemSource: String by lazy { letterItemFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }

    private val renderBody: String by lazy {
        val start = surfaceSource.indexOf("<SafeAreaProvider")
        assertTrue("找不到 NotificationSurface 微根起点，RN 结构可能大改了", start >= 0)
        surfaceSource.substring(start)
    }

    @Test
    fun `RN 契约源文件存在`() {
        assertTrue("找不到 ${surfaceFile.absolutePath}", surfaceFile.isFile)
        assertTrue("找不到 ${letterItemFile.absolutePath}", letterItemFile.isFile)
        assertTrue("找不到 ${registrationFile.absolutePath}", registrationFile.isFile)
    }

    @Test
    fun `componentName 已在 Surface 入口注册`() {
        assertEquals("NotificationSurface", NotificationSurfaceContract.COMPONENT_NAME)
        assertTrue(
            "index.surfaces.js 未注册 NotificationSurface",
            registrationSource.contains(
                "AppRegistry.registerComponent('NotificationSurface', () => NotificationSurface)",
            ),
        )
    }

    @Test
    fun `微根依赖与 RN 开标签双向一致且保持顺序`() {
        val expected = SurfaceDependencyChecklist.NOTIFICATION
            .map { it.component }
            .toMutableList()
            .apply { add(indexOf("SurfaceToastHost"), "Stack.Screen") }
        val inRn = Regex("<([A-Z][A-Za-z0-9.]*)\\b")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "NotificationSurface 微根与清单漂移（新增/删减 provider 都要两边同步）",
            expected,
            inRn,
        )
    }

    @Test
    fun `微栈挂 NotificationStack 且初始屏是 Notification`() {
        assertTrue(
            "NotificationSurface 不再挂 NotificationStackNavigator，微栈契约要重核",
            surfaceSource.contains("component={NotificationStackNavigator}"),
        )
        assertTrue(
            "初始屏不再是 Notification",
            surfaceSource.contains("screen: 'Notification'"),
        )
        // tab prop：缺省 System（props.tab ?? 'System'）
        assertTrue(
            "tab prop 的缺省分流变了（不再是 ?? 'System'）",
            surfaceSource.contains("props.tab ?? 'System'"),
        )
    }

    @Test
    fun `tab prop 键与 RN 消费一致`() {
        val props = SurfaceProps.forRoute(AppRoute.Letter(tab = "Engagement"))
        assertEquals(mapOf("tab" to "Engagement"), props)
        assertTrue(surfaceSource.contains("props.tab"))
        // 铃铛入口无参：不放键（RN 缺省 System）
        assertEquals(emptyMap<String, Any>(), SurfaceProps.forRoute(AppRoute.Letter()))
    }

    /**
     * Engagement tab 的四个跨栈出口在 RN 侧全部是 `?.()` 方法级守卫的
     * 可选桥调用 —— 壳桥方法名拼错/漏注册的表现是「点了没反应」且不报错，
     * 这里双向锁：RN 调用的方法名必须与 Android 桥注册名一致。
     * openFeedback 的调用点在详情页（letter-detail），不在 LetterItem。
     */
    @Test
    fun `跨栈出口的桥方法名与 RN 调用一致`() {
        val bridgeModule = File(
            "../tipsy-app/modules/tipsy-auth/android/src/main/java/expo/modules/tipsyauth/TipsyAuthModule.kt",
        ).readText()
        val letterDetailSource = File(
            "../tipsy-app/src/app/chatList/letter-detail.tsx",
        ).readText()
        val callSites = mapOf(
            "openComments" to letterItemSource,
            "openChatDetail" to letterItemSource,
            "openUserProfile" to letterItemSource,
            "openFeedback" to letterDetailSource,
        )
        for ((method, source) in callSites) {
            assertTrue(
                "letter 系不再调用 $method —— 出口契约变了",
                source.contains("$method?.("),
            )
            assertTrue(
                "Android 桥未注册 $method（RN 侧 ?.() 会静默降级，点了没反应）",
                bridgeModule.contains("AsyncFunction(\"$method\")"),
            )
        }
    }
}
