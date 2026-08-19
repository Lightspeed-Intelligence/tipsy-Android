package ai.lightspeed.tipsy.shell.user

import ai.lightspeed.tipsy.shell.auth.LegacyMmkvStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * RN `useUserStore` 的 Zustand persist 信封契约。
 *
 * Android Native 已经是登录与 `/user/info` 的 owner，所以这里维护的是一份
 * **完整的、非敏感的用户快照**，不是只够某个页面工作的 `userId` 补丁。
 * 字段名与 `tipsy-app/src/store/user.ts#setUser` 一一对应；已知字段即使接口缺失
 * 也显式写 JSON null（`nsfw` 除外，跟 RN 一样回落 false），防止换号后保留
 * 上一账号的昵称、引导状态或功能开关。
 */
class UserStorageSnapshot private constructor(private val stateJson: String) {

    /** 每次返回独立对象，调用方 merge 时不会改到本快照。 */
    fun fields(): JSONObject = JSONObject(stateJson)

    companion object {
        fun fromApi(data: JSONObject, userId: String): UserStorageSnapshot {
            val fields = JSONObject()
                .put(FIELD_USER_ID, userId)
                .putNullable(FIELD_NICKNAME, stringOrNull(data, "nickname"))
                .putNullable(FIELD_GENDER, stringOrNull(data, "gender"))
                .putNullable(FIELD_EMAIL, stringOrNull(data, "email"))
                .putNullable(FIELD_AVATAR, stringOrNull(data, "avatar"))
                .putNullable(FIELD_AVATAR_URL, stringOrNull(data, "avatar_url"))
                .putNullable(
                    FIELD_AVATAR_DECORATION_CODE,
                    stringOrNull(data, "avatar_decoration_code"),
                )
                .putNullable(FIELD_EMAIL_VERIFIED, booleanOrNull(data, "email_verified"))
                .putNullable(FIELD_CREATED_AT, numberOrNull(data, "created_at"))
                .putNullable(FIELD_UPDATED_AT, numberOrNull(data, "updated_at"))
                .putNullable(
                    FIELD_BASIC_RULES_COMPLETED,
                    booleanOrNull(data, "basic_rules_completed"),
                )
                .putNullable(FIELD_BLUR, booleanOrNull(data, "blur"))
                // RN setUser: `user.nsfw ?? false`
                .put(FIELD_NSFW, booleanOrNull(data, "nsfw") ?: false)
                .putNullable(FIELD_TEXT_MODEL_ID, stringOrNull(data, "text_model_id"))
                .putNullable(FIELD_IS_DELETED, booleanOrNull(data, "is_deleted"))
                .putNullable(FIELD_BACKGROUND_IMG_URL, stringOrNull(data, "background_img_url"))
                .putNullable(FIELD_LANGUAGE_CODE, stringOrNull(data, "language_code"))
                .putNullable(FIELD_AGE, numberOrNull(data, "age"))
                .putNullable(
                    FIELD_IS_FREE_MODEL_TRIAL,
                    booleanOrNull(data, "is_free_model_trial"),
                )
                .putNullable(
                    FIELD_HAS_FREE_MODEL_CHAT,
                    booleanOrNull(data, "has_free_model_chat"),
                )
                .putNullable(
                    FIELD_RELATIONSHIP_SWITCH,
                    booleanOrNull(data, "relationship_switch"),
                )
                .putNullable(
                    FIELD_IS_LEGACY_SUBSCRIBER,
                    booleanOrNull(data, "is_legacy_subscriber"),
                )
                .putNullable(FIELD_REG_COUNTRY, stringOrNull(data, "reg_country"))
                .putNullable(FIELD_INITIAL_GENDER, stringOrNull(data, "initial_gender"))
                .putNullable(FIELD_BIO, stringOrNull(data, "bio"))
                .putNullable(FIELD_ONBOARDING_STATUS, stringOrNull(data, "onboarding_status"))
                .putNullable(
                    FIELD_HAS_EVER_CHATTED,
                    booleanOrNull(data, "has_ever_chatted"),
                )
                .putNullable(FIELD_DEFAULT_INPUT_MODE, stringOrNull(data, "default_input_mode"))
                .putNullable(
                    FIELD_GEM_ENTRY_EXP_GROUP_NAME,
                    stringOrNull(data, "gem_entry_exp_group_name"),
                )
                .putNullable(
                    FIELD_INSPIRATION_EXP_GROUP_NAME,
                    stringOrNull(data, "inspiration_exp_group_name"),
                )
                .putNullable(
                    FIELD_VOICE_CALL_SHOW_TEXT,
                    booleanOrNull(data, "voice_call_show_text"),
                )
                .putNullable(FIELD_DISPLAY_URL, jsonArrayOrNull(data, "display_urls"))
                .putNullable(
                    FIELD_CHECKIN_REWARD_INFO,
                    jsonObjectOrNull(data, "checkin_reward_info"),
                )
                .putNullable(
                    FIELD_NEW_USER_CHECK_POP_V2,
                    numberOrNull(data, "new_user_check_pop_v2"),
                )
                .putNullable(FIELD_JULY_TAROT_FACTION, stringOrNull(data, "july_tarot_faction"))
            return UserStorageSnapshot(fields.toString())
        }

        private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
            put(key, value ?: JSONObject.NULL)

        /** JS `value || null` 的字符串部分：空串为 null，空白字符串仍是有效值。 */
        private fun stringOrNull(data: JSONObject, key: String): String? {
            if (!data.has(key) || data.isNull(key)) return null
            return when (val raw = data.opt(key)) {
                is String -> raw.takeIf { it.isNotEmpty() }
                is Number, is Boolean -> raw.toString()
                else -> null
            }
        }

        /** 宽容后端常见的 true/false、0/1 与字符串形态。 */
        private fun booleanOrNull(data: JSONObject, key: String): Boolean? {
            if (!data.has(key) || data.isNull(key)) return null
            return when (val raw = data.opt(key)) {
                is Boolean -> raw
                is Number -> when (raw.toInt()) {
                    0 -> false
                    1 -> true
                    else -> null
                }
                is String -> when (raw.trim().lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
                else -> null
            }
        }

        /** 保留整数/浮点的 JSON 数值形态；字符串数值转为 Number。 */
        private fun numberOrNull(data: JSONObject, key: String): Number? {
            if (!data.has(key) || data.isNull(key)) return null
            return when (val raw = data.opt(key)) {
                is Number -> raw
                is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull()
                else -> null
            }
        }

        private fun jsonArrayOrNull(data: JSONObject, key: String): JSONArray? =
            data.optJSONArray(key)?.let { JSONArray(it.toString()) }

        private fun jsonObjectOrNull(data: JSONObject, key: String): JSONObject? =
            data.optJSONObject(key)?.let { JSONObject(it.toString()) }

        const val FIELD_USER_ID = "userId"
        const val FIELD_NICKNAME = "nickname"
        const val FIELD_GENDER = "gender"
        const val FIELD_EMAIL = "email"
        const val FIELD_AVATAR = "avatar"
        const val FIELD_AVATAR_URL = "avatarUrl"
        const val FIELD_AVATAR_DECORATION_CODE = "avatarDecorationCode"
        const val FIELD_EMAIL_VERIFIED = "emailVerified"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_UPDATED_AT = "updatedAt"
        const val FIELD_BASIC_RULES_COMPLETED = "basicRulesCompleted"
        const val FIELD_BLUR = "blur"
        const val FIELD_NSFW = "nsfw"
        const val FIELD_TEXT_MODEL_ID = "textModelId"
        const val FIELD_IS_DELETED = "isDeleted"
        const val FIELD_BACKGROUND_IMG_URL = "backgroundImgUrl"
        const val FIELD_LANGUAGE_CODE = "languageCode"
        const val FIELD_AGE = "age"
        const val FIELD_IS_FREE_MODEL_TRIAL = "isFreeModelTrial"
        const val FIELD_HAS_FREE_MODEL_CHAT = "hasFreeModelChat"
        const val FIELD_RELATIONSHIP_SWITCH = "relationshipSwitch"
        const val FIELD_IS_LEGACY_SUBSCRIBER = "isLegacySubscriber"
        const val FIELD_REG_COUNTRY = "regCountry"
        const val FIELD_INITIAL_GENDER = "initialGender"
        const val FIELD_BIO = "bio"
        const val FIELD_ONBOARDING_STATUS = "onboardingStatus"
        const val FIELD_HAS_EVER_CHATTED = "hasEverChatted"
        const val FIELD_DEFAULT_INPUT_MODE = "defaultInputMode"
        const val FIELD_GEM_ENTRY_EXP_GROUP_NAME = "gem_entry_exp_group_name"
        const val FIELD_INSPIRATION_EXP_GROUP_NAME = "inspirationExpGroupName"
        const val FIELD_VOICE_CALL_SHOW_TEXT = "voiceCallShowText"
        const val FIELD_DISPLAY_URL = "displayUrl"
        const val FIELD_CHECKIN_REWARD_INFO = "checkinRewardInfo"
        const val FIELD_NEW_USER_CHECK_POP_V2 = "newUserCheckPopV2"
        const val FIELD_JULY_TAROT_FACTION = "julyTarotFaction"
    }
}

/** 纯 JSON 变换；测试直接覆盖信封保留/建新/字段清理语义。 */
object UserStorageEnvelope {
    fun merge(raw: String?, fields: JSONObject): String {
        val envelope = parseEnvelope(raw) ?: JSONObject().put(VERSION, DEFAULT_VERSION)
        val state = envelope.optJSONObject(STATE) ?: JSONObject()
        fields.keys().forEach { key -> state.put(key, fields.get(key)) }
        envelope.put(STATE, state)
        if (!envelope.has(VERSION)) envelope.put(VERSION, DEFAULT_VERSION)
        return envelope.toString()
    }

    fun userId(raw: String?): String? = parseEnvelope(raw)
        ?.optJSONObject(STATE)
        ?.optString(UserStorageSnapshot.FIELD_USER_ID)
        ?.takeIf { it.isNotBlank() && it != "null" }

    private fun parseEnvelope(raw: String?): JSONObject? {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private const val STATE = "state"
    private const val VERSION = "version"
    private const val DEFAULT_VERSION = 0
}

/**
 * Android 壳对 `user-storage` 的唯一 read-modify-write 入口。
 *
 * `synchronized` 覆盖整段读→merge→写，而不是只依赖 MMKV 单次操作原子性；
 * 这样 `/user/info` 发布完整快照与语言页写 `languageCode` 不会互相丢更新。
 */
class UserStorageRepository(private val store: LegacyMmkvStore) {
    private val lock = Any()

    fun merge(fields: JSONObject): Boolean = synchronized(lock) {
        val raw = store.getString(USER_STORAGE_KEY)
        store.putString(USER_STORAGE_KEY, UserStorageEnvelope.merge(raw, fields))
    }

    fun readUserId(): String? = synchronized(lock) {
        UserStorageEnvelope.userId(store.getString(USER_STORAGE_KEY))
    }

    fun clear(): Boolean = synchronized(lock) { store.removeString(USER_STORAGE_KEY) }

    companion object {
        const val USER_STORAGE_KEY = "user-storage"
    }
}
