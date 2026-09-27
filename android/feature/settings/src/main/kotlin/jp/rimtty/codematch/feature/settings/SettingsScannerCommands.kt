package jp.rimtty.codematch.feature.settings

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Scanner requests issued by the Settings card (#137).
 *
 * One scanner is used at a time and the operator's choice always wins: Connect
 * targets the chosen device, and Reconnect/Retry target the device selected in
 * the list when there is one, falling back to the adapter's known device only
 * when nothing is selected. Each function returns false only when the adapter
 * refused a connection request, so the host can surface it instead of
 * silently dropping the tap.
 */
object SettingsScannerCommands {
    fun connect(scanner: ExternalScanner, device: ScannerDevice): Boolean =
        scanner.connect(device)

    fun reconnect(scanner: ExternalScanner, state: SettingsUiState): Boolean {
        val target = state.reconnectTarget
        return if (target != null) scanner.connect(target) else scanner.reconnectKnownDevice()
    }

    fun retry(scanner: ExternalScanner, state: SettingsUiState): Boolean {
        val connection = scanner.connectionState
        return when {
            connection is ConnectionState.Searching -> {
                scanner.stopDiscovery()
                scanner.startDiscovery()
                true
            }
            connection.isConnected &&
                scanner.configurationState is ConfigurationState.Failed -> {
                // A failed recovery/configuration must not be marked Ready by
                // the UI. Reconnect is the adapter-neutral way to obtain a
                // fresh handshake of the connected scanner.
                scanner.disconnect()
                scanner.reconnectKnownDevice()
            }
            // A connected/ready scanner has nothing to retry. Keep this
            // branch side-effect free so a stale button cannot rewrite
            // scanner settings.
            connection.isConnected -> true
            else -> {
                val target = state.reconnectTarget
                if (target != null) {
                    scanner.connect(target)
                } else {
                    if (!scanner.reconnectKnownDevice()) scanner.startDiscovery()
                    true
                }
            }
        }
    }
}
