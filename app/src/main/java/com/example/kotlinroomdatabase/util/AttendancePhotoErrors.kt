package com.example.kotlinroomdatabase.util

object AttendancePhotoErrors {
    const val GRADE_REFRESH_FAILED = "Посещаемость сохранена, но баллы ещё не обновились. Повторите подтверждение."
    fun message(raw: String): String = when (raw) {
        "face recognition is disabled" -> "Распознавание пока не подключено к серверу"
        "forbidden: face recognition is not enabled for this group" -> "Администратор не включил распознавание для этой группы"
        "photo queue or session photo limit reached" -> "Очередь заполнена или достигнут предел: 100 фото на занятие. Дождитесь обработки."
        "use a JPEG, PNG or WebP photograph" -> "Выберите фото в формате JPEG, PNG или WebP"
        "photograph exceeds 20 megapixels" -> "Выберите фото размером до 20 мегапикселей"
        "photograph exceeds 15 MiB", "photograph must be between 1 byte and 15 MiB" -> "Размер фото должен быть не больше 15 МБ"
        "a student may occur only once in one photograph" -> "Один студент выбран для двух лиц. Исправьте выбор."
        "student is not in this photograph's group and session roster" -> "Студент не входит в выбранную группу этого занятия"
        "photograph already confirmed; correct attendance in the journal" -> "Фото уже подтверждено. Исправьте посещаемость в списке студентов."
        "attendance saved; grade refresh failed; repeat confirmation" -> GRADE_REFRESH_FAILED
        "semester is not open for changes", "semester has ended" -> "Семестр закрыт для изменений"
        "semester has not started" -> "Семестр ещё не начался"
        "photograph is already being processed" -> "Фото уже обрабатывается. Дождитесь результата."
        else -> ApiErrorMapper.mapErrorMessage(raw, "Не удалось выполнить действие. Попробуйте ещё раз.")
    }
}
