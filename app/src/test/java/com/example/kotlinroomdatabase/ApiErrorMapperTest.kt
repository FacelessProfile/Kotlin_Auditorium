package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.util.ApiErrorMapper
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiErrorMapperTest {

    @Test
    fun testMapHttpStatus() {
        assertEquals("Срок действия сессии истёк. Пожалуйста, войдите снова", ApiErrorMapper.mapHttpStatus(401))
        assertEquals("Слишком много запросов. Подождите немного", ApiErrorMapper.mapHttpStatus(429))
        assertEquals("Сервер временно недоступен. Попробуйте позже", ApiErrorMapper.mapHttpStatus(500))
        assertEquals("Сервер временно недоступен. Попробуйте позже", ApiErrorMapper.mapHttpStatus(502))
        assertEquals("Сервер временно недоступен. Попробуйте позже", ApiErrorMapper.mapHttpStatus(503))

        // Default fallbacks
        assertEquals("Некорректный запрос", ApiErrorMapper.mapHttpStatus(400))
        assertEquals("Доступ к этому действию ограничен", ApiErrorMapper.mapHttpStatus(403))
        assertEquals("Запрашиваемые данные не найдены", ApiErrorMapper.mapHttpStatus(404))

        // Custom error override
        assertEquals("Кастомная ошибка", ApiErrorMapper.mapHttpStatus(400, "Кастомная ошибка"))
        assertEquals("Запрещено деканатом", ApiErrorMapper.mapHttpStatus(403, "Запрещено деканатом"))
    }

    @Test
    fun testMapNetworkExceptions() {
        val unknownHost = UnknownHostException("Unable to resolve host")
        val connectEx = ConnectException("Connection refused")
        val timeoutEx = SocketTimeoutException("Read timed out")

        assertEquals("Нет подключения к серверу. Проверьте интернет-соединение", ApiErrorMapper.mapError(unknownHost))
        assertEquals("Нет подключения к серверу. Проверьте интернет-соединение", ApiErrorMapper.mapError(connectEx))
        assertEquals("Сервер не ответил вовремя. Попробуйте повторить запрос", ApiErrorMapper.mapError(timeoutEx))
    }

    @Test
    fun testMapErrorMessages() {
        assertEquals("Неверный логин или пароль", ApiErrorMapper.mapErrorMessage("Invalid credentials"))
        assertEquals("Неверный логин или пароль", ApiErrorMapper.mapErrorMessage("Wrong password provided"))
        assertEquals("Зафиксирована попытка нарушения (антифрод)", ApiErrorMapper.mapErrorMessage("Possible fraud detected"))
        assertEquals("Зафиксирована попытка нарушения (антифрод)", ApiErrorMapper.mapErrorMessage("Внимание, антифрод сработал!"))
        assertEquals("Вы уже отмечены на этом занятии", ApiErrorMapper.mapErrorMessage("Student is already marked"))
        assertEquals("Вы уже отмечены на этом занятии", ApiErrorMapper.mapErrorMessage("студент уже отмечен"))
        assertEquals("Срок действия сессии истёк", ApiErrorMapper.mapErrorMessage("Unauthorized access"))
        assertEquals("Действие недоступно для вашей роли", ApiErrorMapper.mapErrorMessage("Forbidden: role does not have access"))

        // Fallback
        assertEquals("Стандартная ошибка", ApiErrorMapper.mapErrorMessage(null, "Стандартная ошибка"))
        assertEquals("Стандартная ошибка", ApiErrorMapper.mapErrorMessage("", "Стандартная ошибка"))
    }
}
