package com.example.kotlinroomdatabase.streak

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.mascot.MascotPhrases
import com.example.kotlinroomdatabase.mascot.MascotType
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Вспомогательный класс для отображения красивого модального окна стрика и маскота.
 */
object StreakDialogHelper {

    fun showStreakInfoDialog(context: Context, onMascotChanged: ((MascotType) -> Unit)? = null) {
        val streak = StreakManager.getCurrentStreak(context)
        val bestStreak = StreakManager.getBestStreak(context)
        val freezes = StreakManager.getFreezesRemaining(context)
        val totalAttended = StreakManager.getTotalAttended(context)
        val emotion = StreakManager.getMascotEmotion(context)
        var currentMascot = StreakManager.getCurrentMascotType(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_streak_info, null)
        val ivMascot = dialogView.findViewById<ImageView>(R.id.ivDialogMascot)
        val tvMascotName = dialogView.findViewById<TextView>(R.id.tvDialogMascotName)
        val tvMascotStatus = dialogView.findViewById<TextView>(R.id.tvDialogMascotStatus)
        val switchBadge = dialogView.findViewById<View>(R.id.layoutMascotSwitchBadge)
        val tvQuote = dialogView.findViewById<TextView>(R.id.tvDialogMascotQuote)
        val tvCurrentStreak = dialogView.findViewById<TextView>(R.id.tvDialogCurrentStreak)
        val tvBestStreak = dialogView.findViewById<TextView>(R.id.tvDialogBestStreak)
        val tvFreezes = dialogView.findViewById<TextView>(R.id.tvDialogFreezes)
        val tvTotalAttended = dialogView.findViewById<TextView>(R.id.tvDialogTotalAttended)
        val btnClose = dialogView.findViewById<View>(R.id.btnDialogClose)

        fun updateMascotUI(m: MascotType) {
            ivMascot.setImageResource(m.getIconResId(emotion))
            tvMascotName.text = m.displayName
            tvMascotStatus.text = m.description
            val quote = MascotPhrases.getPhrase(m, emotion, streak)
            tvQuote.text = "«$quote»"
        }

        updateMascotUI(currentMascot)

        val switchAction = View.OnClickListener {
            currentMascot = if (currentMascot == MascotType.SIB_GUTENOK) {
                MascotType.SQUIRREL_COMM
            } else {
                MascotType.SIB_GUTENOK
            }
            StreakManager.setMascotType(context, currentMascot)
            updateMascotUI(currentMascot)
            onMascotChanged?.invoke(currentMascot)
            Toast.makeText(context, "Выбран персонаж: ${currentMascot.displayName}", Toast.LENGTH_SHORT).show()
        }

        ivMascot.setOnClickListener(switchAction)
        switchBadge?.setOnClickListener(switchAction)

        tvCurrentStreak.text = "$streak ${getPluralPairs(streak)}"
        tvBestStreak.text = "$bestStreak ${getPluralPairs(bestStreak)}"
        tvFreezes.text = "$freezes ${getPluralFreezes(freezes)}"
        tvTotalAttended.text = "$totalAttended"

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    fun getPluralPairs(n: Int): String {
        val n100 = n % 100
        val n10 = n % 10
        return when {
            n100 in 11..19 -> "пар"
            n10 == 1 -> "пара"
            n10 in 2..4 -> "пары"
            else -> "пар"
        }
    }

    fun getPluralFreezes(n: Int): String {
        val n100 = n % 100
        val n10 = n % 10
        return when {
            n100 in 11..19 -> "пропусков"
            n10 == 1 -> "пропуск"
            n10 in 2..4 -> "пропуска"
            else -> "пропусков"
        }
    }
}
