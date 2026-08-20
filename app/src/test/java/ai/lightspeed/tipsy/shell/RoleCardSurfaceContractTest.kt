package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.RoleCardSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import ai.lightspeed.tipsy.shell.router.AppRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RoleCardSurface` 的壳侧契约与 RN 源码是否仍然一致（W4 批次 5）。
 *
 * **这行的静态 gate 重点是 CreateStack 死链**：iOS 的原始事故 ——
 * `RoleCardSurface` 缺 `CreateStack` 时换头像子流程直接死链，且
 * React Navigation 对不存在的目标是静默 no-op 不崩，只能靠用户反馈发现。
 */
class RoleCardSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/RoleCardSurface.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }

    @Test
    fun `componentName 已在 Surface 入口注册`() {
        assertEquals("RoleCardSurface", RoleCardSurfaceContract.COMPONENT_NAME)
        assertTrue(
            "index.surfaces.js 未注册 RoleCardSurface",
            registrationSource.contains(
                "AppRegistry.registerComponent('RoleCardSurface', () => RoleCardSurface)",
            ),
        )
    }

    /**
     * ⚠️ **换头像死链锁**：微栈必须同时含 EditRoleCard 与 CreateStack。
     * 少 CreateStack 的表现是「编辑角色卡里点换头像没反应」——
     * 静默 no-op，两端无报错。
     */
    @Test
    fun `微栈同时含 EditRoleCard 与 CreateStack`() {
        assertTrue(
            "RoleCardSurface 微栈缺 EditRoleCard",
            surfaceSource.contains("EditRoleCard"),
        )
        assertTrue(
            "RoleCardSurface 微栈缺 CreateStack —— 换头像子流程会死链（iOS 原始事故）",
            surfaceSource.contains("CreateStack"),
        )
    }

    @Test
    fun `profileCardId prop 与 RN 消费一致`() {
        // 空 = 新增分支（Add New），非空 = 编辑分支（拉 getProfileCard 预填）
        assertTrue(surfaceSource.contains("profileCardId"))

        val editProps = SurfaceProps.forRoute(AppRoute.RoleCard(profileCardId = "pc-1"))
        assertEquals(mapOf("profileCardId" to "pc-1"), editProps)

        val addProps = SurfaceProps.forRoute(AppRoute.RoleCard())
        assertFalse("新增分支不放键（RN 按 falsy 判分支）", addProps.containsKey("profileCardId"))
    }
}
