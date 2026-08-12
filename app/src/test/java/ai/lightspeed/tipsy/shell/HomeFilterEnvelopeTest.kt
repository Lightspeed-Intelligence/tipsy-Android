package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.home.HomeGender
import ai.lightspeed.tipsy.shell.pages.home.mergeGenderIntoEnvelopeForTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `config-persist-storage` 信封的 merge 语义（W2，方案 §4.6）。
 *
 * ## 为什么单独测这一层
 *
 * 整体覆盖信封会丢掉同一信封里其余二十多个字段（模型选择、上下文长度、
 * 已点击标签…）—— 表现为**用户的一堆设置被重置**，且不报错、不崩溃。
 * 这是本包里破坏性最大的一处写入，所以逐条钉死。
 *
 * `MmkvTokenPersistence` 那边只碰单个裸字符串 key，不涉及信封，所以没有这层风险。
 */
class HomeFilterEnvelopeTest {

    @Test
    fun `只改 gender，其余字段原样保留`() {
        val original = """
            {"state":{"gender":"Male","nsfw":true,"chatModelName":"gpt","globalContextLength":8000,
              "clickedTagsWithBadge":["a","b"],"tags":[{"id":"1","tag":"t"}]},"version":3}
        """.trimIndent()

        val merged = mergeGenderIntoEnvelopeForTest(original, HomeGender.FEMALE)!!
        val state = JSONObject(merged).getJSONObject("state")

        assertEquals("Female", state.getString("gender"))
        // 其余字段一个都不能丢
        assertTrue(state.getBoolean("nsfw"))
        assertEquals("gpt", state.getString("chatModelName"))
        assertEquals(8000, state.getInt("globalContextLength"))
        assertEquals(2, state.getJSONArray("clickedTagsWithBadge").length())
        assertEquals(1, state.getJSONArray("tags").length())
        // version 也要留着 —— Zustand 靠它决定是否跑 migrate
        assertEquals(3, JSONObject(merged).getInt("version"))
    }

    @Test
    fun `写入 NonBinary 用存储值而不是 i18n key`() {
        // 写 "Non-binary" 进去会让 RN 侧 fromStored 认不出来（它比对 HomeGenderState），
        // 表现为「设了非二元，下次进来变回全部」
        val merged = mergeGenderIntoEnvelopeForTest(
            """{"state":{"gender":"All"},"version":1}""",
            HomeGender.NON_BINARY,
        )!!
        assertEquals("NonBinary", JSONObject(merged).getJSONObject("state").getString("gender"))
    }

    @Test
    fun `信封不可读时返回 null —— 不造一个残缺信封`() {
        // 宁可丢一次筛选偏好，也不要用半个信封覆盖掉用户的全部设置
        assertNull(mergeGenderIntoEnvelopeForTest(null, HomeGender.MALE))
        assertNull(mergeGenderIntoEnvelopeForTest("", HomeGender.MALE))
        assertNull(mergeGenderIntoEnvelopeForTest("not json", HomeGender.MALE))
        // 不是 JSON 对象（数组形态）
        assertNull(mergeGenderIntoEnvelopeForTest("""[1,2]""", HomeGender.MALE))
        // 缺 state 子对象 —— 说明不是 Zustand 信封，不认
        assertNull(mergeGenderIntoEnvelopeForTest("""{"version":1}""", HomeGender.MALE))
    }

    @Test
    fun `不写 nsfw —— 它的真值在后端`() {
        // config_persist.ts:225 + 文件末尾的 useUserStore.subscribe：
        // nsfw 从 user.nsfw 单向镜像，App 不回写后端。壳写它会破坏单向流，
        // 表现为「关了 NSFW 过一会儿自己开回来」
        val merged = mergeGenderIntoEnvelopeForTest(
            """{"state":{"gender":"All","nsfw":false},"version":1}""",
            HomeGender.MALE,
        )!!
        val state = JSONObject(merged).getJSONObject("state")
        assertEquals("写入不得改动 nsfw", false, state.getBoolean("nsfw"))
    }
}
