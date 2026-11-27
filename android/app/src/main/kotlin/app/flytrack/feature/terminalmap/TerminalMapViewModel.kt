package app.flytrack.feature.terminalmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TerminalMapContent {
    data object Loading : TerminalMapContent
    data object NotAvailable : TerminalMapContent
    data class Ready(
        val data: TerminalMapData,
        /** The flight's gate on this map, when the map knows it. */
        val myGate: TerminalPlace?,
        /** The arrival belt area, when known and mapped. */
        val myBelt: TerminalPlace?,
    ) : TerminalMapContent
}

data class TerminalMapUiState(
    val iata: String = "",
    /** The gate / belt as the flight gives them, shown even when the map does not have them. */
    val gate: String? = null,
    val belt: String? = null,
    val content: TerminalMapContent = TerminalMapContent.Loading,
    val query: String = "",
    val category: TerminalKind? = null,
    val selectedId: String? = null,
)

@HiltViewModel
class TerminalMapViewModel @Inject constructor(
    private val repository: TerminalMapRepository,
) : ViewModel() {
    private val state = MutableStateFlow(TerminalMapUiState())
    val uiState: StateFlow<TerminalMapUiState> = state.asStateFlow()

    fun start(iata: String, gate: String?, belt: String?) {
        val code = iata.uppercase()
        val s = state.value
        if (s.iata == code && s.gate == gate && s.belt == belt && s.content != TerminalMapContent.Loading) return
        state.value = TerminalMapUiState(iata = code, gate = gate?.trim()?.ifEmpty { null }, belt = belt?.trim()?.ifEmpty { null })
        viewModelScope.launch {
            val content = when (val load = repository.load(code)) {
                is TerminalMapLoad.Ready -> TerminalMapContent.Ready(
                    data = load.data,
                    myGate = TerminalMapLogic.findGate(load.data.gates, gate),
                    myBelt = TerminalMapLogic.findBelt(load.data.beltAreas, belt),
                )
                TerminalMapLoad.NotAvailable, TerminalMapLoad.Broken -> TerminalMapContent.NotAvailable
            }
            state.update { it.copy(content = content) }
        }
    }

    fun setQuery(query: String) = state.update { it.copy(query = query) }

    fun setCategory(category: TerminalKind?) = state.update { it.copy(category = if (it.category == category) null else category) }

    fun select(id: String?) = state.update { it.copy(selectedId = if (it.selectedId == id) null else id) }
}
