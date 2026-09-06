package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.util.RoleUtils
import org.junit.Assert.*
import org.junit.Test

class RoleUtilsTest {

    @Test
    fun testNormalizeRole() {
        assertEquals("student", RoleUtils.normalizeRole("student"))
        assertEquals("teacher", RoleUtils.normalizeRole("  TEACHER  "))
        assertEquals("head", RoleUtils.normalizeRole("Head"))
        assertEquals("student", RoleUtils.normalizeRole(""))
        assertEquals("student", RoleUtils.normalizeRole(null))
        assertEquals("student", RoleUtils.normalizeRole("   "))
    }

    @Test
    fun testGetRoleLabel() {
        assertEquals("Студент", RoleUtils.getRoleLabel("student"))
        assertEquals("Преподаватель", RoleUtils.getRoleLabel("teacher"))
        assertEquals("Зав. кафедрой", RoleUtils.getRoleLabel("head"))
        assertEquals("Администратор", RoleUtils.getRoleLabel("admin"))
        assertEquals("Декан", RoleUtils.getRoleLabel("dean"))
        assertEquals("Секретарь", RoleUtils.getRoleLabel("secretary"))
        assertEquals("Руководитель программы", RoleUtils.getRoleLabel("program_creator"))
        assertEquals("Директор института", RoleUtils.getRoleLabel("director"))
        assertEquals("Министр образования", RoleUtils.getRoleLabel("minister"))
        assertEquals("Пользователь", RoleUtils.getRoleLabel("custom_role"))
    }

    @Test
    fun testIsTeacherOrHead() {
        assertTrue(RoleUtils.isTeacherOrHead("teacher"))
        assertTrue(RoleUtils.isTeacherOrHead("TEACHER"))
        assertTrue(RoleUtils.isTeacherOrHead("head"))
        assertTrue(RoleUtils.isTeacherOrHead("HEAD"))
        assertFalse(RoleUtils.isTeacherOrHead("student"))
        assertFalse(RoleUtils.isTeacherOrHead("admin"))
        assertFalse(RoleUtils.isTeacherOrHead(null))
        assertFalse(RoleUtils.isTeacherOrHead(""))
    }

    @Test
    fun testCanViewSchedule() {
        assertTrue(RoleUtils.canViewSchedule("student"))
        assertTrue(RoleUtils.canViewSchedule("teacher"))
        assertTrue(RoleUtils.canViewSchedule("head"))
        assertFalse(RoleUtils.canViewSchedule("admin"))
    }

    @Test
    fun testFormatShortName() {
        // Three parts: Last First Middle -> Last F.M.
        assertEquals("Иванов И.И.", RoleUtils.formatShortName("Иванов Иван Иванович"))
        assertEquals("Смирнов А.В.", RoleUtils.formatShortName("  Смирнов   Алексей   Владимирович  "))

        // Two parts: Last First -> Last F.
        assertEquals("Петров П.", RoleUtils.formatShortName("Петров Петр"))

        // Single part
        assertEquals("Сидоров", RoleUtils.formatShortName("Сидоров"))

        // Blank or null
        assertEquals("", RoleUtils.formatShortName(null))
        assertEquals("", RoleUtils.formatShortName(""))
        assertEquals("", RoleUtils.formatShortName("    "))
    }

    @Test
    fun testGetRoleHeroTitle() {
        assertEquals("Рабочая панель преподавателя", RoleUtils.getRoleHeroTitle("teacher"))
        assertEquals("Панель заведующего кафедрой", RoleUtils.getRoleHeroTitle("head"))
        assertEquals("Панель администратора", RoleUtils.getRoleHeroTitle("admin"))
        assertEquals("Личный кабинет студента", RoleUtils.getRoleHeroTitle("student"))
        assertEquals("Личный кабинет студента", RoleUtils.getRoleHeroTitle(null))
    }
}
