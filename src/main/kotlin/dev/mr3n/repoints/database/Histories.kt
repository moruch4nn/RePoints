package dev.mr3n.repoints.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object Histories: Table("histories") {
    val id = long("id")
    val uniqueId = uuid("uniqueId")
    val point = long("point")
    val timestamp = datetime("timestamp").defaultExpression(CurrentDateTime)
    val cancelled = bool("cancelled").default(false)
}