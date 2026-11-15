package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckStartupRecoveryTest {
    @Test
    fun reconnectIsRequestedOncePerForegroundEpoch() {
        var calls = 0
        val recovery = InateckStartupRecovery {
            calls++
            true
        }

        assertTrue(recovery.onForeground())
        assertFalse(recovery.onForeground())
        assertEquals(1, calls)

        recovery.onBackground()
        assertTrue(recovery.onForeground())
        assertEquals(2, calls)
    }

    @Test
    fun failedAttemptCanRetryAfterBluetoothOrPermissionRecovery() {
        var calls = 0
        val recovery = InateckStartupRecovery {
            calls++
            false
        }

        assertFalse(recovery.onForeground())
        assertFalse(recovery.onForeground())
        assertEquals(1, calls)

        recovery.onBackground()
        assertFalse(recovery.onForeground())
        assertEquals(2, calls)
    }

    @Test
    fun recoveryPolicyTableReconnectsOnlyWhenNothingHoldsItBack() {
        // closed, GATT switch, queued selection, connecting, operator disconnected
        val table = listOf(
            Row(false, false, false, false, false, InateckRecoveryDecision.RECONNECT),
            Row(true, false, false, false, false, InateckRecoveryDecision.SKIP_CLOSED),
            Row(false, true, false, false, false, InateckRecoveryDecision.SKIP_GATT_SWITCH),
            Row(false, false, true, false, false, InateckRecoveryDecision.SKIP_OPERATOR_SELECTION),
            Row(false, false, false, true, false, InateckRecoveryDecision.SKIP_CONNECTING),
            Row(false, false, false, false, true, InateckRecoveryDecision.SKIP_OPERATOR_DISCONNECTED),
            // Several reasons at once: the earlier column names the skip.
            Row(true, true, true, true, true, InateckRecoveryDecision.SKIP_CLOSED),
            Row(false, true, true, true, true, InateckRecoveryDecision.SKIP_GATT_SWITCH),
            Row(false, false, true, true, true, InateckRecoveryDecision.SKIP_OPERATOR_SELECTION),
            Row(false, false, false, true, true, InateckRecoveryDecision.SKIP_CONNECTING),
        )
        table.forEach { row ->
            assertEquals(
                row.toString(),
                row.expected,
                InateckRecoveryPolicy.decide(
                    closed = row.closed,
                    gattSwitchActive = row.gattSwitchActive,
                    selectionQueued = row.selectionQueued,
                    connecting = row.connecting,
                    operatorDisconnected = row.operatorDisconnected,
                ),
            )
        }
    }

    @Test
    fun everyCombinationWithAnyReasonSkipsTheReconnect() {
        val flags = listOf(false, true)
        for (closed in flags) for (gatt in flags) for (queued in flags)
            for (connecting in flags) for (disconnected in flags) {
                val decision = InateckRecoveryPolicy.decide(
                    closed = closed,
                    gattSwitchActive = gatt,
                    selectionQueued = queued,
                    connecting = connecting,
                    operatorDisconnected = disconnected,
                )
                val anyReason = closed || gatt || queued || connecting || disconnected
                assertEquals(!anyReason, decision.reconnects)
            }
    }

    @Test
    fun onlyTheOperatorDisconnectSkipIsExplainedInTheDiagnostics() {
        InateckRecoveryDecision.entries.forEach { decision ->
            if (decision == InateckRecoveryDecision.SKIP_OPERATOR_DISCONNECTED) {
                assertEquals(
                    "Automatic reconnect skipped: the operator disconnected the scanner",
                    decision.diagnosticLine,
                )
            } else {
                assertEquals(null, decision.diagnosticLine)
            }
        }
    }

    @Test
    fun operatorDisconnectStaysSkippedOnEveryForegroundUntilItIsCleared() {
        var operatorDisconnected = true
        var reconnects = 0
        val recovery = InateckStartupRecovery {
            val decision = InateckRecoveryPolicy.decide(
                closed = false,
                gattSwitchActive = false,
                selectionQueued = false,
                connecting = false,
                operatorDisconnected = operatorDisconnected,
            )
            if (decision.reconnects) reconnects++
            decision.reconnects
        }

        repeat(3) {
            assertFalse(recovery.onForeground())
            recovery.onBackground()
        }
        assertEquals(0, reconnects)

        operatorDisconnected = false
        assertTrue(recovery.onForeground())
        assertEquals(1, reconnects)
    }

    private data class Row(
        val closed: Boolean,
        val gattSwitchActive: Boolean,
        val selectionQueued: Boolean,
        val connecting: Boolean,
        val operatorDisconnected: Boolean,
        val expected: InateckRecoveryDecision,
    )
}
