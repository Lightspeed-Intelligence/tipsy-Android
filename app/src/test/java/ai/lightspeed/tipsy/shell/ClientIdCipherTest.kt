package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.login.Base64Codec
import ai.lightspeed.tipsy.shell.pages.login.ClientIdCipher
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `X-Client-ID` 加密的单测。
 *
 * ## 核心断言是「能解回来」而不是「等于某个常量」
 *
 * GCM 每次用随机 IV，密文不可复现，所以钉死字符串没意义。真正要证明的是
 * **字节布局与 RN 的 WebCrypto 一致**：`iv(12) ‖ ciphertext ‖ tag(16)`。
 * 验证方式是按这个布局切开、用标准 JCE 解密，能还原出原文即证明布局对。
 *
 * 布局错了不会崩、不会报错，只会让后端风控静默拒绝发码 —— 所以这层必须有测试。
 */
class ClientIdCipherTest {

    /** 单测跑在 JVM 上，可以用 API 26+ 的 java.util.Base64。 */
    private val jvmBase64 = object : Base64Codec {
        override fun encodeToString(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        override fun decode(value: String): ByteArray =
            java.util.Base64.getDecoder().decode(value)
    }

    private val key16 = ByteArray(16) { it.toByte() }
    private val keyB64 = java.util.Base64.getEncoder().encodeToString(key16)

    @Test
    fun `输出布局是 iv 加密文加 tag —— 能用标准 JCE 解回原文`() {
        val deviceId = "9774d56d682e549c"

        val out = ClientIdCipher.encrypt(deviceId, keyB64, base64 = jvmBase64)
        val raw = java.util.Base64.getDecoder().decode(out)

        // 12 字节 IV + 密文 + 16 字节 tag。deviceId 16 字节 → 共 44。
        assertEquals("总长应为 iv(12)+密文(16)+tag(16)", 44, raw.size)

        val iv = raw.copyOfRange(0, 12)
        val sealed = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), GCMParameterSpec(128, iv))
        }
        assertEquals(deviceId, String(cipher.doFinal(sealed), Charsets.UTF_8))
    }

    @Test
    fun `每次调用的 IV 不同 —— 同一 deviceId 不产生相同密文`() {
        val a = ClientIdCipher.encrypt("same-device", keyB64, base64 = jvmBase64)
        val b = ClientIdCipher.encrypt("same-device", keyB64, base64 = jvmBase64)
        assertNotEquals("IV 必须每次随机，否则可被重放识别", a, b)
    }

    @Test
    fun `IV 固定时输出可复现 —— 证明随机源确实被使用`() {
        val fixedIv = ByteArray(12) { 7 }
        val fixedRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                fixedIv.copyInto(bytes)
            }
        }
        val a = ClientIdCipher.encrypt("dev", keyB64, fixedRandom, jvmBase64)
        val b = ClientIdCipher.encrypt("dev", keyB64, fixedRandom, jvmBase64)
        assertEquals(a, b)
        // 前 12 字节应就是注入的 IV
        val raw = java.util.Base64.getDecoder().decode(a)
        assertTrue(raw.copyOfRange(0, 12).contentEquals(fixedIv))
    }

    // ── 降级路径：这些都必须返回空串而不是抛 ──────────────────
    //
    // RN 侧 encryptDeviceId() 没有 try/catch，抛错会把登录请求带崩。
    // 壳刻意更稳：加密失败降级成空串照发，不让登录页彻底不可用。

    @Test
    fun `密钥缺失时返回空串而不抛`() {
        assertEquals("", ClientIdCipher.encrypt("dev", "", base64 = jvmBase64))
    }

    @Test
    fun `deviceId 为空时返回空串`() {
        assertEquals("", ClientIdCipher.encrypt("", keyB64, base64 = jvmBase64))
    }

    @Test
    fun `密钥长度不是 16 字节时返回空串`() {
        // 典型误配：把 32 字节的 AES-256 密钥填进来
        val key32 = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        assertEquals("", ClientIdCipher.encrypt("dev", key32, base64 = jvmBase64))
    }

    @Test
    fun `密钥不是合法 base64 时返回空串`() {
        assertEquals("", ClientIdCipher.encrypt("dev", "!!!not-base64!!!", base64 = jvmBase64))
    }

    @Test
    fun `输出不含换行 —— 换行会让 OkHttp 拒绝该请求头`() {
        // 长 deviceId 触发 Base64.DEFAULT 的 76 字符折行行为（若误用）
        val longId = "a".repeat(200)
        val out = ClientIdCipher.encrypt(longId, keyB64, base64 = jvmBase64)
        assertTrue("输出不得含 \\n 或 \\r", out.none { it == '\n' || it == '\r' })
    }
}
