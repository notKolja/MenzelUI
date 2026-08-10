package de.menzel.menzelUI.paper

import de.menzel.menzelUI.paper.commands.MenzelUICommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard

class MenzelUIPaper : JavaPlugin(), Listener {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private var configModel = PaperUiConfig()
    private var luckPerms: LuckPerms? = null
    private val scoreboards = mutableMapOf<Player, Scoreboard>()

    override fun onEnable() {
        instance = this

        saveDefaultConfig()
        loadSettings()
        luckPerms = LuckPermsProvider.get()

        server.pluginManager.registerEvents(this, this)
        scheduleUpdates()
        updateAll()

        registerCommand("mui", MenzelUICommand())

        logger.info("MenzelUI Paper enabled.")
    }

    override fun onDisable() {
        scoreboards.clear()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        server.scheduler.runTaskLater(this, Runnable { updateAll() }, 2L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        scoreboards.remove(event.player)
        server.scheduler.runTaskLater(this, Runnable { updateAll() }, 2L)
    }

    fun loadSettings() {
        reloadConfig()
        val nametag = config.getConfigurationSection("nametag")
        val scoreboard = config.getConfigurationSection("scoreboard")

        configModel = PaperUiConfig(
            nametag = NametagConfig(
                enabled = nametag?.getBoolean("enabled", true) ?: true,
                updateIntervalTicks = (nametag?.getLong("update-interval-ticks", 40L) ?: 40L).coerceAtLeast(1L),
                highestWeightFirst = nametag?.getBoolean("highest-weight-first", true) ?: true,
                format = nametag?.getString("format") ?: "<prefix><gray><name></gray><suffix>",
                emptyPrefix = nametag?.getString("empty-prefix") ?: "",
                emptySuffix = nametag?.getString("empty-suffix") ?: "",
            ),
            scoreboard = SidebarConfig(
                enabled = scoreboard?.getBoolean("enabled", true) ?: true,
                updateIntervalTicks = (scoreboard?.getLong("update-interval-ticks", 20L) ?: 20L).coerceAtLeast(1L),
                title = scoreboard?.getString("title") ?: "<bold>MenzelUI</bold>",
                lines = scoreboard?.getStringList("lines").orEmpty(),
            ),
        )
    }

    private fun scheduleUpdates() {
        val intervals = listOf(
            configModel.nametag.updateIntervalTicks.takeIf { configModel.nametag.enabled },
            configModel.scoreboard.updateIntervalTicks.takeIf { configModel.scoreboard.enabled },
        ).filterNotNull()

        val interval = intervals.minOrNull() ?: 20L
        server.scheduler.runTaskTimer(this, Runnable { updateAll() }, interval, interval)
    }

    fun updateAll() {
        Bukkit.getOnlinePlayers().forEach { viewer ->
            val scoreboard = scoreboardFor(viewer)
            if (configModel.scoreboard.enabled) {
                updateSidebar(viewer, scoreboard)
            }
            if (configModel.nametag.enabled) {
                updateNametags(scoreboard)
            }
            viewer.scoreboard = scoreboard
        }
    }

    private fun scoreboardFor(player: Player): Scoreboard =
        scoreboards.getOrPut(player) {
            server.scoreboardManager.newScoreboard
        }

    private fun updateSidebar(viewer: Player, scoreboard: Scoreboard) {
        val objective = scoreboard.getObjective(SIDEBAR_OBJECTIVE)
            ?: scoreboard.registerNewObjective(SIDEBAR_OBJECTIVE, Criteria.DUMMY, Component.empty())

        objective.displaySlot = DisplaySlot.SIDEBAR
        objective.displayName(render(configModel.scoreboard.title, viewer))

        SIDEBAR_ENTRIES.forEach { scoreboard.resetScores(it) }

        val lines = configModel.scoreboard.lines.take(MAX_SIDEBAR_LINES)
        lines.forEachIndexed { index, line ->
            val entry = sidebarEntry(index)
            objective.getScore(entry).score = lines.size - index
            val team = scoreboard.getTeam(sidebarTeamName(index)) ?: scoreboard.registerNewTeam(sidebarTeamName(index))
            if (!team.hasEntry(entry)) {
                team.addEntry(entry)
            }
            team.setPrefix(renderLegacy(line, viewer))
            team.setSuffix("")
        }

        (lines.size until MAX_SIDEBAR_LINES).forEach { index ->
            scoreboard.getTeam(sidebarTeamName(index))?.unregister()
        }
    }

    private fun updateNametags(scoreboard: Scoreboard) {
        val sortedPlayers = Bukkit.getOnlinePlayers()
            .map { player -> TaggedPlayer(player, luckPerms?.userManager?.getUser(player.uniqueId)) }
            .sortedWith(compareByTaggedPlayer())

        sortedPlayers.forEachIndexed { index, taggedPlayer ->
            val teamName = nametagTeamName(index, taggedPlayer.player)
            val team = scoreboard.getTeam(teamName) ?: scoreboard.registerNewTeam(teamName)
            val split = splitNametag(configModel.nametag.format)
            val playerEntry = taggedPlayer.player.name

            scoreboard.teams
                .filter { it.name.startsWith(NAMETAG_TEAM_PREFIX) && it.name != teamName && it.hasEntry(playerEntry) }
                .forEach { it.removeEntry(playerEntry) }

            team.setPrefix(renderLegacy(split.prefix, taggedPlayer.player, taggedPlayer.user))
            team.setSuffix(renderLegacy(split.suffix, taggedPlayer.player, taggedPlayer.user))
            team.color = ChatColor.RESET

            if (!team.hasEntry(playerEntry)) {
                team.addEntry(playerEntry)
            }
        }

        scoreboard.teams
            .filter { it.name.startsWith(NAMETAG_TEAM_PREFIX) }
            .forEach { team ->
                team.entries
                    .filter { entry -> Bukkit.getPlayerExact(entry) == null }
                    .forEach { entry -> team.removeEntry(entry) }
            }
    }

    private fun compareByTaggedPlayer(): Comparator<TaggedPlayer> {
        val weightComparator = compareBy<TaggedPlayer> { it.weight }
        val comparator = if (configModel.nametag.highestWeightFirst) {
            weightComparator.reversed()
        } else {
            weightComparator
        }
        return comparator.thenBy { it.player.name.lowercase() }
    }

    private fun splitNametag(format: String): NametagSplit {
        val normalized = format.normalizePlaceholders()
        val marker = "<name>"
        val index = normalized.indexOf(marker)
        if (index < 0) {
            return NametagSplit(normalized, "")
        }
        return NametagSplit(
            prefix = normalized.substring(0, index),
            suffix = normalized.substring(index + marker.length),
        )
    }

    private fun render(template: String, player: Player, user: User? = luckPerms?.userManager?.getUser(player.uniqueId)): Component {
        val meta = user?.cachedData?.metaData
        val prefix = meta?.prefix ?: configModel.nametag.emptyPrefix
        val suffix = meta?.suffix ?: configModel.nametag.emptySuffix
        val weight = meta?.weight ?: 0

        return miniMessage.deserialize(
            template.normalizePlaceholders(),
            Placeholder.parsed("prefix", prefix),
            Placeholder.component("name", Component.text(player.name)),
            Placeholder.component("display_name", player.displayName()),
            Placeholder.parsed("suffix", suffix),
            Placeholder.unparsed("weight", weight.toString()),
            Placeholder.unparsed("uuid", player.uniqueId.toString()),
            Placeholder.unparsed("world", player.world.name),
            Placeholder.unparsed("online", Bukkit.getOnlinePlayers().size.toString()),
            Placeholder.unparsed("ping", player.ping.toString()),
            Placeholder.unparsed("health", player.health.toInt().toString()),
            Placeholder.unparsed("max_health", player.maxHealth.toInt().toString()),
            Placeholder.unparsed("food", player.foodLevel.toString()),
            Placeholder.unparsed("level", player.level.toString()),
            Placeholder.unparsed("gamemode", player.gameMode.name.lowercase()),
            Placeholder.unparsed("x", player.location.blockX.toString()),
            Placeholder.unparsed("y", player.location.blockY.toString()),
            Placeholder.unparsed("z", player.location.blockZ.toString()),
        )
    }

    private fun renderLegacy(template: String, player: Player, user: User? = luckPerms?.userManager?.getUser(player.uniqueId)): String =
        legacySerializer.serialize(render(template, player, user))

    private fun String.normalizePlaceholders(): String = this
        .replace("{prefix}", "<prefix>")
        .replace("{name}", "<name>")
        .replace("{display_name}", "<display_name>")
        .replace("{suffix}", "<suffix>")
        .replace("{weight}", "<weight>")
        .replace("{uuid}", "<uuid>")
        .replace("{world}", "<world>")
        .replace("{online}", "<online>")
        .replace("{ping}", "<ping>")
        .replace("{health}", "<health>")
        .replace("{max_health}", "<max_health>")
        .replace("{food}", "<food>")
        .replace("{level}", "<level>")
        .replace("{gamemode}", "<gamemode>")
        .replace("{x}", "<x>")
        .replace("{y}", "<y>")
        .replace("{z}", "<z>")

    fun renderPlain(template: String): Component =
        miniMessage.deserialize(template)

    private fun sidebarEntry(index: Int): String = SIDEBAR_ENTRIES[index]

    private fun sidebarTeamName(index: Int): String = "mui_line_$index"

    private fun nametagTeamName(index: Int, player: Player): String =
        "$NAMETAG_TEAM_PREFIX${index.toString().padStart(3, '0')}_${player.uniqueId.toString().take(12)}"

    private data class PaperUiConfig(
        val nametag: NametagConfig = NametagConfig(),
        val scoreboard: SidebarConfig = SidebarConfig(),
    )

    private data class NametagConfig(
        val enabled: Boolean = true,
        val updateIntervalTicks: Long = 40L,
        val highestWeightFirst: Boolean = true,
        val format: String = "<prefix><gray><name></gray><suffix>",
        val emptyPrefix: String = "",
        val emptySuffix: String = "",
    )

    private data class SidebarConfig(
        val enabled: Boolean = true,
        val updateIntervalTicks: Long = 20L,
        val title: String = "<bold>MenzelUI</bold>",
        val lines: List<String> = emptyList(),
    )

    private data class TaggedPlayer(
        val player: Player,
        val user: User?,
    ) {
        val weight: Int = user?.cachedData?.metaData?.weight ?: 0
    }

    private data class NametagSplit(
        val prefix: String,
        val suffix: String,
    )

    companion object {
        lateinit var instance: MenzelUIPaper
            private set

        private const val SIDEBAR_OBJECTIVE = "menzeluiSidebar"
        private const val NAMETAG_TEAM_PREFIX = "mui_nt_"
        private const val MAX_SIDEBAR_LINES = 15
        private val SIDEBAR_ENTRIES = ChatColor.values()
            .take(MAX_SIDEBAR_LINES)
            .map { "${it}${ChatColor.RESET}" }
    }
}
