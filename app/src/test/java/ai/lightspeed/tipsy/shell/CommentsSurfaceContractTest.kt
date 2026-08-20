package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.CommentsSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import ai.lightspeed.tipsy.shell.router.AppRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CommentsSurface` 的壳侧契约与 RN 源码是否仍然一致（W4 批次 3）。
 *
 * iOS 先例：`.comments` 路由 → `CommentsSurfaceViewController`（复用整条
 * ChatDetail 栈、初始屏 Comments，使 CommentReport 等二级页同栈可跳）。
 * Android 同构：通用 `RNSurfaceFragment` + [CommentsSurfaceContract]。
 * 这些测试只验证静态契约，**不替代** §9.1 的设备生命周期矩阵。
 */
class CommentsSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/CommentsSurface.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }

    private val renderBody: String by lazy {
        val start = surfaceSource.indexOf("<SafeAreaProvider")
        assertTrue("找不到 CommentsSurface 微根起点，RN 结构可能大改了", start >= 0)
        surfaceSource.substring(start)
    }

    @Test
    fun `RN 契约源文件存在`() {
        assertTrue("找不到 ${surfaceFile.absolutePath}", surfaceFile.isFile)
        assertTrue("找不到 ${registrationFile.absolutePath}", registrationFile.isFile)
    }

    @Test
    fun `componentName 已在 Surface 入口注册`() {
        assertEquals("CommentsSurface", CommentsSurfaceContract.COMPONENT_NAME)
        assertTrue(
            "index.surfaces.js 未注册 CommentsSurface",
            registrationSource.contains(
                "AppRegistry.registerComponent('CommentsSurface', () => CommentsSurface)",
            ),
        )
    }

    @Test
    fun `微根依赖与 RN 开标签双向一致且保持顺序`() {
        val expected = SurfaceDependencyChecklist.COMMENTS
            .map { it.component }
            .toMutableList()
            .apply { add(indexOf("SurfaceToastHost"), "Stack.Screen") }
        val inRn = Regex("<([A-Z][A-Za-z0-9.]*)\\b")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "CommentsSurface 微根与清单漂移（新增/删减 provider 都要两边同步）",
            expected,
            inRn,
        )
    }

    @Test
    fun `微栈复用 ChatDetail 栈且初始屏是 Comments`() {
        // 复用整条 ChatDetailStackNavigator —— 微栈目标即 CHAT_DETAIL_STACK_TARGETS，
        // 评论页内部跳转（CommentReport 等）同栈可用，不存在 RoleCard 那种死链
        assertTrue(
            "CommentsSurface 不再复用 ChatDetailStackNavigator，微栈清单要重核",
            surfaceSource.contains("component={ChatDetailStackNavigator}"),
        )
        assertTrue(
            "初始屏不再是 Comments —— initialScreen 分流契约变了",
            surfaceSource.contains("screen: 'Comments'"),
        )
    }

    @Test
    fun `props 键与 RN root 消费一一对应`() {
        // RN root 消费 5 个 props（CommentsSurface.tsx:64-68）；壳的 forRoute
        // 产出必须是它们的子集且必填三项恒在
        val props = SurfaceProps.forRoute(
            AppRoute.Comments(
                targetType = 1,
                targetId = "t",
                creatorId = "c",
                commentId = "m",
                rootId = "r",
            ),
        )
        for (key in props.keys) {
            assertTrue(
                "壳下发的 prop『$key』RN root 没有消费（props.$key 零命中）",
                surfaceSource.contains("props.$key"),
            )
        }
        assertEquals(setOf("targetType", "targetId", "creatorId", "commentId", "rootId"), props.keys)
    }
}
