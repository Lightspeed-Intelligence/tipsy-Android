package ai.lightspeed.tipsy.shell.pages.home

/**
 * Home 的纯文本处理（都从 RN 移植，**都有对应单测**）。
 *
 * 抽成纯函数而不是写在 Composable 里，是为了能对着 RN 的行为逐条断言 ——
 * 这几个函数的错法都是"看起来正常但内容不对"，肉眼验不出来。
 */
internal object HomeText {

    /**
     * 敏感词打码（`utils/func.ts:91-100` 的 `maskText`）。
     *
     * ## ⚠️ **只在 Google Play 渠道生效**
     *
     * `maskTextWithPlatform`（`:102-112`）先判 `isGooglePlay`，其余渠道原样返回。
     * 三渠道包名不同（方案 §2.2），壳侧靠 `BuildConfig.DOWNLOAD_CHANNEL` 判断。
     *
     * 搞错方向的两个后果都很严重：全渠道打码 → APK/RuStore 用户看到无意义的
     * 星号；全渠道不打码 → **Google Play 审核风险**。
     *
     * ## 词表边界是 `\b`，不是子串
     *
     * RN 的正则是 `\bword\b`（`:86-89`）—— `sex` 不会匹配 `sexuality` 里的
     * 那三个字母。用子串替换会把正常词打花（"Essex" → "Es*x"）。
     *
     * ## ⚠️ 必须是**一个合并正则一次遍历**，不能逐词多趟替换
     *
     * RN 把 43 个词合成一个 `\ba\b|\bb\b|…` 的正则跑一次（`:86-89`）。
     * 逐词跑 43 趟会让**前一趟的替换结果被后一趟再匹配** —— 当前词表下
     * 两种写法结果恰好相同（打码符 `*` 是非单词字符，产生的新边界不构成
     * 表内任何词），但那是巧合，加一个词就可能分叉。一次遍历没有这个风险。
     */
    fun maskSensitiveWords(text: String, isGooglePlay: Boolean): String {
        if (!isGooglePlay || text.isEmpty()) return text
        return COMBINED_PATTERN.replace(text) { match ->
            // 大小写不敏感匹配，查表要按小写（`:95-97` 的 toLowerCase 比对）。
            // 找不到时返回原文而不是抛 —— 与 RN 的 `found ? found[1] : matched` 一致
            MASK_BY_WORD[match.value.lowercase()] ?: match.value
        }
    }

    /**
     * 角色简介的占位符替换（`lib/llm/index.ts:31-56`）。
     *
     * `{{char}}` → 角色名；`{{user}}` → 「你」的对应语言词。
     *
     * ## ⚠️ 语言参数取决于 `is_translated`
     *
     * `HomeCard.tsx:472` 传的是 `item.character.is_translated ? i18n.language : 'en'` ——
     * 未翻译的角色**恒用英文 you**，即使界面是中文。这不是 bug：简介本身是英文，
     * 中间插一个「你」会中英混排。
     *
     * ## 语言表只有 10 个词条，且键是**两字母前缀**
     *
     * `zh` 而不是 `zh-tw`/`zh-cn`。壳的 `L10n.current` 是 `zh-tw` 这种形态，
     * 直接查表会 miss 并回落到 `you` —— 必须先取前两位。
     * 表里没有的语言（如 nl/cs/pl）本来就回落 `you`，与 RN 一致。
     */
    fun replaceIntroductionPlaceholders(
        introduction: String,
        characterName: String,
        languageCode: String,
    ): String {
        if (introduction.isEmpty()) return introduction
        val prefix = languageCode.take(2).lowercase()
        val you = YOU_TRANSLATIONS[prefix] ?: "you"
        return introduction
            .replace("{{char}}", characterName)
            .replace("{{user}}", you)
            .trim()
    }

    /**
     * 消息数格式化（`utils/func.ts:73-83` 的 `formatNumber`）。
     *
     * ⚠️ **不是** `formatCountMaxThreeDigits`（`utils/formatNumbers.ts`，那个有单测
     * 且规则不同：1000 → `1K`）。卡片用的是 `formatNumber`：
     *
     * | 输入 | `formatNumber`（卡片用） | `formatCountMaxThreeDigits`（别的页面） |
     * | --- | --- | --- |
     * | 1000 | `1,000` | `1K` |
     * | 12500 | `12.5K` | `12.5K` |
     * | 999500 | `999.5K` | `1M` |
     *
     * 挑错函数不会报错，只是数字与 RN 版对不上。
     *
     * `< 10000` 走 `toLocaleString()`（带千分位）。JVM 用 `%,d` 等价，
     * 但**必须指定 Locale.US** —— 系统 locale 是德语时 `%,d` 出的是 `1.000`
     * 而 RN 的 `toLocaleString()` 在 Hermes 上默认 en-US。
     */
    fun formatMessageCount(count: Long): String = when {
        count < 10_000 -> String.format(java.util.Locale.US, "%,d", count)
        count < 1_000_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1000.0)
        else -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
    }

    /**
     * CDN 图片变换（`utils/img.ts:33-45` 的 `transformImage`）。
     *
     * 只对 `*.tipsy.chat` 域生效，且**跳过 mp4** 与已变换过的 URL。
     * 对非匹配 URL 原样返回 —— 强行拼接会让第三方图挂掉。
     */
    fun transformImageUrl(url: String, width: Int = HomeStyle.IMAGE_CDN_WIDTH, quality: Int = 90): String {
        if (url.isEmpty() || MP4_PATTERN.containsMatchIn(url)) return url
        val match = CDN_PATTERN.find(url) ?: return url
        val host = match.groupValues[1]
        val path = match.groupValues[2]
        return "$host/cdn-cgi/image/width=$width,quality=$quality,f=auto$path"
    }

    /** `.mp4` 结尾或带 query（`img.ts` 的 `MP4_EXT_RE`）。 */
    private val MP4_PATTERN = Regex("""\.mp4(?:\?|$)""", RegexOption.IGNORE_CASE)

    /**
     * `(https?://[^/]+\.tipsy\.chat)(?!.*\/cdn-cgi\/image\/)(\/.*)`（`img.ts:36`）。
     *
     * 中间那个负向前视挡的是"已经变换过的 URL"—— 少了它会拼出
     * `/cdn-cgi/image/.../cdn-cgi/image/...` 这种双重路径，CDN 返回 404。
     */
    private val CDN_PATTERN = Regex("""(https?://[^/]+\.tipsy\.chat)(?!.*/cdn-cgi/image/)(/.*)""")

    /** `lib/llm/index.ts:36-47`，10 个语言。键是两字母码。 */
    private val YOU_TRANSLATIONS = mapOf(
        "de" to "du",
        "en" to "you",
        "es" to "tú",
        "fr" to "tu",
        "it" to "tu",
        "ja" to "あなた",
        "ko" to "당신",
        "pt" to "você",
        "ru" to "ты",
        "zh" to "你",
    )

    /** `constants/common.ts:45-88` 的 `SensitiveWordsMap`，43 条，顺序照抄。 */
    private val SENSITIVE_WORDS: List<Pair<String, String>> = listOf(
        "cock" to "c**k",
        "deepthroat" to "deept****t",
        "dick" to "di*k",
        "cumshot" to "c**shot",
        "fuck" to "fu*k",
        "sperm" to "sp*rm",
        "jerk off" to "je*k off",
        "tits" to "ti*s",
        "masturbate" to "mast****te",
        "blowjob" to "blo**ob",
        "prostitute" to "pro***tute",
        "dickhead" to "d**khead",
        "boobs" to "bo*bs",
        "pussy" to "pu**y",
        "dildo" to "d**do",
        "erection" to "ere*tion",
        "foreskin" to "fo**skin",
        "handjob" to "h**djob",
        "penis" to "peni*",
        "porn" to "p*rn",
        "viagra" to "viag**",
        "vagina" to "vag*na",
        "vulva" to "v*lva",
        "orgy" to "org*",
        "sexting" to "s*xting",
        "squirt" to "squ**t",
        "testicles" to "testi***s",
        "anal" to "an*l",
        "bareback" to "bareb**k",
        "bukkake" to "bukk**e",
        "creampie" to "creamp**",
        "strap-on" to "str*p-on",
        "missionary" to "mission**y",
        "clitoris" to "clito**s",
        "doggy style" to "d*ggy style",
        "fleshlight" to "fl*shlight",
        "sex" to "s*x",
        "butt plug" to "b*tt plug",
        "wank" to "wa*k",
        "orgasm" to "org*sm",
        "cum" to "c*m",
        "prick" to "pr*ck",
        "cunt" to "cu*t",
    )

    /**
     * 合并正则，与 RN 的 `SENSITIVE_WORDS_REGEX` 同构（一次遍历，见上方说明）。
     *
     * 顶层 `val` 即预编译。43 条词 × 每张卡片两个文本字段 × 滚动重组，
     * 每次都编译会在滚动时掉帧。
     *
     * `Regex.escape` 是必要的：`strap-on` 含连字符，虽然在正则里不是元字符，
     * 但词表将来可能加入含 `.` `+` 之类的词，届时不转义会静默改变匹配范围。
     */
    private val COMBINED_PATTERN: Regex = Regex(
        SENSITIVE_WORDS.joinToString("|") { (word, _) -> """\b${Regex.escape(word)}\b""" },
        RegexOption.IGNORE_CASE,
    )

    /** 匹配到的词（小写）→ 打码串。 */
    private val MASK_BY_WORD: Map<String, String> =
        SENSITIVE_WORDS.associate { (word, masked) -> word.lowercase() to masked }
}
