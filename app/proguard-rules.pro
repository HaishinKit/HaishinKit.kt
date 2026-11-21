# Library JNI/reflection entry points belong in each library's consumer rules.
# Keep this app free of blanket keeps so development catches missing consumer rules.

# Okio's optional nullability annotation has no runtime behavior on Android.
-dontwarn javax.annotation.Nullable
