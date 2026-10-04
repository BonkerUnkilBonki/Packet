/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.bluehouse.bada.R
import dev.bluehouse.bada.panel.QuickSharePanelActivity
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder

/**
 * Quick Settings tile that slides the Quick Share panel up over the app the
 * user is currently in. The tile only launches the panel; the panel owns the
 * receiver visibility bump / restore for its own lifetime. Tapping the tile
 * never switches the user into Packet.
 */
internal class BadaQuickShareTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        openPanel()
        syncTile()
    }

    private fun openPanel() {
        val intent =
            Intent(this, QuickSharePanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending =
                    PendingIntent.getActivity(
                        this,
                        PANEL_REQUEST_CODE,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Panel launch from tile failed", e)
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val active = MdnsVisibilityOverrideHolder.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_bada_visible)
        val statusRes = if (active) R.string.qs_tile_subtitle_on else R.string.qs_tile_subtitle_off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(statusRes)
        }
        tile.contentDescription =
            getString(
                if (active) R.string.qs_tile_content_desc_on else R.string.qs_tile_content_desc_off,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(statusRes)
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "PacketQsTile"
        const val PANEL_REQUEST_CODE = 1
    }
}
