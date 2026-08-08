package ai.lightspeed.tipsy.shell

import android.app.Application
import android.content.res.Configuration
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.defaults.DefaultReactNativeHost
import expo.modules.ApplicationLifecycleDispatcher
import expo.modules.ReactNativeHostWrapper

/**
 * 壳 Application。
 *
 * 方案 ADR-003：**一个进程一个 React Runtime**。这里只建一个 ReactHost，
 * 所有 RN 页面都是它之上的 Surface —— 不得为页面新建 Runtime。
 *
 * 实现 [ReactApplication] 的作用不只是"提供 host"：`ReactFragment` 默认经
 * `activity.application as ReactApplication` 取 reactHost，实现了它就能复用
 * 官方的生命周期转发，不必自己实现 onHostResume/Pause/Destroy（方案 ADR-002）。
 *
 * W0 边界：这里刻意不做 auth / 埋点 / 营销 SDK / 推送的初始化。那些属于
 * 方案 §4.2 的 root side-effect 清单，各由所属波次按"单一 owner"契约接入；
 * 提前在这里初始化会造成与 RN 侧双写（iOS 上真实发生过）。
 */
class TipsyApplication : Application(), ReactApplication {

    override val reactNativeHost: ReactNativeHost = ReactNativeHostWrapper(
        this,
        object : DefaultReactNativeHost(this) {
            override fun getPackages(): List<ReactPackage> = PackageList(this).packages

            // Debug 直连 Metro 时的入口模块。W0 严格隔离期指向零业务依赖的
            // 自检入口，与 app/build.gradle 的 react.entryFile 保持一致 ——
            // 两处不一致会出现"Metro 加载业务包、离线包却是自检包"的错配。
            override fun getJSMainModuleName(): String = "index.surfaces.debug"

            override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

            override val isNewArchEnabled: Boolean = true
        }
    )

    override val reactHost: ReactHost
        get() = ReactNativeHostWrapper.createReactHost(applicationContext, reactNativeHost)

    override fun onCreate() {
        super.onCreate()
        DefaultNewArchitectureEntryPoint.releaseLevel = ReleaseLevel.STABLE
        loadReactNative(this)
        // Expo 模块的 Application 生命周期分发；autolinked 模块依赖它
        ApplicationLifecycleDispatcher.onApplicationCreate(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
    }
}
