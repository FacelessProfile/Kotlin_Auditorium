package com.example.kotlinroomdatabase.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.example.kotlinroomdatabase.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Centralized helper for checking NFC availability, handling state transitions,
 * launching system NFC settings/panels, and presenting user-friendly Material dialogs.
 */
object NfcHelper {

    enum class NfcState {
        NOT_SUPPORTED,
        DISABLED,
        ENABLED
    }

    /**
     * Returns the current hardware and software state of the NFC module.
     */
    fun getNfcState(context: Context): NfcState {
        val adapter = try {
            NfcAdapter.getDefaultAdapter(context)
        } catch (_: Throwable) {
            null
        }
        return when {
            adapter == null -> NfcState.NOT_SUPPORTED
            !adapter.isEnabled -> NfcState.DISABLED
            else -> NfcState.ENABLED
        }
    }

    fun isNfcSupported(context: Context): Boolean =
        getNfcState(context) != NfcState.NOT_SUPPORTED

    fun isNfcEnabled(context: Context): Boolean =
        getNfcState(context) == NfcState.ENABLED

    /**
     * Prompts Android to open the NFC toggle panel (Android 10+) or navigates to NFC settings.
     */
    fun openNfcSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val panelIntent = Intent(Settings.Panel.ACTION_NFC)
                    if (context !is Activity) {
                        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(panelIntent)
                    return
                } catch (_: Exception) {}
            }
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось открыть настройки NFC", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shows a student-facing Material dialog explaining the status of NFC and electronic pass.
     * Offers instant switch to QR scanner if NFC is unavailable, or a 1-tap button to open NFC settings if disabled.
     */
    fun showStudentNfcDialog(
        context: Context,
        studentName: String = "",
        group: String = "",
        onScanQrRequested: (() -> Unit)? = null
    ) {
        when (getNfcState(context)) {
            NfcState.NOT_SUPPORTED -> {
                val builder = MaterialAlertDialogBuilder(context)
                    .setTitle("NFC не поддерживается")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "На вашем смартфоне отсутствует аппаратный модуль NFC.\n\n" +
                        "Отметка посещаемости через NFC недоступна на этом устройстве. " +
                        "Пожалуйста, используйте сканирование динамического QR-кода занятия для фиксации присутствия."
                    )
                    .setPositiveButton("Понятно", null)

                if (onScanQrRequested != null) {
                    builder.setNeutralButton("Сканировать QR") { _, _ ->
                        onScanQrRequested()
                    }
                }
                builder.show()
            }
            NfcState.DISABLED -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("NFC выключен")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "Модуль NFC выключен на вашем смартфоне.\n\n" +
                        "Для работы цифрового пропуска и бесконтактной отметки необходимо включить NFC в настройках устройства.\n\n" +
                        "Включить модуль сейчас?"
                    )
                    .setPositiveButton("Включить NFC") { _, _ ->
                        openNfcSettings(context)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            NfcState.ENABLED -> {
                val displayName = studentName.ifBlank { "Студент" }
                val displayGroup = group.ifBlank { "СибГУТИ" }
                MaterialAlertDialogBuilder(context)
                    .setTitle("Электронный пропуск СибГУТИ")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "Владелец: $displayName\n" +
                        "Группа: $displayGroup\n\n" +
                        "Статус: Эмуляция карты активна (NFC включен).\n" +
                        "Поднесите заднюю панель смартфона к считывателю преподавателя или валидатору аудитории."
                    )
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        }
    }

    /**
     * Shows a teacher-facing Material dialog explaining NFC status on the lesson screen.
     */
    fun showTeacherNfcDialog(context: Context) {
        when (getNfcState(context)) {
            NfcState.NOT_SUPPORTED -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("NFC не поддерживается")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "На данном устройстве отсутствует модуль NFC.\n\n" +
                        "Студенты могут отмечаться через динамический QR-код занятия на экране вашего устройства " +
                        "или вы можете отметить их вручную в списке группы."
                    )
                    .setPositiveButton("Понятно", null)
                    .show()
            }
            NfcState.DISABLED -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("NFC выключен")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "Модуль NFC выключен на вашем смартфоне.\n\n" +
                        "Для считывания меток студентов необходимо включить NFC в настройках.\n\n" +
                        "Включить модуль сейчас?"
                    )
                    .setPositiveButton("Включить") { _, _ ->
                        openNfcSettings(context)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            NfcState.ENABLED -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("NFC готов к работе")
                    .setIcon(R.drawable.ic_nfc)
                    .setMessage(
                        "Бесконтактное считывание меток студентов активно.\n\n" +
                        "Студенты могут прикладывать свои смартфоны с включённым NFC к вашему устройству для фиксации присутствия."
                    )
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        }
    }
}
