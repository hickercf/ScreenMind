# JSON is mapped explicitly; Room and ML Kit supply their own consumer rules.

# ONNX Runtime looks up Java constructors and members from native code.
-keep class ai.onnxruntime.** { *; }
