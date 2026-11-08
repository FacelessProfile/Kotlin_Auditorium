package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.model.AttendanceAppealsPage
import com.example.kotlinroomdatabase.model.AttendanceAppealResult
import com.example.kotlinroomdatabase.model.AttendanceHistoryResponse
import com.example.kotlinroomdatabase.model.HistoryItem
import com.example.kotlinroomdatabase.util.AttendanceAppealRules as Rules
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AttendanceAppealsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val today = LocalDate.of(2026, 11, 8)
    private fun lesson(entry: String = "session:12", status: String = "absent", date: String = "2026-11-07") =
        HistoryItem(date = date, status = status, entry_id = entry)

    @Test fun parsesBothSessionAndLegacyJournalTargetsFromHistory() {
        val history = json.decodeFromString<AttendanceHistoryResponse>("""{"year":2026,"items":[{"date":"2026-11-07","entry_id":"session:12","status":"absent","source":"session"},{"date":"2026-11-07","entry_id":"journal:9","status":"excused"}]}""")
        assertEquals(listOf("session:12", "journal:9"), history.items.map { it.entry_id })
        assertTrue(history.items.all { Rules.canCreate(it, today) })
    }

    @Test fun olderServersWithoutEntryIdsDoNotOfferAnInvalidRequest() {
        val history = json.decodeFromString<AttendanceHistoryResponse>("""{"year":2026,"items":[{"date":"2026-11-07","status":"absent"}]}""")
        assertFalse(Rules.canCreate(history.items.single(), today))
    }

    @Test fun openAndFutureLessonsCannotBeSubmitted() {
        assertFalse(Rules.canCreate(lesson(status = "pending"), today))
        assertFalse(Rules.canCreate(lesson(date = "2026-11-09"), today))
        assertFalse(Rules.canCreate(lesson(date = "invalid"), today))
        assertTrue(Rules.canCreate(lesson(date = "2026-11-08"), today))
    }

    @Test fun unsupportedEntryTypesAndZeroIdsAreHidden() {
        listOf("", "12", "schedule:12", "session:0", "session:-3").forEach { assertFalse(it, Rules.canCreate(lesson(entry = it), today)) }
    }

    @Test fun rolePermissionsMatchTheBackendActiveRoles() {
        listOf("teacher", "head", "admin").forEach { assertTrue(Rules.canReview(it)); assertTrue(Rules.canView(it)) }
        assertTrue(Rules.canView("student"))
        assertFalse(Rules.canReview("student"))
        listOf("dean", "developer", "director", "secretary").forEach { assertFalse(Rules.canView(it)); assertFalse(Rules.canReview(it)) }
    }

    @Test fun reasonLimitsUseUnicodeCodePointsLikeTheGoServer() {
        assertFalse(Rules.validReason(" 123456789 "))
        assertTrue(Rules.validReason(" 1234567890 "))
        assertTrue(Rules.validReason("😀".repeat(4000)))
        assertFalse(Rules.validReason("😀".repeat(4001)))
        assertFalse(Rules.validReason("😀".repeat(5)))
    }

    @Test fun decisionAlwaysRequiresAnExplanation() {
        assertFalse(Rules.validNote("  "))
        assertFalse(Rules.validNote("Да"))
        assertTrue(Rules.validNote("Да."))
        assertTrue(Rules.validNote("a".repeat(4000)))
        assertFalse(Rules.validNote("a".repeat(4001)))
    }

    @Test fun parsesPageHistoryAndAnUnavailableCurrentJournalEntry() {
        val page = json.decodeFromString<AttendanceAppealsPage>("""{"page":2,"has_more":true,"items":[{"appeal_id":2147483648,"entry_id":"journal:9","subject_name":"Математика","student_name":"Иванов","lesson_date":"2026-11-07T00:00:00Z","original_status":"absent","requested_status":"excused","current_status":null,"status":"pending","reason":"Причина запроса","decision_note":null,"events":[{"action":"created","note":"Причина запроса","created_at":"2026-11-08T05:00:00Z"}],"student_id":1}]}""")
        assertEquals(2, page.page)
        assertTrue(page.has_more)
        val item = page.items.single()
        assertEquals(2147483648L, item.appeal_id)
        assertNull(item.current_status)
        assertNull(item.decision_note)
        assertEquals("created", item.events.single().action)
    }

    @Test fun parsesResolvedAppealAndEmptyPage() {
        assertTrue(json.decodeFromString<AttendanceAppealsPage>("""{"items":[],"page":1,"has_more":false}""").items.isEmpty())
        assertEquals("approved", json.decodeFromString<AttendanceAppealResult>("""{"appeal_id":7,"status":"approved"}""").status)
    }

    @Test fun conflictInstructsToReloadInsteadOfSilentlyOverwriting() {
        val error = Rules.errorMessage("attendance changed since the appeal; review the current mark first")
        assertTrue(error.contains("обновите список"))
        assertTrue(error.contains("текущую отметку"))
    }

    @Test fun duplicateAndNotYetEndedRequestsHaveSpecificMessages() {
        assertTrue(Rules.errorMessage("an appeal for this lesson already exists").contains("уже отправлено"))
        assertTrue(Rules.errorMessage("wait until the lesson has ended").contains("завершения занятия"))
        assertTrue(Rules.errorMessage("", 403).contains("активной роли"))
    }

    @Test fun openOrUnknownMarksAreNeverDisplayedAsPresence() {
        assertEquals("Отметка ещё открыта", Rules.markLabel("pending"))
        assertEquals("Нет данных", Rules.markLabel(null))
        assertEquals("Нет данных", Rules.markLabel("unknown"))
    }
}
