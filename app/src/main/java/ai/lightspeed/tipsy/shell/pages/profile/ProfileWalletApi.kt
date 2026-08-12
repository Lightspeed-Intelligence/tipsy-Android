package ai.lightspeed.tipsy.shell.pages.profile

import ai.lightspeed.tipsy.shell.network.ApiClient
import ai.lightspeed.tipsy.shell.network.AuthMode
import ai.lightspeed.tipsy.shell.network.ScalarCoercion
import org.json.JSONObject

/**
 * 钱包区的两个接口（`apis/subscribe.ts`，都走 `axiosAuth` → [AuthMode.REQUIRED]）。
 *
 * | RN | 路径 | 用途 |
 * | --- | --- | --- |
 * | `getUserWallet`（`subscribe.ts:91`） | `/wallet/info` | 宝石/免费条数/金币 |
 * | `getSubscriptionActive`（`subscribe.ts:102`） | `/subscription/get/active` | 订阅档位（中栏标题与配色） |
 *
 * ## 只取钱包卡要用的字段
 *
 * `useUserWallet` 派生了十几个值（汇率/提现/佣金/生图余量…），那些消费方是
 * `UserCoinsSurface` / 提现页 —— 都不迁（§8.0）。壳只解析三栏卡上屏的五个字段。
 * `membership_rights/info`（`useUserRights`）同理**整个不拉** —— 卡片不消费
 * 任何权益字段，RN 在这个组件里也只用它做预取。
 *
 * ## ⚠️ `has_inf_msg` 优先于数值
 *
 * `left_free_msg_amount` 在 `has_inf_msg=true` 时**无意义**
 * （`useUserWallet.tsx:39-41` 直接置 `'inf'`，UI 显示 `Unlimited`）。
 * 先判布尔再读数值，反过来会把一个残值当真实余量显示。
 */
class ProfileWalletApi(private val apiClient: ApiClient) : ProfileWalletSource {

    override suspend fun fetchWallet(): ProfileWallet {
        val envelope = apiClient.post(
            path = PATH_WALLET_INFO,
            authMode = AuthMode.REQUIRED,
        )
        return ProfileWallet.parse(envelope.data)
    }

    override suspend fun fetchSubscriptionPlanId(): Int {
        val envelope = apiClient.post(
            path = PATH_SUBSCRIPTION_ACTIVE,
            authMode = AuthMode.REQUIRED,
        )
        // 无订阅时 RN 拿到的 data 可能为 null / 缺 plan_id —— 都按 Free 处理
        //（DefaultUserSubscription 的语义）
        val data = envelope.data ?: return ProfileWallet.PLAN_FREE
        return ScalarCoercion.optInt(data, FIELD_PLAN_ID) ?: ProfileWallet.PLAN_FREE
    }

    companion object {
        const val PATH_WALLET_INFO = "/wallet/info"
        const val PATH_SUBSCRIPTION_ACTIVE = "/subscription/get/active"
        private const val FIELD_PLAN_ID = "plan_id"
    }
}

/** 数据源接缝（理由同 [ProfileSource]：编排语义走 JVM 单测）。 */
interface ProfileWalletSource {
    suspend fun fetchWallet(): ProfileWallet
    suspend fun fetchSubscriptionPlanId(): Int
}

/**
 * 钱包卡的数据（`/wallet/info` 响应子集 + 订阅档位）。
 *
 * @property gemAmount 红宝石数（`gem_amount`，缺省 0，对齐 `?? 0`）
 * @property freeAmountIsUnlimited `has_inf_msg` —— 为 true 时中栏显示 `Unlimited`
 * @property leftFreeAmount 免费条数（`left_free_msg_amount`）
 * @property coinAmount 金币数（`coin_amount`，**一位小数**显示，见 [ProfileText.formatCoinAmount]）
 * @property planId 订阅档位（`plan_id`，0=Free…5=OnTrial；未拉到时 0）
 */
data class ProfileWallet(
    val gemAmount: Long = 0L,
    val freeAmountIsUnlimited: Boolean = false,
    val leftFreeAmount: Long = 0L,
    val coinAmount: Double = 0.0,
    val planId: Int = PLAN_FREE,
) {
    /**
     * 中栏标题的 i18n key（`MemberShipTierName`，`constants/subscribe.ts:28-35`，
     * key = 英文原文）。认不出的档位回落 Free —— 新档位上线时不崩、显示保守值。
     */
    val planNameKey: String
        get() = when (planId) {
            PLAN_GET_A_TASTE -> "Get a Taste"
            PLAN_STANDARD -> "Standard"
            PLAN_PREMIUM -> "Premium"
            PLAN_DELUXE -> "Deluxe"
            PLAN_ON_TRIAL -> "On Trial"
            else -> "Free"
        }

    /** 非 Free 档位中栏数字走蓝色（`UserProfileGems.tsx:373` 的三元）。 */
    val isFreePlan: Boolean get() = planId == PLAN_FREE

    companion object {
        const val PLAN_FREE = 0
        const val PLAN_GET_A_TASTE = 1
        const val PLAN_STANDARD = 2
        const val PLAN_PREMIUM = 3
        const val PLAN_DELUXE = 4
        const val PLAN_ON_TRIAL = 5

        val EMPTY = ProfileWallet()

        /**
         * 解析 `/wallet/info` 的 `data`（不含 planId —— 那来自另一个接口，
         * 由 ViewModel 合成）。缺字段一律 0/false，对齐 RN 的 `?? 0`。
         */
        fun parse(data: JSONObject?): ProfileWallet {
            if (data == null) return EMPTY
            return ProfileWallet(
                gemAmount = ScalarCoercion.optLong(data, FIELD_GEM_AMOUNT) ?: 0L,
                freeAmountIsUnlimited =
                    ScalarCoercion.optBoolean(data, FIELD_HAS_INF_MSG) ?: false,
                leftFreeAmount =
                    ScalarCoercion.optLong(data, FIELD_LEFT_FREE_MSG_AMOUNT) ?: 0L,
                coinAmount = ScalarCoercion.optDouble(data, FIELD_COIN_AMOUNT) ?: 0.0,
            )
        }

        private const val FIELD_GEM_AMOUNT = "gem_amount"
        private const val FIELD_HAS_INF_MSG = "has_inf_msg"
        private const val FIELD_LEFT_FREE_MSG_AMOUNT = "left_free_msg_amount"
        private const val FIELD_COIN_AMOUNT = "coin_amount"
    }
}
