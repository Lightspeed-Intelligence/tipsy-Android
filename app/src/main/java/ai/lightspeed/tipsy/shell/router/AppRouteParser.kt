package ai.lightspeed.tipsy.shell.router

/**
 * 把外部 URI 解析成 [AppRoute]（W1-P4）。
 *
 * ## 契约
 *
 * - 认识的路径 → 对应 [AppRoute]
 * - 不认识 / 畸形 / 空 → **返回 null，绝不抛**
 *
 * 第二条是硬要求（方案 §4.3）：「Native 收到未知 route/字段要**可诊断地拒绝或忽略，
 * 绝不崩**」。深链的来源是**外部不可信输入** —— 任意 App 或网页都能构造
 * `tipsy://` URI 拉起本应用，解析器抛异常就等于给了一个远程崩溃入口。
 *
 * ## 路径来源
 *
 * 七条路径逐字对齐 RN 的 `linking.config.screens`（实测 `src/App.tsx:445-465`）。
 * **不要"整理"路径命名** —— 现网深链、push payload、运营物料里都是这些字面量。
 */
object AppRouteParser {

    /** app scheme，实测 `app.config.js:83`。 */
    const val SCHEME = "tipsy"

    /**
     * 解析 `tipsy://<path>?<query>` 形态的深链。
     *
     * @param uriString 原始 URI。**允许为 null/空**（冷启动时 Intent 常无 data）。
     * @return 匹配的路由；无法识别返回 null。
     */
    fun parse(uriString: String?): AppRoute? {
        val raw = uriString?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val parsed = runCatching { SimpleUri.of(raw) }.getOrNull() ?: return null

        // scheme 必须匹配。**不接受任意 scheme** —— 壳只声明 tipsy://，
        // 收到别的说明有人在乱发 intent，直接忽略。
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null

        return fromPath(parsed.path, parsed.query)
    }

    /**
     * 按路径分派。**path 比较用小写且去掉首尾斜杠** ——
     * `tipsy://chat/detail`、`tipsy:///chat/detail/`、`tipsy://Chat/Detail`
     * 应视为同一条（外部构造的 URI 形态不可控）。
     */
    private fun fromPath(path: String, query: Map<String, String>): AppRoute? {
        val key = path.trim('/').lowercase()
        val characterId = query["character_id"] ?: query["characterId"]

        return when (key) {
            "profile/daily-gem-entry" -> AppRoute.DailyGemEntry
            "profile/user-balance" -> AppRoute.UserBalance
            "subscribe/page" -> AppRoute.Subscribe
            "chat/detail" -> AppRoute.ChatDetail(characterId)
            "chat/mini-phone" -> AppRoute.MiniPhoneChat(characterId)
            "chat/letter" -> AppRoute.Letter()
            "create/profile-detail" -> AppRoute.CreateProfileDetail(characterId)
            // 未知路径：返回 null 由调用方记日志并安全忽略。
            // ⚠️ 不要在这里加"猜测式"兜底（比如 startsWith("chat") → ChatDetail）——
            // 猜错会把用户送到错的页面，比什么都不做更糟。
            else -> null
        }
    }

    /**
     * 极简 URI 解析。
     *
     * 为什么不用 `android.net.Uri`：它在 JVM 单测里是**抛异常的 stub**
     * （与 `android.util.Base64` / `Log` 同类），而深链解析**必须可单测** ——
     * 它处理的是外部不可信输入，每个畸形分支都要有断言。
     * 用 `returnDefaultValues = true` 绕过是方案 §5.4 点名的假绿色。
     *
     * 也不用 `java.net.URI`：它对 `tipsy://chat/detail` 这种非标准 authority
     * 的解析结果反直觉（host="chat"、path="/detail"），还会对某些合法深链抛
     * `URISyntaxException`。自己解 20 行，行为完全可控。
     */
    internal data class SimpleUri(
        val scheme: String,
        val path: String,
        val query: Map<String, String>,
    ) {
        companion object {
            fun of(raw: String): SimpleUri {
                val schemeEnd = raw.indexOf("://")
                require(schemeEnd > 0) { "缺少 scheme 分隔符" }
                val scheme = raw.substring(0, schemeEnd)

                var rest = raw.substring(schemeEnd + 3)

                // fragment 不参与路由，丢掉
                rest = rest.substringBefore('#')

                val pathPart = rest.substringBefore('?')
                val queryPart = rest.substringAfter('?', "")

                return SimpleUri(
                    scheme = scheme,
                    path = pathPart,
                    query = parseQuery(queryPart),
                )
            }

            private fun parseQuery(q: String): Map<String, String> {
                if (q.isBlank()) return emptyMap()
                return q.split('&').mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val idx = pair.indexOf('=')
                    // 无 `=` 的裸参数（如 `?flag`）：保留 key、值为空串，
                    // 不丢掉也不当成错误 —— 外部 URI 什么都可能有
                    if (idx < 0) return@mapNotNull pair to ""
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    if (k.isBlank()) null else k to decode(v)
                }.toMap()
            }

            /** 只解 percent-encoding 与 `+`；不引入依赖。 */
            private fun decode(s: String): String {
                if ('%' !in s && '+' !in s) return s
                val out = StringBuilder(s.length)
                var i = 0
                while (i < s.length) {
                    when {
                        s[i] == '+' -> {
                            out.append(' '); i++
                        }
                        s[i] == '%' && i + 2 < s.length -> {
                            val hex = s.substring(i + 1, i + 3)
                            val b = hex.toIntOrNull(16)
                            if (b == null) {
                                // 畸形转义原样保留，不抛 —— 外部输入不该导致崩溃
                                out.append(s[i]); i++
                            } else {
                                out.append(b.toChar()); i += 3
                            }
                        }
                        else -> {
                            out.append(s[i]); i++
                        }
                    }
                }
                return out.toString()
            }
        }
    }
}
