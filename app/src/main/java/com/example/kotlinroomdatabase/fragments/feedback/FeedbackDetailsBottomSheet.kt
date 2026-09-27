package com.example.kotlinroomdatabase.fragments.feedback

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.BottomSheetFeedbackDetailsBinding
import com.example.kotlinroomdatabase.databinding.ItemDevCommentBinding
import com.example.kotlinroomdatabase.model.FeedbackComment
import com.example.kotlinroomdatabase.model.FeedbackTicket
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.AppErrorLogger
import com.example.kotlinroomdatabase.util.AvatarManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class FeedbackDetailsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "FeedbackDetailsBottomSheet"
        private const val ARG_TICKET_ID = "arg_ticket_id"

        fun newInstance(ticketId: Int, onTicketUpdated: () -> Unit = {}): FeedbackDetailsBottomSheet {
            val sheet = FeedbackDetailsBottomSheet()
            sheet.ticketId = ticketId
            sheet.onTicketUpdated = onTicketUpdated
            val args = Bundle().apply {
                putInt(ARG_TICKET_ID, ticketId)
            }
            sheet.arguments = args
            return sheet
        }

        fun newInstance(ticket: FeedbackTicket, onTicketUpdated: () -> Unit = {}): FeedbackDetailsBottomSheet {
            val sheet = FeedbackDetailsBottomSheet()
            sheet.cachedTicket = ticket
            sheet.ticketId = ticket.ticketId
            sheet.onTicketUpdated = onTicketUpdated
            val args = Bundle().apply {
                putInt(ARG_TICKET_ID, ticket.ticketId)
            }
            sheet.arguments = args
            return sheet
        }
    }

    private var ticketId: Int = 0
    private var cachedTicket: FeedbackTicket? = null
    private var onTicketUpdated: () -> Unit = {}

    private var _binding: BottomSheetFeedbackDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository
    private val commentsList = mutableListOf<FeedbackComment>()
    private lateinit var commentsAdapter: CommentsAdapter

    private val replyAttachmentFiles = mutableListOf<File>()

    private val pickReplyPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (uri in uris) {
            val file = copyUriToTempFile(uri) ?: continue
            replyAttachmentFiles.add(file)
        }
        renderReplyAttachmentsPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ticketId == 0) {
            ticketId = arguments?.getInt(ARG_TICKET_ID, 0) ?: 0
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFeedbackDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        cachedTicket?.let { setupViews(it) }
        setupCommentsRecycler()
        loadDetails()

        binding.btnAttachReplyPhoto.setOnClickListener {
            pickReplyPhotosLauncher.launch("image/*")
        }

        binding.btnSendReply.setOnClickListener {
            sendReply()
        }
    }

    private fun setupViews(t: FeedbackTicket) {
        binding.tvDetailTicketId.text = "#${t.ticketId}"
        binding.tvDetailCategoryBadge.text = t.categoryLabel
        binding.tvDetailTitle.text = t.title
        binding.tvDetailDescription.text = t.description
        binding.tvDetailDate.text = "Создано: ${t.createdAt.take(16).replace("T", " ")}"

        // Status badge
        binding.tvDetailStatusBadge.text = t.statusLabel
        val statusColor = when (t.status.lowercase()) {
            "resolved" -> Color.parseColor("#15803D")
            "in_progress" -> Color.parseColor("#B45309")
            "closed" -> Color.parseColor("#6B7280")
            else -> Color.parseColor("#1D4ED8")
        }
        val statusBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(statusColor)
        }
        binding.tvDetailStatusBadge.background = statusBg

        // Attachments gallery
        if (t.attachments.isNotEmpty()) {
            binding.tvAttachmentsHeader.visibility = View.VISIBLE
            binding.scrollAttachments.visibility = View.VISIBLE
            binding.layoutAttachmentsContainer.removeAllViews()

            for (url in t.attachments) {
                val isImage = url.contains(Regex("\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))
                if (isImage) {
                    val sizePx = (72 * resources.displayMetrics.density).toInt()
                    val marginPx = (10 * resources.displayMetrics.density).toInt()
                    val iv = ImageView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            setMargins(0, 0, marginPx, 0)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_rounded_thumb)
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                        setOnClickListener {
                            PhotoViewerDialog.newInstance(url, t.title).show(parentFragmentManager, PhotoViewerDialog.TAG)
                        }
                    }
                    loadAttachmentThumbnail(iv, url)
                    binding.layoutAttachmentsContainer.addView(iv)
                } else {
                    val chip = createLogChipView(url)
                    binding.layoutAttachmentsContainer.addView(chip)
                }
            }
        } else {
            binding.tvAttachmentsHeader.visibility = View.GONE
            binding.scrollAttachments.visibility = View.GONE
        }
    }

    private fun setupCommentsRecycler() {
        commentsAdapter = CommentsAdapter(commentsList)
        binding.rvTicketComments.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = commentsAdapter
        }
    }

    private fun loadDetails() {
        if (ticketId <= 0) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getFeedbackTicketDetails(ticketId)
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                if (result is GenericResult.Success) {
                    val details = result.data
                    cachedTicket = details.ticket
                    setupViews(details.ticket)

                    val comments = details.comments
                    commentsList.clear()
                    commentsList.addAll(comments)
                    commentsAdapter.notifyDataSetChanged()
                    binding.tvNoCommentsHint.visibility = if (comments.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun renderReplyAttachmentsPreview() {
        if (replyAttachmentFiles.isEmpty()) {
            binding.scrollReplyAttachments.visibility = View.GONE
            binding.layoutReplyAttachmentsPreview.removeAllViews()
            return
        }

        binding.scrollReplyAttachments.visibility = View.VISIBLE
        binding.layoutReplyAttachmentsPreview.removeAllViews()

        for ((index, file) in replyAttachmentFiles.withIndex()) {
            val sizePx = (64 * resources.displayMetrics.density).toInt()
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            val itemContainer = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(0, 0, marginPx, 0)
                }
            }

            val iv = ImageView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_rounded_thumb)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                setImageURI(Uri.fromFile(file))
            }
            itemContainer.addView(iv)

            val btnRemove = ImageButton(requireContext()).apply {
                val size = 52
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    setMargins(0, 4, 4, 0)
                }
                setImageResource(R.drawable.ic_cross)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#B42318"))
                }
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    replyAttachmentFiles.removeAt(index)
                    renderReplyAttachmentsPreview()
                }
            }
            itemContainer.addView(btnRemove)

            binding.layoutReplyAttachmentsPreview.addView(itemContainer)
        }
    }

    private fun sendReply() {
        val message = binding.etReplyMessage.text.toString().trim()
        if (message.isBlank() && replyAttachmentFiles.isEmpty()) return

        binding.btnSendReply.isEnabled = false
        binding.btnAttachReplyPhoto.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val uploadedUrls = mutableListOf<String>()

            // Upload any attached files first
            for (file in replyAttachmentFiles) {
                val uploadResult = repository.uploadFeedbackAttachment(file)
                if (uploadResult is GenericResult.Success) {
                    uploadedUrls.add(uploadResult.data)
                } else if (uploadResult is GenericResult.Error) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Ошибка загрузки фото: ${uploadResult.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val currentTicketId = ticketId
            val result = repository.replyFeedbackTicket(currentTicketId, message, uploadedUrls)
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.btnSendReply.isEnabled = true
                binding.btnAttachReplyPhoto.isEnabled = true

                if (result is GenericResult.Success) {
                    val authPrefs = requireContext().getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
                    val studentPrefs = requireContext().getSharedPreferences("student_prefs", android.content.Context.MODE_PRIVATE)
                    val authorName = studentPrefs.getString("student_name", "Студент") ?: "Студент"
                    val authorAvatar = authPrefs.getString("avatar_url", "") ?: ""

                    // Optimistically add comment to list immediately so user sees it right away!
                    val newComment = FeedbackComment(
                        commentId = (System.currentTimeMillis() % 100000).toInt(),
                        ticketId = currentTicketId,
                        authorId = 0,
                        authorName = authorName,
                        authorRole = "student",
                        authorAvatar = authorAvatar,
                        content = message,
                        isInternal = false,
                        attachments = uploadedUrls,
                        createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    )

                    commentsList.add(newComment)
                    commentsAdapter.notifyItemInserted(commentsList.size - 1)
                    binding.tvNoCommentsHint.visibility = View.GONE
                    binding.rvTicketComments.scrollToPosition(commentsList.size - 1)
                    binding.scrollFeedbackDetails.post {
                        binding.scrollFeedbackDetails.fullScroll(View.FOCUS_DOWN)
                    }

                    binding.etReplyMessage.text.clear()
                    replyAttachmentFiles.clear()
                    renderReplyAttachmentsPreview()

                    Toast.makeText(requireContext(), "Ответ отправлен", Toast.LENGTH_SHORT).show()
                    onTicketUpdated()
                    // Sync fresh state from server
                    loadDetails()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadAttachmentThumbnail(iv: ImageView, url: String) {
        val context = iv.context
        val finalUrl = ServerConfig.resolveMediaUrl(context, url)
        iv.tag = finalUrl

        val cacheDir = File(context.cacheDir, "attachments_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${url.hashCode()}_thumb.jpg")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                return
            }
        }

        // Clean subtle placeholder
        iv.setImageResource(R.drawable.ic_camera)
        iv.imageTintList = ColorStateList.valueOf(Color.parseColor("#4B5563"))
        iv.setPadding(32, 32, 32, 32)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = StudentDatabase.getInstance(context)
                val repo = StudentRepositoryHTTPS(context, db.studentDao())
                val authPrefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""

                val reqBuilder = Request.Builder().url(finalUrl)
                // KA-04: Only attach Authorization header if destination matches trusted backend origin
                if (ServerConfig.isTrustedOrigin(context, finalUrl) && token.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val resp = repo.getUnsafeOkHttpClient().newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    // KA-14: Bounded stream read (max 15 MB)
                    val maxBytes = 15 * 1024 * 1024L
                    val body = resp.body
                    var readBytes: ByteArray? = null
                    if (body != null && (body.contentLength() <= 0 || body.contentLength() <= maxBytes)) {
                        body.byteStream().use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0L
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                total += n
                                if (total > maxBytes) {
                                    out.reset()
                                    break
                                }
                                out.write(buffer, 0, n)
                            }
                            if (out.size() > 0) readBytes = out.toByteArray()
                        }
                    }

                    if (readBytes != null && readBytes.isNotEmpty()) {
                        FileOutputStream(cacheFile).use { it.write(readBytes) }
                        val bmp = BitmapFactory.decodeByteArray(readBytes, 0, readBytes.size)
                        withContext(Dispatchers.Main) {
                            if (iv.tag == finalUrl && bmp != null) {
                                iv.setPadding(0, 0, 0, 0)
                                iv.imageTintList = null
                                iv.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun createLogChipView(url: String): View {
        val context = requireContext()
        val fileName = url.substringAfterLast("/").takeLast(24)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                110
            ).apply {
                setMargins(0, 0, 12, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(Color.parseColor("#1E293B"))
                setStroke(2, Color.parseColor("#334155"))
            }
            setPadding(20, 10, 20, 10)

            val tvIcon = TextView(context).apply {
                text = "📄"
                textSize = 14f
                setPadding(0, 0, 12, 0)
            }
            addView(tvIcon)

            val tvText = TextView(context).apply {
                text = fileName
                setTextColor(Color.parseColor("#93C5FD"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            addView(tvText)

            setOnClickListener {
                showLogViewerDialog(url)
            }
        }
    }

    private fun showLogViewerDialog(url: String) {
        val context = requireContext()
        val finalUrl = ServerConfig.resolveMediaUrl(context, url)
        val progressDialog = AlertDialog.Builder(context)
            .setMessage("Загрузка лога...")
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StudentDatabase.getInstance(context)
                val repo = StudentRepositoryHTTPS(context, db.studentDao())
                val authPrefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""

                val reqBuilder = Request.Builder().url(finalUrl)
                // KA-04: Only attach Authorization header if destination matches trusted backend origin
                if (ServerConfig.isTrustedOrigin(context, finalUrl) && token.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val resp = repo.getUnsafeOkHttpClient().newCall(reqBuilder.build()).execute()
                // KA-14: Bounded stream read for log viewer (max 10000 characters)
                val reader = resp.body?.charStream()?.buffered()
                val charBuf = CharArray(2048)
                val sb = java.lang.StringBuilder()
                var totalChars = 0
                val maxChars = 10000
                while (reader != null && totalChars < maxChars) {
                    val read = reader.read(charBuf, 0, kotlin.math.min(charBuf.size, maxChars - totalChars))
                    if (read == -1) break
                    sb.append(charBuf, 0, read)
                    totalChars += read
                }
                val content = if (sb.isNotEmpty()) sb.toString() else "Файл пуст"
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("Содержимое файла лога")
                        .setMessage(content.take(4000))
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Не удалось открыть лог: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("reply_att_", ".jpg", requireContext().cacheDir)
            FileOutputStream(tempFile).use { out ->
                inputStream.copyTo(out)
            }
            tempFile
        } catch (e: Exception) {
            AppErrorLogger.logError(requireContext(), "FEEDBACK_REPLY", "Failed to copy URI to temp file", e)
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class CommentsAdapter(private val list: List<FeedbackComment>) :
        RecyclerView.Adapter<CommentsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemDevCommentBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemDevCommentBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val context = holder.binding.root.context
            with(holder.binding) {
                val isDev = item.authorRole.equals("developer", ignoreCase = true) ||
                        item.authorRole.equals("admin", ignoreCase = true)

                tvCommentAuthor.text = item.authorName.ifBlank {
                    if (isDev) "Служба поддержки" else "Пользователь"
                }

                // Bubble color & Role badge distinction
                if (isDev) {
                    layoutCommentBubble.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 24f
                        setColor(Color.parseColor("#172540")) // Developer blue tint
                        setStroke(2, Color.parseColor("#2563EB"))
                    }
                    tvCommentRoleBadge.text = "Разработчик"
                    tvCommentRoleBadge.setTextColor(Color.parseColor("#60A5FA"))
                    tvCommentRoleBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f
                        setColor(Color.parseColor("#252563EB"))
                    }
                } else {
                    layoutCommentBubble.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 24f
                        setColor(Color.parseColor("#111827")) // Student card tint
                        setStroke(2, Color.parseColor("#1F2937"))
                    }
                    tvCommentRoleBadge.text = "Студент"
                    tvCommentRoleBadge.setTextColor(Color.parseColor("#34D399"))
                    tvCommentRoleBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f
                        setColor(Color.parseColor("#2510B981"))
                    }
                }

                tvCommentContent.text = item.content
                tvCommentContent.visibility = if (item.content.isBlank()) View.GONE else View.VISIBLE

                // Format time at bottom: e.g. "15:21"
                val timeStr = try {
                    if (item.createdAt.contains("T")) {
                        item.createdAt.substringAfter("T").take(5)
                    } else if (item.createdAt.contains(" ")) {
                        item.createdAt.substringAfter(" ").take(5)
                    } else {
                        item.createdAt.takeLast(5)
                    }
                } catch (e: Exception) {
                    ""
                }
                tvCommentTime.text = timeStr
                AvatarManager.loadAvatarUrl(context, ivCommentAuthorAvatar, item.authorAvatar)

                // Render attached images/files in the comment
                if (item.attachments.isNotEmpty()) {
                    scrollCommentAttachments.visibility = View.VISIBLE
                    layoutCommentAttachments.removeAllViews()
                    for (url in item.attachments) {
                        val isImg = url.contains(Regex("\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))
                        if (isImg) {
                            val sizePx = (72 * context.resources.displayMetrics.density).toInt()
                            val marginPx = (8 * context.resources.displayMetrics.density).toInt()
                            val iv = ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                                    setMargins(0, 0, marginPx, 0)
                                }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_rounded_thumb)
                                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                                clipToOutline = true
                                setOnClickListener {
                                    PhotoViewerDialog.newInstance(url, "Вложение к ответу")
                                        .show(parentFragmentManager, PhotoViewerDialog.TAG)
                                }
                            }
                            loadAttachmentThumbnail(iv, url)
                            layoutCommentAttachments.addView(iv)
                        } else {
                            val chip = createLogChipView(url)
                            layoutCommentAttachments.addView(chip)
                        }
                    }
                } else {
                    scrollCommentAttachments.visibility = View.GONE
                    layoutCommentAttachments.removeAllViews()
                }
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
