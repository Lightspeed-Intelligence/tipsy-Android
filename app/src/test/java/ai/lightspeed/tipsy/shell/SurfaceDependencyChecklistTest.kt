package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.surface.SurfaceDependencyChecklist
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微根清单与 RN 源码是否仍然一致（W1-P9）。
 *
 * **这个测试防的是清单腐化** —— RN 侧改了 `ChatDetailSurface` 的微根（加/删一个
 * `PortalHost`），而壳的清单没跟上。那样核对表会给出虚假的安心感：
 * 逐行核对全过，但漏掉的正是新增那项。
 *
 * 直接读 submodule 源文件而不是靠人记：清单里每一项都能在 RN 源码里找到，
 * 数量也要对得上。路径相对模块根（Gradle 单测工作目录），与
 * `MergedManifestTest` / `LocaleAssetsTest` 同一手法。
 *
 * ⚠️ 这测的是「壳的清单与 RN 源码一致」，**不是**「Surface 真的能跑」——
 * 后者只有真机能验（§9.1 矩阵）。清单全绿也不代表 Surface 可用。
 */
class SurfaceDependencyChecklistTest {

    private val surfaceSource = File("../tipsy-app/src/surfaces/ChatDetailSurface.tsx")

    private val renderBody: String by lazy {
        val text = surfaceSource.readText()
        // 只看 render 返回的组件树 —— import 段也含这些名字，会让断言假绿
        val start = text.indexOf("<SafeAreaProvider")
        assertTrue("找不到微根起点，RN 侧结构可能大改了", start > 0)
        text.substring(start)
    }

    @Test
    fun `RN 源文件存在`() {
        assertTrue(
            "找不到 ${surfaceSource.absolutePath} —— submodule 未初始化？",
            surfaceSource.isFile,
        )
    }

    @Test
    fun `清单里的每个组件都在 RN 微根里`() {
        val missing = SurfaceDependencyChecklist.CHAT_DETAIL
            .map { it.component }
            .filter { component ->
                if (component.startsWith("PortalHost:")) {
                    val name = component.removePrefix("PortalHost:")
                    !renderBody.contains("<PortalHost name=\"$name\"")
                } else {
                    !renderBody.contains("<$component")
                }
            }
        assertTrue("清单列了 RN 微根里不存在的组件：$missing", missing.isEmpty())
    }

    @Test
    fun `RN 微根里的 PortalHost 没有清单外的遗漏`() {
        // 反方向断言 —— 上一条只保证「清单 ⊆ 源码」，这条保证「源码 ⊆ 清单」。
        // 少了它，RN 侧新增一个 PortalHost 时清单仍然全绿
        val inSource = Regex("<PortalHost name=\"([^\"]+)\"")
            .findAll(renderBody)
            .map { it.groupValues[1] }
            .toSet()
        val inChecklist = SurfaceDependencyChecklist.CHAT_DETAIL
            .map { it.component }
            .filter { it.startsWith("PortalHost:") }
            .map { it.removePrefix("PortalHost:") }
            .toSet()
        assertEquals("RN 微根的 PortalHost 集合与清单不一致", inSource, inChecklist)
    }

    @Test
    fun `SurfaceToastHost 必须在具名 PortalHost 群之前`() {
        // 顺序有语义：弹窗要盖在 toast 之上（对齐 App.tsx 层序）。
        // 反了的表现是「弹窗弹出来但被 toast 盖住」—— 视觉问题，测试很难抓，
        // 所以在这里把源码顺序钉死
        val toastAt = renderBody.indexOf("<SurfaceToastHost")
        val firstHostAt = renderBody.indexOf("<PortalHost name=")
        assertTrue("找不到 SurfaceToastHost", toastAt > 0)
        assertTrue("找不到具名 PortalHost", firstHostAt > 0)
        assertTrue(
            "SurfaceToastHost 应在具名 PortalHost 群之前（toast=$toastAt, host=$firstHostAt）",
            toastAt < firstHostAt,
        )
    }

    @Test
    fun `五个微栈目标都在 RN 栈里注册`() {
        val missing = SurfaceDependencyChecklist.CHAT_DETAIL_STACK_TARGETS.filter {
            !renderBody.contains("name=\"$it\"")
        }
        // 方案 §8.3：启用 Surface 前枚举内部 navigate 目标，确认要么在微栈里、
        // 要么有桥出口 —— iOS 的 RoleCardSurface 缺 CreateStack 时换头像流程死链
        assertTrue("清单列的微栈目标在 RN 栈里不存在：$missing", missing.isEmpty())
    }

    @Test
    fun `每项都写了缺失后果`() {
        // 只列组件名的清单没有价值 —— 核对的人得知道自己在看什么。
        // 这条防止后续加项时只填名字
        val noSymptom = SurfaceDependencyChecklist.CHAT_DETAIL
            .filter { it.symptomIfMissing.isBlank() }
            .map { it.component }
        assertTrue("这些项缺少「缺失后果」说明：$noSymptom", noSymptom.isEmpty())
    }

    @Test
    fun `刻意不挂的池初始化器确实不在微根里`() {
        // 方案 §4.2 记为「已接受的取舍」：GreetingVideoPlayer 对 preloadedPlayer
        // 空值有 fallbackPlayer 兜底，池仅为预加载优化。
        // 若有人"顺手修复"把它挂上，这条会红 —— 那需要先改方案文档而不是改代码
        assertTrue(
            "VideoPlayerPoolInitializer 被挂进微根了 —— 方案 §4.2 记的是刻意不挂",
            !renderBody.contains("<VideoPlayerPoolInitializer"),
        )
    }
}
