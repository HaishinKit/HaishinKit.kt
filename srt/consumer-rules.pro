# JNI entry points use the class and method names.
-keep class com.haishinkit.srt.SrtSocket { native <methods>; }
-keep class com.haishinkit.srt.SrtSocketException { *; }
-keep class com.haishinkit.srt.SrtPerformanceData { *; }
-keep class com.haishinkit.srt.SrtLogger { native <methods>; }
-keep class com.haishinkit.srt.SrtSocket$EventWaiter { *; }
