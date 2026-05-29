package com.example.kotlinroomdatabase.fragments.list

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.provider.Settings
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
//QR
import android.net.Uri
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class User_Interface : Fragment() {
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var tvWelcome: TextView
    private var isProcessing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.user_ui, container, false)
        rootLayout = view.findViewById(R.id.rootLayout)
        statusIcon = view.findViewById(R.id.statusIcon)
        statusText = view.findViewById(R.id.statusText)
        tvWelcome = view.findViewById(R.id.tvWelcome)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val fullName = prefs.getString("student_name", "Студент")
        var displayName = "Студент"

        if (!fullName.isNullOrBlank() && fullName != "Студент") {
            val parts = fullName.trim().split(" ")

            displayName = when {
                parts.size >= 3 -> "${parts[1]} ${parts[2]}"
                parts.size == 2 -> parts[1]
                else -> parts[0]
            }

        }

        tvWelcome.text = "Добро пожаловать,\n$displayName"
        setNeutralState()

        val btnScan = view.findViewById<Button>(R.id.btnScan)
        btnScan.setOnClickListener {
            startScanning()
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(nfcReceiver, IntentFilter("NFC_MARK_SUCCESS"))
    }

    fun showSuccessCheck() {
        lifecycleScope.launch(Dispatchers.Main) {
            setSuccessState()
            statusText.text = "Успешная отметка!"
            delay(3000)
            setNeutralState()
            statusText.text = "Смартфон готов к считыванию"
        }
    }

    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isProcessing) {
                showSuccessCheck()
            }
        }
    }

    private fun setSuccessState() {
        statusIcon.setImageResource(R.drawable.ic_check)
        statusIcon.setColorFilter(Color.GREEN)
        statusText.setTextColor(Color.GREEN)
    }

    private fun setNeutralState() {
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.colorFilter = null
        statusText.text = "Поднесите к чекеру"
    }
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Сканирование отменено", Toast.LENGTH_LONG).show()
        } else {
            val scannedData = result.contents
            if (scannedData.startsWith("http://") || scannedData.startsWith("https://")) {
                handleScannedUrl(scannedData)
            } else {
                Toast.makeText(requireContext(), "QR-код не содержит ссылки: $scannedData", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleScannedUrl(url: String) {
        val uri = Uri.parse(url)
        val lessonIdStr = uri.getQueryParameter("lesson_id")
        
        if (lessonIdStr != null) {
            val lessonId = lessonIdStr.toIntOrNull() ?: -1
            if (lessonId != -1) {
                markAttendance(lessonId)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun markAttendance(lessonId: Int) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

                lifecycleScope.launch(Dispatchers.IO) {
                    val repository = com.example.kotlinroomdatabase.settings.RepositoryHTTPS.getStudentRepository(requireContext())
                    val result = repository.markAttendanceViaQr(lessonId, deviceId, lat, lon)
                    
                    launch(Dispatchers.Main) {
                        when (result) {
                            is com.example.kotlinroomdatabase.repository.AttendanceResult.Success -> {
                                showSuccessCheck()
                            }
                            is com.example.kotlinroomdatabase.repository.AttendanceResult.Error -> {
                                Toast.makeText(requireContext(), "Ошибка: ${result.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "Нет разрешения на геолокацию", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Наведите камеру на QR-код")
        options.setBeepEnabled(true)
        options.setBarcodeImageEnabled(true)
        options.setOrientationLocked(true)
        barcodeLauncher.launch(options)
    }
}