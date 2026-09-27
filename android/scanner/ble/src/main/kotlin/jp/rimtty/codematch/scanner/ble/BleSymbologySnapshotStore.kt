package jp.rimtty.codematch.scanner.ble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Preferences DataStore name used for the per-scanner recovery snapshots.
 *
 * The resulting private file is
 * `files/datastore/codematch-ble-symbology.preferences_pb`. Keep this name in
 * the app's backup and device-transfer exclusion rules; scanner configuration
 * is local recovery state, not user data to copy to another device.
 */
const val BLE_SYMBOLOGY_DATASTORE_NAME = "codematch-ble-symbology"
const val BLE_SYMBOLOGY_DATASTORE_FILE_NAME =
    "$BLE_SYMBOLOGY_DATASTORE_NAME.preferences_pb"

/**
 * Single-slot key written by builds before #135. It is still read, but only as
 * the record of the device named inside the value; new writes never use it.
 */
private const val LEGACY_SNAPSHOT_PREFERENCE_NAME = "completeSnapshot"

/** One record per scanner: this prefix followed by the device id (its MAC). */
private const val DEVICE_SNAPSHOT_PREFERENCE_PREFIX = "completeSnapshot:"

/** Process-wide app-private DataStore used by [BleSymbologySnapshotStore]. */
val Context.codeMatchBleSymbologyDataStore: DataStore<Preferences> by
    preferencesDataStore(name = BLE_SYMBOLOGY_DATASTORE_NAME)

/** The only schema currently accepted by [BleSymbologySnapshotSerializer]. */
const val BLE_SYMBOLOGY_SNAPSHOT_SCHEMA_VERSION = 1

/** Rejection reasons are deliberately free of device data and scanner payloads. */
object BleSymbologySnapshotRejectionReason {
    const val CORRUPT = "Saved scanner settings are corrupt"
    const val UNSUPPORTED_VERSION = "Saved scanner settings use an unsupported version"
    const val DEVICE_MISMATCH = "Saved scanner settings belong to another device"
    const val PROFILE_MISMATCH = "Saved scanner settings belong to another BLE profile"
    const val DATASTORE_READ_FAILED = "Saved scanner settings could not be read"
}

/** Result of strict, identity-aware decoding of persisted scanner settings. */
sealed interface BleSymbologySnapshotDecodeResult {
    data class Accepted(val snapshot: SymbologySnapshot) : BleSymbologySnapshotDecodeResult

    data class Rejected(val reason: String) : BleSymbologySnapshotDecodeResult
}

/**
 * Versioned serializer for the complete recovery inventory.
 *
 * It intentionally stores extra fields as their existing stringified JSON
 * representation. [ScannerSettingItem.extraFields] already uses that form;
 * keeping it as a string means values such as numbers, booleans, objects, and
 * strings round-trip byte-for-byte instead of being coerced by Preferences.
 */
class BleSymbologySnapshotSerializer {
    private val gson = com.google.gson.Gson()

    /** Encode one complete inventory with its adapter-selected profile identity. */
    fun encode(snapshot: SymbologySnapshot, profileIdentity: String): String {
        require(profileIdentity.isNotBlank()) { "profileIdentity must not be blank" }

        val root = com.google.gson.JsonObject().apply {
            addProperty("schemaVersion", BLE_SYMBOLOGY_SNAPSHOT_SCHEMA_VERSION)
            addProperty("profileIdentity", profileIdentity)
            addProperty("deviceId", snapshot.deviceId)
            addProperty("capturedAtMillis", snapshot.capturedAtMillis)
            add("settings", com.google.gson.JsonArray().apply {
                snapshot.settings.forEach { item ->
                    add(com.google.gson.JsonObject().apply {
                        addProperty("name", item.name)
                        addProperty("area", item.area)
                        addProperty("value", item.value)
                        item.flag?.let { addProperty("flag", it) }
                        add("extras", com.google.gson.JsonObject().apply {
                            item.extraFields.forEach { (key, value) ->
                                addProperty(key, value)
                            }
                        })
                    })
                }
            })
        }
        return gson.toJson(root)
    }

    /**
     * Decode and validate one stored value.
     *
     * A null expected identity skips that check. The DataStore adapter always
     * passes both the device and the profile identity before returning a value.
     */
    fun decodeResult(
        serialized: String,
        expectedDeviceId: String? = null,
        expectedProfileIdentity: String? = null,
    ): BleSymbologySnapshotDecodeResult {
        if (expectedDeviceId?.isBlank() == true || expectedProfileIdentity?.isBlank() == true) {
            return BleSymbologySnapshotDecodeResult.Rejected(
                BleSymbologySnapshotRejectionReason.CORRUPT,
            )
        }

        val root = runCatching {
            com.google.gson.JsonParser.parseString(serialized).asJsonObject
        }.getOrNull() ?: return rejectedCorrupt()

        val version = root.numberAsLong("schemaVersion")
            ?: return rejectedCorrupt()
        if (version != BLE_SYMBOLOGY_SNAPSHOT_SCHEMA_VERSION.toLong()) {
            return BleSymbologySnapshotDecodeResult.Rejected(
                BleSymbologySnapshotRejectionReason.UNSUPPORTED_VERSION,
            )
        }

        val profileIdentity = root.stringValue("profileIdentity")
            ?.takeIf(String::isNotBlank)
            ?: return rejectedCorrupt()
        if (expectedProfileIdentity != null && profileIdentity != expectedProfileIdentity) {
            return BleSymbologySnapshotDecodeResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            )
        }

        val deviceId = root.stringValue("deviceId")
            ?.takeIf(String::isNotBlank)
            ?: return rejectedCorrupt()
        if (expectedDeviceId != null && deviceId != expectedDeviceId) {
            return BleSymbologySnapshotDecodeResult.Rejected(
                BleSymbologySnapshotRejectionReason.DEVICE_MISMATCH,
            )
        }

        val capturedAtMillis = root.numberAsLong("capturedAtMillis")
            ?: return rejectedCorrupt()
        val settingsElement = root["settings"]
        if (settingsElement == null || !settingsElement.isJsonArray) return rejectedCorrupt()
        val settings = settingsElement.asJsonArray.map { parseItem(it) }
        if (settings.any { it == null }) return rejectedCorrupt()

        return runCatching {
            BleSymbologySnapshotDecodeResult.Accepted(
                SymbologySnapshot(
                    deviceId = deviceId,
                    settings = settings.filterNotNull(),
                    capturedAtMillis = capturedAtMillis,
                ),
            )
        }.getOrElse { rejectedCorrupt() }
    }

    /** Nullable compatibility helper for callers that only need a safe value. */
    fun decode(
        serialized: String,
        expectedDeviceId: String? = null,
        expectedProfileIdentity: String? = null,
    ): SymbologySnapshot? = when (
        val result = decodeResult(serialized, expectedDeviceId, expectedProfileIdentity)
    ) {
        is BleSymbologySnapshotDecodeResult.Accepted -> result.snapshot
        is BleSymbologySnapshotDecodeResult.Rejected -> null
    }

    /**
     * The device a stored value names, read leniently so that a value whose
     * other fields are corrupt or of another version or profile can still be
     * attributed to its scanner. Null when no device id can be read at all.
     */
    fun ownerDeviceId(serialized: String): String? {
        val root = runCatching {
            com.google.gson.JsonParser.parseString(serialized).asJsonObject
        }.getOrNull() ?: return null
        return root.stringValue("deviceId")?.takeIf(String::isNotBlank)
    }

    private fun parseItem(element: com.google.gson.JsonElement): ScannerSettingItem? {
        if (!element.isJsonObject) return null
        val jsonObject = element.asJsonObject
        val name = jsonObject.stringValue("name")?.takeIf(String::isNotBlank) ?: return null
        val area = jsonObject.stringValue("area")?.takeIf(String::isNotBlank) ?: return null
        val value = jsonObject.numberAsInt("value") ?: return null
        if (value !in 0..1) return null

        val flagElement = jsonObject["flag"]
        val flag = when {
            flagElement == null -> null
            else -> jsonObject.numberAsInt("flag") ?: return null
        }

        val extrasElement = jsonObject["extras"]
        val extras = when {
            extrasElement == null -> emptyMap()
            !extrasElement.isJsonObject -> return null
            else -> {
                val parsedExtras = linkedMapOf<String, String>()
                extrasElement.asJsonObject.entrySet().forEach { (key, value) ->
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
                    parsedExtras[key] = value.asString
                }
                parsedExtras
            }
        }
        return runCatching {
            ScannerSettingItem(
                name = name,
                area = area,
                value = value,
                flag = flag,
                extraFields = extras,
            )
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private fun com.google.gson.JsonObject.numberAsLong(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asJsonPrimitive.asString.toLongOrNull()
    }

    private fun com.google.gson.JsonObject.numberAsInt(name: String): Int? =
        numberAsLong(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun rejectedCorrupt(): BleSymbologySnapshotDecodeResult.Rejected =
        BleSymbologySnapshotDecodeResult.Rejected(
            BleSymbologySnapshotRejectionReason.CORRUPT,
        )
}

/**
 * App-private atomic persistence for the recovery inventories, one per scanner.
 *
 * Each physical scanner (its [SymbologySnapshot.deviceId]) owns an independent
 * record, so a restore still pending for one scanner never blocks or is
 * applied to another scanner, and saving or clearing one scanner's record
 * leaves every other record untouched. A value written by earlier builds under
 * the single legacy key is honoured as the record of the device it names: it
 * is found and cleared only through that device, is superseded when that
 * device saves a fresh baseline, and is invisible to every other device. A
 * legacy value that does not name a device cannot be attributed and is left
 * in place without blocking anyone.
 *
 * The public store contract remains synchronous because [BleSymbologySession]
 * is a synchronous protocol state machine. DataStore's atomic [edit] is
 * confined to an IO dispatcher here; no scan callback, raw frame, or payload
 * is ever accepted by this class.
 */
class BleSymbologySnapshotStore(
    private val dataStore: DataStore<Preferences>,
    override val profileIdentity: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serializer: BleSymbologySnapshotSerializer = BleSymbologySnapshotSerializer(),
) : SymbologySnapshotStore {
    private val legacySnapshotKey = stringPreferencesKey(LEGACY_SNAPSHOT_PREFERENCE_NAME)

    init {
        require(profileIdentity.isNotBlank()) { "profileIdentity must not be blank" }
    }

    constructor(
        context: Context,
        profileIdentity: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        serializer: BleSymbologySnapshotSerializer = BleSymbologySnapshotSerializer(),
    ) : this(
        dataStore = context.codeMatchBleSymbologyDataStore,
        profileIdentity = profileIdentity,
        ioDispatcher = ioDispatcher,
        serializer = serializer,
    )

    override fun load(deviceId: String): SymbologySnapshot? =
        (read(deviceId) as? SymbologySnapshotReadResult.Found)?.snapshot

    /** Read only [deviceId]'s own record; another scanner's record is never consulted. */
    override fun read(deviceId: String): SymbologySnapshotReadResult {
        if (deviceId.isBlank()) {
            return SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.CORRUPT,
            )
        }

        val preferences = try {
            runBlocking(ioDispatcher) { dataStore.data.first() }
        } catch (_: IOException) {
            return SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.DATASTORE_READ_FAILED,
            )
        } catch (_: Exception) {
            return SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.DATASTORE_READ_FAILED,
            )
        }

        val serialized = recordFor(preferences, deviceId)
            ?: return SymbologySnapshotReadResult.Missing
        return when (
            val decoded = serializer.decodeResult(
                serialized = serialized,
                expectedDeviceId = deviceId,
                expectedProfileIdentity = profileIdentity,
            )
        ) {
            is BleSymbologySnapshotDecodeResult.Accepted ->
                SymbologySnapshotReadResult.Found(decoded.snapshot)

            is BleSymbologySnapshotDecodeResult.Rejected ->
                SymbologySnapshotReadResult.Rejected(decoded.reason)
        }
    }

    /** Replace [snapshot]'s device record; other scanners' records are kept. */
    override fun save(snapshot: SymbologySnapshot) {
        val encoded = serializer.encode(snapshot, profileIdentity)
        try {
            runBlocking(ioDispatcher) {
                dataStore.edit { preferences ->
                    preferences[deviceSnapshotKey(snapshot.deviceId)] = encoded
                    // The fresh baseline supersedes this device's legacy value,
                    // exactly as the old single-slot save overwrote it.
                    if (legacyOwner(preferences) == snapshot.deviceId) {
                        preferences.remove(legacySnapshotKey)
                    }
                }
            }
        } catch (error: Exception) {
            throw BleSymbologySnapshotStoreException(
                "Saved scanner settings could not be written",
                error,
            )
        }
    }

    /** Clear only [deviceId]'s own valid record; other scanners' records are kept. */
    override fun clear(deviceId: String): SymbologySnapshotClearResult {
        if (deviceId.isBlank()) {
            return SymbologySnapshotClearResult.Rejected(
                BleSymbologySnapshotRejectionReason.CORRUPT,
            )
        }
        val deviceKey = deviceSnapshotKey(deviceId)
        return try {
            runBlocking(ioDispatcher) {
                var result: SymbologySnapshotClearResult = SymbologySnapshotClearResult.Missing
                val updated = dataStore.edit { preferences ->
                    val serialized = recordFor(preferences, deviceId) ?: return@edit
                    when (
                        val decoded = serializer.decodeResult(
                            serialized = serialized,
                            expectedDeviceId = deviceId,
                            expectedProfileIdentity = profileIdentity,
                        )
                    ) {
                        is BleSymbologySnapshotDecodeResult.Accepted -> {
                            preferences.remove(deviceKey)
                            if (legacyOwner(preferences) == deviceId) {
                                preferences.remove(legacySnapshotKey)
                            }
                            result = SymbologySnapshotClearResult.Cleared
                        }
                        is BleSymbologySnapshotDecodeResult.Rejected -> {
                            result = SymbologySnapshotClearResult.Rejected(decoded.reason)
                        }
                    }
                }
                if (result == SymbologySnapshotClearResult.Cleared &&
                    recordFor(updated, deviceId) != null
                ) {
                    SymbologySnapshotClearResult.Rejected(
                        "Saved scanner settings could not be cleared",
                    )
                } else {
                    result
                }
            }
        } catch (_: Exception) {
            SymbologySnapshotClearResult.Rejected(
                "Saved scanner settings could not be cleared",
            )
        }
    }

    /**
     * The record owned by [deviceId]: its own key first, then a legacy
     * single-slot value that names this device. Never another device's.
     */
    private fun recordFor(preferences: Preferences, deviceId: String): String? =
        preferences[deviceSnapshotKey(deviceId)]
            ?: preferences[legacySnapshotKey]?.takeIf {
                serializer.ownerDeviceId(it) == deviceId
            }

    private fun legacyOwner(preferences: Preferences): String? =
        preferences[legacySnapshotKey]?.let(serializer::ownerDeviceId)

    private fun deviceSnapshotKey(deviceId: String): Preferences.Key<String> =
        stringPreferencesKey(DEVICE_SNAPSHOT_PREFERENCE_PREFIX + deviceId)
}

class BleSymbologySnapshotStoreException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
