package jp.rimtty.codematch.scanner.inateck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.IlluminationState
import jp.rimtty.codematch.scanner.api.TuningState
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.ScannerFailureReasons
import jp.rimtty.codematch.scanner.ble.BleConnectionCoordinator
import jp.rimtty.codematch.scanner.ble.BleKnownDeviceStore
import jp.rimtty.codematch.scanner.ble.BleScannerSessionCoordinator
import jp.rimtty.codematch.scanner.ble.BleSessionCoordinatorFactory
import jp.rimtty.codematch.scanner.ble.BleSymbologyProfile
import jp.rimtty.codematch.scanner.ble.BleSymbologySession
import jp.rimtty.codematch.scanner.ble.BleSymbologySnapshotStore
import jp.rimtty.codematch.scanner.ble.SelectableBleExternalScanner

/** Fixed identity for recovery data created by the official Android SDK PoC. */
const val INATECK_ANDROID_SDK_PROFILE_IDENTITY =
    "inateck-android-sdk-2.0.0-area-name-v1"

internal const val INATECK_SETTINGS_ENDPOINT = "inateck-sdk:get-set-setting-info"
private const val TICK_INTERVAL_MILLIS = 250L
private const val RESET_RECONNECT_DELAY_MILLIS = 8_000L
private const val TUNING_RETRY_DELAY_MILLIS = 1_000L
private const val TUNING_ATTEMPT_LIMIT = 10

/**
 * Official-SDK-backed scanner bound by the `release` app variant.
 *
 * The SDK owns discovery, authentication, command framing, and GATT. Existing
 * BLE safety coordinators still own settings snapshot/restore, timeout order,
 * reconnect policy, and payload privacy.
 */
class InateckExternalScanner private constructor(
    private val delegate: SelectableBleExternalScanner,
    private val connection: BleConnectionCoordinator,
    private val transport: InateckSdkTransport,
    private val handler: Handler,
    private val nowMillis: () -> Long,
    private val gateway: InateckSdkGateway,
) : ExternalScanner, DefaultLifecycleObserver {
    private val illuminationObservers = mutableSetOf<ExternalScannerListener>()
    private var illuminationDeviceId: String? = null
    private var illuminationGeneration = 0L
    private var illuminationDeadline = 0L
    private var illuminationWaitingForDisconnect = false
    private var tuningGeneration = 0L
    private var tuningDeadline = 0L
    private var tuningRetryAt = 0L
    private var tuningAttempts = 0
    private var applicationForeground = false
    private val deferredSettings = InateckDeferredSettings()

    private fun drainDeferredSettings() {
        if (illuminationWaitingForDisconnect && transport.isLinkActive) return
        deferredSettings.drain().forEach { action ->
            when (action) {
                is InateckDeferredSettings.Action.Format -> delegate.setExpectedFormat(action.value)
                is InateckDeferredSettings.Action.ApplicationActive ->
                    delegate.setApplicationActive(action.value, nowMillis())
            }
        }
    }
    override var illuminationState = IlluminationState.UNKNOWN
        private set
    override var tuningState = TuningState.UNKNOWN
        private set
    private val illuminationConnectionObserver = object : ExternalScannerListener {
        override fun onConnectionStateChanged(state: ConnectionState) {
            // Invalidate immediately, even if disconnect/reconnect occurs
            // entirely between two timer ticks for the same device ID.
            if (!state.isConnected) {
                illuminationGeneration++
                illuminationDeviceId = null
                publishIllumination(IlluminationState.UNKNOWN)
                resetTuning()
                handler.post { if (!closed) drainDeferredSettings() }
            }
        }
    }

    private fun publishIllumination(state: IlluminationState) {
        if (illuminationState == state) return
        illuminationState = state
        (illuminationObservers.toList() + listOfNotNull(listener)).distinct().forEach {
            it.onIlluminationStateChanged(state)
        }
    }

    private fun publishTuning(state: TuningState) {
        if (tuningState == state) return
        tuningState = state
        (illuminationObservers.toList() + listOfNotNull(listener)).distinct().forEach {
            it.onTuningStateChanged(state)
        }
    }

    private fun resetTuning() {
        tuningGeneration++
        tuningAttempts = 0
        tuningRetryAt = 0L
        publishTuning(TuningState.UNKNOWN)
    }

    private val settingsBusy: Boolean
        get() = illuminationWaitingForDisconnect ||
            illuminationState == IlluminationState.APPLYING ||
            tuningState == TuningState.APPLYING

    /**
     * Read tuning runs once per connection after the illumination check has
     * settled (ON / OFF / UNSUPPORTED). It uses the same 30 s deadline and
     * the same wait-for-disconnect fallback as illumination so a stuck SDK
     * task never stacks another command on the shared characteristic.
     */
    private fun refreshTuning() {
        if (illuminationWaitingForDisconnect) return
        val deviceId = connectedDevice?.id ?: return
        if (tuningState == TuningState.APPLYING && nowMillis() >= tuningDeadline) {
            tuningGeneration++
            illuminationWaitingForDisconnect = true
            publishTuning(TuningState.FAILED)
            delegate.disconnect()
            drainDeferredSettings()
            return
        }
        val illuminationSettled = illuminationState == IlluminationState.ON ||
            illuminationState == IlluminationState.OFF ||
            illuminationState == IlluminationState.UNSUPPORTED
        if (applicationForeground && tuningState == TuningState.UNKNOWN && illuminationSettled &&
            configurationState.isReady && nowMillis() >= tuningRetryAt
        ) {
            applyTuning(deviceId)
        }
    }

    private fun applyTuning(deviceId: String) {
        val generation = ++tuningGeneration
        tuningDeadline = nowMillis() + 30_000L
        publishTuning(TuningState.APPLYING)
        val accepted = gateway.applyTuning(deviceId) { outcome ->
            if (!closed && generation == tuningGeneration && connectedDevice?.id == deviceId) {
                publishTuning(
                    when (outcome) {
                        InateckTuningOutcome.UNSUPPORTED -> TuningState.UNSUPPORTED
                        InateckTuningOutcome.MATCHED -> TuningState.MATCHED
                        InateckTuningOutcome.APPLIED -> TuningState.APPLIED
                        InateckTuningOutcome.FAILED -> TuningState.FAILED
                    },
                )
                drainDeferredSettings()
            }
        }
        if (!accepted && generation == tuningGeneration) {
            // Another SDK operation owns the link right now (or this gateway
            // cannot tune). Retry on a later tick a bounded number of times.
            tuningAttempts++
            tuningRetryAt = nowMillis() + TUNING_RETRY_DELAY_MILLIS
            publishTuning(if (tuningAttempts >= TUNING_ATTEMPT_LIMIT) TuningState.UNSUPPORTED else TuningState.UNKNOWN)
            drainDeferredSettings()
        }
    }

    private fun refreshIllumination() {
        if (illuminationWaitingForDisconnect) {
            if (transport.isLinkActive) return
            illuminationWaitingForDisconnect = false
            drainDeferredSettings()
        }
        val deviceId = connectedDevice?.id
        if (deviceId != illuminationDeviceId) {
            illuminationDeviceId = deviceId
            illuminationGeneration++
            publishIllumination(IlluminationState.UNKNOWN)
            resetTuning()
        }
        if (deviceId == null) return
        if (illuminationState == IlluminationState.APPLYING && nowMillis() >= illuminationDeadline) {
            illuminationGeneration++
            illuminationWaitingForDisconnect = true
            publishIllumination(IlluminationState.FAILED)
            delegate.disconnect()
            drainDeferredSettings()
            return
        }
        if (applicationForeground && illuminationState == IlluminationState.UNKNOWN && configurationState.isReady) {
            setIllumination(false)
        }
    }

    override fun setIllumination(enabled: Boolean): Boolean {
        if (closed || illuminationWaitingForDisconnect || !applicationForeground || !configurationState.isReady || illuminationState == IlluminationState.APPLYING) return false
        val deviceId = connectedDevice?.id ?: return false
        illuminationDeviceId = deviceId
        val previous = illuminationState
        val generation = ++illuminationGeneration
        illuminationDeadline = nowMillis() + 30_000L
        publishIllumination(IlluminationState.APPLYING)
        val accepted = gateway.setIllumination(deviceId, enabled) { result ->
            if (!closed && generation == illuminationGeneration && connectedDevice?.id == deviceId) {
                publishIllumination(if (result.isSuccess) {
                    if (enabled) IlluminationState.ON else IlluminationState.OFF
                } else IlluminationState.FAILED)
                drainDeferredSettings()
            }
        }
        if (!accepted && generation == illuminationGeneration) {
            publishIllumination(previous)
            drainDeferredSettings()
        }
        return accepted
    }
    private var closed = false
    private var resetAcknowledged = false
    private var reconnectScheduled = false
    private val resetReconnect = Runnable {
        reconnectScheduled = false
        if (!closed && !gattSwitch.isActive) delegate.reconnectKnownDevice()
    }
    private val startupRecovery = InateckStartupRecovery {
        // Never let automatic recovery redirect or reset a connection the
        // operator (or the GATT-switch follow-up) has already started (#137).
        if (closed || gattSwitch.isActive || delegate.queuedSelection != null ||
            delegate.connectionState is ConnectionState.Connecting
        ) {
            false
        } else {
            delegate.reconnectKnownDevice()
        }
    }

    /** Follows a scanner restarted in GATT mode under a new identity (#137). */
    private val gattSwitch = InateckGattSwitch(
        operations = object : InateckGattSwitch.Operations {
            override val isLinkActive: Boolean
                get() = transport.isLinkActive
            override val isSearching: Boolean
                get() = delegate.connectionState is ConnectionState.Searching

            override fun forgetKnownDevice(deviceId: String) =
                connection.forgetKnownDevice(deviceId)

            override fun startDiscovery(): Boolean = delegate.startDiscovery()

            override fun connect(device: ScannerDevice): Boolean = delegate.connect(device)

            override fun requireManualSelection() {
                connection.publishFailure(ScannerFailureReasons.RESELECT_AFTER_GATT_SWITCH)
            }

            override fun diagnostic(message: String, error: Boolean) =
                connection.recordDiagnostic(message, error)
        },
        nowMillis = nowMillis,
    )

    private fun onGattModeEvent(event: InateckGattModeEvent) {
        if (closed) return
        when (event) {
            is InateckGattModeEvent.Confirmed ->
                connection.recordDiagnostic("Scanner Bluetooth mode confirmed: GATT (2)")
            is InateckGattModeEvent.Unknown ->
                connection.recordDiagnostic("Scanner Bluetooth mode could not be read; continuing")
            is InateckGattModeEvent.SwitchStarted -> {
                connection.recordDiagnostic(
                    "Scanner Bluetooth mode is ${event.mode}; applying GATT mode (2)",
                )
                gattSwitch.begin(ScannerDevice(event.deviceId, event.deviceName))
            }
            is InateckGattModeEvent.Restarting ->
                connection.recordDiagnostic("Scanner restart accepted after GATT mode change")
            is InateckGattModeEvent.SwitchFailed -> {
                connection.recordDiagnostic("GATT mode switch failed", error = true)
                gattSwitch.cancel()
            }
        }
    }

    /**
     * The operator's action always wins (#137): stop following a GATT switch
     * and drop a delayed reconnect scheduled after a transport reset.
     */
    private fun cancelAutomaticFollowUps() {
        gattSwitch.cancel()
        if (reconnectScheduled) {
            handler.removeCallbacks(resetReconnect)
            reconnectScheduled = false
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            transport.refreshReadiness()
            if (closed) return
            delegate.tick(nowMillis())
            gattSwitch.tick()
            reconcileTransportReset()
            refreshIllumination()
            refreshTuning()
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        gateway.setGattModeListener(::onGattModeEvent)
        transport.onDeviceDiscovered = { device -> if (!closed) gattSwitch.onDeviceDiscovered(device) }
        delegate.addListener(illuminationConnectionObserver)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        handler.post(ticker)
    }

    override val devices: List<ScannerDevice>
        get() = delegate.devices
    override val connectionState: ConnectionState
        get() = delegate.connectionState
    override val configurationState: ConfigurationState
        get() = delegate.configurationState
    override val diagnosticEvents: List<DiagnosticEvent>
        get() = delegate.diagnosticEvents
    override val connectedDevice: ScannerDevice?
        get() = delegate.connectedDevice
    override val isConnected: Boolean
        get() = delegate.isConnected
    override val isReadyForScanning: Boolean
        get() = delegate.isReadyForScanning
    override val supportsConnectionControls: Boolean = true
    override val expectedFormat: ScanFormat?
        get() = delegate.expectedFormat

    override var listener: ExternalScannerListener?
        get() = delegate.listener
        set(value) {
            delegate.listener = value
        }

    override fun addListener(listener: ExternalScannerListener): Boolean {
        illuminationObservers.add(listener)
        return delegate.addListener(listener)
    }

    override fun removeListener(listener: ExternalScannerListener): Boolean {
        illuminationObservers.remove(listener)
        return delegate.removeListener(listener)
    }

    override fun startDiscovery(): Boolean {
        transport.refreshReadiness()
        if (closed) return false
        cancelAutomaticFollowUps()
        return delegate.startDiscovery()
    }

    override fun stopDiscovery(): Boolean {
        transport.refreshReadiness()
        if (closed) return false
        cancelAutomaticFollowUps()
        return delegate.stopDiscovery()
    }

    override fun connect(device: ScannerDevice): Boolean {
        transport.refreshReadiness()
        if (closed) return false
        cancelAutomaticFollowUps()
        return delegate.connect(device)
    }

    override fun disconnect(): Boolean {
        transport.refreshReadiness()
        if (closed) return false
        cancelAutomaticFollowUps()
        return delegate.disconnect()
    }

    override fun reconnectKnownDevice(): Boolean {
        transport.refreshReadiness()
        if (closed) return false
        cancelAutomaticFollowUps()
        return delegate.reconnectKnownDevice()
    }

    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        transport.refreshReadiness()
        if (!closed && settingsBusy) {
            deferredSettings.offer(InateckDeferredSettings.Action.Format(format))
            return true
        }
        return if (closed) false else delegate.setExpectedFormat(format)
    }

    override fun onStart(owner: LifecycleOwner) {
        applicationForeground = true
        transport.refreshReadiness()
        if (closed) return
        if (settingsBusy) {
            deferredSettings.offer(InateckDeferredSettings.Action.ApplicationActive(true))
        } else delegate.setApplicationActive(true, nowMillis())
        // A process recreation restores the known identity synchronously, but
        // it must still explicitly start the connection. Retry on a later
        // foreground if the first attempt was blocked by Bluetooth or
        // runtime permission state.
        startupRecovery.onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        applicationForeground = false
        startupRecovery.onBackground()
        if (settingsBusy) {
            deferredSettings.offer(InateckDeferredSettings.Action.ApplicationActive(false))
        } else delegate.setApplicationActive(false, nowMillis())
    }

    fun close() {
        if (closed) return
        closed = true
        illuminationGeneration++
        tuningGeneration++
        gattSwitch.cancel()
        handler.removeCallbacks(resetReconnect)
        gateway.setGattModeListener(null)
        transport.onDeviceDiscovered = null
        illuminationObservers.clear()
        deferredSettings.clear()
        delegate.removeListener(illuminationConnectionObserver)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        handler.removeCallbacks(ticker)
        delegate.close()
        transport.close()
    }

    private fun reconcileTransportReset() {
        if (!delegate.isAwaitingTransportReset) {
            resetAcknowledged = false
            return
        }
        if (transport.isLinkActive || resetAcknowledged) return
        resetAcknowledged = true
        delegate.onTransportResetCompleted()
        if (!reconnectScheduled) {
            reconnectScheduled = true
            handler.postDelayed(resetReconnect, RESET_RECONNECT_DELAY_MILLIS)
        }
    }

    companion object {
        fun create(
            context: Context,
            handler: Handler = Handler(Looper.getMainLooper()),
            nowMillis: () -> Long = { System.currentTimeMillis() },
        ): InateckExternalScanner {
            val applicationContext = context.applicationContext
            val gateway = AndroidInateckSdkGateway(applicationContext, handler)
            val transport = InateckSdkTransport(
                gateway = gateway,
                nowMillis = nowMillis,
                scanDeliveryObserver = { kind ->
                    Log.println(
                        Log.INFO,
                        "CodeMatchInateck",
                        "scan-delivery=${kind.name.lowercase()}",
                    )
                },
            )
            val snapshotStore = BleSymbologySnapshotStore(
                applicationContext,
                INATECK_ANDROID_SDK_PROFILE_IDENTITY,
            )
            val knownDeviceStore = BleKnownDeviceStore(
                applicationContext,
                INATECK_ANDROID_SDK_PROFILE_IDENTITY,
            )
            val connection = BleConnectionCoordinator(
                transport = transport,
                knownDeviceStore = knownDeviceStore,
                nowMillis = nowMillis,
                discoveryTimeoutMillis = 6_000L,
                sustainedReconnectDelayMillis = 30_000L,
            )
            val delegate = SelectableBleExternalScanner(
                connectionCoordinator = connection,
                sessionFactory = BleSessionCoordinatorFactory { device ->
                    BleScannerSessionCoordinator(
                        connectionCoordinator = connection,
                        symbologySession = BleSymbologySession(
                            device = device,
                            transport = transport,
                            profile = BleSymbologyProfile(
                                settingsCharacteristicUuid = INATECK_SETTINGS_ENDPOINT,
                                codec = InateckAreaNameSymbologyCodec,
                                identity = INATECK_ANDROID_SDK_PROFILE_IDENTITY,
                            ),
                            snapshotStore = snapshotStore,
                            nowMillis = nowMillis,
                            // SDK 2.0.0 configures a 5-second FastBle timeout.
                            // setSettingInfo performs get/set/get and the
                            // adapter adds one exact readback, so keep the
                            // outer safety deadline beyond all four stages.
                            commandTimeoutMillis = 25_000L,
                            settingsReadTimeoutMillis = 6_000L,
                        ),
                    )
                },
            )
            return InateckExternalScanner(delegate, connection, transport, handler, nowMillis, gateway)
        }
    }
}
