package jp.rimtty.codematch.scanner.ble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Preference key used for the last explicitly known scanner identity.
 *
 * This is kept in the existing BLE recovery DataStore rather than creating a
 * second file.  The app already excludes that file from cloud backup and
 * device-to-device transfer, so a scanner identity cannot be copied to a
 * different physical device by accident.
 */
const val BLE_KNOWN_DEVICE_PREFERENCE_NAME = "knownDevice"

/**
 * Preference key of the "operator disconnected" flag, kept next to the known
 * scanner identity: after the operator taps Disconnect no automatic reconnect
 * runs until the operator connects again, also across a process restart.
 */
const val BLE_OPERATOR_DISCONNECTED_PREFERENCE_NAME = "knownDeviceOperatorDisconnected"

/** Version of the small, identity-only known-device envelope. */
const val BLE_KNOWN_DEVICE_SCHEMA_VERSION = 1

/** Aliases make the storage boundary discoverable without introducing a file. */
const val BLE_KNOWN_DEVICE_DATASTORE_NAME = BLE_SYMBOLOGY_DATASTORE_NAME
const val BLE_KNOWN_DEVICE_DATASTORE_FILE_NAME = BLE_SYMBOLOGY_DATASTORE_FILE_NAME

/** Rejection messages contain no scanner payload, settings, or raw frame data. */
object BleKnownDeviceRejectionReason {
    const val CORRUPT = "Saved scanner identity is corrupt"
    const val UNSUPPORTED_VERSION = "Saved scanner identity uses an unsupported version"
    const val PROFILE_MISMATCH = "Saved scanner identity belongs to another BLE profile"
    const val DEVICE_MISMATCH = "Saved scanner identity belongs to another device"
    const val DATASTORE_READ_FAILED = "Saved scanner identity could not be read"
    const val DATASTORE_WRITE_FAILED = "Saved scanner identity could not be written"
    const val DATASTORE_CLEAR_FAILED = "Saved scanner identity could not be cleared"
}

/** Identity-aware result of reading the persisted known scanner. */
sealed interface BleKnownDeviceReadResult {
    data object Missing : BleKnownDeviceReadResult

    data class Found(val device: ScannerDevice) : BleKnownDeviceReadResult

    /** A value exists but must not be used to connect. */
    data class Rejected(val reason: String) : BleKnownDeviceReadResult
}

/** Result of atomically remembering a scanner identity. */
sealed interface BleKnownDeviceWriteResult {
    data object Saved : BleKnownDeviceWriteResult

    data class Rejected(val reason: String) : BleKnownDeviceWriteResult
}

/** Result of clearing a known scanner identity. */
sealed interface BleKnownDeviceClearResult {
    data object Cleared : BleKnownDeviceClearResult

    data object Missing : BleKnownDeviceClearResult

    data class Rejected(val reason: String) : BleKnownDeviceClearResult
}

/**
 * Persistence boundary consumed by [BleConnectionCoordinator].
 *
 * Keeping this interface free of Android Bluetooth types allows JVM tests and
 * future adapters to use a memory implementation while the production
 * implementation below remains an app-private Preferences DataStore.
 */
interface KnownDeviceStore {
    val profileIdentity: String

    fun read(): BleKnownDeviceReadResult

    fun load(): ScannerDevice? =
        (read() as? BleKnownDeviceReadResult.Found)?.device

    fun save(device: ScannerDevice): BleKnownDeviceWriteResult

    /** Clear only the stored identity, optionally requiring an exact ID. */
    fun clear(expectedDeviceId: String? = null): BleKnownDeviceClearResult

    /**
     * Whether the operator disconnected the scanner and has not connected one
     * since. The known identity itself is kept, so an explicit reconnect still
     * works; only automatic reconnects are held back. A store that does not
     * persist the flag reports false.
     */
    fun readOperatorDisconnected(): Boolean = false

    /** Persist the flag; false when it could not be written. */
    fun saveOperatorDisconnected(disconnected: Boolean): Boolean = true
}

/**
 * Versioned serializer for the known-device envelope.
 *
 * Only profile identity, device ID, and the last advertised display name are
 * persisted.  In particular, this class has no API for scan values, settings,
 * notification bytes, or GATT frames.
 */
class BleKnownDeviceSerializer {
    private val gson = com.google.gson.Gson()

    fun encode(device: ScannerDevice, profileIdentity: String): String {
        require(device.id.isNotBlank()) { "device.id must not be blank" }
        require(profileIdentity.isNotBlank()) { "profileIdentity must not be blank" }

        val root = com.google.gson.JsonObject().apply {
            addProperty("schemaVersion", BLE_KNOWN_DEVICE_SCHEMA_VERSION)
            addProperty("profileIdentity", profileIdentity)
            addProperty("deviceId", device.id)
            addProperty("deviceName", device.name)
        }
        return gson.toJson(root)
    }

    fun decodeResult(
        serialized: String,
        expectedProfileIdentity: String,
    ): BleKnownDeviceReadResult {
        if (expectedProfileIdentity.isBlank()) {
            return BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.CORRUPT,
            )
        }

        val root = runCatching {
            com.google.gson.JsonParser.parseString(serialized).asJsonObject
        }.getOrNull() ?: return rejectedCorrupt()

        val version = root.numberAsLong("schemaVersion")
            ?: return rejectedCorrupt()
        if (version != BLE_KNOWN_DEVICE_SCHEMA_VERSION.toLong()) {
            return BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.UNSUPPORTED_VERSION,
            )
        }

        val profileIdentity = root.stringValue("profileIdentity")
            ?.takeIf(String::isNotBlank)
            ?: return rejectedCorrupt()
        if (profileIdentity != expectedProfileIdentity) {
            return BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.PROFILE_MISMATCH,
            )
        }

        val deviceId = root.stringValue("deviceId")
            ?.takeIf(String::isNotBlank)
            ?: return rejectedCorrupt()
        val deviceName = root.stringValue("deviceName")
            ?: return rejectedCorrupt()

        return BleKnownDeviceReadResult.Found(
            ScannerDevice(id = deviceId, name = deviceName),
        )
    }

    fun decode(
        serialized: String,
        expectedProfileIdentity: String,
    ): ScannerDevice? =
        (decodeResult(serialized, expectedProfileIdentity) as?
            BleKnownDeviceReadResult.Found)?.device

    private fun com.google.gson.JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private fun com.google.gson.JsonObject.numberAsLong(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asJsonPrimitive.asString.toLongOrNull()
    }

    private fun rejectedCorrupt(): BleKnownDeviceReadResult.Rejected =
        BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.CORRUPT)
}

/**
 * App-private atomic DataStore for a manually retained scanner identity.
 *
 * The synchronous methods match the existing BLE protocol state machine. I/O
 * is isolated to [ioDispatcher], and all malformed or mismatched values are
 * returned as rejections rather than being treated as a reconnect candidate.
 */
class BleKnownDeviceStore(
    private val dataStore: DataStore<Preferences>,
    override val profileIdentity: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serializer: BleKnownDeviceSerializer = BleKnownDeviceSerializer(),
) : KnownDeviceStore {
    private val knownDeviceKey =
        stringPreferencesKey(BLE_KNOWN_DEVICE_PREFERENCE_NAME)
    private val operatorDisconnectedKey =
        booleanPreferencesKey(BLE_OPERATOR_DISCONNECTED_PREFERENCE_NAME)

    init {
        require(profileIdentity.isNotBlank()) { "profileIdentity must not be blank" }
    }

    constructor(
        context: Context,
        profileIdentity: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        serializer: BleKnownDeviceSerializer = BleKnownDeviceSerializer(),
    ) : this(
        dataStore = context.codeMatchBleSymbologyDataStore,
        profileIdentity = profileIdentity,
        ioDispatcher = ioDispatcher,
        serializer = serializer,
    )

    override fun read(): BleKnownDeviceReadResult {
        val serialized = try {
            runBlocking(ioDispatcher) {
                dataStore.data.first()[knownDeviceKey]
            }
        } catch (_: IOException) {
            return BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_READ_FAILED,
            )
        } catch (_: Exception) {
            return BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_READ_FAILED,
            )
        }

        serialized ?: return BleKnownDeviceReadResult.Missing
        return serializer.decodeResult(serialized, profileIdentity)
    }

    override fun save(device: ScannerDevice): BleKnownDeviceWriteResult {
        val encoded = runCatching { serializer.encode(device, profileIdentity) }
            .getOrElse {
                return BleKnownDeviceWriteResult.Rejected(
                    BleKnownDeviceRejectionReason.CORRUPT,
                )
            }
        return try {
            runBlocking(ioDispatcher) {
                dataStore.edit { preferences ->
                    preferences[knownDeviceKey] = encoded
                }
            }
            BleKnownDeviceWriteResult.Saved
        } catch (_: Exception) {
            BleKnownDeviceWriteResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_WRITE_FAILED,
            )
        }
    }

    override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult {
        if (expectedDeviceId?.isBlank() == true) {
            return BleKnownDeviceClearResult.Rejected(
                BleKnownDeviceRejectionReason.CORRUPT,
            )
        }
        return try {
            runBlocking(ioDispatcher) {
                var result: BleKnownDeviceClearResult = BleKnownDeviceClearResult.Missing
                val updated = dataStore.edit { preferences ->
                    val serialized = preferences[knownDeviceKey] ?: return@edit
                    when (val decoded = serializer.decodeResult(serialized, profileIdentity)) {
                        is BleKnownDeviceReadResult.Found -> {
                            if (expectedDeviceId == null || decoded.device.id == expectedDeviceId) {
                                preferences.remove(knownDeviceKey)
                                result = BleKnownDeviceClearResult.Cleared
                            } else {
                                result = BleKnownDeviceClearResult.Rejected(
                                    BleKnownDeviceRejectionReason.DEVICE_MISMATCH,
                                )
                            }
                        }
                        BleKnownDeviceReadResult.Missing -> Unit
                        is BleKnownDeviceReadResult.Rejected -> {
                            result = BleKnownDeviceClearResult.Rejected(decoded.reason)
                        }
                    }
                }
                if (result == BleKnownDeviceClearResult.Cleared && updated[knownDeviceKey] != null) {
                    BleKnownDeviceClearResult.Rejected(
                        BleKnownDeviceRejectionReason.DATASTORE_CLEAR_FAILED,
                    )
                } else {
                    result
                }
            }
        } catch (_: Exception) {
            BleKnownDeviceClearResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_CLEAR_FAILED,
            )
        }
    }

    override fun readOperatorDisconnected(): Boolean = try {
        runBlocking(ioDispatcher) {
            dataStore.data.first()[operatorDisconnectedKey] == true
        }
    } catch (_: Exception) {
        // An unreadable flag must not strand the scanner: keep reconnecting.
        false
    }

    override fun saveOperatorDisconnected(disconnected: Boolean): Boolean = try {
        runBlocking(ioDispatcher) {
            dataStore.edit { preferences ->
                if (disconnected) {
                    preferences[operatorDisconnectedKey] = true
                } else {
                    preferences.remove(operatorDisconnectedKey)
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
