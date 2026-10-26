package com.example.kotlinroomdatabase

import android.content.Context

/** One saved choice for the application shell, shared screens, and analytics. */
object InterfaceAppearance {
    private const val KEY = "interface_design"
    fun isModern(context: Context): Boolean =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString(KEY, "modern") != "classic"

    fun setModern(context: Context, modern: Boolean) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putString(KEY, if (modern) "modern" else "classic").apply()
    }
}
