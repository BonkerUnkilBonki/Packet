// Packet fork: ktlint + detekt removed (Gradle Plugin Portal artifacts are not
// reachable from this build environment and are not needed to build the APK).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
