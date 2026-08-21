package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.CreateSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CreateSurface` 的壳侧静态契约与 Android 当前 RN pin 是否一致。
 *
 * 这是进度 §2.40 遗留的“已进生产白名单但无微根机器断言”缺口。测试只补静态
 * gate，不替代 §9.1 的真机生命周期矩阵，也不把 Create 标成 production-ready。
 */
class CreateSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/CreateSurface.tsx")
    private val navigatorFile = File("../tipsy-app/src/navigation/CreateStackNavigator.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val navigatorSource: String by lazy { navigatorFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }

    private val renderBody: String by lazy {
        val start = surfaceSource.indexOf("<SafeAreaProvider")
        assertTrue("找不到 CreateSurface 微根起点，RN 结构可能大改了", start >= 0)
        surfaceSource.substring(start)
    }

    @Test
    fun `RN 契约源文件存在`() {
        assertTrue("找不到 ${surfaceFile.absolutePath}", surfaceFile.isFile)
        assertTrue("找不到 ${navigatorFile.absolutePath}", navigatorFile.isFile)
        assertTrue("找不到 ${registrationFile.absolutePath}", registrationFile.isFile)
    }

    @Test
    fun `componentName 已注册且入口前完成 tags 引导`() {
        assertEquals("CreateSurface", CreateSurfaceContract.COMPONENT_NAME)
        val registerStatement =
            "AppRegistry.registerComponent('CreateSurface', () => CreateSurface)"
        val hydrateAt = registrationSource.indexOf(
            "void useConfigPersistStore.getState().hydrateTags()",
        )
        val registerAt = registrationSource.indexOf(registerStatement)

        assertTrue("index.surfaces.js 未注册 CreateSurface", registerAt >= 0)
        assertTrue("Create 入口缺少 hydrateTags()", hydrateAt >= 0)
        assertTrue(
            "hydrateTags() 必须先于 CreateSurface 注册执行（全新安装标签否则为空）",
            hydrateAt < registerAt,
        )
    }

    @Test
    fun `微根依赖与 RN 开标签双向一致且保持顺序`() {
        val expected = SurfaceDependencyChecklist.CREATE
            .map { it.component }
            .toMutableList()
            .apply { add(indexOf("SurfaceToastHost"), "Stack.Screen") }
        val inRn = Regex("<([A-Z][A-Za-z0-9.]*)\\b")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("CreateSurface 微根开标签或顺序与清单不一致", expected, inRn)
    }

    @Test
    fun `createEnterSource 从 Android 平铺 prop 进入 RN 分流`() {
        assertEquals("createEnterSource", SurfaceProps.CREATE_ENTER_SOURCE)
        assertTrue(
            "RN props 不再声明 createEnterSource",
            Regex("createEnterSource\\?:\\s*string").containsMatchIn(surfaceSource),
        )
        assertTrue(
            "RN 不再消费 createEnterSource",
            surfaceSource.contains("normalizeCharacterTriggerSource(createEnterSource)"),
        )
        assertTrue(
            "CreateStack 不再接收 Surface 计算出的 initialParams",
            Regex("initialParams=\\{initialParams\\}").containsMatchIn(renderBody),
        )
    }

    @Test
    fun `Surface 根只挂一个 CreateStack`() {
        val rootTargets = Regex("<Stack\\.Screen\\s+name=\"([^\"]+)\"")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("CreateStack"), rootTargets)
    }

    @Test
    fun `13 个创建微栈目标与 RN 注册双向一致`() {
        val inRn = Regex("<Stack\\.Screen\\s+name=\"([^\"]+)\"")
            .findAll(navigatorSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "CreateStackNavigator 目标与壳侧清单不一致",
            inRn,
            SurfaceDependencyChecklist.CREATE_STACK_TARGETS,
        )
    }

    @Test
    fun `每项都写了缺失后果`() {
        val noSymptom = SurfaceDependencyChecklist.CREATE
            .filter { it.symptomIfMissing.isBlank() }
            .map { it.component }
        assertTrue("这些项缺少『缺失后果』说明：$noSymptom", noSymptom.isEmpty())
    }

    @Test
    fun `Surface 底色与原生壳一致`() {
        assertTrue(
            "CreateSurface theme 不再使用 #34212A，需重新审计双壳转场底色",
            surfaceSource.contains("background: '#34212A'"),
        )
    }
}
