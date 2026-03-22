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