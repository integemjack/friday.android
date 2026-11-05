# JSch（mwiede fork）不需要额外规则；kotlinx-serialization 用默认。
-keep class com.integemjack.friday.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.ow2.asm.**
# 语音唤醒（sherpa-onnx）：原生库按名字读配置类的字段、构造结果类，不能混淆 / 裁掉
-keep class com.k2fsa.sherpa.onnx.** { *; }
