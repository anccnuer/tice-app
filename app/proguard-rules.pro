# Room 生成类、手写 JSON 字段名与 xlsx 正则拼接都不依赖类名/字段名反射，
# 保留整个包会阻止 R8 裁剪这些核心模块，因此只保留真正的反射入口。
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.v1.** { *; }
