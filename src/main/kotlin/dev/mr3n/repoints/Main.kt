package dev.mr3n.repoints

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIBukkitConfig
import dev.jorel.commandapi.kotlindsl.*
import dev.mr3n.paperallinone.commands.failCommand
import dev.mr3n.paperallinone.commands.successCommand
import dev.mr3n.paperallinone.event.registerEvent
import dev.mr3n.repoints.database.Histories
import dev.mr3n.repoints.database.OperationHistory
import dev.mr3n.repoints.database.Players
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Named

@Suppress("unused")
class Main: JavaPlugin() {
    override fun onLoad() {
        CommandAPI.onLoad(CommandAPIBukkitConfig(this).verboseOutput(false))
    }

    val df = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    override fun onEnable() {

        val databaseHost = System.getenv("DATABASE_HOST")
        val databaseUsername = System.getenv("DATABASE_USERNAME")
        val databasePassword = System.getenv("DATABASE_PASSWORD")
        Database.connect(
            "jdbc:mariadb://${databaseHost}/points",
            driver = "org.mariadb.jdbc.Driver",
            user = databaseUsername,
            password = databasePassword
        )

        transaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Players, Histories, OperationHistory)
        }
        this.registerEvent<PlayerJoinEvent> { event ->
            transaction {
                Players.insertIgnore { it[uniqueId] = event.player.uniqueId;it[name] = event.player.name }
            }
        }

        CommandAPI.onEnable()
        commandTree("points") {
            playerExecutor { sender, _ ->
                // >>> メッセージを表示 >>>
                sender.sendMessage(Component.text("ポイント履歴", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("====================================", NamedTextColor.YELLOW))
                val pointHistory = sender.getPointHistory()
                if(pointHistory.isEmpty()) {
                    // ポイント履歴がないことを伝える。
                    sender.sendMessage(Component.text("履歴が存在しません。", NamedTextColor.YELLOW))
                } else {
                    // ポイント履歴をプレイヤーに送信。
                    sender.sendMessage(pointHistory.asColoredString())
                }
                // sender.sendMessage(languages.getTranslationMessage("point.history.separator"))
                sender.sendMessage(Component.text("合計ポイント: ${sender.getTotalPoint()}ポイント", NamedTextColor.GREEN, TextDecoration.BOLD))
                // <<< メッセージを表示 <<<
            }
            multiLiteralArgument("operation", listOf("add", "sub")) {
                entitySelectorArgumentManyPlayers("players") {
                    longArgument("point") {
                        playerExecutor { sender, args ->
                            val operation = args["operation"] as String
                            val players = (args["players"] as List<*>).filterIsInstance(Player::class.java)
                            val point = args["point"] as Long
                            if(point <= 0) {
                                sender.failCommand(NamedTextColor.RED to "ポイントは1以上で指定してください。")
                            }
                            val id = System.currentTimeMillis()
                            when(operation) {
                                "add" -> {
                                    players.forEach { it.addPoint(sender.uniqueId, id, point) }
                                    sender.successCommand(NamedTextColor.GREEN to "操作に成功しました。下記のプレイヤーに ${point}ポイント 与えました。\n${players.map { it.name }}")
                                }
                                "sub" -> {
                                    players.forEach { it.subPoint(sender.uniqueId, id, point) }
                                    sender.successCommand(NamedTextColor.RED to "操作に成功しました。下記のプレイヤーから ${point}ポイント 削除しました。\n${players.map { it.name }}")
                                }
                            }
                        }
                    }
                }
            }
            literalArgument("history") {
                playerArgument("player") {
                    playerExecutor { sender, args ->
                        val player = args["player"] as Player
                        val pointHistory = player.getPointHistory()
                        if(pointHistory.isEmpty()) {
                            // ポイント履歴がないことを伝える。
                            sender.sendMessage(Component.text("このプレイヤーにはポイント歴が存在しません。", NamedTextColor.RED))
                        } else {
                            // ポイント履歴をプレイヤーに送信。
                            sender.sendMessage(pointHistory.asColoredString())
                        }
                    }
                }
            }
            literalArgument("undo") {
                playerExecutor { sender, _ ->
                    transaction {
                        // 実行者が行った最後の操作を取得
                        val lastOperation = OperationHistory.select(OperationHistory.uniqueId.eq(sender.uniqueId) and not(OperationHistory.cancelled))
                            .reversed().firstOrNull()
                        // 一度も操作をしていない場合はエラーを送信して処理を終了
                        checkNotNull(lastOperation) { sender.failCommand(NamedTextColor.RED to "取り消せる操作がありません。") }
                        // 対応する実行履歴にキャンセルのフラグをセットする
                        OperationHistory.update({ OperationHistory.id eq lastOperation[OperationHistory.id] }) { it[cancelled] = true }
                        // 対応するポイント履歴にキャンセルのフラグを立てる。
                        Histories.update({Histories.id eq lastOperation[OperationHistory.id]}) { it[cancelled] = true }
                        // 対応する履歴を取得してメッセージを送信(影響人数、影響ポイント)
                        val result = Histories.select(Histories.id eq lastOperation[OperationHistory.id])
                        sender.successCommand(NamedTextColor.GREEN to "${result.count()}人に対する ${result.first()[Histories.point]}ポイント 付与の操作を取り消しました。")
                    }
                }
            }
            literalArgument("redo") {
                playerExecutor { sender, _ ->
                    transaction {
                        // 実行者が行った最後のundoを取得
                        val lastOperation = OperationHistory.select(OperationHistory.uniqueId.eq(sender.uniqueId) and OperationHistory.cancelled).firstOrNull()
                        // 一度もundoをしていない場合はエラーを送信して処理を終了
                        checkNotNull(lastOperation) { sender.failCommand(NamedTextColor.RED to "取り消せる操作がありません。") }
                        // 対応する実行履歴のキャンセルのフラグを外す
                        OperationHistory.update({ OperationHistory.id eq lastOperation[OperationHistory.id] }) { it[cancelled] = false }
                        // 対応するポイント履歴にキャンセルのフラグを外す
                        Histories.update({Histories.id eq lastOperation[OperationHistory.id]}) { it[cancelled] = false }
                        // 対応する履歴を取得してメッセージを送信(影響人数、影響ポイント)
                        val result = Histories.select(Histories.id eq lastOperation[OperationHistory.id])
                        sender.successCommand(NamedTextColor.GREEN to "${result.count()}人に対する ${result.first()[Histories.point]}ポイント 付与の操作の取り消しを取り消しました。")
                    }
                }
            }
            literalArgument("help") {
                playerExecutor { player, _ ->
                    player.sendMessage(Component.text("ないよ^^^"))
                }
            }
            literalArgument("broadcast") {
                booleanArgument("withoutOp") {
                    playerExecutor { sender, args ->
                        val isWithoutOp = args["withoutOp"] as Boolean
                        val points = transaction { Players.selectAll()
                            .map { it[Players.uniqueId] to it[Players.name] } }
                            .map { Bukkit.getOfflinePlayer(it.first) to it.second }
                            .filter { !isWithoutOp||!it.first.isOp }
                            .map { it.first to (it.second to it.first.getTotalPoint()) }
                            .sortedBy { it.second.second }.reversed()
                            .mapIndexed { index, pair -> index+1 to pair }
                        points.forEach { (rank,pair) ->
                            when(rank) {
                                1 -> {
                                    Bukkit.broadcast(Component.text("${rank}.${pair.second.first}: ${pair.second.second} Point", NamedTextColor.YELLOW))
                                }
                                2 -> {
                                    Bukkit.broadcast(Component.text("${rank}.${pair.second.first}: ${pair.second.second} Point", NamedTextColor.GRAY))
                                }
                                3 -> {
                                    Bukkit.broadcast(Component.text("${rank}.${pair.second.first}: ${pair.second.second} Point", NamedTextColor.GOLD))
                                }
                                4 -> {
                                    Bukkit.broadcast(Component.text("${rank}.${pair.second.first}: ${pair.second.second} Point", NamedTextColor.WHITE))
                                }
                                5 -> {
                                    Bukkit.broadcast(Component.text("${rank}.${pair.second.first}: ${pair.second.second} Point", NamedTextColor.WHITE))
                                }
                                else -> { return@forEach }
                            }
                        }
                        points.forEach { (rank,pair) ->
                            val player = pair.first.player?:return@forEach
                            val rankMsg = Component.text("あなたは${rank}位でした！(${pair.second.second}ポイント)")
                            player.showTitle(Title.title(Component.text("結果発表！", NamedTextColor.YELLOW), rankMsg))
                            player.sendMessage(Component.text("====================================", NamedTextColor.YELLOW))
                            player.sendMessage(rankMsg)
                        }
                    }
                }
            }
        }
    }

    /**
     * プレイヤーのポイント履歴を取得します。ポイントの変化量と変更された日時を返します。
     * @receiver player 対象のプレイヤー。
     * @return 変更日時とポイントの変化量(相対値)です。
     */
    private fun OfflinePlayer.getPointHistory(): List<Pair<LocalDateTime,Long>> { return getPointHistory(this.uniqueId) }

    private fun getPointHistory(uniqueId: UUID): List<Pair<LocalDateTime, Long>> {
        return transaction { Histories.select(Histories.uniqueId.eq(uniqueId) and not(Histories.cancelled)).map { it[Histories.timestamp] to it[Histories.point] } }
    }

    private fun List<Pair<LocalDateTime, Long>>.asColoredString(): Component {
        var result = Component.text("")
        this.forEachIndexed { index, pair ->
            val prefix = if(index == 0) { "" } else { "\n" }
            result = result.append(Component.text("${prefix}${df.format(pair.first.toJavaLocalDateTime())}: ", NamedTextColor.GRAY))
                .append(if(pair.second < 0) { Component.text("${pair.second}", NamedTextColor.RED) } else { Component.text("+${pair.second}", NamedTextColor.GREEN) })
        }
        return result
    }

    /**
     * プレイヤーの合計ポイントを取得します。
     * @receiver 対象のプレイヤー。
     * @return プレイヤーのポイントの合計値。
     */
    private fun OfflinePlayer.getTotalPoint(): BigDecimal {
        return getTotalPoint(this.uniqueId)
    }

    private fun getTotalPoint(uniqueId: UUID): BigDecimal {
        return this.getPointHistory(uniqueId).map { it.second }.sumOf { BigDecimal(it) }
    }

    /**
     * プレイヤーにポイントを追加します。
     * @receiver ポイントを追加するプレヤー。
     * @param value 何ポイント追加するかをBigIntegerで。
     * @return 操作番号(index)
     */
    fun OfflinePlayer.addPoint(executor: UUID, id: Long, value: Long) {
        transaction {
            OperationHistory.deleteWhere { cancelled and uniqueId.eq(executor) }
            Histories.deleteWhere { cancelled and uniqueId.eq(executor) }
            try { OperationHistory.insert { it[OperationHistory.id] = id;it[uniqueId] = executor } } catch(_: Exception) {}
            Histories.insert { it[Histories.id] = id;it[uniqueId] = this@addPoint.uniqueId;it[point] = value }
        }
    }

    /**
     * プレイヤーからポイントを削除します。
     * @receiver ポイントを削除するプレヤー。
     * @param value 何ポイント削除するかをBigIntegerで。
     * @return 操作番号(index)
     */
    fun OfflinePlayer.subPoint(executor: UUID, id: Long, value: Long) = this.addPoint(executor,id,-value)

    override fun onDisable() {
        CommandAPI.onDisable()
    }
}