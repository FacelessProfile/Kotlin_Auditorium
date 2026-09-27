package com.example.kotlinroomdatabase.fragments.list

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentListBinding
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.*
import com.example.kotlinroomdatabase.util.SafeNdefManager
import com.example.kotlinroomdatabase.data.ZmqSockets
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File

class ListFragment : NFC_Tools() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = ListAdapter()
    private val prefsName = "StudentPrefs"
    private val selectedGroupKey = "selected_group"

    @OptIn(InternalSerializationApi::class)
    private var studentList: MutableList<Student> = mutableListOf()

    private var toolbarSpinner: Spinner? = null
    private lateinit var studentRepository: IStudentRepository

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: Context) {
        super.onAttach(context)
        val useHttp = true // Можно вынести в настройки
        if (useHttp) {
            val db = StudentDatabase.getInstance(requireContext())
            studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        } else {
            studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    @OptIn(InternalSerializationApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)

        binding.recyclerview.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerview.adapter = adapter
        loadStudents()
        setupSwipeToDelete()
        adapter.setOnItemClickListener { student ->
            val bundle = Bundle()
            bundle.putInt("studentId", student.id)
            findNavController().navigate(R.id.action_listFragment_to_addFragment, bundle)
        }

        binding.floatingActionButton.setOnClickListener {
            findNavController().navigate(R.id.action_listFragment_to_addFragment)
        }

        return binding.root
    }

    @OptIn(InternalSerializationApi::class)
    private fun setupToolbarSpinner() {     // Спинер в тулбаре
        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar
        actionBar?.setDisplayShowTitleEnabled(false)

        toolbarSpinner = Spinner(requireContext()).apply {
            layoutParams = ActionBar.LayoutParams(
                ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.WRAP_CONTENT
            )
        }
        setupSpinnerData(toolbarSpinner!!)
        actionBar?.setDisplayShowCustomEnabled(true)
        actionBar?.setCustomView(toolbarSpinner)
    }

    private fun navigateToNfcAttendance() {                 //Переход в режим чтения метки при нажатии на пункт в dropdown
        findNavController().navigate(R.id.action_listFragment_to_nfcAttendanceFragment)
    }

    @OptIn(InternalSerializationApi::class)
    private fun setupSpinnerData(spinner: Spinner) {                        // работа с dropdown menu (2 стандартных пункта + группы)
        val groups = studentList.map { it.studentGroup }.toSet().toMutableList()
        groups.add(0, "Все группы")
        groups.add("Отметка посещения")

        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            groups
        )
        spinner.adapter = spinnerAdapter

        val prefs = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)  // логика сохранения состояния
        val savedGroup = prefs.getString(selectedGroupKey, "Все группы")
        val actualSavedGroup = if (savedGroup == "Отметка посещения") "Все группы" else savedGroup
        val savedPosition = groups.indexOf(actualSavedGroup).takeIf { it >= 0 } ?: 0
        spinner.setSelection(savedPosition)

        applyFilter(actualSavedGroup ?: "Все группы")

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = groups[position]
                if (selected == "Отметка посещения") {
                    navigateToNfcAttendance()
                    val previousGroup = prefs.getString(selectedGroupKey, "Все группы") ?: "Все группы"
                    spinner.setSelection(groups.indexOf(previousGroup).takeIf { it >= 0 } ?: 0)
                } else {
                    prefs.edit().putString(selectedGroupKey, selected).apply()
                    applyFilter(selected)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun processNfcTag(nfcId: String) {
        processNfcTag(nfcId, "")
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String, physicalUid: String) {
        lifecycleScope.launch {
            val cleanPhysUid = SafeNdefManager.cleanUid(physicalUid)
            val parsed = SafeNdefManager.parsePassPayload(nfcId, cleanPhysUid)

            if (parsed != null && parsed.isCloneDetected) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "ОБНАРУЖЕН КЛОН МЕТКИ! UID чипа не совпадает", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val searchKey = when {
                parsed != null && parsed.tagUid.isNotBlank() -> parsed.tagUid
                cleanPhysUid.isNotBlank() -> cleanPhysUid
                else -> nfcId.trim()
            }

            val existingStudent = studentRepository.getStudentByNfc(searchKey)
            requireActivity().runOnUiThread {
                if (existingStudent != null) {
                    markStudentAttendance(existingStudent)
                } else {
                    Toast.makeText(requireContext(), "Студент с таким NFC не найден: $searchKey", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun showNfcNotSupportedMessage() {
        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("NFC не поддерживается")
            .setIcon(R.drawable.ic_nfc)
            .setMessage("На данном устройстве отсутствует модуль NFC.")
            .setPositiveButton("Понятно", null)
            .show()
    }

    override fun showNfcReadingStartedMessage() {
        Toast.makeText(requireContext(), "Режим чтения NFC активирован", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcReadingStoppedMessage() {
        Toast.makeText(requireContext(), "Режим чтения завершен", Toast.LENGTH_SHORT).show()
    }

    @OptIn(InternalSerializationApi::class)
    private fun markStudentAttendance(student: Student) { // Функция отметки студента в журнале по индексу
        lifecycleScope.launch {
            studentRepository.updateAttendance(student.id, true)
            Toast.makeText(requireContext(), "${student.studentName} отмечен", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbarSpinner()
    }

    @OptIn(InternalSerializationApi::class)
    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            setDisplayShowCustomEnabled(false)
            setDisplayShowTitleEnabled(true)
        }
        toolbarSpinner = null
        _binding = null
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.delete_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_delete -> {
                deleteAllStudents()
                true
            }
            R.id.menu_sync -> {
                syncStudents()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun syncStudents() {
        lifecycleScope.launch {
            val result = studentRepository.syncAllStudents()
            when (result) {
                is SyncResult.Success -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                is SyncResult.Error -> Toast.makeText(requireContext(), "Ошибка: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadStudents() {
        lifecycleScope.launch {
            try {
                studentRepository.getAllStudents().collect { students ->
                    studentList = students.toMutableList()
                    adapter.setData(studentList)
                    toolbarSpinner?.let { setupSpinnerData(it) }
                }
            } catch (e: Exception) {
                studentList = mutableListOf()
                adapter.setData(studentList)
            }
        }
    }

    @OptIn(InternalSerializationApi::class) // Фильтрация по группе после выбора в dropdown
    private fun applyFilter(selected: String) {
        if (selected == "Все группы") {
            adapter.setData(studentList)
        } else {
            val filtered = studentList.filter { it.studentGroup == selected }
            adapter.setData(filtered)
        }
    }

    private fun deleteAllStudents() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setPositiveButton("Да") { _, _ ->
            lifecycleScope.launch {
                studentRepository.deleteAllStudents()
                Toast.makeText(requireContext(), "Success!", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Отмена") { _, _ -> }
        builder.setTitle("Удалить всех студентов?")
        builder.setMessage("Вы точно хотите удалить всех?")
        builder.create().show()
    }

    @OptIn(InternalSerializationApi::class)                     //Диалог удаления студента из списка
    private fun showDeleteConfirmationDialog(student: Student, position: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить студента")
            .setMessage("Вы уверены, что хотите удалить ${student.studentName}?")
            .setPositiveButton("Удалить") { dialog, which ->
                lifecycleScope.launch {
                    studentRepository.deleteStudent(student)
                }
            }
            .setNegativeButton("Отмена") { dialog, which ->
                adapter.notifyItemChanged(position)
            }
            .show()
    }

    @OptIn(InternalSerializationApi::class)                         //Работа со свайпом удаления
    private fun setupSwipeToDelete() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT                                       // Свайп влево(меньше случайных свайпов для правшей)
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val studentToDelete = adapter.getStudentAtPosition(position)
                    showDeleteConfirmationDialog(studentToDelete, position)
                }
            }

            override fun onChildDraw(
                canvas: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable()
                background.color = ContextCompat.getColor(requireContext(), R.color.purple_200)

                if (dX < 0) {
                    background.setBounds(
                        itemView.right + dX.toInt(),
                        itemView.top,
                        itemView.right,
                        itemView.bottom
                    )
                    background.draw(canvas)

                    val deleteIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_delete_24)
                    val iconMargin = (itemView.height-deleteIcon!!.intrinsicHeight) / 2
                    val iconTop = itemView.top + (itemView.height-deleteIcon.intrinsicHeight) / 2
                    val iconBottom = iconTop + deleteIcon.intrinsicHeight
                    val iconLeft = itemView.right-iconMargin-deleteIcon.intrinsicWidth
                    val iconRight = itemView.right-iconMargin
                    deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    deleteIcon.draw(canvas)
                }

                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.6f             //срабатывание на 60% и более
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                return defaultValue * 0.5f          // скорость свайпа
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerview)
    }

    override fun onPhysicalTagScanned(tag: android.nfc.Tag, payloadOrUid: String) {
        lifecycleScope.launch {
            val bundle = Bundle().apply {
                putString("scanned_tag_uid", payloadOrUid)
            }
            try {
                findNavController().navigate(R.id.nfcManagerFragment, bundle)
            } catch (e: Exception) {
                Log.e("ListFragment", "Failed to navigate to nfcManagerFragment with tag $payloadOrUid", e)
            }
        }
    }
}