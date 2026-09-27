package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice

/** State of the setting handshake and the step-restricted scan session. */
sealed interface BleSymbologySessionState {
    data object Disconnected : BleSymbologySessionState
    data object LoadingSettings : BleSymbologySessionState
    data object Ready : BleSymbologySessionState
    data class ApplyingSession(val expectedFormat: ScanFormat) : BleSymbologySessionState
    data object SessionReady : BleSymbologySessionState
    data object Restoring : BleSymbologySessionState
    data object AwaitingTransportReset : BleSymbologySessionState
    data object AwaitingReconnect : BleSymbologySessionState
    data class Failed(val reason: String) : BleSymbologySessionState
}

/**
 * Protocol-neutral owner of scanner symbology settings.
 *
 * The adapter supplies the discovered settings characteristic endpoint and a
 * [BleSymbologyCodec], then translates [BleTransport.read]/[BleTransport.write]
 * to Android BluetoothGatt or a vendor SDK. No scanner UUID, wire encoding,
 * flag range, vendor command, or SDK class is fixed in this module.
 *
 * Each step enables only its expected physical symbology. Before starting, a fresh full
 * device inventory is required and is persisted under this scanner's device
 * id. The inventory is retained until restoration succeeds on this scanner,
 * including after a command/read timeout or process interruption; other
 * scanners keep their own records and are never blocked by this one.
 */
class BleSymbologySession(
    private val device: ScannerDevice,
    private val transport: BleTransport,
    private val profile: BleSymbologyProfile,
    private val snapshotStore: SymbologySnapshotStore,
    private val diagnostics: BleDiagnosticLog = BleDiagnosticLog(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
    private val settingsReadTimeoutMillis: Long = DEFAULT_SETTINGS_READ_TIMEOUT_MILLIS,
) {
    init {
        require(commandTimeoutMillis > 0) { "commandTimeoutMillis must be positive" }
        require(settingsReadTimeoutMillis > 0) { "settingsReadTimeoutMillis must be positive" }
        require(profile.identity == snapshotStore.profileIdentity) {
            "BLE profile and snapshot store identities must match"
        }
    }

    private enum class Operation {
        RECOVERY,
        START_SESSION,
        RESTORE_SESSION,
    }

    /** Why a restore was requested; background resumes the logical step. */
    private enum class RestoreIntent {
        END_SESSION,
        BACKGROUND,
    }

    private var mutableState: BleSymbologySessionState = BleSymbologySessionState.Disconnected
    private var mutableConfiguration: ConfigurationState = ConfigurationState.Unavailable
    private var mutableExpectedFormat: ScanFormat? = null
    private var freshSnapshot: SymbologySnapshot? = null
    private var activeSnapshot: SymbologySnapshot? = null
    private var sessionActive = false
    private var pendingSettingsRead: PendingSettingsRead? = null
    private var pendingRestoreIntent: RestoreIntent? = null
    private var suspendedForBackground = false
    private var suspendedExpectedFormat: ScanFormat? = null
    private var connectionGeneration = 0L
    private var commandGeneration = 0L
    private var listener: ((BleSymbologySessionState, ConfigurationState) -> Unit)? = null

    private val commandQueue = BleCommandQueue(
        dispatch = { command, completion ->
            transport.write(
                characteristicUuid = command.characteristicUuid,
                payload = command.payload,
            ) { result ->
                completion(
                    result.fold(
                        onSuccess = { BleCommandOutcome.Succeeded },
                        onFailure = { BleCommandOutcome.Failed("BLE setting command failed") },
                    ),
                )
            }
        },
        nowMillis = nowMillis,
    )

    val state: BleSymbologySessionState get() = mutableState
    /** Device identity this protocol session is bound to. */
    val scannerDevice: ScannerDevice get() = device
    val configurationState: ConfigurationState get() = mutableConfiguration
    val expectedFormat: ScanFormat? get() = mutableExpectedFormat
    val physicalMode: BleSymbologyMode
        get() = if (sessionActive) {
            BleSymbologyMode.forExpectedFormat(mutableExpectedFormat)
        } else {
            BleSymbologyMode.UNRESTRICTED
        }
    val preSessionSnapshot: SymbologySnapshot? get() = activeSnapshot
    val currentSnapshot: SymbologySnapshot? get() = freshSnapshot
    val diagnosticEvents get() = diagnostics.snapshot()
    val isSessionActive: Boolean get() = sessionActive
    /** True while a backgrounded session can be resumed after baseline restore. */
    val isSuspendedForBackground: Boolean get() = suspendedForBackground
    val isSettingsReadPending: Boolean get() = pendingSettingsRead != null
    /** True while a settings read or command has been issued and not yet settled. */
    val isOperationInFlight: Boolean
        get() = pendingSettingsRead != null || commandQueue.isInFlight
    val isReadyForScanning: Boolean
        get() = mutableState == BleSymbologySessionState.SessionReady &&
            mutableConfiguration.isReady
    val isCommandBlockedAfterTimeout: Boolean get() = commandQueue.blockedAfterTimeout

    fun setListener(listener: ((BleSymbologySessionState, ConfigurationState) -> Unit)?) {
        this.listener = listener
    }

    /**
     * Starts a fresh settings read after the transport reports a connection.
     * Only this scanner's own persisted snapshot is consulted: when it exists
     * it is restored before Ready, while a snapshot still pending for another
     * scanner is left untouched for that scanner's next connection (#135).
     */
    fun onConnected(): Boolean {
        if (mutableState == BleSymbologySessionState.AwaitingTransportReset ||
            pendingSettingsRead != null
        ) return false
        if (mutableState !is BleSymbologySessionState.Disconnected &&
            mutableState !is BleSymbologySessionState.AwaitingReconnect &&
            mutableState !is BleSymbologySessionState.Failed
        ) return false
        val generation = ++connectionGeneration
        pendingSettingsRead = PendingSettingsRead(generation, nowMillis())
        mutableState = BleSymbologySessionState.LoadingSettings
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        diagnostics.configuration("Scanner settings read requested")
        val accepted = try {
            transport.read(profile.settingsCharacteristicUuid) { result ->
                if (generation != connectionGeneration ||
                    pendingSettingsRead?.generation != generation
                ) return@read
                // Clear this before invoking the codec and any downstream
                // callback so a synchronous adapter cannot start a second
                // read against the same request generation.
                pendingSettingsRead = null
                val snapshot = result.getOrNull()?.let { payload ->
                    runCatching {
                        profile.codec.decodeSnapshot(
                            deviceId = device.id,
                            payload = payload,
                            capturedAtMillis = nowMillis(),
                        )
                    }.getOrNull()
                }
                if (snapshot == null || !snapshot.hasRequiredSessionSymbols()) {
                    fail("Scanner settings are incomplete")
                    return@read
                }
                if (snapshot.deviceId != device.id) {
                    fail("Scanner settings belong to another device")
                    return@read
                }
                freshSnapshot = snapshot
                val persistedResult = runCatching {
                    snapshotStore.read(device.id)
                }.getOrElse {
                    SymbologySnapshotReadResult.Rejected(
                        "Saved scanner settings could not be read",
                    )
                }
                val persisted = when (persistedResult) {
                    is SymbologySnapshotReadResult.Found -> persistedResult.snapshot
                    SymbologySnapshotReadResult.Missing -> null
                    is SymbologySnapshotReadResult.Rejected -> {
                        fail(persistedResult.reason)
                        return@read
                    }
                }
                // The store is keyed by device, so this only guards against a
                // broken store adapter: never apply another scanner's record.
                if (persisted != null && persisted.deviceId != device.id) {
                    fail("Saved scanner settings belong to another device")
                    return@read
                }
                if (persisted != null) {
                    val recovery = mergeCurrentAreas(snapshot, persisted)
                    if (recovery == null) {
                        fail("Saved scanner settings no longer match the device inventory")
                        return@read
                    }
                    mutableState = BleSymbologySessionState.Restoring
                    apply(
                        operation = Operation.RECOVERY,
                        settings = recovery.settings,
                        expectedFormat = null,
                    )
                } else {
                    mutableState = BleSymbologySessionState.Ready
                    mutableConfiguration = ConfigurationState.Ready
                    emit()
                    diagnostics.configuration("Scanner settings read successfully")
                }
            }
        } catch (_: Exception) {
            if (pendingSettingsRead?.generation == generation) {
                pendingSettingsRead = null
                connectionGeneration++
                fail("Scanner settings read could not start")
            }
            return false
        }
        if (!accepted && pendingSettingsRead?.generation == generation) {
            pendingSettingsRead = null
            connectionGeneration++
            fail("Scanner settings read could not start")
        }
        return accepted
    }

    /**
     * Begins a session with only the expected symbol enabled.
     */
    fun startSession(expectedFormat: ScanFormat): Boolean {
        if (sessionActive) return setExpectedFormat(expectedFormat)
        val original = freshSnapshot
        if (mutableState != BleSymbologySessionState.Ready || original == null) return false
        if (!original.hasRequiredSessionSymbols()) return false

        // Persist before the first setting write. A process death after this
        // point leaves enough information for the next connection to restore
        // every reported symbology value.
        try {
            snapshotStore.save(original)
        } catch (_: Exception) {
            fail("Saved scanner settings could not be written")
            return false
        }
        activeSnapshot = original
        sessionActive = true
        mutableExpectedFormat = expectedFormat
        val restricted = original.forMode(BleSymbologyMode.forExpectedFormat(expectedFormat)) ?: run {
            fail("Scanner settings do not contain QR and Code 128")
            return false
        }
        return apply(Operation.START_SESSION, restricted, expectedFormat)
    }

    /**
     * Changes the physical restriction, coalescing in-flight requests to the
     * latest step. Input stays disabled until that setting has succeeded.
     */
    fun setExpectedFormat(expectedFormat: ScanFormat): Boolean {
        if (!sessionActive) return false
        if (mutableState != BleSymbologySessionState.SessionReady &&
            mutableState !is BleSymbologySessionState.ApplyingSession
        ) return false
        if (mutableExpectedFormat == expectedFormat) return true
        mutableExpectedFormat = expectedFormat
        if (mutableState is BleSymbologySessionState.ApplyingSession) {
            mutableState = BleSymbologySessionState.ApplyingSession(expectedFormat)
            emit()
            return true
        }
        val restricted = activeSnapshot?.forMode(BleSymbologyMode.forExpectedFormat(expectedFormat))
            ?: return false
        return apply(Operation.START_SESSION, restricted, expectedFormat)
    }

    /**
     * Restore the complete pre-session inventory; Ready follows success only.
     *
     * If a session-setting write is still in flight, restoration is queued
     * behind that write. This keeps the one-command boundary intact while
     * ensuring an end/background request cannot be overtaken by a late
     * callback from the setting that was being applied.
     */
    fun endSession(): Boolean {
        return requestRestore(RestoreIntent.END_SESSION)
    }

    /**
     * Pause a scanner session for app backgrounding or another input source.
     *
     * The physical scanner is restored to its pre-session inventory. The
     * logical format is retained so a foreground/input-source transition can
     * reapply the current step restriction without losing the current step.
     */
    fun suspendForBackground(): Boolean {
        if (!sessionActive) return false
        val previousSuspended = suspendedForBackground
        val previousExpectedFormat = suspendedExpectedFormat
        suspendedForBackground = true
        suspendedExpectedFormat = mutableExpectedFormat
        val requested = requestRestore(RestoreIntent.BACKGROUND)
        if (!requested && mutableState != BleSymbologySessionState.AwaitingTransportReset) {
            // A restore already owned by another lifecycle action (or a local
            // command-building failure) must not silently turn into a future
            // auto-resume request.
            suspendedForBackground = previousSuspended
            suspendedExpectedFormat = previousExpectedFormat
        }
        return requested
    }

    /**
     * Re-apply the fixed scanner mode after a successful background restore.
     * Returns false while a fresh connection/settings handshake is still
     * pending; the caller may retry after observing [BleSymbologySessionState.Ready].
     */
    fun resumeSuspendedSession(): Boolean {
        if (!suspendedForBackground) return false
        val expected = suspendedExpectedFormat
        if (expected == null) {
            // Camera (or another non-BLE source) had already cleared the
            // logical format. Baseline is already safe; there is no BLE mode
            // to resume.
            suspendedForBackground = false
            return true
        }
        if (sessionActive) return true
        if (mutableState != BleSymbologySessionState.Ready) return false
        return startSession(expected)
    }

    /**
     * Called after the owner has observed that the timed-out link is closed.
     * Only now is a blocked command generation released.
     */
    fun onTransportResetCompleted() {
        pendingSettingsRead = null
        commandQueue.resetAfterTransportReset()
        connectionGeneration++
        mutableState = BleSymbologySessionState.AwaitingReconnect
        mutableConfiguration = ConfigurationState.Unavailable
        emit()
    }

    /**
     * Called on any disconnect callback. The recovery snapshot remains in the
     * store until [onConnected] completes its restore command successfully.
     */
    fun onTransportDisconnected() {
        pendingSettingsRead = null
        pendingRestoreIntent = null
        connectionGeneration++
        commandQueue.cancel("transport disconnected")
        commandQueue.resetAfterTransportReset()
        mutableState = BleSymbologySessionState.Disconnected
        mutableConfiguration = ConfigurationState.Unavailable
        emit()
    }

    /** Host drives timeout handling from its coroutine/ticker. */
    fun tick(atMillis: Long = nowMillis()): BleCommandTickResult {
        val pendingRead = pendingSettingsRead
        if (pendingRead != null &&
            atMillis - pendingRead.startedAtMillis >= settingsReadTimeoutMillis
        ) {
            pendingSettingsRead = null
            connectionGeneration++
            mutableState = BleSymbologySessionState.AwaitingTransportReset
            mutableConfiguration = ConfigurationState.Unavailable
            emit()
            diagnostics.error("Scanner settings read timed out; transport reset required")
            transport.disconnect(device)
            return BleCommandTickResult.Noop
        }
        val result = commandQueue.tick(atMillis)
        if (result is BleCommandTickResult.TimedOut) {
            // Keep the snapshot, refuse all additional commands, and require
            // the owner to close the link before attempting reconnection.
            mutableState = BleSymbologySessionState.AwaitingTransportReset
            mutableConfiguration = ConfigurationState.Unavailable
            emit()
            diagnostics.error("Scanner settings command timed out; transport reset required")
            transport.disconnect(device)
        }
        return result
    }

    private fun requestRestore(intent: RestoreIntent): Boolean {
        val original = activeSnapshot ?: return false
        if (!sessionActive) return false
        if (mutableState == BleSymbologySessionState.AwaitingTransportReset) return false
        if (mutableState == BleSymbologySessionState.Restoring) return false

        if (commandQueue.isInFlight) {
            // START_SESSION is the only command that can be in flight while
            // sessionActive is true outside a restore. Defer the restore until
            // its callback is accepted by the command queue.
            pendingRestoreIntent = intent
            mutableState = BleSymbologySessionState.Restoring
            mutableConfiguration = ConfigurationState.Configuring
            emit()
            return true
        }

        return beginRestore(original, intent)
    }

    private fun beginRestore(
        original: SymbologySnapshot,
        intent: RestoreIntent,
    ): Boolean {
        pendingRestoreIntent = null
        mutableState = BleSymbologySessionState.Restoring
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        return apply(
            operation = Operation.RESTORE_SESSION,
            settings = original.settings,
            expectedFormat = null,
            preserveExpectedFormat = intent == RestoreIntent.BACKGROUND,
        )
    }

    private fun apply(
        operation: Operation,
        settings: List<ScannerSettingItem>,
        expectedFormat: ScanFormat?,
        preserveExpectedFormat: Boolean = false,
    ): Boolean {
        val snapshot = SymbologySnapshot(device.id, settings, nowMillis())
        val commands = SymbologySettings.commandsFor(snapshot, BleSymbologyMode.UNRESTRICTED)
        if (commands == null) {
            fail("Scanner settings command could not be built")
            return false
        }
        val encodedPayload = runCatching { profile.codec.encodeCommands(commands) }.getOrNull()
            ?: run {
                fail("Scanner settings command could not be encoded")
                return false
            }
        if (encodedPayload.isEmpty()) {
            fail("Scanner settings command could not be encoded")
            return false
        }
        val command = BleCommand(
            id = "symbology-${++commandGeneration}",
            characteristicUuid = profile.settingsCharacteristicUuid,
            payload = encodedPayload,
            timeoutMillis = commandTimeoutMillis,
        )
        if (operation == Operation.START_SESSION) {
            mutableState = BleSymbologySessionState.ApplyingSession(
                expectedFormat ?: ScanFormat.QR,
            )
        }
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        val submitted = commandQueue.submit(command) { outcome ->
            when (outcome) {
                BleCommandOutcome.Succeeded -> {
                    when (operation) {
                        Operation.RECOVERY -> {
                            if (clearPersistedSnapshot()) {
                                freshSnapshot = snapshot
                                clearActiveSession(
                                    preserveExpectedFormat = suspendedForBackground,
                                )
                                mutableState = BleSymbologySessionState.Ready
                                mutableConfiguration = ConfigurationState.Ready
                                diagnostics.configuration("Scanner settings restored")
                            } else {
                                fail("Saved scanner settings could not be cleared")
                            }
                        }
                        Operation.START_SESSION -> {
                            val deferredRestore = pendingRestoreIntent
                            if (deferredRestore != null) {
                                val original = activeSnapshot
                                if (original == null) {
                                    fail("Saved scanner settings are unavailable")
                                } else {
                                    beginRestore(original, deferredRestore)
                                }
                            } else if (mutableExpectedFormat != expectedFormat) {
                                val latest = mutableExpectedFormat
                                val restricted = activeSnapshot?.forMode(
                                    BleSymbologyMode.forExpectedFormat(latest),
                                )
                                if (latest == null || restricted == null) {
                                    fail("Scanner settings are unavailable")
                                } else {
                                    apply(Operation.START_SESSION, restricted, latest)
                                }
                            } else {
                                mutableState = BleSymbologySessionState.SessionReady
                                mutableConfiguration = ConfigurationState.Ready
                                suspendedForBackground = false
                                suspendedExpectedFormat = null
                                diagnostics.configuration("Scanner session settings ready")
                            }
                        }
                        Operation.RESTORE_SESSION -> {
                            if (clearPersistedSnapshot()) {
                                freshSnapshot = snapshot
                                clearActiveSession(preserveExpectedFormat)
                                mutableState = BleSymbologySessionState.Ready
                                mutableConfiguration = ConfigurationState.Ready
                                diagnostics.configuration("Scanner settings restored")
                            } else {
                                fail("Saved scanner settings could not be cleared")
                            }
                        }
                    }
                    emit()
                }
                BleCommandOutcome.TimedOut -> {
                    // tick() owns the transport reset transition. Keeping this
                    // callback branch side-effect free avoids duplicate close
                    // requests when an adapter reports its own timeout.
                }
                is BleCommandOutcome.Failed -> fail("Scanner settings command failed")
                is BleCommandOutcome.Cancelled -> {
                    if (mutableState != BleSymbologySessionState.Disconnected) {
                        fail("Scanner settings command cancelled")
                    }
                }
            }
        }
        if (submitted is BleCommandSubmitResult.Rejected) {
            fail("Scanner settings command is not available")
            return false
        }
        return true
    }

    private fun mergeCurrentAreas(
        current: SymbologySnapshot,
        saved: SymbologySnapshot,
    ): SymbologySnapshot? {
        if (current.deviceId != saved.deviceId) return null
        val currentIdentities = current.settings.groupingBy(::stableIdentity).eachCount()
        val savedIdentities = saved.settings.groupingBy(::stableIdentity).eachCount()
        if (currentIdentities != savedIdentities) return null

        val savedValues = saved.settings.groupBy(::stableIdentity)
            .mapValues { (_, values) -> ArrayDeque(values) }
        val merged = current.settings.map { item ->
            val values = savedValues[stableIdentity(item)] ?: return null
            val savedItem = values.removeFirstOrNull() ?: return null
            item.copy(value = savedItem.value)
        }
        return SymbologySnapshot(current.deviceId, merged, current.capturedAtMillis)
    }

    /** Name/area/flag identify one reported symbology across a reconnect. */
    private fun stableIdentity(item: ScannerSettingItem): SymbologyIdentity =
        SymbologyIdentity(
            name = item.name.lowercase(),
            area = item.area,
            flag = item.flag,
        )

    private fun fail(reason: String) {
        mutableState = BleSymbologySessionState.Failed(reason)
        mutableConfiguration = ConfigurationState.Failed(reason)
        emit()
        diagnostics.error("Scanner configuration failed")
    }

    private fun clearActiveSession(preserveExpectedFormat: Boolean = false) {
        activeSnapshot = null
        sessionActive = false
        if (preserveExpectedFormat) {
            mutableExpectedFormat = suspendedExpectedFormat
        } else {
            mutableExpectedFormat = null
            suspendedForBackground = false
            suspendedExpectedFormat = null
        }
    }

    private fun clearPersistedSnapshot(): Boolean =
        runCatching {
            snapshotStore.clear(device.id) == SymbologySnapshotClearResult.Cleared
        }.getOrDefault(false)

    private data class PendingSettingsRead(
        val generation: Long,
        val startedAtMillis: Long,
    )

    private data class SymbologyIdentity(
        val name: String,
        val area: String,
        val flag: Int?,
    )

    private fun emit() {
        listener?.invoke(mutableState, mutableConfiguration)
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 3_000L
        const val DEFAULT_SETTINGS_READ_TIMEOUT_MILLIS = 3_000L
    }
}
