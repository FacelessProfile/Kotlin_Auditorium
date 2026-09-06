package com.example.kotlinroomdatabase.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ApiErrorMapper {

    fun mapError(throwable: Throwable?, fallbackMessage: String = "Произошла ошибка при обращении к серверу"): String {
        return when (throwable) {
            is UnknownHostException, is ConnectException -> "Нет подключения к серверу. Проверьте интернет-соединение"
            is SocketTimeoutException -> "Сервер не ответил вовремя. Попробуйте повторить запрос"
            null -> fallbackMessage
            else -> {
                val msg = throwable.message.orEmpty()
                mapErrorMessage(msg, fallbackMessage)
            }
        }
    }

    fun mapHttpStatus(statusCode: Int, rawError: String? = null): String {
        val serverMsg = rawError?.takeIf { it.isNotBlank() }
        return when (statusCode) {
            400 -> serverMsg ?: "Некорректный запрос"
            401 -> "Срок действия сессии истёк. Пожалуйста, войдите снова"
            403 -> serverMsg ?: "Доступ к этому действию ограничен"
            404 -> serverMsg ?: "Запрашиваемые данные не найдены"
            409 -> serverMsg ?: "Конфликт данных"
            422 -> serverMsg ?: "Некорректные параметры запроса"
            429 -> "Слишком много запросов. Подождите немного"
            500, 502, 503, 504 -> "Сервер временно недоступен. Попробуйте позже"
            else -> serverMsg ?: "Ошибка сервера ($statusCode)"
        }
    }

    fun mapErrorMessage(rawError: String?, fallbackMessage: String = "Ошибка загрузки"): String {
        if (rawError.isNullOrBlank()) return fallbackMessage
        val lower = rawError.lowercase()
        return when {
            lower.contains("invalid credentials") || lower.contains("invalid username or password") || lower.contains("user not found") || lower.contains("wrong password") ->
                "Неверный логин или пароль"
            lower.contains("invalid or used invite_code") || lower.contains("used invite_code") || lower.contains("invalid invite") ->
                "Неверный или уже использованный инвайт-код"
            lower.contains("already registered") || lower.contains("уже зарегистрирован") ->
                "Профиль уже зарегистрирован. Для доступа используйте восстановление пароля"
            lower.contains("invite_code is required") || lower.contains("invite code required") ->
                "Пожалуйста, введите инвайт-код"
            lower.contains("already exists") || lower.contains("user exists") || lower.contains("user exist") ->
                "Пользователь с таким логином уже существует"
            lower.contains("at least 8 characters") || lower.contains("password must be at least") ->
                "Пароль должен содержать минимум 8 символов"
            lower.contains("failed to connect") || lower.contains("unable to resolve host") || lower.contains("connection refused") ->
                "Нет подключения к серверу. Проверьте интернет"
            lower.contains("timeout") ->
                "Превышено время ожидания ответа"
            lower.contains("fraud") || lower.contains("антифрод") || lower.contains("читер") ->
                "Зафиксирована попытка нарушения (антифрод)"
            lower.contains("already marked") || lower.contains("уже отмечен") ->
                "Вы уже отмечены на этом занятии"
            lower.contains("session expired") || lower.contains("token expired") || lower.contains("unauthorized") ->
                "Срок действия сессии истёк"
            lower.contains("forbidden") || lower.contains("access denied") ->
                "Действие недоступно для вашей роли"
            lower.contains("not found") ->
                "Данные не найдены"
            else -> rawError
        }
    }

    fun getErrorMessage(rawError: String?, fallbackMessage: String = "Ошибка запроса"): String =
        mapErrorMessage(rawError, fallbackMessage)
}
