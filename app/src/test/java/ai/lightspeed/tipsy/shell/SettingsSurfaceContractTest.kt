package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.SettingsSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SettingsSurface` 的壳侧契约与 RN 源码是否仍然一致。
 *
 * iOS 先例把入口收成 7 个强类型 `InitialScreen` 并用一个 Surface 容器承载；
 * Android 沿用同一结构，但最终字符串和微根组件仍以当前 RN Android 分支为真值。
 * 这些测试只验证静态契约，**不替代** §9.1 的真机生命周期矩阵。
 */
class SettingsSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/SettingsSurface.tsx")
    private val navigatorFile = File("../tipsy-app/src/navigation/SettingStackNavigator.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val navigatorSource: String by lazy { navigatorFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }

    private val renderBody: String by lazy {
        val start = surfaceSource.indexOf("<SafeAreaProvider")
        assertTrue("找不到 SettingsSurface 微根起点，RN 结构可能大改了", start >= 0)
        surfaceSource.substring(start)
    }

    @Test
    fun `RN 契约源文件存在`() {
        assertTrue("找不到 ${surfaceFile.absolutePath}", surfaceFile.isFile)
        assertTrue("找不到 ${navigatorFile.absolutePath}", navigatorFile.isFile)
        assertTrue("找不到 ${registrationFile.absolutePath}", registrationFile.isFile)
    }

    @Test
    fun `componentName 已在 Surface 入口注册`() {
        assertEquals("SettingsSurface", SettingsSurfaceContract.COMPONENT_NAME)
        assertTrue(
            "index.surfaces.js 未注册 SettingsSurface",
            registrationSource.contains(
                "AppRegistry.registerComponent('SettingsSurface', () => SettingsSurface)",
            ),
        )
    }

    @Test
    fun `微根依赖与 RN 开标签双向一致且保持顺序`() {
        val expected = SurfaceDependencyChecklist.SETTINGS
            .map { it.component }
            .toMutableList()
            .apply { add(indexOf("SurfaceToastHost"), "Stack.Screen") }
        val inRn = Regex("<([A-Z][A-Za-z0-9.]*)\\b")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()

        // 双向精确比较：RN 新增 provider 时也必须同步清单，不能只验证清单 ⊆ RN。
        assertEquals("SettingsSurface 微根开标签或顺序与清单不一致", expected, inRn)
    }

    @Test
    fun `7 个强类型入口与 RN 白名单双向一致`() {
        val block = Regex(
            "const KNOWN_SCREENS = \\[([^\\]]+)\\] as const",
            RegexOption.DOT_MATCHES_ALL,
        ).find(surfaceSource)?.groupValues?.get(1)
        assertTrue("找不到 SettingsSurface.KNOWN_SCREENS", block != null)

        val inRn = Regex("'([^']+)'")
            .findAll(block.orEmpty())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "Android 强类型入口与 RN KNOWN_SCREENS 不一致",
            inRn,
            SurfaceDependencyChecklist.SETTINGS_DIRECT_SCREENS,
        )
    }

    @Test
    fun `initialScreen 从 Android 平铺 prop 接到 SettingStack 初始屏`() {
        assertEquals("initialScreen", SurfaceProps.SETTINGS_INITIAL_SCREEN)
        assertTrue(
            "RN props 不再声明 initialScreen",
            Regex("initialScreen\\?:\\s*string").containsMatchIn(surfaceSource),
        )
        assertTrue(
            "RN 不再从 props.initialScreen 做白名单归一",
            surfaceSource.contains("normalizeScreen(props.initialScreen)"),
        )
        assertTrue(
            "归一后的 initialScreen 未接到 SettingStack initialParams.screen",
            Regex(
                "initialParams=\\{\\{\\s*screen:\\s*initialScreen\\s*\\}\\}",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(renderBody),
        )
    }

    @Test
    fun `Surface 根只挂一个 SettingStack`() {
        val rootTargets = Regex("<Stack\\.Screen\\s+name=\"([^\"]+)\"")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("SettingStack"), rootTargets)
    }

    @Test
    fun `12 个设置微栈目标与 RN 注册双向一致`() {
        val inRn = Regex("<Stack\\.Screen\\s+name=\"([^\"]+)\"")
            .findAll(navigatorSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "SettingStackNavigator 目标与壳侧清单不一致",
            inRn,
            SurfaceDependencyChecklist.SETTINGS_STACK_TARGETS,
        )
    }

    @Test
    fun `每项都写了缺失后果`() {
        val noSymptom = SurfaceDependencyChecklist.SETTINGS
            .filter { it.symptomIfMissing.isBlank() }
            .map { it.component }
        assertTrue("这些项缺少『缺失后果』说明：$noSymptom", noSymptom.isEmpty())
    }

    @Test
    fun `Surface 底色与原生壳一致`() {
        // iOS SettingsSurfaceViewController 与 Android windowBackground 都用 #34212A；
        // RN theme 必须同色，避免首帧揭示或转场时闪白。
        assertTrue(
            "SettingsSurface theme 不再使用 #34212A，需重新审计双壳转场底色",
            surfaceSource.contains("background: '#34212A'"),
        )
    }
}
