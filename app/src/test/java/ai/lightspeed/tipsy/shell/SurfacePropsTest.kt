package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.router.AppRoute
import ai.lightspeed.tipsy.shell.router.ChatDetailPreload
import ai.lightspeed.tipsy.shell.surface.SurfaceContract
import ai.lightspeed.tipsy.shell.surface.SurfaceProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * route → Surface 业务 props 的映射（W1-CLOSEOUT-2）。
 *
 * **这个类防的是跨仓形状漂移** —— 壳产出的 props 与 RN 组件要的 props 对不上时，
 * 两边都不报错：RN 侧 props 是 TS 类型，但 initial props 来自原生，**运行期不校验**。
 * 症状是「参数没生效」（点某个角色却进了上次会话），极难归因。
 *
 * 原实现把参数塞进嵌套 `route` Bundle，而 RN 侧 13 个 Surface **无一读 `props.route`**
 * （全仓零命中）—— 等于 `characterId` 恒为 `undefined`。这批断言让它不会再发生。
 *
 * `SurfaceProps` 返回纯 `Map` 而不是 `Bundle`，正是为了让这些断言可跑
 * （`Bundle` 在 JVM 单测里是抛异常的 stub，见 `SurfaceContractTest` 的说明）。
 */
class SurfacePropsTest {

    @Test
    fun `ChatDetail 的 characterId 平铺在顶层`() {
        val props = SurfaceProps.forRoute(AppRoute.ChatDetail("abc123"))
        assertEquals("abc123", props["characterId"])
        // 不该再出现嵌套层 —— RN 侧没有任何 Surface 读它
        assertNull(props["route"])
    }

    @Test
    fun `ChatDetail 无 characterId 时不放该 key`() {
        // RN 侧该深链参数可选（进去恢复上次会话）
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail(null)).isEmpty())
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail("")).isEmpty())
        assertTrue(SurfaceProps.forRoute(AppRoute.ChatDetail("   ")).isEmpty())
    }

    // ── ChatDetail 判定素材（P9）──────────────────────────────

    /*
     * 这一组防的是**四个素材各自的形状**。它们的共同特征是错了不报错：
     * RN 侧 initial props 运行期不校验，分流判定又用严格相等 ——
     * 症状是「某类角色从某个入口进去落错屏」，只能靠逐个入口人工点。
     */

    /**
     * `characterType` / `contentType` **必须在嵌套 `preload` 里**，不是顶层。
     *
     * `resolveInitialParams` 读的是 `preloadState.characterType`
     * （`ChatDetailSurface.tsx:377`）—— `props.characterType` 全仓零命中。
     * 平铺的表现是 html 富文本与多角色影院一律落普通聊天页。
     */
    @Test
    fun `分流素材必须进嵌套 preload 而不是顶层`() {
        val props = SurfaceProps.forRoute(
            AppRoute.ChatDetail(
                characterId = "c1",
                characterType = 1,
                contentType = 2,
            ),
        )

        assertNull("characterType 平铺在顶层 RN 读不到", props["characterType"])
        assertNull("contentType 平铺在顶层 RN 读不到", props["contentType"])

        val preload = props["preload"] as? Map<*, *>
        assertNotNull("缺少 preload —— 分流素材没有别的通道", preload)
        assertEquals(1, preload!!["characterType"])
        assertEquals(2, preload["contentType"])
    }

    /**
     * 素材必须是**数字**，不能是字符串。
     *
     * RN 按 `characterType === 1 && contentType === 2` 判 html
     * （`chat_mode_lru.ts:77`），而 `"1" === 1` 在 JS 里是 `false`。
     */
    @Test
    fun `分流素材是数字类型不是字符串`() {
        val preload = SurfaceProps.forRoute(
            AppRoute.ChatDetail("c1", characterType = 2),
        )["preload"] as Map<*, *>

        assertTrue(
            "characterType 必须是 Int —— 字符串会让 JS 的 === 判定恒假",
            preload["characterType"] is Int,
        )
    }

    /** 两个素材都缺时整个 preload 不放（空对象在 RN 侧等价于不传）。 */
    @Test
    fun `无分流素材时不放空 preload`() {
        val props = SurfaceProps.forRoute(AppRoute.ChatDetail("c1"))
        assertNull(props["preload"])
    }

    /** 只有一个素材时也要放 preload —— 缺的那个由 RN 侧 `?? state` 保旧。 */
    @Test
    fun `单个分流素材也产出 preload`() {
        val preload = SurfaceProps.forRoute(
            AppRoute.ChatDetail("c1", contentType = 2),
        )["preload"] as Map<*, *>

        assertEquals(2, preload["contentType"])
        assertNull(preload["characterType"])
    }

    /**
     * Screen 首次进入影院时，背景 URL 必须在 RN 子树首渲前到达 preload。
     * MultiCinema 把该值作为 `initialBackground` 的 useState 初值；漏传会首进黑底，
     * 第二次才靠 runtime 内详情缓存看起来恢复。
     */
    @Test
    fun `Screen 首帧素材完整进入嵌套 preload`() {
        val props = SurfaceProps.forRoute(
            AppRoute.ChatDetail(
                characterId = "cinema-1",
                chatEnterSource = AppRoute.ChatEnterSource.BIG_SCREEN,
                preload = ChatDetailPreload(
                    nickname = "Evelyn",
                    gender = "female",
                    imageUrl = "https://cdn/character.jpg",
                    faceUrl = "https://cdn/face.jpg",
                    imgPrimaryColor = "#102030",
                    nsfw = false,
                    greeting = "Hello",
                    introduction = "Intro",
                    isTranslated = true,
                    lang = "en",
                    characterType = 2,
                    contentType = 1,
                    greetingVideoUrl = "https://cdn/greeting.mp4",
                    greetingVideoCoverUrl = "https://cdn/cover.jpg",
                ),
            ),
        )

        val preload = props["preload"] as Map<*, *>
        assertEquals("Evelyn", preload["nickname"])
        assertEquals("female", preload["gender"])
        assertEquals("https://cdn/character.jpg", preload["imageUrl"])
        assertEquals("https://cdn/face.jpg", preload["faceUrl"])
        assertEquals("#102030", preload["imgPrimaryColor"])
        assertEquals(false, preload["nsfw"])
        assertEquals("Hello", preload["greeting"])
        assertEquals("Intro", preload["introduction"])
        assertEquals(true, preload["isTranslated"])
        assertEquals("en", preload["lang"])
        assertEquals(2, preload["characterType"])
        assertEquals(1, preload["contentType"])
        assertEquals("https://cdn/greeting.mp4", preload["greetingVideoUrl"])
        assertEquals("https://cdn/cover.jpg", preload["greetingVideoCoverUrl"])
        assertNull("preload 字段不能平铺到顶层", props["imageUrl"])
    }

    /**
     * `chatEnterSource` 与 `isStory` 走**顶层**（RN 侧确实读 props 那两个：
     * `:356` 与 `:378`）。`isStory` 是 Boolean 而不是字符串 ——
     * RN 用 `?? false`，字符串 `"false"` 会被当成真。
     */
    @Test
    fun `入口来源与 story 标记走顶层且类型正确`() {
        val props = SurfaceProps.forRoute(
            AppRoute.ChatDetail(
                characterId = "s1",
                chatEnterSource = AppRoute.ChatEnterSource.BIG_SCREEN,
                isStory = true,
            ),
        )

        assertEquals("big_screen", props["chatEnterSource"])
        assertEquals(true, props["isStory"])
        assertTrue("isStory 必须是 Boolean", props["isStory"] is Boolean)
    }

    /** `isStory = false` 与缺省在 RN 侧等价（`?? false`），不放该键。 */
    @Test
    fun `isStory 为假时不放该键`() {
        val props = SurfaceProps.forRoute(AppRoute.ChatDetail("c1", isStory = false))
        assertNull(props["isStory"])
    }

    /**
     * `chatEnterSource` 的取值是跨仓契约，必须落在 RN 的联合类型里
     * （`navigation/type.ts:21-26`）。
     *
     * ⚠️ 特别是**没有 `search`** —— 搜索页复用 `HomeCard`，传的是 `home`。
     * 编一个新值不报错，只是入口模式判定落到 else 分支。
     */
    @Test
    fun `入口来源取值都在 RN 的联合类型里`() {
        val rnUnion = setOf("home", "big_screen", "chat_list", "profile", "unknown")
        val shellValues = setOf(
            AppRoute.ChatEnterSource.HOME,
            AppRoute.ChatEnterSource.BIG_SCREEN,
            AppRoute.ChatEnterSource.CHAT_LIST,
            AppRoute.ChatEnterSource.PROFILE,
        )

        assertTrue(
            "壳用了 RN 不认的入口来源：${shellValues - rnUnion}",
            rnUnion.containsAll(shellValues),
        )
        assertFalse(
            "RN 的 ChatEnterSource 里没有 search —— 搜索页复用 HomeCard 传 home",
            rnUnion.contains("search"),
        )
    }

    /** mini phone 不参与影院/html 分流，故不带素材（RN `:297` 那支只读两个参数）。 */
    @Test
    fun `MiniPhone 不带分流素材`() {
        val props = SurfaceProps.forRoute(AppRoute.MiniPhoneChat("c1"))
        assertNull(props["preload"])
        assertNull(props["isStory"])
    }

    @Test
    fun `MiniPhone 与 ChatDetail 是同一 Surface 的不同初始屏`() {
        val props = SurfaceProps.forRoute(AppRoute.MiniPhoneChat("c1"))
        assertEquals("c1", props["characterId"])
        // 对齐 useChatNavigation.toChatPage 的 mini_phone 分支 —— 不传 initialScreen
        // 会落到默认的 ChatDetailPage，用户点小手机会话进到普通聊天页
        assertEquals("MiniPhoneChat", props["initialScreen"])
    }

    @Test
    fun `UserProfile 传 userId 但不传推荐归因`() {
        val props = SurfaceProps.forRoute(
            AppRoute.UserProfile(userId = "u1", recommendationContextJSON = """{"a":1}"""),
        )
        assertEquals("u1", props["userId"])
        // 归因的消费方是 W3 的原生他人主页，不经 Surface。
        // 多传一个 RN 侧不读的字段会让人误以为它有用
        assertNull(props["recommendationContextJSON"])
    }

    @Test
    fun `尚未启用的 route 不产出 props`() {
        for (route in listOf<AppRoute>(
            AppRoute.DailyGemEntry,
            AppRoute.UserBalance,
            AppRoute.Subscribe,
            AppRoute.Letter(),
            AppRoute.CreateProfileDetail("x"),
            AppRoute.GemsPurchase(),
            AppRoute.Login(),
            AppRoute.Search,
        )) {
            assertTrue(
                "${route.javaClass.simpleName} 尚无 Surface 目标",
                SurfaceProps.forRoute(route).isEmpty(),
            )
        }
    }

    // ── 与 RN 侧必填 props 的对齐断言 ─────────────────────────

    /**
     * 本类最重要的一条：编码「`ChatDetailSurface` 的 `characterId` 是必填」这个
     * 跨仓事实（`ChatDetailSurface.tsx:75` 声明为 `characterId: string`，非可选）。
     * 有人改了映射让它不再产出该 key 时这里会红。
     */
    @Test
    fun `带 characterId 的 ChatDetail 深链必须产出必填 prop`() {
        val required = setOf("characterId")
        val actual = SurfaceProps.forRoute(AppRoute.ChatDetail("real-id")).keys
        assertTrue(
            "缺少 ChatDetailSurface 必填 props：${required - actual}",
            actual.containsAll(required),
        )
    }

    @Test
    fun `业务 props 不得使用壳自有字段名`() {
        for (route in listOf<AppRoute>(
            AppRoute.ChatDetail("c"),
            AppRoute.MiniPhoneChat("c"),
            AppRoute.UserProfile("u"),
        )) {
            val clash = SurfaceProps.forRoute(route).keys
                .intersect(SurfaceContract.SHELL_OWNED_KEYS)
            assertTrue(
                "${route.javaClass.simpleName} 的 props 与壳字段撞名：$clash",
                clash.isEmpty(),
            )
        }
    }

    @Test
    fun `props 里绝不含 token 字样`() {
        // initial props 会进 Bundle，可能落入 saved instance state / ANR trace / 崩溃日志
        for (route in listOf<AppRoute>(
            AppRoute.ChatDetail("c"),
            AppRoute.MiniPhoneChat("c"),
            AppRoute.UserProfile("u"),
        )) {
            val keys = SurfaceProps.forRoute(route).keys
            assertFalse(
                "props 含疑似凭据字段：$keys",
                keys.any { it.contains("token", true) || it.contains("jwt", true) },
            )
        }
    }

    // ── 撞名守卫本身 ──────────────────────────────────────────

    @Test
    fun `撞名时抛而不是静默覆盖`() {
        for (shellKey in SurfaceContract.SHELL_OWNED_KEYS) {
            try {
                SurfaceContract.assertNoShellKeyClash(setOf("characterId", shellKey))
                fail("与壳字段 `$shellKey` 撞名应当抛")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "异常消息应指出撞的是哪个 key，实际：${expected.message}",
                    expected.message?.contains(shellKey) == true,
                )
            }
        }
    }

    /**
     * `SettingsSubScreen` → 平铺的 `initialScreen`（§2.33）。
     *
     * ⚠️ 值必须落在 RN 的 `KNOWN_SCREENS` 里 —— 传别的值 RN 会**静默兜底
     * `Feedback`**（`normalizeScreen`），表现为「点安全设置进了反馈页」，
     * 两端都不报错。行级的白名单断言在 `SettingsRowTest`。
     */
    @Test
    fun `SettingsSubScreen 产出平铺 initialScreen`() {
        val props = SurfaceProps.forRoute(
            AppRoute.SettingsSubScreen(AppRoute.SettingsSubScreen.Screen.SECURITY),
        )
        assertEquals(mapOf("initialScreen" to "Security"), props)
    }

    /** 列表本体是原生页，不经 Surface props（传了说明走错路径）。 */
    @Test
    fun `Settings 列表本体不产出 props`() {
        assertTrue(SurfaceProps.forRoute(AppRoute.Settings).isEmpty())
    }

    /** EditProfile 的业务真值由 RN auth-scoped bootstrap 拉取，壳不得透传旧资料。 */
    @Test
    fun `EditProfile 不携带业务 props`() {
        assertTrue(SurfaceProps.forRoute(AppRoute.EditProfile).isEmpty())
    }

    // ── Create（Tab3，W4）─────────────────────────────────────

    /**
     * `Create` → 平铺的 `createEnterSource`（`CreateSurface.tsx:25`）。
     *
     * 默认值必须是 `normalizeCharacterTriggerSource`
     * （`characterCreateAnalytics.ts:106-122`）认识的值 —— 不认识的会归一成
     * `null` 再被 Surface 的 `|| 'tab_bar_plus'` 兜底，表现是**归因静默串到
     * Tab 入口**而不报错。
     */
    @Test
    fun `Create 产出平铺 createEnterSource`() {
        val props = SurfaceProps.forRoute(AppRoute.Create())
        assertEquals(mapOf("createEnterSource" to "tab_bar_plus"), props)
        assertNull("不该出现嵌套层", props["route"])
    }

    /**
     * 壳**不传**目标屏与 triggerSource —— `CreateSurface` 自决
     * （`CreateSurface.tsx:113-135` 的 `initialParams`）。
     *
     * 壳再传一份就是把分流复刻成两份（§2.30 纪律，ChatDetail 同理）。
     * 这条断言防的是「照抄 RN `TabNavigator.tsx:425` 那四个参数」。
     */
    @Test
    fun `Create 不传目标屏与 triggerSource`() {
        val props = SurfaceProps.forRoute(AppRoute.Create())
        for (key in listOf("screen", "from", "triggerSource", "operationType")) {
            assertNull("$key 应由 CreateSurface 自决，壳不得传", props[key])
        }
    }

    /** 取值来自 `CreateEnterSource` 常量，不硬编码字面量。 */
    @Test
    fun `Create 的 enterSource 可覆盖`() {
        val props = SurfaceProps.forRoute(
            AppRoute.Create(AppRoute.CreateEnterSource.DRAFT_BOX),
        )
        assertEquals("draft_box", props["createEnterSource"])
    }

    // ── P5：EditCharacter（CreateSurface 编辑态）────────────

    @Test
    fun `EditCharacter 产出结构化 editCharacter 对象与兜底 id`() {
        val props = SurfaceProps.forRoute(
            AppRoute.EditCharacter(
                characterJson = """{"character_id":"c1","nickname":"n",
                    "custom_prompt":"keep","tags":["a","b"],
                    "world_books":[{"book_id":"w1"}],"conversation_style":null}""",
                characterId = "c1",
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val edit = props["editCharacter"] as Map<String, Any>
        assertEquals("c1", edit["character_id"])
        // 模型没建模的字段必须还在 —— 这是 editCharacter 存在的全部理由
        assertEquals("keep", edit["custom_prompt"])
        assertEquals(listOf("a", "b"), edit["tags"])
        @Suppress("UNCHECKED_CAST")
        val books = edit["world_books"] as List<Map<String, Any>>
        assertEquals("w1", books.single()["book_id"])
        // 显式 null 不能丢（键缺失在 zustand 里语义不同）
        assertTrue("conversation_style 必须保留", edit.containsKey("conversation_style"))
        assertEquals("兜底 id 同时携带", "c1", props["editCharacterId"])
    }

    @Test
    fun `EditCharacter 的 JSON 坏掉时退化成仅 id`() {
        // RN 走 getCharacterAuth 有损兜底 —— 仍是编辑态，不会错落创建态
        val props = SurfaceProps.forRoute(
            AppRoute.EditCharacter(characterJson = "not-json{", characterId = "c1"),
        )
        assertFalse("坏 JSON 不产出 editCharacter", props.containsKey("editCharacter"))
        assertEquals("c1", props["editCharacterId"])
    }

    @Test
    fun `EditCharacter 不带 createEnterSource`() {
        // triggerSource='cha_edit' 由 Surface 从 isEdit 自推（CreateSurface.tsx:80-82），
        // 壳传了就是把分流复刻成两份（§2.30 纪律）
        val props = SurfaceProps.forRoute(
            AppRoute.EditCharacter(characterJson = """{"character_id":"c1"}""", characterId = "c1"),
        )
        assertFalse(props.containsKey("createEnterSource"))
    }

    // ── W4 批次 3：Comments ─────────────────────────

    @Test
    fun `Comments 的 targetType 是数字且可选定位参数只在有值时下发`() {
        // props 形状照 iOS CommentsSurfaceViewController:56-62（camelCase、
        // targetType Int）；RN root 里 String(props.targetType) 归一
        val full = SurfaceProps.forRoute(
            AppRoute.Comments(
                targetType = 1,
                targetId = "char-1",
                creatorId = "u9",
                commentId = "cm-5",
                rootId = "rt-2",
            ),
        )
        assertEquals(1, full["targetType"])
        assertEquals("char-1", full["targetId"])
        assertEquals("u9", full["creatorId"])
        assertEquals("cm-5", full["commentId"])
        assertEquals("rt-2", full["rootId"])

        val minimal = SurfaceProps.forRoute(
            AppRoute.Comments(targetType = 1, targetId = "char-1"),
        )
        // creatorId 恒下发（iOS 恒传，缺省空串 —— 删除权限走 RN 兜底请求）
        assertEquals("", minimal["creatorId"])
        assertFalse("无定位参数不放键", minimal.containsKey("commentId"))
        assertFalse(minimal.containsKey("rootId"))
    }

    @Test
    fun `纯业务 key 不触发守卫`() {
        // 不该误报：这些都是 RN 侧真实 props 名
        SurfaceContract.assertNoShellKeyClash(
            setOf("characterId", "initialScreen", "targetType", "targetId", "tab", "userId"),
        )
    }
}
