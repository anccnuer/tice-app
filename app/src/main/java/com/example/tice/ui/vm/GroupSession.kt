package com.example.tice.ui.vm

import com.example.tice.data.db.StudentEntity
import com.example.tice.util.ProjectGroup

/**
 * 录入现场的集中状态转换。
 *
 * 每个函数只描述一种业务动作对应的字段重置，避免在 ViewModel 的各个分支里手写 copy 时漏字段，
 * 让本组输入、越界确认与已测计数始终一起被收拾干净。
 */
internal fun TiceUiState.closeSession(): TiceUiState = copy(
    activeGroup = null,
    phase = ProjectPhase.None,
    roster = emptyList(),
    rosterIndex = 0,
    groupScores = emptyMap(),
    pendingWarning = "",
    activeTested = 0
)

internal fun TiceUiState.previewSession(group: ProjectGroup): TiceUiState = copy(
    activeGroup = group,
    phase = ProjectPhase.Preview,
    roster = emptyList(),
    rosterIndex = 0,
    groupScores = emptyMap(),
    pendingWarning = "",
    activeTested = 0
)

internal fun TiceUiState.startSession(
    roster: List<StudentEntity>,
    tested: Int = activeTested
): TiceUiState = copy(
    roster = roster,
    rosterIndex = 0,
    groupScores = emptyMap(),
    pendingWarning = "",
    phase = ProjectPhase.Testing,
    activeTested = tested
)

internal fun TiceUiState.moveToGroup(index: Int): TiceUiState = copy(
    rosterIndex = index,
    groupScores = emptyMap(),
    pendingWarning = ""
)

internal fun TiceUiState.finishSession(tested: Int): TiceUiState = copy(
    phase = ProjectPhase.Done,
    rosterIndex = roster.size,
    groupScores = emptyMap(),
    pendingWarning = "",
    activeTested = tested
)
