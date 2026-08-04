# 基线混淆规则。当前 isMinifyEnabled=false，本文件暂不生效；
# 开启 R8 时需按此补充 keep 规则。

# QQ SDK（本地 jar，无自带 consumer rules）
-keep class com.tencent.tauth.** { *; }
-keep class com.tencent.connect.** { *; }
-keep class com.tencent.open.** { *; }
-dontwarn com.tencent.**

# jbsdiff（io.sigpipe，2013 未维护库，靠反射实例化）
-keep class io.sigpipe.** { *; }
-dontwarn io.sigpipe.**

# jsoup 通过反射访问 DOM
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
