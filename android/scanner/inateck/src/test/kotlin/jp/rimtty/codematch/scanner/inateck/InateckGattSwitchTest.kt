package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-up of a scanner restarted in GATT mode (#137, iOS #134 parity). */
class InateckGattSwitchTest {
    private val origin = ScannerDevice("2B94A2A1", "Hyper 160B-4F5F-UNI")
    private val renamed = ScannerDevice("4CD0F65B", "HPRT-4F5F")
    private val unrelated = ScannerDevice("6680C70B", "HPRT-636E")

    private class FakeOperations : InateckGattSwitch.Operations {
        override var isLinkActive = true
        override var isSearching = false
        val forgotten = mutableListOf<String>()
        val connects = mutableListOf<ScannerDevice>()
        var discoveries = 0
        var discoveryAccepted = true
        var manualSelectionRequests = 0
        val diagnostics = mutableListOf<String>()

        override fun forgetKnownDevice(deviceId: String) {
            forgotten += deviceId
        }

        override fun startDiscovery(): Boolean {
            discoveries++
            if (discoveryAccepted) isSearching = true
            return discoveryAccepted
        }

        override fun connect(device: ScannerDevice): Boolean {
            connects += device
            return true
        }

        override fun requireManualSelection() {
            manualSelectionRequests++
        }

        override fun diagnostic(message: String, error: Boolean) {
            diagnostics += message
        }
    }

    private var now = 0L
    private val operations = FakeOperations()
    private val gattSwitch = InateckGattSwitch(operations, nowMillis = { now })

    /** Advance past the rediscovery delay and start a search round. */
    private fun runSearch(vararg found: ScannerDevice) {
        now += InateckGattSwitch.DEFAULT_REDISCOVERY_DELAY_MILLIS
        gattSwitch.tick()
        assertTrue(operations.isSearching)
        found.forEach(gattSwitch::onDeviceDiscovered)
        gattSwitch.tick()
        operations.isSearching = false
        gattSwitch.tick()
    }

    @Test
    fun waitsForTheOriginLinkToCloseThenForgetsItAndConnectsTheRenamedScanner() {
        gattSwitch.begin(origin)
        gattSwitch.tick()
        assertTrue(operations.forgotten.isEmpty())
        assertEquals(0, operations.discoveries)

        operations.isLinkActive = false
        gattSwitch.tick()
        // The origin may never advertise again: it stops being the known device.
        assertEquals(listOf(origin.id), operations.forgotten)
        gattSwitch.tick()
        assertEquals(0, operations.discoveries)

        runSearch(origin, unrelated, renamed)
        assertEquals(1, operations.discoveries)
        assertEquals(listOf(renamed), operations.connects)
        assertFalse(gattSwitch.isActive)
        assertEquals(0, operations.manualSelectionRequests)
    }

    @Test
    fun reconnectsTheOriginWhenItsIdentityDidNotChange() {
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()

        runSearch(unrelated, origin)
        assertEquals(listOf(origin), operations.connects)
        assertFalse(gattSwitch.isActive)
    }

    @Test
    fun searchesAgainWhenNothingIsFoundAndGivesUpAfterThreeRounds() {
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()

        runSearch(unrelated)
        assertTrue(gattSwitch.isActive)
        runSearch()
        assertTrue(gattSwitch.isActive)
        runSearch(unrelated)
        assertEquals(3, operations.discoveries)
        assertTrue(operations.connects.isEmpty())
        assertFalse(gattSwitch.isActive)
        assertEquals(1, operations.manualSelectionRequests)
        assertEquals(listOf(origin.id), operations.forgotten)
    }

    @Test
    fun foundInALaterRoundStillConnects() {
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()

        runSearch()
        runSearch(renamed)
        assertEquals(listOf(renamed), operations.connects)
        assertEquals(0, operations.manualSelectionRequests)
    }

    @Test
    fun severalCandidatesAskTheOperatorToChoose() {
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()

        runSearch(renamed, ScannerDevice("99999999", "BCST-4F5F"))
        assertTrue(operations.connects.isEmpty())
        assertEquals(1, operations.manualSelectionRequests)
        assertFalse(gattSwitch.isActive)
    }

    @Test
    fun devicesSeenBeforeTheRoundDoNotCount() {
        gattSwitch.begin(origin)
        // Reported while still waiting to search: not evidence of this round.
        gattSwitch.onDeviceDiscovered(renamed)
        operations.isLinkActive = false
        gattSwitch.tick()
        runSearch()
        assertTrue(operations.connects.isEmpty())
        assertTrue(gattSwitch.isActive)
    }

    @Test
    fun aSearchThatCannotStartCountsAsARound() {
        operations.discoveryAccepted = false
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()
        repeat(3) {
            now += InateckGattSwitch.DEFAULT_REDISCOVERY_DELAY_MILLIS
            gattSwitch.tick()
        }
        assertEquals(3, operations.discoveries)
        assertEquals(1, operations.manualSelectionRequests)
        assertFalse(gattSwitch.isActive)
    }

    @Test
    fun operatorActionCancelsTheFollowUp() {
        gattSwitch.begin(origin)
        operations.isLinkActive = false
        gattSwitch.tick()
        gattSwitch.cancel()
        runCatching { runSearch(renamed) }
        assertTrue(operations.connects.isEmpty())
        assertEquals(0, operations.discoveries)
        assertEquals(0, operations.manualSelectionRequests)
    }
}
