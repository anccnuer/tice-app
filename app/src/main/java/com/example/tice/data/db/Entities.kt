package com.example.tice.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "students", indices = [Index("classId")])
data class StudentEntity(
    @PrimaryKey val studentId: String,
    val gradeId: String,
    val classId: String,
    val className: String,
    val nationCode: String,
    val name: String,
    val gender: String,
    val birthDate: String,
    val address: String,
    val sourceRow: Int
)

@Entity(tableName = "scores", primaryKeys = ["studentId", "projectKey"], indices = [Index("projectKey")])
data class ScoreEntity(
    val studentId: String,
    val projectKey: String,
    val value: String,
    val updatedAt: Long
)

@Entity(tableName = "project_rules")
data class ProjectRuleEntity(
    @PrimaryKey val key: String,
    val column: String,
    val hint: String,
    val minValue: Double?,
    val maxValue: Double?,
    val integerOnly: Boolean,
    val textValue: Boolean,
    val sortOrder: Int
)

/** 单条批次会话：杀进程后可从这里恢复现场。 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Int = 1,
    val classId: String,
    val className: String,
    val batchSize: Int,
    val randomMode: Boolean,
    val projectsCsv: String,
    val studentIdsCsv: String,
    val index: Int,
    val batchNo: Int,
    val updatedAt: Long
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** 班级聚合结果，非实体。 */
data class ClassStat(
    val classId: String,
    val className: String,
    val gradeId: String,
    val total: Int,
    val tested: Int
)
