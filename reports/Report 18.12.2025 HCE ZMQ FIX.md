# Отчёт о проделанной работе
**Период:** 14 неделя проекта  
**Дата составления:** 18.12.2025  

## Описание проделанной работы

За четырнадцаую неделю разработки были успешно выполнены следующие шаги:
* В связи с обнаруженными ранее ошибками в логике общения android app и zmq была провдена работа с:
  * Удаление старых записей из БД и изменение её параметров
  * Обновлён repository file
  * Изменён код python server'a для обработки новых queries
  * Изменён порт на маршрутизаторе(к которому подключён "удалённый" сервер с "белым" ip) + NAT LOOPBACK (Нахожусь в одной сети с роутером и сервером)
* Была обнаружена и исправлена частичная неработоспособность APDU HCE сервиса

## Поэтапное описание (основные изменения)

- Обновил код python server'a для обработки новых запросов - отдача данных о юзерах из бд
  ```python3
  
    def process_batch_students(self, data_list):
        connection = psycopg2.connect(**self.db_config)
        try:
            cursor = connection.cursor()
            logger.info(f"Processing batch of {len(data_list)} students")

            for item in data_list:
                student_data = item.get('data', {})
                self._upsert_student(cursor, student_data, item.get('operation'))

            connection.commit()
            return f"Processed {len(data_list)} records"
        except Exception as e:
            connection.rollback()
            raise Exception(f"Database error in batch: {e}")
        finally:
            connection.close()

    def _upsert_student(self, cursor, student_data, operation):
        android_id = student_data.get('id')
        name = student_data.get('studentName')
        group = student_data.get('studentGroup')
        nfc_id = student_data.get('studentNFC', '')
        attendance = student_data.get('attendance', False)
        cursor.execute("""
            INSERT INTO students
            (android_id, student_name, student_group, student_nfc, attendance, operation_type)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (student_name, student_group)
            DO UPDATE SET
                student_nfc = CASE
                    WHEN EXCLUDED.student_nfc <> '' THEN EXCLUDED.student_nfc
                    ELSE students.student_nfc
                END,
                android_id = EXCLUDED.android_id,
                attendance = EXCLUDED.attendance,
                operation_type = EXCLUDED.operation_type,
                synced_at = CURRENT_TIMESTAMP
        """, (android_id, name, group, nfc_id, attendance, operation))

    def process_single_student(self, data):
        if data.get('operation') == 'test':
            return "Test connection successful"

        connection = psycopg2.connect(**self.db_config)
        try:
            cursor = connection.cursor()
            operation = data.get('operation')
            student_data = data.get('data', {})
            if operation == "sync_all":
                cursor.execute("SELECT android_id, student_name, student_group, student_nfc, attendance FROM students")
                return {
                    "students": [
                        {
                            "id": r[0], "studentName": r[1], "studentGroup": r[2],
                            "studentNFC": r[3], "attendance": r[4]
                        } for r in cursor.fetchall()
                    ]
                }
            elif operation == "delete":
                name = student_data.get('studentName')
                group = student_data.get('studentGroup')
                cursor.execute("DELETE FROM students WHERE student_name = %s AND student_group = %s", (name, group))
                result = f"Deleted: {name}"
            elif operation == "get_group":
                group = student_data.get('studentGroup')
                cursor.execute("SELECT student_name, student_group, student_nfc, attendance FROM students WHERE student_group = %s", (group,))
                return {
                    "students": [
                        {"studentName": r[0], "studentGroup": r[1], "studentNFC": r[2], "attendance": r[3]}
                        for r in cursor.fetchall()
                    ]
                }
            else:
                self._upsert_student(cursor, student_data, operation)
                result = f"Processed {student_data.get('studentName')}"

            connection.commit()
            return result
    ```
  - Добавил метод в student repository для поиска студента в DB:
    ```kotlin
        @OptIn(InternalSerializationApi::class)
    suspend fun searchStudentOnServer(name: String, group: String): Student? {
        return try {
            if (zeroMQSender == null) {
                Log.e("StudentRepository", "ZeroMQ sender is null")
                return null
            }

            val searchData = JSONObject().apply {
                put("operation", "search")
                put("data", JSONObject().apply {
                    put("studentName", name)
                    put("studentGroup", group)
                })
            }

            Log.d("StudentRepository", "Searching on server: $name, $group")
            val response = zeroMQSender.sendData(searchData.toString())
            Log.d("StudentRepository", "Server response: $response")

            val json = JSONObject(response)
            if (json.optString("status") != "success") {
                Log.e("StudentRepository", "Server returned error status")
                return null
            }
            val data = json.optJSONObject("data")
            if (data == null) {
                Log.e("StudentRepository", "No data field in response")
                return null
            }

            Log.d("StudentRepository", "Parsed data: $data")

            val student = Student(
                id = 0,
                studentName = data.getString("studentName"),
                studentGroup = data.getString("studentGroup"),
                studentNFC = data.optString("studentNFC", ""),
                attendance = data.optBoolean("attendance", false)
            )

            Log.d("StudentRepository", "Created student: ${student.studentName}")
            return student

        } catch (e: Exception) {
            Log.e("StudentRepository", "Search on server failed", e)
            null
        }
    }
    ```
     
  - Обнаружил и исправил проблему, что HCE APDU не отрабатывал как нужно и читалась метка UID даже на смартфонах с IsoDep поддержкой
  ```kotlin
  // Callback при подносе метки
    protected val nfcReaderCallback = NfcAdapter.ReaderCallback { tag ->
        // читаем как HCE устройство(смартфон)
        val hceData = readHcePayload(tag)

        val resultString = if (!hceData.isNullOrBlank()) {
            Log.d("NFC_TOOLS", "HCE detected. Payload: $hceData")
            hceData
        } else {
            // Если не HCE возвращаем физический UID
            val uid = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
            Log.d("NFC_TOOLS", "Standard Tag detected. UID: $uid")
            uid
        }
        requireActivity().runOnUiThread {
            processNfcTag(resultString)
        }
    }

    private fun readHcePayload(tag: Tag): String? {
        val isoDep = IsoDep.get(tag) ?: return null

        return try {
            isoDep.connect()
            val aidBytes = hexStringToByteArray(SERVICE_AID)
            val selectCommand = buildSelectApdu(aidBytes)

            Log.d("NFC_TOOLS", "Sending APDU: ${selectCommand.joinToString("") { "%02X".format(it) }}")
            val response = isoDep.transceive(selectCommand)

            Log.d("NFC_TOOLS", "Response received: ${response.joinToString("") { "%02X".format(it) }}")
            val responseLength = response.size
            if (responseLength >= 2 &&
                response[responseLength - 2] == 0x90.toByte() &&
                response[responseLength - 1] == 0x00.toByte()
            ) {
                val payloadBytes = response.copyOfRange(0, responseLength - 2)
                String(payloadBytes, Charset.forName("UTF-8"))
            } else {
                null // Ошибка
            }
        } catch (e: IOException) {
            Log.e("NFC_TOOLS", "IsoDep connection failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e("NFC_TOOLS", "General error: ${e.message}")
            null
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception){}
        }
    }
  ```
  #### В итоге имеем что данные передаются на сервер и записываются в базу данных. UID заменён на STUD ID.
  <img width="676" height="171" alt="image" src="https://github.com/user-attachments/assets/fcec45ab-aedc-42ec-977c-7e7c375f3f2e" />
  
  _MORE SCREENSHOTS WILL BE PLACED ASAP_

  ## TODO:
  - [ ] Начать работу с GPG
  - [ ] Продумать процесс регистрации
  
  
