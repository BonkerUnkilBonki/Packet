/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import dev.bluehouse.bada.R

/** Applies a user-chosen hex accent to the key accent surfaces at runtime. */
public object AccentApplier {
    /** "#2E7DF6" / "2E7DF6" -> ARGB int, or null when unparseable. */
    public fun parse(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        return runCatching { Color.parseColor("#$h") }.getOrNull()
    }

    public fun apply(
        root: View,
        color: Int?,
    ) {
        val c = color ?: return
        val tint = ColorStateList.valueOf(c)
        intArrayOf(
            R.id.qs_panel_select_files,
            R.id.main_send_files_button,
            R.id.qs_panel_transfer_bar,
            R.id.qs_orb_view,
            R.id.qs_panel_orb,
        ).forEach { id -> root.findViewById<View>(id)?.backgroundTintList = tint }
        intArrayOf(
            R.id.qs_panel_files_more,
            R.id.qs_go_to_settings,
            R.id.settings_theme_value,
        ).forEach { id -> root.findViewById<TextView>(id)?.setTextColor(c) }
    }
}
