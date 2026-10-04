/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Light / Dark / Pitch black (AMOLED). */
public enum class AppThemeMode { LIGHT, DARK, PITCH_BLACK }

/**
 * App theme selection. Light + Dark ride AppCompat night mode; Pitch black is
 * Dark plus a pure-black overlay applied at runtime (see
 * `R.style.Theme_Packet_PitchBlack`).
 */
public class ThemePreferences(
    private val prefs: SharedPreferences,
) {
    public fun mode(): AppThemeMode =
        when (prefs.getString(KEY_MODE, AppThemeMode.LIGHT.name)) {
            AppThemeMode.DARK.name -> AppThemeMode.DARK
            AppThemeMode.PITCH_BLACK.name -> AppThemeMode.PITCH_BLACK
            else -> AppThemeMode.LIGHT
        }

    public fun setMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    public fun isPitchBlack(): Boolean = mode() == AppThemeMode.PITCH_BLACK

    /** Custom accent hex ("#RRGGBB") or null for the default accent. */
    public fun customAccent(): String? = prefs.getString(KEY_ACCENT, null)

    public fun setCustomAccent(hex: String?) {
        prefs.edit().apply {
            if (hex.isNullOrBlank()) remove(KEY_ACCENT) else putString(KEY_ACCENT, hex)
        }.apply()
    }

    public companion object {
        private const val PREFS_NAME = "packet.theme"
        private const val KEY_MODE = "mode"
        private const val KEY_ACCENT = "accent"

        public fun from(context: Context): ThemePreferences =
            ThemePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )

        public fun mode(context: Context): AppThemeMode = from(context).mode()

        public fun isPitchBlack(context: Context): Boolean = from(context).isPitchBlack()

        /** Push the light/dark half of the theme process-wide. */
        public fun applyNightMode(context: Context) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode(context)) {
                    AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }
    }
}
