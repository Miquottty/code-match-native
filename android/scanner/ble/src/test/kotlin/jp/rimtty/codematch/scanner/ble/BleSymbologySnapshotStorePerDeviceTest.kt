package jp.rimtty.codematch.scanner.ble

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage of the real DataStore adapter: one recovery record per scanner,
 * and the pre-#135 single-slot value honoured only for the device it names.
 */
class BleSymbologySnapshotStorePerDeviceTest {
    private val profileIdentity = "vendor:model:firmware:codec-v1"
    private val serializer = BleSymbologySnapshotSerializer()
    private val legacyKey = stringPreferencesKey("completeSnapshot")
    private val scannerA = "AA:AA:AA:AA:AA:01"
    private val scannerB = "BB:BB:BB:BB:BB:02"

    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: BleSymbologySnapshotStore

    @Before
    fun setUp() {
        directory = kotlin.io.path.createTempDirectory("ble-symbology").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, BLE_SYMBOLOGY_DATASTORE_FILE_NAME) },
        )
        store = BleSymbologySnapshotStore(dataStore, profileIdentity)
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun eachScannerKeepsItsOwnSnapshotAndClearTouchesOnlyItsOwn() {
        val snapshotA = snapshot(scannerA, capturedAtMillis = 1L)
        val snapshotB = snapshot(scannerB, capturedAtMillis = 2L)

        store.save(snapshotA)
        store.save(snapshotB)

        assertEquals(SymbologySnapshotReadResult.Found(snapshotA), store.read(scannerA))
        assertEquals(SymbologySnapshotReadResult.Found(snapshotB), store.read(scannerB))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read("CC:CC:CC:CC:CC:03"))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear("CC:CC:CC:CC:CC:03"))

        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(scannerA))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerA))
        assertEquals(SymbologySnapshotReadResult.Found(snapshotB), store.read(scannerB))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear(scannerA))

        // Saving again replaces only that scanner's record.
        val newerB = snapshot(scannerB, capturedAtMillis = 3L)
        store.save(newerB)
        assertEquals(newerB, store.load(scannerB))
        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(scannerB))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerB))
    }

    @Test
    fun legacySingleSlotValueBelongsOnlyToTheDeviceItNames() {
        val legacyA = snapshot(scannerA, capturedAtMillis = 7L)
        writeLegacy(serializer.encode(legacyA, profileIdentity))

        assertEquals(SymbologySnapshotReadResult.Found(legacyA), store.read(scannerA))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerB))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear(scannerB))

        // Another scanner's session neither blocks on nor overwrites it.
        val snapshotB = snapshot(scannerB, capturedAtMillis = 8L)
        store.save(snapshotB)
        assertEquals(snapshotB, store.load(scannerB))
        assertEquals(legacyA, store.load(scannerA))

        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(scannerA))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerA))
        assertNull(readLegacy())
        assertEquals(snapshotB, store.load(scannerB))
    }

    @Test
    fun freshBaselineSupersedesTheSameDevicesLegacyValue() {
        writeLegacy(serializer.encode(snapshot(scannerA, capturedAtMillis = 1L), profileIdentity))

        val fresh = snapshot(scannerA, capturedAtMillis = 2L)
        store.save(fresh)

        assertEquals(fresh, store.load(scannerA))
        assertNull(readLegacy())
        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(scannerA))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerA))
    }

    @Test
    fun unreadableRecordIsRejectedOnlyForItsOwnDevice() {
        writeDeviceRecord(scannerA, "not-json")
        store.save(snapshot(scannerB))

        assertEquals(
            SymbologySnapshotReadResult.Rejected(BleSymbologySnapshotRejectionReason.CORRUPT),
            store.read(scannerA),
        )
        assertEquals(
            SymbologySnapshotClearResult.Rejected(BleSymbologySnapshotRejectionReason.CORRUPT),
            store.clear(scannerA),
        )
        assertEquals(snapshot(scannerB), store.load(scannerB))

        // A legacy value that names A but uses an unknown schema still fails A only.
        writeDeviceRecord(scannerA, null)
        writeLegacy(
            serializer.encode(snapshot(scannerA), profileIdentity)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
        )
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.UNSUPPORTED_VERSION,
            ),
            store.read(scannerA),
        )
        assertEquals(snapshot(scannerB), store.load(scannerB))
    }

    @Test
    fun legacyValueNamingNoDeviceBlocksNobodyAndIsLeftInPlace() {
        writeLegacy("not-json")

        assertEquals(SymbologySnapshotReadResult.Missing, store.read(scannerA))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear(scannerA))
        store.save(snapshot(scannerA))
        assertEquals(snapshot(scannerA), store.load(scannerA))
        assertEquals("not-json", readLegacy())
    }

    @Test
    fun profileMismatchIsRejectedWithoutClearingTheStoredValue() {
        store.save(snapshot(scannerA))
        val otherProfileStore = BleSymbologySnapshotStore(dataStore, "other-profile")

        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.read(scannerA),
        )
        assertEquals(
            SymbologySnapshotClearResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.clear(scannerA),
        )
        assertEquals(SymbologySnapshotReadResult.Missing, otherProfileStore.read(scannerB))
        assertEquals(snapshot(scannerA), store.load(scannerA))
    }

    private fun writeLegacy(value: String) {
        runBlocking { dataStore.edit { it[legacyKey] = value } }
    }

    private fun readLegacy(): String? = runBlocking { dataStore.data.first()[legacyKey] }

    private fun writeDeviceRecord(deviceId: String, value: String?) {
        val key = stringPreferencesKey("completeSnapshot:$deviceId")
        runBlocking {
            dataStore.edit { preferences ->
                if (value == null) preferences.remove(key) else preferences[key] = value
            }
        }
    }

    private fun snapshot(deviceId: String, capturedAtMillis: Long = 99L) = SymbologySnapshot(
        deviceId = deviceId,
        settings = listOf(
            ScannerSettingItem(
                name = "qrcode_on",
                area = "qr-area",
                value = 1,
                flag = 2022,
                extraFields = mapOf("vendor" to "opaque"),
            ),
            ScannerSettingItem("code128_on", "code128-area", 0, flag = 2008),
            ScannerSettingItem("future_symbol", "future-area", 1),
        ),
        capturedAtMillis = capturedAtMillis,
    )
}
