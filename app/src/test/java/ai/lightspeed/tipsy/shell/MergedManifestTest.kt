package ai.lightspeed.tipsy.shell

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * merged manifest 快照测试（方案 §5.1）。
 *
 * 为什么需要：autolinking 会把 50+ 个模块的 manifest 合并进最终产物 —— 权限、
 * exported 组件、intent scheme 都可能被**依赖悄悄引入**，而普通构建不会报错。
 * 现网三条渠道的 applicationId 也必须钉死，改错会导致覆盖升级拿不到旧数据目录。
 *
 * 这些断言针对 **merged manifest**（不是 src/main 的那份），因为只有合并结果
 * 才反映真实产物。
 *
 * 注意：测试依赖 process*Manifest 任务的产物。单独跑 `test` 时若产物不存在，
 * 用 assumeTrue 跳过而非失败 —— CI 里应在 assemble 之后跑（见方案 §5.4 的 G1 gate）。
 */
class MergedManifestTest {

    private fun manifestFor(variant: String): File? {
        val dir = File("build/intermediates/merged_manifests/$variant")
        if (!dir.isDirectory) return null
        return dir.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
    }

    /**
     * 注意：AGP 产出的 merged manifest **不带 `xmlns:android` 声明**，
     * 因此不能用 namespace-aware 解析 + `getAttributeNS` —— 那样取不到值
     * （实测 largeHeap 断言假失败）。这里按字面属性名 `android:xxx` 读取。
     */
    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.androidAttr(name: String): String? =
        getAttribute("android:$name").takeIf { it.isNotEmpty() }

    private fun Element.androidName(): String? = androidAttr("name")

    private fun Element.exported(): String? = androidAttr("exported")

    /**
     * 只取 `<application>` 的**直接**子元素。
     * `getElementsByTagName` 会递归全部后代 —— 例如 activity 内嵌的
     * `<intent-filter>` 里的元素也会被算进来，导致断言错位。
     */
    private fun components(root: Element, tag: String): List<Element> {
        val app = directChildren(root, "application").firstOrNull() ?: return emptyList()
        return directChildren(app, tag)
    }

    private fun directChildren(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { it.tagName == tag }
    }

    private fun permissions(root: Element): Set<String> {
        val nodes = root.getElementsByTagName("uses-permission")
        return (0 until nodes.length)
            .mapNotNull { (nodes.item(it) as Element).androidName() }
            .toSet()
    }

    /** 现网三条渠道的包名，改动会破坏覆盖升级（方案 §2.2 / §5.1）。 */
    @Test
    fun `applicationId 与现网一致`() {
        val expected = mapOf(
            "googlePlayDebug" to "com.tipsyturbo.app",
            "directApkDebug" to "ai.lightspeed.tipsy",
            "ruStoreDebug" to "com.tipsytavern.app",
        )
        var checked = 0
        expected.forEach { (variant, pkg) ->
            val file = manifestFor(variant) ?: return@forEach
            assertEquals("$variant 的 package 变了（会破坏覆盖升级）", pkg, parse(file).getAttribute("package"))
            checked++
        }
        assumeTrue("未找到任何 merged manifest，请先跑 assemble", checked > 0)
    }

    /**
     * 开发期工具不得进入 release。
     *
     * W0 实测：`expo-dev-launcher` 曾出现在 release runtime classpath 上，
     * 把 `androidx.compose.ui.tooling.PreviewActivity`（exported=true）带进了
     * release manifest —— 一个开发工具在生产包里对外暴露 Activity。
     */
    @Test
    fun `release 不含开发期组件`() {
        val file = manifestFor("directApkRelease")
        assumeTrue("未找到 release merged manifest", file != null)
        val root = parse(file!!)
        val forbidden = listOf(
            "androidx.compose.ui.tooling.PreviewActivity",
            "com.facebook.react.devsupport.DevSettingsActivity",
        )
        val present = components(root, "activity").mapNotNull { it.androidName() }
        forbidden.forEach { name ->
            assertTrue("release manifest 不应包含开发期组件 $name", name !in present)
        }
    }

    /** 所有 exported 组件都要显式声明，避免依赖默认值（API31+ 语义变化）。 */
    @Test
    fun `有 intent-filter 的组件必须显式声明 exported`() {
        val file = manifestFor("directApkDebug")
        assumeTrue("未找到 merged manifest", file != null)
        val root = parse(file!!)
        listOf("activity", "service", "receiver").forEach { tag ->
            components(root, tag).forEach { e ->
                if (directChildren(e, "intent-filter").isNotEmpty()) {
                    assertNotNull(
                        "${tag} ${e.androidName()} 有 intent-filter 但未声明 exported",
                        e.exported(),
                    )
                }
            }
        }
    }

    /**
     * 权限清单快照。
     *
     * 新权限往往是**依赖引入的**而非有意添加，且直接影响商店审核。
     * 这里断言几条不该出现的敏感权限；变更权限集时必须同步更新本测试与方案 §2.3。
     */
    @Test
    fun `不含已明确排除的敏感权限`() {
        val file = manifestFor("directApkRelease")
        assumeTrue("未找到 release merged manifest", file != null)
        val perms = permissions(parse(file!!))
        // withRemoveAgoraMediaProjection：本产品只用语音，不做屏幕共享
        assertTrue(
            "MEDIA_PROJECTION 权限回归了 —— 检查 Agora full-sdk→voice-sdk 替换是否失效",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" !in perms,
        )
        assertTrue(
            "不应请求精确定位权限",
            "android.permission.ACCESS_FINE_LOCATION" !in perms,
        )
    }

    /** withAndroidLargeHeap（方案 §2.3）：线上 ExoPlayer OOM 的缓解措施，漏掉是静默回归。 */
    @Test
    fun `release 保留 largeHeap`() {
        val file = manifestFor("directApkRelease")
        assumeTrue("未找到 release merged manifest", file != null)
        val app = directChildren(parse(file!!), "application").first()
        assertEquals(
            "largeHeap 丢失 —— 这是线上 OOM 缓解措施（方案 §2.3）",
            "true",
            app.androidAttr("largeHeap"),
        )
    }
}
