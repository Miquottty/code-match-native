package jp.rimtty.codematch.feature.settings

/**
 * The distinct setup barcodes of the scanner guide: the exact ASCII messages
 * used by the iOS guide, each listed once.
 *
 * This enum says nothing about the order in which the codes are read, and two
 * of them are read twice; the guide walks [BluetoothScannerSetupStep] instead.
 * [scannerDisplayText] is what the scanner shows after reading the code; it is
 * empty when no display text has been confirmed (the factory reset), and the
 * guide then hides its display hint rather than inventing one.
 */
enum class BluetoothScannerSetupCode(
    val rawValue: String,
    val accessibilityId: String,
    val scannerDisplayText: String,
) {
    ENTER_SETUP(
        rawValue = "/*EnterSet*/",
        accessibilityId = "enterSetup",
        scannerDisplayText = "Enter Setup",
    ),
    FACTORY_RESET(
        rawValue = "/*SetFun00*/",
        accessibilityId = "factoryReset",
        scannerDisplayText = "",
    ),
    GATT_MODE(
        rawValue = "/*BLE_GATT*/",
        accessibilityId = "gattMode",
        scannerDisplayText = "BLE_GATT",
    ),
    SAVE_AND_EXIT(
        rawValue = "/*ExitSave*/",
        accessibilityId = "saveAndExit",
        scannerDisplayText = "Exit / Save",
    ),
}

/**
 * The six pages of the scanner guide, in the order the codes must be read.
 *
 * The order is the scanner's setup protocol, verified on an Inateck BCST-36 on
 * 2026-11-15, and must not be changed: the factory reset has to be saved and
 * setup mode entered again before `BLE_GATT` is read, because reading
 * `BLE_GATT` directly after the factory reset does not take effect.
 *
 * `EnterSet` and `ExitSave` each appear twice, so pages are identified by
 * [accessibilityId] (shared with the iOS guide), never by their [code].
 */
enum class BluetoothScannerSetupStep(
    val code: BluetoothScannerSetupCode,
    val accessibilityId: String,
) {
    ENTER_SETUP(
        code = BluetoothScannerSetupCode.ENTER_SETUP,
        accessibilityId = "enterSetup",
    ),
    FACTORY_RESET(
        code = BluetoothScannerSetupCode.FACTORY_RESET,
        accessibilityId = "factoryReset",
    ),
    SAVE_FACTORY_RESET(
        code = BluetoothScannerSetupCode.SAVE_AND_EXIT,
        accessibilityId = "saveFactoryReset",
    ),
    ENTER_SETUP_AGAIN(
        code = BluetoothScannerSetupCode.ENTER_SETUP,
        accessibilityId = "enterSetupAgain",
    ),
    GATT_MODE(
        code = BluetoothScannerSetupCode.GATT_MODE,
        accessibilityId = "gattMode",
    ),
    SAVE_AND_EXIT(
        code = BluetoothScannerSetupCode.SAVE_AND_EXIT,
        accessibilityId = "saveAndExit",
    ),
}

/**
 * A deterministic, raster-independent Code 128 image description.
 *
 * [modules] contains one entry per horizontal module, including the white
 * quiet zone on both sides. The bars occupy [barHeightModules] rows between
 * the same-sized white quiet zones above and below. This mirrors Core Image's
 * CICode128BarcodeGenerator output for the iOS guide: with quiet space 18,
 * these setup values have 203 x 68 base modules (167 symbol modules and 18
 * modules on each side/top/bottom). A renderer may scale the x and y axes
 * independently, just as iOS does with CGAffineTransform(scaleX: 4, y: 8).
 */
data class Code128Barcode(
    val modules: List<Boolean>,
    val encodedValues: List<Int>,
    val quietZoneModules: Int = Code128Encoder.DEFAULT_QUIET_ZONE_MODULES,
    val barHeightModules: Int = Code128Encoder.DEFAULT_BAR_HEIGHT_MODULES,
) {
    init {
        require(quietZoneModules >= 0) { "quietZoneModules must not be negative" }
        require(barHeightModules > 0) { "barHeightModules must be positive" }
        require(modules.size > quietZoneModules * 2) {
            "A Code 128 symbol must contain modules between its quiet zones"
        }
        require(modules.take(quietZoneModules).all { !it }) {
            "The leading quiet zone must be white"
        }
        require(modules.takeLast(quietZoneModules).all { !it }) {
            "The trailing quiet zone must be white"
        }
        require(encodedValues.firstOrNull() == Code128Encoder.START_CODE_B) {
            "The setup guide uses Code 128 B"
        }
        require(encodedValues.lastOrNull() == Code128Encoder.STOP) {
            "A Code 128 symbol must end with the stop code"
        }
    }

    /** Width of the generated base image before a renderer applies scaling. */
    val widthModules: Int get() = modules.size

    /** Width aliases keep the model easy to consume from platform renderers. */
    val width: Int get() = widthModules

    /** Height of the generated base image, including top and bottom quiet space. */
    val heightModules: Int get() = barHeightModules + quietZoneModules * 2

    val height: Int get() = heightModules

    /** Number of modules occupied by the start/data/checksum/stop symbols. */
    val symbolModules: Int get() = widthModules - quietZoneModules * 2

    val quietZone: Int get() = quietZoneModules

    /** The aspect ratio after matching iOS's x4/y8 render transform. */
    val iOSRenderAspectRatio: Float
        get() = (widthModules * 4f) / (heightModules * 8f)

    /** A short alias for Compose or image exporters that call it render ratio. */
    val renderAspectRatio: Float get() = iOSRenderAspectRatio
}

/**
 * Code 128 B encoder for the printable ASCII setup messages.
 *
 * Core Image's Code 128 filter selects Code 128 B for these messages. Keeping
 * the encoder local and table-driven avoids a barcode dependency and makes the
 * exact vector/checksum suitable for JVM tests. No scanner payload is logged.
 */
object Code128Encoder {
    const val DEFAULT_QUIET_ZONE_MODULES: Int = 18
    const val DEFAULT_BAR_HEIGHT_MODULES: Int = 32
    const val START_CODE_B: Int = 104
    const val STOP: Int = 106

    /** Encode a setup message using the same 18-module quiet space as iOS. */
    fun encode(
        message: String,
        quietZoneModules: Int = DEFAULT_QUIET_ZONE_MODULES,
        barHeightModules: Int = DEFAULT_BAR_HEIGHT_MODULES,
    ): Code128Barcode {
        require(message.isNotEmpty()) { "Code 128 message must not be empty" }
        require(quietZoneModules >= 0) { "quietZoneModules must not be negative" }
        require(barHeightModules > 0) { "barHeightModules must be positive" }
        require(message.all { it.code in PRINTABLE_ASCII }) {
            "Code 128 B setup messages must contain printable ASCII"
        }

        val dataValues = message.map { it.code - PRINTABLE_ASCII.first }
        val checksum = (START_CODE_B + dataValues.mapIndexed { index, value -> (index + 1) * value }
            .sum()) % CHECKSUM_MODULUS
        val encodedValues = buildList {
            add(START_CODE_B)
            addAll(dataValues)
            add(checksum)
            add(STOP)
        }

        val symbolModules = encodedValues.flatMap(::patternModules)
        val modules = buildList(symbolModules.size + quietZoneModules * 2) {
            repeat(quietZoneModules) { add(false) }
            addAll(symbolModules)
            repeat(quietZoneModules) { add(false) }
        }
        return Code128Barcode(
            modules = modules,
            encodedValues = encodedValues,
            quietZoneModules = quietZoneModules,
            barHeightModules = barHeightModules,
        )
    }

    /** Encode one of the guide's values without exposing it to any logger. */
    fun encode(code: BluetoothScannerSetupCode): Code128Barcode = encode(code.rawValue)

    /** Encode the code a guide page shows. */
    fun encode(step: BluetoothScannerSetupStep): Code128Barcode = encode(step.code)

    private fun patternModules(value: Int): List<Boolean> {
        require(value in CODE128_PATTERNS.indices) { "Unknown Code 128 value: $value" }
        val pattern = CODE128_PATTERNS[value]
        val modules = ArrayList<Boolean>(pattern.sumOf { it - '0' })
        var black = true
        pattern.forEach { widthCharacter ->
            repeat(widthCharacter - '0') { modules += black }
            black = !black
        }
        return modules
    }

    private val PRINTABLE_ASCII = 32..127
    private const val CHECKSUM_MODULUS = 103

    /** Code 128 patterns, values 0...106, with stop at 106. */
    private val CODE128_PATTERNS = listOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112",
    )
}
