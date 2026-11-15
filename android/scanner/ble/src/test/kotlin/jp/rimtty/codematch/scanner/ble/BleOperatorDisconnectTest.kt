package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator's Disconnect lasts until the operator connects again (#137):
 * no automatic reconnect is scheduled or started meanwhile, also after a
 * process restart, while the known device stays available for an explicit
 * reconnect.
 */
class BleOperatorDisconnectTest {
    private val profileIdentity = "observed-adapter-profile-v1"
    private val device = ScannerDevice("scanner-1", "BCST-47")

    @Test
    fun operatorDisconnectSurvivesARestartAndKeepsTheKnownDevice() {
        val store = InMemoryKnownDeviceStore(profileIdentity)
        val firstTransport = RecordingTransport()
        val first = coordinator(firstTransport, store)
        assertFalse(first.isOperatorDisconnected)

        assertTrue(first.connect(device))
        firstTransport.emit(BleTransportEvent.Connected(device))
        assertTrue(first.setOperatorDisconnected(true))
        assertTrue(first.disconnect())
        firstTransport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertTrue(store.operatorDisconnected)
        assertEquals(BleKnownDeviceReadResult.Found(device), store.read())

        // A new process restores both the identity and the operator's intent.
        val secondTransport = RecordingTransport()
        val second = coordinator(secondTransport, store)
        assertTrue(second.isOperatorDisconnected)
        assertEquals(device, second.knownDevice)

        // Foreground return, Bluetooth off and on again: nothing reconnects.
        second.setApplicationActive(false, 0L)
        second.setApplicationActive(true, 0L)
        secondTransport.availability = BleAvailability.PoweredOff
        secondTransport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        second.setApplicationActive(false, 0L)
        second.setApplicationActive(true, 0L)
        assertNull(second.pendingReconnectAtMillis)
        secondTransport.availability = BleAvailability.Ready
        secondTransport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Ready))
        assertFalse(second.tick(600_000L))
        assertTrue(secondTransport.connectCalls.isEmpty())

        // The operator's own reconnect ends it and targets the kept identity.
        assertTrue(second.setOperatorDisconnected(false))
        assertFalse(store.operatorDisconnected)
        assertTrue(second.reconnectKnownDevice())
        assertEquals(listOf(device), secondTransport.connectCalls)
    }

    @Test
    fun facadeExposesTheKnownScannerAfterARestartWithoutConnectingIt() {
        // Settings offers a one-tap reconnect from this value alone.
        val store = InMemoryKnownDeviceStore(profileIdentity).apply {
            save(device)
            saveOperatorDisconnected(true)
        }
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, store)
        val facade = SelectableBleExternalScanner(
            connectionCoordinator = coordinator,
            sessionFactory = BleSessionCoordinatorFactory { error("no session is bound here") },
        )

        assertEquals(device, facade.knownDevice)
        assertTrue(facade.devices.isEmpty())
        assertEquals(jp.rimtty.codematch.scanner.api.ConnectionState.Idle, facade.connectionState)
        assertTrue(transport.connectCalls.isEmpty())
        assertTrue(coordinator.isOperatorDisconnected)

        // A scanner forgotten by the GATT mode switch is not offered any more.
        coordinator.forgetKnownDevice(device.id)
        assertNull(facade.knownDevice)
        assertTrue(transport.connectCalls.isEmpty())
    }

    @Test
    fun withoutTheFlagTheSameOutageSchedulesAnAutomaticReconnect() {
        // Control for the test above: the guard, not the fake, holds it back.
        val store = InMemoryKnownDeviceStore(profileIdentity).apply { save(device) }
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, store)

        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertNotNull(coordinator.pendingReconnectAtMillis)
    }

    @Test
    fun operatorDisconnectDropsAnAlreadyScheduledReconnect() {
        val store = InMemoryKnownDeviceStore(profileIdentity)
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, store)
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        assertNotNull(coordinator.pendingReconnectAtMillis)

        assertTrue(coordinator.setOperatorDisconnected(true))
        assertNull(coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(600_000L))
        assertEquals(listOf(device), transport.connectCalls)
        assertEquals(device, coordinator.knownDevice)
    }

    @Test
    fun retrySequenceOfDisconnectThenReconnectStillReconnects() {
        // Retry is `disconnect(); reconnectKnownDevice()` on the facade: the
        // flag is set and cleared again before the close completes.
        val store = InMemoryKnownDeviceStore(profileIdentity)
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, store)
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))

        coordinator.setOperatorDisconnected(true)
        assertTrue(coordinator.disconnect())
        coordinator.setOperatorDisconnected(false)
        assertTrue(coordinator.reconnectKnownDevice())
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        val dueAt = coordinator.pendingReconnectAtMillis
        assertNotNull(dueAt)
        assertTrue(coordinator.tick(dueAt!!))
        assertEquals(listOf(device, device), transport.connectCalls)
        assertFalse(store.operatorDisconnected)
    }

    @Test
    fun flagChangesAreLoggedOnceWithoutScannerDetails() {
        val store = InMemoryKnownDeviceStore(profileIdentity).apply { save(device) }
        val coordinator = coordinator(RecordingTransport(), store)

        coordinator.setOperatorDisconnected(true)
        coordinator.setOperatorDisconnected(true)
        coordinator.setOperatorDisconnected(false)
        val messages = coordinator.state.diagnostics.map { it.message }
        assertEquals(
            listOf(
                "Automatic reconnect paused: the operator disconnected the scanner",
                "Automatic reconnect resumed: the operator requested a connection",
            ),
            messages.filter { it.startsWith("Automatic reconnect") },
        )
        assertTrue(messages.none { it.contains(device.id) || it.contains(device.name) })
        assertEquals(2, store.flagWrites)
    }

    @Test
    fun unsavedFlagStillAppliesToThisProcessAndIsReported() {
        val store = InMemoryKnownDeviceStore(profileIdentity).apply {
            save(device)
            failFlagWrites = true
        }
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, store)

        assertFalse(coordinator.setOperatorDisconnected(true))
        assertTrue(coordinator.isOperatorDisconnected)
        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(BleAvailability.PoweredOff))
        assertNull(coordinator.pendingReconnectAtMillis)
        assertTrue(
            coordinator.state.diagnostics.any {
                it.message == "Operator disconnect state could not be saved"
            },
        )
    }

    @Test
    fun storeWithoutFlagSupportNeverHoldsReconnectsBack() {
        val store = object : KnownDeviceStore {
            override val profileIdentity = this@BleOperatorDisconnectTest.profileIdentity
            override fun read(): BleKnownDeviceReadResult = BleKnownDeviceReadResult.Found(device)
            override fun save(device: ScannerDevice): BleKnownDeviceWriteResult =
                BleKnownDeviceWriteResult.Saved
            override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult =
                BleKnownDeviceClearResult.Missing
        }
        assertFalse(coordinator(RecordingTransport(), store).isOperatorDisconnected)
    }

    private fun coordinator(
        transport: RecordingTransport,
        store: KnownDeviceStore,
    ): BleConnectionCoordinator = BleConnectionCoordinator(
        transport = transport,
        knownDeviceStore = store,
        nowMillis = { 0L },
    )

    private class InMemoryKnownDeviceStore(
        override val profileIdentity: String,
    ) : KnownDeviceStore {
        private var current: BleKnownDeviceReadResult = BleKnownDeviceReadResult.Missing
        var operatorDisconnected = false
            private set
        var flagWrites = 0
            private set
        var failFlagWrites = false

        override fun read(): BleKnownDeviceReadResult = current

        override fun save(device: ScannerDevice): BleKnownDeviceWriteResult {
            current = BleKnownDeviceReadResult.Found(device)
            return BleKnownDeviceWriteResult.Saved
        }

        override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult {
            current = BleKnownDeviceReadResult.Missing
            return BleKnownDeviceClearResult.Cleared
        }

        override fun readOperatorDisconnected(): Boolean = operatorDisconnected

        override fun saveOperatorDisconnected(disconnected: Boolean): Boolean {
            if (failFlagWrites) return false
            flagWrites++
            operatorDisconnected = disconnected
            return true
        }
    }

    private class RecordingTransport(
        override var availability: BleAvailability = BleAvailability.Ready,
    ) : BleTransport {
        override var listener: BleTransportListener? = null
        val connectCalls = mutableListOf<ScannerDevice>()

        override fun startDiscovery(): Boolean = true

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls += device
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean = true

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
