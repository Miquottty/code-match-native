package jp.rimtty.codematch.scanner.ble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import jp.rimtty.codematch.scanner.api.ScannerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BleKnownDeviceStoreTest {
    private val profileIdentity = "observed-adapter-profile-v1"
    private val device = ScannerDevice("scanner-1", "BCST-47")

    @Test
    fun knownDeviceSurvivesDataStoreReopenAndWrongDeviceCannotClearIt() = runBlocking {
        val file = temporaryFile()
        try {
            withStore(file) { store, _ ->
                assertEquals(BleKnownDeviceWriteResult.Saved, store.save(device))
                assertEquals(BleKnownDeviceReadResult.Found(device), store.read())
            }

            withStore(file) { reopened, _ ->
                assertEquals(BleKnownDeviceReadResult.Found(device), reopened.read())
                assertEquals(
                    BleKnownDeviceClearResult.Rejected(
                        BleKnownDeviceRejectionReason.DEVICE_MISMATCH,
                    ),
                    reopened.clear("another-device"),
                )
                assertEquals(BleKnownDeviceReadResult.Found(device), reopened.read())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun profileMismatchAndCorruptionAreRejectedWithoutReturningAnIdentity() = runBlocking {
        val file = temporaryFile()
        try {
            withStore(file) { store, dataStore ->
                assertEquals(BleKnownDeviceWriteResult.Saved, store.save(device))
                val otherProfile = BleKnownDeviceStore(dataStore, "other-profile")
                assertEquals(
                    BleKnownDeviceReadResult.Rejected(
                        BleKnownDeviceRejectionReason.PROFILE_MISMATCH,
                    ),
                    otherProfile.read(),
                )
            }

            withStore(file) { store, dataStore ->
                dataStore.edit { preferences ->
                    preferences[stringPreferencesKey(BLE_KNOWN_DEVICE_PREFERENCE_NAME)] =
                        "not-json"
                }
                assertEquals(
                    BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.CORRUPT),
                    store.read(),
                )
                assertTrue(store.load() == null)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun knownDeviceEnvelopeContainsNoSettingsOrScanDataAndUsesExcludedBleFile() = runBlocking {
        val file = temporaryFile()
        try {
            withStore(file) { store, dataStore ->
                store.save(device)
                val serialized = dataStore.data.first()[
                    stringPreferencesKey(BLE_KNOWN_DEVICE_PREFERENCE_NAME)
                ].orEmpty()
                assertFalse(serialized.contains("settings"))
                assertFalse(serialized.contains("scanPayload"))
                assertFalse(serialized.contains("rawFrame"))
                assertEquals(BLE_SYMBOLOGY_DATASTORE_FILE_NAME, BLE_KNOWN_DEVICE_DATASTORE_FILE_NAME)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun recreatedServiceReconnectsKnownDeviceAndRestoresSnapshotBeforeReady() = runBlocking {
        val file = temporaryFile()
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(Dispatchers.IO + firstJob)
        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(Dispatchers.IO + secondJob)
        try {
            val firstDataStore = dataStore(firstScope, file)
            val firstKnownStore = BleKnownDeviceStore(firstDataStore, profileIdentity)
            val firstSnapshotStore = BleSymbologySnapshotStore(firstDataStore, profileIdentity)
            val firstTransport = PendingTransport()
            val firstConnection = BleConnectionCoordinator(
                transport = firstTransport,
                knownDeviceStore = firstKnownStore,
            )
            val firstSession = BleSymbologySession(
                device = device,
                transport = firstTransport,
                profile = profile(),
                snapshotStore = firstSnapshotStore,
            )
            val firstBridge = BleScannerSessionCoordinator(firstConnection, firstSession)

            assertTrue(firstBridge.connect(device))
            firstTransport.emit(BleTransportEvent.Connected(device))
            firstTransport.completeRead(settingsJson())
            assertEquals(BleSymbologySessionState.Ready, firstSession.state)
            assertTrue(firstBridge.startSession(ScanFormat.QR))
            assertTrue(
                firstSnapshotStore.read(device.id) is SymbologySnapshotReadResult.Found,
            )
            // Leave the restriction command unfinished to model a process
            // restart while the scanner is restricted. The saved baseline
            // must survive and be restored by the next service instance.
            assertEquals(1, firstTransport.pendingWrites)
            firstBridge.close()
            firstJob.cancelAndJoin()

            val secondDataStore = dataStore(secondScope, file)
            val secondKnownStore = BleKnownDeviceStore(secondDataStore, profileIdentity)
            val secondSnapshotStore = BleSymbologySnapshotStore(secondDataStore, profileIdentity)
            val secondTransport = PendingTransport()
            val secondConnection = BleConnectionCoordinator(
                transport = secondTransport,
                knownDeviceStore = secondKnownStore,
            )
            val secondSession = BleSymbologySession(
                device = device,
                transport = secondTransport,
                profile = profile(),
                snapshotStore = secondSnapshotStore,
            )
            val secondBridge = BleScannerSessionCoordinator(secondConnection, secondSession)

            assertEquals(device, secondConnection.knownDevice)
            assertTrue(secondBridge.reconnectKnownDevice())
            secondTransport.emit(BleTransportEvent.Connected(device))
            assertEquals(BleSymbologySessionState.LoadingSettings, secondSession.state)
            assertEquals(ConfigurationState.Configuring, secondSession.configurationState)
            assertFalse(secondBridge.state.isReadyForScanning)

            secondTransport.completeRead(settingsJson())
            assertEquals(BleSymbologySessionState.Restoring, secondSession.state)
            assertEquals(1, secondTransport.pendingWrites)
            assertFalse(secondBridge.state.isReadyForScanning)
            assertTrue(
                secondSnapshotStore.read(device.id) is SymbologySnapshotReadResult.Found,
            )

            secondTransport.completeWrite(Result.success(Unit))
            assertEquals(BleSymbologySessionState.Ready, secondSession.state)
            assertEquals(ConfigurationState.Ready, secondSession.configurationState)
            assertEquals(SymbologySnapshotReadResult.Missing, secondSnapshotStore.read(device.id))
            assertEquals(BleKnownDeviceReadResult.Found(device), secondKnownStore.read())
            assertFalse(secondBridge.state.isReadyForScanning)
            secondBridge.close()
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
            file.delete()
        }
    }

    private suspend fun withStore(
        file: File,
        block: suspend (BleKnownDeviceStore, DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val dataStore = dataStore(scope, file)
            block(BleKnownDeviceStore(dataStore, profileIdentity), dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun dataStore(
        scope: CoroutineScope,
        file: File,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file },
    )

    private fun profile(): BleSymbologyProfile = BleSymbologyProfile(
        settingsCharacteristicUuid = "settings-endpoint-from-adapter",
        codec = IosObservedSymbologyCodec,
        identity = profileIdentity,
    )

    private fun settingsJson(): String = """
        {"data":[
          {"area":"qr-area","value":"1","name":"qrcode_on"},
          {"area":"code128-area","value":"0","name":"code128_on"},
          {"area":"other-area","value":"1","name":"code39_on"}
        ]}
    """.trimIndent()

    private class PendingTransport : BleTransport {
        override var availability: BleAvailability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        private val readCallbacks = ArrayDeque<(Result<ByteArray>) -> Unit>()
        private val writeCallbacks = ArrayDeque<(Result<Unit>) -> Unit>()

        val pendingWrites: Int get() = writeCallbacks.size

        override fun startDiscovery(): Boolean = true

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean = true

        override fun disconnect(device: ScannerDevice): Boolean = true

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
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

        fun completeRead(settings: String) {
            readCallbacks.removeFirst()(Result.success(settings.toByteArray(Charsets.UTF_8)))
        }

        fun completeWrite(result: Result<Unit>) {
            writeCallbacks.removeFirst()(result)
        }

        fun emit(event: BleTransportEvent) {
            listener?.onTransportEvent(event)
        }
    }

    private fun temporaryFile(): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(context.cacheDir, "ble-known-${UUID.randomUUID()}.preferences_pb")
    }
}
