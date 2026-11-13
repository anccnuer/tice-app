package com.example.tice.data.repo

import com.example.tice.data.db.ClassStat
import com.example.tice.data.db.MetaEntity
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.ScoreEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.data.db.TiceDao
import kotlinx.coroutines.flow.Flow

class TestRepository(private val dao: TiceDao) {
    private companion object {
        const val GROUP_SIZE_KEY = "group_size"
        const val MIN_GROUP_SIZE = 1
        const val MAX_GROUP_SIZE = 6
    }

    fun classStats(): Flow<List<ClassStat>> = dao.classStats()
    fun rules(): Flow<List<ProjectRuleEntity>> = dao.rules()

    suspend fun studentsOfClass(classId: String): List<StudentEntity> = dao.studentsOfClass(classId)
    suspend fun studentsByIds(ids: List<String>): List<StudentEntity> =
        if (ids.isEmpty()) emptyList() else dao.studentsByIds(ids)

    suspend fun scoresOfStudent(studentId: String): Map<String, String> =
        dao.scoresOfStudent(studentId).associate { it.projectKey to it.value }

    /** 一次查询取回整组成绩，避免进入下一组时逐人查库。组人数上限 6，无 SQLite IN 参数上限风险。 */
    suspend fun scoresForStudents(ids: List<String>): Map<String, Map<String, String>> =
        if (ids.isEmpty()) emptyMap()
        else dao.scoresFor(ids).groupBy { it.studentId }
            .mapValues { (_, list) -> list.associate { it.projectKey to it.value } }

    /** 每组人数是教师的使用习惯，存在 meta 表里，杀进程/重启后仍保留。 */
    suspend fun savedGroupSize(): Int =
        dao.meta(GROUP_SIZE_KEY)?.toIntOrNull()?.coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE)
            ?: MIN_GROUP_SIZE

    suspend fun saveGroupSize(size: Int) {
        dao.insertMeta(listOf(MetaEntity(GROUP_SIZE_KEY, size.coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE).toString())))
    }

    /** 每次输入立即落库；清空输入框会删除对应成绩，也就等于「未测」。 */
    suspend fun saveScores(studentId: String, projectKeys: List<String>, values: Map<String, String>) {
        val now = System.currentTimeMillis()
        projectKeys.forEach { key ->
            val value = values[key].orEmpty().trim()
            if (value.isEmpty()) dao.deleteScore(studentId, key)
            else dao.upsertScore(ScoreEntity(studentId, key, value, now))
        }
    }

    /** 项目组内所有项目都有非空成绩的学生人数；空值视为未测。 */
    suspend fun testedCount(classId: String, projectKeys: List<String>): Int =
        if (projectKeys.isEmpty()) 0
        else dao.testedCount(classId, projectKeys, projectKeys.size)

    /** 项目组还没测完的学生，按导入名单顺序。 */
    suspend fun untestedStudents(classId: String, projectKeys: List<String>): List<StudentEntity> =
        if (projectKeys.isEmpty()) emptyList()
        else dao.untestedStudents(classId, projectKeys, projectKeys.size)
}
