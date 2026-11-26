# kotlinx.serialization keeps its generated serializers through the rules shipped in its jar.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keep,includedescriptorclasses class app.flytrack.wear.**$$serializer { *; }
-keepclassmembers class app.flytrack.wear.** {
    *** Companion;
}
-dontwarn kotlinx.serialization.**
