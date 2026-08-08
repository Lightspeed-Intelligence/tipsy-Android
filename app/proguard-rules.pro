# R8/ProGuard 规则（release）。
#
# 大部分 keep 规则由各 RN/Expo 模块经 consumer proguard 自带，这里只放壳自己的。
#
# 注意：方案 §5.4 要求 R8 mapping 与 native symbols、RN source map 经同一
# build ID 可检索并归档 —— 发布脚本负责上传，不要在这里关掉 mapping 输出。

# 壳的入口类经 manifest 反射实例化
-keep class ai.lightspeed.tipsy.shell.TipsyApplication { *; }
-keep class ai.lightspeed.tipsy.shell.MainActivity { *; }

# RN 桥：@ReactMethod 经反射调用，不能被裁剪或改名
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod <methods>;
}
-keep,allowobfuscation @interface com.facebook.proguard.annotations.DoNotStrip
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
}

# Expo 模块经反射注册
-keep class expo.modules.** { *; }

# 保留行号以便 Sentry 还原堆栈（配合 mapping 上传）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 第三方 SDK 的可选依赖（W0 实测 R8 报 Missing class）──
# 这两个类在运行时并不需要：
#  - ThrowableExtension 是 Bazel desugar 的运行时残留，Agora 的日志类引用了它
#  - DevLog 是 QT SDK 的调试日志类，release 版 AAR 未包含
# 用 dontwarn 而非 keep：keep 一个不存在的类无意义，dontwarn 才是正确处理。
# 若将来 R8 报出新的 Missing class，先确认「运行时是否真的需要」再决定
# dontwarn / keep，不要无脑加 -dontwarn ** 掩盖问题。
-dontwarn com.google.devtools.build.android.desugar.runtime.ThrowableExtension
-dontwarn com.quick.qt.analytics.middle.DevLog
