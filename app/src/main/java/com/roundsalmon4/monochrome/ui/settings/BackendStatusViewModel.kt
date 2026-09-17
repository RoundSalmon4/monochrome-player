package com.roundsalmon4.monochrome.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.BackendHealthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackendStatusViewModel @Inject constructor(
    private val checker: BackendHealthChecker
) : ViewModel() {

    data class UiState(
        val results: Map<String, BackendHealthChecker.Result> = emptyMap(),
        val checking: Boolean = false
    )

    val targets: List<BackendHealthChecker.Target> get() = checker.targets

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(results = emptyMap(), checking = true) }
            checker.checkAll { result ->
                _uiState.update { it.copy(results = it.results + (result.target.name to result)) }
            }
            _uiState.update { it.copy(checking = false) }
        }
    }
}