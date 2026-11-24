# Keep Kotlinx Serialization generated serializers and annotations
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lantaservice.intra.data.model.**$$serializer { *; }
-keepclassmembers class com.lantaservice.intra.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.lantaservice.intra.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.lantaservice.intra.data.model.** { *; }

# Keep Retrofit service interface and runtime annotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep interface com.lantaservice.intra.data.network.IntraApiService { *; }
-keepclassmembers interface com.lantaservice.intra.data.network.IntraApiService { *; }
-keep class com.lantaservice.intra.data.network.IntraResilientDns { *; }

# Keep CameraX Config provider
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }

# Silence benign desktop/optional OkHttp platform warnings on Android
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn retrofit2.Platform**
