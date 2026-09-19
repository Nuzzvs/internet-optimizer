package com.internetoptimizer.tunnel

import com.internetoptimizer.app.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton que compartilha o estado do túnel entre o TunnelService
 * e a camada de UI (ViewModel + Compose).
 *
 * O TunnelService atualiza este repositório quando o estado muda
 * (Stopped → Starting → Connecting → Running → Stopped).
 * O ViewModel observa este repositório e propaga as mudanças para
 * a UI via StateFlow.
 *
 * Isso elimina o bug de estado onde a UI mostrava sempre "ativado"
 * independentemente do estado real do túnel.
 */
object TunnelStateRepository {

    private val _tunnelState = MutableStateFlow(TunnelState.Stopped)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    /**
     * Chamado pelo TunnelService para atualizar o estado global do túnel.
     * Qualquer observador (ViewModel, etc) será notificado via StateFlow.
     */
    fun updateState(newState: TunnelState) {
        _tunnelState.value = newState
    }

    /**
     * Estado atual do túnel, para leituras síncronas (e.g. no toggle).
     */
    fun currentState(): TunnelState = _tunnelState.value

    /**
     * Verdadeiro se o túnel está no estado Running.
     */
    fun isRunning(): Boolean = _tunnelState.value == TunnelState.Running
}
