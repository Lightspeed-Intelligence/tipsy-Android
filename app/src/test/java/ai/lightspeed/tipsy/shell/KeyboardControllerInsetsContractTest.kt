package ai.lightspeed.tipsy.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止 RN Surface 再次把宿主 Activity 的状态栏 inset 改成 0。 */
class KeyboardControllerInsetsContractTest {

    private val keyboardControllerSource = File(
        "../tipsy-app/node_modules/react-native-keyboard-controller/android/src/main/java/" +
            "com/reactnativekeyboardcontroller/views/EdgeToEdgeReactViewGroup.kt",
    )
    private val dependencyPatch = File(
        "../tipsy-app/patches/react-native-keyboard-controller+1.21.0-beta.1.patch",
    )

    @Test
    fun `KeyboardProvider 向共享 Activity 子树传递原始 WindowInsets`() {
        assertTrue("找不到 keyboard controller Android 源码", keyboardControllerSource.isFile)
        val source = keyboardControllerSource.readText()

        assertFalse(
            "RN Surface 不能把 statusBars top 归零后再派给 Native Tab",
            source.contains("v.replaceStatusBarInsets(insets"),
        )
        assertTrue(
            "KeyboardProvider 应保留系统原始 inset 供 RN 与 Native 各自避让",
            source.contains("ViewCompat.onApplyWindowInsets(v, insets)"),
        )
    }

    @Test
    fun `依赖重装后仍会应用同一 inset 修复`() {
        assertTrue("缺少可复现的 patch-package 补丁", dependencyPatch.isFile)
        val patch = dependencyPatch.readText()

        assertTrue(
            patch.contains(
                "-import com.reactnativekeyboardcontroller.extensions.replaceStatusBarInsets",
            ),
        )
        assertTrue(patch.contains("+        ViewCompat.onApplyWindowInsets(v, insets)"))
    }
}
