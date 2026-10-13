# ERT release shrink rules.
#
# The app uses no reflection, so R8 full mode needs very little. The keeps below are belt-and-braces
# for the JSON models (kept verbatim so field names in any future debugging payload stay readable)
# and to silence the usual annotation-processing noise.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations

# Our own data: keep names so "Copy raw JSON" output lines up with the models.
-keep class com.ERT.app.core.** { *; }
-keep class com.ERT.app.ui.Load { *; }
-keep class com.ERT.app.ui.Load$* { *; }

# Required by the Compose compiler plugin's generated code.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# Third-party libraries ship their own R8 rules via META-INF; only silence missing optional deps.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn okhttp3.**
-dontwarn kotlinx.coroutines.debug.**
