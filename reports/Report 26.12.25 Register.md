# Отчёт о проделанной работе
**Период:** 15 неделя проекта  
**Дата составления:** 26.12.2025  

## Описание проделанной работы

За пятнадцату неделю разработки были успешно выполнены следующие шаги:
* Добавлены методы для регистрации в Repository
* Теперь у каждого user есть role (admin,student)
* Обновлена серверная часть кода (обработка register,выдача тегов)
* Переделан login page и добавлена регистрация

## Поэтапное описание (основные изменения)

- Обновил код python server'a для обработки новых запросов:
Для защиты используется RSA шифрование + хеши хранятся в отдельной таблице. Теперь сервер отдаёт nfc hce tag.
  ```python3
    def process_single_student(self, data):
        operation = data.get('operation')
        student_data = data.get('data', {})
        if operation == 'test': return "Test connection successful"

        connection = psycopg2.connect(**self.db_config)
        try:
            cursor = connection.cursor()
            if operation == "login":
                login = student_data.get('login')
                raw_pass = self.decrypt_password(student_data.get('password'))
                cursor.execute("""
                    SELECT s.id, s.student_name, s.student_group, s.student_nfc, s.attendance, u.role, u.password_hash
                    FROM user_accounts u JOIN students s ON u.student_id = s.id WHERE u.login = %s
                """, (login,))
                row = cursor.fetchone()
                if row and bcrypt.checkpw(raw_pass.encode('utf-8'), row[6].encode('utf-8')):
                    return {"role": row[5], "student": {"id": row[0], "studentName": row[1], "studentGroup": row[2], "studentNFC": row[3], "attendance": row[4]}}
                return {"status": "error", "message": "Invalid credentials"}

            elif operation == "register":
                login = student_data.get('login')
                group = student_data.get('group')
                raw_pass = self.decrypt_password(student_data.get('password'))
                nfc_label = f"STUD-{uuid.uuid4().hex[:8].upper()}"

                hashed = bcrypt.hashpw(raw_pass.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')

                cursor.execute("INSERT INTO students (student_name, student_group,student_nfc, role) VALUES (%s, %s,%s, 'student') RETURNING id", (login, group,nfc_label))
                s_id = cursor.fetchone()[0]
                cursor.execute("INSERT INTO user_accounts (student_id, login, password_hash, role) VALUES (%s, %s, %s, 'student')", (s_id, login, hashed))
                connection.commit()
                return {"role": "student", "student": {"id": s_id, "studentName": login, "studentGroup": group, "studentNFC": nfc_label, "attendance": False}}

  ```
  
  <img width="1280" height="339" alt="image" src="https://github.com/user-attachments/assets/aead9349-46e9-499e-b34c-9ed9103cf023" />
  
  <img width="1061" height="111" alt="image" src="https://github.com/user-attachments/assets/f22943af-0be4-486a-b163-191163c07ed1" />


- Добавил метод в student repository для register student'a:
    ```kotlin
            @OptIn(InternalSerializationApi::class)
    suspend fun register(name: String, group: String, passwordRaw: String): LoginResult {
        return try {
            val encryptedPassword = Crypto.encryptPassword(passwordRaw)
             val jsonRequest = JSONObject().apply {
                put("operation", "register")
                put("data", JSONObject().apply {
                    put("login", name)
                    put("group", group)
                    put("password", encryptedPassword)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString()) ?: return LoginResult.Error("Сервер недоступен")
            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                parseAndSaveStudent(jsonResponse)
            } else {
                LoginResult.Error(jsonResponse.optString("message", "Ошибка регистрации"))
            }
        } catch (e: Exception) {
            LoginResult.Error("Ошибка связи: ${e.message}")
        }
    }

    ```
     
- В проекте был добавлен новый обькт Crypto для шифрования стратегией X509
  ``` kotlin
  object Crypto {
    private const val SERVER_PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvNnAQd+SLd8B7beYvJBR4XgYWTgzWJ/cgrSFvSxMvDHXwNQneA6twQuuBpuNuG6wB176oFuyNNXjHJ04KZZdp/UdVuJ1X4nfWg3wZ8zRPqI8zy7eJpmZOShTisQnp7UuJrs0eek5x29YkXxDV4/lIB4LnYXzGnX9sFhJi2uv4of2eqxy7MctCwt0UwPJjr3c4OyJ++m+sJk/VmkognW2xmVfhqWG2juxhoqcygGGYYEZDiqVcRzuKIgmsg9qtjEKDXOisYwBjifwOVR9MgGCn+tSCo/WqbtdEhe9Lcm3ivOYGsFsvHFr7p6v4u4fi5lL6yt5avq+yDT7npWsQVRfFQIDAQAB"
    fun encryptPassword(password: String): String {
        return try {
            val keyBytes = Base64.decode(SERVER_PUBLIC_KEY_B64, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)

            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)

            val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
  } 
  ```

- Проведена работа с Login page
  <img width="651" height="1280" alt="image" src="https://github.com/user-attachments/assets/0efe0491-f16b-4e9c-8740-8647a18cf4a9" />


  ## TODO:
  - Проработать интерфейс пользователя
  - Согласовать дальнейшие действия
  
  
