package dev.mr3n.repoints.database

import org.jetbrains.exposed.sql.Table

object OperationHistory: Table("operation_history") {
    val id = long("id")
    val uniqueId = uuid("uniqueId")
    val cancelled = bool("cancelled").default(false)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}