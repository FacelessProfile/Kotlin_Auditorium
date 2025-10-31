# Отчёт о проделанной работе
**Период:** 8 неделя проекта  
**Дата составления:** 31.10.2025  

## Описание проделанной работы

За восьмую неделю разработки были успешно выполнены следующие шаги:
- Был изучен материал по Dao
- Написаны:
  - Интерфейс StudentDao.kt
  - StudentDatabase.kt
- Обновлён data class Student
  
## Поэтапное описание

### Написан интерфейс StudentDao.kt
  За основу нового StudentDao изучил пример из forked repository и написал новый,подходящий для DB
  ```kotlin
  @Dao
interface StudentDao {
    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student ORDER BY studentName")
    fun getAllStudents(): Flow<List<Student>>

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE studentGroup = :group ORDER BY studentName")
    fun getStudentsByGroup(group: String): Flow<List<Student>>

    @Query("SELECT DISTINCT studentGroup FROM student ORDER BY studentGroup")
    fun getAllGroups(): Flow<List<String>>

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE studentNFC = :nfcId")
    suspend fun getStudentByNfc(nfcId: String): Student?

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE id = :id")
    suspend fun getStudentById(id: Int): Student?

    @OptIn(InternalSerializationApi::class)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: Student)

    @OptIn(InternalSerializationApi::class)
    @Update
    suspend fun updateStudent(student: Student)

    @OptIn(InternalSerializationApi::class)
    @Delete
    suspend fun deleteStudent(student: Student)

    @Query("DELETE FROM student")
    suspend fun deleteAllStudents()

    @Query("UPDATE student SET attendance = :attendance WHERE id = :id")
    suspend fun updateAttendance(id: Int, attendance: Boolean)
}
```


### Data Class Student
Внёс небольшие изменения для совместимости с DB:
```kotlin
@Entity(tableName = "student")
data class Student(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val studentNFC: String = "", // NFC (Device/tag) ID
    val studentName: String,
    val studentGroup: String,
    val attendance: Boolean,
)
```

### Написан StudentDatabase.kt
Код отвечает за создание SQLite Database

```kotlin
abstract class StudentDatabase : RoomDatabase() {
            abstract fun studentDao(): StudentDao

            companion object {
                @Volatile
                private var INSTANCE: StudentDatabase? = null

                fun getInstance(context: Context): StudentDatabase {
                    return INSTANCE ?: synchronized(this) {
                        val instance = Room.databaseBuilder(
                            context.applicationContext,
                            StudentDatabase::class.java,
                            "student_database"
                        ).build()
                        INSTANCE = instance
                        instance
                    }
                }
    }
}
```

## Планы на неделю и issues

- Дописать StudentRepository.kt по примеру из форка.
- Разобраться с SQLlite и бд
- Адаптировать методы обновления и добавления под SQLite БД
