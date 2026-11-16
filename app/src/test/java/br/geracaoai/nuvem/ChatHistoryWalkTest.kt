package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.ChatHistoryWalk
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryWalkTest {
    @Test fun emptyNewChannelStopsAfterTwoEmptyPages() {
        var from = 0L; var empty = 0; var pages = 0; var done = false
        repeat(5) {
            if (done) return@repeat
            val s = ChatHistoryWalk.next(from, empty, longArrayOf(), full = false, knownCount = 0, pages = pages)
            from = s.nextFromId; empty = s.emptyRounds; pages = s.pages; done = s.done
        }
        assertTrue(done)
        assertEquals(2, empty)
        assertEquals(2, pages)
        assertEquals(0L, from)
    }

    @Test fun firstEmptyDoesNotFinish() {
        val s = ChatHistoryWalk.next(0L, 0, longArrayOf(), full = false, knownCount = 0, pages = 0)
        assertFalse(s.done)
        assertEquals(1, s.emptyRounds)
    }

    @Test fun pagesAdvanceFromLastId() {
        val s = ChatHistoryWalk.next(0L, 0, longArrayOf(30, 20, 10), full = false, knownCount = 0, pages = 0)
        assertFalse(s.done)
        assertEquals(10L, s.nextFromId)
        assertEquals(0, s.emptyRounds)
    }

    @Test fun stopsWhenAllMessagesAlreadyIndexed() {
        val s = ChatHistoryWalk.next(0L, 0, longArrayOf(5, 4, 3), full = false, knownCount = 3, pages = 0)
        assertTrue(s.done)
    }

    @Test fun fullSyncKeepsGoingEvenIfKnown() {
        val s = ChatHistoryWalk.next(0L, 0, longArrayOf(5, 4, 3), full = true, knownCount = 3, pages = 0)
        assertFalse(s.done)
        assertEquals(3L, s.nextFromId)
    }

    @Test fun emptyRoundResetsWhenAPageArrives() {
        val empty = ChatHistoryWalk.next(0L, 0, longArrayOf(), full = false, knownCount = 0, pages = 0)
        assertFalse(empty.done)
        assertEquals(1, empty.emptyRounds)
        val got = ChatHistoryWalk.next(0L, empty.emptyRounds, longArrayOf(9, 8), full = false, knownCount = 0, pages = empty.pages)
        assertEquals(0, got.emptyRounds)
        assertFalse(got.done)
    }

    @Test fun capsPages() {
        val s = ChatHistoryWalk.next(1L, 0, longArrayOf(2), full = true, knownCount = 0, pages = ChatHistoryWalk.MAX_PAGES, maxPages = ChatHistoryWalk.MAX_PAGES)
        assertTrue(s.done)
    }

    @Test fun missingChatErrors() {
        assertTrue(ChatHistoryWalk.isMissingChat(400, "Chat not found"))
        assertTrue(ChatHistoryWalk.isMissingChat(400, "CHAT_ID_INVALID"))
        assertFalse(ChatHistoryWalk.isMissingChat(429, "Too Many Requests"))
        assertFalse(ChatHistoryWalk.isMissingChat(400, "MESSAGE_NOT_FOUND"))
    }
}
