package com.example.kotlinroomdatabase.fragments.list

import android.app.Dialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.BottomSheetAttendancePhotosBinding
import com.example.kotlinroomdatabase.model.AttendancePhotoFace
import com.example.kotlinroomdatabase.repository.GenericResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AttendancePhotosBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAttendancePhotosBinding? = null
    private val binding get() = _binding!!
    private val model: AttendancePhotoViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AttendancePhotoViewModel(requireActivity().application, requireArguments().getInt(LESSON_ID)) as T
            }
        }
    }
    private lateinit var faceAdapter: AttendancePhotoFaceAdapter
    private var groupLabels = emptyList<String>()
    private var photoLabels = emptyList<String>()
    private var notifiedVersion = 0
    private var previewDialog: Dialog? = null
    private var chooserDialog: Dialog? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.upload(uris)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), R.style.FullScreenBottomSheetDialog)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAttendancePhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = false
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        groupLabels = emptyList()
        photoLabels = emptyList()
        val padding = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            target.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        faceAdapter = AttendancePhotoFaceAdapter(viewLifecycleOwner.lifecycleScope, ::chooseStudent, ::previewFace)
        binding.faces.layoutManager = LinearLayoutManager(requireContext())
        binding.faces.adapter = faceAdapter
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnUpload.setOnClickListener { photoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
        binding.btnRefresh.setOnClickListener { model.refreshNow() }
        binding.btnConfirm.setOnClickListener { model.confirm() }
        binding.btnRetry.setOnClickListener { model.retry() }
        binding.btnOriginal.setOnClickListener { previewOriginal() }
        binding.acGroup.setOnItemClickListener { _, _, position, _ ->
            model.state.value.groups.getOrNull(position)?.let { model.selectGroup(it.id) }
        }
        binding.acPhoto.setOnItemClickListener { _, _, position, _ ->
            model.state.value.photos?.jobs?.getOrNull(position)?.let { model.selectJob(it.job_id) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect { render(it) } }
                launch {
                    while (true) { model.refresh(); delay(10_000) }
                }
            }
        }
    }

    private fun render(state: AttendancePhotoState) {
        val groups = state.groups
        val newGroupLabels = groups.map { it.name }
        if (groupLabels != newGroupLabels) {
            groupLabels = newGroupLabels
            binding.acGroup.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, groupLabels))
        }
        binding.acGroup.setText(groups.firstOrNull { it.id == state.groupId }?.name.orEmpty(), false)
        val jobs = state.photos?.jobs.orEmpty()
        val newPhotoLabels = jobs.mapIndexed { index, job ->
            "Фото ${jobs.size - index} · ${state.roster.firstOrNull { it.group_id == job.group_id }?.group_name.orEmpty()} · ${stateLabel(job.state)}"
        }
        if (photoLabels != newPhotoLabels) {
            photoLabels = newPhotoLabels
            binding.acPhoto.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, photoLabels))
        }
        binding.acPhoto.setText(photoLabels.getOrNull(jobs.indexOfFirst { it.job_id == state.jobId }).orEmpty(), false)
        binding.layoutPhotoSelector.isVisible = jobs.isNotEmpty()
        binding.acGroup.isEnabled = !state.busy && groups.isNotEmpty()
        binding.acPhoto.isEnabled = !state.busy
        binding.btnUpload.isEnabled = !state.busy && !state.refreshing && state.groupId in groups.map { it.id } && state.photos?.enabled == true
        binding.btnRefresh.isEnabled = !state.busy && !state.refreshing
        binding.progress.isVisible = state.busy || state.refreshing || state.job?.state in listOf("queued", "processing")
        val job = state.job
        binding.tvState.text = when {
            state.photos == null -> "Проверяем доступность распознавания…"
            !state.photos.enabled -> "Распознавание недоступно для групп этого занятия"
            job == null -> "Выберите группу и загрузите фотографии"
            else -> "${stateLabel(job.state)} · лиц: ${job.face_count}"
        }
        binding.btnOriginal.isVisible = job != null
        binding.btnOriginal.isEnabled = !state.busy
        binding.btnRetry.isVisible = job?.state == "failed"
        binding.btnRetry.isEnabled = !state.busy && !state.refreshing
        val message = state.error.ifBlank { state.notice }.ifBlank {
            if (state.photos?.enabled == false) "Администратор включает распознавание для группы после получения письменных согласий. Посещаемость можно отметить вручную."
            else if (job?.state == "failed") "Не удалось обработать фото. Повторите обработку."
            else if (job?.state == "ready" && job.face_count == 0) "Лиц не найдено. Попробуйте более чёткий снимок или отметьте студентов вручную."
            else if (job?.state == "confirmed") "Отметки сохранены. Исправить посещаемость можно в списке студентов."
            else ""
        }
        binding.tvMessage.text = message
        binding.tvMessage.isVisible = message.isNotBlank()

        val recorded = model.drafts.alreadyRecorded(state.roster, jobs)
        val decisions = job?.let { model.drafts.decisions(it) }.orEmpty().associate { it.face_id to it.student_id }
        val pool = state.roster.filter { it.group_id == job?.group_id }.associateBy { it.student_id }
        faceAdapter.submitList(if (job?.state in listOf("ready", "confirmed")) job!!.faces.map { face ->
            val studentId = decisions[face.face_id] ?: 0
            val hint = when {
                job.state == "confirmed" -> if (studentId > 0) "Отметка сохранена" else "Лицо пропущено"
                studentId > 0 && studentId in recorded -> "Уже учтён на занятии · повтор не увеличит посещаемость"
                face.student_id > 0 -> "Сервис предложил имя — проверьте его"
                else -> "Выберите студента, если узнали его"
            } + if (!face.trainable && !face.learning_allowed) "\nНечёткое лицо: отметку можно сохранить, для обучения нужен более чёткий снимок." else ""
            AttendancePhotoFaceRow(job.job_id, face, pool[studentId]?.student_name ?: "Не определён / пропустить", hint,
                job.state == "ready" && !state.busy)
        } else emptyList())

        val ids = decisions.values.filter { it > 0 }.toSet()
        val repeated = ids.count { it in recorded }
        val invalid = job?.let { model.drafts.validationError(it, state.roster) }
        binding.tvSummary.text = when {
            job?.state != "ready" -> "Повторы на нескольких фото учитываются один раз"
            invalid != null && job.faces.size == job.face_count && job.face_count > 0 -> invalid
            else -> "Выбрано: ${ids.size} · новых: ${ids.size - repeated} · уже учтённых: $repeated\nЛица без имени будут пропущены"
        }
        val confirmationRetry = job?.state == "confirmed" && job.job_id == state.confirmationRetryJobId
        binding.btnConfirm.isVisible = (job?.state == "ready" && job.face_count > 0) || confirmationRetry
        binding.btnConfirm.isEnabled = !state.busy && !state.refreshing && (invalid == null || confirmationRetry)
        if (state.confirmationVersion > notifiedVersion) {
            notifiedVersion = state.confirmationVersion
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply { putInt(LESSON_ID, model.lessonId) })
        }
    }

    private data class StudentChoice(val id: Int, val label: String) { override fun toString() = label }

    private fun chooseStudent(faceId: Int) {
        val state = model.state.value
        val job = state.job ?: return
        if (state.busy || job.state != "ready") return
        val recorded = model.drafts.alreadyRecorded(state.roster, state.photos?.jobs.orEmpty())
        val choices = listOf(StudentChoice(0, "Не определён / пропустить")) +
            state.roster.filter { it.group_id == job.group_id }.sortedBy { it.student_name }.map {
                StudentChoice(it.student_id, it.student_name + if (it.student_id in recorded) " · уже учтён" else "")
            }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, choices)
        val search = EditText(requireContext()).apply {
            hint = "Поиск студента"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            doOnTextChanged { text, _, _, _ -> adapter.filter.filter(text) }
        }
        chooserDialog?.dismiss()
        chooserDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Лицо ${faceId + 1}")
            .setView(search).setAdapter(adapter) { dialog, index ->
                // Polling may finish this job elsewhere while the chooser is open.
                if (model.state.value.jobId == job.job_id) adapter.getItem(index)?.let { model.choose(faceId, it.id) }
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun previewFace(face: AttendancePhotoFace) {
        viewLifecycleOwner.lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                withContext(Dispatchers.Default) {
                    bitmap = AttendancePhotoFaceAdapter.decodePhoto(face.preview_crop.ifBlank { face.crop }, 1024)
                }
                bitmap?.let { showPreview(it, "Лицо ${face.face_id + 1}"); bitmap = null }
            } finally { bitmap?.recycle() }
        }
    }

    private fun previewOriginal() {
        val jobId = model.state.value.jobId
        if (jobId.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnOriginal.isEnabled = false
            try {
                when (val result = model.originalPhoto(jobId)) {
                    is GenericResult.Success -> {
                        var bitmap: Bitmap? = null
                        try {
                            withContext(Dispatchers.Default) { bitmap = AttendancePhotoFaceAdapter.decodePhotoBytes(result.data, 2048) }
                            if (model.state.value.jobId == jobId) bitmap?.let { showPreview(it, "Фотография группы"); bitmap = null }
                        } finally { bitmap?.recycle() }
                    }
                    is GenericResult.Error -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Исходное фото недоступно. Оно хранится на сервере 7 дней; найденные лица доступны в списке.")
                        .setPositiveButton("Понятно", null).show()
                }
            } finally { _binding?.btnOriginal?.isEnabled = true }
        }
    }

    private fun showPreview(bitmap: Bitmap, title: String) {
        previewDialog?.dismiss()
        val image = ImageView(requireContext()).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        previewDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(image)
            .setPositiveButton("Закрыть", null).create().apply {
                setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() }
                show()
            }
    }

    override fun onDestroyView() {
        previewDialog?.dismiss()
        chooserDialog?.dismiss()
        previewDialog = null
        chooserDialog = null
        binding.faces.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "attendance_photos"
        const val RESULT_KEY = "attendance_photos_confirmed"
        const val LESSON_ID = "lesson_id"
        fun newInstance(lessonId: Int) = AttendancePhotosBottomSheet().apply {
            arguments = Bundle().apply { putInt(LESSON_ID, lessonId) }
        }
        private fun stateLabel(state: String) = when (state) {
            "queued" -> "В очереди"
            "processing" -> "Обрабатывается"
            "ready" -> "Проверьте имена"
            "confirmed" -> "Отметки сохранены"
            "failed" -> "Не удалось обработать"
            else -> "Ожидание"
        }
    }
}
