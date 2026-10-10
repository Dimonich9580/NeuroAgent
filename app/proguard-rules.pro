# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
# Shizuku binder + hidden APIs are resolved by reflection
-keep class rikka.shizuku.** { *; }
-keep class com.neuroagent.system.** { *; }
# JNI ABI: Java_com_neuroagent_llm_NativeLlama_{load,generate,free}
-keep class com.neuroagent.llm.NativeLlama { *; }
-keepclassmembers class com.neuroagent.llm.NativeLlama {
    native <methods>;
}
# generate() callback is invoked from native code via Function1.invoke
-keep class kotlin.jvm.functions.Function1 { *; }
