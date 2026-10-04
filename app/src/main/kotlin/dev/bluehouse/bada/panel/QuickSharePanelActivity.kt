/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.panel

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dev.bluehouse.bada.R
import dev.bluehouse.bada.discovery.NearbyPeer
import dev.bluehouse.bada.discovery.NearbyPeerRoute
import dev.bluehouse.bada.discovery.UserFacingMediumFeatures
import dev.bluehouse.bada.discovery.bootstrap.BleGattInitialControlClient
import dev.bluehouse.bada.discovery.bootstrap.BleL2capInitialControlClient
import dev.bluehouse.bada.discovery.bootstrap.BluetoothClassicBootstrapClient
import dev.bluehouse.bada.discovery.medium.MediumRegistries
import dev.bluehouse.bada.discovery.wifi.AndroidWifiCapabilities
import dev.bluehouse.bada.protocol.connection.FileSource
import dev.bluehouse.bada.protocol.connection.OutboundConnection
import dev.bluehouse.bada.protocol.connection.OutboundConnectionState
import dev.bluehouse.bada.protocol.connection.TextSource
import dev.bluehouse.bada.protocol.medium.LocalWifiCapabilities
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.send.DocumentTreeFileSourceFactory
import dev.bluehouse.bada.send.SendBootstrapPlan
import dev.bluehouse.bada.send.SendPayloadResolution
import dev.bluehouse.bada.send.SendPayloadResolver
import dev.bluehouse.bada.send.SendPeerPickerController
import dev.bluehouse.bada.send.UriFileSourceFactory
import dev.bluehouse.bada.service.radio.RadioHelperClient
import dev.bluehouse.bada.service.radio.ShareRadioController
import dev.bluehouse.bada.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder
import dev.bluehouse.bada.service.receiver.OutboundSessionActiveHolder
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.service.receiver.ReceiverMasterSwitch
import dev.bluehouse.bada.service.receiver.TileVisibilityElevationHolder
import dev.bluehouse.bada.service.receiver.consent.ConsentModalRegistry
import java.security.SecureRandom
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One UI Quick Share-style bottom panel — the single surface that does
 * receive *and* send, including the transfer itself, without handing off to
 * the main app.
 */
internal class QuickSharePanelActivity : AppCompatActivity() {
    private var startedService = false

    private val handler = Handler(Looper.getMainLooper())

    private val consentWatch =
        object : Runnable {
            override fun run() {
                if (ConsentModalRegistry.instance.snapshotIds().isNotEmpty()) {
                    finish()
                    return
                }
                handler.postDelayed(this, CONSENT_WATCH_MS)
            }
        }

    private lateinit var openFilesLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>

    private var pickedUris: List<Uri> = emptyList()
    private var files: List<FileSource> = emptyList()
    private var texts: List<TextSource> = emptyList()

    private lateinit var payloadResolver: SendPayloadResolver
    private var peerPickerController: SendPeerPickerController? = null
    private var senderEndpointId: String = ""
    private var senderEndpointInfoBytes: ByteArray = ByteArray(0)
    private var activeConnection: OutboundConnection? = null
    private var transferJob: Job? = null

    private val shareRadios: ShareRadioController by lazy {
        ShareRadioController(this, "PacketPanelRadio")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (dev.bluehouse.bada.theme.ThemePreferences.isPitchBlack(this)) {
            theme.applyStyle(R.style.Theme_Packet_PitchBlack, true)
        }
        setContentView(R.layout.activity_quick_share_panel)
        dev.bluehouse.bada.theme.AccentApplier.apply(
            findViewById(android.R.id.content),
            dev.bluehouse.bada.theme.AccentApplier.parse(
                dev.bluehouse.bada.theme.ThemePreferences.from(this).customAccent(),
            ),
        )

        payloadResolver =
            SendPayloadResolver(
                UriFileSourceFactory(contentResolver),
                DocumentTreeFileSourceFactory(contentResolver),
            )

        openFilesLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                if (uris.isEmpty()) return@registerForActivityResult
                pickedUris = uris
                if (resolvePayload(uris)) showSendDevices()
            }

        openTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri == null) return@registerForActivityResult
                if (resolvePayload(listOf(treeUri), SendActivityFolder.ACTION)) showSendDevices()
            }

        findViewById<View>(R.id.qs_panel_root).setOnClickListener { dismiss() }
        findViewById<View>(R.id.qs_panel_close).setOnClickListener { dismiss() }

        val sheet = findViewById<View>(R.id.qs_panel_sheet)
        sheet.alpha = 0f
        sheet.translationY = dp(24).toFloat()
        sheet.animate().alpha(1f).translationY(0f).setDuration(220).start()
        findViewById<View>(R.id.qs_panel_orb)?.let { startAmbientPulse(it) }
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = dp(28) + bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.qs_panel_device_name).text = AdvertisedDeviceNames.resolve(this)

        wireModeSwitch()
        wireVisibilityButton()

        findViewById<Button>(R.id.qs_panel_select_files).setOnClickListener { launchFilePicker() }
        findViewById<Button>(R.id.qs_panel_send_folder).setOnClickListener { openTreeLauncher.launch(null) }
        findViewById<Button>(R.id.qs_panel_transfer_cancel).setOnClickListener { cancelTransfer() }

        beginReceiving()
    }


    /** Soft ambient pulse used on the receive orb. */
    private fun startAmbientPulse(view: View) {
        val animator =
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                view,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1f),
            )
        animator.duration = 1600
        animator.repeatMode = android.animation.ValueAnimator.REVERSE
        animator.repeatCount = android.animation.ValueAnimator.INFINITE
        animator.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun wireModeSwitch() {
        val receiveContainer = findViewById<View>(R.id.qs_panel_receive_container)
        val sendContainer = findViewById<View>(R.id.qs_panel_send_container)
        val receiveTab = findViewById<View>(R.id.qs_panel_mode_receive)
        val sendTab = findViewById<View>(R.id.qs_panel_mode_send)
        fun select(send: Boolean) {
            receiveContainer.isVisible = !send
            sendContainer.isVisible = send
            receiveTab.setBackgroundResource(if (send) android.R.color.transparent else R.drawable.qs_pill_segment_active)
            sendTab.setBackgroundResource(if (send) R.drawable.qs_pill_segment_active else android.R.color.transparent)
            (receiveTab as android.view.ViewGroup).getChildAt(1).let {
                (it as TextView).setTextColor(getColor(if (send) R.color.qs_text_secondary else R.color.qs_text_primary))
            }
            (sendTab as android.view.ViewGroup).getChildAt(1).let {
                (it as TextView).setTextColor(getColor(if (send) R.color.qs_text_primary else R.color.qs_text_secondary))
            }
        }
        receiveTab.setOnClickListener { select(false) }
        sendTab.setOnClickListener { select(true) }
        select(false)
    }

    private fun wireVisibilityButton() {
        val button = findViewById<Button>(R.id.qs_panel_always_visible_button)
        val caption = findViewById<TextView>(R.id.qs_panel_always_visible_caption)
        if (!ReceiverMasterSwitch.isEnabled(this)) {
            button.isEnabled = false
            button.alpha = 0.5f
            caption.setText(R.string.main_master_off_caption)
            return
        }
        fun render() {
            val active = MdnsVisibilityOverrideHolder.isActive
            button.setText(if (active) R.string.main_always_visible_title else R.string.main_always_visible_off_title)
            button.setBackgroundResource(if (active) R.drawable.qs_button_primary else R.drawable.qs_button_secondary)
            button.setTextColor(getColor(if (active) android.R.color.white else R.color.qs_text_primary))
            caption.setText(if (active) R.string.main_always_visible_caption_on else R.string.main_always_visible_caption_off)
        }
        button.setOnClickListener {
            MdnsVisibilityOverrideHolder.setAlwaysVisible(!MdnsVisibilityOverrideHolder.isActive)
            render()
        }
        render()
    }

    private fun beginReceiving() {
        if (!ReceiverMasterSwitch.isEnabled(this)) return
        val priorOverrideActive = MdnsVisibilityOverrideHolder.isActive
        if (!priorOverrideActive) {
            MdnsVisibilityOverrideHolder.setAlwaysVisible(true)
            try {
                ReceiverForegroundService.startWithRadios(this)
                startedService = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Receiver start rejected from panel", e)
                MdnsVisibilityOverrideHolder.setAlwaysVisible(false)
            }
        }
        TileVisibilityElevationHolder.arm(priorOverrideActive, startedService)
    }

    private fun launchFilePicker() {
        try {
            openFilesLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "OpenMultipleDocuments not resolvable", e)
            Toast.makeText(this, R.string.main_send_files_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Resolve the picked URIs into transfer payload sources. */
    private fun resolvePayload(uris: List<Uri>, action: String = Intent.ACTION_SEND): Boolean {
        val intent =
            Intent(this, dev.bluehouse.bada.send.SendActivityInApp::class.java).apply {
                this.action = action
                if (action == Intent.ACTION_SEND && uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else if (action == Intent.ACTION_SEND && uris.size > 1) {
                    this.action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                } else {
                    data = uris.first()
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return when (val resolved = payloadResolver.resolve(intent)) {
            is SendPayloadResolution.Payload -> {
                files = resolved.files
                texts = resolved.texts
                true
            }
            else -> {
                Toast.makeText(this, R.string.main_send_files_pick_failed, Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private fun showSendDevices() {
        findViewById<View>(R.id.qs_panel_select_files).isVisible = false
        findViewById<View>(R.id.qs_panel_send_folder).isVisible = false
        findViewById<View>(R.id.qs_panel_picked_container).isVisible = true
        val names = pickedUris.map { uriLabel(it) }
        val count = names.size.coerceAtLeast(1)
        findViewById<TextView>(R.id.qs_panel_files_summary).text =
            names.firstOrNull() ?: getString(R.string.qs_selected_files)
        findViewById<TextView>(R.id.qs_panel_files_size).text =
            resources.getQuantityString(R.plurals.qs_file_count, count, count)
        val moreChip = findViewById<TextView>(R.id.qs_panel_files_more)
        val list = findViewById<LinearLayout>(R.id.qs_panel_files_list)
        names.forEach { name ->
            list.addView(
                TextView(this).apply {
                    text = name
                    setTextColor(getColor(R.color.qs_text_secondary))
                    textSize = 13f
                    setPadding(0, dp(5), 0, dp(5))
                },
            )
        }
        if (names.size > 1) {
            moreChip.isVisible = true
            moreChip.text = getString(R.string.qs_more_files, names.size - 1)
            findViewById<View>(R.id.qs_panel_files_card).setOnClickListener {
                val expand = !list.isVisible
                list.isVisible = expand
                moreChip.text =
                    if (expand) getString(R.string.qs_cancel) else getString(R.string.qs_more_files, names.size - 1)
            }
        } else {
            moreChip.isVisible = false
        }
        startDiscovery()
    }

    private fun uriLabel(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"

    private fun startDiscovery() {
        OutboundSessionActiveHolder.setOutboundSessionActive(true)
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BOTH)
        senderEndpointId = OutboundConnection.generateEndpointId()
        val info =
            EndpointInfoFactory.build(AdvertisedDeviceNames.resolve(this))
        senderEndpointInfoBytes = info.serialize()
        val controller =
            SendPeerPickerController(
                context = this,
                peerList = findViewById(R.id.qs_panel_send_peer_list),
                emptyState = findViewById(R.id.qs_panel_send_empty),
                networkHint = findViewById(R.id.qs_panel_send_hint),
                bluetoothOffBanner = findViewById(R.id.qs_panel_send_bt_banner),
                bluetoothOffAction = findViewById(R.id.qs_panel_send_bt_action),
                subtitle = findViewById(R.id.qs_panel_send_subtitle),
                lifecycle = lifecycle,
                scope = lifecycleScope,
                onPeerSelected = ::onPeerPicked,
                logDiagnostic = { msg -> Log.d(TAG, msg) },
                senderEndpointId = senderEndpointId,
                onEnableBluetoothRequested = {
                    runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                },
            )
        peerPickerController = controller
        controller.start()
    }

    /** A device was tapped: run the whole transfer inside the panel. */
    private fun onPeerPicked(peer: NearbyPeer) {
        peerPickerController?.suspendPicker()
        val plan = SendBootstrapPlan.resolve(peer = peer)
        val route = (plan.action as? SendBootstrapPlan.Action.Direct)?.route
        if (route == null) {
            Toast.makeText(this, R.string.qs_tx_reach_failed, Toast.LENGTH_LONG).show()
            return
        }
        showTransferUi(peer)
        transferJob =
            lifecycleScope.launch {
                val connection = buildConnection(route)
                if (connection == null) {
                    renderTransferStatus(getString(R.string.qs_tx_failed))
                    return@launch
                }
                activeConnection = connection
                val collector = launch { connection.state.collect { renderTransfer(it) } }
                try {
                    connection.run(files, texts)
                } finally {
                    collector.cancel()
                    activeConnection = null
                }
            }
    }

    private suspend fun buildConnection(route: NearbyPeerRoute): OutboundConnection? {
        val mediumRegistry = MediumRegistries.defaultForContext(applicationContext)
        val wifiCapabilities = AndroidWifiCapabilities.read(applicationContext)
        return when (route) {
            is NearbyPeerRoute.Lan ->
                OutboundConnection(
                    targetAddress = route.address,
                    port = route.port,
                    endpointId = senderEndpointId,
                    endpointInfo = senderEndpointInfoBytes,
                    mediumRegistry = mediumRegistry,
                    logger = { msg -> Log.d(TAG, msg) },
                    wifiCapabilities = wifiCapabilities,
                )
            is NearbyPeerRoute.BluetoothClassic -> {
                if (!UserFacingMediumFeatures.BLUETOOTH_CLASSIC_BOOTSTRAP_ROUTE_ENABLED &&
                    !UserFacingMediumFeatures.BLUETOOTH_CLASSIC_USER_FACING_ENABLED
                ) {
                    return null
                }
                val client = BluetoothClassicBootstrapClient(applicationContext)
                val transport: ConnectedTransport =
                    runCatching { client.connect(route.macAddress) }.getOrNull() ?: return null
                transportConnection(transport, mediumRegistry, wifiCapabilities)
            }
            is NearbyPeerRoute.BleL2cap -> {
                val client = BleL2capInitialControlClient(applicationContext)
                val transport: ConnectedTransport =
                    runCatching { client.connect(route.macAddress, route.psm) }.getOrNull() ?: return null
                transportConnection(transport, mediumRegistry, wifiCapabilities)
            }
            is NearbyPeerRoute.BleGatt -> {
                val client = BleGattInitialControlClient(applicationContext)
                val transport: ConnectedTransport =
                    runCatching { client.connect(route.macAddress) }.getOrNull() ?: return null
                transportConnection(transport, mediumRegistry, wifiCapabilities)
            }
        }
    }

    private fun transportConnection(
        transport: ConnectedTransport,
        mediumRegistry: MediumRegistry,
        wifiCapabilities: LocalWifiCapabilities,
    ): OutboundConnection =
        OutboundConnection(
            transport = transport,
            endpointId = senderEndpointId,
            endpointInfo = senderEndpointInfoBytes,
            mediumRegistry = mediumRegistry,
            logger = { msg -> Log.d(TAG, msg) },
            wifiCapabilities = wifiCapabilities,
        )

    private fun showTransferUi(peer: NearbyPeer) {
        findViewById<View>(R.id.qs_panel_picked_container).isVisible = false
        findViewById<View>(R.id.qs_panel_transfer_container).isVisible = true
        findViewById<TextView>(R.id.qs_panel_transfer_file).text =
            peer.displayName().ifBlank { getString(R.string.qs_selected_files) }
        renderTransferStatus(getString(R.string.qs_tx_preparing))
    }

    private fun renderTransferStatus(text: String) {
        findViewById<TextView>(R.id.qs_panel_transfer_status).text = text
    }

    private fun renderTransfer(state: OutboundConnectionState) {
        val bar = findViewById<ProgressBar>(R.id.qs_panel_transfer_bar)
        val pct = findViewById<TextView>(R.id.qs_panel_transfer_pct)
        when (state) {
            OutboundConnectionState.Idle -> renderTransferStatus(getString(R.string.qs_tx_preparing))
            OutboundConnectionState.Connecting -> renderTransferStatus(getString(R.string.qs_tx_connecting))
            OutboundConnectionState.Handshaking -> renderTransferStatus(getString(R.string.qs_tx_connecting))
            is OutboundConnectionState.AwaitingRemoteAcceptance -> renderTransferStatus(getString(R.string.qs_tx_accept))
            is OutboundConnectionState.Sending -> {
                renderTransferStatus(getString(R.string.qs_tx_sending))
                val percent = (state.progress.fraction * 100).toInt().coerceIn(0, 100)
                bar.progress = percent
                pct.text = "$percent%"
            }
            OutboundConnectionState.Completed -> {
                bar.progress = 100
                pct.text = "100%"
                renderTransferStatus(getString(R.string.qs_tx_done))
            }
            is OutboundConnectionState.Rejected -> renderTransferStatus(getString(R.string.qs_tx_failed))
            is OutboundConnectionState.Cancelled -> renderTransferStatus(getString(R.string.qs_tx_cancelled))
            is OutboundConnectionState.Failed -> renderTransferStatus(getString(R.string.qs_tx_failed))
        }
    }

    private fun cancelTransfer() {
        activeConnection?.cancel()
        transferJob?.cancel()
        renderTransferStatus(getString(R.string.qs_tx_cancelled))
    }

    private fun dismiss() {
        activeConnection?.cancel()
        peerPickerController?.stop()
        OutboundSessionActiveHolder.setOutboundSessionActive(false)
        shareRadios.restoreRadios(finishSession = true)
        TileVisibilityElevationHolder.restoreIfArmed(this)
        if (PanelScopedBackgroundPreferences.isEnabled(this)) {
            ReceiverForegroundService.stop(this)
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(consentWatch, CONSENT_WATCH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(consentWatch)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        dismiss()
    }

    private companion object {
        const val TAG = "PacketPanel"
        const val CONSENT_WATCH_MS = 400L
    }
}

/** Small helper so the panel can build the same EndpointInfo the activity does. */
private object EndpointInfoFactory {
    fun build(deviceName: String) =
        dev.bluehouse.bada.protocol.endpoint.EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = dev.bluehouse.bada.protocol.endpoint.DeviceType.PHONE,
            reserved = false,
            metadata =
                ByteArray(dev.bluehouse.bada.protocol.endpoint.EndpointInfo.METADATA_LEN)
                    .also { SecureRandom().nextBytes(it) },
            deviceName = deviceName,
        )
}

/** Folder-send action constant, referenced without importing the activity. */
private object SendActivityFolder {
    const val ACTION = "dev.bluehouse.bada.action.SEND_FOLDER"
}
