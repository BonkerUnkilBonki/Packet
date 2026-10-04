/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.panel

import android.content.Context
import android.content.SharedPreferences

/** "Only run while the panel is open" preference (off by default). */
public class PanelScopedBackgroundPreferences(
    private val prefs: SharedPreferences,
) {
    public fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    public fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    public companion object {
        private const val PREFS_NAME = "packet.panel_scope"
        private const val KEY_ENABLED = "panel_scoped_background"

        public fun from(context: Context): PanelScopedBackgroundPreferences =
            PanelScopedBackgroundPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )

        public fun isEnabled(context: Context): Boolean = from(context).isEnabled()
    }
}
