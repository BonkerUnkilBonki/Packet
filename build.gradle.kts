// Top-level build file. Plugins are declared with `apply false` so each module opts in via its own
// `plugins { ... }` block.
//
// NOTE (fork): the upstream root build applied the ktlint and detekt static-analysis plugins to
// every subproject. Those plugins resolve from plugins.gradle.org, which is not reachable in this
// build environment, so they have been dropped here — they are lint-only and do not affect the APK.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
