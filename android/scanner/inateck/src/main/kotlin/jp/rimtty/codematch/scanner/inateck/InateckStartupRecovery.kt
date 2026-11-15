package jp.rimtty.codematch.scanner.inateck

/**
 * Prevents duplicate automatic reconnect requests while the app remains in
 * one foreground epoch.
 *
 * ProcessLifecycleOwner can deliver an initial [onForeground] callback while
 * the scanner is being constructed and can deliver another callback when a
 * host is recreated. The reconnect operation itself is idempotent only after
 * a session has been bound, so the lifecycle edge is kept explicit here. A
 * failed request is deliberately not treated as permanent: returning to the
 * foreground after permissions or Bluetooth have recovered gets one new
 * attempt.
 */
internal class InateckStartupRecovery(
    private val reconnectKnownDevice: () -> Boolean,
) {
    private var foreground = false

    /** Requests recovery once for the current foreground epoch. */
    fun onForeground(): Boolean {
        if (foreground) return false
        foreground = true
        return reconnectKnownDevice()
    }

    /** Ends the epoch so the next foreground can retry recovery. */
    fun onBackground() {
        foreground = false
    }
}

/** What automatic recovery does when the app returns to the foreground. */
internal enum class InateckRecoveryDecision(
    /** Payload-free diagnostic line for a skip worth explaining, else null. */
    val diagnosticLine: String? = null,
) {
    RECONNECT,
    SKIP_CLOSED,
    SKIP_GATT_SWITCH,
    SKIP_OPERATOR_SELECTION,
    SKIP_CONNECTING,
    SKIP_OPERATOR_DISCONNECTED(
        "Automatic reconnect skipped: the operator disconnected the scanner",
    ),
    ;

    val reconnects: Boolean get() = this == RECONNECT
}

/**
 * Decides whether automatic recovery may reconnect the known scanner (#137).
 *
 * It never redirects or resets a connection the operator or the GATT-switch
 * follow-up has already started, and it never undoes the operator's
 * Disconnect: that lasts until the operator connects again, across foreground
 * returns and process restarts. Kept free of Android types so the whole table
 * is unit-tested; the scanner only supplies its current state.
 */
internal object InateckRecoveryPolicy {
    fun decide(
        closed: Boolean,
        gattSwitchActive: Boolean,
        selectionQueued: Boolean,
        connecting: Boolean,
        operatorDisconnected: Boolean,
    ): InateckRecoveryDecision = when {
        closed -> InateckRecoveryDecision.SKIP_CLOSED
        gattSwitchActive -> InateckRecoveryDecision.SKIP_GATT_SWITCH
        selectionQueued -> InateckRecoveryDecision.SKIP_OPERATOR_SELECTION
        connecting -> InateckRecoveryDecision.SKIP_CONNECTING
        operatorDisconnected -> InateckRecoveryDecision.SKIP_OPERATOR_DISCONNECTED
        else -> InateckRecoveryDecision.RECONNECT
    }
}
