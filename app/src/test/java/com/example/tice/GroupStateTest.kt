package com.example.tice

import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.ui.vm.ProjectPhase
import com.example.tice.ui.vm.TiceUiState
import com.example.tice.ui.vm.closeSession
import com.example.tice.ui.vm.finishSession
import com.example.tice.ui.vm.moveToGroup
import com.example.tice.ui.vm.previewSession
import com.example.tice.ui.vm.startSession
import com.example.tice.util.ProjectGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验录入现场的状态转换不会残留上一组的输入、越界确认或已测计数。 */
class GroupStateTest {

    private fun student(id: String) = StudentEntity(
        studentId = id,
        gradeId = "2026",
        classId = "c1",
        className = "1班",
        nationCode = "",
        name = "学生$id",
        gender = "男",
        birthDate = "",
        address = "",
        sourceRow = 1
    )

    private fun group() = ProjectGroup(
        title = "肺活量",
        rules = listOf(ProjectRuleEntity("肺活量", "H", "", 500.0, 9999.0, false, false, 0))
    )

    private fun dirtyState() = TiceUiState(
        activeGroup = group(),
        phase = ProjectPhase.Testing,
        roster = listOf(student("1"), student("2"), student("3")),
        rosterIndex = 1,
        groupScores = mapOf("1" to mapOf("肺活量" to "1000")),
        pendingWarning = "越界",
        activeTested = 2
    )

    @Test
    fun `closeSession 清空整组现场`() {
        val next = dirtyState().closeSession()
        assertNull(next.activeGroup)
        assertEquals(ProjectPhase.None, next.phase)
        assertTrue(next.roster.isEmpty())
        assertEquals(0, next.rosterIndex)
        assertTrue(next.groupScores.isEmpty())
        assertEquals("", next.pendingWarning)
        assertEquals(0, next.activeTested)
    }

    @Test
    fun `previewSession 载入新项目并清空旧输入`() {
        val target = ProjectGroup(
            title = "50米跑",
            rules = listOf(ProjectRuleEntity("50米跑", "J", "", 5.0, 20.0, false, false, 1))
        )
        val next = dirtyState().previewSession(target)
        assertEquals(target, next.activeGroup)
        assertEquals(ProjectPhase.Preview, next.phase)
        assertTrue(next.roster.isEmpty())
        assertEquals(0, next.rosterIndex)
        assertTrue(next.groupScores.isEmpty())
        assertEquals("", next.pendingWarning)
        assertEquals(0, next.activeTested)
    }

    @Test
    fun `startSession 换名单并回到第一组`() {
        val roster = listOf(student("4"), student("5"))
        val next = dirtyState().startSession(roster)
        assertEquals(roster, next.roster)
        assertEquals(0, next.rosterIndex)
        assertTrue(next.groupScores.isEmpty())
        assertEquals("", next.pendingWarning)
        assertEquals(ProjectPhase.Testing, next.phase)
        // 未显式传入时沿用调用前的已测人数，避免调用点各自决定
        assertEquals(2, next.activeTested)
    }

    @Test
    fun `startSession 可带入最新已测人数`() {
        val next = dirtyState().startSession(listOf(student("4")), tested = 5)
        assertEquals(5, next.activeTested)
    }

    @Test
    fun `moveToGroup 只清当前组输入并保留名单`() {
        val source = dirtyState()
        val next = source.moveToGroup(2)
        assertEquals(2, next.rosterIndex)
        assertTrue(next.groupScores.isEmpty())
        assertEquals("", next.pendingWarning)
        assertEquals(source.roster, next.roster)
        assertEquals(source.phase, next.phase)
        assertEquals(source.activeGroup, next.activeGroup)
    }

    @Test
    fun `finishSession 标记完成并停在名单末尾`() {
        val source = dirtyState()
        val next = source.finishSession(tested = 3)
        assertEquals(ProjectPhase.Done, next.phase)
        assertEquals(source.roster.size, next.rosterIndex)
        assertTrue(next.groupScores.isEmpty())
        assertEquals("", next.pendingWarning)
        assertEquals(3, next.activeTested)
    }
}
