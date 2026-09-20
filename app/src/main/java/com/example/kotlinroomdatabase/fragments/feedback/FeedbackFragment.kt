package com.example.kotlinroomdatabase.fragments.feedback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentFeedbackBinding
import com.example.kotlinroomdatabase.model.FeedbackAttachmentItem
import com.example.kotlinroomdatabase.model.FeedbackTicket
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.ImageSanitizer
import com.example.kotlinroomdatabase.util.RoleUtils
import com.example.kotlinroomdatabase.util.SystemInfoHelper
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository
    private val attachmentItems = mutableListOf<FeedbackAttachmentItem>()
    private lateinit var attachmentAdapter: FeedbackAttachmentAdapter
    private lateinit var myTicketsAdapter: MyFeedbackTicketsAdapter

    private var isSubmitting = false

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult

        val remainingSlots = 5 - attachmentItems.size
        if (remainingSlots <= 0) {
            Toast.makeText(requireContext(), "Максимум 5 фотографий", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val toProcess = uris.take(remainingSlots)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            for (uri in toProcess) {
                val sanitizedFile = ImageSanitizer.sanitizeImage(requireContext(), uri)
                if (sanitizedFile != null) {
                    val item = FeedbackAttachmentItem(
                        id = UUID.randomUUID().toString(),
                        file = sanitizedFile
                    )
                    withContext(Dispatchers.Main) {
                        attachmentItems.add(item)
                        attachmentAdapter.notifyItemInserted(attachmentItems.size - 1)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Не удалось обработать изображение", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"

        // Access check: Developers must use the dev tracker
        if (RoleUtils.isDeveloper(userRole)) {
            binding.layoutDevRestricted.visibility = View.VISIBLE
            binding.layoutFeedbackContent.visibility = View.GONE
            binding.btnGoToDevTasks.setOnClickListener {
                findNavController().navigate(R.id.devTasksFragment)
            }
            binding.swipeRefreshFeedback.isEnabled = false
            return
        }

        binding.layoutDevRestricted.visibility = View.GONE
        binding.layoutFeedbackContent.visibility = View.VISIBLE

        setupTabs()
        setupAttachmentsRecycler()
        setupHistoryRecycler()

        binding.btnAddPhoto.setOnClickListener {
            if (attachmentItems.size >= 5) {
                Toast.makeText(requireContext(), "Максимум 5 фотографий", Toast.LENGTH_SHORT).show()
            } else {
                pickImagesLauncher.launch("image/*")
            }
        }

        binding.btnSubmitFeedback.setOnClickListener {
            submitFeedback()
        }

        binding.swipeRefreshFeedback.setOnRefreshListener {
            if (binding.tabLayoutFeedback.selectedTabPosition == 1) {
                loadMyTickets()
            } else {
                binding.swipeRefreshFeedback.isRefreshing = false
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayoutFeedback.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.viewCreateTicket.visibility = View.VISIBLE
                        binding.viewHistoryTickets.visibility = View.GONE
                    }
                    1 -> {
                        binding.viewCreateTicket.visibility = View.GONE
                        binding.viewHistoryTickets.visibility = View.VISIBLE
                        loadMyTickets()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                if (tab?.position == 1) {
                    loadMyTickets()
                }
            }
        })
    }

    private fun setupAttachmentsRecycler() {
        attachmentAdapter = FeedbackAttachmentAdapter(attachmentItems) { item, position ->
            try {
                item.file.delete()
            } catch (_: Exception) {}
            attachmentItems.removeAt(position)
            attachmentAdapter.notifyItemRemoved(position)
        }
        binding.rvAttachments.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = attachmentAdapter
        }
    }

    private fun setupHistoryRecycler() {
        myTicketsAdapter = MyFeedbackTicketsAdapter { ticket ->
            val sheet = FeedbackDetailsBottomSheet.newInstance(ticket) {
                loadMyTickets()
            }
            sheet.show(parentFragmentManager, FeedbackDetailsBottomSheet.TAG)
        }
        binding.rvMyTickets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = myTicketsAdapter
        }
    }

    private fun submitFeedback() {
        if (isSubmitting) return

        val title = binding.etFeedbackTitle.text?.toString()?.trim() ?: ""
        val description = binding.etFeedbackDescription.text?.toString()?.trim() ?: ""

        if (title.length < 3) {
            binding.etFeedbackTitle.error = "Тема должна содержать не менее 3 символов"
            binding.etFeedbackTitle.requestFocus()
            return
        }
        if (description.length < 5) {
            binding.etFeedbackDescription.error = "Описание должно содержать не менее 5 символов"
            binding.etFeedbackDescription.requestFocus()
            return
        }

        val category = when (binding.chipGroupCategory.checkedChipId) {
            R.id.chipFeature -> "feature"
            R.id.chipQuestion -> "question"
            R.id.chipOther -> "other"
            else -> "bug"
        }

        setSubmittingState(true)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 1. Upload attachments
            val uploadedUrls = mutableListOf<String>()
            for (item in attachmentItems) {
                val uploadRes = repository.uploadFeedbackAttachment(item.file)
                if (uploadRes is GenericResult.Success) {
                    uploadedUrls.add(uploadRes.data)
                }
            }

            // 2. Always generate & upload diagnostic .log file with system metrics & error logs for developers
            try {
                val logFile = com.example.kotlinroomdatabase.util.AppErrorLogger.getOrCreateDiagnosticLogFile(requireContext())
                if (logFile.exists() && logFile.length() > 0) {
                    val logUploadRes = repository.uploadFeedbackAttachment(logFile)
                    if (logUploadRes is GenericResult.Success) {
                        uploadedUrls.add(logUploadRes.data)
                    } else {
                        android.util.Log.e("FEEDBACK", "Failed to upload log file: ${(logUploadRes as? GenericResult.Error)?.message}")
                    }
                }
            } catch (e: Exception) {
                com.example.kotlinroomdatabase.util.AppErrorLogger.logError(requireContext(), "FEEDBACK_SUBMIT", "Failed to upload log file", e)
            }

            // Append readable diagnostic info to description if switch is checked
            val finalDescription = if (binding.switchDiagnostics.isChecked) {
                description + com.example.kotlinroomdatabase.util.SystemInfoHelper.getDiagnosticInfoMarkdown(requireContext())
            } else {
                description
            }

            // 3. Create ticket
            val result = repository.createFeedbackTicket(
                title = title,
                description = finalDescription,
                category = category,
                priority = "medium",
                attachments = uploadedUrls
            )

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                setSubmittingState(false)

                if (result is GenericResult.Success) {
                    Toast.makeText(requireContext(), "Обращение #${result.data.ticketId} успешно отправлено!", Toast.LENGTH_LONG).show()

                    // Clear inputs & cached files
                    binding.etFeedbackTitle.text?.clear()
                    binding.etFeedbackDescription.text?.clear()
                    attachmentItems.forEach {
                        try { it.file.delete() } catch (_: Exception) {}
                    }
                    attachmentItems.clear()
                    attachmentAdapter.notifyDataSetChanged()

                    // Switch to My Tickets tab
                    binding.tabLayoutFeedback.getTabAt(1)?.select()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), "Ошибка: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadMyTickets() {
        binding.swipeRefreshFeedback.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getMyFeedbackTickets()
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.swipeRefreshFeedback.isRefreshing = false

                if (result is GenericResult.Success) {
                    val tickets = result.data
                    myTicketsAdapter.submitList(tickets)
                    binding.layoutEmptyTickets.visibility = if (tickets.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvMyTickets.visibility = if (tickets.isNotEmpty()) View.VISIBLE else View.GONE
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setSubmittingState(submitting: Boolean) {
        isSubmitting = submitting
        binding.btnSubmitFeedback.isEnabled = !submitting
        binding.progressSubmit.visibility = if (submitting) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup remaining temporary files
        attachmentItems.forEach {
            try { it.file.delete() } catch (_: Exception) {}
        }
        _binding = null
    }
}
