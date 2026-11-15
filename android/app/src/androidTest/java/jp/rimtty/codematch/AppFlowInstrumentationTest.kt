package jp.rimtty.codematch

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import jp.rimtty.codematch.di.DebugAppTestEntryPoint
import jp.rimtty.codematch.feature.history.HistoryTestTags
import jp.rimtty.codematch.feature.settings.BluetoothScannerSetupStep
import jp.rimtty.codematch.feature.settings.SettingsTestTags
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.ScanLogEventKind
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.fake.FakeExternalScanner
import jp.rimtty.codematch.scan.ScanViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Application-level evidence for flows that cross navigation, ViewModels,
 * repositories, and the debug Fake scanner.
 *
 * The Fake is reached only through [DebugAppTestEntryPoint], which exists in
 * the debug source set. Payloads are injected on the UI thread and are never
 * written to logs or diagnostics. This keeps the tests deterministic without
 * making a claim about real camera frames or a production BLE adapter.
 *
 * This suite is for a dedicated connected-test installation only. It resets
 * the debug app's Room/DataStore state before and after each case and must not
 * be run against a user's production data.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dependencies: DebugAppTestEntryPoint
        get() = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            DebugAppTestEntryPoint::class.java,
        )

    private val fakeScanner: FakeExternalScanner
        get() = dependencies.externalScanner() as FakeExternalScanner

    private fun onNodeWithTag(tag: String) = composeRule.onNodeWithTag(tag)

    private fun onAllNodesWithTag(tag: String) = composeRule.onAllNodesWithTag(tag)

    private fun onNodeWithText(text: String) = composeRule.onNodeWithText(text)

    private fun onAllNodesWithText(text: String) = composeRule.onAllNodesWithText(text)

    @Before
    fun resetApplicationState() {
        clearRepositories()
        resetFakeScanner()
        resetApplicationLocale()

        // Navigation and saveable destination state are intentionally tested
        // elsewhere. Recreate after cleanup so this class starts with the
        // current persisted defaults even when another test left a route or
        // locale in the retained Activity state.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun restoreApplicationState() {
        // Leave the shared test process in the product defaults for the other
        // connected tests. The next test also performs the same reset before
        // its Activity is recreated.
        clearRepositories()
        resetFakeScanner()
        resetApplicationLocale()
    }

    @Test
    fun fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches() {
        connectFakeScannerThroughSettings()

        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_session_name")
            .performTextInput("app flow")
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_input_source_picker")
        onNodeWithTag("scan_input_bluetooth").assertIsSelected()

        // Match the Swift UI flow: switch away from Bluetooth and back before
        // exercising the scanner callback order.
        onNodeWithTag("scan_input_camera").performClick()
        onNodeWithTag("scan_input_camera").assertIsSelected()
        onNodeWithTag("scan_camera_stage").assertIsDisplayed()
        onNodeWithTag("scan_input_bluetooth").performClick()
        onNodeWithTag("scan_input_bluetooth").assertIsSelected()

        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        waitForTag("scan_message")
        onNodeWithText("QRコード読み取り").assertIsDisplayed()

        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        onNodeWithText("バーコード読み取り").assertIsDisplayed()

        // The Fake duplicate gate is crossed because the repeated Bluetooth
        // callbacks are one second apart.
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(1)

        // Reset to the QR step and prove that a mismatching Code 128 is kept
        // visible but does not change the persisted count.
        onNodeWithTag("scan_manual_next").performClick()
        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 5_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = mismatchBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 6_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("不一致").assertIsDisplayed()
        assertSessionCount(1)

        // A second box has a different full QR even though the part number and
        // Code 128 are shared, so it must be recorded as another box.
        onNodeWithTag("scan_manual_next").performClick()
        emitBluetooth(
            ScanPayload.qr(
                value = secondBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 7_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 8_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 9_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(2)

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm)).performClick()
        waitForTag("scan_start_session")

        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).assertIsDisplayed()
    }

    /**
     * Mirrors the iOS match -> duplicate -> reset/reread -> mismatch flow
     * through the app-owned ViewModel, Room repository, and debug Fake.
     *
     * The first two comparisons use different box QR values with one shared
     * part number and Code 128, and must become two persisted boxes. Re-reading
     * the first QR is a duplicate. The final mismatch after a QR reread must
     * leave both the count and history unchanged before the session is ended.
     */
    @Test
    fun fakeScannerDifferentBoxesDuplicateRereadAndMismatchPreserveCountAndHistory() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        // First successful comparison records box 1.
        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        assertSessionCount(1)
        awaitActiveEntryCount(1)

        // Manual next is the reset equivalent. A different box QR carrying
        // the same part and Code 128 is a second persisted box.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = secondBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // Re-reading the first box with the same shared Code 128 is a
        // duplicate. It remains visible but creates no third history row.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 5_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 6_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("すでに照合済みです").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // Start a fourth comparison, then explicitly reread the QR. The
        // reread preserves the two successful boxes and returns to QR input.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 7_000L,
            ),
        )
        waitForText("バーコード読み取り")
        // The action follows the waiting card and can be outside the compact
        // API 31 viewport. Scroll it into view so the click exercises the
        // action instead of being clipped by the root scroll container.
        onNodeWithTag("scan_reread_qr")
            .performScrollTo()
            .performClick()
        waitForText("QRコード読み取り")
        assertSessionCount(2)

        // Re-read the correct QR and then submit a different Code 128. A
        // mismatch is visible but is never persisted or counted as a box.
        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 8_000L,
            ),
        )
        waitForText("バーコード読み取り")
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 9_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("不一致").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)
        assertActiveEntries(
            expectedCodes = listOf(
                "BCJH-55-81GG",
                "BCJH-55-81GG",
            ),
        )

        // Ending publishes the two successful boxes to history; the mismatch
        // never creates a third entry.
        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm)).performClick()
        waitForTag("scan_start_session")
        awaitHistoryEntryCount(expectedSessions = 1, expectedEntries = 2)
        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).assertIsDisplayed()
    }

    /**
     * The Denso destination end to end: a JAMA kanban locks the session, the
     * 6-4 tag matches, a second kanban of the same part is the second box, the
     * same kanban is a duplicate whatever tag follows it, and a Sawai slip is
     * refused with a message naming the locked destination.
     */
    @Test
    fun fakeScannerDensoFlowCountsBoxesPerPartNumberAndLocksDestination() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        // Box 1: kanban serial 0140 of part 860150-7722.
        emitBluetooth(
            ScanPayload.qr(
                value = densoFirstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = densoFirstBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        // The Denso part number is printed 6-4, never as a Sawai 4-2-4.
        onNodeWithTag("scan_result_qr_part.value")
            .performScrollTo()
            .assertTextEquals("860150-7722")
        // Boxes are counted per part number, so the Molten delivery summary
        // must not appear.
        onAllNodesWithTag("scan_result_molten_box_summary").assertCountEquals(0)
        onNodeWithTag("scan_session_destination")
            .performScrollTo()
            .assertTextEquals("仕向地：デンソー")
        assertSessionCount(1)
        awaitActiveEntryCount(1)

        // Box 2: a different kanban serial (0141) of the same part.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = densoSecondBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = densoSecondBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // The kanban serial identifies the box, so re-reading kanban 0141 is
        // the same physical box even though a new label follows it.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = densoSecondBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 5_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = densoFirstBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 6_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("すでに照合済みです").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // A Sawai slip cannot join a Denso session; the step never advances.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 7_000L,
            ),
        )
        composeRule.onNodeWithText(
            "このセッションは仕向地「デンソー」で照合中です",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        // Scrolling to the message above can push the waiting card out of the
        // compact CI emulator viewport; bring the title back before asserting.
        onNodeWithText("QRコード読み取り").performScrollTo().assertIsDisplayed()
        assertSessionCount(2)
        assertActiveEntries(
            expectedCodes = listOf(
                "860150-7722",
                "860150-7722",
            ),
        )

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm))
            .performClick()
        waitForTag("scan_start_session")
        awaitHistoryEntryCount(expectedSessions = 1, expectedEntries = 2)
        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DESTINATION, useUnmergedTree = true)
            .assertTextEquals("デンソー")
        onNodeWithTag(HistoryTestTags.SESSION_ROW).performClick()
        waitForTag(HistoryTestTags.SESSION_DETAIL)
        onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasTestTag(HistoryTestTags.GROUP_ROW))
        onNodeWithTag(HistoryTestTags.GROUP_ROW).performClick()
        waitForTag(HistoryTestTags.GROUP_DETAIL)
        // Both kanbans carry one part number, so the group lists two boxes.
        onNodeWithTag(HistoryTestTags.GROUP_DETAIL)
            .performScrollToNode(hasText("2箱目"))
        onNodeWithText("2箱目").assertIsDisplayed()
    }

    /**
     * The Molten destination end to end: the first accepted QR locks the
     * session, boxes are counted per delivery number with a running quantity,
     * a repeated QR+tag pair is a duplicate, and a Sawai slip is refused with
     * a message naming the locked destination.
     */
    @Test
    fun fakeScannerMoltenFlowCountsBoxesPerDeliveryNumberAndLocksDestination() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        // Box 1 of delivery number UAG5560 (pack quantity 120).
        emitBluetooth(
            ScanPayload.qr(
                value = moltenDeliveryQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = moltenFirstBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        onNodeWithTag("scan_result_qr_part.value").assertTextEquals("PAF1-15-422")
        onNodeWithTag("scan_result_molten_box_summary")
            .performScrollTo()
            .assertTextEquals("納品番号 UAG5560 は本セッションで1箱目（累計 120個）")
        onNodeWithTag("scan_session_destination")
            .performScrollTo()
            .assertTextEquals("仕向地：モルテン")
        assertSessionCount(1)
        awaitActiveEntryCount(1)

        // The same slip with a different management code is the next box of
        // the same delivery number, so the quantity accumulates.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = moltenDeliveryQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = moltenSecondBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()
        onNodeWithTag("scan_result_molten_box_summary")
            .performScrollTo()
            .assertTextEquals("納品番号 UAG5560 は本セッションで2箱目（累計 240個）")
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // Re-reading the same QR and the same tag is the same physical box.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = moltenDeliveryQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 5_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = moltenFirstBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 6_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("すでに照合済みです").assertIsDisplayed()
        assertSessionCount(2)
        awaitActiveEntryCount(2)

        // A Sawai slip cannot join a Molten session; the step never advances.
        onNodeWithTag("scan_manual_next").performClick()
        waitForText("QRコード読み取り")
        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 7_000L,
            ),
        )
        composeRule.onNodeWithText(
            "このセッションは仕向地「モルテン」で照合中です",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        // Scrolling to the message above can push the waiting card out of the
        // compact CI emulator viewport; bring the title back before asserting.
        onNodeWithText("QRコード読み取り").performScrollTo().assertIsDisplayed()
        assertSessionCount(2)

        // A different delivery number restarts the box count and quantity.
        emitBluetooth(
            ScanPayload.qr(
                value = moltenSecondDeliveryQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 8_000L,
            ),
        )
        waitForText("バーコード読み取り")
        emitBluetooth(
            ScanPayload.code128(
                value = moltenSecondDeliveryBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 9_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithTag("scan_result_molten_box_summary")
            .performScrollTo()
            .assertTextEquals("納品番号 U543820 は本セッションで1箱目（累計 2個）")
        assertSessionCount(3)
        awaitActiveEntryCount(3)
        assertActiveEntries(
            expectedCodes = listOf(
                "PAF1-15-422",
                "PAF1-15-422",
                "D10E-50-N10B",
            ),
        )

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm))
            .performClick()
        waitForTag("scan_start_session")
        awaitHistoryEntryCount(expectedSessions = 1, expectedEntries = 3)
        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).performClick()
        waitForTag(HistoryTestTags.SESSION_DETAIL)
    }

    /**
     * An active session with no successful boxes is discarded at the same
     * application boundary that a non-empty session uses. The assertion is
     * made through both the repository and the rendered History destination.
     */
    @Test
    fun emptySessionIsNotKeptAfterConfirmedEnd() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm))
            .performClick()
        waitForTag("scan_start_session")

        // HistoryRepository deletes zero-box sessions rather than persisting
        // a misleading empty history row.
        awaitHistoryEntryCount(expectedSessions = 0, expectedEntries = 0)
        openDestination(R.string.destination_history)
        waitForText("履歴はまだありません")
        onNodeWithText("履歴はまだありません").assertIsDisplayed()
    }

    /**
     * Exercises the complete debug app graph: Fake scanner -> ScanViewModel ->
     * Room -> HistoryViewModel/HistoryRoute. The detail traversal also proves
     * that a persisted box remains addressable before the session is renamed
     * and deleted.
     */
    @Test
    fun completedSessionCanBeRenamedViewedInDetailsAndDeleted() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 10_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 11_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(1)
        awaitActiveEntryCount(1)

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm))
            .performClick()
        waitForTag("scan_start_session")
        awaitHistoryEntryCount(expectedSessions = 1, expectedEntries = 1)

        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).performClick()
        waitForTag(HistoryTestTags.SESSION_DETAIL)

        // The single persisted part is exposed as one group and one box.
        onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasTestTag(HistoryTestTags.GROUP_ROW))
        onNodeWithTag(HistoryTestTags.GROUP_ROW)
            .performClick()
        waitForTag(HistoryTestTags.GROUP_DETAIL)
        onNodeWithTag(HistoryTestTags.GROUP_DETAIL)
            .performScrollToNode(hasTestTag(HistoryTestTags.BOX_ROW))
        onNodeWithTag(HistoryTestTags.BOX_ROW)
            .performClick()
        waitForTag(HistoryTestTags.ENTRY_DETAIL)
        // The code is intentionally rendered in both the normalized and
        // parsed sections, so assert presence through the collection helper.
        waitForText("BCJH-52-81GG")

        // Pop box -> group -> session using the real compact history route.
        historyBack()
        waitForTag(HistoryTestTags.GROUP_DETAIL)
        historyBack()
        waitForTag(HistoryTestTags.SESSION_DETAIL)

        val renamedSession = "app-flow-renamed"
        onNodeWithTag(HistoryTestTags.NAME_FIELD)
            .performTextInput(renamedSession)
        onNodeWithTag(HistoryTestTags.NAME_FIELD).performImeAction()
        awaitSessionName(renamedSession)
        waitForText(renamedSession)

        // Returning to the list and selecting the row again verifies that the
        // displayed name came from Room, not only the text-field draft.
        backToHistoryList()
        waitForTag(HistoryTestTags.SESSION_ROW)
        waitForText(renamedSession)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).performClick()
        waitForTag(HistoryTestTags.SESSION_DETAIL)
        waitForText(renamedSession)
        backToHistoryList()
        waitForTag(HistoryTestTags.SESSION_ROW)

        onNodeWithTag("${HistoryTestTags.SESSION_ROW}.delete").performClick()
        awaitHistoryEntryCount(expectedSessions = 0, expectedEntries = 0)
        waitForText("履歴はまだありません")
        onNodeWithText("履歴はまだありません").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(HistoryTestTags.SESSION_ROW)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    @Test
    fun settingsGuideAndFakeScannerReconnectAreConnectedThroughTheApp() {
        openDestination(R.string.destination_settings)
        openGuideAtFirstStep()

        onNodeWithTag(
            SettingsTestTags.setupEnlarge(BluetoothScannerSetupStep.ENTER_SETUP),
        ).performScrollTo().performClick()
        waitForTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE)
        onNodeWithTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE).performClick()
        // Six pages in the scanner's protocol order; the last "next" closes
        // the guide.
        BluetoothScannerSetupStep.entries.drop(1).forEach { nextStep ->
            onNodeWithTag(SettingsTestTags.SETUP_NEXT).performScrollTo().performClick()
            waitForTag(SettingsTestTags.setupGuideStep(nextStep))
        }
        onNodeWithTag(SettingsTestTags.SETUP_NEXT).performScrollTo().performClick()

        waitForTag(SettingsTestTags.SCANNER_SECTION)
        onNodeWithTag(SettingsTestTags.DISCOVERY)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.DEVICE_ROW)
        onNodeWithTag(SettingsTestTags.DEVICE_ROW)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")

        onNodeWithTag(SettingsTestTags.DISCONNECT)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.RECONNECT)
        onNodeWithTag(SettingsTestTags.RECONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")
    }

    @Test
    fun languageSelectionPersistsAcrossActivityRecreation() {
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.LANGUAGE)
            .performScrollTo()
        onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE)
            .get(1)
            .performClick()

        waitForText("Display language")
        onNodeWithText("Display language").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.LANGUAGE).performScrollTo()
        onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE).get(1).assertIsSelected()
        onNodeWithText("Display language").assertIsDisplayed()

    }

    @Test
    fun enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime() {
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(5_000) {
            try {
                onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH).assertIsOn()
                true
            } catch (_: AssertionError) {
                // Repository updates are asynchronous; slower API images can
                // recompose after the input event has already completed.
                false
            }
        }
        onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH).assertIsOn()
        onAllNodesWithTag(SettingsTestTags.DELAY_CHOICE)
            .get(0)
            .performScrollTo()
            .performClick()

        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        emitCamera(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.CAMERA,
                timestampMillis = 100_000L,
            ),
        )
        emitCamera(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.CAMERA,
                timestampMillis = 101_000L,
            ),
        )
        emitCamera(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.CAMERA,
                timestampMillis = 102_000L,
            ),
        )
        waitForTag("scan_result_card")
        waitForTag("scan_countdown")
        assertSessionCount(1)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("scan_countdown").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("scan_waiting_card").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("QRコード読み取り").assertIsDisplayed()
        assertSessionCount(1)
    }


    /**
     * The scan log must survive the whole flow, not just a match: a repeated
     * box is exactly the case an operator asks about afterwards. Session
     * start, the accepted QR and Code 128, the match, and the duplicate all
     * belong to it, and the count is visible at the bottom of Settings.
     */
    @Test
    fun matchAndRepeatedBoxAreRecordedInTheScanLogAndCountedInSettings() {
        connectFakeScannerThroughSettings()
        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("一致").assertIsDisplayed()

        // The same box again: shown as a duplicate, never recorded as a box.
        onNodeWithTag("scan_manual_next").performClick()
        emitBluetooth(
            ScanPayload.qr(
                value = firstBoxQrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = sharedBoxBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(1)

        awaitScanLogEvents(
            listOf(
                ScanLogEventKind.SESSION_START,
                ScanLogEventKind.QR_ACCEPTED,
                ScanLogEventKind.BARCODE_ACCEPTED,
                ScanLogEventKind.MATCH,
                ScanLogEventKind.QR_ACCEPTED,
                ScanLogEventKind.BARCODE_ACCEPTED,
                ScanLogEventKind.DUPLICATE,
            ),
        )
        val recorded = runBlocking { dependencies.scanLogRepository().export() }
        val sessionId = runBlocking {
            dependencies.historyRepository().activeSession.first()?.id
        }
        assertEquals(sessionId, recorded.first().sessionId)
        assertEquals(firstBoxQrPayload, recorded[1].qrPayload)
        assertEquals(sharedBoxBarcodePayload, recorded[2].barcodePayload)
        assertEquals(1, recorded[3].boxNumber)

        openDestination(R.string.destination_settings)
        composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_COUNT)
            .performScrollTo()
            .assertIsDisplayed()
        val shown = composeRule.onNodeWithTag(SettingsTestTags.SCAN_LOG_COUNT)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }
        val count = Regex("\\d+").find(shown)?.value?.toInt() ?: 0
        assertTrue("scan log count was $shown", count >= 3)
    }

    private fun openDestination(destinationRes: Int) {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(destinationRes),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
    }

    private fun historyBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun backToHistoryList() {
        if (composeRule.activity.resources.configuration.screenWidthDp < 840) {
            historyBack()
        }
    }

    private fun normalizeGuideToFirstStep() {
        waitForTag(SettingsTestTags.SETUP_GUIDE)
        while (composeRule.onAllNodesWithTag(
                SettingsTestTags.setupGuideStep(BluetoothScannerSetupStep.ENTER_SETUP),
            ).fetchSemanticsNodes().isEmpty()
        ) {
            onNodeWithTag(SettingsTestTags.SETUP_PREVIOUS)
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
        }
    }

    private fun openGuideAtFirstStep() {
        if (composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE)
                .fetchSemanticsNodes().isEmpty()
        ) {
            onNodeWithTag(SettingsTestTags.SETUP_GUIDE_OPEN)
                .performScrollTo()
                .performClick()
        }
        normalizeGuideToFirstStep()
    }

    /** Connect the same debug Fake instance that the Settings screen owns. */
    private fun connectFakeScannerThroughSettings() {
        openDestination(R.string.destination_settings)
        openGuideAtFirstStep()
        repeat(BluetoothScannerSetupStep.entries.size) {
            onNodeWithTag(SettingsTestTags.SETUP_NEXT)
                .performScrollTo()
                .performClick()
        }
        waitForTag(SettingsTestTags.SCANNER_SECTION)
        onNodeWithTag(SettingsTestTags.DISCOVERY)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.DEVICE_ROW)
        onNodeWithTag(SettingsTestTags.DEVICE_ROW)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // Per-app locale changes recreate MainActivity asynchronously.
                // Treat the short no-hierarchy window as not ready yet.
                false
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // Per-app locale changes recreate MainActivity asynchronously.
                false
            }
        }
    }

    private fun awaitScanLogEvents(expected: List<String>) {
        composeRule.waitUntil(5_000) {
            runBlocking {
                dependencies.scanLogRepository().export().map { it.event } == expected
            }
        }
    }

    private fun assertSessionCount(expected: Int) {
        onNodeWithTag("scan_session_count")
            .assertTextEquals("${expected}件照合済み")
    }

    private fun awaitActiveEntryCount(expected: Int) {
        composeRule.waitUntil(5_000) {
            runBlocking {
                dependencies.historyRepository().activeSession.first()?.entries?.size == expected
            }
        }
    }

    private fun awaitHistoryEntryCount(expectedSessions: Int, expectedEntries: Int) {
        composeRule.waitUntil(5_000) {
            runBlocking {
                val sessions = dependencies.historyRepository().sessions.first()
                sessions.size == expectedSessions &&
                    (expectedSessions == 0 ||
                        sessions.firstOrNull()?.entries?.size == expectedEntries)
            }
        }
    }

    private fun awaitSessionName(expectedName: String) {
        composeRule.waitUntil(5_000) {
            runBlocking {
                dependencies.historyRepository().sessions.first()
                    .singleOrNull()
                    ?.name == expectedName
            }
        }
    }

    private fun assertActiveEntries(expectedCodes: List<String>) {
        val entries = runBlocking {
            dependencies.historyRepository().activeSession.first()?.entries.orEmpty()
        }
        assertEquals(expectedCodes, entries.map { it.code })
    }

    private fun emitBluetooth(payload: ScanPayload) = emit(payload)

    private fun emitCamera(payload: ScanPayload) = emit(payload)

    private fun emit(payload: ScanPayload) {
        var delivered = false
        composeRule.runOnIdle {
            delivered = fakeScanner.emitPayload(payload)
        }
        assertTrue("deterministic scan callback was not delivered", delivered)
        composeRule.waitForIdle()
    }

    private fun clearRepositories() {
        val history = dependencies.historyRepository()
        val settings = dependencies.settingsRepository()
        val scanLog = dependencies.scanLogRepository()
        runBlocking {
            val ids = history.sessions.first().map { it.id }
            history.deleteSessions(ids)
            settings.update { AppSettings() }
            // The scan log deliberately outlives its session, so deleting the
            // sessions above leaves it behind.
            scanLog.clear()
        }
    }

    private fun resetFakeScanner() {
        // connect -> disconnect clears the Fake's duplicate gate even when a
        // preceding test used camera-source callbacks without a BLE session.
        composeRule.runOnIdle {
            fakeScanner.connect(fakeScanner.defaultDevice)
            fakeScanner.disconnect()
            fakeScanner.clearDiagnostics()
        }
        composeRule.waitForIdle()
    }

    private fun resetApplicationLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(AppLanguage.JAPANESE.code)
            } else {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(
                        AppLanguage.JAPANESE.code,
                    ),
                )
            }
        }
    }

    private companion object {
        const val firstBoxQrPayload =
            "DAAL134150BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        const val secondBoxQrPayload =
            "DAAL134140BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        const val sharedBoxBarcodePayload = "BCJH-55-81GG@1KVQ0C"
        const val qrPayload =
            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        const val barcodePayload = "BCJH-52-81GG@1N5X0C"
        const val mismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"

        // Destination Molten. Trailing spaces are record data, so these
        // literals must never be reformatted: delivery UAG5560 carries pack
        // quantity 120 with a nine-character part, U543820 carries 2.
        const val moltenDeliveryQrPayload =
            "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
        const val moltenSecondDeliveryQrPayload =
            "AK6805D10E50N10B         U543820000MB    S600700000020908    "
        const val moltenFirstBoxBarcodePayload = "PAF1-15-422@0NKD3C"
        const val moltenSecondBoxBarcodePayload = "PAF1-15-422@0NLL3C"
        const val moltenSecondDeliveryBarcodePayload = "D10E-50-N10B@0UBL00"

        // Destination Denso. The first pair is the ViewModel's documented demo
        // pair (kanban serial 0140); 0141 is the next box of the same part.
        // The runs of spaces are blank item values, so these literals must
        // never be reformatted.
        const val densoFirstBoxQrPayload = ScanViewModel.SAMPLE_DENSO_QR_PAYLOAD
        const val densoFirstBoxBarcodePayload = ScanViewModel.SAMPLE_DENSO_BARCODE_PAYLOAD
        const val densoSecondBoxQrPayload =
            "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"
        const val densoSecondBoxBarcodePayload = "860150-7722@1DZB0O"
    }
}
