package com.example.kotlinroomdatabase.mascot

import com.example.kotlinroomdatabase.R

/**
 * Доступные маскоты приложения с уникальными фразами и визуалом.
 */
enum class MascotType(
    val displayName: String,
    val description: String
) {
    SIB_GUTENOK(
        displayName = "СибгУтёнок",
        description = "Маскот посещаемости СибГУТИ"
    ),
    SQUIRREL_COMM(
        displayName = "Белка-Связист",
        description = "Инженер связи и гроза оптоволокна"
    );

    fun getIconResId(emotion: MascotEmotion): Int {
        return when (this) {
            SIB_GUTENOK -> emotion.iconResId
            SQUIRREL_COMM -> when (emotion) {
                MascotEmotion.ON_FIRE -> R.drawable.ic_squirrel_on_fire
                MascotEmotion.PROUD -> R.drawable.ic_squirrel_proud
                MascotEmotion.WATCHFUL -> R.drawable.ic_squirrel_watchful
                MascotEmotion.SAD -> R.drawable.ic_squirrel_sad
                MascotEmotion.UNHINGED -> R.drawable.ic_squirrel_unhinged
                MascotEmotion.GHOST -> R.drawable.ic_squirrel_ghost
                MascotEmotion.DOOM -> R.drawable.ic_squirrel_doom
            }
        }
    }

    companion object {
        fun getRandom(): MascotType {
            return values().random()
        }
    }
}
