package io.slogr.desktop.core.notifications

import io.slogr.agent.contracts.SlaGrade
import kotlin.test.*

class DesktopNotifierTest {

    @BeforeTest
    fun setUp() {
        DesktopNotifier.reset()
    }

    @Test
    fun `GREEN to YELLOW produces degraded message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.GREEN, SlaGrade.YELLOW)
        assertNotNull(msg)
        assertTrue(msg.contains("degraded"))
    }

    @Test
    fun `GREEN to RED produces poor message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.GREEN, SlaGrade.RED)
        assertNotNull(msg)
        assertTrue(msg.contains("poor"))
    }

    @Test
    fun `YELLOW to RED produces worsened message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.YELLOW, SlaGrade.RED)
        assertNotNull(msg)
        assertTrue(msg.contains("worsened"))
    }

    @Test
    fun `RED to GREEN produces restored message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.RED, SlaGrade.GREEN)
        assertNotNull(msg)
        assertTrue(msg.contains("restored"))
    }

    @Test
    fun `YELLOW to GREEN produces improved message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.YELLOW, SlaGrade.GREEN)
        assertNotNull(msg)
        assertTrue(msg.contains("improved"))
    }

    @Test
    fun `RED to YELLOW produces improving message`() {
        val msg = DesktopNotifier.gradeChangeMessage(SlaGrade.RED, SlaGrade.YELLOW)
        assertNotNull(msg)
        assertTrue(msg.contains("improving"))
    }

    @Test
    fun `same grade produces no message`() {
        assertNull(DesktopNotifier.gradeChangeMessage(SlaGrade.GREEN, SlaGrade.GREEN))
        assertNull(DesktopNotifier.gradeChangeMessage(SlaGrade.RED, SlaGrade.RED))
    }

    @Test
    fun `all six transitions produce non-null messages`() {
        val transitions = listOf(
            SlaGrade.GREEN to SlaGrade.YELLOW,
            SlaGrade.GREEN to SlaGrade.RED,
            SlaGrade.YELLOW to SlaGrade.RED,
            SlaGrade.RED to SlaGrade.GREEN,
            SlaGrade.YELLOW to SlaGrade.GREEN,
            SlaGrade.RED to SlaGrade.YELLOW,
        )
        transitions.forEach { (from, to) ->
            assertNotNull(
                DesktopNotifier.gradeChangeMessage(from, to),
                "Expected message for $from → $to",
            )
        }
    }
}
