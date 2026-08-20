package ai.lightspeed.tipsy.shell.pages.chatlist

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ChatList 的纯文本处理（全部从 RN 移植，都有对应单测）。
 *
 * 敏感词打码复用 `HomeText.maskSensitiveWords`（同一份词表与语义），
 * 这里不复制 —— 词表加词只该改一处。
 */
internal object ChatListText {

    /**
     * 行尾时间（`ChatListItem.tsx:326-351` 的 `formatTime`）。
     *
     * | 场景 | 格式 | 例 |
     * | --- | --- | --- |
     * | 今天 | `H:mm`（**小时不补零**，RN 用裸 `getHours()`） | `9:05` |
     * | 今年 | `MM/DD`（都补零） | `03/07` |
     * | 跨年 | `MM/DD/YY` | `03/07/25` |
     *
     * ⚠️ 恒数字格式、不走 locale —— RN 未用 dayjs/toLocaleString，别顺手「本地化」。
     *
     * @param timestampMs 毫秒（会话是 `latest_time*1000`，草稿直接是 `updatedAt`）
     * @param nowMs 注入是为了测「今天/今年」边界
     */
    fun formatRowTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }

        val hours = cal.get(Calendar.HOUR_OF_DAY)
        val minutes = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')

        val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "$hours:$minutes"

        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) return "$month/$day"

        val year = cal.get(Calendar.YEAR).toString().takeLast(2)
        return "$month/$day/$year"
    }

    /**
     * 最后消息的展示文本（`ChatListItem.tsx:359-376` 的 `displayLastMessage`）。
     *
     * game 用 `introduction`；其余 `last_message_content || greeting`，
     * 都空时回落 i18n key `No messages yet`（返回 null 让 UI 层走 LocalizedText，
     * 这里不做翻译 —— 纯函数不碰 L10n，保持可单测）。
     * cinema XML 消息先转纯文本。
     */
    fun displayLastMessage(thread: ChatThread): String? {
        if (thread.itemType == ChatThread.TYPE_GAME) {
            return thread.introduction.takeIf { it.isNotBlank() }
        }
        val content = thread.lastMessageContent?.takeIf { it.isNotBlank() }
            ?: thread.greeting?.takeIf { it.isNotBlank() }
            ?: return null
        return if (isCinemaMessage(content)) convertCinemaXmlToPlainText(content) else content
    }

    /** `lib/cinema/index.ts:67-70`：trim 后以 `<initial_script>` 开头。 */
    fun isCinemaMessage(content: String): Boolean =
        content.trim().startsWith("<initial_script>")

    /**
     * cinema XML → 纯文本（`convertCinemaXmlToMarkdown`，`lib/cinema/index.ts:102-165`）。
     *
     * 规则：`<image_prompt>`/`<options>` 整块删；`<voiceover>` 留内容；
     * `<dialog>` 的冒号后加引号（支持四种冒号，输出恒标准冒号+英文引号）；
     * 段间一个空行。任何异常回退原文（RN 的 try/catch 同义）——
     * 列表预览行只有一行高，格式错一点无所谓，丢内容才是问题。
     */
    fun convertCinemaXmlToPlainText(content: String): String {
        if (!isCinemaMessage(content)) return content
        return runCatching {
            val script = SCRIPT_PATTERN.find(content)?.groupValues?.get(1) ?: return content
            var s = script
            s = IMAGE_PROMPT_PATTERN.replace(s, "")
            s = OPTIONS_PATTERN.replace(s, "")
            s = VOICEOVER_PATTERN.replace(s) { it.groupValues[1].trim() }
            s = DIALOG_PATTERN.replace(s) { match ->
                val trimmed = match.groupValues[1].trim()
                val colonIndex = trimmed.indexOfFirst { ch -> ch in COLON_CHARS }
                if (colonIndex == -1) {
                    trimmed
                } else {
                    val name = trimmed.substring(0, colonIndex)
                    val dialogue = trimmed.substring(colonIndex + 1).trim()
                    "$name: \"$dialogue\""
                }
            }
            s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        }.getOrDefault(content)
    }

    /** `isColonChar`（`lib/cinema/index.ts:21-28`）：英文/全角/修饰字母/比例符号。 */
    private val COLON_CHARS = charArrayOf(':', '：', '꞉', '∶')

    private val SCRIPT_PATTERN =
        Regex("""<initial_script>([\s\S]*?)</initial_script>""")
    private val IMAGE_PROMPT_PATTERN =
        Regex("""<image_prompt>[\s\S]*?</image_prompt>""")
    private val OPTIONS_PATTERN = Regex("""<options>[\s\S]*?</options>""")
    private val VOICEOVER_PATTERN = Regex("""<voiceover>([\s\S]*?)</voiceover>""")
    private val DIALOG_PATTERN = Regex("""<dialog>([\s\S]*?)</dialog>""")

    /**
     * Map 卡片的时间（`formatChatGridTime`，`func.ts:322-340`；Map 卡消费点
     * `ChatItem.tsx:217`）。⚠️ 与 [formatRowTime]（Grid 行尾，恒数字格式）
     * **不是同一个函数**，别合并 —— RN 侧就是两个。
     *
     * 三分支照 iOS 端口（`ChatListFormat.formatChatGridTime`）：
     * - 今天 → 相对时间（RN dayjs `fromNow()`、iOS `RelativeDateTimeFormatter`）。
     *   ⚠️ 相对时间由 [relativeToday] **注入**：对等物是
     *   `android.icu.text.RelativeDateTimeFormatter`（API 24+，随 locale
     *   本地化），但它是 Android API —— JVM 单测里是抛异常的桩，且这类
     *   本地化文案没有 i18next 词条可拼（dayjs 的相对时间是它自带的
     *   locale 包，26 语言词表里都没有）。生产实现见
     *   `ChatListFragment.mapCardRelativeTime`
     * - 今年 → `d MMM`（dayjs `D MMM`，如 `7 Mar`）
     * - 跨年 → `MMM d, yyyy`（dayjs `ll` 的 en 形状，如 `Mar 7, 2025`）
     *
     * @param locale 月份名的 locale；生产传 `L10n` 当前语言对应 locale，
     *   测试注入固定值（同 iOS `dateFormatter` 用 `currentLocale()`）
     */
    fun formatMapCardTime(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        relativeToday: (elapsedMs: Long) -> String,
    ): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }

        val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return relativeToday(nowMs - timestampMs)

        return if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            SimpleDateFormat("d MMM", locale).format(Date(timestampMs))
        } else {
            SimpleDateFormat("MMM d, yyyy", locale).format(Date(timestampMs))
        }
    }
}
