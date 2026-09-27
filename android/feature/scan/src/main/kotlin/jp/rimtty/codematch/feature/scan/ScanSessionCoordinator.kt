package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.core.model.ScanLogEventKind
import jp.rimtty.codematch.core.model.ScanLogReason
import jp.rimtty.codematch.core.model.ScanLogSource
import jp.rimtty.codematch.core.model.ScanLogStep
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor

/**
 * Whether a Bluetooth scanner can be the input source (#145): its link is
 * established and its configuration has not failed. A scanner that is still
 * configuring counts; payloads are forwarded only once it is ready.
 */
val ExternalScanner.isBluetoothInputUsable: Boolean
    get() = connectionState.connectedDevice != null &&
        configurationState !is ConfigurationState.Failed

/**
 * Bridges scanner lifecycle callbacks to the pure [ScanReducer].
 *
 * This class owns only input-source policy (#145): while a scanner is usable
 * ([isBluetoothInputUsable]) Bluetooth is the source at session start, at the
 * QR step of every box, on a QR reread, when the scanner connects or becomes
 * ready, and when the scan screen returns to the foreground. An explicit
 * camera choice lasts for the current box only (QR → Code 128 → result; a
 * reread is the same box). A disconnect or configuration failure falls back
 * to camera without discarding the current step or QR value, and the source
 * returns to Bluetooth when the scanner is ready again. Persistence and
 * Compose state collection are intentionally outside this contract.
 */
class ScanSessionCoordinator(
    private val scanner: ExternalScanner,
    private val reducer: ScanReducer = ScanReducer(),
    autoAdvanceEnabled: Boolean = false,
    autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
    existingMatchedCount: Int = 0,
    private val cameraStabilizer: ScanStabilizer = ScanStabilizer(),
    restoredCheckpoint: ScanSessionCheckpoint? = null,
    recordedBoxes: Collection<RecordedBox> = emptyList(),
    sessionDestination: Destination? = null,
    scanLogRecorder: ScanLogRecorder? = null,
) : ExternalScannerListener {
    /**
     * Sink for the on-device scan log, or null to record nothing.
     *
     * This is the only place a scanned value is written anywhere outside the
     * comparison itself; scanner diagnostics and [ScanEffect.InvalidScan] stay
     * payload free.
     */
    var scanLogRecorder: ScanLogRecorder? = scanLogRecorder
    private val cameraAcceptanceLock = ScanAcceptanceLock()
    private var applyingScannerFormat = false
    private val restoredBoxes: List<RecordedBox> = recordedBoxes.toList()
    private val restoredState: ScanSessionState? = restoredCheckpoint?.toScanSessionState(
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
    )?.let { restored ->
        restored.copy(
            recordedBoxes = restoredBoxes,
            // Prefer the checkpoint's own lock, then the persisted session row,
            // and only then the destination the restored boxes were matched at.
            destination = restored.destination
                ?: sessionDestination
                ?: restoredBoxes.firstNotNullOfOrNull { it.destination },
        )
    }
    private val hasRestoredState: Boolean = restoredState != null

    var state: ScanSessionState = restoredState ?: ScanReducer.initial(
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
        existingMatchedCount = existingMatchedCount,
        recordedBoxes = restoredBoxes,
        destination = sessionDestination,
    )
        private set

    var inputSource: InputSource = state.inputSource
        private set

    /**
     * True after an explicit camera selection, for the current box only: it
     * is cleared when the flow returns to the QR step for the next box (and
     * when the operator selects Bluetooth). A QR reread keeps it.
     */
    var cameraWasSelectedByUser: Boolean = restoredCheckpoint?.cameraWasSelectedByUser ?: false
        private set

    /** Prevents a synchronous scanner callback from restarting input in the background. */
    var isBackgrounded: Boolean = false
        private set

    /**
     * Configuration-failure fallbacks since the scanner last reached payload
     * readiness. The source returns to Bluetooth on every Ready; only a
     * streak of [MAX_CONSECUTIVE_CONFIGURATION_FALLBACKS] failures without a
     * single successful restriction pauses the automatic return, and only
     * until the next box, an explicit Bluetooth choice or reconnect. On
     * Android a failed restriction is followed by a baseline restore that
     * reports Ready again, so an unbounded return could otherwise rewrite the
     * scanner in a tight loop. This never pins the camera for the session.
     */
    private var configurationFallbackStreak = 0

    var lastEffects: List<ScanEffect> = emptyList()
        private set

    var onStateChanged: ((ScanSessionState) -> Unit)? = null
    var onEffects: ((List<ScanEffect>) -> Unit)? = null
    var onInputSourceChanged: ((InputSource) -> Unit)? = null
    /** Invoked only when a lost/unready Bluetooth link forces camera fallback. */
    var onBluetoothFallback: (() -> Unit)? = null
    /** Typed reason captured before fallback restores the scanner baseline. */
    var onBluetoothFallbackIssue: ((ScannerIssue) -> Unit)? = null
    /** Publishes scanner configuration transitions without exposing adapter details. */
    var onScannerConfigurationStateChanged: ((ConfigurationState) -> Unit)? = null
    var onScannerConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    init {
        // Settings and Scan are separate destinations but observe the same
        // scanner. Use the fan-out contract so installing this coordinator
        // never steals the settings observer (or vice versa).
        scanner.addListener(this)
        // A scanner may already be connected before the scan feature is
        // constructed. It becomes the default only once a session starts.
        handleConnectionState(scanner.connectionState)
    }

    /** Stop receiving transport callbacks when the owning ViewModel is cleared. */
    fun dispose() {
        scanner.removeListener(this)
    }

    fun startSession(): ScanReduction {
        if (hasRestoredState && state.phase != ScanPhase.IDLE) {
            // A restored result/waiting step is already a live session. Do not
            // feed StartSession through the reducer: that would erase the
            // accepted QR/barcode or re-trigger the countdown. If the saved
            // Bluetooth source is no longer available, retain the logical step
            // and fall back to camera input.
            if (inputSource == InputSource.BLUETOOTH &&
                !scanner.isBluetoothInputUsable &&
                !scanner.connectionState.isConnectionPending
            ) {
                setInputSource(InputSource.CAMERA)
            } else {
                // A restored camera source (for example a fallback saved while
                // the scanner was momentarily not ready) returns to a usable
                // scanner unless the operator chose camera for this box.
                promoteToBluetoothIfUsable(applyFormat = false)
            }
            val reduction = ScanReduction(
                state = state,
                effects = listOf(ScanEffect.ExpectFormat(state.expectedFormat)),
            )
            lastEffects = reduction.effects
            applyEffects(reduction.effects)
            onStateChanged?.invoke(state)
            onEffects?.invoke(reduction.effects)
            return reduction
        }

        return dispatch(ScanEvent.StartSession)
    }

    fun endSession(): ScanReduction = dispatch(ScanEvent.EndSession)

    fun rereadQr(): ScanReduction = dispatch(ScanEvent.RereadQr)

    fun manualNext(): ScanReduction = dispatch(ScanEvent.ManualNext)

    fun tickAutoAdvance(seconds: Int = 1): ScanReduction =
        dispatch(ScanEvent.AutoAdvanceTick(seconds))

    fun setAutoAdvanceEnabled(enabled: Boolean): ScanReduction =
        dispatch(ScanEvent.SetAutoAdvanceEnabled(enabled))

    fun setAutoAdvanceDelay(delay: AutoAdvanceDelay): ScanReduction =
        dispatch(ScanEvent.SetAutoAdvanceDelay(delay))

    fun onBackgrounded(): ScanReduction = dispatch(ScanEvent.Backgrounded)

    fun onForegrounded(): ScanReduction = dispatch(ScanEvent.Foregrounded)

    fun cancelAutoAdvance(): ScanReduction = dispatch(ScanEvent.CancelAutoAdvance)

    /** Feed a camera callback or a scanner callback through source filtering. */
    fun submitScanPayload(payload: ScanPayload): ScanReduction? {
        // CameraX/ML Kit and BLE callbacks may complete after lifecycle stop.
        // Never let a delayed result mutate a backgrounded session.
        if (isBackgrounded) return null
        if (payload.source != inputSource) {
            // A stale camera frame during a Bluetooth step (or the reverse) is
            // dropped silently by the flow. Record it: an operator reporting
            // "nothing happened" is usually looking at exactly this case.
            recordPayloadLog(
                payload = payload,
                event = ScanLogEventKind.REJECTED,
                reason = ScanLogReason.SOURCE_MISMATCH,
            )
            return null
        }

        val timestamp = payload.timestampMillis
        if (payload.source == InputSource.CAMERA && cameraAcceptanceLock.isLocked(timestamp)) {
            return null
        }

        // A camera Code 128 outside the product-tag business format skips
        // stabilization: the reducer rejects it on the first frame and it
        // never occupies the two-observation candidate slot (#78). The format
        // depends on the locked destination, so a 4-2-3 tag reaches the
        // stabilizer in a Sawai or Molten session and a 6-4 tag only in a Denso one.
        val payloadToDispatch = if (
            payload.source == InputSource.CAMERA &&
            payload.format == ScanFormat.CODE_128 &&
            state.phase == ScanPhase.WAITING_CODE_128 &&
            TagBarcodeRecord.isValidScanPayload(payload.value, state.destination)
        ) {
            when (val stabilization = cameraStabilizer.submit(payload.value, timestamp)) {
                is ScanStabilizationResult.Accepted -> payload.copy(value = stabilization.value)
                ScanStabilizationResult.Pending -> {
                    // First of the two observations a camera Code 128 needs.
                    // Only this one is logged: Locked and Rejected are repeats
                    // of a value already recorded as a candidate.
                    recordPayloadLog(
                        payload = payload,
                        event = ScanLogEventKind.BARCODE_CANDIDATE,
                    )
                    return null
                }
                ScanStabilizationResult.Locked,
                ScanStabilizationResult.Rejected,
                -> return null
            }
        } else {
            payload
        }

        val reduction = dispatch(ScanEvent.PayloadReceived(payloadToDispatch))
        if (payload.source == InputSource.CAMERA &&
            reduction.effects.any { it === ScanEffect.ScanAccepted }
        ) {
            cameraAcceptanceLock.acquire(timestamp)
        }
        return reduction
    }

    fun handleScanPayload(payload: ScanPayload): ScanReduction? = submitScanPayload(payload)

    /**
     * Select an input source. Selecting Bluetooth while unavailable keeps the
     * camera active and allows a later connection-ready callback to promote it.
     */
    fun selectInputSource(source: InputSource): Boolean {
        if (state.phase == ScanPhase.IDLE || state.phase == ScanPhase.RESULT) {
            // Result remains a valid session state, but no scanner input should
            // be started until the user chooses next. Keep selection for the
            // next logical step without accepting a payload in the meantime.
            if (state.phase == ScanPhase.IDLE) return false
        }

        return when (source) {
            InputSource.CAMERA -> {
                cameraWasSelectedByUser = true
                setInputSource(InputSource.CAMERA)
                applyExpectedFormat(null)
                true
            }

            InputSource.BLUETOOTH -> {
                cameraWasSelectedByUser = false
                configurationFallbackStreak = 0
                if (!scanner.isBluetoothInputUsable) {
                    setInputSource(InputSource.CAMERA)
                    applyExpectedFormat(null)
                    false
                } else {
                    setInputSource(InputSource.BLUETOOTH)
                    applyExpectedFormat()
                    true
                }
            }
        }
    }

    /**
     * Explicit reconnect entry point for the scan screen.
     *
     * A transport can remain connected while its settings restoration has
     * failed. Treat that as a stale link and ask the adapter for a fresh
     * handshake rather than reporting the already-connected link as a retry
     * success. The adapter still owns every protocol operation.
     */
    fun reconnectKnownDevice(): Boolean {
        // Do not tear down an in-flight handshake or settings recovery on a
        // repeated tap. The connection/configuration callbacks unlock retry.
        if (scanner.connectionState.isConnectionPending ||
            scanner.configurationState == ConfigurationState.Configuring
        ) return false
        if (scanner.isConnected &&
            (!scanner.isReadyToStartSession || configurationFallbackStreak > 0)
        ) {
            scanner.disconnect()
        }
        val reconnected = scanner.reconnectKnownDevice()
        if (reconnected) {
            // Reconnect is normally asynchronous. An explicit user retry
            // resets the failure streak now so the later Ready callback
            // promotes the session back to Bluetooth.
            configurationFallbackStreak = 0
            if (scanner.isReadyForScanning) {
                handleConnectionState(scanner.connectionState)
            }
        }
        return reconnected
    }

    /** Dispatch a reducer event and apply scanner-related effects. */
    fun dispatch(event: ScanEvent): ScanReduction {
        when (event) {
            ScanEvent.Backgrounded -> isBackgrounded = true
            ScanEvent.Foregrounded -> isBackgrounded = false
            ScanEvent.StartSession -> isBackgrounded = false
            ScanEvent.EndSession -> isBackgrounded = false
            else -> Unit
        }
        when (event) {
            ScanEvent.StartSession,
            ScanEvent.RereadQr,
            ScanEvent.ManualNext,
            ScanEvent.EndSession,
            -> {
                cameraStabilizer.reset()
                cameraAcceptanceLock.reset()
            }
            else -> Unit
        }
        val previousState = state
        val reduction = reducer.reduce(state, event)
        state = reduction.state
        lastEffects = reduction.effects
        recordReductionLog(event, previousState, reduction)
        selectSourceForStep(event, reduction)
        applyEffects(reduction.effects)
        onStateChanged?.invoke(state)
        onEffects?.invoke(reduction.effects)
        return reduction
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        handleConnectionState(state)
        onScannerConnectionStateChanged?.invoke(state)
    }

    override fun onConfigurationStateChanged(state: ConfigurationState) {
        onScannerConfigurationStateChanged?.invoke(state)
        when (state) {
            ConfigurationState.Ready -> {
                handleConnectionState(scanner.connectionState)
                if (inputSource == InputSource.BLUETOOTH && scanner.isReadyForScanning) {
                    // The restriction was applied: a later failure starts a
                    // new streak.
                    configurationFallbackStreak = 0
                }
            }
            is ConfigurationState.Failed -> fallbackToCameraIfBluetooth()
            ConfigurationState.Unavailable -> {
                if (scanner.connectionState.connectedDevice == null &&
                    !scanner.connectionState.isConnectionPending
                ) {
                    fallbackToCameraIfBluetooth()
                }
            }
            ConfigurationState.Configuring -> Unit
        }
    }

    override fun onScanPayload(payload: ScanPayload) {
        submitScanPayload(payload)
    }

    private fun handleConnectionState(connectionState: ConnectionState) {
        if (connectionState.connectedDevice != null) {
            // Connected (configuring or ready): Bluetooth, unless the operator
            // chose camera for this box. A still-configuring scanner shows the
            // Bluetooth input; its restriction is applied once it is Ready.
            promoteToBluetoothIfUsable()
            return
        }

        // A process recreation can restore the logical BLE source before the
        // known device has finished reconnecting. Keep that source selected
        // while discovery/connection is genuinely in flight; otherwise the
        // initial Connecting + Unavailable pair would immediately switch the
        // restored session to camera and hide the recovery state from UI.
        if (connectionState.isConnectionPending) return

        fallbackToCameraIfBluetooth()
    }

    private val ConnectionState.isConnectionPending: Boolean
        get() = this is ConnectionState.Searching || this is ConnectionState.Connecting

    private fun fallbackToCameraIfBluetooth() {
        if (inputSource != InputSource.BLUETOOTH) return
        val issue = scannerIssueFor(
            scanner.connectionState,
            scanner.configurationState,
        ).takeIf { it != ScannerIssue.NONE } ?: ScannerIssue.CONNECTION_FAILED
        if (issue == ScannerIssue.CONFIGURATION_FAILED || issue == ScannerIssue.RESTORE_FAILED) {
            configurationFallbackStreak++
        }
        // setInputSource/applyExpectedFormat may synchronously clear a failed
        // configuration on real or fake adapters. Publish the typed issue
        // before that cleanup so the host cannot lose the failure category.
        onBluetoothFallbackIssue?.invoke(issue)
        setInputSource(InputSource.CAMERA)
        applyExpectedFormat(null)
        onBluetoothFallback?.invoke()
    }

    /**
     * Source policy at step boundaries (#145). The next box (manual or
     * automatic advance back to the QR step) and a new session end the
     * operator's one-box camera choice; a QR reread is the same box and keeps
     * it. Then a usable scanner becomes the source for the QR step, the
     * reread and the return to the foreground. Called before the reduction's
     * effects are applied so they start the chosen source.
     */
    private fun selectSourceForStep(event: ScanEvent, reduction: ScanReduction) {
        val nextBox = event == ScanEvent.StartSession ||
            (event != ScanEvent.RereadQr && reduction.effects.contains(ScanEffect.StartNextScan))
        if (nextBox) {
            cameraWasSelectedByUser = false
            configurationFallbackStreak = 0
        }
        val selectsSource = nextBox || event == ScanEvent.RereadQr || event == ScanEvent.Foregrounded
        if (selectsSource) promoteToBluetoothIfUsable(applyFormat = false)
    }

    /**
     * Make Bluetooth the source when the scanner is usable, the session is
     * running in the foreground, the operator has not chosen camera for this
     * box, and no failure streak pauses the return. With [applyFormat] the
     * current step's restriction is requested right away (it is applied by
     * the adapter once the scanner is Ready).
     */
    private fun promoteToBluetoothIfUsable(applyFormat: Boolean = true): Boolean {
        // A callback delivered synchronously from our own scanner call (for
        // example the baseline restore of a fallback) must not re-enter it.
        if (applyingScannerFormat) return false
        if (state.phase == ScanPhase.IDLE || isBackgrounded || cameraWasSelectedByUser) return false
        if (!scanner.isBluetoothInputUsable) return false
        if (configurationFallbackStreak >= MAX_CONSECUTIVE_CONFIGURATION_FALLBACKS) return false
        setInputSource(InputSource.BLUETOOTH)
        if (applyFormat) applyExpectedFormat()
        return true
    }

    companion object {
        /** Configuration failures in a row that pause the automatic return. */
        const val MAX_CONSECUTIVE_CONFIGURATION_FALLBACKS: Int = 3
    }

    private fun applyEffects(effects: List<ScanEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is ScanEffect.ExpectFormat -> applyExpectedFormat(effect.format)
                ScanEffect.StopInput -> applyExpectedFormat(null)
                is ScanEffect.ResumeInput -> applyExpectedFormat(effect.format)
                ScanEffect.SessionEnded -> applyExpectedFormat(null)
                else -> Unit
            }
        }
    }

    private fun applyExpectedFormat(format: ScanFormat? = state.expectedFormat) {
        val expected = if (inputSource == InputSource.BLUETOOTH) format else null
        // Real and fake adapters may synchronously invoke Ready from
        // setExpectedFormat. Do not re-enter the adapter from that callback.
        if (applyingScannerFormat) return
        if (scanner.expectedFormat == expected &&
            (expected == null || scanner.configurationState === ConfigurationState.Ready)
        ) return

        applyingScannerFormat = true
        try {
            scanner.setExpectedFormat(expected)
        } finally {
            applyingScannerFormat = false
        }
    }

    private fun setInputSource(source: InputSource) {
        if (inputSource == source) return
        inputSource = source
        state = state.copy(inputSource = source)
        onInputSourceChanged?.invoke(source)
    }

    // --- Scan log ---------------------------------------------------------

    /** Log a payload that never reached the reducer, using the current state. */
    private fun recordPayloadLog(
        payload: ScanPayload,
        event: String,
        reason: String? = null,
    ) {
        val recorder = scanLogRecorder ?: return
        val isBarcode = payload.format == ScanFormat.CODE_128
        recorder.record(
            ScanLogEvent(
                atEpochMillis = System.currentTimeMillis(),
                // The host fills the session id in: this object is built while
                // the session may still be idle.
                sessionId = null,
                source = payload.source.scanLogId,
                step = state.phase.scanLogId,
                event = event,
                reason = reason,
                destination = state.destination,
                qrPayload = payload.value.takeUnless { isBarcode },
                barcodePayload = payload.value.takeIf { isBarcode },
            ),
        )
    }

    private fun recordReductionLog(
        event: ScanEvent,
        previous: ScanSessionState,
        reduction: ScanReduction,
    ) {
        val recorder = scanLogRecorder ?: return
        if (event is ScanEvent.PayloadReceived) {
            recordPayloadReduction(recorder, event.payload, previous, reduction)
            return
        }
        if (reduction.effects.any { it === ScanEffect.SessionEnded }) {
            // Ending resets the input source and clears the destination, so the
            // finished session is described by the state before the reduction.
            recorder.record(
                ScanLogEvent(
                    atEpochMillis = System.currentTimeMillis(),
                    sessionId = null,
                    source = previous.inputSource.scanLogId,
                    step = ScanLogStep.NONE,
                    event = ScanLogEventKind.SESSION_END,
                    destination = previous.destination,
                ),
            )
        }
    }

    /**
     * Turn one reduced payload into the log lines it produced.
     *
     * Both states are needed: the step a value was judged in comes from the
     * state before the reduction, while the destination lock and the accepted
     * values come from the one after it.
     */
    private fun recordPayloadReduction(
        recorder: ScanLogRecorder,
        payload: ScanPayload,
        previous: ScanSessionState,
        reduction: ScanReduction,
    ) {
        val source = payload.source.scanLogId
        val step = previous.phase.scanLogId
        val destination = reduction.state.destination
        fun log(
            event: String,
            reason: String? = null,
            qrPayload: String? = null,
            barcodePayload: String? = null,
            code: String? = null,
            boxNumber: Int? = null,
            stepOverride: String? = null,
        ) = recorder.record(
            ScanLogEvent(
                atEpochMillis = System.currentTimeMillis(),
                sessionId = null,
                source = source,
                step = stepOverride ?: step,
                event = event,
                reason = reason,
                destination = destination,
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                code = code,
                boxNumber = boxNumber,
            ),
        )

        val invalid = reduction.effects.filterIsInstance<ScanEffect.InvalidScan>().firstOrNull()
        if (invalid != null) {
            // The value is filed under the step the reducer expected rather
            // than the symbology the scanner reported, so a Code 128 sent in
            // the QR step is still readable as "what arrived at the QR step".
            val asBarcode = invalid.expectedFormat == ScanFormat.CODE_128
            log(
                event = ScanLogEventKind.REJECTED,
                reason = invalid.reason.scanLogId,
                qrPayload = payload.value.takeUnless { asBarcode },
                barcodePayload = payload.value.takeIf { asBarcode },
            )
            return
        }

        val accepted = reduction.effects.any { it === ScanEffect.ScanAccepted }
        when (val scan = reduction.state.scan) {
            is ScanState.WaitingCode128 -> if (accepted) {
                log(event = ScanLogEventKind.QR_ACCEPTED, qrPayload = scan.qrPayload)
            }

            is ScanState.Result -> if (accepted) {
                log(
                    event = ScanLogEventKind.BARCODE_ACCEPTED,
                    barcodePayload = scan.barcodePayload,
                )
                val match = reduction.effects
                    .filterIsInstance<ScanEffect.RecordMatch>()
                    .firstOrNull()
                log(
                    event = when (scan.result) {
                        MatchResult.MATCH -> ScanLogEventKind.MATCH
                        MatchResult.MISMATCH -> ScanLogEventKind.MISMATCH
                        MatchResult.DUPLICATE -> ScanLogEventKind.DUPLICATE
                    },
                    qrPayload = scan.qrPayload,
                    barcodePayload = scan.barcodePayload,
                    // Only a match carries a RecordMatch effect; the other two
                    // verdicts still deserve the part number they were about.
                    code = match?.code ?: recordedCode(scan.qrPayload, scan.barcodePayload),
                    boxNumber = match?.boxNumber,
                    // The verdict belongs to the result step, as on iOS.
                    stepOverride = ScanLogStep.RESULT,
                )
            } else if (previous.scan is ScanState.Result && reduction.effects.isEmpty()) {
                // The reducer deliberately swallows callbacks while a result is
                // on screen. Record them: otherwise the most confusing case for
                // an operator leaves no trace at all.
                val isBarcode = payload.format == ScanFormat.CODE_128
                log(
                    event = ScanLogEventKind.REJECTED,
                    reason = ScanLogReason.RESULT_PENDING,
                    qrPayload = payload.value.takeUnless { isBarcode },
                    barcodePayload = payload.value.takeIf { isBarcode },
                )
            }

            ScanState.Idle, is ScanState.WaitingQr -> Unit
        }
    }

    /** The part number the reducer would have recorded for this pair. */
    private fun recordedCode(qrPayload: String, barcodePayload: String): String {
        val part = CodeMatcher.partNumberFromBarcode(barcodePayload)
            ?: CodeMatcher.partNumberFromQr(qrPayload)
            ?: qrPayload
        return CodeMatcher.formatPartNumber(part, CodeMatcher.detectDestination(qrPayload))
    }
}

private val InputSource.scanLogId: String
    get() = when (this) {
        InputSource.CAMERA -> ScanLogSource.CAMERA
        InputSource.BLUETOOTH -> ScanLogSource.BLUETOOTH
    }

private val ScanPhase.scanLogId: String
    get() = when (this) {
        ScanPhase.IDLE -> ScanLogStep.NONE
        ScanPhase.WAITING_QR -> ScanLogStep.QR
        ScanPhase.WAITING_CODE_128 -> ScanLogStep.BARCODE
        ScanPhase.RESULT -> ScanLogStep.RESULT
    }

private val InvalidScanReason.scanLogId: String
    get() = when (this) {
        InvalidScanReason.SESSION_NOT_STARTED -> ScanLogReason.SESSION_NOT_STARTED
        InvalidScanReason.WRONG_ORDER -> ScanLogReason.WRONG_ORDER
        InvalidScanReason.EMPTY_PAYLOAD -> ScanLogReason.EMPTY
        InvalidScanReason.INCOMPLETE_QR_PAYLOAD -> ScanLogReason.INCOMPLETE
        InvalidScanReason.OVERLONG_QR_PAYLOAD -> ScanLogReason.OVERLONG
        InvalidScanReason.INVALID_PAYLOAD -> ScanLogReason.INVALID
        InvalidScanReason.WRONG_DESTINATION -> ScanLogReason.WRONG_DESTINATION
    }


typealias ScanController = ScanSessionCoordinator
