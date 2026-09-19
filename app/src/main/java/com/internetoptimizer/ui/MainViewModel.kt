package com.internetoptimizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.internetoptimizer.app.AppConfig
import com.internetoptimizer.app.ConfigManager
import com.internetoptimizer.app.TunnelState
import com.internetoptimizer.tunnel.TunnelStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the main screen.
 *
 * Holds UI state:
 * - Whether the tunnel is active
 * - Current tunnel status message
 * - Speed test results (before/after comparison)
 * - Navigation events (settings, excluded apps picker)
 *
 * Observes [TunnelStateRepository.tunnelState] to reflect the real
 * tunnel state — not a local toggle. This fixes the bug where the UI
 * always showed "activated" regardless of the actual tunnel status.
 */
class MainViewModel(
    private val configManager: ConfigManager,
) : ViewModel() {

    private val _config = MutableStateFlow(configManager.loadConfig())

    // Derive UI state from the real TunnelStateRepository + config
    val uiState: StateFlow<MainUiState> = combine(
        TunnelStateRepository.tunnelState,
        _config,
    ) { tunnelState, config ->
        MainUiState(
            isTunnelActive = tunnelState == TunnelState.Running,
            tunnelStatus = when (tunnelState) {
                TunnelState.Stopped -> "Desligado"
                TunnelState.Starting -> "Iniciando..."
                TunnelState.Connecting -> "Conectando..."
                TunnelState.Running -> "Ativado"
            },
            config = config,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(
            isTunnelActive = false,
            tunnelStatus = "Desligado",
            config = configManager.loadConfig(),
        ),
    )

    fun loadConfig() {
        val config = configManager.loadConfig()
        _config.value = config
    }

    fun refreshConfig() {
        viewModelScope.launch {
            loadConfig()
        }
    }

    /**
     * Toggle the tunnel ON or OFF.
     *
     * This only updates the repository's expected state; the TunnelService
     * is responsible for actually transitioning and reporting back the
     * real TunnelState. The UI reflects whatever the repository holds.
     */
    fun toggleTunnel() {
        val current = TunnelStateRepository.currentState()
        if (current == TunnelState.Running) {
            // Requesting stop — set Stopped as a transitional hint
            TunnelStateRepository.updateState(TunnelState.Stopped)
        } else {
            // Requesting start — set Starting as a transitional hint
            TunnelStateRepository.updateState(TunnelState.Starting)
        }
    }
}

data class MainUiState(
    val isTunnelActive: Boolean = false,
    val tunnelStatus: String = "Desligado",
    val config: AppConfig = AppConfig(),
    val latencyBeforeMs: Long = -1L,
    val latencyAfterMs: Long = -1L,
    val throughputBeforeMbps: Double = 0.0,
    val throughputAfterMbps: Double = 0.0,
    val showSettings: Boolean = false,
    val showExcludedApps: Boolean = false,
)
