package de.menzel.menzelUI;

import com.google.inject.Inject
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.util.GameProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.slf4j.Logger
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.UUID

class MenzelUI @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private var luckPerms: LuckPerms? = null
    private var config = TablistConfig()
    private var updateTask: ScheduledTask? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        config = loadConfig()
        luckPerms = LuckPermsProvider.get()

        if (!config.enabled) {
            logger.info("Custom tablist is disabled in the config.")
            return
        }

        updateTask = server.scheduler
            .buildTask(this, Runnable { updateTablists() })
            .delay(Duration.ofSeconds(1))
            .repeat(Duration.ofSeconds(config.updateIntervalSeconds))
            .schedule()

        updateTablists()
        logger.info("Custom tablist with LuckPerms support enabled.")
    }

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        updateTablistsSoon()
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        updateTablistsSoon()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        updateTablistsSoon()
        removePlayerFromTablistsSoon(event.player.uniqueId)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        updateTask?.cancel()
    }

    private fun updateTablistsSoon() {
        server.scheduler
            .buildTask(this, Runnable { updateTablists() })
            .delay(Duration.ofMillis(500))
            .schedule()
    }

    private fun removePlayerFromTablistsSoon(uuid: UUID) {
        server.scheduler
            .buildTask(this, Runnable {
                server.allPlayers.forEach { viewer ->
                    viewer.tabList.removeEntry(uuid)
                }
            })
            .delay(Duration.ofSeconds(1))
            .schedule()
    }

    private fun updateTablists() {
        if (!config.enabled) {
            return
        }

        val luckPerms = luckPerms ?: return
        val header = config.header.toComponent()
        val footer = config.footer.toComponent()
        val playerEntries = server.allPlayers
            .map { player -> TabPlayer(player, luckPerms.userManager.getUser(player.uniqueId)) }
            .map { tabPlayer -> tabPlayer.toDisplayEntry() }
            .sortedWith(compareByDisplayEntry())
            .mapIndexed { index, entry -> entry.copy(listOrder = index) }

        server.allPlayers.forEach { viewer ->
            viewer.tabList.setHeaderAndFooter(header, footer)
            val tabList = viewer.tabList
            playerEntries.forEach { entry ->
                val current = tabList.getEntry(entry.uuid)
                if (current.isPresent) {
                    current.get()
                        .setDisplayName(entry.displayName)
                        .setLatency(entry.latency)
                        .setGameMode(entry.gameMode)
                        .setListed(true)
                        .setListOrder(entry.listOrder)
                } else {
                    tabList.addEntry(
                        tabList.buildEntry(
                            entry.profile,
                            entry.displayName,
                            entry.latency,
                            entry.gameMode,
                            null,
                            true,
                            entry.listOrder,
                            true,
                        ),
                    )
                }
            }
        }
    }

    private fun TabPlayer.toDisplayEntry(): DisplayEntry {
        val meta = user?.cachedData?.metaData
        val prefix = meta?.prefix ?: config.emptyPrefix
        val suffix = meta?.suffix ?: config.emptySuffix
        val weight = meta?.weight ?: 0
        val rendered = config.format
            .normalizePlaceholders()
            .replace(Regex("\\s+"), " ")
            .trim()

        return DisplayEntry(
            player = player,
            weight = weight,
            displayName = miniMessage.deserialize(
                rendered,
                Placeholder.parsed("prefix", prefix),
                Placeholder.component("name", Component.text(player.username)),
                Placeholder.parsed("suffix", suffix),
                Placeholder.unparsed("weight", weight.toString()),
            ),
        )
    }

    private fun String.normalizePlaceholders(): String = this
        .replace("{prefix}", "<prefix>")
        .replace("{name}", "<name>")
        .replace("{suffix}", "<suffix>")
        .replace("{weight}", "<weight>")

    private fun compareByDisplayEntry(): Comparator<DisplayEntry> {
        val weightComparator = compareBy<DisplayEntry> { it.weight }
        val comparator = if (config.highestWeightFirst) {
            weightComparator
        } else {
            weightComparator.reversed()
        }

        return comparator.thenBy { it.player.username.lowercase() }
    }

    private fun List<String>.toComponent(): Component =
        if (isEmpty()) {
            Component.empty()
        } else {
            miniMessage.deserialize(joinToString("\n") { it.normalizePlaceholders() })
        }

    private fun loadConfig(): TablistConfig {
        Files.createDirectories(dataDirectory)
        val configPath = dataDirectory.resolve("config.yml")
        if (Files.notExists(configPath)) {
            javaClass.classLoader.getResourceAsStream("config.yml").use { input ->
                if (input == null) {
                    Files.writeString(configPath, DEFAULT_CONFIG)
                } else {
                    Files.copy(input, configPath)
                }
            }
        }

        val root = YamlConfigurationLoader.builder()
            .path(configPath)
            .build()
            .load()

        val tablist = root.node("tablist")
        return TablistConfig(
            enabled = tablist.node("enabled").getBoolean(true),
            header = readStringList(tablist, "header", "beforeNames", "beforePlayerList"),
            format = readFormat(root, tablist),
            footer = readStringList(tablist, "footer", "afterNames", "afterPlayerList", "aftername"),
            updateIntervalSeconds = tablist.node("update-interval-seconds").getLong(5).coerceAtLeast(1),
            highestWeightFirst = tablist.node("highest-weight-first").getBoolean(true),
            emptyPrefix = tablist.node("empty-prefix").getString(""),
            emptySuffix = tablist.node("empty-suffix").getString(""),
        )
    }

    private fun readStringList(node: org.spongepowered.configurate.ConfigurationNode, vararg keys: String): List<String> {
        keys.forEach { key ->
            val child = node.node(key)
            if (!child.virtual()) {
                return child.getList(String::class.java, emptyList())
            }
        }
        return emptyList()
    }

    private fun readFormat(
        root: org.spongepowered.configurate.ConfigurationNode,
        tablist: org.spongepowered.configurate.ConfigurationNode,
    ): String {
        val rootFormat = root.node("format")
        if (!rootFormat.virtual()) {
            return rootFormat.getString("<prefix> <name> <suffix>")
        }

        val oldFormat = tablist.node("format")
        if (oldFormat.isList) {
            return oldFormat.getList(String::class.java, listOf("<prefix>", "<name>", "<suffix>")).joinToString(" ")
        }
        return oldFormat.getString("<prefix> <name> <suffix>")
    }

    private data class TablistConfig(
        val enabled: Boolean = true,
        val header: List<String> = emptyList(),
        val format: String = "<prefix> <name> <suffix>",
        val footer: List<String> = emptyList(),
        val updateIntervalSeconds: Long = 5,
        val highestWeightFirst: Boolean = true,
        val emptyPrefix: String = "",
        val emptySuffix: String = "",
    )

    private data class TabPlayer(
        val player: Player,
        val user: User?,
    )

    private data class DisplayEntry(
        val player: Player,
        val weight: Int,
        val displayName: Component,
        val listOrder: Int = 0,
    ) {
        val uuid: UUID = player.uniqueId
        val profile: GameProfile = player.gameProfile
        val latency: Int = player.ping.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val gameMode: Int = player.tabList.getEntry(uuid).map { it.gameMode }.orElse(0)
    }

    private companion object {
        private const val DEFAULT_CONFIG = """
format: "<prefix> <name> <suffix>"

tablist:
  enabled: true
  header:
    - "<gray>custom stuff 1</gray>"
    - "<gray>custom 2</gray>"
  footer:
    - "<gray>custom 4</gray>"
  update-interval-seconds: 5
  highest-weight-first: true
  empty-prefix: ""
  empty-suffix: ""
"""
    }
}
