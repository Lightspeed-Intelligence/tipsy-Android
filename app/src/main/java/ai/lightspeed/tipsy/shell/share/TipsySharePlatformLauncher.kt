package ai.lightspeed.tipsy.shell.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.net.toUri

enum class TipsyShareLaunchTarget {
    CLIPBOARD,
    APP,
    WEB,
}

sealed interface TipsyShareLaunchResult {
    data class Launched(
        val channel: TipsyShareChannel,
        val target: TipsyShareLaunchTarget,
    ) : TipsyShareLaunchResult

    data class Unavailable(val channel: TipsyShareChannel) : TipsyShareLaunchResult
}

/**
 * Activity-scoped Android destination launcher for the generic share sheet.
 *
 * There is intentionally no Application singleton here: launching an external Activity and
 * haptic/clipboard feedback belong to the currently visible host. Explicit package launches do
 * not require inbound intent filters or a chooser, and every App/scheme miss degrades to web.
 */
class TipsySharePlatformLauncher(
    private val activity: Activity,
    private val hapticView: View? = null,
) {
    fun launch(
        channel: TipsyShareChannel,
        reelUrl: String,
        shareText: String,
    ): TipsyShareLaunchResult = when (channel) {
        TipsyShareChannel.COPY_LINK -> copyShareText(channel, shareText)

        TipsyShareChannel.DISCORD -> {
            val copied = copyShareText(channel, shareText)
            if (copied is TipsyShareLaunchResult.Unavailable) copied else launchExternal(
                channel,
                reelUrl,
                shareText,
            )
        }

        TipsyShareChannel.TIKTOK -> {
            val copied = copyShareText(channel, shareText)
            if (copied is TipsyShareLaunchResult.Unavailable) copied else launchExternal(
                channel,
                reelUrl,
                shareText,
            )
        }

        else -> launchExternal(channel, reelUrl, shareText)
    }

    /**
     * Discord/TikTok 的 RN 合同要求：先确认剪贴板成功，随后才展示 opening 提示、
     * 提交推荐反馈并拉起外部 App。Screen 通过这个分段接口维持该时序；[launch]
     * 仍为其它调用方提供一次完成的默认行为。
     */
    fun copyShareText(
        channel: TipsyShareChannel,
        shareText: String,
    ): TipsyShareLaunchResult = if (copyToClipboard(shareText)) {
        TipsyShareLaunchResult.Launched(channel, TipsyShareLaunchTarget.CLIPBOARD)
    } else {
        TipsyShareLaunchResult.Unavailable(channel)
    }

    /** 已完成渠道前置动作后，只负责拉起外部目的地。 */
    fun launchExternal(
        channel: TipsyShareChannel,
        reelUrl: String,
        shareText: String,
    ): TipsyShareLaunchResult = when (channel) {
        TipsyShareChannel.COPY_LINK -> TipsyShareLaunchResult.Unavailable(channel)

        TipsyShareChannel.DISCORD -> launchAppThenWeb(
            channel = channel,
            appUri = "discord://",
            webUri = "https://discord.com/channels/@me",
        )

        TipsyShareChannel.INSTAGRAM -> launchAppThenWeb(
            channel = channel,
            appUri = "instagram://sharesheet?text=${Uri.encode(shareText)}",
            webUri = "https://www.instagram.com",
        )

        TipsyShareChannel.TIKTOK -> launchAppThenWeb(
            channel = channel,
            appUri = "tiktok://",
            webUri = "https://www.tiktok.com",
        )

        TipsyShareChannel.X -> launchTargetedThenSchemeThenWeb(
            channel = channel,
            packageName = X_PACKAGE,
            reelUrl = reelUrl,
            localizedMessage = localizedMessage(shareText, reelUrl),
            // RN Android falls back to the app root, not the iOS-only twitter://post contract.
            schemeUri = "twitter://",
            nativeDefaultWebUri = "https://twitter.com/intent/tweet?" +
                "text=${TipsyShareUrlBuilder.encodeQueryComponent(localizedMessage(shareText, reelUrl))}&" +
                "url=${TipsyShareUrlBuilder.encodeQueryComponent(reelUrl)}",
            fallbackWebUri = "https://twitter.com/intent/tweet?" +
                "text=${Uri.encode(localizedMessage(shareText, reelUrl))}&" +
                "url=${Uri.encode(reelUrl)}",
        )

        TipsyShareChannel.FACEBOOK -> launchTargetedThenSchemeThenWeb(
            channel = channel,
            packageName = FACEBOOK_PACKAGE,
            reelUrl = reelUrl,
            localizedMessage = localizedMessage(shareText, reelUrl),
            schemeUri = "fb://",
            nativeDefaultWebUri = "https://www.facebook.com/sharer/sharer.php?u=" +
                TipsyShareUrlBuilder.encodeQueryComponent(reelUrl),
            fallbackWebUri = "https://www.facebook.com/sharer/sharer.php?u=${Uri.encode(reelUrl)}",
        )
    }

    private fun copyToClipboard(text: String): Boolean {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            hapticView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun launchTargetedThenSchemeThenWeb(
        channel: TipsyShareChannel,
        packageName: String,
        reelUrl: String,
        localizedMessage: String,
        schemeUri: String,
        nativeDefaultWebUri: String,
        fallbackWebUri: String,
    ): TipsyShareLaunchResult {
        TipsyShareTargetedContract.destinationOrder(
            packageInstalled = isPackageInstalled(packageName),
        ).forEach { destination ->
            val launched = when (destination) {
                TipsyShareTargetedDestination.TARGETED_APP -> tryStart(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage(packageName)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            TipsyShareTargetedContract.targetedExtraText(
                                localizedMessage = localizedMessage,
                                reelUrl = reelUrl,
                            ),
                        )
                    },
                )

                TipsyShareTargetedDestination.NATIVE_DEFAULT_WEB -> tryStart(
                    Intent(Intent.ACTION_VIEW, nativeDefaultWebUri.toUri()),
                )

                TipsyShareTargetedDestination.SCHEME -> tryStart(
                    Intent(Intent.ACTION_VIEW, schemeUri.toUri()),
                )

                TipsyShareTargetedDestination.JS_FALLBACK_WEB -> tryStart(
                    Intent(Intent.ACTION_VIEW, fallbackWebUri.toUri()),
                )
            }
            if (launched) {
                val target = when (destination) {
                    TipsyShareTargetedDestination.NATIVE_DEFAULT_WEB,
                    TipsyShareTargetedDestination.JS_FALLBACK_WEB,
                    -> TipsyShareLaunchTarget.WEB

                    TipsyShareTargetedDestination.TARGETED_APP,
                    TipsyShareTargetedDestination.SCHEME,
                    -> TipsyShareLaunchTarget.APP
                }
                return TipsyShareLaunchResult.Launched(channel, target)
            }
        }
        return TipsyShareLaunchResult.Unavailable(channel)
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            activity.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun launchAppThenWeb(
        channel: TipsyShareChannel,
        appUri: String,
        webUri: String,
    ): TipsyShareLaunchResult {
        if (tryStart(Intent(Intent.ACTION_VIEW, appUri.toUri()))) {
            return TipsyShareLaunchResult.Launched(channel, TipsyShareLaunchTarget.APP)
        }
        if (tryStart(Intent(Intent.ACTION_VIEW, webUri.toUri()))) {
            return TipsyShareLaunchResult.Launched(channel, TipsyShareLaunchTarget.WEB)
        }
        return TipsyShareLaunchResult.Unavailable(channel)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun localizedMessage(shareText: String, reelUrl: String): String {
        val suffix = "\n$reelUrl"
        return if (shareText.endsWith(suffix)) shareText.removeSuffix(suffix) else shareText
    }

    private companion object {
        const val CLIP_LABEL = "Tipsy share"
        const val X_PACKAGE = "com.twitter.android"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
    }
}

/** react-native-share 12.2.5 `SingleShareIntent` + JS catch 的纯路由合同。 */
internal enum class TipsyShareTargetedDestination {
    TARGETED_APP,
    NATIVE_DEFAULT_WEB,
    SCHEME,
    JS_FALLBACK_WEB,
}

internal object TipsyShareTargetedContract {
    fun destinationOrder(packageInstalled: Boolean): List<TipsyShareTargetedDestination> =
        if (packageInstalled) {
            listOf(
                TipsyShareTargetedDestination.TARGETED_APP,
                TipsyShareTargetedDestination.SCHEME,
                TipsyShareTargetedDestination.JS_FALLBACK_WEB,
            )
        } else {
            listOf(
                // SingleShareIntent 未安装时直接改成官方 defaultWebLink；只有这个
                // start 也失败，JS catch 才继续 scheme → 手写 Web fallback。
                TipsyShareTargetedDestination.NATIVE_DEFAULT_WEB,
                TipsyShareTargetedDestination.SCHEME,
                TipsyShareTargetedDestination.JS_FALLBACK_WEB,
            )
        }

    fun targetedExtraText(localizedMessage: String, reelUrl: String): String =
        localizedMessage.takeIf { it.isNotEmpty() }
            ?.let { "$it $reelUrl" }
            ?: reelUrl
}
