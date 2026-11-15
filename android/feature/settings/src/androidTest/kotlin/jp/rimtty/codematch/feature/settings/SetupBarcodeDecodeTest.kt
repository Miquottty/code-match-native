package jp.rimtty.codematch.feature.settings

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Proves that Android's bundled decoder can read every vector shown by the
 * first-run scanner guide: the four distinct codes, the factory reset
 * (`SetFun00`) included, and the code of each of the six guide pages. The
 * bitmap is memory-only and is never persisted or logged. A physical BCST-47
 * scan remains a separate M4 acceptance gate.
 */
class SetupBarcodeDecodeTest {
    @Test
    fun allSetupBarcodesDecodeToTheirExactCommands() {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build(),
        )

        try {
            BluetoothScannerSetupCode.entries.forEach { setupCode ->
                assertDecodesTo(
                    scanner = scanner,
                    barcode = Code128Encoder.encode(setupCode),
                    expected = setupCode.rawValue,
                    label = setupCode.name,
                )
            }
        } finally {
            scanner.close()
        }
    }

    /**
     * What the operator actually reads is a guide page, so every page must
     * decode to the command of its position in the setup protocol.
     */
    @Test
    fun everyGuideStepDecodesToTheCommandOfItsPositionInTheProtocol() {
        val expectedCommands = listOf(
            "/*EnterSet*/",
            "/*SetFun00*/",
            "/*ExitSave*/",
            "/*EnterSet*/",
            "/*BLE_GATT*/",
            "/*ExitSave*/",
        )
        val steps = BluetoothScannerSetupStep.entries
        assertEquals(expectedCommands.size, steps.size)

        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build(),
        )

        try {
            steps.zip(expectedCommands).forEach { (step, expected) ->
                assertDecodesTo(
                    scanner = scanner,
                    barcode = Code128Encoder.encode(step),
                    expected = expected,
                    label = step.name,
                )
            }
        } finally {
            scanner.close()
        }
    }

    private fun assertDecodesTo(
        scanner: BarcodeScanner,
        barcode: Code128Barcode,
        expected: String,
        label: String,
    ) {
        val bitmap = barcode.toBitmap()
        val decoded = Tasks.await(
            scanner.process(InputImage.fromBitmap(bitmap, 0)),
            DECODE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )

        assertEquals("$label should decode exactly once", 1, decoded.size)
        assertEquals(Barcode.FORMAT_CODE_128, decoded.single().format)
        assertEquals(expected, decoded.single().rawValue)
        bitmap.recycle()
    }

    private fun Code128Barcode.toBitmap(): Bitmap {
        val width = widthModules * PIXELS_PER_MODULE
        val height = heightModules * PIXELS_PER_MODULE
        val pixels = IntArray(width * height) { Color.WHITE }
        val barTop = quietZoneModules * PIXELS_PER_MODULE
        val barBottom = barTop + barHeightModules * PIXELS_PER_MODULE

        modules.forEachIndexed { moduleIndex, isBlack ->
            if (!isBlack) return@forEachIndexed
            val left = moduleIndex * PIXELS_PER_MODULE
            val right = left + PIXELS_PER_MODULE
            for (y in barTop until barBottom) {
                val row = y * width
                for (x in left until right) {
                    pixels[row + x] = Color.BLACK
                }
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val PIXELS_PER_MODULE = 4
        const val DECODE_TIMEOUT_SECONDS = 10L
    }
}
