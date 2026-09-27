package jp.rimtty.codematch.scanner.ble

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSymbologySessionTest {
    private val device = ScannerDevice("scanner-1", "BCST-47")
    private val settingsEndpoint = "settings-endpoint-from-adapter"
    private val profileIdentity = "test-profile-v1"

    @Test
    fun connectedSessionRequiresFreshInventoryAndChangesPhysicalStep() {
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val session = session(transport, store)

        assertTrue(session.onConnected())
        assertEquals(BleSymbologySessionState.LoadingSettings, session.state)
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(ConfigurationState.Ready, session.configurationState)
        assertNotNull(session.currentSnapshot)

        assertTrue(session.startSession(ScanFormat.QR))
        assertEquals(BleSymbologySessionState.ApplyingSession(ScanFormat.QR), session.state)
        assertEquals(1, transport.writeCallbacks.size)
        val sessionCommands = parseCommands(transport.writes.single())
        assertEquals(1, sessionCommands.count { it.asJsonObject.get("value").asString == "1" })
        assertEquals("qrcode_on", sessionCommands.single {
            it.asJsonObject.get("value").asString == "1"
        }.asJsonObject.get("name").asString)
        assertEquals(0, sessionCommands.first { it.asJsonObject.get("name").asString == "code39_on" }
            .asJsonObject.get("value").asString.toInt())
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.SessionReady, session.state)
        assertTrue(session.isReadyForScanning)

        // Switching the step changes the physical restriction before Ready.
        assertTrue(session.startSession(ScanFormat.CODE_128))
        assertEquals(2, transport.writes.size)
        assertFalse(session.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertTrue(session.isReadyForScanning)
        assertEquals(ScanFormat.CODE_128, session.expectedFormat)

        assertTrue(session.endSession())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(1, transport.writeCallbacks.size)
        val restoredCommands = parseCommands(transport.writes.last())
        assertEquals(1, restoredCommands.first { it.asJsonObject.get("name").asString == "code39_on" }
            .asJsonObject.get("value").asString.toInt())
        assertEquals("42", restoredCommands.first { it.asJsonObject.get("name").asString == "qrcode_on" }
            .asJsonObject.get("area").asString)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(ConfigurationState.Ready, session.configurationState)
        assertFalse(session.isReadyForScanning)
        assertNull(store.load(device.id))
        assertNull(session.expectedFormat)
    }

    @Test
    fun onlyLatestPendingStepBecomesReadyAndSameStepDoesNotWrite() {
        val transport = RecordingTransport()
        val session = session(transport, InMemorySymbologySnapshotStore(profileIdentity))
        session.onConnected()
        transport.completeRead(settingsJson())
        session.startSession(ScanFormat.QR)
        session.setExpectedFormat(ScanFormat.CODE_128)
        transport.completeWrite(Result.success(Unit))
        assertFalse(session.isReadyForScanning)
        assertEquals(2, transport.writes.size)
        transport.completeWrite(Result.success(Unit))
        assertTrue(session.isReadyForScanning)
        repeat(10) { assertTrue(session.setExpectedFormat(ScanFormat.CODE_128)) }
        assertEquals(2, transport.writes.size)
        session.setExpectedFormat(ScanFormat.QR)
        assertFalse(session.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertTrue(session.isReadyForScanning)
        assertEquals(BleSymbologyMode.QR_ONLY, session.physicalMode)
    }

    @Test
    fun pendingStepChangesAreCoalescedAndRestoreTakesPriority() {
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val session = session(transport, store)
        session.onConnected()
        transport.completeRead(settingsJson())
        session.startSession(ScanFormat.QR)
        repeat(10) { session.setExpectedFormat(ScanFormat.CODE_128) }
        assertEquals(1, transport.writes.size)
        assertFalse(session.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertEquals(2, transport.writes.size)
        assertFalse(session.isReadyForScanning)
        val commands = parseCommands(transport.writes.last())
        assertEquals(listOf("code128_on"), commands.filter {
            it.asJsonObject.get("value").asString == "1"
        }.map { it.asJsonObject.get("name").asString })
        session.setExpectedFormat(ScanFormat.QR)
        session.endSession()
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(3, transport.writes.size)
        assertFalse(session.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertNull(store.load(device.id))
    }

    @Test
    fun timeoutBlocksCommandsUntilDisconnectResetAndReconnectRestoresSnapshot() {
        var now = 1_000L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val session = session(transport, store, nowMillis = { now })
        session.onConnected()
        transport.completeRead(settingsJson())
        session.startSession(ScanFormat.QR)

        now += 3_000L
        assertTrue(session.tick() is BleCommandTickResult.TimedOut)
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertTrue(session.isCommandBlockedAfterTimeout)
        assertEquals(1, transport.disconnectCalls.size)
        assertFalse(session.startSession(ScanFormat.CODE_128))

        // The owner acknowledges link closure before the queue is released.
        session.onTransportResetCompleted()
        assertEquals(BleSymbologySessionState.AwaitingReconnect, session.state)
        assertFalse(session.isCommandBlockedAfterTimeout)
        transport.discardPendingWrites()

        assertTrue(session.onConnected())
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(2, transport.writes.size)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertNull(store.load(device.id))
        assertFalse(session.isSessionActive)
        assertNull(session.preSessionSnapshot)
        assertNull(session.expectedFormat)
    }

    @Test
    fun snapshotPendingForAnotherDeviceNeitherBlocksNorIsAppliedToThisScanner() {
        // #135: before, another scanner's pending snapshot failed this scanner
        // with "Saved scanner settings belong to another device".
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val other = SymbologySettings.parse("other-scanner", settingsJson())!!
        store.save(other)
        val session = session(transport, store)

        session.onConnected()
        transport.completeRead(settingsJson())

        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(ConfigurationState.Ready, session.configurationState)
        assertEquals(0, transport.writeCallbacks.size)
        assertEquals(other, store.load("other-scanner"))
        assertNull(store.load(device.id))
    }

    @Test
    fun scannerBIsUsableWhileAIsPendingAndAIsRestoredWhenItReturns() {
        val scannerA = ScannerDevice("AA:AA:AA:AA:AA:01", "HPRT-636E")
        val scannerB = ScannerDevice("BB:BB:BB:BB:BB:02", "HPRT-4F5F")
        val store = InMemorySymbologySnapshotStore(profileIdentity)

        // A starts a session (its baseline is saved) and then drops mid-session.
        val transportA = RecordingTransport()
        val sessionA = session(transportA, store, scanner = scannerA)
        assertTrue(sessionA.onConnected())
        transportA.completeRead(settingsJson())
        assertTrue(sessionA.startSession(ScanFormat.QR))
        transportA.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.SessionReady, sessionA.state)
        sessionA.onTransportDisconnected()
        assertTrue(sessionA.isSessionActive)
        val pendingA = store.load(scannerA.id)
        assertNotNull(pendingA)

        // B connects: Ready without any write, and A's snapshot is untouched.
        val transportB = RecordingTransport()
        val sessionB = session(transportB, store, scanner = scannerB)
        assertTrue(sessionB.onConnected())
        transportB.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, sessionB.state)
        assertEquals(ConfigurationState.Ready, sessionB.configurationState)
        assertTrue(transportB.writes.isEmpty())
        assertEquals(pendingA, store.load(scannerA.id))

        // B runs its own session with its own snapshot; ending it clears B only.
        assertTrue(sessionB.startSession(ScanFormat.QR))
        assertEquals(scannerB.id, store.load(scannerB.id)?.deviceId)
        transportB.completeWrite(Result.success(Unit))
        assertTrue(sessionB.isReadyForScanning)
        assertTrue(sessionB.endSession())
        transportB.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, sessionB.state)
        assertNull(store.load(scannerB.id))
        assertEquals(pendingA, store.load(scannerA.id))

        // A comes back: the RECOVERY path restores A's baseline and clears A only.
        val transportA2 = RecordingTransport()
        val reconnectedA = session(transportA2, store, scanner = scannerA)
        assertTrue(reconnectedA.onConnected())
        transportA2.completeRead(restrictedSettingsJson())
        assertEquals(BleSymbologySessionState.Restoring, reconnectedA.state)
        val restored = parseCommands(transportA2.writes.single())
        assertEquals(
            1,
            restored.first { it.asJsonObject.get("name").asString == "code39_on" }
                .asJsonObject.get("value").asString.toInt(),
        )
        transportA2.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, reconnectedA.state)
        assertNull(store.load(scannerA.id))
    }

    @Test
    fun rejectedSnapshotOfThisScannerStillFailsBeforeAnyWrite() {
        val transport = RecordingTransport()
        val backing = InMemorySymbologySnapshotStore(profileIdentity)
        val rejectingStore = object : SymbologySnapshotStore by backing {
            override fun read(deviceId: String): SymbologySnapshotReadResult =
                if (deviceId == device.id) {
                    SymbologySnapshotReadResult.Rejected(
                        BleSymbologySnapshotRejectionReason.CORRUPT,
                    )
                } else {
                    SymbologySnapshotReadResult.Missing
                }
        }
        val session = session(transport, rejectingStore)

        session.onConnected()
        transport.completeRead(settingsJson())

        assertEquals(
            BleSymbologySessionState.Failed(BleSymbologySnapshotRejectionReason.CORRUPT),
            session.state,
        )
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun recoveryRejectsChangedAreaOrFlagInsteadOfApplyingByNameOnly() {
        listOf(
            { item: ScannerSettingItem -> item.copy(area = "changed-area") },
            { item: ScannerSettingItem -> item.copy(flag = 2999) },
        ).forEach { mutateIdentity ->
            val transport = RecordingTransport()
            val store = InMemorySymbologySnapshotStore(profileIdentity)
            val saved = SymbologySettings.parse(device.id, settingsJson())!!
            store.save(
                saved.copy(
                    settings = saved.settings.mapIndexed { index, item ->
                        if (index == 1) mutateIdentity(item) else item
                    },
                ),
            )
            val session = session(transport, store)

            assertTrue(session.onConnected())
            transport.completeRead(settingsJson())

            assertEquals(
                BleSymbologySessionState.Failed("Saved scanner settings no longer match the device inventory"),
                session.state,
            )
            assertEquals(0, transport.writeCallbacks.size)
        }
    }

    @Test
    fun incompleteFreshInventoryCannotStartOrReportReady() {
        val transport = RecordingTransport()
        val session = session(transport, InMemorySymbologySnapshotStore(profileIdentity))

        session.onConnected()
        transport.completeRead("""{"data":[{"area":"1","value":"1","name":"qrcode_on"}]}""")

        assertTrue(session.state is BleSymbologySessionState.Failed)
        assertFalse(session.startSession(ScanFormat.QR))
        assertFalse(session.configurationState.isReady)
        assertTrue(session.diagnosticEvents.none { it.message.contains("qrcode") })
    }

    @Test
    fun profileCodecReceivesRawReadBytesAndOwnsWriteEncoding() {
        val transport = RecordingTransport()
        val snapshot = SymbologySnapshot(
            deviceId = device.id,
            settings = listOf(
                ScannerSettingItem(
                    name = SymbologySettings.QR_NAME,
                    area = "android-qr-area",
                    value = 0,
                    flag = 4001,
                    extraFields = mapOf("opaque" to "0xA5"),
                ),
                ScannerSettingItem(
                    name = SymbologySettings.CODE_128_NAME,
                    area = "android-code128-area",
                    value = 1,
                    flag = 4002,
                    extraFields = mapOf("mode" to "binary"),
                ),
                ScannerSettingItem(
                    name = "vendor_symbol",
                    area = "vendor-area",
                    value = 1,
                    flag = 9001,
                    extraFields = mapOf("vendorField" to "opaque"),
                ),
            ),
        )
        val codec = RecordingCodec(snapshot)
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(settingsEndpoint, codec, profileIdentity),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val rawRead = byteArrayOf(0x00, 0x7F, 0x10, 0xFF.toByte())

        assertTrue(session.onConnected())
        transport.completeRead(rawRead)
        assertArrayEquals(rawRead, codec.lastDecodedPayload)
        assertEquals(BleSymbologySessionState.Ready, session.state)

        assertTrue(session.startSession(ScanFormat.QR))
        assertEquals(1, codec.encodeCalls)
        assertEquals(snapshot.settings.size, codec.lastCommands.size)
        assertEquals(4001, codec.lastCommands.first { it.name == SymbologySettings.QR_NAME }.flag)
        assertEquals("0xA5", codec.lastCommands.first { it.name == SymbologySettings.QR_NAME }
            .extraFields["opaque"])
        assertArrayEquals(codec.encodedPayload, transport.writes.single())
    }

    @Test
    fun codecCannotReturnInventoryForAnotherDevice() {
        val transport = RecordingTransport()
        val wrongDeviceSnapshot = SymbologySettings.parse("other-device", settingsJson())!!
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsEndpoint,
                RecordingCodec(wrongDeviceSnapshot, bindRequestedDeviceId = false),
                profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )

        assertTrue(session.onConnected())
        transport.completeRead(byteArrayOf(1))

        assertEquals(
            BleSymbologySessionState.Failed("Scanner settings belong to another device"),
            session.state,
        )
        assertTrue(transport.writes.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionRejectsSnapshotStoreForAnotherProfile() {
        BleSymbologySession(
            device = device,
            transport = RecordingTransport(),
            profile = BleSymbologyProfile(
                settingsEndpoint,
                IosObservedSymbologyCodec,
                profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore("other-profile"),
        )
    }

    @Test
    fun restoreDoesNotBecomeReadyWhenPersistedSnapshotCannotBeCleared() {
        val transport = RecordingTransport()
        val backing = InMemorySymbologySnapshotStore(profileIdentity)
        val rejectingStore = object : SymbologySnapshotStore by backing {
            override fun clear(deviceId: String): SymbologySnapshotClearResult =
                SymbologySnapshotClearResult.Rejected("clear rejected")
        }
        val session = session(transport, rejectingStore)

        session.onConnected()
        transport.completeRead(settingsJson())
        assertTrue(session.startSession(ScanFormat.QR))
        transport.completeWrite(Result.success(Unit))
        assertTrue(session.endSession())
        transport.completeWrite(Result.success(Unit))

        assertEquals(
            BleSymbologySessionState.Failed("Saved scanner settings could not be cleared"),
            session.state,
        )
        assertFalse(session.configurationState.isReady)
        assertNotNull(backing.load(device.id))
    }

    @Test
    fun settingsReadIsSingleFlightAndTimesOutBeforeAcceptingLateBytes() {
        var now = 1_000L
        val transport = RecordingTransport()
        val session = session(
            transport = transport,
            store = InMemorySymbologySnapshotStore(profileIdentity),
            nowMillis = { now },
        )

        assertTrue(session.onConnected())
        assertTrue(session.isSettingsReadPending)
        assertFalse(session.onConnected())
        assertEquals(1, transport.readCallbacks.size)

        now += 2_999L
        assertEquals(BleCommandTickResult.Noop, session.tick())
        now += 1L
        assertEquals(BleCommandTickResult.Noop, session.tick())
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(session.isSettingsReadPending)
        assertEquals(1, transport.disconnectCalls.size)

        // The old callback is stale after the timeout and cannot make the
        // session Ready or trigger a settings write.
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(session.onConnected())

        session.onTransportResetCompleted()
        assertTrue(session.onConnected())
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, session.state)
    }

    private fun session(
        transport: RecordingTransport,
        store: SymbologySnapshotStore,
        nowMillis: () -> Long = { 0L },
        settingsReadTimeoutMillis: Long = 3_000L,
        scanner: ScannerDevice = device,
    ) = BleSymbologySession(
        device = scanner,
        transport = transport,
        profile = BleSymbologyProfile(
            settingsEndpoint,
            IosObservedSymbologyCodec,
            profileIdentity,
        ),
        snapshotStore = store,
        diagnostics = BleDiagnosticLog(nowMillis = nowMillis),
        nowMillis = nowMillis,
        settingsReadTimeoutMillis = settingsReadTimeoutMillis,
    )

    private fun parseCommands(payload: ByteArray) =
        JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonArray

    private class RecordingTransport : BleTransport {
        override val availability: BleAvailability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        val readCallbacks = mutableListOf<(Result<ByteArray>) -> Unit>()
        val writeCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
        val writes = mutableListOf<ByteArray>()
        val disconnectCalls = mutableListOf<ScannerDevice>()

        override fun startDiscovery(): Boolean = true
        override fun stopDiscovery(): Boolean = true
        override fun connect(device: ScannerDevice): Boolean = true

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls += device
            return true
        }

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            writes += payload.copyOf()
            writeCallbacks += completion
            return true
        }

        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ): Boolean {
            readCallbacks += completion
            return true
        }

        fun completeRead(json: String) {
            completeRead(json.toByteArray(Charsets.UTF_8))
        }

        fun completeRead(payload: ByteArray) {
            readCallbacks.removeAt(0)(Result.success(payload))
        }

        fun completeWrite(result: Result<Unit>) {
            writeCallbacks.removeAt(0)(result)
        }

        fun discardPendingWrites() {
            writeCallbacks.clear()
        }
    }

    private class RecordingCodec(
        private val snapshot: SymbologySnapshot,
        private val bindRequestedDeviceId: Boolean = true,
    ) : BleSymbologyCodec {
        var lastDecodedPayload: ByteArray = byteArrayOf()
        var lastCommands: List<SymbologySettingCommand> = emptyList()
        var encodeCalls: Int = 0
        val encodedPayload = byteArrayOf(0x4B, 0x01, 0x7F)

        override fun decodeSnapshot(
            deviceId: String,
            payload: ByteArray,
            capturedAtMillis: Long,
        ): SymbologySnapshot {
            lastDecodedPayload = payload.copyOf()
            return snapshot.copy(
                deviceId = if (bindRequestedDeviceId) deviceId else snapshot.deviceId,
                capturedAtMillis = capturedAtMillis,
            )
        }

        override fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray {
            encodeCalls++
            lastCommands = commands
            return encodedPayload.copyOf()
        }
    }

    /** The same inventory left restricted to QR only by an unrestored session. */
    private fun restrictedSettingsJson(): String = """
        {"data":[
          {"area":"11","value":"0","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":0,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"99","value":"0","name":"future_symbol","flag":2028,"vendor":"keep"}
        ]}
    """.trimIndent()

    private fun settingsJson(): String = """
        {"data":[
          {"area":"11","value":"1","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"99","value":"1","name":"future_symbol","flag":2028,"vendor":"keep"}
        ]}
    """.trimIndent()
}
