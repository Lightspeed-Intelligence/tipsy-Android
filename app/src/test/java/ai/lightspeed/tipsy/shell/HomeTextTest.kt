package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `HomeText` 的对等断言（W2）。
 *
 * 这四个函数的错法都是「看起来正常但内容不对」—— 肉眼验不出来，
 * 所以每条都对着 RN 实现取真值。
 */
class HomeTextTest {

    // ── 敏感词打码 ────────────────────────────────────────

    @Test
    fun `非 GooglePlay 渠道不打码`() {
        // `maskTextWithPlatform`（func.ts:102-112）先判渠道。
        // 全渠道打码会让 APK/RuStore 用户看到无意义星号
        assertEquals("this is sex", HomeText.maskSensitiveWords("this is sex", isGooglePlay = false))
    }

    @Test
    fun `GooglePlay 渠道按词表打码`() {
        assertEquals("this is s*x", HomeText.maskSensitiveWords("this is sex", isGooglePlay = true))
    }

    @Test
    fun `词边界是完整单词 —— 不打码子串`() {
        // RN 的正则是 `\bword\b`（func.ts:86-89）。用子串替换会把正常词打花：
        // Essex → Es*x、sexuality → s*xuality
        assertEquals("Essex", HomeText.maskSensitiveWords("Essex", isGooglePlay = true))
        assertEquals("sexuality", HomeText.maskSensitiveWords("sexuality", isGooglePlay = true))
    }

    @Test
    fun `打码大小写不敏感但保留替换串原样`() {
        // 正则带 'i'，查表按小写比对（func.ts:95-97）。
        // 替换串取表里的值，不跟随原文大小写 —— RN 就是这个行为
        assertEquals("s*x", HomeText.maskSensitiveWords("SEX", isGooglePlay = true))
        assertEquals("s*x", HomeText.maskSensitiveWords("Sex", isGooglePlay = true))
    }

    @Test
    fun `含空格的词也能命中`() {
        // 词表里有 `jerk off` / `doggy style` / `butt plug` 三条含空格的
        assertEquals("je*k off", HomeText.maskSensitiveWords("jerk off", isGooglePlay = true))
        assertEquals("d*ggy style", HomeText.maskSensitiveWords("doggy style", isGooglePlay = true))
    }

    @Test
    fun `一次遍历 —— 替换结果不被再次匹配`() {
        // 逐词多趟替换会让前一趟的产物被后一趟匹配。当前词表下两种写法结果
        // 恰好相同，但这条断言钉住"一次遍历"这个语义，防止有人改成多趟
        val input = "cock and cum"
        assertEquals("c**k and c*m", HomeText.maskSensitiveWords(input, isGooglePlay = true))
    }

    // ── 简介占位符 ────────────────────────────────────────

    @Test
    fun `占位符替换 char 与 user`() {
        assertEquals(
            "Zara likes you",
            HomeText.replaceIntroductionPlaceholders("{{char}} likes {{user}}", "Zara", "en"),
        )
    }

    @Test
    fun `user 按语言取词 —— 语言码取两字母前缀`() {
        // ⚠️ 表里的键是两字母码（`zh`），而壳的 L10n.current 是 `zh-tw` 形态。
        // 不取前缀会 miss 并回落 you —— 中文用户看到英文 you 混在中文简介里
        assertEquals("你好", HomeText.replaceIntroductionPlaceholders("{{user}}好", "X", "zh-tw"))
        assertEquals("你好", HomeText.replaceIntroductionPlaceholders("{{user}}好", "X", "zh"))
    }

    @Test
    fun `表里没有的语言回落 you`() {
        // nl/cs/pl 等不在那 10 个里，RN 同样回落 'you'
        assertEquals("you", HomeText.replaceIntroductionPlaceholders("{{user}}", "X", "nl"))
    }

    @Test
    fun `占位符替换会 trim`() {
        // RN 的实现末尾有 .trim()（llm/index.ts:55）
        assertEquals("hi", HomeText.replaceIntroductionPlaceholders("  hi  ", "X", "en"))
    }

    @Test
    fun `空简介原样返回`() {
        assertEquals("", HomeText.replaceIntroductionPlaceholders("", "X", "en"))
    }

    // ── 消息数格式化 ──────────────────────────────────────

    @Test
    fun `万以下带千分位`() {
        // ⚠️ 这是 formatNumber（func.ts:73-83），**不是** formatCountMaxThreeDigits。
        // 后者对 1000 给 "1K"，前者给 "1,000" —— 挑错函数不报错，只是与 RN 对不上
        assertEquals("0", HomeText.formatMessageCount(0))
        assertEquals("999", HomeText.formatMessageCount(999))
        assertEquals("1,000", HomeText.formatMessageCount(1_000))
        assertEquals("9,999", HomeText.formatMessageCount(9_999))
    }

    @Test
    fun `万到百万用 K`() {
        assertEquals("10.0K", HomeText.formatMessageCount(10_000))
        assertEquals("12.5K", HomeText.formatMessageCount(12_500))
        assertEquals("999.5K", HomeText.formatMessageCount(999_500))
    }

    @Test
    fun `百万以上用 M`() {
        assertEquals("1.0M", HomeText.formatMessageCount(1_000_000))
        assertEquals("574.0M", HomeText.formatMessageCount(574_000_000))
    }

    // ── CDN 图片变换 ──────────────────────────────────────

    @Test
    fun `tipsy 域插入 cdn-cgi 路径`() {
        assertEquals(
            "https://img.tipsy.chat/cdn-cgi/image/width=400,quality=90,f=auto/a/b.png",
            HomeText.transformImageUrl("https://img.tipsy.chat/a/b.png"),
        )
    }

    @Test
    fun `非 tipsy 域原样返回`() {
        // 强行拼接会让第三方图挂掉
        val url = "https://cdn.example.com/a.png"
        assertEquals(url, HomeText.transformImageUrl(url))
    }

    @Test
    fun `mp4 原样返回`() {
        // 视频不能走图片变换（img.ts:34 的 MP4_EXT_RE 判断）
        val url = "https://img.tipsy.chat/a/b.mp4"
        assertEquals(url, HomeText.transformImageUrl(url))
        val withQuery = "https://img.tipsy.chat/a/b.mp4?token=1"
        assertEquals(withQuery, HomeText.transformImageUrl(withQuery))
    }

    @Test
    fun `已变换过的 URL 不再变换`() {
        // 那个负向前视（img.ts:36）挡的就是这个。少了它会拼出
        // /cdn-cgi/image/.../cdn-cgi/image/... 双重路径，CDN 返回 404
        val already = "https://img.tipsy.chat/cdn-cgi/image/width=400,quality=90,f=auto/a.png"
        assertEquals(already, HomeText.transformImageUrl(already))
    }

    @Test
    fun `空 URL 原样返回`() {
        assertEquals("", HomeText.transformImageUrl(""))
    }
}
