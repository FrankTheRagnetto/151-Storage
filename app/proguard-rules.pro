# ponte JS <-> Kotlin usato dalle pagine in assets/viewer
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
