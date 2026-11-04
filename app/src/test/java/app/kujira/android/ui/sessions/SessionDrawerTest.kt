package app.kujira.android.ui.sessions

import app.kujira.android.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class SessionDrawerTest {

    private val zone = ZoneId.of("Asia/Bangkok")
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val now = 1_760_000_000_000L

    private fun conversation(
        id: String,
        updatedAt: Long,
        pinned: Boolean = false,
        title: String = id,
    ) = Conversation(id = id, title = title, updatedAt = updatedAt, pinned = pinned)

    @Test
    fun `groups conversations into pinned today yesterday week and older`() {
        val groups = groupConversations(
            conversations = listOf(
                conversation("old", updatedAt = now - 20 * day),
                conversation("today", updatedAt = now - 2 * hour),
                conversation("yesterday", updatedAt = now - (day + hour)),
                conversation("week", updatedAt = now - 3 * day),
                conversation("pinned", updatedAt = now - 20 * day, pinned = true),
            ),
            now = now,
            zoneId = zone,
        )

        assertEquals(listOf("置顶", "今天", "昨天", "近 7 天", "更早"), groups.map { it.title })
        assertEquals(listOf("pinned"), groups[0].conversations.map { it.id })
        assertEquals(listOf("today"), groups[1].conversations.map { it.id })
        assertEquals(listOf("yesterday"), groups[2].conversations.map { it.id })
        assertEquals(listOf("week"), groups[3].conversations.map { it.id })
        assertEquals(listOf("old"), groups[4].conversations.map { it.id })
    }

    @Test
    fun `sorts unpinned conversations newest first`() {
        val groups = groupConversations(
            conversations = listOf(
                conversation("a", updatedAt = now - 3 * hour),
                conversation("b", updatedAt = now - 1 * hour),
                conversation("c", updatedAt = now - 2 * hour),
            ),
            now = now,
            zoneId = zone,
        )
        assertEquals(listOf("b", "c", "a"), groups.single().conversations.map { it.id })
    }

    @Test
    fun `formats timestamps`() {
        assertEquals("昨天", formatSessionTimestamp(now - (day + hour), now, zone))
        assertEquals("3 天前", formatSessionTimestamp(now - 3 * day, now, zone))
        assertEquals("14:20", formatSessionTimestamp(
            java.time.ZonedDateTime.of(2026, 1, 2, 14, 20, 0, 0, zone).toInstant().toEpochMilli(),
            java.time.ZonedDateTime.of(2026, 1, 2, 18, 0, 0, 0, zone).toInstant().toEpochMilli(),
            zone,
        ))
    }
}
