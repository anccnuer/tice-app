package com.example.tice.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TiceDao {
    @Query(
        """
        SELECT s.classId AS classId,
               MAX(s.className) AS className,
               MAX(s.gradeId) AS gradeId,
               COUNT(*) AS total,
               (SELECT COUNT(DISTINCT sc.studentId)
                  FROM scores sc
                  JOIN students s2 ON s2.studentId = sc.studentId
                 WHERE s2.classId = s.classId) AS tested
          FROM students s
         GROUP BY s.classId
         ORDER BY s.classId
        """
    )
    fun classStats(): Flow<List<ClassStat>>

    @Query("SELECT * FROM students WHERE classId = :classId ORDER BY sourceRow")
    suspend fun studentsOfClass(classId: String): List<StudentEntity>

    /**
     * 项目组内所有项目都有非空成绩的学生数。
     *
     * 空字符串视为「未测」，因此用 TRIM(value) <> '' 过滤；单独一个项目也适用（required = 1）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM students st
         WHERE st.classId = :classId
           AND (SELECT COUNT(DISTINCT sc.projectKey)
                  FROM scores sc
                 WHERE sc.studentId = st.studentId
                   AND sc.projectKey IN (:projectKeys)
                   AND TRIM(sc.value) <> '') = :required
        """
    )
    suspend fun testedCount(classId: String, projectKeys: List<String>, required: Int): Int

    /** 项目组还没测完的学生，按导入名单顺序，用于生成待测名单。 */
    @Query(
        """
        SELECT * FROM students st
         WHERE st.classId = :classId
           AND (SELECT COUNT(DISTINCT sc.projectKey)
                  FROM scores sc
                 WHERE sc.studentId = st.studentId
                   AND sc.projectKey IN (:projectKeys)
                   AND TRIM(sc.value) <> '') < :required
         ORDER BY st.sourceRow
        """
    )
    suspend fun untestedStudents(
        classId: String,
        projectKeys: List<String>,
        required: Int
    ): List<StudentEntity>

    @Query("SELECT * FROM students WHERE classId IN (:classIds) ORDER BY classId, name")
    suspend fun studentsOfClasses(classIds: List<String>): List<StudentEntity>

    @Query("SELECT * FROM students WHERE studentId IN (:ids)")
    suspend fun studentsByIds(ids: List<String>): List<StudentEntity>

    @Query("SELECT * FROM students ORDER BY sourceRow")
    suspend fun allStudents(): List<StudentEntity>

    @Query("SELECT DISTINCT gradeId FROM students ORDER BY gradeId")
    suspend fun allGrades(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudents(list: List<StudentEntity>)

    @Query("DELETE FROM students")
    suspend fun clearStudents()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScore(score: ScoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScores(list: List<ScoreEntity>)

    @Query("SELECT * FROM scores WHERE studentId IN (:ids)")
    suspend fun scoresFor(ids: List<String>): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE studentId = :id")
    suspend fun scoresOfStudent(id: String): List<ScoreEntity>

    @Query("DELETE FROM scores WHERE studentId = :id AND projectKey = :key")
    suspend fun deleteScore(id: String, key: String)

    @Query("SELECT * FROM scores")
    suspend fun allScores(): List<ScoreEntity>

    @Query("DELETE FROM scores")
    suspend fun clearScores()

    @Query("SELECT * FROM project_rules ORDER BY sortOrder")
    fun rules(): Flow<List<ProjectRuleEntity>>

    @Query("SELECT * FROM project_rules ORDER BY sortOrder")
    suspend fun rulesOnce(): List<ProjectRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(list: List<ProjectRuleEntity>)

    @Query("DELETE FROM project_rules")
    suspend fun clearRules()

    @Query("SELECT * FROM sessions WHERE id = 1")
    suspend fun session(): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: SessionEntity)

    @Query("DELETE FROM sessions")
    suspend fun clearSession()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    /** 清空按班级统计的批次号（重新导入学生数据时调用）。 */
    @Query("DELETE FROM meta WHERE `key` LIKE 'batch_no:%'")
    suspend fun clearBatchCounters()

    @Query("SELECT * FROM meta")
    suspend fun allMeta(): List<MetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(list: List<MetaEntity>)

    @Query("DELETE FROM meta")
    suspend fun clearMeta()

    /** 导入新表：覆盖学生名单与项目规则，成绩按学籍号保留。 */
    @Transaction
    suspend fun replaceWorkbook(
        students: List<StudentEntity>,
        rules: List<ProjectRuleEntity>,
        meta: List<MetaEntity>
    ) {
        clearStudents()
        insertStudents(students)
        clearRules()
        insertRules(rules)
        clearBatchCounters()
        insertMeta(meta)
    }

    /** 备份恢复：先清空再单事务整体写回，失败自动回滚。 */
    @Transaction
    suspend fun restoreAll(
        students: List<StudentEntity>,
        scores: List<ScoreEntity>,
        rules: List<ProjectRuleEntity>,
        meta: List<MetaEntity>,
        session: SessionEntity?
    ) {
        clearScores()
        clearStudents()
        clearRules()
        clearMeta()
        clearSession()
        insertStudents(students)
        insertScores(scores)
        insertRules(rules)
        insertMeta(meta)
        if (session != null) saveSession(session)
    }
}
