# Add project specific ProGuard rules here.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn ai.onnxruntime.**
