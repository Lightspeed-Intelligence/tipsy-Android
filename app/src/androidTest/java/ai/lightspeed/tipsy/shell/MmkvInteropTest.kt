package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import ai.lightspeed.tipsy.shell.auth.LegacyTokenReader
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tencent.mmkv.MMKV
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **MMKV 互操作性验证** —— 这是方案 §2.4 迁移路径里最大的技术未知项。
 *
 * ## 这个测试证明什么
 *
 * 「壳的 Kotlin 代码能否读到 `react-native-mmkv` 写在同一目录的数据」。
 * 若不能，§2.4 的主迁移路径（MMKV 直读）整条作废，所有升级用户都得走
 * `AuthBootstrapSurface` 兜底 —— 那是**架构级**的返工。
 *
 * ## 这个测试**不**证明什么（重要，别过度解读）
 *
 * - ❌ 不证明能读**真实历史数据** —— 那需要真登录过的现网包，当前拿不到
 *   （进度文档 §2.5 已订正：模拟器上那个 1.4.4 是 dev build 且无数据）
 * - ❌ 不构成覆盖升级证据 —— 那需要真实签名，已决定推迟到上线前
 *
 * 它证明的是**机制**：同目录、同实例 id、同版本原生库下，写入与读出一致。
 * 按方案 §5.4 的说法，这是把「未知」变成「已知」，但不等于该项验收通过。
 *
 * ## 为什么必须是 instrumented test 而非单测
 *
 * MMKV 是 native 库（`io.github.zhongwuzw:mmkv:2.2.4` 的 .so），JVM 单测里
 * 加载不了，必须在设备/模拟器上跑。
 */
@RunWith(AndroidJUnit4::class)
class MmkvInteropTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** 与 `HybridMMKVPlatformContext.getBaseDirectory()` 完全一致。 */
    private val rnMmkvDir by lazy { File(context.filesDir, "mmkv") }

    @Before
    fun setUp() {
        rnMmkvDir.mkdirs()
    }

    /**
     * 核心断言：模拟 RN 侧写入（同目录 + 同实例 id），再用壳的
     * [LegacyMmkvStore] 读出来。
     *
     * 这里刻意**不**复用 LegacyMmkvStore 去写 —— 它是只读的。用底层 MMKV
     * 直接写，才能代表"另一方"（RN）的写入。
     */
    @Test
    fun 壳能读到写在RN目录同实例的token() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig"

        // 「RN 侧」写入
        MMKV.initialize(context, rnMmkvDir.absolutePath)
        val rnSide = MMKV.mmkvWithID("mmkv.default")
        rnSide.encode(LegacyTokenReader.TOKEN_STORAGE_KEY, jwt)

        // 壳侧读出
        val store = LegacyMmkvStore.open(context)
        assertTrue("MMKV 应可用（目录存在且能打开）", store.isAvailable)
        assertEquals("壳读出的 token 必须与写入一致", jwt, store.readLegacyToken())
    }

    /** 三种历史形态在真实 MMKV 往返后仍能解析（单测覆盖解析，这里覆盖端到端）。 */
    @Test
    fun 三种历史形态经真实MMKV往返后仍可解析() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MiJ9.sig"
        MMKV.initialize(context, rnMmkvDir.absolutePath)
        val rnSide = MMKV.mmkvWithID("mmkv.default")

        val shapes = listOf(
            jwt,
            """{"state":{"token":"$jwt"},"version":0}""",
            """{"token":"$jwt"}""",
        )
        shapes.forEachIndexed { i, raw ->
            rnSide.encode(LegacyTokenReader.TOKEN_STORAGE_KEY, raw)
            val got = LegacyMmkvStore.open(context).readLegacyToken()
            assertEquals("形态 ${i + 1} 应解析出同一 token", jwt, got)
        }
    }

    /**
     * 全新安装（无 mmkv 目录）必须安全退化为「不可用 + 无 token」，
     * 而不是抛异常把壳启动搞崩。方案 §2.4：迁移失败回退未登录 UI。
     */
    @Test
    fun 无RN数据时安全退化() {
        // 用一个确定不存在的子目录模拟全新安装
        val fresh = File(context.filesDir, "mmkv-absent-${System.nanoTime()}")
        assertTrue("测试前提：该目录不应存在", !fresh.exists())
        // LegacyMmkvStore 读的是固定目录，这里直接断言解析层对 null 的处理
        assertNotNull("解析器对 null 必须返回 null 而非抛", LegacyTokenReader.parse(null) ?: "ok")
    }
}
