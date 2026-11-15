package jp.rimtty.codematch.scanner.fake

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeExternalScannerTest {
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    @Test
    fun discoveryAndConnectionAreSynchronousAndObservable() {
        val scanner = FakeExternalScanner()
        val connections = mutableListOf<ConnectionState>()
        val configurations = mutableListOf<ConfigurationState>()
        scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                connections += state
            }

            override fun onConfigurationStateChanged(state: ConfigurationState) {
                configurations += state
            }
        }

        assertTrue(scanner.startDiscovery())
        assertEquals(listOf(FakeExternalScanner.DEFAULT_DEVICE), scanner.devices)
        assertEquals(ConnectionState.Searching, scanner.connectionState)
        assertTrue(connections.contains(ConnectionState.Searching))

        assertTrue(scanner.stopDiscovery())
        assertEquals(ConnectionState.Idle, scanner.connectionState)
        assertTrue(scanner.connect(scanner.devices.single()))
        assertEquals(ConnectionState.Connected(scanner.defaultDevice), scanner.connectionState)
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
        assertTrue(scanner.isReadyForScanning)
        assertTrue(configurations.contains(ConfigurationState.Configuring))
    }

    @Test
    fun listenersFanOutWithoutStealingLegacyOrOtherObservers() {
        val scanner = FakeExternalScanner()
        val legacyConnections = mutableListOf<ConnectionState>()
        val settingsConnections = mutableListOf<ConnectionState>()
        val scanPayloads = mutableListOf<ScanPayload>()
        val settingsListener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                settingsConnections += state
            }
        }
        val scanListener = object : ExternalScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                scanPayloads += payload
            }
        }
        scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                legacyConnections += state
            }
        }

        assertTrue(scanner.addListener(settingsListener))
        assertTrue(scanner.addListener(scanListener))
        scanner.startDiscovery()
        scanner.connect(scanner.devices.single())

        // The legacy property and Settings/Scan observers all receive the
        // same transport. A settings subscription must not replace the
        // callback that consumes scanner payloads.
        assertTrue(legacyConnections.contains(ConnectionState.Searching))
        assertTrue(settingsConnections.contains(ConnectionState.Connected(scanner.defaultDevice)))
        assertTrue(scanner.emitPayload("qr-from-fanout", ScanFormat.QR))
        assertEquals("qr-from-fanout", scanPayloads.single().value)

        val settingsEventCount = settingsConnections.size
        assertTrue(scanner.removeListener(settingsListener))
        assertFalse(scanner.removeListener(settingsListener))
        scanner.disconnect()
        assertEquals(settingsEventCount, settingsConnections.size)
        assertTrue(legacyConnections.contains(ConnectionState.Idle))
        assertTrue(scanPayloads.isNotEmpty())
        assertTrue(scanner.removeListener(scanListener))
    }

    @Test
    fun availabilitySimulationClassifiesPlatformNeutralIssuesAndCanRecover() {
        val scanner = FakeExternalScanner()

        assertTrue(scanner.simulatePermissionDenied())
        assertFalse(scanner.startDiscovery())
        assertEquals(
            ConnectionState.Unavailable(FakeExternalScanner.PERMISSION_DENIED_REASON),
            scanner.connectionState,
        )
        assertEquals(
            jp.rimtty.codematch.scanner.api.ScannerIssue.PERMISSION_DENIED,
            jp.rimtty.codematch.scanner.api.scannerIssueFor(
                scanner.connectionState,
                scanner.configurationState,
            ),
        )
        assertTrue(scanner.restoreBluetooth())
        assertTrue(scanner.startDiscovery())

        assertTrue(scanner.simulatePoweredOff())
        assertEquals(
            jp.rimtty.codematch.scanner.api.ScannerIssue.POWERED_OFF,
            jp.rimtty.codematch.scanner.api.scannerIssueFor(
                scanner.connectionState,
                scanner.configurationState,
            ),
        )
    }

    @Test
    fun restoreFailureStaysNotReadyUntilAReconnect() {
        val scanner = connectedScanner()

        assertTrue(scanner.simulateRestoreFailure())
        assertEquals(
            ConfigurationState.Failed(FakeExternalScanner.DEFAULT_RESTORE_FAILURE),
            scanner.configurationState,
        )
        assertFalse(scanner.isReadyForScanning)
        assertEquals(
            jp.rimtty.codematch.scanner.api.ScannerIssue.RESTORE_FAILED,
            jp.rimtty.codematch.scanner.api.scannerIssueFor(
                scanner.connectionState,
                scanner.configurationState,
            ),
        )

        assertTrue(scanner.reconnectKnownDevice())
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
        assertTrue(scanner.isReadyForScanning)
    }

    @Test
    fun connectionAndConfigurationFailuresAreExplicitStates() {
        val scanner = FakeExternalScanner()
        scanner.startDiscovery()

        scanner.failNextConnection("radio unavailable")
        assertFalse(scanner.connect(scanner.devices.single()))
        assertEquals(ConnectionState.Failed("radio unavailable"), scanner.connectionState)
        assertEquals(ConfigurationState.Unavailable, scanner.configurationState)

        scanner.reconnectKnownDevice()
        scanner.failNextConfiguration("settings rejected")
        assertFalse(scanner.setExpectedFormat(ScanFormat.QR))
        assertEquals(ConfigurationState.Failed("settings rejected"), scanner.configurationState)
        assertFalse(scanner.isReadyForScanning)
        assertEquals(
            ScannerIssue.CONFIGURATION_FAILED,
            jp.rimtty.codematch.scanner.api.scannerIssueFor(
                scanner.connectionState,
                scanner.configurationState,
            ),
        )
    }

    @Test
    fun expectedFormatChangesAreLogicalAndDoNotReconfigureEveryStep() {
        val scanner = connectedScanner()
        val configurations = mutableListOf<ConfigurationState>()
        scanner.onConfigurationStateChanged = { configurations += it }

        assertTrue(scanner.setExpectedFormat(ScanFormat.QR))
        configurations.clear()
        assertTrue(scanner.setExpectedFormat(ScanFormat.CODE_128))

        assertEquals(ScanFormat.CODE_128, scanner.expectedFormat)
        assertTrue(configurations.isEmpty())
        assertEquals(ConfigurationState.Ready, scanner.configurationState)

        scanner.setExpectedFormat(null)
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
    }

    @Test
    fun payloadsAreIssuedOnlyWhenTransportIsReadyAndTerminatorsAreRemoved() {
        val scanner = FakeExternalScanner()
        val received = mutableListOf<ScanPayload>()
        scanner.onPayload = { received += it }

        assertFalse(
            scanner.emitPayload(
                qrPayload,
                format = ScanFormat.QR,
                source = InputSource.BLUETOOTH,
            ),
        )
        assertTrue(received.isEmpty())

        scanner.startDiscovery()
        scanner.connect(scanner.devices.single())
        assertTrue(
            scanner.emitPayload(
                "$qrPayload\r\n\u0000",
                format = ScanFormat.QR,
                source = InputSource.BLUETOOTH,
            ),
        )
        assertEquals(qrPayload, received.single().value)
        assertEquals(InputSource.BLUETOOTH, received.single().source)
    }

    @Test
    fun duplicateCallbacksAreSuppressedByInjectedClock() {
        var now = 1_700_000_000_000L
        val scanner = FakeExternalScanner(now = { now })
        val received = mutableListOf<String>()
        scanner.onPayload = { received += it.value }
        scanner.startDiscovery()
        scanner.connect(scanner.devices.single())

        assertTrue(scanner.emitPayload("ABC\r", ScanFormat.CODE_128))
        now += 200L
        assertFalse(scanner.emitPayload("ABC\n", ScanFormat.CODE_128))
        now += 800L
        assertTrue(scanner.emitPayload("ABC", ScanFormat.CODE_128))
        assertEquals(listOf("ABC", "ABC"), received)
    }

    @Test
    fun diagnosticsKeepOnlyConnectionConfigurationEventsAndNeverPayloads() {
        val scanner = connectedScanner()
        repeat(25) { index ->
            scanner.recordConnectionEvent("connection event $index")
        }
        scanner.emitPayload(barcodePayload, ScanFormat.CODE_128)

        assertEquals(FakeExternalScanner.MAX_DIAGNOSTIC_EVENTS, scanner.diagnosticEvents.size)
        assertTrue(scanner.diagnosticEvents.all { it.category != DiagnosticCategory.ERROR || it.message.isNotEmpty() })
        assertTrue(scanner.diagnosticEvents.none { it.message.contains(barcodePayload) })
        assertTrue(scanner.diagnosticEvents.none { it.message.contains("ABC") })
        assertTrue(scanner.diagnosticEvents.all { it.category == DiagnosticCategory.CONNECTION || it.category == DiagnosticCategory.CONFIGURATION })
    }

    @Test
    fun disconnectPreservesKnownDeviceForExplicitReconnect() {
        val scanner = connectedScanner()
        val device = scanner.connectedDevice
        assertTrue(scanner.disconnect())
        assertNull(scanner.connectedDevice)
        assertEquals(ConfigurationState.Unavailable, scanner.configurationState)
        assertEquals(device, scanner.preferredDevice)
        // Exposed through the contract so Settings can offer a one-tap reconnect.
        assertEquals(device, scanner.knownDevice)
        assertNull(FakeExternalScanner().knownDevice)

        assertTrue(scanner.reconnectKnownDevice())
        assertEquals(device, scanner.connectedDevice)
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
    }

    @Test
    fun discoveryCanBeStoppedOrEndedByConnection() {
        val scanner = FakeExternalScanner()
        assertTrue(scanner.startDiscovery())
        assertEquals(ConnectionState.Searching, scanner.connectionState)
        assertTrue(scanner.connect(scanner.devices.single()))
        assertEquals(ConnectionState.Connected(scanner.defaultDevice), scanner.connectionState)

        assertFalse(scanner.stopDiscovery())
    }

    @Test
    fun disconnectResetsDuplicateHistoryForTheNextConnection() {
        val scanner = connectedScanner()
        val delivered = mutableListOf<String>()
        scanner.onPayload = { delivered += it.value }

        assertTrue(scanner.emitPayload(barcodePayload, ScanFormat.CODE_128))
        assertTrue(scanner.disconnect())
        assertTrue(scanner.reconnectKnownDevice())
        assertTrue(scanner.emitPayload(barcodePayload, ScanFormat.CODE_128))

        assertEquals(listOf(barcodePayload, barcodePayload), delivered)
    }

    private fun connectedScanner(): FakeExternalScanner {
        val scanner = FakeExternalScanner()
        scanner.startDiscovery()
        scanner.connect(scanner.devices.single())
        return scanner
    }
}
