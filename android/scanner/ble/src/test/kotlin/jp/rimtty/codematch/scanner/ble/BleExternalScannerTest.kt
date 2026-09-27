package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crosses the same facade boundary used by the future production app adapter. */
class BleExternalScannerTest {
    private val device = ScannerDevice("scanner-1", "observed scanner")
    private val profileIdentity = "adapter-profile-v1"

    @Test
    fun facadeMapsExternalScannerStateAndKeepsPayloadsOutOfDiagnostics() {
        val transport = RecordingTransport()
        val (_, _, scanner) = createStack(transport)
        val received = mutableListOf<ScanPayload>()
        val states = mutableListOf<ConnectionState>()
        val configurations = mutableListOf<ConfigurationState>()
        scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                states += state
            }

            override fun onConfigurationStateChanged(state: ConfigurationState) {
                configurations += state
            }

            override fun onScanPayload(payload: ScanPayload) {
                received += payload
            }
        }

        assertTrue(scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(ConnectionState.Connected(device), scanner.connectionState)
        assertEquals(ConfigurationState.Configuring, scanner.configurationState)
        assertFalse(scanner.isReadyForScanning)

        transport.completeRead(originalSettings())
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
        assertTrue(configurations.contains(ConfigurationState.Configuring))
        assertTrue(configurations.contains(ConfigurationState.Ready))
        assertFalse(scanner.isReadyForScanning)

        assertTrue(scanner.setExpectedFormat(ScanFormat.QR))
        assertEquals(1, transport.writes.size)
        assertFalse(scanner.isReadyForScanning)
        transport.completeWrite(0, Result.success(Unit))
        assertTrue(scanner.isReadyForScanning)

        val privateValue = "PRIVATE-BLE-SCAN"
        transport.emit(
            BleTransportEvent.ScanReceived(
                ScanPayload.qr(
                    privateValue,
                    source = InputSource.BLUETOOTH,
                    timestampMillis = 100L,
                ),
            ),
        )
        transport.emit(
            BleTransportEvent.ScanReceived(
                ScanPayload.qr("camera-value", source = InputSource.CAMERA),
            ),
        )

        assertEquals(listOf(privateValue), received.map(ScanPayload::value))
        assertTrue(scanner.diagnosticEvents.none { it.message.contains(privateValue) })
        assertTrue(states.contains(ConnectionState.Connected(device)))
    }

    @Test
    fun nullableFormatRestoresBaselineAndManualDisconnectWaitsForRestore() {
        val transport = RecordingTransport()
        val (session, _, scanner) = createStack(transport)
        startReadySession(transport, scanner)

        assertTrue(scanner.setExpectedFormat(null))
        assertEquals(2, transport.writes.size)
        assertEquals(ConnectionState.Connected(device), scanner.connectionState)
        assertFalse(scanner.isReadyForScanning)

        transport.completeWrite(1, Result.success(Unit))
        assertNull(scanner.expectedFormat)
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(BleSymbologyMode.UNRESTRICTED, session.physicalMode)
        assertFalse(scanner.isReadyForScanning)

        // A second end request is a no-op once the physical baseline is safe.
        assertFalse(scanner.setExpectedFormat(null))
        assertTrue(scanner.disconnect())
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(BleSymbologySessionState.Disconnected, session.state)
    }

    @Test
    fun manualDisconnectDefersTransportCloseUntilBaselineRestoreCompletes() {
        val transport = RecordingTransport()
        val (session, _, scanner) = createStack(transport)
        startReadySession(transport, scanner)

        assertTrue(scanner.disconnect())
        assertEquals(2, transport.writes.size)
        assertEquals(0, transport.disconnectCalls.size)
        assertEquals(ConnectionState.Connected(device), scanner.connectionState)
        assertFalse(scanner.isReadyForScanning)

        transport.completeWrite(1, Result.success(Unit))
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(ConnectionState.Idle, scanner.connectionState)
        assertEquals(BleSymbologySessionState.Disconnected, session.state)
        assertFalse(scanner.isReadyForScanning)
    }

    @Test
    fun reconnectRequestedDuringRestoreRunsAfterThePhysicalDisconnect() {
        val transport = RecordingTransport()
        val (_, bridge, scanner) = createStack(transport, nowMillis = { 0L })
        startReadySession(transport, scanner)

        assertTrue(scanner.disconnect())
        assertTrue(scanner.reconnectKnownDevice())
        assertEquals(1, transport.connectCalls.size)
        assertEquals(0, transport.disconnectCalls.size)

        transport.completeWrite(1, Result.success(Unit))

        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        bridge.tick(atMillis = 8_000L)
        assertEquals(2, transport.connectCalls.size)
        assertEquals(device, transport.connectCalls.last())
        assertEquals(ConnectionState.Connecting(device), scanner.connectionState)
        assertFalse(scanner.isReadyForScanning)
    }

    @Test
    fun backgroundRestoresBaselineAndForegroundReappliesTheSameLogicalStep() {
        val transport = RecordingTransport()
        val (session, bridge, scanner) = createStack(transport)
        startReadySession(transport, scanner)

        scanner.setApplicationActive(false, atMillis = 10L)
        assertEquals(2, transport.writes.size)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertFalse(scanner.isReadyForScanning)

        val dropped = mutableListOf<ScanPayload>()
        scanner.listener = object : ExternalScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                dropped += payload
            }
        }
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("background-value")))
        assertTrue(dropped.isEmpty())

        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologyMode.UNRESTRICTED, session.physicalMode)
        assertTrue(bridge.isSuspendedForBackground)
        assertFalse(scanner.isReadyForScanning)

        scanner.setApplicationActive(true, atMillis = 20L)
        assertEquals(3, transport.writes.size)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertFalse(scanner.isReadyForScanning)
        transport.completeWrite(2, Result.success(Unit))
        assertTrue(scanner.isReadyForScanning)
        assertFalse(bridge.isSuspendedForBackground)

        // The QR -> Code128 transition waits for the physical setting.
        assertTrue(scanner.setExpectedFormat(ScanFormat.CODE_128))
        assertEquals(4, transport.writes.size)
        assertFalse(scanner.isReadyForScanning)
        transport.completeWrite(3, Result.success(Unit))
        assertTrue(scanner.isReadyForScanning)
        assertEquals(ScanFormat.CODE_128, scanner.expectedFormat)
    }

    @Test
    fun failedRestoreKeepsSnapshotNonReadyAndCanBeRetriedWithoutLateCallback() {
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val (session, _, scanner) = createStack(transport, snapshotStore = store)
        startReadySession(transport, scanner)

        assertTrue(scanner.setExpectedFormat(null))
        transport.completeWrite(1, Result.failure(IllegalStateException("write failed")))
        assertTrue(session.state is BleSymbologySessionState.Failed)
        assertTrue(scanner.configurationState is ConfigurationState.Failed)
        assertFalse(scanner.isReadyForScanning)
        assertNotNull(store.load(device.id))

        // A duplicate/late callback from the failed command cannot clear the
        // snapshot or publish Ready after the queue accepted no new command.
        transport.completeWrite(1, Result.success(Unit))
        assertTrue(session.state is BleSymbologySessionState.Failed)
        assertNotNull(store.load(device.id))

        // Retrying the nullable API starts one new restore command only.
        assertTrue(scanner.setExpectedFormat(null))
        assertEquals(3, transport.writes.size)
        transport.completeWrite(2, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertNull(store.load(device.id))
    }

    @Test
    fun endRequestedDuringSessionApplyQueuesRestoreAndRejectsLateStartCallback() {
        val transport = RecordingTransport()
        val (session, _, scanner) = createStack(transport)
        connectAndRead(transport, scanner)

        assertTrue(scanner.setExpectedFormat(ScanFormat.QR))
        assertEquals(1, transport.writes.size)
        assertTrue(scanner.setExpectedFormat(null))
        assertEquals(1, transport.writes.size)
        assertEquals(BleSymbologySessionState.Restoring, session.state)

        transport.completeWrite(0, Result.success(Unit))
        assertEquals(2, transport.writes.size)
        assertEquals(BleSymbologySessionState.Restoring, session.state)

        // The old callback is delivered after the restore is in flight. The
        // command generation boundary ignores it and does not stack a write.
        transport.completeWrite(0, Result.success(Unit))
        assertEquals(2, transport.writes.size)
        assertEquals(BleSymbologySessionState.Restoring, session.state)

        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertFalse(scanner.isReadyForScanning)
    }

    @Test
    fun unexpectedDisconnectRetainsSnapshotAndReconnectsIntoRecoveryBeforeReady() {
        var now = 0L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val (session, bridge, scanner) = createStack(
            transport = transport,
            snapshotStore = store,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        startReadySession(transport, scanner)

        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = true,
            ),
        )
        assertEquals(
            ConnectionState.Failed("Bluetooth scanner disconnected"),
            scanner.connectionState,
        )
        assertEquals(BleSymbologySessionState.Disconnected, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertNotNull(store.load(device.id))

        now = 100L
        assertTrue(bridge.tick(now).connectionOperationStarted)
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(restrictedSettings())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertEquals(2, transport.writes.size)

        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertNull(store.load(device.id))
    }

    @Test
    fun availabilityLossDuringManualRestoreClosesWithoutAutomaticReconnect() {
        var now = 0L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val (session, bridge, scanner) = createStack(
            transport = transport,
            snapshotStore = store,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        startReadySession(transport, scanner)
        assertTrue(scanner.disconnect())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertTrue(transport.disconnectCalls.isEmpty())

        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertFalse(scanner.isReadyForScanning)
        assertNotNull(store.load(device.id))
        assertEquals(listOf(device), transport.disconnectCalls)

        // A late restore reply cannot prove the unavailable link was restored.
        transport.completeWrite(1, Result.success(Unit))
        assertNotNull(store.load(device.id))
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        transport.availability = BleAvailability.Ready
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        now = 100L
        assertFalse(bridge.tick(now).connectionOperationStarted)
        assertEquals(listOf(device), transport.connectCalls)
        assertNotNull(store.load(device.id))
    }

    @Test
    fun availabilityRecoveryKeepsSnapshotUntilAcknowledgedCloseAndFreshRestore() {
        var now = 0L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val (session, bridge, scanner) = createStack(
            transport = transport,
            snapshotStore = store,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        startReadySession(transport, scanner)
        transport.availability = BleAvailability.Unauthorized
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Unauthorized))
        assertFalse(scanner.isReadyForScanning)
        assertNotNull(store.load(device.id))

        transport.availability = BleAvailability.Ready
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertFalse(scanner.isReadyForScanning)
        assertEquals(1, transport.readCallbacks.size)
        now = 100L
        assertTrue(bridge.tick(now).connectionOperationStarted)
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(listOf(device), transport.connectCalls)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        now = 200L
        assertTrue(bridge.tick(now).connectionOperationStarted)
        transport.emit(BleTransportEvent.Connected(device))
        assertFalse(scanner.isReadyForScanning)
        transport.completeRead(restrictedSettings())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertNotNull(store.load(device.id))
        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertNull(store.load(device.id))
        assertFalse(scanner.isReadyForScanning)
    }

    @Test
    fun timeoutRequiresResetAndLateCallbackCannotOpenFacadeForScanning() {
        var now = 0L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val (session, bridge, scanner) = createStack(
            transport = transport,
            snapshotStore = store,
            nowMillis = { now },
        )
        connectAndRead(transport, scanner)
        assertTrue(scanner.setExpectedFormat(ScanFormat.QR))
        assertEquals(1, transport.writes.size)

        now = 3_000L
        assertTrue(bridge.tick(now).command is BleCommandTickResult.TimedOut)
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertEquals(1, transport.disconnectCalls.size)

        // The old command callback is late and must not make the facade ready.
        transport.completeWrite(0, Result.success(Unit))
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(scanner.setExpectedFormat(ScanFormat.CODE_128))
        assertEquals(1, transport.writes.size)

        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = false,
            ),
        )
        bridge.onTransportResetCompleted()
        assertEquals(BleSymbologySessionState.AwaitingReconnect, session.state)
        assertTrue(scanner.reconnectKnownDevice())
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(restrictedSettings())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(2, transport.writes.size)
        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertFalse(scanner.isReadyForScanning)
        assertNull(store.load(device.id))
    }

    @Test
    fun failedSuspendRequestDoesNotLeaveAStaleAutoResumeFlag() {
        val transport = RecordingTransport()
        val (session, _, scanner) = createStack(transport)
        startReadySession(transport, scanner)

        assertTrue(session.endSession())
        assertFalse(session.suspendForBackground())
        assertFalse(session.isSuspendedForBackground)
        transport.completeWrite(1, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertFalse(scanner.isReadyForScanning)
    }

    @Test
    fun relaunchUsesKnownIdentityAndRestoresPersistedSnapshotBeforeReady() {
        val snapshotStore = InMemorySymbologySnapshotStore(profileIdentity)
        val knownStore = InMemoryKnownDeviceStore(profileIdentity)
        val firstTransport = RecordingTransport()
        val (_, _, firstScanner) = createStack(firstTransport, snapshotStore, knownStore)
        startReadySession(firstTransport, firstScanner)

        // A new process/service instance receives a fresh transport and the
        // same app-private stores. The scanner is currently restricted, so the
        // second read reflects the restricted values, not the saved baseline.
        val relaunchedTransport = RecordingTransport()
        val (reloadedSession, _, reloadedScanner) = createStack(
            relaunchedTransport,
            snapshotStore,
            knownStore,
        )
        assertTrue(reloadedScanner.reconnectKnownDevice())
        assertEquals(listOf(device), relaunchedTransport.connectCalls)
        relaunchedTransport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleSymbologySessionState.LoadingSettings, reloadedSession.state)
        relaunchedTransport.completeRead(restrictedSettings())
        assertEquals(BleSymbologySessionState.Restoring, reloadedSession.state)
        assertFalse(reloadedScanner.isReadyForScanning)
        assertEquals(1, relaunchedTransport.writes.size)

        relaunchedTransport.completeWrite(0, Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, reloadedSession.state)
        assertFalse(reloadedScanner.isReadyForScanning)
        assertNull(snapshotStore.load(device.id))
    }

    @Test
    fun selectableFacadeBindsTheDiscoveredDeviceBeforeConnectionAndConfiguration() {
        val transport = RecordingTransport()
        val stack = createSelectableStack(transport)
        val selected = ScannerDevice("scanner-selected", "selected scanner")
        val connectionStates = mutableListOf<ConnectionState>()
        stack.scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                connectionStates += state
            }
        }

        assertTrue(stack.scanner.startDiscovery())
        transport.emit(
            BleTransportEvent.DeviceFound(
                BleDiscoveredDevice(selected, setOf("service-observed")),
            ),
        )
        assertEquals(listOf(selected), stack.scanner.devices)

        assertTrue(stack.scanner.connect(selected))
        assertEquals(selected, stack.scanner.boundDevice)
        assertEquals(listOf(selected), transport.connectCalls)
        transport.emit(BleTransportEvent.Connected(selected))
        assertEquals(ConfigurationState.Configuring, stack.scanner.configurationState)

        transport.completeRead(originalSettings())
        assertEquals(ConfigurationState.Ready, stack.scanner.configurationState)
        assertFalse(stack.scanner.isReadyForScanning)
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))
        assertTrue(stack.scanner.isReadyForScanning)
        assertTrue(connectionStates.contains(ConnectionState.Connected(selected)))
        assertEquals(listOf(selected), stack.createdSessions.map { it.scannerDevice })
    }

    @Test
    fun selectableFacadeQueuesAnotherScannerUntilTheEstablishedLinkIsClosed() {
        val transport = RecordingTransport()
        val knownStore = InMemoryKnownDeviceStore(profileIdentity)
        val stack = createSelectableStack(transport, knownStore)
        val other = ScannerDevice("scanner-other", "other scanner")

        assertTrue(stack.scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))

        // An established link is never redirected: the operator disconnects first.
        assertFalse(stack.scanner.connect(other))
        assertEquals(device, stack.scanner.boundDevice)
        assertEquals(listOf(device), transport.connectCalls)
        assertEquals(device, knownStore.load())

        assertTrue(stack.scanner.disconnect())
        transport.completeWrite(1, Result.success(Unit))
        // #137: a choice made while that link closes is queued, not refused.
        // Idle is the app-visible closing state, not proof of physical close,
        // so the old owner stays bound and nothing is connected yet.
        assertTrue(stack.scanner.connect(other))
        assertEquals(ConnectionState.Connecting(other), stack.scanner.connectionState)
        assertEquals(device, stack.scanner.boundDevice)
        assertEquals(listOf(device), transport.connectCalls)
        assertEquals(other, knownStore.load())

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        stack.scanner.tick(System.currentTimeMillis())
        assertEquals(other, stack.scanner.boundDevice)
        assertEquals(listOf(device, other), transport.connectCalls)
        assertEquals(listOf(device, other), stack.createdSessions.map { it.scannerDevice })
        assertEquals(ConnectionState.Connecting(other), stack.scanner.connectionState)
    }

    @Test
    fun selectableFacadeSwitchesScannerAfterTheSessionScannerDropsMidSession() {
        // #135: the dropped scanner's unrestored session used to block every
        // other scanner for the rest of the process (connect() returned false).
        val transport = RecordingTransport()
        val stack = createSelectableStack(transport)
        val other = ScannerDevice("scanner-other", "other scanner")

        assertTrue(stack.scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))
        assertTrue(stack.scanner.isReadyForScanning)
        val pendingA = stack.snapshotStore.load(device.id)
        assertNotNull(pendingA)

        // Power off / battery: the link is lost while the session is active.
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        assertTrue(stack.createdSessions.single().isSessionActive)
        assertTrue(stack.scanner.connect(other))
        assertEquals(other, stack.scanner.boundDevice)
        assertEquals(listOf(device, other), transport.connectCalls)
        assertEquals(pendingA, stack.snapshotStore.load(device.id))

        // The new scanner configures, and the scan flow can start on it with
        // its own snapshot while A's stays pending.
        transport.emit(BleTransportEvent.Connected(other))
        transport.completeRead(originalSettings())
        assertEquals(ConfigurationState.Ready, stack.scanner.configurationState)
        assertTrue(transport.writes.size == 1)
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(1, Result.success(Unit))
        assertTrue(stack.scanner.isReadyForScanning)
        assertEquals(other.id, stack.snapshotStore.load(other.id)?.deviceId)
        assertEquals(pendingA, stack.snapshotStore.load(device.id))

        // Ending on B restores and clears B only.
        assertTrue(stack.scanner.disconnect())
        transport.completeWrite(2, Result.success(Unit))
        assertNull(stack.snapshotStore.load(other.id))
        assertEquals(pendingA, stack.snapshotStore.load(device.id))
        transport.emit(BleTransportEvent.Disconnected(other, unexpected = false))

        // A comes back and gets its baseline restored before Ready.
        assertTrue(stack.scanner.connect(device))
        assertEquals(device, stack.scanner.boundDevice)
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(restrictedSettings())
        assertEquals(BleSymbologySessionState.Restoring, stack.createdSessions.last().state)
        assertEquals(4, transport.writes.size)
        transport.completeWrite(3, Result.success(Unit))
        assertEquals(ConfigurationState.Ready, stack.scanner.configurationState)
        assertNull(stack.snapshotStore.load(device.id))
        assertEquals(
            listOf(device, other, device),
            stack.createdSessions.map { it.scannerDevice },
        )
    }

    @Test
    fun operatorChoicePreemptsTheAutomaticReconnectOfTheDroppedScanner() {
        var now = 1_000L
        val transport = RecordingTransport()
        val knownStore = InMemoryKnownDeviceStore(profileIdentity)
        val stack = createSelectableStack(
            transport,
            knownStore,
            nowMillis = { now },
            reconnectDelayMillis = { 1_000L },
        )
        val other = ScannerDevice("scanner-other", "other scanner")

        assertTrue(stack.scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))

        // A is switched off: the automatic reconnect to A is in flight.
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        now += 1_000L
        stack.scanner.tick(now)
        assertEquals(ConnectionState.Connecting(device), stack.scanner.connectionState)
        assertEquals(listOf(device, device), transport.connectCalls)

        // #137: the operator's choice closes that pending attempt instead of
        // being refused, and becomes the known device at once.
        assertTrue(stack.scanner.connect(other))
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(other, knownStore.load())
        assertEquals(ConnectionState.Connecting(other), stack.scanner.connectionState)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        now += 250L
        stack.scanner.tick(now)
        assertEquals(other, stack.scanner.boundDevice)
        assertEquals(listOf(device, device, other), transport.connectCalls)

        // A failed attempt is retried against the chosen scanner, never A.
        transport.emit(BleTransportEvent.ConnectionFailed(other, "unreachable"))
        now += 60_000L
        stack.scanner.tick(now)
        assertEquals(listOf(device, device, other, other), transport.connectCalls)
        // A's pending restore stays for its next connection (#135).
        assertNotNull(stack.snapshotStore.load(device.id))
    }

    @Test
    fun operatorChoiceWhileTheSameScannerIsConnectingKeepsThatAttempt() {
        val transport = RecordingTransport()
        val stack = createSelectableStack(transport)

        assertTrue(stack.scanner.connect(device))
        assertTrue(stack.scanner.connect(device))
        assertEquals(listOf(device), transport.connectCalls)
        assertTrue(transport.disconnectCalls.isEmpty())
        assertEquals(ConnectionState.Connecting(device), stack.scanner.connectionState)
    }

    @Test
    fun searchClosesAPendingReconnectInsteadOfBeingRefused() {
        var now = 1_000L
        val transport = RecordingTransport()
        val stack = createSelectableStack(
            transport,
            nowMillis = { now },
            reconnectDelayMillis = { 1_000L },
        )
        assertTrue(stack.scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        now += 1_000L
        stack.scanner.tick(now)
        assertEquals(ConnectionState.Connecting(device), stack.scanner.connectionState)

        assertTrue(stack.scanner.startDiscovery())
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(0, transport.discoveryCalls)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        stack.scanner.tick(now)
        assertEquals(1, transport.discoveryCalls)
        assertEquals(ConnectionState.Searching, stack.scanner.connectionState)
        // The cancelled attempt is not retried behind the operator's back.
        now += 60_000L
        stack.scanner.tick(now)
        assertEquals(listOf(device, device), transport.connectCalls)
    }

    @Test
    fun selectableFacadeWaitsForAnUnsettledRestoreOfTheDroppedScanner() {
        val transport = RecordingTransport()
        val stack = createSelectableStack(transport)
        val other = ScannerDevice("scanner-other", "other scanner")
        assertTrue(stack.scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertTrue(stack.scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))

        // The scan flow falls back to camera and asks for the baseline. This
        // transport accepts the write although there is no link, so the old
        // owner still has a command on the shared transport.
        stack.scanner.setExpectedFormat(null)
        assertEquals(2, transport.writes.size)
        // The choice is accepted (#137) but the owner is not replaced yet.
        assertTrue(stack.scanner.connect(other))
        assertEquals(device, stack.scanner.boundDevice)
        assertEquals(listOf(device), transport.connectCalls)

        // Once that write settles (it cannot reach a scanner without a link),
        // the other scanner is bound and A's snapshot stays pending.
        transport.completeWrite(1, Result.failure(IllegalStateException("no link")))
        assertNotNull(stack.snapshotStore.load(device.id))
        stack.scanner.tick(System.currentTimeMillis())
        assertEquals(other, stack.scanner.boundDevice)
        assertEquals(listOf(device, other), transport.connectCalls)
        assertNotNull(stack.snapshotStore.load(device.id))
    }

    @Test
    fun unavailablePendingLinkIsClosedBeforeTheChosenScannerIsBound() {
        val transport = RecordingTransport()
        val stack = createSelectableStack(transport)
        val other = ScannerDevice("scanner-other", "other scanner")
        assertTrue(stack.scanner.connect(device))
        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))

        // The pending link is closed first; the owner is never rebound over it.
        assertTrue(stack.scanner.connect(other))
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(device, stack.scanner.boundDevice)
        assertEquals(listOf(device), stack.createdSessions.map { it.scannerDevice })
        assertEquals(listOf(device), transport.connectCalls)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        transport.availability = BleAvailability.Ready
        stack.scanner.tick(System.currentTimeMillis())
        assertEquals(other, stack.scanner.boundDevice)
        assertEquals(listOf(device, other), transport.connectCalls)
    }

    @Test
    fun selectableFacadeRecreatesTheSessionForAPersistedKnownDevice() {
        val knownStore = InMemoryKnownDeviceStore(profileIdentity)
        val firstTransport = RecordingTransport()
        val first = createSelectableStack(firstTransport, knownStore)

        assertTrue(first.scanner.connect(device))
        assertEquals(device, first.scanner.boundDevice)
        first.scanner.close()

        val recreatedTransport = RecordingTransport()
        val recreated = createSelectableStack(recreatedTransport, knownStore)
        assertNull(recreated.scanner.boundDevice)
        assertTrue(recreated.scanner.reconnectKnownDevice())

        assertEquals(device, recreated.scanner.boundDevice)
        assertEquals(listOf(device), recreatedTransport.connectCalls)
        assertEquals(listOf(device), recreated.createdSessions.map { it.scannerDevice })
        assertEquals(ConnectionState.Connecting(device), recreated.scanner.connectionState)
    }

    private fun startReadySession(
        transport: RecordingTransport,
        scanner: BleExternalScanner,
    ) {
        connectAndRead(transport, scanner)
        assertTrue(scanner.setExpectedFormat(ScanFormat.QR))
        transport.completeWrite(0, Result.success(Unit))
    }

    private fun connectAndRead(
        transport: RecordingTransport,
        scanner: BleExternalScanner,
    ) {
        assertTrue(scanner.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
    }

    private fun createStack(
        transport: RecordingTransport,
        snapshotStore: InMemorySymbologySnapshotStore =
            InMemorySymbologySnapshotStore(profileIdentity),
        knownStore: KnownDeviceStore = InMemoryKnownDeviceStore(profileIdentity),
        nowMillis: () -> Long = { System.currentTimeMillis() },
        reconnectDelayMillis: (Int) -> Long = { 8_000L },
    ): Triple<BleSymbologySession, BleScannerSessionCoordinator, BleExternalScanner> {
        val connection = BleConnectionCoordinator(
            transport = transport,
            knownDeviceStore = knownStore,
            nowMillis = nowMillis,
            reconnectDelayMillis = reconnectDelayMillis,
        )
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsCharacteristicUuid = "adapter-settings-endpoint",
                codec = IosObservedSymbologyCodec,
                identity = profileIdentity,
            ),
            snapshotStore = snapshotStore,
            nowMillis = nowMillis,
        )
        val bridge = BleScannerSessionCoordinator(connection, session)
        return Triple(session, bridge, BleExternalScanner(bridge))
    }

    private fun createSelectableStack(
        transport: RecordingTransport,
        knownStore: KnownDeviceStore = InMemoryKnownDeviceStore(profileIdentity),
        nowMillis: () -> Long = { System.currentTimeMillis() },
        reconnectDelayMillis: (Int) -> Long = { 8_000L },
    ): SelectableStack {
        val connection = BleConnectionCoordinator(
            transport = transport,
            knownDeviceStore = knownStore,
            nowMillis = nowMillis,
            reconnectDelayMillis = reconnectDelayMillis,
        )
        val snapshotStore = InMemorySymbologySnapshotStore(profileIdentity)
        val createdSessions = mutableListOf<BleSymbologySession>()
        val scanner = SelectableBleExternalScanner(
            connectionCoordinator = connection,
            sessionFactory = BleSessionCoordinatorFactory { selected ->
                val session = BleSymbologySession(
                    device = selected,
                    transport = transport,
                    profile = BleSymbologyProfile(
                        settingsCharacteristicUuid = "adapter-settings-endpoint",
                        codec = IosObservedSymbologyCodec,
                        identity = profileIdentity,
                    ),
                    snapshotStore = snapshotStore,
                )
                createdSessions += session
                BleScannerSessionCoordinator(connection, session)
            },
        )
        return SelectableStack(scanner, createdSessions, snapshotStore)
    }

    private data class SelectableStack(
        val scanner: SelectableBleExternalScanner,
        val createdSessions: List<BleSymbologySession>,
        val snapshotStore: InMemorySymbologySnapshotStore,
    )

    private class RecordingTransport : BleTransport {
        override var availability: BleAvailability = BleAvailability.Ready
        override var readiness: BleTransportReadiness = BleTransportReadiness()
        override var listener: BleTransportListener? = null
        val connectCalls = mutableListOf<ScannerDevice>()
        val disconnectCalls = mutableListOf<ScannerDevice>()
        val readCallbacks = mutableListOf<(Result<ByteArray>) -> Unit>()
        val writeCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
        val writes = mutableListOf<ByteArray>()

        var discoveryCalls = 0

        override fun startDiscovery(): Boolean {
            discoveryCalls++
            return true
        }

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls += device
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls += device
            return true
        }

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            writes += payload.copyOf()
            writeCallbacks += completion
            return true
        }

        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ): Boolean {
            readCallbacks += completion
            return true
        }

        fun emit(event: BleTransportEvent) {
            listener?.onTransportEvent(event)
        }

        fun completeRead(json: String) {
            // Keep stale callbacks available for explicit late-callback tests,
            // but complete the newest read after a reconnect.
            readCallbacks.last()(Result.success(json.toByteArray(Charsets.UTF_8)))
        }

        fun completeWrite(index: Int, result: Result<Unit>) {
            writeCallbacks[index](result)
        }
    }

    private class InMemoryKnownDeviceStore(
        override val profileIdentity: String,
    ) : KnownDeviceStore {
        private var saved: ScannerDevice? = null

        override fun read(): BleKnownDeviceReadResult = saved?.let {
            BleKnownDeviceReadResult.Found(it)
        } ?: BleKnownDeviceReadResult.Missing

        override fun save(device: ScannerDevice): BleKnownDeviceWriteResult {
            saved = device
            return BleKnownDeviceWriteResult.Saved
        }

        override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult {
            if (saved == null) return BleKnownDeviceClearResult.Missing
            if (expectedDeviceId != null && expectedDeviceId != saved?.id) {
                return BleKnownDeviceClearResult.Rejected(
                    BleKnownDeviceRejectionReason.DEVICE_MISMATCH,
                )
            }
            saved = null
            return BleKnownDeviceClearResult.Cleared
        }
    }

    private fun originalSettings(): String = """
        {"data":[
          {"area":"qr-area","value":"1","name":"qrcode_on"},
          {"area":"code128-area","value":"1","name":"code128_on"},
          {"area":"code39-area","value":"1","name":"code39_on"}
        ]}
    """.trimIndent()

    private fun restrictedSettings(): String = """
        {"data":[
          {"area":"qr-area","value":"1","name":"qrcode_on"},
          {"area":"code128-area","value":"1","name":"code128_on"},
          {"area":"code39-area","value":"0","name":"code39_on"}
        ]}
    """.trimIndent()

}
