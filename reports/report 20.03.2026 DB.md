# Отчёт о проделанной работе
**Период:** 3 неделя второго сезона проекта  
**Дата составления:** 20.03.2026  

## Описание проделанной работы

За третью неделю работы над проектом в этом сезоне были выполнены следующие шаги:
* Изучены нормальные формы DB
* Добавлены новые поля DB и таблицы
* Постарался привести к нормальной форме базу данных

## Поэтапное описание

### ___1.Изучена информация по нормальным формам___
 ### Первая нормальная форма (1NF): Устранение дублирующихся записей
Таблица находится в 1NF, если она соответствует следующим условиям:
  * Все столбцы содержат атомарные значения (т. е. неделимые значения)
  * Каждая строка уникальна (отсутствуют дублирующиеся строки)
  * Каждый столбец имеет уникальное имя
  * Порядок хранения данных не имеет значения.

Пример нарушения 1NF: Если в таблице есть столбец «Номера телефонов», в котором хранится несколько номеров в одной ячейке, это нарушает 1NF. Чтобы привести её к 1NF, необходимо разделить номера телефонов по отдельным строкам.

### Вторая нормальная форма (2NF): Устранение частичной зависимости
* Отношение находится в 2NF, если оно удовлетворяет условиям 1NF и, кроме того, отсутствует частичная зависимость. Это означает, что каждый неключевой атрибут должен зависеть от всего первичного ключа целиком, а не от его части.

Пример: Для составного ключа (StudentID, CourseID), если атрибут «StudentName» зависит только от «StudentID», а не от всего ключа, это нарушает 2NF. Для нормализации следует вынести StudentName в отдельную таблицу, где он будет зависеть только от «StudentID».

### Третья нормальная форма (3NF): Устранение транзитивной зависимости
* Отношение находится в 3NF, если оно удовлетворяет условиям 2NF и, кроме того, отсутствуют транзитивные зависимости. Проще говоря, неключевые атрибуты не должны зависеть от других неключевых атрибутов.

Пример: Рассмотрим таблицу (StudentID, CourseID, Instructor). Если Instructor зависит от «CourseID», а «CourseID» зависит от «StudentID», то Instructor косвенно зависит от «StudentID», что нарушает 3NF. Чтобы решить эту проблему, поместите Instructor в отдельную таблицу, связанную через «CourseID».

### Нормальная форма Бойса-Кодда (BCNF): Усиленная форма 3NF
* BCNF - это более строгая версия 3NF, где для каждой нетривиальной функциональной зависимости (X → Y) X должен быть суперключом (уникальным идентификатором записи в таблице).

Пример: Если в таблице есть зависимость (StudentID, CourseID) → Instructor, но ни «StudentID», ни «CourseID» не являются суперключом, это нарушает BCNF. Чтобы привести таблицу к BCNF, декомпозируйте её так, чтобы каждый детерминант был потенциальным ключом.

### Четвертая нормальная форма (4NF): Удаление многозначных зависимостей
* Таблица находится в 4NF, если она находится в BCNF и не имеет многозначных зависимостей. Многозначная зависимость возникает, когда один атрибут определяет другой, и оба этих атрибута не зависят от всех остальных атрибутов в таблице.

Пример: Рассмотрим таблицу с атрибутами (StudentID, Language, Hobby). Если у студента может быть несколько хобби и несколько языков, существует многозначная зависимость. Чтобы устранить её, разделите таблицу на отдельные таблицы для языков (Languages) и хобби (Hobbies).

### Пятая нормальная форма (5NF): Устранение зависимости соединения
* 5NF достигается, когда таблица находится в 4NF и все зависимости соединения удалены. Эта форма гарантирует, что каждая таблица полностью декомпозирована на более мелкие таблицы, которые логически связаны без потери информации.

 Пример: Если таблица содержит (StudentID, Course, Instructor) и существует зависимость, при которой для определенного отношения необходимы все комбинации этих столбцов, вы должны разделить их на более мелкие таблицы для устранения избыточности.
### ___2. Нормализация БД___
* В ходе работы в качестве артефакта работы представлены SQL разметка таблиц (init.sql) приведённая к 3NF(вроде как) и схема DB:
<img width="1087" height="743" alt="image" src="https://github.com/user-attachments/assets/33c8cf80-c548-42f1-b37f-fc79623198fd" />

```SQL
--Кафедры
CREATE TABLE IF NOT EXISTS lecterns (
    lectern_id SERIAL PRIMARY KEY,
    code VARCHAR(10),
    name VARCHAR(255) NOT NULL
);

--Типы контроля
CREATE TABLE IF NOT EXISTS control_types (
    type_id SERIAL PRIMARY KEY,
    type_name VARCHAR(50) UNIQUE NOT NULL
);

--Предметы
CREATE TABLE IF NOT EXISTS subjects (
    subject_id SERIAL PRIMARY KEY,
    subject_index VARCHAR(20),
    name VARCHAR(255) NOT NULL,
    in_plan BOOLEAN DEFAULT TRUE,
    lectern_id INT REFERENCES lecterns(lectern_id) ON DELETE SET NULL
);

--Метрики предмета
CREATE TABLE IF NOT EXISTS subject_metrics (
    subject_id INT PRIMARY KEY REFERENCES subjects(subject_id) ON DELETE CASCADE,
    zet_expert INT,
    zet_fact INT,
    hours_expert INT,
    hours_by_plan INT,
    hours_contr_work INT,
    hours_auditory INT,
    hours_self_study INT,
    hours_control INT,
    hours_prep INT
);

--Распределение нагрузки по семестрам
CREATE TABLE IF NOT EXISTS semester_load (
    load_id SERIAL PRIMARY KEY,
    subject_id INT NOT NULL REFERENCES subjects(subject_id) ON DELETE CASCADE,
    semester_num INT NOT NULL,
    zet_value FLOAT8
);

-- Формы контроля по семестрам
CREATE TABLE IF NOT EXISTS subject_controls (
    control_id SERIAL PRIMARY KEY,
    subject_id INT NOT NULL REFERENCES subjects(subject_id) ON DELETE CASCADE,
    type_id INT NOT NULL REFERENCES control_types(type_id) ON DELETE CASCADE,
    semester_num INT NOT NULL
);

--Преподаватели
CREATE TABLE IF NOT EXISTS teachers (
    teacher_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    lectern_id INT REFERENCES lecterns(lectern_id) ON DELETE SET NULL,
    job_title VARCHAR(50)
);

--Группы
CREATE TABLE IF NOT EXISTS groups (
    group_id SERIAL PRIMARY KEY,
    group_name VARCHAR(30) NOT NULL,
    lectern_id INT REFERENCES lecterns(lectern_id) ON DELETE SET NULL
);

-- Студенты
CREATE TABLE IF NOT EXISTS students (
    student_id SERIAL PRIMARY KEY,
    student_name VARCHAR(100),
    group_id INT REFERENCES groups(group_id) ON DELETE CASCADE
);

-- Типы контроля (наполнение)
INSERT INTO control_types (type_name) 
SELECT unnest(ARRAY['Экзамен', 'Зачет', 'Зачет с оц.'])
ON CONFLICT (type_name) DO NOTHING;
```

