package com.example.kotlinroomdatabase.fragments.biometrics

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.activity.FaceCaptureActivity
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentStudentFaceSamplesBinding
import com.example.kotlinroomdatabase.model.StudentFaceSamplesStatus
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.StudentFacePhotoManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StudentFaceSamplesFragment : Fragment() {

    private var _binding: FragmentStudentFaceSamplesBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository
    private var currentStatus: StudentFaceSamplesStatus = StudentFaceSamplesStatus.initial()

    private var pendingGuidedAngle: String? = null

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val imagePath = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_IMAGE_PATH)
                val angle = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_ANGLE)
                    ?: StudentFaceSamplesStatus.ANGLE_FRONTAL

                if (!imagePath.isNullOrBlank()) {
                    handleCapturedPhoto(angle, File(imagePath))
                }
            } else {
                pendingGuidedAngle = null
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStudentFaceSamplesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = StudentDatabase.getInstance(requireContext().applicationContext)
        repository = StudentRepositoryHTTPS(requireContext().applicationContext, db.studentDao())

        setupListeners()
        loadSamplesStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            loadSamplesStatus()
        }

        binding.btnCaptureAllGuided.setOnClickListener {
            startGuidedCapture()
        }

        binding.btnActionFrontal.setOnClickListener {
            launchCameraForAngle(StudentFaceSamplesStatus.ANGLE_FRONTAL)
        }

        binding.btnActionThreeQuarter.setOnClickListener {
            launchCameraForAngle(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER)
        }

        binding.btnActionProfile.setOnClickListener {
            launchCameraForAngle(StudentFaceSamplesStatus.ANGLE_PROFILE)
        }
    }

    private fun launchCameraForAngle(angle: String) {
        val intent = FaceCaptureActivity.newIntent(requireContext(), angle)
        captureLauncher.launch(intent)
    }

    private fun startGuidedCapture() {
        // Find first angle that is not yet uploaded
        val nextAngle = when {
            !currentStatus.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_FRONTAL) ->
                StudentFaceSamplesStatus.ANGLE_FRONTAL
            !currentStatus.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER) ->
                StudentFaceSamplesStatus.ANGLE_THREE_QUARTER
            !currentStatus.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_PROFILE) ->
                StudentFaceSamplesStatus.ANGLE_PROFILE
            else -> StudentFaceSamplesStatus.ANGLE_FRONTAL // all complete, start re-capture from frontal
        }
        pendingGuidedAngle = nextAngle
        launchCameraForAngle(nextAngle)
    }

    private fun loadSamplesStatus() {
        binding.swipeRefreshLayout.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getStudentFaceSamples()

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.swipeRefreshLayout.isRefreshing = false

                when (result) {
                    is GenericResult.Success -> {
                        currentStatus = result.data
                        renderStatus(currentStatus)
                    }
                    is GenericResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                        renderStatus(currentStatus)
                    }
                }
            }
        }
    }

    private fun renderStatus(status: StudentFaceSamplesStatus) {
        val count = status.uploadedCount()
        binding.tvCounter.text = "$count / 3"

        if (count >= 3) {
            binding.tvCounter.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_success))
            binding.tvCounter.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.sib_success_bg)
            binding.btnCaptureAllGuided.text = "Все 3 ракурса загружены (переснять)"
        } else {
            binding.tvCounter.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_blue_primary))
            binding.tvCounter.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.sib_blue_active_bg)
            binding.btnCaptureAllGuided.text = "Снять все 3 ракурса по очереди"
        }

        renderSlot(
            angle = StudentFaceSamplesStatus.ANGLE_FRONTAL,
            isUploaded = status.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_FRONTAL),
            emptyView = binding.layoutFrontalEmpty,
            thumbnailView = binding.ivFrontalThumbnail,
            doneBadge = binding.badgeFrontalDone,
            actionButton = binding.btnActionFrontal
        )

        renderSlot(
            angle = StudentFaceSamplesStatus.ANGLE_THREE_QUARTER,
            isUploaded = status.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER),
            emptyView = binding.layoutThreeQuarterEmpty,
            thumbnailView = binding.ivThreeQuarterThumbnail,
            doneBadge = binding.badgeThreeQuarterDone,
            actionButton = binding.btnActionThreeQuarter
        )

        renderSlot(
            angle = StudentFaceSamplesStatus.ANGLE_PROFILE,
            isUploaded = status.isAngleUploaded(StudentFaceSamplesStatus.ANGLE_PROFILE),
            emptyView = binding.layoutProfileEmpty,
            thumbnailView = binding.ivProfileThumbnail,
            doneBadge = binding.badgeProfileDone,
            actionButton = binding.btnActionProfile
        )
    }

    private fun renderSlot(
        angle: String,
        isUploaded: Boolean,
        emptyView: View,
        thumbnailView: android.widget.ImageView,
        doneBadge: View,
        actionButton: com.google.android.material.button.MaterialButton
    ) {
        if (isUploaded) {
            emptyView.visibility = View.GONE
            thumbnailView.visibility = View.VISIBLE
            doneBadge.visibility = View.VISIBLE
            actionButton.text = "Переснять"

            // Load local thumbnail if available
            val localFile = StudentFacePhotoManager.getSampleFile(requireContext(), angle)
            if (localFile.exists()) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val thumb = StudentFacePhotoManager.loadThumbnail(localFile, 350)
                    withContext(Dispatchers.Main) {
                        if (_binding != null && isAdded && thumb != null) {
                            thumbnailView.setImageBitmap(thumb)
                        }
                    }
                }
            }
        } else {
            emptyView.visibility = View.VISIBLE
            thumbnailView.visibility = View.GONE
            doneBadge.visibility = View.GONE
            actionButton.text = "Добавить фото"
        }
    }

    private fun handleCapturedPhoto(angle: String, photoFile: File) {
        val progressBar = when (angle) {
            StudentFaceSamplesStatus.ANGLE_FRONTAL -> binding.progressFrontalUpload
            StudentFaceSamplesStatus.ANGLE_THREE_QUARTER -> binding.progressThreeQuarterUpload
            StudentFaceSamplesStatus.ANGLE_PROFILE -> binding.progressProfileUpload
            else -> null
        }
        progressBar?.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val uploadResult = repository.uploadStudentFaceSample(angle, photoFile)

            withContext(Dispatchers.Main) {
                progressBar?.visibility = View.GONE
                if (_binding == null || !isAdded) return@withContext

                when (uploadResult) {
                    is GenericResult.Success -> {
                        Snackbar.make(binding.root, "Фото '${StudentFaceSamplesStatus.titleForAngle(angle)}' сохранено", Snackbar.LENGTH_SHORT).show()
                        loadSamplesStatus()

                        // If guided capture was running, proceed to next step
                        checkAndAdvanceGuidedCapture(angle)
                    }
                    is GenericResult.Error -> {
                        Toast.makeText(requireContext(), uploadResult.message, Toast.LENGTH_LONG).show()
                        pendingGuidedAngle = null
                    }
                }
            }
        }
    }

    private fun checkAndAdvanceGuidedCapture(finishedAngle: String) {
        if (pendingGuidedAngle == null) return

        val nextAngle = when (finishedAngle) {
            StudentFaceSamplesStatus.ANGLE_FRONTAL -> StudentFaceSamplesStatus.ANGLE_THREE_QUARTER
            StudentFaceSamplesStatus.ANGLE_THREE_QUARTER -> StudentFaceSamplesStatus.ANGLE_PROFILE
            else -> null
        }

        if (nextAngle != null) {
            pendingGuidedAngle = nextAngle
            Toast.makeText(
                requireContext(),
                "Следующий ракурс: ${StudentFaceSamplesStatus.titleForAngle(nextAngle)}",
                Toast.LENGTH_SHORT
            ).show()
            launchCameraForAngle(nextAngle)
        } else {
            pendingGuidedAngle = null
            Toast.makeText(requireContext(), "Все 3 ракурса успешно добавлены!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
