# Отчёт о проделанной работе
**Период:** 17 неделя проекта  
**Дата составления:** 16.01.2026  

## Описание проделанной работы

За семнадцатую неделю разработки были успешно выполнены следующие шаги:
* Избавился от ненужного легаси в коде
* Обновил пути навигации в приложении
* Добавил фрагмент для создания занятий в приложении
* Сделал конфигурационный файл для занятий
* Решил проблему с gradle

## Поэтапное описание (основные изменения)
  * В ходе работы неожиданно возникла ошибка сборки с gradle, которая отняла критически много времени и отсылала на Unknown location problem(как в итоге оказалось старый JDK + зависимости, следовательно они были обновлены)
  * Легаси код был по возможности был удалён (всё форкнутое удалено, самописное оставлено до дальнейшего согласования)
  * Теперь, после входа в систему admin'а переносит в небольшое меню, где он может выбрать смотреть список всех зарегистрированных студентов(старый list_fragment) или же начать занятие.
  * Добавлена, но не в полной мере реализована(есть временная логика) система занятий с автокомплит заполнением полей
  <img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/3eae5588-c5b0-4122-ae66-c153d6c2ac75" />
  <img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/4e2ca7d6-5ab6-4286-995a-2953d3629a6c" />
  
  * Добавлен конфиг для занятий
    ```
    object LessonsConfig {
      val SUBJECTS_POOL = listOf(
        "Математика",
        "Информатика",
        "Физика",
        "Программирование",
        "ООП",
        "Визуальное программирование",
        "Базы данных",
        "Вычислительная математика"
      )
    const val PREFS_NAME = "student_prefs"
    const val KEY_TEACHER_ID = "teacher_id"
    }
    ```
Возможно стоит разместить его на сервере(предметы), но это увеличит число обращений и увеличит задержки.

* Дополнен Repository для запросов на сервер
  ```kotlin
      @OptIn(InternalSerializationApi::class)
    suspend fun createLesson(subject: String, teacherId: Int, groups: List<String>): Int? {
        return try {
            val newLesson = Lesson(
                subject = subject,
                date = System.currentTimeMillis(),
                groups = groups.joinToString(", ")
            )
            val localId = studentDao.insertLesson(newLesson).toInt()
            val jsonRequest = JSONObject().apply {
                put("operation", "create_lesson")
                put("data", JSONObject().apply {
                    put("subject", subject)
                    put("teacher_id", teacherId)
                    put("groups", JSONArray(groups))
                    put("local_id", localId)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString())

            if (response != null) {
                val jsonResponse = JSONObject(response)
                if (jsonResponse.optString("status") == "success") {
                    return jsonResponse.optInt("lesson_id", localId)
                }
            }
            localId
        } catch (e: Exception) {
            null
        }
    }
  ```

  ## TODO:
  - Обновить серверную часть
  - Продолжить работу над системой занятий (удалить временную логику и поработать с БД)
  - Доработать интерфейс
  - Согласовать будущие изменения
    - Уже переходить к qr коду надо бы......
  
  
