package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.pages.screen.ScreenSoundPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen 视频声音开关的信封解析（W4-P2）。
 *
 * 真值是 RN 的 `chat-persist-storage.state.videoSoundEnabled`，默认 `true`
 * （`chat_persist.ts:137`）。⚠️ 这是一处**只读的所有权例外**，见
 * [ScreenSoundPreference] 的类注释。
 */
class ScreenSoundPreferenceTest {

    @Test
    fun `从信封读出开声`() {
        val raw = """{"state":{"videoSoundEnabled":true,"chatBubbleColor":""},"version":0}"""
        assertTrue(ScreenSoundPreference.parse(raw))
    }

    @Test
    fun `显式存的 false 必须读成 false`() {
        // 反向验证：若这条挂了说明 parse 恒返回默认值，
        // 那么用户在 RN 侧关掉声音后进原生 Screen 页会突然出声
        val raw = """{"state":{"videoSoundEnabled":false},"version":0}"""
        assertFalse(ScreenSoundPreference.parse(raw))
    }

    @Test
    fun `缺键回落开声而不是静音`() {
        // RN 默认 videoSoundEnabled: true。回落成 false 的表现是
        // 「视频没声音」—— 用户大概不会报这个，所以必须由测试钉住
        assertTrue(ScreenSoundPreference.parse("""{"state":{},"version":0}"""))
        assertTrue(ScreenSoundPreference.parse("""{"version":0}"""))
    }

    @Test
    fun `信封不存在或损坏时回落开声`() {
        // 收集器代替 android.util.Log —— 它在 JVM 单测里是抛异常的 stub，
        // 而本工程禁止用 returnDefaultValues 绕（方案 §5.4 的「假绿色」）
        val errors = mutableListOf<Throwable>()
        val collect: (Throwable) -> Unit = { errors += it }

        assertTrue(ScreenSoundPreference.parse(null, collect))
        assertTrue(ScreenSoundPreference.parse("", collect))
        assertTrue(ScreenSoundPreference.parse("not json", collect))
        // state 是标量而非对象（RN 换 store 结构时可能出现）
        assertTrue(ScreenSoundPreference.parse("""{"state":123}""", collect))

        // 反向验证：坏 JSON 真的走了异常分支，而不是恰好被别的早退兜住
        assertEquals(1, errors.size)
    }

    @Test
    fun `不读顶层同名字段`() {
        // Zustand persist 的值一定在 state 下。顶层同名字段是别的东西，
        // 读它等于绕过信封契约 —— 与 AccountLanguageReader 同一条纪律
        assertTrue(ScreenSoundPreference.parse("""{"videoSoundEnabled":false,"state":{}}"""))
    }
}
