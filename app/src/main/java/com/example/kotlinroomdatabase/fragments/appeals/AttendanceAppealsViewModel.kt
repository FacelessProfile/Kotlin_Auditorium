package com.example.kotlinroomdatabase.fragments.appeals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.kotlinroomdatabase.model.AttendanceAppeal
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.example.kotlinroomdatabase.util.AttendanceAppealRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AttendanceAppealsState(
    val items: List<AttendanceAppeal> = emptyList(),
    val page: Int = 1,
    val filter: String = "",
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

class AttendanceAppealsViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repository = RepositoryZMQ.getStudentRepository(app)
    private val mutableState = MutableStateFlow(AttendanceAppealsState(page = saved["page"] ?: 1, filter = saved["filter"] ?: ""))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun load(page: Int = state.value.page, filter: String = state.value.filter) {
        loadJob?.cancel()
        saved["page"] = page
        saved["filter"] = filter
        mutableState.value = AttendanceAppealsState(page = page, filter = filter, loading = true)
        loadJob = viewModelScope.launch {
            when (val result = repository.getAttendanceAppeals(page, filter)) {
                is GenericResult.Success -> mutableState.value = AttendanceAppealsState(result.data.items, result.data.page, filter, result.data.has_more)
                is GenericResult.Error -> mutableState.value = state.value.copy(loading = false, error = result.message)
            }
        }
    }
}

data class AppealFormState(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

class AttendanceAppealFormViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repository = RepositoryZMQ.getStudentRepository(app)
    private val mutableState = MutableStateFlow(AppealFormState())
    val state = mutableState.asStateFlow()
    var draft: String
        get() = saved["draft"] ?: ""
        set(value) { saved["draft"] = value }
    var selected: String
        get() = saved["selected"] ?: AttendanceAppealRules.requestedStatuses.first { it != saved.get<String>("currentStatus") }
        set(value) { saved["selected"] = value }

    fun submit(decision: String? = null) {
        if (state.value.busy || state.value.done) return
        val isReview = (saved.get<Long>("appealId") ?: 0L) > 0
        val valid = if (isReview) AttendanceAppealRules.validNote(draft) else AttendanceAppealRules.validReason(draft)
        if (!valid) {
            mutableState.value = AppealFormState(error = if (isReview) "Добавьте пояснение: от 3 до 4000 символов." else "Опишите причину: от 10 до 4000 символов.")
            return
        }
        if (isReview && decision == "approved" && saved.get<String>("currentStatus").isNullOrBlank()) {
            mutableState.value = AppealFormState(error = "Текущая отметка недоступна. Обновите список перед одобрением.")
            return
        }
        mutableState.value = AppealFormState(busy = true)
        viewModelScope.launch {
            val result = if (isReview) repository.decideAttendanceAppeal(
                requireNotNull(saved["appealId"]), requireNotNull(decision), draft, saved["currentStatus"]
            ) else repository.createAttendanceAppeal(requireNotNull(saved["entryId"]), selected, draft)
            mutableState.value = when (result) {
                is GenericResult.Success -> AppealFormState(done = true)
                is GenericResult.Error -> AppealFormState(error = result.message)
            }
        }
    }
}
