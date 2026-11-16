package com.example.tice.util

/** 每组人数的快捷候选值；加减微调可落到 1..6 内任意整数。 */
val GROUP_SIZE_OPTIONS = listOf(1, 2, 3, 4, 6)

const val MIN_GROUP_SIZE = 1
const val MAX_GROUP_SIZE = 6

/** 备份服务的默认端口与自动探测范围。 */
const val DEFAULT_BACKUP_PORT = 8080
const val BACKUP_PORT_RANGE = 12

/** 单次 HTTP 请求读取超时。 */
const val HTTP_SOCKET_TIMEOUT_MS = 15_000

/** 前台服务通知与 4 位访问码的取值范围。 */
const val BACKUP_NOTIFICATION_ID = 1001
const val MIN_BACKUP_CODE = 1000
const val MAX_BACKUP_CODE = 9999
