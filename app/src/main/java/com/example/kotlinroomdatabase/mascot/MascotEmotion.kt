package com.example.kotlinroomdatabase.mascot

import com.example.kotlinroomdatabase.R

/**
 * Эмоциональные состояния маскота СибгУтёнка (вдохновлено Duolingo).
 */
enum class MascotEmotion(
    val title: String,
    val iconResId: Int,
    val primaryColorHex: String
) {
    ON_FIRE(
        title = "Ударный режим",
        iconResId = R.drawable.ic_sibgutenok_on_fire,
        primaryColorHex = "#F59E0B" // Огненно-янтарный
    ),
    PROUD(
        title = "Гордость",
        iconResId = R.drawable.ic_sibgutenok_proud,
        primaryColorHex = "#10B981" // Изумрудный успех
    ),
    WATCHFUL(
        title = "Наблюдение",
        iconResId = R.drawable.ic_sibgutenok_watchful,
        primaryColorHex = "#1F55D8" // Фирменный синий СибГУТИ
    ),
    SAD(
        title = "Драма и слёзы",
        iconResId = R.drawable.ic_sibgutenok_sad,
        primaryColorHex = "#607891" // Грустный приглушенный
    ),
    UNHINGED(
        title = "Пассивная агрессия",
        iconResId = R.drawable.ic_sibgutenok_unhinged,
        primaryColorHex = "#DC2626" // Опасный красный
    ),
    GHOST(
        title = "Призрак аудиторий",
        iconResId = R.drawable.ic_sibgutenok_ghost,
        primaryColorHex = "#8B5CF6" // Мистический фиолетовый
    ),
    DOOM(
        title = "Угроза отчисления",
        iconResId = R.drawable.ic_sibgutenok_doom,
        primaryColorHex = "#991B1B" // Багрово-аварийный
    )
}
