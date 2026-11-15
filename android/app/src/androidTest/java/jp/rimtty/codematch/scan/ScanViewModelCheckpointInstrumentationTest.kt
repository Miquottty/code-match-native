package jp.rimtty.codematch.scan

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.data.CodeMatchDatabase
import jp.rimtty.codematch.core.data.CodeMatchDatabaseFactory
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.ScanLogRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.feature.scan.InvalidScanReason
import jp.rimtty.codematch.feature.scan.ScanPhase
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.fake.FakeExternalScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * App-level checkpoint evidence using storage isolated from the normal app.
 *
 * The test drives [ScanViewModel] through its public UI actions, then closes
 * and reopens the UUID-named Room database before constructing a new ViewModel.
 * No default database, DataStore, or repository cleanup helper is used.
 */
@RunWith(AndroidJUnit4::class)
class ScanViewModelCheckpointInstrumentationTest {
    @Test
    fun qrTransitionSurvivesIsolatedDatabaseReopenAndRestoresCode128Step() = runBlocking {
        val fixture = IsolatedScanFixture.open()
        var firstOwner: TestViewModelOwner? = null
        var secondOwner: TestViewModelOwner? = null
        try {
            val first = fixture.createViewModel()
            firstOwner = first.first
            val firstViewModel = first.second

            firstViewModel.onAction(jp.rimtty.codematch.feature.scan.ScanUiAction.StartSession)
            awaitState(firstViewModel, "session-start") { state ->
                state.sessionActive && state.phase == ScanPhase.WAITING_QR
            }
            val sessionId = requireNotNull(fixture.history.activeSession.first()?.id)

            val qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
            firstViewModel.onAction(
                jp.rimtty.codematch.feature.scan.ScanUiAction.ScanReceived(
                    jp.rimtty.codematch.scanner.api.ScanPayload.qr(
                        value = qrPayload,
                        source = InputSource.CAMERA,
                        timestampMillis = 1_000L,
                    ),
                ),
            )
            val afterQr = awaitState(firstViewModel, "qr-transition") { state ->
                state.sessionActive &&
                    state.phase == ScanPhase.WAITING_CODE_128 &&
                    state.qrPayload == qrPayload
            }
            assertEquals(InputSource.CAMERA, afterQr.inputSource)
            assertEquals(jp.rimtty.codematch.scanner.api.ScanFormat.CODE_128, afterQr.expectedFormat)

            val persisted = awaitCheckpoint(fixture.history, sessionId, qrPayload)
            assertEquals(qrPayload, persisted?.qrPayload)
            assertEquals(
                jp.rimtty.codematch.core.model.ScanCheckpointPhase.WAITING_CODE_128,
                persisted?.phase,
            )

            firstOwner.clearAndJoin()
            firstOwner = null
            fixture.reopenDatabase()

            val second = fixture.createViewModel()
            secondOwner = second.first
            val restored = awaitState(second.second, "checkpoint-restoration") { state ->
                state.sessionActive &&
                    state.phase == ScanPhase.WAITING_CODE_128 &&
                    state.qrPayload == qrPayload
            }
            assertEquals(InputSource.CAMERA, restored.inputSource)
            assertEquals(jp.rimtty.codematch.scanner.api.ScanFormat.CODE_128, restored.expectedFormat)
            assertEquals(0, restored.matchedCount)
        } finally {
            secondOwner?.clearAndJoin()
            firstOwner?.clearAndJoin()
            fixture.close()
        }
    }

    @Test
    fun restoredMatchResultDoesNotReplayHistoryEntryAfterDatabaseReopen() = runBlocking {
        val fixture = IsolatedScanFixture.open()
        var owner: TestViewModelOwner? = null
        try {
            val sessionId = fixture.history.beginSession("result-${fixture.suffix}", at = 20_000L)
            val qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
            val barcodePayload = "BCJH-52-81GG@1N5X0C"
            val boxNumber = fixture.history.recordMatch(
                code = "BCJH-52-81GG",
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                at = 20_001L,
                sessionId = sessionId,
                checkpoint = jp.rimtty.codematch.core.model.ScanSessionCheckpoint(
                    sessionId = sessionId,
                    phase = jp.rimtty.codematch.core.model.ScanCheckpointPhase.RESULT,
                    qrPayload = qrPayload,
                    barcodePayload = barcodePayload,
                    result = MatchResult.MATCH,
                    matchedCount = 0,
                    inputSource = jp.rimtty.codematch.core.model.ScanCheckpointInputSource.CAMERA,
                    cameraWasSelectedByUser = true,
                ),
            )
            assertEquals(1, boxNumber)

            fixture.reopenDatabase()
            val created = fixture.createViewModel()
            owner = created.first
            val restored = awaitState(created.second) { state ->
                state.sessionActive &&
                    state.phase == ScanPhase.RESULT &&
                    state.result == MatchResult.MATCH &&
                    state.qrPayload == qrPayload &&
                    state.barcodePayload == barcodePayload
            }
            assertEquals(1, restored.matchedCount)

            val active = fixture.history.activeSession.first { it?.id == sessionId }
            assertEquals(1, active?.entries?.size)
            assertEquals("BCJH-52-81GG", active?.entries?.single()?.code)
            assertEquals(
                MatchResult.MATCH,
                fixture.history.getScanCheckpoint(sessionId)?.result,
            )
        } finally {
            owner?.clearAndJoin()
            fixture.close()
        }
    }

    @Test
    fun densoDestinationLockSurvivesIsolatedDatabaseReopen() = runBlocking {
        val fixture = IsolatedScanFixture.open()
        var firstOwner: TestViewModelOwner? = null
        var secondOwner: TestViewModelOwner? = null
        try {
            val first = fixture.createViewModel()
            firstOwner = first.first
            val firstViewModel = first.second

            firstViewModel.onAction(jp.rimtty.codematch.feature.scan.ScanUiAction.StartSession)
            awaitState(firstViewModel, "session-start") { state ->
                state.sessionActive && state.phase == ScanPhase.WAITING_QR
            }
            val sessionId = requireNotNull(fixture.history.activeSession.first()?.id)

            // Destination Denso; the runs of spaces are blank item values.
            val densoQrPayload =
                "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
            assertEquals(221, densoQrPayload.length)
            firstViewModel.onAction(
                jp.rimtty.codematch.feature.scan.ScanUiAction.ScanReceived(
                    jp.rimtty.codematch.scanner.api.ScanPayload.qr(
                        value = densoQrPayload,
                        source = InputSource.CAMERA,
                        timestampMillis = 1_000L,
                    ),
                ),
            )
            val afterQr = awaitState(firstViewModel, "denso-qr-transition") { state ->
                state.phase == ScanPhase.WAITING_CODE_128 &&
                    state.qrPayload == densoQrPayload
            }
            assertEquals(Destination.DENSO, afterQr.destination)

            val persisted = awaitCheckpoint(fixture.history, sessionId, densoQrPayload)
            assertEquals(Destination.DENSO, persisted?.destination)

            firstOwner.clearAndJoin()
            firstOwner = null
            fixture.reopenDatabase()

            val second = fixture.createViewModel()
            secondOwner = second.first
            val secondViewModel = second.second
            val restored = awaitState(secondViewModel, "denso-lock-restoration") { state ->
                state.sessionActive && state.phase == ScanPhase.WAITING_CODE_128
            }
            assertEquals(Destination.DENSO, restored.destination)

            // A reread drops the accepted QR but must not drop the lock.
            secondViewModel.onAction(jp.rimtty.codematch.feature.scan.ScanUiAction.RereadQr)
            val afterReread = awaitState(secondViewModel, "reread") { state ->
                state.phase == ScanPhase.WAITING_QR
            }
            assertEquals(Destination.DENSO, afterReread.destination)

            secondViewModel.onAction(
                jp.rimtty.codematch.feature.scan.ScanUiAction.ScanReceived(
                    jp.rimtty.codematch.scanner.api.ScanPayload.qr(
                        value =
                            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
                        source = InputSource.CAMERA,
                        timestampMillis = 5_000L,
                    ),
                ),
            )
            val rejected = awaitState(secondViewModel, "wrong-destination") { state ->
                state.lastInvalidReason == InvalidScanReason.WRONG_DESTINATION
            }
            assertEquals(ScanPhase.WAITING_QR, rejected.phase)
            assertEquals(Destination.DENSO, rejected.destination)

            assertEquals(
                Destination.DENSO,
                fixture.history.activeSession.first { it?.id == sessionId }?.destination,
            )
        } finally {
            secondOwner?.clearAndJoin()
            firstOwner?.clearAndJoin()
            fixture.close()
        }
    }

    @Test
    fun moltenDestinationLockSurvivesIsolatedDatabaseReopen() = runBlocking {
        val fixture = IsolatedScanFixture.open()
        var firstOwner: TestViewModelOwner? = null
        var secondOwner: TestViewModelOwner? = null
        try {
            val first = fixture.createViewModel()
            firstOwner = first.first
            val firstViewModel = first.second

            firstViewModel.onAction(jp.rimtty.codematch.feature.scan.ScanUiAction.StartSession)
            awaitState(firstViewModel, "session-start") { state ->
                state.sessionActive && state.phase == ScanPhase.WAITING_QR
            }
            val sessionId = requireNotNull(fixture.history.activeSession.first()?.id)

            // Destination Molten; the trailing spaces are record data.
            val moltenQrPayload =
                "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
            assertEquals(61, moltenQrPayload.length)
            firstViewModel.onAction(
                jp.rimtty.codematch.feature.scan.ScanUiAction.ScanReceived(
                    jp.rimtty.codematch.scanner.api.ScanPayload.qr(
                        value = moltenQrPayload,
                        source = InputSource.CAMERA,
                        timestampMillis = 1_000L,
                    ),
                ),
            )
            val afterQr = awaitState(firstViewModel, "molten-qr-transition") { state ->
                state.phase == ScanPhase.WAITING_CODE_128 &&
                    state.qrPayload == moltenQrPayload
            }
            assertEquals(Destination.MOLTEN, afterQr.destination)

            val persisted = awaitCheckpoint(fixture.history, sessionId, moltenQrPayload)
            assertEquals(Destination.MOLTEN, persisted?.destination)

            firstOwner.clearAndJoin()
            firstOwner = null
            fixture.reopenDatabase()

            val second = fixture.createViewModel()
            secondOwner = second.first
            val secondViewModel = second.second
            val restored = awaitState(secondViewModel, "molten-lock-restoration") { state ->
                state.sessionActive && state.phase == ScanPhase.WAITING_CODE_128
            }
            assertEquals(Destination.MOLTEN, restored.destination)

            // A reread drops the accepted QR but must not drop the lock.
            secondViewModel.onAction(jp.rimtty.codematch.feature.scan.ScanUiAction.RereadQr)
            val afterReread = awaitState(secondViewModel, "reread") { state ->
                state.phase == ScanPhase.WAITING_QR
            }
            assertEquals(Destination.MOLTEN, afterReread.destination)

            secondViewModel.onAction(
                jp.rimtty.codematch.feature.scan.ScanUiAction.ScanReceived(
                    jp.rimtty.codematch.scanner.api.ScanPayload.qr(
                        value =
                            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
                        source = InputSource.CAMERA,
                        timestampMillis = 5_000L,
                    ),
                ),
            )
            val rejected = awaitState(secondViewModel, "wrong-destination") { state ->
                state.lastInvalidReason == InvalidScanReason.WRONG_DESTINATION
            }
            assertEquals(ScanPhase.WAITING_QR, rejected.phase)
            assertEquals(Destination.MOLTEN, rejected.destination)

            assertEquals(
                Destination.MOLTEN,
                fixture.history.activeSession.first { it?.id == sessionId }?.destination,
            )
        } finally {
            secondOwner?.clearAndJoin()
            firstOwner?.clearAndJoin()
            fixture.close()
        }
    }

    private suspend fun awaitState(
        viewModel: ScanViewModel,
        stage: String = "result-restoration",
        predicate: (jp.rimtty.codematch.feature.scan.ScanUiState) -> Boolean,
    ): jp.rimtty.codematch.feature.scan.ScanUiState = try {
        withTimeout(10_000L) { viewModel.state.first(predicate) }
    } catch (timeout: TimeoutCancellationException) {
        // Do not print state.toString(): it contains accepted payloads.
        val state = viewModel.state.value
        throw AssertionError("Timed out at $stage: active=${state.sessionActive}, phase=${state.phase}", timeout)
    }

    private suspend fun awaitCheckpoint(
        history: HistoryRepository,
        sessionId: String,
        qrPayload: String,
    ): jp.rimtty.codematch.core.model.ScanSessionCheckpoint? = withTimeout(10_000L) {
        var checkpoint: jp.rimtty.codematch.core.model.ScanSessionCheckpoint?
        do {
            checkpoint = history.getScanCheckpoint(sessionId)
            if (checkpoint?.qrPayload != qrPayload) {
                kotlinx.coroutines.delay(25L)
            }
        } while (checkpoint?.qrPayload != qrPayload)
        checkpoint
    }

    private class TestViewModelOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()

        /** The scope job of the ViewModel this owner holds, recorded when it is created. */
        var viewModelJob: Job? = null

        /**
         * Clear the ViewModel and wait until its coroutines have really ended.
         *
         * `clear()` only cancels: a Room transaction the ViewModel had in
         * flight (checkpoint, scan log) unwinds a moment later. Closing the
         * database in that moment leaves the transaction open on a connection
         * nobody owns any more, and the file stays write-locked — the next
         * instance of the same file then fails its first write with
         * SQLITE_BUSY and the new ViewModel never initializes (#140: 8 of 25
         * runs on an emulator; none once the job is joined first). A process
         * death, which this test stands in for, has no such window.
         */
        suspend fun clearAndJoin() {
            viewModelStore.clear()
            viewModelJob?.let { job -> withTimeout(10_000L) { job.join() } }
        }
    }

    private class IsolatedScanFixture private constructor(
        private val context: Context,
        val suffix: String,
        private val databaseName: String,
        private val dataStoreFile: File,
        private val dataStoreJob: Job,
        var database: CodeMatchDatabase,
        var history: HistoryRepository,
        val settings: SettingsRepository,
    ) {
        fun createViewModel(): Pair<TestViewModelOwner, ScanViewModel> {
            val owner = TestViewModelOwner()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == ScanViewModel::class.java)
                    return ScanViewModel(
                        historyRepository = history,
                        settingsRepository = settings,
                        scanner = FakeExternalScanner(),
                        feedbackPlayer = FeedbackPlayer(context),
                        scanLogRepository = ScanLogRepository(database),
                    ) as T
                }
            }
            val viewModel = ViewModelProvider(owner, factory)[ScanViewModel::class.java]
            owner.viewModelJob = viewModel.viewModelScope.coroutineContext.job
            return owner to viewModel
        }

        fun reopenDatabase() {
            database.close()
            database = CodeMatchDatabaseFactory.create(context, databaseName)
            history = HistoryRepository(database)
        }

        suspend fun close() {
            database.close()
            dataStoreJob.cancelAndJoin()
            context.deleteDatabase(databaseName)
            dataStoreFile.delete()
        }

        companion object {
            fun open(): IsolatedScanFixture {
                val context: Context = ApplicationProvider.getApplicationContext()
                val suffix = UUID.randomUUID().toString()
                val databaseName = "scan-vm-$suffix.db"
                val dataStoreFile = File(
                    context.cacheDir,
                    "scan-vm-$suffix.preferences_pb",
                )
                val dataStoreJob = SupervisorJob()
                val dataStoreScope = CoroutineScope(Dispatchers.IO + dataStoreJob)
                val dataStore = PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { dataStoreFile },
                )
                val database = CodeMatchDatabaseFactory.create(context, databaseName)
                return IsolatedScanFixture(
                    context = context,
                    suffix = suffix,
                    databaseName = databaseName,
                    dataStoreFile = dataStoreFile,
                    dataStoreJob = dataStoreJob,
                    database = database,
                    history = HistoryRepository(database),
                    settings = SettingsRepository(dataStore),
                )
            }
        }
    }
}
