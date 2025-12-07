# Отчёт о проделанной работе
**Период:** 13 неделя проекта  
**Дата составления:** 05.12.2025  

## Описание проделанной работы

За двенадцатую неделю разработки были успешно выполнены следующие шаги:
- Проведён небольшой рефакторинг кода
- Настройки ZMQ были вынесены в отдельный kt obj

## Поэтапное описание

### Разработан RepositoryZMQ.kt 
Файл представляет из себя обьект, через который другие фрагменты получают StudentRepository,ZmqSender и соответственно становится гораздо проще настраивать проект, большее соответствие DRY принципу.

``` kotlin
object RepositoryZMQ {
    private var _studentRepository: StudentRepository? = null
    private var _sharedPreferences: SharedPreferences? = null
    fun initialize(context: Context) {
        if (_studentRepository == null) {
            val database = StudentDatabase.getInstance(context)
            val zeroMQSender = ZmqSockets("tcp://192.168.0.19:5555") // CHANGE ME
            _studentRepository = StudentRepository(database.studentDao(), zeroMQSender)
            _sharedPreferences = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getStudentRepository(context: Context): StudentRepository {
        initialize(context)
        return _studentRepository!!
    }

    fun getSharedPreferences(context: Context): SharedPreferences {
        initialize(context)
        return _sharedPreferences!!
    }

    fun getCurrentStudentId(context: Context): Int {
        return getSharedPreferences(context).getInt("current_student_id", -1)
    }
}
```

## TODO
- Продолжить работу над упрощением настройки общения с сервером
- Продолжить работу над регистрацией/логином user'ов
