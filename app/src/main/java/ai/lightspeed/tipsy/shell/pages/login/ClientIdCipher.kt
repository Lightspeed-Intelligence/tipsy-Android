package ai.lightspeed.tipsy.shell.pages.login

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Base64 编解码的注入口。
 *
 * ## 为什么需要它
 *
 * `android.util.Base64` 在 JVM 单测里是**空壳**（调用抛
 * `RuntimeException: Stub!`），而 `java.util.Base64` 需要 **API 26**，
 * 本工程 minSdk=24 —— 两个都不能直接用于「既能上真机又能单测」。
 *
 * 所以把这层抽出来：生产走 [AndroidBase64]，单测注入
 * `java.util.Base64` 实现（测试跑在 JVM 上，不受 minSdk 约束）。
 * 不这么做的代价是加密逻辑**完全没法单测** —— 而 AES 参数错了不会崩，
 * 只会让后端风控静默拒绝。
 */
interface Base64Codec {
    fun encodeToString(bytes: ByteArray): String
    fun decode(value: String): ByteArray
}

/** 生产实现：`android.util.Base64`，`NO_WRAP` 见 [ClientIdCipher] 注释。 */
object AndroidBase64 : Base64Codec {
    override fun encodeToString(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    override fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}

/**
 * 生成风控头 `X-Client-ID`：`Base64(iv ‖ ciphertext ‖ tag)`。
 *
 * 1:1 复刻 RN 侧 `tipsy-app/src/lib/fraud-detection/index.ts:64-93`：
 * AES-128-GCM 加密设备唯一 id，随机 96-bit IV 前置。
 *
 * ## 字节序为什么天然对齐
 *
 * RN 用 WebCrypto 的 `subtle.encrypt`，它把 **16 字节 auth tag 追加在
 * ciphertext 尾部**；Kotlin 的 `AES/GCM/NoPadding` + [GCMParameterSpec] 也是
 * 同一布局。所以两端都是 `iv(12) ‖ ciphertext ‖ tag(16)`，无需手工拼 tag。
 * 若改用 `AES/GCM` 之外的模式或自己切 tag，后端会解不出来 —— 而**报错不会
 * 提到字节序**，只会表现为风控静默拒绝。
 *
 * ## 失败为什么返回空串而不是抛
 *
 * RN 侧 `encryptDeviceId()` **没有** try/catch（已核实），抛错会把整个
 * 登录请求带崩。壳这里刻意更稳：任何加密失败都降级成空串照发，让
 * 「拿不到设备 id」不至于变成「登录页完全不能用」。后端对空值的处置由
 * 风控决定（大概率拒绝发码），但那是可诊断的业务错误，不是崩溃。
 */
object ClientIdCipher {

    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * @param deviceId 设备唯一 id（Android 侧对应 `Settings.Secure.ANDROID_ID`）
     * @param aesKeyBase64 Base64 编码的 16 字节 AES-128 密钥，来自
     *   `BuildConfig.DEVICE_ID_AES_KEY`（按渠道 × 环境注入，见 app/build.gradle）
     * @param random 可注入随机源，仅为让测试能固定 IV；生产用默认值
     * @return `Base64(iv ‖ ciphertext ‖ tag)`；任何失败返回空串
     */
    fun encrypt(
        deviceId: String,
        aesKeyBase64: String,
        random: SecureRandom = SecureRandom(),
        base64: Base64Codec = AndroidBase64,
    ): String {
        if (deviceId.isEmpty() || aesKeyBase64.isEmpty()) return ""
        return try {
            val keyBytes = base64.decode(aesKeyBase64)
            // AES-128 要求恰好 16 字节。密钥配错长度是最容易犯的错，
            // 且 Cipher 的报错（InvalidKeyException）不会说"你配的是 dev 密钥"。
            if (keyBytes.size != 16) return ""

            val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            val sealed = cipher.doFinal(deviceId.toByteArray(Charsets.UTF_8))

            // NO_WRAP：不插换行。默认的 Base64.DEFAULT 会每 76 字符插 \n，
            // 而 HTTP 头里出现换行会被 OkHttp 拒绝（且报错只说 header 非法）。
            base64.encodeToString(iv + sealed)
        } catch (e: GeneralSecurityException) {
            // 刻意不打印密钥或 deviceId
            ""
        } catch (e: IllegalArgumentException) {
            // Base64 解码失败（密钥不是合法 base64）
            ""
        }
    }
}
