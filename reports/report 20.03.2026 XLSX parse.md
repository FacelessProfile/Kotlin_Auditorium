# Отчёт о проделанной работе
**Период:** 4 неделя второго сезона проекта  
**Дата составления:** 27.03.2026  

## Описание проделанной работы

За четвёртую неделю работы над проектом в этом сезоне были выполнены следующие шаги:
* Изучена библиотека pandas для базовой работы с xlsx files
* Написан xlsx parser план-свода

## Поэтапное описание

### ___1.Изучен pandas___
Pandas — это библиотека для анализа табличных данных, где основной структурой является DataFrame (двумерная таблица). 
В контексте нашего проекта она заменяет ручной парсинг Excel файла, беря на себя индексацию и типизацию данных. 
С помощью него стало возможным написание минимального скрипта который бы парсил данные из листа План-свод:
```SQL
def parse_plan_svod(file_path):
    df = pd.read_excel(file_path, sheet_name="ПланСвод", header=2)
    df = df[df.iloc[:, 2].astype(str).str.contains('Б1', na=False)]

    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    try:
        for _, row in df.iterrows():
            row = row.fillna(0)
            l_code = str(row.iloc[28]).replace('.0', '')
            l_name = row.iloc[29]
            
            if pd.notna(l_code):
                cur.execute("""
                    INSERT INTO lecterns (code, name) VALUES (%s, %s)
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
                    RETURNING lectern_id
                """, (l_code, l_name))
                lectern_id = cur.fetchone()[0]
            else:
                lectern_id = None

            cur.execute("""
                INSERT INTO subjects (subject_index, name, in_plan, lectern_id)
                VALUES (%s, %s, %s, %s) RETURNING subject_id
            """, (row.iloc[2], row.iloc[3], row.iloc[1] == '+', lectern_id))
            subject_id = cur.fetchone()[0]
            cur.execute("""
                INSERT INTO subject_metrics (subject_id, zet_expert, zet_fact, hours_expert, 
                hours_by_plan, hours_contr_work, hours_auditory, hours_self_study, hours_control)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (subject_id, row.iloc[11], row.iloc[12], row.iloc[13], 
                  row.iloc[14], row.iloc[15], row.iloc[16], row.iloc[17], row.iloc[18]))
            control_map = {
    	        4: 1,   # Экзамен
    	        5: 2,   # Зачет
    	        6: 3,   # Зачет с оц.
    	        7: 7,   # КП
              8: 8,   # КР
    	        9: 9,   # Реферат
    	        10: 10  # РГР
            } # ID из control_types
            for col_idx, type_id in control_map.items():
                sems = split_semesters(row.iloc[col_idx])
                for s in sems:
                    cur.execute("""
                        INSERT INTO subject_controls (subject_id, type_id, semester_num)
                        VALUES (%s, %s, %s)
                    """, (subject_id, type_id, s))
            for i, sem_idx in enumerate(range(20, 28)):
                zet_val = row.iloc[sem_idx]
                if pd.notna(zet_val) and zet_val > 0:
                    cur.execute("""
                        INSERT INTO semester_load (subject_id, semester_num, zet_value)
                        VALUES (%s, %s, %s)
                    """, (subject_id, i + 1, zet_val))

        conn.commit()
        print("Парсинг успешно завершен!")
    except Exception as e:
        conn.rollback()
        print(f"Ошибка при парсинге: {e}(((")
    finally:
        cur.close()
        conn.close()

```
<img width="1771" height="886" alt="изображение" src="https://github.com/user-attachments/assets/f33d7cce-1304-4cb5-aa14-6602db49e6de" />

### ___2.TODO___
- [ ] Выявлена проблема что невозможно остановить докер контейнера (permission denied) нужно разобраться
- [ ] Пофиксить init файл базы данных!!!
- [ ] Разобраться как парсить другие данные из таблицы
- [ ] Посмотреть в строну QR кодов в Android app (реализация)
