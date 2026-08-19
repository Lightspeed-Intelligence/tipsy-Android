package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.surface.EditProfileSurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EditProfileSurface` 的 Android 宿主契约与固定 RN pin 是否仍一致。
 *
 * iOS 活跃实现也是“专属 host controller + 空 initial props + 同一 RN Surface”；
 * Android 复用通用 `RNSurfaceFragment`，由本测试保留同一组件身份与依赖边界。
 * auth-scoped gate 也由本测试钉住，但静态实现**不替代** fresh-login / 跨账号 /
 * 慢请求等专属 §9.1 真机证据。
 */
class EditProfileSurfaceContractTest {

    private val surfaceFile = File("../tipsy-app/src/surfaces/EditProfileSurface.tsx")
    private val drawerFile = File("../tipsy-app/src/components/profile/EditProfileDrawer.tsx")
    private val socialDrawerFile =
        File("../tipsy-app/src/components/profile/EditProfile/SocialPlatformDrawer.tsx")
    private val registrationFile = File("../tipsy-app/index.surfaces.js")
    private val mainActivityFile =
        File("src/main/java/ai/lightspeed/tipsy/shell/MainActivity.kt")
    private val loginFragmentFile =
        File("src/main/java/ai/lightspeed/tipsy/shell/pages/login/LoginFragment.kt")
    private val applicationFile =
        File("src/main/java/ai/lightspeed/tipsy/shell/TipsyApplication.kt")

    private val surfaceSource: String by lazy { surfaceFile.readText() }
    private val drawerSource: String by lazy { drawerFile.readText() }
    private val socialDrawerSource: String by lazy { socialDrawerFile.readText() }
    private val registrationSource: String by lazy { registrationFile.readText() }
    private val mainActivitySource: String by lazy { mainActivityFile.readText() }
    private val loginFragmentSource: String by lazy { loginFragmentFile.readText() }
    private val applicationSource: String by lazy { applicationFile.readText() }

    private val renderBody: String by lazy {
        val start = surfaceSource.indexOf("<SafeAreaProvider")
        assertTrue("找不到 EditProfileSurface 微根起点，RN 结构可能大改了", start >= 0)
        surfaceSource.substring(start)
    }

    @Test
    fun `RN 契约源文件存在`() {
        for (file in listOf(
            surfaceFile,
            drawerFile,
            socialDrawerFile,
            registrationFile,
            mainActivityFile,
            loginFragmentFile,
            applicationFile,
        )) {
            assertTrue("找不到 ${file.absolutePath}", file.isFile)
        }
    }

    @Test
    fun `原生登录先发布完整用户快照再广播 RN 与 Native`() {
        assertTrue(
            "LoginFragment 必须委托 Application 的完整会话事务",
            loginFragmentSource.contains("app.establishUserSession(result.token)"),
        )
        val transaction = applicationSource.indexOf("suspend fun establishUserSession")
        val clearOldUser = applicationSource.indexOf("userStorageRepository.clear()", transaction)
        val persistToken = applicationSource.indexOf("tokenStore.onLoggedIn(token)", transaction)
        val fetchUser = applicationSource.indexOf(
            "currentUserStore.refresh(requireSharedSnapshot = true)",
            persistToken,
        )
        val notifyRn = applicationSource.indexOf(
            "TipsyAuthRegistry.notifyAuthStateChanged(\"loggedIn\", user.userId)",
            fetchUser,
        )
        val notifyNative = applicationSource.indexOf(
            "authStateHub.notifyDidLogin(user.userId)",
            notifyRn,
        )

        assertTrue("找不到 Application 会话事务", transaction >= 0)
        assertTrue("新 token 前必须清上一账号 user-storage", clearOldUser in transaction until persistToken)
        assertTrue("登录成功必须先落地 Native token", persistToken >= 0)
        assertTrue("loggedIn 前必须成功拉 /user/info 并发布共享快照", fetchUser > persistToken)
        assertTrue("完整用户发布后必须广播 RN loggedIn", notifyRn > fetchUser)
        assertTrue("RN 与 Native 登录广播顺序漂移", notifyNative > notifyRn)
    }

    @Test
    fun `componentName 已注册且由 MainActivity 复用`() {
        assertEquals("EditProfileSurface", EditProfileSurfaceContract.COMPONENT_NAME)
        assertTrue(
            "index.surfaces.js 未注册 EditProfileSurface",
            registrationSource.contains(
                "AppRegistry.registerComponent('EditProfileSurface', () => EditProfileSurface)",
            ),
        )
        assertTrue(
            "MainActivity 未用共享 component contract 预接 EditProfile",
            Regex(
                "is AppRoute\\.EditProfile\\s*->\\s*openSurface\\(" +
                    "EditProfileSurfaceContract\\.COMPONENT_NAME,\\s*route\\)",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(mainActivitySource),
        )
    }

    @Test
    fun `微根开标签与清单双向一致且保持顺序`() {
        val expected = SurfaceDependencyChecklist.EDIT_PROFILE
            .map { it.component }
            .toMutableList()
            .apply { add(indexOf("SurfaceToastHost"), "Stack.Screen") }
        val inRn = Regex("<([A-Z][A-Za-z0-9.]*)\\b")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("EditProfileSurface 微根开标签或顺序与清单不一致", expected, inRn)
    }

    @Test
    fun `root stack 只有 EditProfile 且无业务 props`() {
        val rootTargets = Regex("<Stack\\.Screen\\s+name=\"([^\"]+)\"")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(SurfaceDependencyChecklist.EDIT_PROFILE_STACK_TARGETS, rootTargets)
        assertTrue(SurfaceProps.forRoute(AppRoute.EditProfile).isEmpty())
        assertTrue("EditProfileScreen 不再挂资料主体", surfaceSource.contains("<EditProfileDrawer"))
        assertTrue(
            "Surface export 不应声明业务 props",
            surfaceSource.contains("export function EditProfileSurface()"),
        )
        assertFalse("壳不应把旧用户资料塞进 initial props", surfaceSource.contains("props.user"))
    }

    @Test
    fun `资料表单只在 auth scoped gate ready 后挂载`() {
        val gate = surfaceSource.indexOf("authScopeState.status !== 'ready'")
        val drawer = surfaceSource.indexOf("<EditProfileDrawer")

        assertTrue(
            "EditProfileSurface 必须主动建立 shell auth scope",
            surfaceSource.contains("const authScopeState = useEditProfileAuthScope(true)"),
        )
        assertTrue("未 ready 时必须先走账号门，不能渲染旧资料表单", gate >= 0)
        assertTrue("账号门必须位于资料表单挂载之前", drawer > gate)
        assertTrue(
            "loading/error 分支必须渲染 EditProfileAuthGate",
            surfaceSource.contains("<EditProfileAuthGate"),
        )
        assertTrue(
            "Drawer 必须收到已冻结并验证的 auth scope",
            surfaceSource.contains("authScope={authScopeState.scope}"),
        )
        assertTrue(
            "账号切换后 Drawer 必须能让 gate 立即失效",
            surfaceSource.contains("onAuthScopeInvalidated={authScopeState.invalidate}"),
        )
    }

    @Test
    fun `壳内走平铺内容且具名 portal 生产消费配对`() {
        assertTrue(
            "EditProfileDrawer 不再按 shell host 走平铺分支",
            drawerSource.contains("if (isShellAuthHost())"),
        )
        assertTrue(
            "shell host 分支不再直接返回 content",
            drawerSource.contains("return open ? content : null"),
        )

        val producer = Regex("portalHostName=\"([^\"]+)\"")
            .find(socialDrawerSource)?.groupValues?.get(1)
        val host = Regex("<PortalHost\\s+name=\"([^\"]+)\"")
            .find(drawerSource)?.groupValues?.get(1)
        assertEquals("EditProfileModal", producer)
        assertEquals("具名 portal 生产者与宿主必须一致", producer, host)
    }

    @Test
    fun `返回只经 guarded popSurface 且卸载会封闸`() {
        assertTrue(surfaceSource.contains("const closingRef = useRef(false)"))
        assertTrue(
            "卸载时必须封住慢保存链的迟到 pop",
            Regex(
                "useEffect\\(\\s*\\(\\) => \\(\\) => \\{\\s*" +
                    "closingRef\\.current = true",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(surfaceSource),
        )
        assertTrue(surfaceSource.contains("if (closingRef.current) return"))
        assertEquals(
            "EditProfileSurface 应只有一个桥 pop 出口",
            1,
            Regex("TipsyAuth\\?\\.popSurface\\(\\)").findAll(surfaceSource).count(),
        )
    }

    @Test
    fun `无参路由退栈后按类型解除去重`() {
        assertTrue(
            "MainActivity 缺 EditProfile 容器可见性判定",
            mainActivitySource.contains(
                "val isEditProfileSurfaceVisible = supportFragmentManager.findFragmentByTag",
            ),
        )
        assertTrue(
            "MainActivity 缺 EditProfile 容器关闭分支",
            mainActivitySource.contains("if (!isEditProfileSurfaceVisible)"),
        )
        assertTrue(
            "MainActivity 缺 EditProfile route 类型去重解除",
            Regex(
                "router\\.onDestinationClosed\\s*\\{\\s*route\\s*->\\s*" +
                    "route is AppRoute\\.EditProfile\\s*}",
            ).containsMatchIn(mainActivitySource),
        )
    }

    @Test
    fun `每项有缺失后果且底色与双壳一致`() {
        val noSymptom = SurfaceDependencyChecklist.EDIT_PROFILE
            .filter { it.symptomIfMissing.isBlank() }
            .map { it.component }
        assertTrue("这些项缺少『缺失后果』说明：$noSymptom", noSymptom.isEmpty())
        assertTrue(
            "EditProfileSurface theme 不再使用 #34212A，需重审双壳转场底色",
            surfaceSource.contains("background: '#34212A'"),
        )
    }
}
