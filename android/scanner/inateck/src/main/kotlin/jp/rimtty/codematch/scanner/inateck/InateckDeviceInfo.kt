package jp.rimtty.codematch.scanner.inateck

/**
 * Version, hardware and battery as the scanner reported them, for the
 * diagnostics log only. A value the scanner did not answer is null.
 */
internal data class InateckDeviceInfo(
    val version: String?,
    val hardware: String?,
    val battery: Int?,
) {
    fun diagnosticLines(): List<String> = listOf(
        "Scanner version: ${version.asDiagnosticValue()}",
        "Scanner hardware: ${hardware.asDiagnosticValue()}",
        "Scanner battery: ${battery?.toString() ?: UNAVAILABLE}",
    )

    private fun String?.asDiagnosticValue(): String =
        this?.lines()?.joinToString(" ") { it.trim() }?.trim()?.take(MAX_VALUE_LENGTH)
            ?.takeIf { it.isNotEmpty() } ?: UNAVAILABLE

    private companion object {
        const val UNAVAILABLE = "unavailable"
        const val MAX_VALUE_LENGTH = 80
    }
}
