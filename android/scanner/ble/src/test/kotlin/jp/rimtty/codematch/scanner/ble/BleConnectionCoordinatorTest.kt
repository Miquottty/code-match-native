package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionCoordinatorTest {
    private val device = ScannerDevice("scanner-1", "BCST-47")

    @Test
    fun discoveryConnectionAndConfigurationAreExposedAsTypedStates() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport)

        assertTrue(coordinator.startDiscovery())
        assertEquals(BleConnectionState.Searching, coordinator.connectionState)
        transport.emit(BleTransportEvent.DeviceFound(BleDiscoveredDevice(device)))
        assertEquals(listOf(device), coordinator.devices)
        transport.emit(BleTransportEvent.DiscoveryStopped)
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertEquals(ConfigurationState.Configuring, coordinator.configurationState)
        coordinator.markConfiguration(ConfigurationState.Ready)
        assertTrue(coordinator.state.configuration.isReady)
        assertEquals(ConnectionState.Connected(device), coordinator.connectionState.asApiState())
    }

    @Test
    fun unexpectedDisconnectSchedulesReconnectAndManualDisconnectDoesNot() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { 0L },
        )
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        assertEquals(BleConnectionState.Failed("Bluetooth scanner disconnected"), coordinator.connectionState)
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(999L))
        assertTrue(coordinator.tick(1_000L))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
        assertEquals(listOf(device, device), transport.connectCalls)

        transport.emit(BleTransportEvent.Connected(device))
        assertTrue(coordinator.disconnect())
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
    }

    @Test
    fun connectedDiagnosticNamesTheScannerButNeverItsAddress() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { 0L })
        val named = ScannerDevice(id = "AA:BB:CC:DD:63:6E", name = "HPRT-636E")
        coordinator.connect(named)
        transport.emit(BleTransportEvent.Connected(named))

        val messages = coordinator.state.diagnostics.map { it.message }
        assertEquals("Scanner connected: HPRT-636E", messages.last())
        assertTrue(messages.none { it.contains(named.id) })
    }

    @Test
    fun scanPayloadIsForwardedButNeverWrittenToDiagnostics() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { 0L })
        val received = mutableListOf<ScanPayload>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload
            }
        })
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))
        val privateValue = "PRIVATE-SCAN-PAYLOAD"
        val payload = ScanPayload(
            value = privateValue,
            source = InputSource.BLUETOOTH,
            format = ScanFormat.QR,
            timestampMillis = 10L,
        )

        transport.emit(BleTransportEvent.ScanReceived(payload))

        assertEquals(listOf(payload), received)
        assertTrue(coordinator.state.diagnostics.none { it.message.contains(privateValue) })
    }

    @Test
    fun connectionCoordinatorNormalizesAndDebouncesPayloadsAndResetsOnReconnect() {
        var now = 1_000L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })
        val received = mutableListOf<String>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload.value
            }
        })
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))

        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC\r", timestampMillis = now)))
        now += 749L
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC\n", timestampMillis = now)))
        now += 1L
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC", timestampMillis = now)))
        assertEquals(listOf("ABC", "ABC"), received)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC", timestampMillis = now)))
        assertEquals(listOf("ABC", "ABC", "ABC"), received)
    }

    @Test
    fun unavailableBluetoothPreventsDiscoveryAndReportsRecoveryState() {
        val transport = RecordingTransport(BleAvailability.PoweredOff)
        val coordinator = BleConnectionCoordinator(transport)

        assertFalse(coordinator.startDiscovery())
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth is off"),
            coordinator.connectionState,
        )
        assertTrue(transport.discoveryStarts == 0)
    }

    @Test
    fun discoveryCannotReplacePendingActiveOrClosingConnectionState() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport)
        assertTrue(coordinator.connect(device))
        assertFalse(coordinator.startDiscovery())
        assertEquals(BleConnectionState.Connecting(device), coordinator.connectionState)

        transport.emit(BleTransportEvent.Connected(device))
        coordinator.markConfiguration(ConfigurationState.Ready)
        assertFalse(coordinator.startDiscovery())
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertEquals(ConfigurationState.Ready, coordinator.configurationState)

        assertTrue(coordinator.disconnect())
        assertFalse(coordinator.startDiscovery())
        assertEquals(0, transport.discoveryStarts)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertTrue(coordinator.startDiscovery())
        assertEquals(1, transport.discoveryStarts)
    }

    @Test
    fun availabilityRecoveryClearsIdleErrorWithoutConnectingOrBecomingConfigured() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport)
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Unauthorized))
        assertTrue(coordinator.connectionState is BleConnectionState.Unavailable)

        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(ConfigurationState.Unavailable, coordinator.configurationState)
        assertTrue(transport.connectCalls.isEmpty())
        assertFalse(coordinator.hasPhysicalLink)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
    }

    @Test
    fun availabilityRecoveryPreservesScheduledBackoffAndDoesNotClearConnectionFailure() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { 0L },
        )
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.ConnectionFailed(device, "synthetic failure"))
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertEquals(BleConnectionState.Failed("synthetic failure"), coordinator.connectionState)

        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        val deadline = coordinator.pendingReconnectAtMillis
        val attempts = coordinator.reconnectAttemptCount
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(deadline, coordinator.pendingReconnectAtMillis)
        assertEquals(attempts, coordinator.reconnectAttemptCount)
        assertFalse(coordinator.tick(999L))
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.tick(1_000L))
        assertEquals(2, transport.connectCalls.size)
    }

    @Test
    fun availabilityLossRetainsPendingLinkAndClosesBeforeRecoveryConnect() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        val requestGeneration = coordinator.pendingRequestGeneration
        val linkGeneration = coordinator.pendingLinkGeneration
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertEquals(BleConnectionState.Unavailable("Bluetooth is off"), coordinator.connectionState)
        assertEquals(requestGeneration, coordinator.pendingRequestGeneration)
        assertEquals(linkGeneration, coordinator.pendingLinkGeneration)
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)

        // Ready means only that the adapter can be used again; it is not a
        // close acknowledgement and must not publish a connection or connect.
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertEquals(BleConnectionState.Unavailable("Bluetooth is off"), coordinator.connectionState)
        assertEquals(1, transport.connectCalls.size)
        assertFalse(coordinator.tick(999L))

        now = 1_000L
        assertTrue(coordinator.tick(now))
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        assertEquals(requestGeneration, coordinator.pendingRequestGeneration)
        assertEquals(linkGeneration, coordinator.pendingLinkGeneration)
        assertEquals(BleConnectionState.Unavailable("Bluetooth is off"), coordinator.connectionState)

        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = false,
                requestGeneration = requestGeneration,
                linkGeneration = linkGeneration,
            ),
        )
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.connectCalls.size)
        assertEquals(BleConnectionState.Reconnecting(device, 2), coordinator.connectionState)
    }

    @Test
    fun availabilityLossRetainsActiveLinkSuppressesScanAndUsesCloseOnlyRetry() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { now },
        )
        val received = mutableListOf<String>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload.value
            }
        })

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        coordinator.markConfiguration(ConfigurationState.Ready)
        val requestGeneration = coordinator.currentRequestGeneration
        val linkGeneration = coordinator.currentLinkGeneration

        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertEquals(BleConnectionState.Unavailable("Bluetooth is off"), coordinator.connectionState)
        assertEquals(requestGeneration, coordinator.currentRequestGeneration)
        assertEquals(linkGeneration, coordinator.currentLinkGeneration)
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        coordinator.markConfiguration(ConfigurationState.Ready)
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("blocked")))
        assertTrue(received.isEmpty())

        // Duplicate availability notifications do not stack close requests.
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        now = 1_000L
        assertTrue(coordinator.tick(now))
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(null, coordinator.pendingReconnectAtMillis)

        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = false,
                requestGeneration = requestGeneration,
                linkGeneration = linkGeneration,
            ),
        )
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.connectCalls.size)
        assertEquals(BleConnectionState.Reconnecting(device, 2), coordinator.connectionState)
    }

    @Test
    fun availabilityLossPreservesManualDisconnectAndNeverSchedulesReconnect() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        val requestGeneration = coordinator.currentRequestGeneration
        val linkGeneration = coordinator.currentLinkGeneration
        assertTrue(coordinator.disconnect())
        assertEquals(1, transport.disconnectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)

        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertEquals(BleConnectionState.Unavailable("Bluetooth is off"), coordinator.connectionState)
        assertEquals(requestGeneration, coordinator.currentRequestGeneration)
        assertEquals(linkGeneration, coordinator.currentLinkGeneration)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(10_000L))
        assertEquals(1, transport.connectCalls.size)
        assertEquals(1, transport.disconnectCalls.size)

        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = false,
                requestGeneration = requestGeneration,
                linkGeneration = linkGeneration,
            ),
        )
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
    }

    @Test
    fun discoveryTimeoutStopsAtExactlyFiveSeconds() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.startDiscovery())
        assertFalse(coordinator.tick(4_999L))
        assertEquals(BleConnectionState.Searching, coordinator.connectionState)
        assertTrue(coordinator.tick(5_000L))
        assertEquals(BleConnectionState.Failed("Bluetooth discovery timed out"), coordinator.connectionState)
        assertEquals(1, transport.discoveryStops)
    }

    @Test
    fun connectionTimeoutUsesThirtySecondsAndSchedulesEightSecondReconnect() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.connect(device))
        assertFalse(coordinator.tick(29_999L))
        assertEquals(BleConnectionState.Connecting(device), coordinator.connectionState)
        now = 30_000L
        assertTrue(coordinator.tick(30_000L))
        assertEquals(BleConnectionState.Failed("Bluetooth connection timed out"), coordinator.connectionState)
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(null, coordinator.pendingReconnectAtMillis)

        // A new link is not attempted until the transport confirms that the
        // timed-out physical GATT has actually closed.
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(38_000L, coordinator.pendingReconnectAtMillis)

        now = 37_999L
        assertFalse(coordinator.tick(now))
        now = 38_000L
        assertTrue(coordinator.tick(now))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
        assertEquals(listOf(device, device), transport.connectCalls)
    }

    @Test
    fun synchronousTimeoutDisconnectFailureSchedulesBoundedCloseOnlyRetries() {
        var now = 0L
        val transport = RecordingTransport().apply { disconnectAccepted = false }
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            maxReconnectAttempts = 2,
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        now = 30_000L
        assertFalse(coordinator.tick(now))
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(31_000L, coordinator.pendingReconnectAtMillis)
        assertTrue(coordinator.hasPhysicalLink)

        // A synchronous false result retains the pending link. Each due tick
        // retries close only, with no replacement connect over that link.
        now = 30_999L
        assertFalse(coordinator.tick(now))
        assertEquals(1, transport.disconnectCalls.size)
        now = 31_000L
        assertFalse(coordinator.tick(now))
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(32_000L, coordinator.pendingReconnectAtMillis)
        assertEquals(1, transport.connectCalls.size)

        now = 32_000L
        assertFalse(coordinator.tick(now))
        assertEquals(3, transport.disconnectCalls.size)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun synchronousReconnectDisconnectExceptionSchedulesCloseRetryAndWaitsForAck() {
        var now = 0L
        val transport = RecordingTransport().apply {
            disconnectException = IllegalStateException("synthetic disconnect failure")
        }
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertFalse(coordinator.reconnectKnownDevice())
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        assertEquals(1, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)

        transport.disconnectException = null
        now = 1_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(1_999L))
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.connectCalls.size)
        assertEquals(BleConnectionState.Reconnecting(device, 2), coordinator.connectionState)
    }

    @Test
    fun failedTimeoutCancellationCannotPublishLateConnectionOrStartReconnect() {
        var now = 0L
        val transport = RecordingTransport().apply { disconnectAccepted = false }
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.connect(device))
        now = 30_000L
        assertFalse(coordinator.tick(now))
        assertEquals(BleConnectionState.Failed("Bluetooth connection timed out"), coordinator.connectionState)
        assertEquals(38_000L, coordinator.pendingReconnectAtMillis)

        // The SDK may finish connecting after its public cancellation failed.
        // It must be closed again and must never become app-visible Connected.
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(2, transport.disconnectCalls.size)
        assertTrue(coordinator.connectionState !is BleConnectionState.Connected)
        assertEquals(38_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(37_999L))
        now = 38_000L
        assertFalse(coordinator.tick(now))
        assertEquals(3, transport.disconnectCalls.size)
        assertEquals(46_000L, coordinator.pendingReconnectAtMillis)
        assertEquals(1, transport.connectCalls.size)
    }

    @Test
    fun failedManualDisconnectCanBeRetriedWithoutReplacingPhysicalLink() {
        val transport = RecordingTransport().apply { disconnectAccepted = false }
        val coordinator = BleConnectionCoordinator(transport)

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertFalse(coordinator.disconnect())
        assertEquals(
            BleConnectionState.Failed("Bluetooth disconnect could not start"),
            coordinator.connectionState,
        )
        assertTrue(coordinator.hasPhysicalLink)

        // A failed synchronous close must be retryable, but the retry cannot
        // create a second connection while the first link is still retained.
        transport.disconnectAccepted = true
        assertTrue(coordinator.disconnect())
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertFalse(coordinator.hasPhysicalLink)
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
    }

    @Test
    fun failedDisconnectIsReportedAndKnownReconnectRetriesCloseBeforeConnecting() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { 0L },
        )

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertTrue(coordinator.disconnect())
        assertTrue(coordinator.hasPhysicalLink)

        // The adapter accepted the close request but later reported that the
        // physical link was still present. This must not be treated as a
        // normal Disconnected event.
        transport.emit(BleTransportEvent.DisconnectFailed(device))
        assertEquals(
            BleConnectionState.Failed("Bluetooth disconnect failed"),
            coordinator.connectionState,
        )
        assertTrue(coordinator.hasPhysicalLink)

        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)

        // Only the close acknowledgement permits the reconnect timer to arm.
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(8_000L, coordinator.pendingReconnectAtMillis)
        assertTrue(coordinator.tick(8_000L))
        assertEquals(2, transport.connectCalls.size)
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
    }

    @Test
    fun automaticReconnectRetriesAFailedCloseWithoutOverlappingConnectAttempts() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            maxReconnectAttempts = 2,
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        assertFalse(coordinator.tick(29_999L))
        assertTrue(coordinator.tick(30_000L))
        assertEquals(1, transport.disconnectCalls.size)

        // The timeout close was accepted but the adapter could not prove that
        // the pending GATT link closed. Retries target disconnect only.
        transport.emit(BleTransportEvent.DisconnectFailed(device))
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        now = 1_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)

        transport.emit(BleTransportEvent.DisconnectFailed(device))
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(3, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)

        // The bounded close retry budget is exhausted; no fresh connection is
        // attempted over the still-retained physical link.
        transport.emit(BleTransportEvent.DisconnectFailed(device))
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertEquals(1, transport.connectCalls.size)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun automaticReconnectConnectsOnlyAfterRetryCloseIsAcknowledged() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            maxReconnectAttempts = 3,
            nowMillis = { now },
        )

        assertTrue(coordinator.connect(device))
        assertTrue(coordinator.tick(30_000L))
        transport.emit(BleTransportEvent.DisconnectFailed(device))
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)

        now = 1_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.disconnectCalls.size)
        assertEquals(1, transport.connectCalls.size)

        // A close acknowledgement, not the retry request itself, is the
        // boundary that permits the next physical connection.
        assertFalse(coordinator.tick(1_001L))
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(1_999L))
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.connectCalls.size)
        assertEquals(BleConnectionState.Reconnecting(device, 2), coordinator.connectionState)
    }

    @Test
    fun unrequestedUntaggedDisconnectFailureCannotDemoteCurrentConnection() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport)

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)

        // Legacy transports may omit generation tokens. Without a matching
        // coordinator-owned disconnect request, a late failure is ambiguous
        // and must not replace a healthy link.
        transport.emit(BleTransportEvent.DisconnectFailed(device))

        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun staleGenerationOrDeviceEventsCannotReplaceCurrentLinkOrDeliverScan() {
        val otherDevice = ScannerDevice("scanner-2", "BCST-47")
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })
        val received = mutableListOf<String>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload.value
            }
        })

        assertTrue(coordinator.connect(device))
        val firstRequest = coordinator.pendingRequestGeneration
        val firstLink = coordinator.pendingLinkGeneration
        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = true,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        now = 8_000L
        assertTrue(coordinator.tick(now))
        val secondRequest = coordinator.pendingRequestGeneration
        val secondLink = coordinator.pendingLinkGeneration

        // A late callback from the first link and a device mismatch are both
        // ignored while the second request is still connecting.
        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(BleTransportEvent.Connected(otherDevice))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)

        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = secondLink,
                requestGeneration = secondRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.DisconnectFailed(
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = true,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.ScanReceived(
                payload = ScanPayload.qr("STALE"),
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertTrue(coordinator.pendingReconnectAtMillis == null)

        transport.emit(
            BleTransportEvent.ScanReceived(
                payload = ScanPayload.qr("CURRENT"),
                device = device,
                linkGeneration = secondLink,
                requestGeneration = secondRequest,
            ),
        )
        assertEquals(listOf("CURRENT"), received)
    }

    private class RecordingTransport(
        override var availability: BleAvailability = BleAvailability.Ready,
    ) : BleTransport {
        override var listener: BleTransportListener? = null
        var discoveryStarts = 0
        var discoveryStops = 0
        val connectCalls = mutableListOf<ScannerDevice>()
        val disconnectCalls = mutableListOf<ScannerDevice>()
        var disconnectAccepted = true
        var disconnectException: Exception? = null

        override fun startDiscovery(): Boolean {
            discoveryStarts += 1
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStarted)
            return true
        }

        override fun stopDiscovery(): Boolean {
            discoveryStops += 1
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
            return true
        }

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls += device
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls += device
            disconnectException?.let { throw it }
            return disconnectAccepted
        }

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean = true

        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ): Boolean = true

        fun emit(event: BleTransportEvent) {
            listener?.onTransportEvent(event)
        }
    }
}
