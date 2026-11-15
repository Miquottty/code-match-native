package jp.rimtty.codematch.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Code128BarcodeTest {
    @Test
    fun setupCodesAreTheFourDistinctExactAsciiMessages() {
        assertEquals(
            listOf(
                BluetoothScannerSetupCode.ENTER_SETUP to "/*EnterSet*/",
                BluetoothScannerSetupCode.FACTORY_RESET to "/*SetFun00*/",
                BluetoothScannerSetupCode.GATT_MODE to "/*BLE_GATT*/",
                BluetoothScannerSetupCode.SAVE_AND_EXIT to "/*ExitSave*/",
            ),
            BluetoothScannerSetupCode.entries.map { it to it.rawValue },
        )
        assertEquals(
            BluetoothScannerSetupCode.entries.size,
            BluetoothScannerSetupCode.entries.map(BluetoothScannerSetupCode::rawValue).toSet().size,
        )
        assertEquals(
            listOf("enterSetup", "factoryReset", "gattMode", "saveAndExit"),
            BluetoothScannerSetupCode.entries.map(BluetoothScannerSetupCode::accessibilityId),
        )
    }

    /**
     * The order is the scanner's setup protocol (verified on a BCST-36 on
     * 2026-11-15): the factory reset is saved and setup mode entered again
     * before BLE_GATT is read. The ids are shared with the iOS guide.
     */
    @Test
    fun setupStepsKeepTheScannerOrderCodesAndIds() {
        assertEquals(
            listOf(
                BluetoothScannerSetupStep.ENTER_SETUP,
                BluetoothScannerSetupStep.FACTORY_RESET,
                BluetoothScannerSetupStep.SAVE_FACTORY_RESET,
                BluetoothScannerSetupStep.ENTER_SETUP_AGAIN,
                BluetoothScannerSetupStep.GATT_MODE,
                BluetoothScannerSetupStep.SAVE_AND_EXIT,
            ),
            BluetoothScannerSetupStep.entries,
        )
        assertEquals(
            listOf(
                "/*EnterSet*/",
                "/*SetFun00*/",
                "/*ExitSave*/",
                "/*EnterSet*/",
                "/*BLE_GATT*/",
                "/*ExitSave*/",
            ),
            BluetoothScannerSetupStep.entries.map { it.code.rawValue },
        )
        assertEquals(
            listOf(
                "enterSetup",
                "factoryReset",
                "saveFactoryReset",
                "enterSetupAgain",
                "gattMode",
                "saveAndExit",
            ),
            BluetoothScannerSetupStep.entries.map(BluetoothScannerSetupStep::accessibilityId),
        )
    }

    @Test
    fun setupStepIdsAndTestTagsAreUniqueEvenWhereACodeRepeats() {
        val steps = BluetoothScannerSetupStep.entries

        assertEquals(steps.size, steps.map(BluetoothScannerSetupStep::accessibilityId).toSet().size)
        listOf(
            SettingsTestTags::setupGuideStep,
            SettingsTestTags::setupBarcode,
            SettingsTestTags::setupEnlarge,
            SettingsTestTags::setupFullscreenBarcode,
        ).forEach { tag ->
            assertEquals(steps.size, steps.map(tag).toSet().size)
        }
        assertEquals(
            "settings_setup_step_saveFactoryReset",
            SettingsTestTags.setupGuideStep(BluetoothScannerSetupStep.SAVE_FACTORY_RESET),
        )
        assertEquals(
            "settings_setup_barcode_enterSetupAgain",
            SettingsTestTags.setupBarcode(BluetoothScannerSetupStep.ENTER_SETUP_AGAIN),
        )
    }

    @Test
    fun stepsEncodeTheBarcodeOfTheirCode() {
        BluetoothScannerSetupStep.entries.forEach { step ->
            assertEquals(Code128Encoder.encode(step.code), Code128Encoder.encode(step))
        }
    }

    /** Only the factory reset has no confirmed display text; the guide hides its hint. */
    @Test
    fun onlyTheFactoryResetHasNoScannerDisplayText() {
        assertEquals(
            listOf(BluetoothScannerSetupCode.FACTORY_RESET),
            BluetoothScannerSetupCode.entries.filter { it.scannerDisplayText.isEmpty() },
        )
    }

    @Test
    fun enterSetupMatchesCoreImageCode128BVectorAndDimensions() {
        val barcode = Code128Encoder.encode(BluetoothScannerSetupCode.ENTER_SETUP)

        assertEquals(
            listOf(104, 15, 10, 37, 78, 84, 69, 82, 51, 69, 84, 10, 15, 9, 106),
            barcode.encodedValues,
        )
        assertEquals(167, barcode.symbolModules)
        assertEquals(203, barcode.widthModules)
        assertEquals(68, barcode.heightModules)
        assertEquals(18, barcode.quietZoneModules)
        assertEquals(32, barcode.barHeightModules)
        assertTrue(barcode.modules.take(18).all { !it })
        assertTrue(barcode.modules.takeLast(18).all { !it })
        assertTrue(barcode.modules.drop(18).dropLast(18).any { it })
    }

    @Test
    fun gattAndSaveCodesUseDeterministicChecksums() {
        val gatt = Code128Encoder.encode(BluetoothScannerSetupCode.GATT_MODE)
        val save = Code128Encoder.encode(BluetoothScannerSetupCode.SAVE_AND_EXIT)

        assertEquals(14, gatt.encodedValues[gatt.encodedValues.lastIndex - 1])
        assertEquals(85, save.encodedValues[save.encodedValues.lastIndex - 1])
        assertEquals(203, gatt.widthModules)
        assertEquals(203, save.widthModules)
        assertEquals(gatt.modules, Code128Encoder.encode(BluetoothScannerSetupCode.GATT_MODE).modules)
        assertEquals(save.modules, Code128Encoder.encode(BluetoothScannerSetupCode.SAVE_AND_EXIT).modules)
    }

    /**
     * The module row is what CICode128BarcodeGenerator (quiet space 18)
     * produced for this message on macOS on 2026-11-15: 203 x 68, like the
     * other setup codes.
     */
    @Test
    fun factoryResetMatchesCoreImageCode128BVectorAndDimensions() {
        val barcode = Code128Encoder.encode(BluetoothScannerSetupCode.FACTORY_RESET)

        assertEquals(
            listOf(104, 15, 10, 51, 69, 84, 38, 85, 78, 16, 16, 10, 15, 42, 106),
            barcode.encodedValues,
        )
        assertEquals(167, barcode.symbolModules)
        assertEquals(203, barcode.widthModules)
        assertEquals(68, barcode.heightModules)
        assertEquals(
            "000000000000000000" +
                "11010010000101110011001100100010011011101000101100100001001111010010001100010" +
                "100111100101100001010010011101100100111011001100100010010111001100101101110001" +
                "100011101011" +
                "000000000000000000",
            barcode.modules.joinToString("") { if (it) "1" else "0" },
        )
    }

    @Test
    fun quietZoneAndBarHeightCanBeChangedWithoutChangingEncodedSymbols() {
        val standard = Code128Encoder.encode("ABC")
        val custom = Code128Encoder.encode("ABC", quietZoneModules = 9, barHeightModules = 40)

        assertEquals(standard.encodedValues, custom.encodedValues)
        assertEquals(9, custom.quietZoneModules)
        assertEquals(40, custom.barHeightModules)
        assertEquals(standard.symbolModules + 18, custom.widthModules)
        assertEquals(58, custom.heightModules)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPrintableInputIsRejectedInsteadOfBeingEncodedDifferently() {
        Code128Encoder.encode("setup\n")
    }

    @Test
    fun moduleVectorContainsOnlyBlackAndWhiteModules() {
        val modules = Code128Encoder.encode("/*BLE_GATT*/").modules

        assertTrue(modules.any { it })
        assertTrue(modules.any { !it })
        assertFalse(modules.first())
        assertFalse(modules.last())
    }
}
