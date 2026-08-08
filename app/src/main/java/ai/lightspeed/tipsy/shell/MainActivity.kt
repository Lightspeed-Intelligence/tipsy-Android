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
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
