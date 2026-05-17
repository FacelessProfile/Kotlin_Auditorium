# Отчёт о проделанной работе
**Период:** 10 неделя второго сезона проекта  
**Дата составления:** 10.04.2026  

## Описание проделанной работы

За десятую неделю работы над проектом в этом сезоне были выполнены следующие шаги:
* Продумана система оценок совместно с Ромой:
    * Система оценок реализована в DB postgres
    * Написан файл миграции для DB
    * Добавлена логика оценок(набросок) для backend
    * Добавлены структуры данных для backend(система оценок)
* Починен логин в систему на frontend (crypto subtle(no https issue))
* В User UI на android app было добавлено считывание qr кодов и логика логина для соответствия бекенду
  
## Поэтапное описание

### Добавлена система оценок
Преподаватель не должен считать баллы так, чтобы вышло ровно 100. Он просто вносит задания, как ему удобно.
Пример:
Лаба №1 - 10 баллов
Лаба №2 - 10 баллов
РГР - 50 баллов

Итого в базе: 70 баллов.
Но для БРС нужно 100 балльная шкала. Для этого Бекенд при расчете рейтинга просто использует пропорцию.
Если 70 это максимально возможная сумма на текущий момент, то 70 баллов = 100% в БРС

```golang
type GradeItem struct {
    ID        int32
    SubjectID int32
    Title     string
    MaxScore  int32
    ItemType  string
    Deadline  *time.Time
    CreatedAt time.Time
}

type Grade struct {
    StudentID int32
    ItemID    int32
    TeacherID *int32
    Score     int32
    SessionID *int32
    Comment   *string
}

type StudentGradePoint struct {
    ItemID   int32
    Title    string
    MaxScore int32
    ItemType string
    Deadline *time.Time
    Score    int32
    GradedAt *time.Time
}

type PredictionStats struct {
    TotalMax     int32 // W_total (должно быть 100)
    PassedMax    int32 // W_passed (макс баллы за уже прошедшие дедлайны)
    CurrentScore int32 // S_current (сколько студент набрал реально)
}
```

```golang
func (r *GradeRepository) CreateGradeItem(ctx context.Context, item GradeItem) (int32, error) {
    var id int32
    query := `
        INSERT INTO grade_items (subject_id, title, max_score, item_type, deadline)
        VALUES ($1, $2, $3, $4, $5)
        RETURNING item_id`
    
    err := r.pool.QueryRow(ctx, query, 
        item.SubjectID, 
        item.Title, 
        item.MaxScore, 
        item.ItemType, 
        item.Deadline,
    ).Scan(&id)
    
    if err != nil {
        return 0, fmt.Errorf("create grade item: %w", err)
    }
    return id, nil
}

func (r *GradeRepository) GetGradeItemsBySubject(ctx context.Context, subjectID int32) ([]GradeItem, error) {
    query := `
        SELECT item_id, subject_id, title, max_score, item_type, deadline, created_at
        FROM grade_items
        WHERE subject_id = $1
        ORDER BY deadline ASC, created_at ASC`

    rows, err := r.pool.Query(ctx, query, subjectID)
    if err != nil {
        return nil, fmt.Errorf("query grade items: %w", err)
    }
    defer rows.Close()

    var items []GradeItem
    for rows.Next() {
        var i GradeItem
        if err := rows.Scan(&i.ID, &i.SubjectID, &i.Title, &i.MaxScore, &i.ItemType, &i.Deadline, &i.CreatedAt); err != nil {
            return nil, err
        }
        items = append(items, i)
    }
    return items, nil
}

func (r *GradeRepository) UpsertGrade(ctx context.Context, grade Grade) error {
    query := `
        INSERT INTO grades (student_id, item_id, teacher_id, score, session_id, comment)
        VALUES ($1, $2, $3, $4, $5, $6)
        ON CONFLICT (student_id, item_id) 
        DO UPDATE SET 
            score = EXCLUDED.score,
            teacher_id = EXCLUDED.teacher_id,
            comment = EXCLUDED.comment,
            updated_at = now()`

    _, err := r.pool.Exec(ctx, query,
        grade.StudentID,
        grade.ItemID,
        grade.TeacherID,
        grade.Score,
        grade.SessionID,
        grade.Comment,
    )
    if err != nil {
        return fmt.Errorf("upsert grade: %w", err)
    }
    return nil
}

func (r *GradeRepository) GetStudentGradesBySubject(ctx context.Context, studentID, subjectID int32) ([]StudentGradePoint, error) {
    query := `
        SELECT 
            gi.item_id, 
            gi.title, 
            gi.max_score, 
            gi.item_type, 
            gi.deadline,
            COALESCE(g.score, 0) as score,
            g.created_at as graded_at
        FROM grade_items gi
        LEFT JOIN grades g ON g.item_id = gi.item_id AND g.student_id = $1
        WHERE gi.subject_id = $2
        ORDER BY gi.deadline ASC`

    rows, err := r.pool.Query(ctx, query, studentID, subjectID)
    if err != nil {
        return nil, fmt.Errorf("get student grades: %w", err)
    }
    defer rows.Close()

    var points []StudentGradePoint
    for rows.Next() {
        var p StudentGradePoint
        if err := rows.Scan(&p.ItemID, &p.Title, &p.MaxScore, &p.ItemType, &p.Deadline, &p.Score, &p.GradedAt); err != nil {
            return nil, err
        }
        points = append(points, p)
    }
    return points, nil
}

func (r *GradeRepository) GetSubjectStatsForPrediction(ctx context.Context, studentID, subjectID int32) (PredictionStats, error) {
    var stats PredictionStats
    query := `
        SELECT 
            COALESCE(SUM(gi.max_score), 0) as total_max,
            COALESCE(SUM(CASE WHEN gi.deadline < now() THEN gi.max_score ELSE 0 END), 0) as passed_max,
            COALESCE(SUM(g.score), 0) as current_score
        FROM grade_items gi
        LEFT JOIN grades g ON g.item_id = gi.item_id AND g.student_id = $1
        WHERE gi.subject_id = $2`

    err := r.pool.QueryRow(ctx, query, studentID, subjectID).Scan(
        &stats.TotalMax,
        &stats.PassedMax,
        &stats.CurrentScore,
    )
    if err != nil {
        return stats, fmt.Errorf("get prediction stats: %w", err)
    }
    return stats, nil
}
```

### В Android app добавлена поддержка считывания qr

```kotlin
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Сканирование отменено", Toast.LENGTH_LONG).show()
        } else {
            val scannedData = result.contents
            if (scannedData.startsWith("http://") || scannedData.startsWith("https://")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scannedData))
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "QR-код не содержит ссылки: $scannedData", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScanning() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Наведите камеру на QR-код")
        options.setBeepEnabled(true)
        options.setBarcodeImageEnabled(true)
        options.setOrientationLocked(false)
        barcodeLauncher.launch(options)
    }
```
