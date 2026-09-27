package jp.rimtty.codematch.feature.settings

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnect/Retry target the operator's selection (#137). */
class SettingsScannerCommandsTest {
    private val known = ScannerDevice("known", "HPRT-636E")
    private val chosen = ScannerDevice("chosen", "HPRT-4F5F")

    private class RecordingScanner : ExternalScanner {
        override val devices: List<ScannerDevice> = emptyList()
        override var connectionState: ConnectionState = ConnectionState.Idle
        override var configurationState: ConfigurationState = ConfigurationState.Unavailable
        override val diagnosticEvents: List<DiagnosticEvent> = emptyList()
        override val expectedFormat: ScanFormat? = null
        override var listener: ExternalScannerListener? = null
        val calls = mutableListOf<String>()
        var accept = true
        var reconnectAccepted = true

        override fun startDiscovery(): Boolean = true.also { calls += "startDiscovery" }
        override fun stopDiscovery(): Boolean = true.also { calls += "stopDiscovery" }
        override fun connect(device: ScannerDevice): Boolean =
            accept.also { calls += "connect:${device.id}" }
        override fun disconnect(): Boolean = true.also { calls += "disconnect" }
        override fun reconnectKnownDevice(): Boolean =
            reconnectAccepted.also { calls += "reconnectKnownDevice" }
        override fun setExpectedFormat(format: ScanFormat?): Boolean = true
    }

    private fun state(selectedId: String?, vararg devices: ScannerDevice) =
        SettingsUiState(devices = devices.toList(), selectedDeviceId = selectedId)

    @Test
    fun reconnectTargetsTheSelectedScannerInsteadOfTheKnownOne() {
        val scanner = RecordingScanner()
        assertTrue(SettingsScannerCommands.reconnect(scanner, state(chosen.id, known, chosen)))
        assertEquals(listOf("connect:chosen"), scanner.calls)
    }

    @Test
    fun reconnectFallsBackToTheKnownDeviceWithoutASelection() {
        val scanner = RecordingScanner()
        assertTrue(SettingsScannerCommands.reconnect(scanner, state(null, known, chosen)))
        // A selection that is no longer listed is not a target either.
        assertTrue(SettingsScannerCommands.reconnect(scanner, state("gone", known)))
        assertEquals(listOf("reconnectKnownDevice", "reconnectKnownDevice"), scanner.calls)
    }

    @Test
    fun retryWhileReconnectingTargetsTheSelection() {
        val scanner = RecordingScanner().apply {
            connectionState = ConnectionState.Connecting(known)
        }
        assertTrue(SettingsScannerCommands.retry(scanner, state(chosen.id, known, chosen)))
        assertEquals(listOf("connect:chosen"), scanner.calls)
    }

    @Test
    fun retryWithoutSelectionReconnectsTheKnownDeviceOrSearches() {
        val scanner = RecordingScanner().apply {
            connectionState = ConnectionState.Failed("lost")
            reconnectAccepted = false
        }
        assertTrue(SettingsScannerCommands.retry(scanner, state(null)))
        assertEquals(listOf("reconnectKnownDevice", "startDiscovery"), scanner.calls)
    }

    @Test
    fun retryOfAConnectedScannerWithFailedConfigurationRehandshakesIt() {
        val scanner = RecordingScanner().apply {
            connectionState = ConnectionState.Connected(known)
            configurationState = ConfigurationState.Failed("restore")
        }
        assertTrue(SettingsScannerCommands.retry(scanner, state(chosen.id, known, chosen)))
        assertEquals(listOf("disconnect", "reconnectKnownDevice"), scanner.calls)

        val ready = RecordingScanner().apply {
            connectionState = ConnectionState.Connected(known)
            configurationState = ConfigurationState.Ready
        }
        assertTrue(SettingsScannerCommands.retry(ready, state(chosen.id, known, chosen)))
        assertTrue(ready.calls.isEmpty())
    }

    @Test
    fun refusedRequestsAreReported() {
        val scanner = RecordingScanner().apply { accept = false }
        assertFalse(SettingsScannerCommands.connect(scanner, chosen))
        assertFalse(SettingsScannerCommands.reconnect(scanner, state(chosen.id, chosen)))
        assertFalse(SettingsScannerCommands.retry(scanner, state(chosen.id, chosen)))
    }
}
