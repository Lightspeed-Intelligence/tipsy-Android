package ai.lightspeed.tipsy.shell

import ai.lightspeed.tipsy.shell.user.UserStorageEnvelope
import ai.lightspeed.tipsy.shell.user.UserStorageSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android `/user/info` → RN Zustand `user-storage` 的完整字段契约。 */
class UserStorageEnvelopeTest {

    @Test
    fun `API 用户映射为 RN setUser 的完整 camelCase 字段集`() {
        val api = JSONObject()
            .put("user_id", "u1")
            .put("nickname", "Lee")
            .put("avatar_url", "https://cdn/avatar.png")
            .put("language_code", "zh-tw")
            .put("nsfw", true)
            .put("relationship_switch", 1)
            .put("has_ever_chatted", "false")
            .put("new_user_check_pop_v2", "1")
            .put(
                "display_urls",
                JSONArray().put(JSONObject().put("platform", "x").put("url", "https://x")),
            )
            .put(
                "checkin_reward_info",
                JSONObject().put("total_gem_count", 7).put("day_rewards", JSONArray()),
            )

        val state = UserStorageSnapshot.fromApi(api, "u1").fields()

        assertEquals(EXPECTED_FIELDS, keysOf(state))
        assertEquals("u1", state.getString("userId"))
        assertEquals("Lee", state.getString("nickname"))
        assertEquals("https://cdn/avatar.png", state.getString("avatarUrl"))
        assertEquals("zh-tw", state.getString("languageCode"))
        assertTrue(state.getBoolean("nsfw"))
        assertTrue(state.getBoolean("relationshipSwitch"))
        assertFalse(state.getBoolean("hasEverChatted"))
        assertEquals(1L, state.getLong("newUserCheckPopV2"))
        assertEquals("x", state.getJSONArray("displayUrl").getJSONObject(0).getString("platform"))
        assertEquals(7, state.getJSONObject("checkinRewardInfo").getInt("total_gem_count"))
        assertFalse(state.has("avatar_url"))
        assertFalse(state.has("language_code"))
    }

    @Test
    fun `缺失字段显式 null 且 nsfw 对齐 RN 回落 false`() {
        val state = UserStorageSnapshot.fromApi(JSONObject().put("user_id", "u1"), "u1").fields()

        assertTrue(state.isNull("nickname"))
        assertTrue(state.isNull("relationshipSwitch"))
        assertTrue(state.isNull("onboardingStatus"))
        assertTrue(state.isNull("checkinRewardInfo"))
        assertFalse(state.getBoolean("nsfw"))
    }

    @Test
    fun `完整快照 merge 保留未知字段与 envelope version`() {
        val raw = """{"state":{"userId":"old","futureField":{"x":1}},"version":7}"""
        val fields = UserStorageSnapshot.fromApi(
            JSONObject().put("user_id", "new").put("nickname", "New"),
            "new",
        ).fields()

        val envelope = JSONObject(UserStorageEnvelope.merge(raw, fields))
        val state = envelope.getJSONObject("state")

        assertEquals(7, envelope.getInt("version"))
        assertEquals("new", state.getString("userId"))
        assertEquals("New", state.getString("nickname"))
        assertEquals(1, state.getJSONObject("futureField").getInt("x"))
        // old 账号已有 true，而新响应缺字段：必须清成 null，不能保留旧值。
        assertTrue(state.isNull("relationshipSwitch"))
    }

    @Test
    fun `坏信封安全重建 version 0 且 userId 可读`() {
        for (raw in listOf<String?>(null, "", "not-json", "[]", "{\"state\":\"bad\"}")) {
            val merged = UserStorageEnvelope.merge(
                raw,
                JSONObject().put(UserStorageSnapshot.FIELD_USER_ID, "u2"),
            )
            assertEquals("raw=$raw", 0, JSONObject(merged).getInt("version"))
            assertEquals("raw=$raw", "u2", UserStorageEnvelope.userId(merged))
        }
        assertNull(UserStorageEnvelope.userId("""{"state":{"userId":null},"version":0}"""))
    }

    private fun keysOf(json: JSONObject): Set<String> = buildSet {
        val iterator = json.keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private companion object {
        val EXPECTED_FIELDS = setOf(
            "userId", "nickname", "gender", "email", "avatar", "avatarUrl",
            "avatarDecorationCode", "emailVerified", "createdAt", "updatedAt",
            "basicRulesCompleted", "blur", "nsfw", "textModelId", "isDeleted",
            "backgroundImgUrl", "languageCode", "age", "isFreeModelTrial",
            "hasFreeModelChat", "relationshipSwitch", "isLegacySubscriber", "regCountry",
            "initialGender", "bio", "onboardingStatus", "hasEverChatted",
            "defaultInputMode", "gem_entry_exp_group_name", "inspirationExpGroupName",
            "voiceCallShowText", "displayUrl", "checkinRewardInfo", "newUserCheckPopV2",
            "julyTarotFaction",
        )
    }
}
