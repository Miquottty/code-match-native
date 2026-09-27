package jp.rimtty.codematch.scanner.inateck

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.data.BleDevice
import com.inateck.scanner.ble.BleListManager
import com.inateck.scanner.ble.BleScannerDevice
import com.inateck.scanner.ble.callback.BleScanResultCallBack
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BlePermissionState
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/** Production PoC gateway backed by Inateck's published Android 2.0.0 SDK. */
internal class AndroidInateckSdkGateway(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val notificationAccumulator: InateckNotificationAccumulator =
        InateckNotificationAccumulator(InateckJnaNotificationNativeParser()),
    private val hidOutputCommandProvider: InateckJnaHidOutputCommandProvider =
        InateckJnaHidOutputCommandProvider(),
    private val notificationObserver: (InateckNotificationKind) -> Unit = {},
) : InateckSdkGateway {
    private val application = context.applicationContext as Application
    private val bluetoothManager =
        application.getSystemService(BluetoothManager::class.java)

    private var activeDevice: BleScannerDevice? = null
    private var pendingDevice: BleScannerDevice? = null
    private var activeScanBytes: ((ByteArray) -> Unit)? = null
    private var activeDisconnect: ((Boolean) -> Unit)? = null
    private var manualDisconnectInFlight = false
    private var connectionAttempt = 0L
    private val operationGate = InateckOperationGate()
    private var disconnectingDevice: BleScannerDevice? = null
    private var closed = false
    private var gattModeListener: ((InateckGattModeEvent) -> Unit)? = null

    init {
        BleListManager.init(application)
        // The SDK demo enables FastBle logging. Disable it immediately; the
        // scanner PoC also strips android.util.Log calls with R8.
        InateckNotificationBridge.disableVendorLogging()
    }

    override val readiness: BleTransportReadiness
        get() = BleTransportReadiness(
            lifecycle = if (closed) {
                BleAdapterLifecycleState.DESTROYED
            } else {
                BleAdapterLifecycleState.FOREGROUND
            },
            availability = bluetoothAvailability(),
            discoveryPermission = permission(Manifest.permission.BLUETOOTH_SCAN),
            connectionPermission = permission(Manifest.permission.BLUETOOTH_CONNECT),
        )

    override fun startDiscovery(
        onDevice: (InateckSdkDevice) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        if (closed || activeDevice != null || pendingDevice != null || disconnectingDevice != null ||
            readiness.failureReason(forConnection = false) != null
        ) return false
        return runCatching {
            BleListManager.scan(
                object : BleScanResultCallBack {
                    override fun onScanStarted(scanResultList: List<BleScannerDevice>) {
                        dispatchDevices(scanResultList, onDevice)
                    }

                    override fun onScanning(device: BleScannerDevice) {
                        device.asGatewayDevice()?.let { discovered ->
                            dispatch { onDevice(discovered) }
                        }
                    }

                    override fun onScanFinished(scanResultList: List<BleScannerDevice>) {
                        dispatchDevices(scanResultList, onDevice)
                        dispatch(onFinished)
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    override fun stopDiscovery(): Boolean {
        if (closed) return false
        return runCatching {
            BleListManager.stopScan()
            true
        }.getOrDefault(false)
    }

    override fun connect(
        deviceId: String,
        onScanBytes: (ByteArray) -> Unit,
        onDisconnected: (unexpected: Boolean) -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        if (closed || activeDevice != null || pendingDevice != null || disconnectingDevice != null ||
            readiness.failureReason(forConnection = true) != null
        ) {
            return false
        }
        val device = findDevice(deviceId) ?: return false
        val attempt = ++connectionAttempt
        pendingDevice = device
        notificationAccumulator.reset()
        activeScanBytes = onScanBytes
        activeDisconnect = onDisconnected
        installDisconnectHandler(device, attempt, completion)
        var sdkConnectCallbackHandled = false
        var connectCompletionDelivered = false
        var outputSetupStarted = false
        var outputWriteSucceeded = false
        var outputResponseAccepted = false
        var outputSettleElapsed = false
        var earlyOutputResponse: ByteArray? = null
        var outputHandshakeHandled = false
        var outputHandshakeTimeout: Runnable? = null
        var modeCheckStarted = false

        fun finishOutputHandshakeIfReady() {
            if (outputHandshakeHandled || !outputWriteSucceeded ||
                !outputResponseAccepted || !outputSettleElapsed ||
                !isCurrentAttempt(device, attempt)
            ) {
                return
            }
            outputHandshakeHandled = true
            outputHandshakeTimeout?.let { mainHandler.removeCallbacks(it) }
            pendingDevice = null
            activeDevice = device
            if (!connectCompletionDelivered) {
                connectCompletionDelivered = true
                completion(Result.success(Unit))
            }
        }

        fun acceptOutputResponse(bytes: ByteArray) {
            if (outputHandshakeHandled || !isCurrentAttempt(device, attempt)) return
            if (!outputWriteSucceeded) {
                earlyOutputResponse = bytes.clone()
                return
            }
            if (!hidOutputCommandProvider.isSuccessfulResponse(bytes)) {
                safeProtocolLog("sdk-output-response=rejected")
                outputHandshakeHandled = true
                outputHandshakeTimeout?.let { mainHandler.removeCallbacks(it) }
                failPendingConnection(
                    device = device,
                    attempt = attempt,
                    connectCompletionDelivered = connectCompletionDelivered,
                    completion = completion,
                    reason = "Inateck SDK output configuration was rejected",
                ) { connectCompletionDelivered = true }
                return
            }
            safeProtocolLog("sdk-output-response=accepted")
            outputResponseAccepted = true
            finishOutputHandshakeIfReady()
        }

        fun startOutputSetup() {
            if (!isCurrentAttempt(device, attempt) || outputSetupStarted) return
            outputSetupStarted = true
            val command = hidOutputCommandProvider.commandForSdkOutput()
            if (command == null) {
                failPendingConnection(
                    device = device,
                    attempt = attempt,
                    connectCompletionDelivered = connectCompletionDelivered,
                    completion = completion,
                    reason = "Inateck SDK output command unavailable",
                ) { connectCompletionDelivered = true }
                return
            }
            val outputWriteTimeout = Runnable {
                if (!outputHandshakeHandled &&
                    isCurrentAttempt(device, attempt) &&
                    !connectCompletionDelivered
                ) {
                    outputHandshakeHandled = true
                    failPendingConnection(
                        device = device,
                        attempt = attempt,
                        connectCompletionDelivered = false,
                        completion = completion,
                        reason = "Inateck SDK output configuration timed out",
                    ) { connectCompletionDelivered = true }
                }
            }
            outputHandshakeTimeout = outputWriteTimeout
            mainHandler.postDelayed(
                outputWriteTimeout,
                SDK_OUTPUT_WRITE_TIMEOUT_MILLIS,
            )
            InateckNotificationBridge.writeSdkOutputCommand(
                device,
                command,
                object : InateckNotificationBridge.WriteCallback {
                    override fun onSuccess() {
                        dispatch {
                            if (outputHandshakeHandled ||
                                !isCurrentAttempt(device, attempt)
                            ) {
                                return@dispatch
                            }
                            outputWriteSucceeded = true
                            // The iOS adapter for this scanner family
                            // deliberately waits one second after the
                            // write-only SDK-output command. FF01 may
                            // deliver a late control response during
                            // this interval; keep it out of the first
                            // getSettingInfo task.
                            mainHandler.postDelayed(
                                {
                                    outputSettleElapsed = true
                                    finishOutputHandshakeIfReady()
                                },
                                SDK_OUTPUT_SETTLE_MILLIS,
                            )
                            earlyOutputResponse?.let { response ->
                                earlyOutputResponse = null
                                acceptOutputResponse(response)
                            }
                        }
                    }

                    override fun onFailure() {
                        dispatch {
                            if (outputHandshakeHandled ||
                                !isCurrentAttempt(device, attempt)
                            ) {
                                return@dispatch
                            }
                            outputHandshakeHandled = true
                            mainHandler.removeCallbacks(outputWriteTimeout)
                            failPendingConnection(
                                device = device,
                                attempt = attempt,
                                connectCompletionDelivered =
                                    connectCompletionDelivered,
                                completion = completion,
                                reason =
                                    "Inateck SDK output configuration failed",
                            ) { connectCompletionDelivered = true }
                        }
                    }
                },
            )
        }

        /**
         * Connect-time Bluetooth-mode check (#137, iOS parity). It runs before
         * the FF04 SDK-output handshake: a scanner outside GATT mode may not
         * answer that handshake, which used to fail the connection before the
         * mode could be read. GATT mode (or an unreadable mode) continues with
         * the unchanged, strict handshake. Any other mode is switched to GATT,
         * the scanner is restarted, and this connection then ends as a
         * failure; the host follows the restarted scanner.
         */
        fun switchToGattMode(mode: Int) {
            safeProtocolLog("gatt-mode=switching")
            gattModeListener?.invoke(
                InateckGattModeEvent.SwitchStarted(
                    deviceId = deviceId,
                    deviceName = device.name?.takeIf(String::isNotBlank) ?: "Inateck scanner",
                    mode = mode,
                ),
            )
            var switchHandled = false
            fun failSwitch(reason: String, switchFailed: Boolean) {
                if (switchHandled || !isCurrentAttempt(device, attempt)) return
                switchHandled = true
                if (switchFailed) {
                    safeProtocolLog("gatt-mode=switch-failed")
                    gattModeListener?.invoke(InateckGattModeEvent.SwitchFailed(deviceId))
                }
                failPendingConnection(
                    device = device,
                    attempt = attempt,
                    connectCompletionDelivered = connectCompletionDelivered,
                    completion = completion,
                    reason = reason,
                ) { connectCompletionDelivered = true }
            }
            val switchTimeout = Runnable {
                failSwitch("Inateck GATT mode switch timed out", switchFailed = true)
            }
            mainHandler.postDelayed(switchTimeout, GATT_MODE_SWITCH_TIMEOUT_MILLIS)
            val written = runCatching {
                device.messager.setSettingInfo(InateckGattMode.SWITCH_COMMAND) { update ->
                    dispatch {
                        if (switchHandled || !isCurrentAttempt(device, attempt)) return@dispatch
                        if (update.isFailure) {
                            mainHandler.removeCallbacks(switchTimeout)
                            failSwitch("Inateck GATT mode switch failed", switchFailed = true)
                            return@dispatch
                        }
                        val restartRequested = runCatching {
                            device.messager.setRestart { restart ->
                                dispatch {
                                    if (switchHandled || !isCurrentAttempt(device, attempt)) {
                                        return@dispatch
                                    }
                                    mainHandler.removeCallbacks(switchTimeout)
                                    if (restart.isFailure) {
                                        failSwitch(
                                            "Inateck restart after GATT mode switch failed",
                                            switchFailed = true,
                                        )
                                        return@dispatch
                                    }
                                    safeProtocolLog("gatt-mode=restarting")
                                    gattModeListener?.invoke(InateckGattModeEvent.Restarting(deviceId))
                                    // The restart normally drops the link, which
                                    // completes this attempt as a failure through
                                    // the disconnect handler. Close it explicitly
                                    // if the link survives the restart.
                                    mainHandler.postDelayed(
                                        {
                                            failSwitch(
                                                "Inateck scanner restarting in GATT mode",
                                                switchFailed = false,
                                            )
                                        },
                                        GATT_RESTART_FALLBACK_MILLIS,
                                    )
                                }
                            }
                        }.isSuccess
                        if (!restartRequested) {
                            mainHandler.removeCallbacks(switchTimeout)
                            failSwitch(
                                "Inateck restart after GATT mode switch failed",
                                switchFailed = true,
                            )
                        }
                    }
                }
            }.isSuccess
            if (!written) {
                mainHandler.removeCallbacks(switchTimeout)
                failSwitch("Inateck GATT mode switch failed", switchFailed = true)
            }
        }

        fun checkBluetoothMode() {
            var modeCheckHandled = false
            fun continueWithMode(mode: Int?) {
                if (modeCheckHandled || !isCurrentAttempt(device, attempt)) return
                modeCheckHandled = true
                when {
                    mode == null -> {
                        safeProtocolLog("gatt-mode=unknown")
                        gattModeListener?.invoke(InateckGattModeEvent.Unknown(deviceId))
                        startOutputSetup()
                    }
                    mode == InateckGattMode.GATT -> {
                        safeProtocolLog("gatt-mode=confirmed")
                        gattModeListener?.invoke(InateckGattModeEvent.Confirmed(deviceId))
                        startOutputSetup()
                    }
                    else -> switchToGattMode(mode)
                }
            }
            val readTimeout = Runnable { continueWithMode(null) }
            mainHandler.postDelayed(readTimeout, GATT_MODE_READ_TIMEOUT_MILLIS)
            clearPendingScanFrame()
            val requested = runCatching {
                device.messager.getSettingInfo { result ->
                    dispatch {
                        mainHandler.removeCallbacks(readTimeout)
                        continueWithMode(
                            result.getOrNull()
                                ?.map { entry -> entry.toMap() }
                                ?.let(InateckGattMode::bluetoothMode),
                        )
                    }
                }
            }.isSuccess
            if (!requested) {
                mainHandler.removeCallbacks(readTimeout)
                continueWithMode(null)
            }
        }

        return runCatching {
            device.connect { result ->
                dispatch {
                    if (!isCurrentAttempt(device, attempt)) return@dispatch
                    if (sdkConnectCallbackHandled) return@dispatch
                    sdkConnectCallbackHandled = true
                    if (result.isFailure) {
                        invalidateConnectionAttempt(attempt)
                        connectCompletionDelivered = true
                        completion(Result.failure(IllegalStateException("Inateck connection failed")))
                        return@dispatch
                    }
                    InateckNotificationBridge.install(
                        device,
                        object : InateckNotificationBridge.Callback {
                            override fun onReady() {
                                dispatch {
                                    if (isCurrentAttempt(device, attempt) && !modeCheckStarted) {
                                        modeCheckStarted = true
                                        checkBluetoothMode()
                                    }
                                }
                            }

                            override fun onFailure() {
                                dispatch {
                                    if (!isCurrentAttempt(device, attempt)) return@dispatch
                                    if (connectCompletionDelivered) return@dispatch
                                    failPendingConnection(
                                        device = device,
                                        attempt = attempt,
                                        connectCompletionDelivered = false,
                                        completion = completion,
                                        reason = "Inateck notification setup failed",
                                    ) { connectCompletionDelivered = true }
                                }
                            }

                            override fun onCommandTraffic() {
                                dispatch {
                                    if (isCurrentAttempt(device, attempt)) {
                                        clearPendingScanFrame()
                                    }
                                }
                            }

                            override fun onBytes(value: ByteArray) {
                                val copy = value.clone()
                                dispatch {
                                    if (!isCurrentAttempt(device, attempt)) {
                                        return@dispatch
                                    }
                                    if (outputSetupStarted && !outputHandshakeHandled) {
                                        acceptOutputResponse(copy)
                                        return@dispatch
                                    }
                                    acceptScanChunk(copy)
                                }
                            }
                        },
                    )
                }
            }
            true
        }.getOrElse {
            invalidateConnectionAttempt(attempt)
            false
        }
    }

    override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean {
        if (disconnectingDevice != null) return false
        val device = (activeDevice ?: pendingDevice)?.takeIf { it.mac == deviceId } ?: return false
        if (activeDevice == null && pendingDevice?.mac == device.mac) {
            return cancelPendingConnection(device, completion)
        }
        manualDisconnectInFlight = true
        val disconnectAttempt = ++connectionAttempt
        invalidateOperations()
        disconnectingDevice = device
        clearPendingScanFrame()
        return runCatching {
            device.disconnect { result ->
                dispatch {
                    if (disconnectingDevice?.mac != device.mac ||
                        disconnectAttempt != connectionAttempt
                    ) {
                        return@dispatch
                    }
                    manualDisconnectInFlight = false
                    disconnectingDevice = null
                    if (result.isSuccess) {
                        clearConnectionCallbacks()
                        completion(Result.success(Unit))
                    } else {
                        // A failed disconnect is not proof that the physical
                        // link is down. Preserve link state so a timeout reset
                        // cannot acknowledge and overlap it with a new link.
                        if (activeDevice?.mac == device.mac) {
                            installDisconnectHandler(device, disconnectAttempt) { }
                        }
                        completion(Result.failure(IllegalStateException("Inateck disconnect failed")))
                    }
                }
            }
            true
        }.getOrElse {
            manualDisconnectInFlight = false
            disconnectingDevice = null
            if (activeDevice?.mac == device.mac) {
                installDisconnectHandler(device, disconnectAttempt) { }
            }
            false
        }
    }

    override fun readSettings(
        deviceId: String,
        completion: (Result<List<Map<String, String>>>) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        clearPendingScanFrame()
        return runCatching {
            device.messager.getSettingInfo { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    finishOperation(operation)
                    @Suppress("UNCHECKED_CAST")
                    completion(result.map { it.map { entry -> entry.toMap() } })
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun writeSettings(
        deviceId: String,
        commandJson: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val requested = InateckAreaNameSettingsContract.parseCommand(commandJson) ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        clearPendingScanFrame()
        return runCatching {
            device.messager.setSettingInfo(commandJson) { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    if (result.isFailure) {
                        finishOperation(operation)
                        completion(Result.failure(IllegalStateException("Inateck settings write failed")))
                        return@dispatch
                    }
                    // The SDK's public write performs get/set/get but returns
                    // no final inventory. Read once more and require every
                    // requested symbology identity/value to be present before
                    // publishing Ready. The SDK also reports general settings
                    // (for example volume), which are intentionally ignored.
                    device.messager.getSettingInfo { verification ->
                        dispatch {
                            if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                            finishOperation(operation)
                            val actual = verification.getOrNull()?.map { it.toMap() }
                            if (actual != null &&
                                InateckAreaNameSettingsContract.containsRequestedSymbologies(
                                    settings = actual,
                                    requested = requested,
                                )
                            ) {
                                completion(Result.success(Unit))
                            } else {
                                completion(Result.failure(
                                    IllegalStateException("Inateck settings verification failed"),
                                ))
                            }
                        }
                    }
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun setIllumination(
        deviceId: String,
        enabled: Boolean,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        fun finish(success: Boolean) {
            if (!isCurrentOperation(device, attempt, operation)) return
            finishOperation(operation)
            completion(if (success) Result.success(Unit) else Result.failure(
                IllegalStateException("Inateck illumination verification failed"),
            ))
        }
        // Do not reset merely on submission. The bridge still clears partial
        // scans when command traffic arrives: it cannot yet distinguish
        // interleaved scan chunks from SDK replies while a task is running.
        return runCatching {
            device.messager.getSettingInfo { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    val inventory = result.getOrNull()?.map { it.toMap() }.orEmpty()
                    val setting = InateckIlluminationSettings.read(inventory)
                    val command = InateckIlluminationSettings.command(inventory, enabled)
                    if (setting == null || command == null) {
                        finish(false)
                        return@dispatch
                    }
                    runCatching {
                        device.messager.setSettingInfo(command) { written ->
                            dispatch {
                                if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                                if (written.isFailure) {
                                    finish(false)
                                    return@dispatch
                                }
                                runCatching {
                                    device.messager.getSettingInfo { verified ->
                                        dispatch {
                                            finish(InateckIlluminationSettings.confirmed(
                                                verified.getOrNull()?.map { it.toMap() }.orEmpty(),
                                                setting.area,
                                                enabled,
                                            ))
                                        }
                                    }
                                }.onFailure { finish(false) }
                            }
                        }
                    }.onFailure { finish(false) }
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun applyTuning(
        deviceId: String,
        completion: (InateckTuningOutcome) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        fun finish(outcome: InateckTuningOutcome) {
            if (!isCurrentOperation(device, attempt, operation)) return
            finishOperation(operation)
            completion(outcome)
        }
        // Same discipline as illumination: read the live inventory, write only
        // the differing items with the reported area, then confirm by readback.
        return runCatching {
            device.messager.getSettingInfo { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    val inventory = result.getOrNull()?.map { it.toMap() }
                    if (inventory == null) {
                        finish(InateckTuningOutcome.FAILED)
                        return@dispatch
                    }
                    if (InateckTuningSettings.present(inventory).isEmpty()) {
                        finish(InateckTuningOutcome.UNSUPPORTED)
                        return@dispatch
                    }
                    val differences = InateckTuningSettings.differences(inventory)
                    if (differences.isEmpty()) {
                        finish(InateckTuningOutcome.MATCHED)
                        return@dispatch
                    }
                    val command = InateckTuningSettings.command(inventory, differences)
                    if (command == null) {
                        finish(InateckTuningOutcome.FAILED)
                        return@dispatch
                    }
                    runCatching {
                        device.messager.setSettingInfo(command) { written ->
                            dispatch {
                                if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                                if (written.isFailure) {
                                    finish(InateckTuningOutcome.FAILED)
                                    return@dispatch
                                }
                                runCatching {
                                    device.messager.getSettingInfo { verified ->
                                        dispatch {
                                            val actual = verified.getOrNull()?.map { it.toMap() }
                                            finish(
                                                if (actual != null && InateckTuningSettings.confirmed(actual)) {
                                                    InateckTuningOutcome.APPLIED
                                                } else {
                                                    InateckTuningOutcome.FAILED
                                                },
                                            )
                                        }
                                    }
                                }.onFailure { finish(InateckTuningOutcome.FAILED) }
                            }
                        }
                    }.onFailure { finish(InateckTuningOutcome.FAILED) }
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun setGattModeListener(listener: ((InateckGattModeEvent) -> Unit)?) {
        gattModeListener = listener
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidateOperations()
        runCatching { BleListManager.stopScan() }
        activeDevice?.let { device ->
            runCatching { InateckNotificationBridge.stop(device) }
            runCatching { device.disconnect { _ -> } }
        }
        pendingDevice?.let { device -> runCatching { device.disconnect { _ -> } } }
        disconnectingDevice?.let { device -> runCatching { device.disconnect { _ -> } } }
        disconnectingDevice = null
        clearConnectionCallbacks()
    }

    /**
     * The SDK's public BleScannerDevice.disconnect() reports success while
     * FastBle is still connecting and therefore does not close that pending
     * BluetoothGatt. Use the SDK's bundled FastBle controller to synchronously
     * destroy its active and temporary link maps before acknowledging reset.
     *
     * This gateway owns the only FastBle scanner stack in the release
     * process, so the controller-wide reset cannot affect another client.
     */
    private fun cancelPendingConnection(
        device: BleScannerDevice,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val attempt = ++connectionAttempt
        invalidateOperations()
        manualDisconnectInFlight = true
        clearPendingScanFrame()
        BleListManager.disconnectHandler = null
        return runCatching {
            BleManager.getInstance().destroy()
            BleListManager.scannerDevices = mutableListOf()
            if (attempt != connectionAttempt || pendingDevice?.mac != device.mac) {
                error("Inateck pending connection changed during reset")
            }
            manualDisconnectInFlight = false
            clearConnectionCallbacks()
            // destroy() retains the FastBle singleton/controller, but the SDK
            // init call reapplies its supported timeout/split configuration.
            BleListManager.init(application)
            InateckNotificationBridge.disableVendorLogging()
            completion(Result.success(Unit))
            true
        }.getOrElse {
            manualDisconnectInFlight = false
            // Do not clear pendingDevice or acknowledge disconnection when a
            // controller reset cannot prove that the BluetoothGatt is closed.
            false
        }
    }

    private fun installDisconnectHandler(
        device: BleScannerDevice,
        attempt: Long,
        connectCompletion: (Result<Unit>) -> Unit,
    ) {
        BleListManager.disconnectHandler = { disconnected, isUserInitiated ->
            dispatch {
                if (disconnected.mac != device.mac || attempt != connectionAttempt) return@dispatch
                val manual = manualDisconnectInFlight || isUserInitiated
                manualDisconnectInFlight = false
                val wasPending = pendingDevice?.mac == device.mac
                val callback = activeDisconnect
                invalidateConnectionAttempt(attempt)
                if (!manual && wasPending) {
                    connectCompletion(Result.failure(IllegalStateException("Inateck connection closed")))
                } else if (!manual) {
                    callback?.invoke(true)
                }
            }
        }
    }

    private fun findDevice(deviceId: String): BleScannerDevice? {
        BleListManager.scannerDevices.firstOrNull { it.mac == deviceId }?.let { return it }
        if (permission(Manifest.permission.BLUETOOTH_CONNECT) != BlePermissionState.GRANTED) {
            return null
        }
        val bluetoothDevice = runCatching {
            bluetoothManager?.adapter?.getRemoteDevice(deviceId)
        }.getOrNull() ?: return null
        return BleScannerDevice(BleDevice(bluetoothDevice)).also { recreated ->
            BleListManager.scannerDevices =
                (BleListManager.scannerDevices + recreated).distinctBy { it.mac }.toMutableList()
        }
    }

    private fun dispatchDevices(
        devices: List<BleScannerDevice>,
        onDevice: (InateckSdkDevice) -> Unit,
    ) {
        devices.distinctBy { it.mac }.forEach { device ->
            device.asGatewayDevice()?.let { discovered ->
                dispatch { onDevice(discovered) }
            }
        }
    }

    private fun failPendingConnection(
        device: BleScannerDevice,
        attempt: Long,
        connectCompletionDelivered: Boolean,
        completion: (Result<Unit>) -> Unit,
        reason: String,
        markCompletionDelivered: () -> Unit,
    ) {
        if (connectCompletionDelivered || !isCurrentAttempt(device, attempt)) return
        manualDisconnectInFlight = true
        invalidateOperations()
        disconnectingDevice = device
        runCatching { InateckNotificationBridge.stop(device) }
        var disconnectHandled = false
        val forceReset = Runnable {
            if (disconnectHandled || attempt != connectionAttempt ||
                disconnectingDevice?.mac != device.mac
            ) {
                return@Runnable
            }
            disconnectHandled = true
            runCatching {
                BleListManager.disconnectHandler = null
                BleManager.getInstance().destroy()
                BleListManager.scannerDevices = mutableListOf()
                BleListManager.init(application)
                InateckNotificationBridge.disableVendorLogging()
            }
            finishFailedConnection(
                attempt = attempt,
                completion = completion,
                reason = reason,
                markCompletionDelivered = markCompletionDelivered,
            )
        }
        mainHandler.postDelayed(forceReset, FAILED_CONNECTION_DISCONNECT_TIMEOUT_MILLIS)
        val disconnectStarted = runCatching {
            device.disconnect {
                dispatch {
                    if (disconnectHandled || attempt != connectionAttempt ||
                        disconnectingDevice?.mac != device.mac
                    ) {
                        return@dispatch
                    }
                    disconnectHandled = true
                    mainHandler.removeCallbacks(forceReset)
                    finishFailedConnection(
                        attempt = attempt,
                        completion = completion,
                        reason = reason,
                        markCompletionDelivered = markCompletionDelivered,
                    )
                }
            }
            true
        }.getOrDefault(false)
        if (!disconnectStarted) {
            mainHandler.removeCallbacks(forceReset)
            forceReset.run()
        }
    }

    private fun finishFailedConnection(
        attempt: Long,
        completion: (Result<Unit>) -> Unit,
        reason: String,
        markCompletionDelivered: () -> Unit,
    ) {
        manualDisconnectInFlight = false
        disconnectingDevice = null
        invalidateConnectionAttempt(attempt)
        markCompletionDelivered()
        completion(Result.failure(IllegalStateException(reason)))
    }

    private fun BleScannerDevice.asGatewayDevice(): InateckSdkDevice? =
        mac?.takeIf(String::isNotBlank)?.let { stableId ->
            InateckSdkDevice(
                id = stableId,
                name = name?.takeIf(String::isNotBlank) ?: "Inateck scanner",
            )
        }

    private fun bluetoothAvailability(): BleAvailability {
        val adapter = bluetoothManager?.adapter ?: return BleAvailability.Unsupported
        return runCatching {
            if (adapter.isEnabled) BleAvailability.Ready else BleAvailability.PoweredOff
        }.getOrDefault(BleAvailability.Unauthorized)
    }

    private fun permission(name: String): BlePermissionState =
        if (application.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
            BlePermissionState.GRANTED
        } else {
            BlePermissionState.DENIED
        }

    private fun clearConnectionCallbacks() {
        clearPendingScanFrame()
        invalidateOperations()
        activeDevice = null
        pendingDevice = null
        activeScanBytes = null
        activeDisconnect = null
        BleListManager.disconnectHandler = null
    }

    private fun isCurrentAttempt(device: BleScannerDevice, attempt: Long): Boolean =
        !closed && disconnectingDevice == null && attempt == connectionAttempt &&
            (pendingDevice?.mac == device.mac || activeDevice?.mac == device.mac)

    private fun invalidateConnectionAttempt(attempt: Long) {
        if (attempt == connectionAttempt) connectionAttempt++
        clearConnectionCallbacks()
    }

    private fun beginOperation(device: BleScannerDevice, attempt: Long): Long? {
        if (activeDevice == null || !isCurrentAttempt(device, attempt)) {
            return null
        }
        return operationGate.begin()
    }

    private fun isCurrentOperation(
        device: BleScannerDevice,
        attempt: Long,
        operation: Long,
    ): Boolean = operationGate.isCurrent(operation) && isCurrentAttempt(device, attempt)

    private fun finishOperation(operation: Long) {
        operationGate.finish(operation)
    }

    private fun invalidateOperations() {
        operationGate.invalidate()
    }

    private fun acceptScanChunk(bytes: ByteArray): InateckNotificationOutcome {
        val outcome = notificationAccumulator.append(bytes)
        notificationObserver(outcome.toSafeKind())
        safeProtocolLog("notification=${outcome.toSafeKind().name.lowercase()}")
        when (outcome) {
            is InateckNotificationOutcome.Scan -> dispatchScanFrame(outcome.bytes)
            InateckNotificationOutcome.Error,
            InateckNotificationOutcome.Incomplete,
            -> Unit
        }
        return outcome
    }

    private fun InateckNotificationOutcome.toSafeKind(): InateckNotificationKind = when (this) {
        InateckNotificationOutcome.Incomplete -> InateckNotificationKind.INCOMPLETE
        is InateckNotificationOutcome.Scan -> InateckNotificationKind.SCAN
        InateckNotificationOutcome.Error -> InateckNotificationKind.ERROR
    }

    /**
     * Payload-free PoC trace. Scanner payloads, byte counts, device identities,
     * setting names/values, and native error text are deliberately excluded.
     * `Log.println` remains available while R8 removes vendor Log.d payloads.
     */
    private fun safeProtocolLog(message: String) {
        Log.println(Log.INFO, SAFE_PROTOCOL_LOG_TAG, message)
    }

    private fun dispatchScanFrame(frame: ByteArray) {
        activeScanBytes?.invoke(frame)
    }

    private fun clearPendingScanFrame() {
        notificationAccumulator.reset()
    }

    private companion object {
        const val SAFE_PROTOCOL_LOG_TAG = "CodeMatchInateck"
        const val SDK_OUTPUT_WRITE_TIMEOUT_MILLIS = 5_000L
        const val SDK_OUTPUT_SETTLE_MILLIS = 1_000L
        const val FAILED_CONNECTION_DISCONNECT_TIMEOUT_MILLIS = 5_000L
        const val GATT_MODE_READ_TIMEOUT_MILLIS = 6_000L
        const val GATT_MODE_SWITCH_TIMEOUT_MILLIS = 15_000L
        const val GATT_RESTART_FALLBACK_MILLIS = 5_000L
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }
}
