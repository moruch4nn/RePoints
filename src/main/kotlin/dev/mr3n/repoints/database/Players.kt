package dev.mr3n.repoints.database

import org.jetbrains.exposed.sql.Table

object Players: Table("players") {
    val uniqueId = uuid("uniqueId")
    val name = varchar("name", 16)

    override val primaryKey: PrimaryKey = PrimaryKey(uniqueId)
}