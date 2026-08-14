package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.pages.settings.SettingsSource
import ai.lightspeed.tipsy.shell.pages.settings.SettingsViewModel
import ai.lightspeed.tipsy.shell.pages.settings.SupportedLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings + 语言页的编排（§2.33）。
 *
 * 重点是**两种相反的写入流**：语言是「先本地切、失败不回滚」，
 * nsfw 是「接口成功才写本地」。抄混任一个都不报错：
 * 语言写成等接口会明显卡顿；nsfw 写成乐观更新会让本地与后端在失败后不一致，
 * 而那是内容分级 —— 合规风险。
 */
class SettingsViewModelTest {

    // ── 语言列表 ────────────────────────────────────

    @Test
    fun `打开语言页拉可选列表并选中当前语言`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val vm = viewModel(api, current = { "ja" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(2, s.supportedLanguages.size)
        assertEquals("ja", s.selectedLanguage)
        assertEquals("ja", s.pendingLanguage)
        assertFalse(s.isLanguageLoading)
        assertNull(s.languageError)
    }

    @Test
    fun `列表已有时不重拉`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English")))
        val vm = viewModel(api)
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        assertEquals(1, api.languageListCalls)
    }

    @Test
    fun `重进语言页把未提交的待选态复位`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")
        // 用户没点 Done 就退出，再进来
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        assertEquals("残留的待选态要清", "en", vm.state.value.pendingLanguage)
        assertFalse(vm.state.value.isLanguageDoneEnabled)
    }

    /**
     * ⚠️ 拉取失败/空列表必须给**错误态**。
     *
     * RN 那边 `isLoading = languages.length === 0` —— 拉不到时是**永久 loading**，
     * 用户对着转圈无从判断。照抄那个是错的。
     */
    @Test
    fun `列表拉取失败给错误态而不是永久 loading`() = runTest {
        val api = FakeApi(failLanguages = true)
        val vm = viewModel(api)
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse("不能停在 loading", s.isLanguageLoading)
        assertNotNull("必须有错误文案", s.languageError)
    }

    @Test
    fun `列表返回空数组也算失败`() = runTest {
        // 空列表的页面没有任何可点项，不给提示等于死页面
        val api = FakeApi(languages = emptyList())
        val vm = viewModel(api)
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        assertFalse(vm.state.value.isLanguageLoading)
        assertNotNull(vm.state.value.languageError)
    }

    // ── Done 的两段选择态 ───────────────────────────

    @Test
    fun `未改动时 Done 不可点且不提交`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English")))
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()

        assertFalse(vm.state.value.isLanguageDoneEnabled)
        assertFalse("Done 不可点时不该提交", vm.onLanguageDone())
        advanceUntilIdle()
        assertTrue(api.setLanguageCalls.isEmpty())
    }

    @Test
    fun `选了别的语言后 Done 可点`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")

        assertTrue(vm.state.value.isLanguageDoneEnabled)
    }

    @Test
    fun `点行只改待选态不提交`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")
        advanceUntilIdle()

        assertEquals("ja", vm.state.value.pendingLanguage)
        assertEquals("en", vm.state.value.selectedLanguage)
        assertTrue("点行不该发请求", api.setLanguageCalls.isEmpty())
        assertTrue("点行不该切壳语言", api.appliedLanguages.isEmpty())
    }

    // ── ⚠️ 语言：先本地切，失败不回滚 ───────────────

    @Test
    fun `Done 先切壳语言再打接口`() = runTest {
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val gate = CompletableDeferred<Unit>()
        api.setLanguageGate = gate
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")

        assertTrue(vm.onLanguageDone())
        advanceUntilIdle()

        // 接口还挂着，但语言已经切了 —— 对齐 RN 的乐观流
        assertEquals(listOf("ja"), api.appliedLanguages)
        assertEquals("ja", vm.state.value.selectedLanguage)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("ja"), api.setLanguageCalls)
    }

    @Test
    fun `保存失败不回滚本地语言 只给错误提示`() = runTest {
        // useChangeLanguage.ts:60-72 + language.tsx:36-39：失败只 Toast
        val api = FakeApi(
            languages = listOf(lang("en", "English"), lang("ja", "日本語")),
            failSetLanguage = true,
        )
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")
        vm.onLanguageDone()
        advanceUntilIdle()

        assertEquals("本地语言已切，不还原", "ja", vm.state.value.selectedLanguage)
        assertEquals(listOf("ja"), api.appliedLanguages)
        assertEquals(SettingsViewModel.SAVE_FAILED_KEY, vm.state.value.languageError)
    }

    @Test
    fun `错误提示弹过后可清掉`() = runTest {
        val api = FakeApi(
            languages = listOf(lang("en", "English"), lang("ja", "日本語")),
            failSetLanguage = true,
        )
        val vm = viewModel(api, current = { "en" })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")
        vm.onLanguageDone()
        advanceUntilIdle()
        vm.onLanguageErrorShown()

        assertNull("不清会在下次进页面时重弹", vm.state.value.languageError)
    }

    // ── ⚠️ nsfw：接口成功才写本地（与语言相反）─────

    @Test
    fun `分级开关成功后才写本地值`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.nsfwGate = gate
        val vm = viewModel(api)
        vm.onAppear(nsfwEnabled = false)

        vm.onNsfwToggle()
        advanceUntilIdle()
        assertFalse("接口未回前不该写本地（不是乐观更新）", vm.state.value.nsfwEnabled)
        assertTrue(vm.state.value.nsfwPending)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.nsfwEnabled)
        assertFalse(vm.state.value.nsfwPending)
        assertEquals(listOf(true), api.nsfwCalls)
    }

    @Test
    fun `分级开关失败保持原值`() = runTest {
        // 内容分级写错方向是合规风险：本地显示"已开"而后端仍关不可接受
        val api = FakeApi(failNsfw = true)
        val vm = viewModel(api)
        vm.onAppear(nsfwEnabled = false)
        vm.onNsfwToggle()
        advanceUntilIdle()

        assertFalse("失败必须保持原值", vm.state.value.nsfwEnabled)
        assertFalse(vm.state.value.nsfwPending)
        assertNotNull("要给失败提示", vm.state.value.languageError)
    }

    @Test
    fun `分级开关在飞期间连点不重复发请求`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.nsfwGate = gate
        val vm = viewModel(api)
        vm.onAppear(nsfwEnabled = false)

        vm.onNsfwToggle()
        advanceUntilIdle()
        vm.onNsfwToggle()
        vm.onNsfwToggle()
        advanceUntilIdle()

        assertEquals(1, api.nsfwCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `关闭分级时发 false`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.onAppear(nsfwEnabled = true)
        vm.onNsfwToggle()
        advanceUntilIdle()

        assertEquals(listOf(false), api.nsfwCalls)
        assertFalse(vm.state.value.nsfwEnabled)
    }

    // ── 展开态 ──────────────────────────────────────

    @Test
    fun `展开收起是纯本地不发请求`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.onToggleAccountSecurity()
        advanceUntilIdle()

        assertTrue(vm.state.value.accountSecurityExpanded)
        assertEquals(0, api.languageListCalls)
        vm.onToggleAccountSecurity()
        assertFalse(vm.state.value.accountSecurityExpanded)
    }

    // ── auth 轨 ─────────────────────────────────────

    /** 换号后在飞的保存不得写错误态（那次保存属上一个账号）。 */
    @Test
    fun `换号后在飞的语言保存不写状态`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(
            languages = listOf(lang("en", "English"), lang("ja", "日本語")),
            failSetLanguage = true,
        )
        api.setLanguageGate = gate
        val vm = viewModel(api, current = { "en" }, generations = generations)
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")
        vm.onLanguageDone()
        advanceUntilIdle()

        generations.bumpAuth()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull("换号后不该写旧账号的错误态", vm.state.value.languageError)
    }

    @Test
    fun `换号后在飞的 nsfw 写入不改本地值`() = runTest {
        val generations = Generations()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi()
        api.nsfwGate = gate
        val vm = viewModel(api, generations = generations)
        vm.onAppear(nsfwEnabled = false)
        vm.onNsfwToggle()
        advanceUntilIdle()

        generations.bumpAuth()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse("换号后不该把上一个账号的分级写进来", vm.state.value.nsfwEnabled)
    }

    @Test
    fun `登录态变化复位选中态到壳当前语言`() = runTest {
        var current = "en"
        val api = FakeApi(languages = listOf(lang("en", "English"), lang("ja", "日本語")))
        val vm = viewModel(api, current = { current })
        vm.onLanguagePageAppear()
        advanceUntilIdle()
        vm.onLanguageSelect("ja")

        // 换号：新账号语言不同
        current = "de"
        vm.onAuthChanged()
        advanceUntilIdle()

        assertEquals("de", vm.state.value.selectedLanguage)
        assertEquals("de", vm.state.value.pendingLanguage)
        assertEquals("公共数据不该清", 2, vm.state.value.supportedLanguages.size)
    }

    // ── 测试脚手架 ──────────────────────────────────

    private fun TestScope.viewModel(
        api: FakeApi,
        current: () -> String = { "en" },
        generations: Generations = Generations(),
    ) = SettingsViewModel(
        api = api,
        applyLanguage = { api.appliedLanguages += it },
        currentLanguage = current,
        generations = generations,
        scope = this,
        logWarn = { _, _ -> },
    )

    private class FakeApi(
        private val languages: List<SupportedLanguage>? = null,
        private val failLanguages: Boolean = false,
        private val failSetLanguage: Boolean = false,
        private val failNsfw: Boolean = false,
    ) : SettingsSource {
        var languageListCalls = 0
        val setLanguageCalls = mutableListOf<String>()
        val nsfwCalls = mutableListOf<Boolean>()

        /** 壳侧实际应用的语言（生产是 `L10n.setLanguage`）。 */
        val appliedLanguages = mutableListOf<String>()

        var setLanguageGate: CompletableDeferred<Unit>? = null
        var nsfwGate: CompletableDeferred<Unit>? = null

        override suspend fun fetchSupportedLanguages(): List<SupportedLanguage> {
            languageListCalls++
            if (failLanguages) throw RuntimeException("languages boom")
            return languages ?: emptyList()
        }

        override suspend fun setLanguage(languageCode: String) {
            setLanguageCalls += languageCode
            setLanguageGate?.await()
            if (failSetLanguage) throw RuntimeException("set_language boom")
        }

        override suspend fun setNsfw(enabled: Boolean) {
            nsfwCalls += enabled
            nsfwGate?.await()
            if (failNsfw) throw RuntimeException("nsfw boom")
        }
    }

    private fun lang(code: String, display: String) =
        SupportedLanguage(languageCode = code, display = display)
}
