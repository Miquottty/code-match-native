package jp.rimtty.codematch.scanner.ble

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class BleSymbologySnapshotStoreTest {
    private val profileIdentity = "vendor:model:firmware:codec-v1"

    @Test
    fun dataStoreRoundTripsAndClearsOnlyTheMatchingDevice() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)
        val snapshot = sampleSnapshot()

        store.save(snapshot)

        assertEquals(snapshot, store.load(snapshot.deviceId))
        // #135: another device has no record of its own; this one's pending
        // snapshot neither blocks it nor is cleared by it.
        assertEquals(SymbologySnapshotReadResult.Missing, store.read("other-device"))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear("other-device"))
        val other = sampleSnapshot().copy(deviceId = "other-device")
        store.save(other)
        assertEquals(other, store.load("other-device"))
        assertEquals(snapshot, store.load(snapshot.deviceId))

        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(snapshot.deviceId))
        assertEquals(SymbologySnapshotReadResult.Missing, store.read(snapshot.deviceId))
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear(snapshot.deviceId))
        assertEquals(other, store.load("other-device"))
        // Preferences DataStore keeps its backing file even when a snapshot
        // key has been removed; the reads above verify its content.
        file.delete()
    }

    @Test
    fun corruptAndUnknownVersionValuesAreRejectedAndNeverReturned() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)
        val deviceId = sampleSnapshot().deviceId
        val key = stringPreferencesKey("completeSnapshot:$deviceId")

        runBlocking {
            dataStore.edit { preferences -> preferences[key] = "not-json" }
        }
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.CORRUPT,
            ),
            store.read(deviceId),
        )

        val encoded = BleSymbologySnapshotSerializer().encode(sampleSnapshot(), profileIdentity)
        runBlocking {
            dataStore.edit { preferences ->
                preferences[key] = encoded.replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":2",
                )
            }
        }
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.UNSUPPORTED_VERSION,
            ),
            store.read(deviceId),
        )

        file.delete()
    }

    @Test
    fun profileMismatchIsRejectedWithoutClearingTheStoredValue() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val firstStore = BleSymbologySnapshotStore(dataStore, profileIdentity)
        firstStore.save(sampleSnapshot())

        val otherProfileStore = BleSymbologySnapshotStore(dataStore, "other-profile")
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.read("scanner-1"),
        )
        assertEquals(
            SymbologySnapshotClearResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.clear("scanner-1"),
        )
        assertEquals(sampleSnapshot(), firstStore.load("scanner-1"))

        file.delete()
    }

    @Test
    fun storeDoesNotDependOnScannerTransportOrPersistScanData() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)

        store.save(sampleSnapshot())
        val serialized = runBlocking {
            dataStore.data.first()[stringPreferencesKey("completeSnapshot:scanner-1")]
        }
        // The store has one dedicated preference per scanner and its serializer schema;
        // scanner callbacks are not part of either boundary.
        assertTrue(serialized != null)
        assertFalse(serialized.orEmpty().contains("scanPayload"))
        assertFalse(serialized.orEmpty().contains("rawFrame"))
        assertTrue(BLE_SYMBOLOGY_DATASTORE_FILE_NAME.endsWith(".preferences_pb"))

        file.delete()
    }

    private fun temporaryFile(): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(context.cacheDir, "ble-symbology-${UUID.randomUUID()}.preferences_pb")
    }

    private fun sampleSnapshot(): SymbologySnapshot = SymbologySnapshot(
        deviceId = ScannerDevice("scanner-1", "BCST-47").id,
        settings = listOf(
            ScannerSettingItem(
                name = "qrcode_on",
                area = "qr-area",
                value = 1,
                flag = 2001,
                extraFields = mapOf("vendor" to "opaque"),
            ),
            ScannerSettingItem("code128_on", "code128-area", 0, flag = 2008),
            ScannerSettingItem("future_symbol", "future-area", 1),
        ),
        capturedAtMillis = 99L,
    )
}
