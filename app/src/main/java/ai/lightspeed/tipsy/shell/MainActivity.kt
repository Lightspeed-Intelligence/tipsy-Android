package ai.lightspeed.tipsy.shell

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.commit
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler

/**
 * 壳的宿主 Activity。
 *
 * 方案 ADR-002：`AppCompatActivity` + FragmentManager 承载两类页面 ——
 * 原生页是 Fragment 内挂 [ComposeView]，RN 页是 [RNSurfaceFragment]。
 * FragmentManager 统一处理返回栈、saved state、predictive back 与进程重建。
 *
 * W0 边界：这里还没有五 Tab 与 Router（W1/W2 的事），只提供一个能验证
 * 「原生根可显示 + 能挂载/卸载 RN Surface」的最小宿主。
 */
class MainActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 桥的 popSurface 出口（W1-P0）。Application 不持 Activity 引用，
        // 用回调转接；onDestroy 必须清掉，否则泄漏本 Activity。
        (application as TipsyApplication).onPopSurfaceRequested = { instanceId ->
            runOnUiThread { popSurface(instanceId) }
        }

        if (savedInstanceState == null) {
            // 原生根：证明壳自己能先渲染，不依赖 RN
            findViewById<ComposeView>(R.id.native_root).setContent {
                MaterialTheme {
                    ShellHomeScreen(onOpenSurface = { openDebugSurface() })
                }
            }
        }
    }

    /**
     * RN 的返回键契约（**必须实现，否则 Surface 一挂就崩**）。
     *
     * `ReactFragment.onResume` → `reactDelegate.onHostResume()` 内部会把宿主
     * Activity 强转成 [DefaultHardwareBackBtnHandler]，不实现就抛
     * `ClassCastException: Host Activity does not implement DefaultHardwareBackBtnHandler`
     * —— 且崩在 onResume，构建期与静态检查都发现不了（W0 gate 实测捕获）。
     *
     * 语义：RN 侧不处理返回键时回调到这里，执行原生默认返回。
     * W1 起这里要接 Router：先给当前 RN 微栈，到栈底才 pop 原生（方案 §4.7）。
     */
    override fun invokeDefaultOnBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        (application as TipsyApplication).onPopSurfaceRequested = null
        super.onDestroy()
    }

    /**
     * 关闭当前 RN Surface 容器（RN 栈底返回键经桥调到这里）。
     *
     * ⚠️ **必须幂等**（ADR-003）：迟到的 popSurface 不得关掉后来打开的容器。
     * W1-P0 先用「栈里有 Surface 才 pop」这个最小保证；P4 接 Router 时改为
     * 按 `surfaceInstanceId` 精确匹配当前容器。
     *
     * iOS 的闸是**类型判定**，迟到事件弹错了同类型页（后用 closingRef 补）——
     * Android 从一开始按实例判定，别重复那个 bug。
     */
    private fun popSurface(surfaceInstanceId: String?) {
        if (supportFragmentManager.backStackEntryCount == 0) return
        supportFragmentManager.popBackStack()
    }

    /**
     * 挂载 DebugSurface。W0 的核心 gate：
     * Metro 直连与离线内嵌 bundle 两种来源都必须能挂上、能返回、能反复开关。
     */
    private fun openDebugSurface() {
        supportFragmentManager.commit {
            replace(R.id.surface_container, RNSurfaceFragment.newInstance("DebugSurface"))
            addToBackStack("DebugSurface")
        }
    }
}

@Composable
private fun ShellHomeScreen(onOpenSurface: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Tipsy Android Shell",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "W0：原生根已渲染（未接业务能力）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onOpenSurface, modifier = Modifier.padding(top = 24.dp)) {
                Text("挂载 DebugSurface")
            }
        }
    }
}
