package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Follows a scanner through the connect-time switch to GATT mode (#137,
 * mirroring iOS #134).
 *
 * After the gateway has written the GATT mode and restarted the scanner, the
 * origin's link closes. The origin then stops being the known device (it may
 * never advertise under that identity again), and after [rediscoveryDelayMillis]
 * a search runs. When the search ends:
 * - exactly one [InateckGattMode.successors] candidate: connect it;
 * - none, but the origin itself was seen: reconnect the origin (a model whose
 *   identity does not change);
 * - none at all: search again, at most [roundLimit] rounds;
 * - several candidates, or no round found anything: stop and ask the operator
 *   to pick the scanner from the search results.
 *
 * Pure state machine driven by [tick] from the host's serialized ticker. Any
 * operator connect/disconnect/search must call [cancel] first: the operator's
 * choice always wins.
 */
internal class InateckGattSwitch(
    private val operations: Operations,
    private val nowMillis: () -> Long,
    private val rediscoveryDelayMillis: Long = DEFAULT_REDISCOVERY_DELAY_MILLIS,
    private val roundLimit: Int = DEFAULT_ROUND_LIMIT,
    private val searchStartGraceMillis: Long = DEFAULT_SEARCH_START_GRACE_MILLIS,
) {
    interface Operations {
        /** A pending or established link to any scanner still exists. */
        val isLinkActive: Boolean
        val isSearching: Boolean
        fun forgetKnownDevice(deviceId: String)
        fun startDiscovery(): Boolean
        fun connect(device: ScannerDevice): Boolean
        /** Publish the "pick the scanner from the search results" failure. */
        fun requireManualSelection()
        fun diagnostic(message: String, error: Boolean = false)
    }

    private enum class Phase {
        AWAITING_LINK_CLOSE,
        WAITING_TO_SEARCH,
        SEARCHING,
    }

    private var origin: ScannerDevice? = null
    private var phase = Phase.AWAITING_LINK_CLOSE
    private var round = 0
    private var searchAtMillis = 0L
    private var searchStartedAtMillis = 0L
    private var sawSearching = false
    private val seen = linkedMapOf<String, ScannerDevice>()

    /** True from the switch until a scanner is chosen or the operator acts. */
    val isActive: Boolean
        get() = origin != null

    val switchOrigin: ScannerDevice?
        get() = origin

    fun begin(origin: ScannerDevice) {
        this.origin = origin
        phase = Phase.AWAITING_LINK_CLOSE
        round = 0
        sawSearching = false
        seen.clear()
    }

    fun cancel() {
        origin = null
        seen.clear()
    }

    /** Feed every device reported by the adapter's discovery. */
    fun onDeviceDiscovered(device: ScannerDevice) {
        if (origin != null && phase == Phase.SEARCHING) seen[device.id] = device
    }

    fun tick() {
        val origin = origin ?: return
        when (phase) {
            Phase.AWAITING_LINK_CLOSE -> {
                if (operations.isLinkActive) return
                // The origin may never come back under this identity; stop
                // reconnecting to it. A reconnected origin is remembered again.
                operations.forgetKnownDevice(origin.id)
                scheduleRound("origin link closed")
            }
            Phase.WAITING_TO_SEARCH -> {
                if (nowMillis() < searchAtMillis || operations.isLinkActive) return
                seen.clear()
                sawSearching = false
                if (operations.startDiscovery()) {
                    phase = Phase.SEARCHING
                    searchStartedAtMillis = nowMillis()
                    sawSearching = operations.isSearching
                } else {
                    scheduleRound("search could not start")
                }
            }
            Phase.SEARCHING -> {
                if (operations.isSearching) {
                    sawSearching = true
                    return
                }
                if (!sawSearching && nowMillis() - searchStartedAtMillis < searchStartGraceMillis) {
                    return
                }
                resolve(origin)
            }
        }
    }

    private fun scheduleRound(reason: String) {
        round++
        if (round > roundLimit) {
            abandon()
            return
        }
        phase = Phase.WAITING_TO_SEARCH
        searchAtMillis = nowMillis() + rediscoveryDelayMillis
        operations.diagnostic(
            "GATT mode switch: searching for the restarted scanner (round $round, $reason)",
        )
    }

    private fun resolve(origin: ScannerDevice) {
        val candidates = seen.values.toList()
        val successors = InateckGattMode.successors(origin, candidates)
        when {
            successors.size == 1 -> {
                cancel()
                operations.diagnostic("GATT mode switch: renamed scanner found; connecting")
                operations.connect(successors.single())
            }
            successors.isEmpty() && candidates.any { it.id == origin.id } -> {
                cancel()
                operations.diagnostic("GATT mode switch: no renamed scanner; reconnecting the origin")
                operations.connect(origin)
            }
            successors.isEmpty() -> scheduleRound("scanner not found")
            else -> {
                operations.diagnostic(
                    "GATT mode switch: ${successors.size} candidate scanners",
                )
                abandon()
            }
        }
    }

    private fun abandon() {
        cancel()
        operations.diagnostic(
            "GATT mode switch: restarted scanner not identified; waiting for manual selection",
            error = true,
        )
        operations.requireManualSelection()
    }

    companion object {
        const val DEFAULT_REDISCOVERY_DELAY_MILLIS = 3_000L
        const val DEFAULT_ROUND_LIMIT = 3
        const val DEFAULT_SEARCH_START_GRACE_MILLIS = 2_000L
    }
}
