package jp.rimtty.codematch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import jp.rimtty.codematch.core.data.ScanLogRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.feature.settings.SettingsPresentationState
import jp.rimtty.codematch.feature.settings.SettingsScannerCommands
import jp.rimtty.codematch.feature.settings.SettingsUiAction
import jp.rimtty.codematch.feature.settings.SettingsUiState
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.locale.AppLanguageSynchronizer
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val scanLogRepository: ScanLogRepository,
    private val scanner: ExternalScanner,
    private val feedbackPlayer: FeedbackPlayer,
    private val appLanguageSynchronizer: AppLanguageSynchronizer,
) : ViewModel() {
    /**
     * Scanner callbacks are subscribed through the fan-out API. Assigning the
     * legacy single listener here would detach ScanSessionCoordinator and
     * silently stop scan payload delivery.
     */
    private val scannerListener = object : ExternalScannerListener {
        override fun onIlluminationStateChanged(state: jp.rimtty.codematch.scanner.api.IlluminationState) {
            refreshScannerState()
        }
        override fun onTuningStateChanged(state: jp.rimtty.codematch.scanner.api.TuningState) {
            refreshScannerState()
        }
        override fun onConnectionStateChanged(state: ConnectionState) {
            refreshScannerState()
        }

        override fun onConfigurationStateChanged(state: jp.rimtty.codematch.scanner.api.ConfigurationState) {
            refreshScannerState()
        }
    }

    private var scannerListenerRegistered = false
    private val _state = MutableStateFlow(scannerState(SettingsUiState()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        scannerListenerRegistered = scanner.addListener(scannerListener)
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update { scannerState(it.copy(settings = settings)) }
            }
        }
        viewModelScope.launch {
            scanLogRepository.count.collect { count ->
                _state.update { it.copy(scanLogCount = count) }
            }
        }
    }

    /**
     * The whole scan log, oldest first.
     *
     * Serialization and every Intent/ContentResolver call stay in the route:
     * this only hands over the rows so the host does not need the repository.
     */
    suspend fun exportScanLog(): List<ScanLogEvent> = scanLogRepository.export()

    override fun onCleared() {
        if (scannerListenerRegistered) {
            scanner.removeListener(scannerListener)
            scannerListenerRegistered = false
        }
        super.onCleared()
    }

    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.SetIllumination -> {
                scanner.setIllumination(action.enabled)
                refreshScannerState()
            }
            SettingsUiAction.OpenSetupGuide ->
                _state.update { it.copy(setupGuideVisible = true) }
            SettingsUiAction.CloseSetupGuide ->
                _state.update { it.copy(setupGuideVisible = false) }
            SettingsUiAction.StartDiscovery -> {
                scanner.startDiscovery()
                updateAfterScannerRequest(accepted = true)
            }
            SettingsUiAction.StopDiscovery -> {
                scanner.stopDiscovery()
                updateAfterScannerRequest(accepted = true)
            }
            is SettingsUiAction.SelectDevice ->
                _state.update {
                    it.copy(selectedDeviceId = action.device.id, connectRequestRejected = false)
                }
            is SettingsUiAction.Connect -> {
                // The selection is recorded first so a later Reconnect/Retry
                // targets this scanner (#137).
                _state.update { it.copy(selectedDeviceId = action.device.id) }
                val accepted = SettingsScannerCommands.connect(scanner, action.device)
                updateAfterScannerRequest(accepted)
            }
            SettingsUiAction.Disconnect -> {
                scanner.disconnect()
                updateAfterScannerRequest(accepted = true)
            }
            SettingsUiAction.Reconnect -> {
                val accepted = SettingsScannerCommands.reconnect(scanner, _state.value)
                updateAfterScannerRequest(accepted)
            }
            SettingsUiAction.RetryScanner -> {
                val accepted = SettingsScannerCommands.retry(scanner, _state.value)
                updateAfterScannerRequest(accepted)
            }
            is SettingsUiAction.SetAutoAdvanceEnabled ->
                viewModelScope.launch { repository.setAutoAdvanceEnabled(action.enabled) }
            is SettingsUiAction.SetAutoAdvanceDelay ->
                viewModelScope.launch { repository.setAutoAdvanceDelay(action.delay) }
            is SettingsUiAction.SetFeedbackVolume ->
                viewModelScope.launch { repository.setFeedbackVolume(action.volume) }
            is SettingsUiAction.SetSuccessSound ->
                viewModelScope.launch { repository.setSuccessSound(action.sound) }
            is SettingsUiAction.PreviewSuccessSound ->
                feedbackPlayer.playSuccess(action.sound, state.value.feedbackVolume)
            is SettingsUiAction.SetFailureSound ->
                viewModelScope.launch { repository.setFailureSound(action.sound) }
            is SettingsUiAction.PreviewFailureSound ->
                feedbackPlayer.playFailure(action.sound, state.value.feedbackVolume)
            SettingsUiAction.ShareDiagnostics, SettingsUiAction.SaveDiagnostics -> Unit // host-owned
            SettingsUiAction.ShareScanLog, SettingsUiAction.SaveScanLog -> Unit // host-owned
            SettingsUiAction.ClearScanLog ->
                viewModelScope.launch { scanLogRepository.clear() }
            is SettingsUiAction.SetLanguage -> viewModelScope.launch {
                appLanguageSynchronizer.setLanguage(action.language)
            }
        }
    }

    fun refreshScannerState() {
        _state.update(::scannerState)
    }

    private fun scannerState(current: SettingsUiState): SettingsUiState = current.copy(
        illuminationState = scanner.illuminationState,
        tuningState = scanner.tuningState,
        devices = scanner.devices,
        connectionState = scanner.connectionState,
        configurationState = scanner.configurationState,
        diagnosticEvents = scanner.diagnosticEvents.takeLast(MAX_DIAGNOSTICS),
        scannerIssue = scannerIssueFor(scanner.connectionState, scanner.configurationState),
        presentation = if (!scanner.supportsConnectionControls) {
            SettingsPresentationState.RELEASE_CAMERA_ONLY
        } else {
            SettingsPresentationState.FAKE_BLE
        },
    )

    /** A refused connection request is surfaced, never silently dropped (#137). */
    private fun updateAfterScannerRequest(accepted: Boolean) {
        _state.update { scannerState(it.copy(connectRequestRejected = !accepted)) }
    }

    private companion object {
        /** Retained for share/save; the screen itself renders only the latest 20. */
        const val MAX_DIAGNOSTICS = 300
    }
}
