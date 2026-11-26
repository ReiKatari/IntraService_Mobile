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

-keep,includedescriptorclasses class com.lantaservice.intra.data.storage.**$$serializer { *; }
-keepclassmembers class com.lantaservice.intra.data.storage.** {
    *** Companion;
}
-keepclasseswithmembers class com.lantaservice.intra.data.storage.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.lantaservice.intra.data.storage.** { *; }

-keep,includedescriptorclasses class com.lantaservice.intra.data.geo.**$$serializer { *; }
-keepclassmembers class com.lantaservice.intra.data.geo.** {
    *** Companion;
}
-keepclasseswithmembers class com.lantaservice.intra.data.geo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.lantaservice.intra.data.geo.** { *; }

-keep class com.lantaservice.intra.data.worker.** { *; }
-keep,includedescriptorclasses class com.lantaservice.intra.util.EquipmentScanResult$$serializer { *; }
-keep class com.lantaservice.intra.util.EquipmentScanResult { *; }
-keep,includedescriptorclasses class com.lantaservice.intra.util.PrinterCatalogEntry$$serializer { *; }
-keep class com.lantaservice.intra.util.PrinterCatalogEntry { *; }

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
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
