package ai.lightspeed.tipsy.shell.auth

import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * JWT payload 解析与过期判定。**逐行对齐** RN 侧 `src/lib/auth/jwt.ts`。
 *
 * 这里的每条规则都是从 RN 实现照搬的，不是"合理设计"。理由：壳接管 auth 后，
 * token 生命周期判断从 JS 移到 Native，两侧阈值不一致会产生**只在特定剩余
 * 时长窗口出现**的问题 —— 比如壳认为还能用、服务端已拒，表现为间歇 401。
 */
object Jwt {

    /**
     * 即将过期的阈值：**5 分钟**（RN `isJwtExpiringSoon`，`jwt.ts:102`）。
     *
     * 改这个值需要同时改 RN 侧，否则 OTA 把新 JS 推给老 binary 时两侧不一致。
     */
    const val EXPIRING_SOON_SECONDS = 60 * 5

    /**
     * 解析 payload。失败返回 null（RN 侧抛 `Invalid token format`，
     * 壳这里返回 null 由调用方决策 —— 避免在 auth 路径上用异常做控制流）。
     */
    fun parsePayload(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        return runCatching {
            JSONObject(String(decodeBase64Url(parts[1]), Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * base64url 解码（JWT 用 `-`/`_` 替代 `+`/`/`，且常省略 padding）。
     *
     * ## 为什么自己实现，不用平台 API
     *
     * - `android.util.Base64`：**JVM 单测里是抛异常的 stub**，会让本类完全不可单测。
     *   用 `testOptions.unitTests.returnDefaultValues = true` 绕过是方案 §5.4
     *   点名的**假绿色** —— 那会让所有未 mock 的 Android API 静默返回默认值。
     * - `java.util.Base64`：**要 API 26**，而 minSdk=24。在 Android 7 上直接
     *   `NoClassDefFoundError`，且 lint 的 NewApi 未必拦得住（本类无 @RequiresApi 上下文）。
     *
     * 剩下的选择就是自己解。逻辑很短，且让 auth 的核心判定**不依赖 Android**，
     * 可以纯 JVM 单测覆盖 —— 这本身是收益。
     */
    private fun decodeBase64Url(input: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                '=' -> break          // padding，到此为止
                '\n', '\r' -> continue // 容忍换行
                else -> throw IllegalArgumentException("非法 base64url 字符")
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * 是否**未**过期。对齐 RN `hasJwtNotExpired`（`jwt.ts:80-94`）：
     * - 无 `exp` 字段 → **视为未过期**（返回 true）
     * - 解析失败 → 视为已过期（返回 false）
     *
     * ⚠️ 注意 RN 用的是 `currentTime >= exp` 判定过期，边界上"正好到期"算过期。
     */
    fun hasNotExpired(token: String, nowSeconds: Long = nowSeconds()): Boolean {
        val payload = parsePayload(token) ?: return false
        if (!payload.has("exp") || payload.isNull("exp")) return true
        val exp = payload.optLong("exp", 0L)
        if (exp == 0L) return true // 与 RN 的 `if (payload.exp)` falsy 分支等价
        return nowSeconds < exp
    }

    /**
     * 是否即将过期（需要刷新）。对齐 RN `isJwtExpiringSoon`（`jwt.ts:97-109`）。
     *
     * ⚠️ **这个条件有个容易看漏的关键点**：RN 的判定是
     * `exp - now > 0 && exp - now < 300`，即
     * **只有「还没过期但快了」才为 true；已经过期的返回 false，不触发刷新**。
     *
     * 所以已过期 token 的处理**不走刷新路径**，而是由 [ShellTokenStore] 按
     * "有 token 但已失效"处理。照搬这条，不要"顺手修正"成 `exp - now < 300`——
     * 那会改变已过期 token 的行为，与 RN 侧分叉。
     *
     * 无 `exp` 字段 → 返回 true（RN 注释：假设即将过期）。
     * 解析失败 → 返回 false（RN catch 分支）。
     */
    fun isExpiringSoon(token: String, nowSeconds: Long = nowSeconds()): Boolean {
        val payload = parsePayload(token) ?: return false
        if (!payload.has("exp") || payload.isNull("exp")) return true
        val exp = payload.optLong("exp", 0L)
        if (exp == 0L) return true
        val remaining = exp - nowSeconds
        return remaining > 0 && remaining < EXPIRING_SOON_SECONDS
    }

    /** `sub` 字段，用于跨账号校验（generation 之外的第二道防线）。 */
    fun subject(token: String): String? {
        val payload = parsePayload(token) ?: return null
        if (!payload.has("sub") || payload.isNull("sub")) return null
        return payload.optString("sub").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
