# Отчёт о проделанной работе
**Период:** 11 неделя проекта  
**Дата составления:** 21.11.2025  

## Описание проделанной работы

За одиннадцатую неделю разработки были успешно выполнены следующие шаги:
- Был изучен материал по HCE (Host card emulation)
- Подготовленна кодовая основа для работы с HCE
- Реализовал временный Login page работающий с БД
- Обновил текущий код для работы с HCE + Tag id

## Поэтапное описание

### Основа для HCE
В ходе работы в проекте был реализован HCEservice.kt для будущей коммуникации с nfc-reader'ом:

```kotlin
class HCEservice : HostApduService() {

    companion object {
        const val TAG = "HceService"
        const val STUDENT_AID = "F14954574F58"
        @OptIn(InternalSerializationApi::class)
        var currentStudent: Student? = null
    }

    @OptIn(InternalSerializationApi::class)
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val command = apdu.toHexString()

        return when {
            command.contains(STUDENT_AID) -> {
                currentStudent?.let { student ->
                    val studentData = "${student.id}:${student.studentName}"
                    buildSuccessfulResponse(studentData.toByteArray())
                } ?: buildErrorResponse("there is no such student")
            }
            else -> buildErrorResponse("Unknown command")
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
    }

    private fun buildSuccessfulResponse(data: ByteArray): ByteArray {
        return byteArrayOf(0x90.toByte(), 0x00) + data
    }

    private fun buildErrorResponse(message: String): ByteArray {
        return byteArrayOf(0x6A, 0x82.toByte())
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
```

- Добавлен APDU сервис:
  ```xml
  <host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/app_name"
    android:requireDeviceUnlock="false">
    <aid-group
        android:description="@string/app_name"
        android:category="other">
        <aid-filter android:name="F14954574F58"/>
    </aid-group>
  </host-apdu-service>
  ```

  ## Обновлена кодовая база
  - Добавил поддержку HCE функционала в NFC_Attendance fragment:
    ```kotlin
    private fun readHceData() {
        lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("student_prefs", android.content.Context.MODE_PRIVATE)
            val studentId = prefs.getInt("current_student_id", -1)

            if (studentId != -1) {
                val student = studentRepository.getStudentById(studentId)
                student?.let {
                    requireActivity().runOnUiThread {
                        handleStudentFound(it)
                    }
                }
            }
        }
    }
    ```

  ## Реализовал временную страницу логина для дальнейшего теста
  - Создал LoginFragment и соответствующий XML файл который работает с БД
    
  Фрагмент работы с БД:
  
  ```kotlin
  lifecycleScope.launch {
                val student = studentRepository.getStudentByNameAndGroup(name, group)
                if (student != null) {
                    HCEservice.currentStudent = student
                    enableHceForStudent(student)
                    findNavController().navigate(R.id.action_login_to_main)
                } else {
                    Toast.makeText(requireContext(), "Студент не найден", Toast.LENGTH_SHORT).show()
                }
            }
  ```
<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/4d825645-6585-45ce-abd2-0b0b56827eb6" />
<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/99f77a81-d672-46f4-8f41-ff35c7b2ae14" />

## Планы на неделю и issues
- Протестировать HCE и работу приложения в целом (работоспособность требуемых features в проекте)
- Обновить Login page в соответствии с требованиями(RBAC + Registration)
