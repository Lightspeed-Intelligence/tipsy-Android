package ai.lightspeed.tipsy.shell.pages.screen.recommendation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Application-lifetime reconnect trigger for the Screen recommendation outbox.
 *
 * RN treats `isConnected !== false && isInternetReachable !== false` as online and flushes only
 * on a known false→true edge. Android maps that to the active default network having both
 * INTERNET and VALIDATED capabilities. Initial online state does not create a duplicate trigger;
 * cold-start flush is owned by [ai.lightspeed.tipsy.shell.TipsyApplication].
 */
class ScreenRecommendationNetworkMonitor(
    context: Context,
    private val connectivityState: ScreenRecommendationConnectivityState,
    private val onReconnect: () -> Unit,
    private val diagnostic: ScreenRecommendationDiagnostic,
) {
    private val connectivityManager = context.applicationContext.getSystemService(
        ConnectivityManager::class.java,
    )
    private val lock = Any()
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrentState()

        override fun onLost(network: Network) = publishCurrentState()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = publishCurrentState()
    }

    /** Idempotent: Application may call this only once, but double registration must stay safe. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        try {
            connectivityState.update(currentConnected())
            connectivityManager.registerDefaultNetworkCallback(callback)
            // Close the small read→register race without treating initial null→online as reconnect.
            publishCurrentState()
        } catch (error: RuntimeException) {
            synchronized(lock) { started = false }
            runCatching {
                diagnostic.record(
                    EVENT_NETWORK_STATE_FAILED,
                    mapOf("error_name" to error.javaClass.simpleName),
                )
            }
        }
    }

    private fun publishCurrentState() {
        if (connectivityState.update(currentConnected())) {
            onReconnect()
        }
    }

    private fun currentConnected(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val EVENT_NETWORK_STATE_FAILED = "screen_recommend_tracking_network_state_failed"
    }
}

/** Thread-safe state shared with reporter; update returns only the false→true reconnect edge. */
class ScreenRecommendationConnectivityState(initialConnected: Boolean? = null) {
    private var connected: Boolean? = initialConnected

    @Synchronized
    fun update(nextConnected: Boolean): Boolean {
        val shouldFlush = connected == false && nextConnected
        connected = nextConnected
        return shouldFlush
    }

    @Synchronized
    fun isConnected(): Boolean? = connected
}
