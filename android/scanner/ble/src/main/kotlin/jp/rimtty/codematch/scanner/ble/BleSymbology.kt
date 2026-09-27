package jp.rimtty.codematch.scanner.ble

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.api.ScanFormat

/** One scanner-reported on/off symbology setting, including its device area. */
data class ScannerSettingItem(
    val name: String,
    val area: String,
    val value: Int,
    val flag: Int? = null,
    /** Stringified non-essential fields are retained for forward compatibility. */
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(area.isNotBlank()) { "area must not be blank" }
        require(value == 0 || value == 1) { "symbology value must be 0 or 1" }
    }
}

/** Complete pre-session symbology inventory for one physical scanner. */
data class SymbologySnapshot(
    val deviceId: String,
    val settings: List<ScannerSettingItem>,
    val capturedAtMillis: Long = 0L,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(settings.isNotEmpty()) { "settings must not be empty" }
    }

    /** Compatibility view for callers that only need the reported values. */
    val values: Map<String, Int> get() = settings.associate { it.name to it.value }

    val deviceID: String get() = deviceId

    fun hasRequiredSessionSymbols(): Boolean =
        settings.any(::isQrSymbol) && settings.any(::isCode128Symbol)

    fun find(name: String): ScannerSettingItem? = settings.firstOrNull {
        it.name.equals(name, ignoreCase = true)
    }

    /** Build a command inventory with every reported type preserved. */
    fun forMode(mode: BleSymbologyMode): List<ScannerSettingItem>? {
        if (!hasRequiredSessionSymbols()) return null
        return when (mode) {
            BleSymbologyMode.UNRESTRICTED -> settings
            BleSymbologyMode.SESSION_CODES -> settings.map { item ->
                item.copy(value = if (isSessionSymbol(item)) 1 else 0)
            }
            BleSymbologyMode.QR_ONLY -> settings.map { item ->
                item.copy(value = if (isQrSymbol(item)) 1 else 0)
            }
            BleSymbologyMode.CODE_128_ONLY -> settings.map { item ->
                item.copy(value = if (isCode128Symbol(item)) 1 else 0)
            }
        }
    }

    private companion object {
        const val QR_NAME = "qrcode_on"
        const val CODE_128_NAME = "code128_on"
        const val QR_FLAG = 2022
        const val CODE_128_FLAG = 2008

        fun isQrSymbol(item: ScannerSettingItem): Boolean =
            item.flag == QR_FLAG || item.name.equals(QR_NAME, ignoreCase = true)

        fun isCode128Symbol(item: ScannerSettingItem): Boolean =
            item.flag == CODE_128_FLAG || item.name.equals(CODE_128_NAME, ignoreCase = true)

        fun isSessionSymbol(item: ScannerSettingItem): Boolean =
            isQrSymbol(item) || isCode128Symbol(item)
    }
}

enum class BleSymbologyMode {
    UNRESTRICTED,
    /** Fixed physical mode: both symbols stay enabled throughout a session. */
    SESSION_CODES,
    QR_ONLY,
    CODE_128_ONLY,
    ;

    companion object {
        fun forExpectedFormat(format: ScanFormat?): BleSymbologyMode =
            when (format) {
                ScanFormat.QR -> QR_ONLY
                ScanFormat.CODE_128 -> CODE_128_ONLY
                null -> UNRESTRICTED
            }
    }
}

/** A protocol command with the exact device-reported area/name/value triple. */
data class SymbologySettingCommand(
    val area: String,
    val name: String,
    val value: Int,
    /** Device-reported flag, when the selected codec needs to send it back. */
    val flag: Int? = null,
    /** Raw JSON values or adapter-defined metadata retained for round-trip. */
    val extraFields: Map<String, String> = emptyMap(),
)

object SymbologySettings {
    const val QR_NAME = "qrcode_on"
    const val CODE_128_NAME = "code128_on"

    /**
     * Parses both iOS-observed response shapes: `{ "data": [...] }` and
     * `{ "info": [...] }`. Unknown symbology entries are retained when they
     * have a symbology flag or a known symbology name; unrelated settings are
     * excluded so a session cannot accidentally disable scanner behavior such
     * as cache or trigger settings. New/unknown symbologies are retained when
     * the device reports an official symbology flag.
     */
    fun parse(
        deviceId: String,
        settingsJson: String,
        capturedAtMillis: Long = 0L,
    ): SymbologySnapshot? {
        val root = runCatching { JsonParser.parseString(settingsJson).asJsonObject }.getOrNull()
            ?: return null
        val rawItems = root.array("data") ?: root.array("info") ?: return null
        val parsed = buildList {
            rawItems.forEach { element ->
                val parsedItem = parseItem(element)
                if (looksLikeSymbologyItem(element) && parsedItem == null) {
                    // A malformed reported symbology must invalidate the
                    // entire inventory. Silently dropping it would make an
                    // exact restore impossible after session restriction.
                    return null
                }
                if (parsedItem != null && isSymbologyItem(parsedItem)) {
                    add(parsedItem)
                }
            }
        }
        if (parsed.isEmpty()) return null
        return SymbologySnapshot(deviceId, parsed, capturedAtMillis)
    }

    fun hasRequired(snapshot: SymbologySnapshot): Boolean = snapshot.hasRequiredSessionSymbols()

    fun commandsFor(
        snapshot: SymbologySnapshot,
        mode: BleSymbologyMode,
    ): List<SymbologySettingCommand>? = snapshot.forMode(mode)?.map {
        SymbologySettingCommand(
            area = it.area,
            name = it.name,
            value = it.value,
            flag = it.flag,
            extraFields = it.extraFields,
        )
    }

    /**
     * Canonical iOS-shaped JSON accepted by the observed scanner API.
     *
     * This encoder is intentionally kept behind [IosObservedSymbologyCodec]
     * for transport use. It preserves optional flag/extra fields so a codec
     * that needs them can round-trip the complete reported inventory; an
     * Android adapter with another wire format should provide its own codec.
     */
    fun encodeCommands(commands: List<SymbologySettingCommand>): String {
        val array = JsonArray()
        commands.forEach { command ->
            array.add(JsonObject().apply {
                addProperty("area", command.area)
                addProperty("value", command.value.toString())
                addProperty("name", command.name)
                command.flag?.let { addProperty("flag", it) }
                command.extraFields.forEach { (key, rawValue) ->
                    if (key !in RESERVED_COMMAND_KEYS) {
                        add(key, rawJsonValue(rawValue))
                    }
                }
            })
        }
        return Gson().toJson(array)
    }

    fun encodeSnapshot(snapshot: SymbologySnapshot): String = Gson().toJson(snapshot)

    fun decodeSnapshot(json: String): SymbologySnapshot? =
        runCatching { Gson().fromJson(json, SymbologySnapshot::class.java) }.getOrNull()

    private fun parseItem(element: JsonElement): ScannerSettingItem? {
        if (!element.isJsonObject) return null
        val jsonObject = element.asJsonObject
        val name = jsonObject.string("name") ?: return null
        val area = jsonObject.string("area") ?: return null
        val value = jsonObject.intValue("value") ?: return null
        if (value !in 0..1) return null
        val flag = if (jsonObject.has("flag")) {
            jsonObject.intValue("flag") ?: return null
        } else {
            null
        }
        val extras = jsonObject.entrySet()
            .filter { it.key != "name" && it.key != "area" && it.key != "value" && it.key != "flag" }
            .associate { it.key to it.value.toString() }
        return ScannerSettingItem(name, area, value, flag, extras)
    }

    private fun isSymbologyItem(item: ScannerSettingItem): Boolean {
        val flag = item.flag
        if (flag != null && flag in 2001..2028) return true
        val lowerName = item.name.lowercase()
        return lowerName in LEGACY_NAMES
    }

    private fun looksLikeSymbologyItem(element: JsonElement): Boolean {
        if (!element.isJsonObject) return true
        val jsonObject = element.asJsonObject
        val flag = jsonObject.intValue("flag")
        if (flag != null && flag in 2001..2028) return true
        val name = jsonObject.string("name")?.lowercase()
        return name != null && name in LEGACY_NAMES
    }

    private fun JsonObject.array(name: String): List<JsonElement>? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.string(name: String): String? = get(name)?.let { element ->
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asNumber.toString()
            else -> null
        }
    }

    private fun JsonObject.intValue(name: String): Int? = string(name)?.toIntOrNull()

    private fun rawJsonValue(value: String): JsonElement =
        runCatching { JsonParser.parseString(value) }
            .getOrElse { com.google.gson.JsonPrimitive(value) }

    private val RESERVED_COMMAND_KEYS = setOf("area", "value", "name", "flag")

    private val LEGACY_NAMES = setOf(
        "codabar_on", "iata25_on", "interleaved25_on", "matrix25_on", "standard25_on",
        "code39_on", "code93_on", "code128_on", "ean_8_on", "ean_13_on", "upc_a_on",
        "upc_e0_on", "msi_on", "code11_on", "chinese_post_on", "upc_e1_on",
        "aztec_on", "maxicode_on", "hanxin_on", "datamatrix_on", "qrcode_on",
        "pdf417_on", "gs1_128", "rss14_composite_on", "rss_14_composite_on", "plessey_on",
        "telepen_on", "rss_14_on", "rss_expanded_on", "rss_limited_on", "symb_128_on",
        "usps_on", "usps_fedex",
    )
}

/** In-memory persistence useful for tests and as a default adapter boundary. */
sealed interface SymbologySnapshotReadResult {
    /** No snapshot has been saved for this device. */
    data object Missing : SymbologySnapshotReadResult

    /** A snapshot passed all persistence and identity checks. */
    data class Found(val snapshot: SymbologySnapshot) : SymbologySnapshotReadResult

    /** A persisted value exists but must not be applied. */
    data class Rejected(val reason: String) : SymbologySnapshotReadResult
}

sealed interface SymbologySnapshotClearResult {
    /** A matching, valid snapshot was removed atomically. */
    data object Cleared : SymbologySnapshotClearResult

    /** No snapshot existed, so the expected recovery record was not cleared. */
    data object Missing : SymbologySnapshotClearResult

    /** A value existed but was unreadable or belonged to another identity. */
    data class Rejected(val reason: String) : SymbologySnapshotClearResult
}

/**
 * Pre-session recovery inventories keyed by scanner (device id).
 *
 * Every device's snapshot is saved, read, and cleared independently: a restore
 * still pending for one scanner neither blocks nor is ever applied to another
 * scanner, and is restored when that scanner reconnects (#135).
 */
interface SymbologySnapshotStore {
    val profileIdentity: String

    fun load(deviceId: String): SymbologySnapshot?

    /**
     * Identity-aware read of [deviceId]'s own snapshot, used by lifecycle code.
     * Legacy stores retain their nullable [load] API while Android persistence
     * adapters can distinguish a missing value from a corrupt, incompatible, or
     * mismatched value.
     */
    fun read(deviceId: String): SymbologySnapshotReadResult =
        load(deviceId)?.let(SymbologySnapshotReadResult::Found)
            ?: SymbologySnapshotReadResult.Missing

    /** Replace the snapshot of [SymbologySnapshot.deviceId] only. */
    fun save(snapshot: SymbologySnapshot)

    /** Remove [deviceId]'s snapshot only; other devices' snapshots are kept. */
    fun clear(deviceId: String): SymbologySnapshotClearResult
}

class InMemorySymbologySnapshotStore(
    override val profileIdentity: String,
) : SymbologySnapshotStore {
    private val snapshots = mutableMapOf<String, SymbologySnapshot>()

    override fun load(deviceId: String): SymbologySnapshot? = snapshots[deviceId]

    override fun save(snapshot: SymbologySnapshot) {
        snapshots[snapshot.deviceId] = snapshot
    }

    override fun clear(deviceId: String): SymbologySnapshotClearResult =
        if (snapshots.remove(deviceId) != null) {
            SymbologySnapshotClearResult.Cleared
        } else {
            SymbologySnapshotClearResult.Missing
        }
}
