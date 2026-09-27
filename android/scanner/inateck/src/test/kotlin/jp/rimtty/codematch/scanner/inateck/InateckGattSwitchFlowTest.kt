package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.ScannerFailureReasons
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BleConnectionCoordinator
import jp.rimtty.codematch.scanner.ble.BleKnownDeviceClearResult
import jp.rimtty.codematch.scanner.ble.BleKnownDeviceReadResult
import jp.rimtty.codematch.scanner.ble.BleKnownDeviceWriteResult
import jp.rimtty.codematch.scanner.ble.BlePermissionState
import jp.rimtty.codematch.scanner.ble.BleScannerSessionCoordinator
import jp.rimtty.codematch.scanner.ble.BleSessionCoordinatorFactory
import jp.rimtty.codematch.scanner.ble.BleSymbologyProfile
import jp.rimtty.codematch.scanner.ble.BleSymbologySession
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness
import jp.rimtty.codematch.scanner.ble.InMemorySymbologySnapshotStore
import jp.rimtty.codematch.scanner.ble.KnownDeviceStore
import jp.rimtty.codematch.scanner.ble.SelectableBleExternalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATT-switch follow-up over the real transport/coordinator/facade stack with
 * a fake SDK gateway, wired the way [InateckExternalScanner.create] wires it.
 */
class InateckGattSwitchFlowTest {
    private val origin = ScannerDevice("2B94A2A1", "Hyper 160B-4F5F-UNI")
    private val renamed = ScannerDevice("4CD0F65B", "HPRT-4F5F")
    private val unrelated = ScannerDevice("6680C70B", "HPRT-636E")

    private class Gateway : InateckSdkGateway {
        override val readiness = BleTransportReadiness(
            lifecycle = BleAdapterLifecycleState.FOREGROUND,
            availability = BleAvailability.Ready,
            discoveryPermission = BlePermissionState.GRANTED,
            connectionPermission = BlePermissionState.GRANTED,
        )
        val connectIds = mutableListOf<String>()
        var connectCompletion: ((Result<Unit>) -> Unit)? = null
        var onDevice: ((InateckSdkDevice) -> Unit)? = null
        var onFinished: (() -> Unit)? = null
        var discoveries = 0

        override fun startDiscovery(
            onDevice: (InateckSdkDevice) -> Unit,
            onFinished: () -> Unit,
        ): Boolean {
            discoveries++
            this.onDevice = onDevice
            this.onFinished = onFinished
            return true
        }

        override fun stopDiscovery(): Boolean = true

        override fun connect(
            deviceId: String,
            onScanBytes: (ByteArray) -> Unit,
            onDisconnected: (unexpected: Boolean) -> Unit,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            connectIds += deviceId
            connectCompletion = completion
            return true
        }

        override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean {
            completion(Result.success(Unit))
            return true
        }

        override fun readSettings(
            deviceId: String,
            completion: (Result<List<Map<String, String>>>) -> Unit,
        ): Boolean = true

        override fun writeSettings(
            deviceId: String,
            commandJson: String,
            completion: (Result<Unit>) -> Unit,
        ): Boolean = true

        override fun close() = Unit
    }

    private class KnownStore : KnownDeviceStore {
        override val profileIdentity = INATECK_ANDROID_SDK_PROFILE_IDENTITY
        var saved: ScannerDevice? = null

        override fun read(): BleKnownDeviceReadResult =
            saved?.let { BleKnownDeviceReadResult.Found(it) } ?: BleKnownDeviceReadResult.Missing

        override fun save(device: ScannerDevice): BleKnownDeviceWriteResult {
            saved = device
            return BleKnownDeviceWriteResult.Saved
        }

        override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult {
            if (saved == null) return BleKnownDeviceClearResult.Missing
            if (expectedDeviceId != null && expectedDeviceId != saved?.id) {
                return BleKnownDeviceClearResult.Missing
            }
            saved = null
            return BleKnownDeviceClearResult.Cleared
        }
    }

    private var now = 0L
    private val gateway = Gateway()
    private val knownStore = KnownStore()
    private val transport = InateckSdkTransport(gateway, nowMillis = { now })
    private val connection = BleConnectionCoordinator(
        transport = transport,
        knownDeviceStore = knownStore,
        nowMillis = { now },
        discoveryTimeoutMillis = 6_000L,
        sustainedReconnectDelayMillis = 30_000L,
    )
    private val snapshotStore = InMemorySymbologySnapshotStore(INATECK_ANDROID_SDK_PROFILE_IDENTITY)
    private val scanner = SelectableBleExternalScanner(
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
                    nowMillis = { now },
                ),
            )
        },
    )
    private val gattSwitch = InateckGattSwitch(
        operations = object : InateckGattSwitch.Operations {
            override val isLinkActive get() = transport.isLinkActive
            override val isSearching get() = scanner.connectionState is ConnectionState.Searching
            override fun forgetKnownDevice(deviceId: String) = connection.forgetKnownDevice(deviceId)
            override fun startDiscovery() = scanner.startDiscovery()
            override fun connect(device: ScannerDevice) = scanner.connect(device)
            override fun requireManualSelection() {
                connection.publishFailure(ScannerFailureReasons.RESELECT_AFTER_GATT_SWITCH)
            }
            override fun diagnostic(message: String, error: Boolean) =
                connection.recordDiagnostic(message, error)
        },
        nowMillis = { now },
    )

    init {
        transport.onDeviceDiscovered = gattSwitch::onDeviceDiscovered
    }

    private fun tick(millis: Long = 250L) {
        now += millis
        scanner.tick(now)
        gattSwitch.tick()
    }

    /** The gateway switched the origin to GATT mode and failed its connect. */
    private fun connectOriginThatSwitchesToGatt() {
        assertTrue(scanner.connect(origin))
        assertEquals(listOf(origin.id), gateway.connectIds)
        assertEquals(origin, knownStore.saved)
        gattSwitch.begin(origin) // InateckGattModeEvent.SwitchStarted
        gateway.connectCompletion!!(Result.failure(IllegalStateException("restarting")))
        assertFalse(transport.isLinkActive)
    }

    @Test
    fun restartedScannerIsFollowedUnderItsNewIdentityAndTheOriginIsNeverRetried() {
        connectOriginThatSwitchesToGatt()
        tick()
        // The origin is no longer the known device, so no automatic reconnect.
        assertNull(knownStore.saved)

        tick(InateckGattSwitch.DEFAULT_REDISCOVERY_DELAY_MILLIS)
        assertEquals(1, gateway.discoveries)
        gateway.onDevice!!(InateckSdkDevice(unrelated.id, unrelated.name))
        gateway.onDevice!!(InateckSdkDevice(renamed.id, renamed.name))
        gateway.onFinished!!()
        tick()

        assertEquals(listOf(origin.id, renamed.id), gateway.connectIds)
        assertEquals(renamed, knownStore.saved)
        assertEquals(renamed, scanner.boundDevice)
        assertFalse(gattSwitch.isActive)
        // The successor's attempt never completes here, so its connect timeout
        // retries it; every retry targets the successor, never the origin.
        repeat(20) { tick(10_000L) }
        assertEquals(origin.id, gateway.connectIds.first())
        assertTrue(gateway.connectIds.drop(1).all { it == renamed.id })
    }

    @Test
    fun unresolvedSwitchAsksTheOperatorToPickTheScanner() {
        connectOriginThatSwitchesToGatt()
        tick()
        repeat(3) {
            tick(InateckGattSwitch.DEFAULT_REDISCOVERY_DELAY_MILLIS)
            gateway.onDevice!!(InateckSdkDevice(unrelated.id, unrelated.name))
            gateway.onFinished!!()
            tick()
        }
        assertEquals(3, gateway.discoveries)
        assertEquals(
            ScannerIssue.RESELECT_REQUIRED,
            scannerIssueFor(scanner.connectionState, scanner.configurationState),
        )
        assertNull(knownStore.saved)
        repeat(20) { tick(10_000L) }
        assertEquals(listOf(origin.id), gateway.connectIds)

        // The operator's choice connects normally.
        assertTrue(scanner.connect(renamed))
        assertEquals(listOf(origin.id, renamed.id), gateway.connectIds)
    }
}
