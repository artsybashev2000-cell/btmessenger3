package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Возраст точки считается по часам отправителя: время получения и расхождение часов не влияют. */
class FormatTest {

    private fun msg(sentAt: Long, fixTime: Long, receivedAt: Long) = ChatMessage(
        id = "m", peer = "AA:BB", fromMe = false, kind = Kind.GEO, text = "",
        geo = GeoPoint(56.83, 60.59, 10f, fixTime),
        sentAt = sentAt, receivedAt = receivedAt, status = Status.RECEIVED
    )

    @Test
    fun ageIsSentAtMinusFixTime() {
        assertEquals(30_000L, Format.fixAgeAtSend(msg(1_000_000L, 970_000L, 1_000_500L)))
    }

    @Test
    fun receiveTimeAndClockSkewDoNotChangeAge() {
        // у получателя часы ушли на час вперёд и сообщение шло долго — возраст тот же
        val a = Format.fixAgeAtSend(msg(1_000_000L, 970_000L, 1_000_500L))
        val b = Format.fixAgeAtSend(msg(1_000_000L, 970_000L, 1_000_500L + 3_600_000L))
        assertEquals(a, b)
    }

    @Test
    fun fixFromFutureCountsAsFreshNotNegative() {
        assertEquals(0L, Format.fixAgeAtSend(msg(1_000_000L, 1_005_000L, 1_000_500L)))
    }

    @Test
    fun staleThresholdIsTwoMinutes() {
        assertEquals(120_000L, Format.STALE_FIX_MS)
    }
}
