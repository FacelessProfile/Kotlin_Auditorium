package com.example.kotlinroomdatabase.fragments.list

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.ItemAttendancePhotoFaceBinding
import com.example.kotlinroomdatabase.model.AttendancePhotoFace
import kotlinx.coroutines.*

data class AttendancePhotoFaceRow(
    val jobId: String,
    val face: AttendancePhotoFace,
    val name: String,
    val hint: String,
    val editable: Boolean
)

class AttendancePhotoFaceAdapter(
    private val scope: CoroutineScope,
    private val onChoose: (Int) -> Unit,
    private val onPreview: (AttendancePhotoFace) -> Unit
) : ListAdapter<AttendancePhotoFaceRow, AttendancePhotoFaceAdapter.Holder>(object : DiffUtil.ItemCallback<AttendancePhotoFaceRow>() {
    override fun areItemsTheSame(a: AttendancePhotoFaceRow, b: AttendancePhotoFaceRow) = a.jobId == b.jobId && a.face.face_id == b.face.face_id
    override fun areContentsTheSame(a: AttendancePhotoFaceRow, b: AttendancePhotoFaceRow) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAttendancePhotoFaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) { holder.clear() }

    inner class Holder(private val binding: ItemAttendancePhotoFaceBinding) : RecyclerView.ViewHolder(binding.root) {
        private var imageJob: Job? = null
        private var bitmap: Bitmap? = null
        private var source: String? = null

        fun bind(row: AttendancePhotoFaceRow) {
            binding.tvFaceNumber.text = "Лицо ${row.face.face_id + 1}"
            binding.btnStudent.text = row.name
            binding.btnStudent.isEnabled = row.editable
            binding.btnStudent.setOnClickListener { onChoose(row.face.face_id) }
            binding.tvFaceHint.text = row.hint
            binding.ivFace.contentDescription = "Открыть лицо ${row.face.face_id + 1} крупнее"
            binding.ivFace.setOnClickListener { onPreview(row.face) }
            val encoded = row.face.preview_crop.ifBlank { row.face.crop }
            if (source == encoded) return
            clear()
            source = encoded
            imageJob = scope.launch {
                var decoded: Bitmap? = null
                try {
                    withContext(Dispatchers.Default) { decoded = decodePhoto(encoded, 512) }
                    if (source == encoded) {
                        bitmap = decoded
                        if (decoded != null) binding.ivFace.setImageBitmap(decoded)
                        decoded = null
                    }
                } finally { decoded?.recycle() }
            }
        }

        fun clear() {
            imageJob?.cancel()
            source = null
            binding.ivFace.setImageResource(R.drawable.ic_person)
            bitmap?.recycle()
            bitmap = null
        }
    }

    companion object {
        fun decodePhoto(encoded: String, maxSide: Int): Bitmap? = runCatching {
            if (encoded.length > 512 * 1024) return null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            decodePhotoBytes(bytes, maxSide)
        }.getOrNull()

        fun decodePhotoBytes(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 20_000_000) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
