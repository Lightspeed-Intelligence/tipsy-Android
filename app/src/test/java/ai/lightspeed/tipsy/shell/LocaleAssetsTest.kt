package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.i18n.LanguageCodes
import ai.lightspeed.tipsy.shell.i18n.LocaleTable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的词条资源与代码期望是否一致（W1-P5）。
 *
 * **这个测试防的是「忘了跑导出脚本」** —— 方案 §4.8 记的 iOS 教训之一：
 * 新原生页文案没加进白名单重跑，非英文用户静默看到英文，
 * **英文环境测试发现不了**（Search 页 shipped 过一次）。
 *
 * 读文件系统而不是 `assets`：JVM 单测里没有 `AssetManager`。路径相对
 * 模块根（Gradle 单测的工作目录），与 `MergedManifestTest` 同一手法。
 */
class LocaleAssetsTest {

    private val localesDir = File("src/main/assets/locales")

    @Test
    fun `26 个 supported 语言各有一份词条表`() {
        assertTrue(
            "词条目录不存在：${localesDir.absolutePath}。" +
                "需在 tipsy-app 里跑 npm run export:shell-locales",
            localesDir.isDirectory,
        )
        val missing = LanguageCodes.SUPPORTED.filter {
            !File(localesDir, "$it.json").isFile
        }
        assertTrue(
            "缺少词条表（跑 npm run export:shell-locales）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `休眠语言不得出现在导出产物里`() {
        // ar 与 zh 都不是产品可选语言（方案 §4.8：不得把休眠文件自动提升）。
        // 导出脚本的 ACTIVE_LANGUAGES 若被误改，这条会红
        for (dormant in listOf("ar", "zh")) {
            assertTrue(
                "$dormant 不应被导出 —— 它不在 SUPPORTED_LANGUAGES 里",
                !File(localesDir, "$dormant.json").isFile,
            )
        }
    }

    @Test
    fun `导出文件数恰好等于 supported 集合大小`() {
        val actual = localesDir.listFiles { f -> f.extension == "json" }?.size ?: 0
        assertEquals(LanguageCodes.SUPPORTED.size, actual)
    }

    @Test
    fun `英文表非空且含 key 不等于 value 的词条`() {
        val en = LocaleTable.parse(File(localesDir, "en.json").readText())
        assertTrue("英文表为空", en.size > 0)
        // 存在 key≠value 的词条是「en 也必须查表」的**唯一理由**。
        // 若某次导出后这个断言不成立，说明白名单里恰好没有这类 key ——
        // 那时 L10n 拿 key 当英文也能过，会掩盖真实约束
        assertEquals("More to come", en["Currently unavailable"])
    }

    @Test
    fun `各语言表 key 集合与英文一致`() {
        // 导出脚本对所有语言用同一份 SHELL_KEYS，缺失的 key 会被跳过。
        // 某语言少 key 是**正常**的（RN locale 本身缺译文），但 key 多出来
        // 说明脚本产物不同步（比如手工编辑过），那会让行为不可预测
        val enKeys = keysOf("en")
        for (code in LanguageCodes.SUPPORTED) {
            val extra = keysOf(code) - enKeys
            assertTrue("$code.json 有英文表里没有的 key：$extra", extra.isEmpty())
        }
    }

    @Test
    fun `Screen 分享可达词条在 26 个语言里都存在`() {
        val required = setOf(
            "Share",
            "Copy Link",
            "share_message",
            "copied_go_share",
            "Unable to open link",
            "Saved",
            "No photo library permission",
            "Failed to save",
            "Failed to save image",
            "Checking content compliance",
            "Share content contains sensitive information",
            "Link copied, opening Discord...",
            "Link copied, opening TikTok...",
        )
        for (code in LanguageCodes.SUPPORTED) {
            val missing = required - keysOf(code)
            assertTrue("$code.json 缺少 Screen 分享词条：$missing", missing.isEmpty())
        }
    }

    private fun keysOf(code: String): Set<String> {
        val json = org.json.JSONObject(File(localesDir, "$code.json").readText())
        return json.keys().asSequence().toSet()
    }
}
