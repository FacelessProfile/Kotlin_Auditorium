package com.example.kotlinroomdatabase.fragments.list

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.model.*
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.AttendancePhotoDrafts
import com.example.kotlinroomdatabase.util.AttendancePhotoFile
import com.example.kotlinroomdatabase.util.AttendancePhotoErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AttendancePhotoState(
    val photos: AttendancePhotos? = null,
    val roster: List<AttendanceRosterStudent> = emptyList(),
    val groupId: Int = 0,
    val jobId: String = "",
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
    val notice: String = "",
    val confirmationVersion: Int = 0,
    val confirmationRetryJobId: String = "",
    val draftVersion: Int = 0
) {
    val job get() = photos?.jobs?.firstOrNull { it.job_id == jobId }
    val groups get() = roster.filter { it.group_id in photos?.allowed_group_ids.orEmpty() }
        .distinctBy { it.group_id }.map { TeacherGroup(it.group_id, it.group_name) }
}

class AttendancePhotoViewModel(application: Application, val lessonId: Int) : AndroidViewModel(application) {
    private val repository: IStudentRepository = StudentRepositoryHTTPS(application,
        StudentDatabase.getInstance(application).studentDao())
    val drafts = AttendancePhotoDrafts()
    private val mutableState = MutableStateFlow(AttendancePhotoState())
    val state = mutableState.asStateFlow()
    private var lastRefreshError = ""

    suspend fun refresh() {
        if (mutableState.value.busy || mutableState.value.refreshing) return
        val wanted = mutableState.value.jobId
        mutableState.update { it.copy(refreshing = true) }
        try {
            val roster = repository.getAttendanceSessionRoster(lessonId)
            val photos = repository.getAttendancePhotos(lessonId, wanted)
            if (wanted != mutableState.value.jobId || mutableState.value.busy) return
            if (photos is GenericResult.Success && roster is GenericResult.Success) {
                val groups = roster.data.students.filter { it.group_id in photos.data.allowed_group_ids }
                    .map { it.group_id }.distinct()
                mutableState.update { current ->
                    current.copy(photos = photos.data, roster = roster.data.students,
                        error = current.error.takeUnless { it == lastRefreshError }.orEmpty(),
                        jobId = wanted.takeIf { id -> photos.data.jobs.any { it.job_id == id } }
                            ?: photos.data.jobs.firstOrNull()?.job_id.orEmpty(),
                        groupId = current.groupId.takeIf { it in groups } ?: groups.singleOrNull() ?: 0)
                }
                lastRefreshError = ""
            } else {
                lastRefreshError = (photos as? GenericResult.Error)?.message
                    ?: (roster as? GenericResult.Error)?.message.orEmpty()
                mutableState.update { it.copy(photos = null, roster = emptyList(), error = lastRefreshError) }
            }
        } finally {
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    fun refreshNow() = viewModelScope.launch { refresh() }

    fun selectGroup(groupId: Int) {
        if (!mutableState.value.busy) mutableState.update { it.copy(groupId = groupId) }
    }

    fun selectJob(jobId: String) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(jobId = jobId, error = "", notice = "") }
        viewModelScope.launch {
            // A response for the previous selection must finish before fetching this photo's faces.
            while (mutableState.value.refreshing) kotlinx.coroutines.delay(50)
            refresh()
        }
    }

    fun choose(faceId: Int, studentId: Int) {
        val current = mutableState.value
        if (current.busy || current.job?.state != "ready") return
        drafts.choose(current.jobId, faceId, studentId)
        mutableState.update { it.copy(draftVersion = it.draftVersion + 1) }
    }

    fun upload(uris: List<Uri>) {
        val current = mutableState.value
        if (current.busy || current.refreshing || current.groupId <= 0 || uris.isEmpty()) return
        if (uris.size > 10) {
            mutableState.update { it.copy(error = "Выберите до 10 фото за раз") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", notice = "") }
            var accepted = 0
            var duplicates = 0
            try {
                for ((index, uri) in uris.distinct().withIndex()) {
                    mutableState.update { it.copy(notice = "Загружаем фото ${index + 1} из ${uris.distinct().size}…") }
                    AttendancePhotoFile.use(getApplication(), uri) { file ->
                        when (val result = repository.uploadAttendancePhoto(lessonId, current.groupId, file)) {
                            is GenericResult.Success -> {
                                accepted++
                                if (result.data.duplicate) duplicates++
                                mutableState.update { it.copy(jobId = result.data.job_id) }
                            }
                            is GenericResult.Error -> error(result.message)
                        }
                    }
                }
                mutableState.update { it.copy(notice = "Фото принято: $accepted. " +
                    (if (duplicates > 0) "Уже загруженных: $duplicates. " else "") +
                    "Дождитесь обработки и проверьте имена.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = "Принято фото: $accepted. ${failure.message.orEmpty()}", notice = "") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            refresh()
        }
    }

    fun confirm() {
        val current = mutableState.value
        val job = current.job ?: return
        if (current.busy || current.refreshing) return
        val isRetry = job.state == "confirmed" && current.confirmationRetryJobId == job.job_id
        val invalid = if (isRetry) null else drafts.validationError(job, current.roster)
        if (invalid != null) {
            mutableState.update { it.copy(error = invalid) }
            return
        }
        val decisions = drafts.decisions(job)
        val ids = decisions.map { it.student_id }.filter { it > 0 }.toSet()
        val recorded = drafts.alreadyRecorded(current.roster, current.photos?.jobs.orEmpty())
        val repeated = ids.count { it in recorded }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", notice = "Сохраняем отметки…") }
            try {
                when (val result = repository.confirmAttendancePhoto(lessonId, job.job_id, decisions)) {
                    is GenericResult.Success -> {
                        // Keep the confirmed decisions locally until polling catches up. Identity is student_id,
                        // so overlapping photos and students with the same name cannot inflate the counter.
                        mutableState.update { state -> state.copy(
                            photos = state.photos?.copy(jobs = state.photos.jobs.map {
                                if (it.job_id == job.job_id) it.copy(state = "confirmed", decisions = decisions) else it
                            }),
                            notice = "Отметки сохранены. Новых: ${ids.size - repeated}, уже учтённых: $repeated. " +
                                "Повторы на других фото не увеличивают посещаемость.",
                            confirmationRetryJobId = "",
                            confirmationVersion = state.confirmationVersion + 1)
                        }
                    }
                    is GenericResult.Error -> mutableState.update { it.copy(error = result.message, notice = "",
                        confirmationRetryJobId = if (result.message == AttendancePhotoErrors.GRADE_REFRESH_FAILED) job.job_id else "") }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
            refresh()
        }
    }

    fun retry() {
        val current = mutableState.value
        val job = current.job ?: return
        if (current.busy || current.refreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", notice = "") }
            try {
                when (val result = repository.retryAttendancePhoto(lessonId, job.job_id)) {
                    is GenericResult.Success -> {
                        drafts.clear(job.job_id)
                        mutableState.update { it.copy(notice = "Фото отправлено на повторную обработку. Проверьте имена заново.") }
                    }
                    is GenericResult.Error -> mutableState.update { it.copy(error = result.message) }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
            refresh()
        }
    }

    suspend fun originalPhoto(jobId: String) = repository.getAttendancePhotoImage(lessonId, jobId)
}
