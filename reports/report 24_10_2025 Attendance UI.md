# Отчёт о проделанной работе
**Стадия/Этап:**  7 неделя проекта  
**Дата составления:** 24.10.2025  

## Описание проделанной работы

Во время седьмой недели разработки были успешно проделаны следующие шаги:
- Убран ненужный кодовый функционал и заменён на релевантные для проекта методы
- Добавлен интерфейс для отметки студентов в виде отдельного fragment
- Произведён небольшой рефакторинг кода для соответствия новому функционалу
  
## Поэтапное описание

### Убран ненужный функционал
В проекте ранее был groupFragment и его xml, который ранее не использовался
и в ходе работы был переименован в NFC_AttendanceFragment, далее адаптирован для создания нового окна-фрагмента для работы с отметкой посещения

### Добавление интерфейса для отметки студентов
Теперь включение режима чтения меток происходит при переходе на новое окно, с которым гораздо удобнее работать.\
Само окно при чтении метки даёт визуально понять: 
- Отмечен ли студент после чтения метки
- Студента ещё нет в базе данных и ему нужно зарегистрироваться
- Студент уже был ранее отмечен

После перехода в одно из состояний (Success,Warning,Error) оно автоматически возвращается в исходное - Neutral через 3 секунды.
 
<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/003619a2-b8ae-47fd-9919-c1d0c39ca117" />

<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/f4e3e259-58e8-4042-a113-20740a2244fa" />

<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/20b7d2ba-20f2-4783-b9c2-e9e891b7c473" />

<img width="562" height="1280" alt="image" src="https://github.com/user-attachments/assets/1ed7152a-2a92-47cc-8593-347f7460f07e" />

### Логика
```kotlin
 override fun processNfcTag(nfcId: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "NFC TAG СЧИТАН: $nfcId", Toast.LENGTH_LONG).show()

            if (nfcId.isNotBlank()) {
                val existingStudent = studentList.find { it.studentNFC == nfcId }

                if (existingStudent != null) {
                    if (existingStudent.attendance) {
                        // Студент уже отмечен
                        setWarningState()
                        statusText.text = "${existingStudent.studentName}\nуже отмечен"
                    } else {
                        // Студент найден и не отмечен
                        markStudentAttendance(existingStudent)
                        setSuccessState()
                        statusText.text = "${existingStudent.studentName}\nотмечен присутствующим"
                    }
                } else {
                    setErrorState()
                    statusText.text = "Студент не найден"
                }

                rootLayout.postDelayed({
                    setNeutralState()
                    statusText.text = "Поднесите следующую метку"
                }, 3000)
            } else {
                setErrorState()
                statusText.text = "Ошибка чтения метки"
                rootLayout.postDelayed({
                    setNeutralState()
                    statusText.text = "Поднесите следующую метку"
                }, 3000)
            }
        }
    }
```
  

### TODO/Issues
- [ ] Работать над регистрацией студента в базе
