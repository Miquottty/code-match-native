package jp.rimtty.codematch.scanner.inateck

/**
 * Result of the once-per-connection buffer maintenance (#155), for the
 * diagnostics log only. It carries counts, never a scanned value.
 *
 * [stored] is the number of reads the scanner held when the app connected
 * (null when the scanner did not answer the count), [cleared] whether the
 * erase command was acknowledged, and [remaining] the count read back after it.
 */
internal data class InateckCacheOutcome(
    val stored: Int?,
    val cleared: Boolean = false,
    val remaining: Int? = null,
) {
    fun diagnosticLine(): String = when {
        stored == null -> "Scanner cache: unavailable"
        stored <= 0 -> "Scanner cache: 0 stored"
        !cleared -> "Scanner cache: $stored stored, clear failed"
        remaining == null -> "Scanner cache: $stored stored, cleared, remain unknown"
        else -> "Scanner cache: $stored stored, cleared, $remaining remain"
    }

    companion object {
        const val TIMED_OUT_LINE = "Scanner cache check timed out"
    }
}

/**
 * The buffer-related settings as the scanner reported them before tuning
 * wrote anything, for the diagnostics log. A value the scanner does not
 * report is null and printed as `unknown`. The wording matches iOS.
 */
internal data class InateckDataModes(
    val inventory: Int?,
    val autoUpload: Int?,
    val clearAtStartup: Int?,
    val doubleClick: Int?,
) {
    fun diagnosticLine(): String =
        "Scanner data modes: inventory=${inventory.text()} autoUpload=${autoUpload.text()} " +
            "clearAtStartup=${clearAtStartup.text()} doubleClick=${doubleClick.text()}"

    private fun Int?.text(): String = this?.toString() ?: "unknown"

    companion object {
        fun from(inventory: List<Map<String, String>>): InateckDataModes {
            fun value(name: String): Int? =
                inventory.firstOrNull { it["name"] == name }?.get("value")?.toIntOrNull()
            return InateckDataModes(
                inventory = value("inventory_mode"),
                autoUpload = value("auto_upload_cache"),
                clearAtStartup = value("start_up_clean_cache"),
                doubleClick = value("d_click_upload_flag"),
            )
        }
    }
}
