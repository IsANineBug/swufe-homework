package com.swufe.homework.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Period
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.DEFAULT_PERIODS
import com.swufe.homework.data.repo.TimetableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 课表网格上的一个色块。 */
data class GridBlock(val classId: Int, val name: String, val room: String, val weekday: Int, val start: Int, val end: Int)

fun buildBlocks(classes: List<ClassInfo>): List<GridBlock> =
    classes.flatMap { c -> c.slots.map { GridBlock(c.id, c.name, it.room, it.weekday, it.start, it.end) } }
        .filter { it.weekday in 1..5 && it.start in 1..12 && it.end in it.start..12 }
        .sortedWith(compareBy({ it.weekday }, { it.start }))

@HiltViewModel
class TimetableViewModel @Inject constructor(private val repo: TimetableRepository) : ViewModel() {
    val classes: StateFlow<List<ClassInfo>> = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val periods: StateFlow<List<Period>> = repo.periods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_PERIODS)

    private val _aiOnline = MutableStateFlow(false)
    val aiOnline = _aiOnline.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            repo.refreshMeta()
            repo.refreshClasses().onFailure { _error.value = it.userMessage }
            _aiOnline.value = repo.aiOnline()
            _refreshing.value = false
        }
    }
}
