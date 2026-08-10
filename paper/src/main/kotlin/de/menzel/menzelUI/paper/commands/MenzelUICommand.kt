package de.menzel.menzelUI.paper.commands

import de.menzel.menzelUI.paper.MenzelUIPaper
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack

class MenzelUICommand : BasicCommand {
    override fun execute(
        source: CommandSourceStack,
        args: Array<out String>
    ) {
        if (args.firstOrNull()?.equals("reload", ignoreCase = true) != true) {
            source.sender.sendMessage(MenzelUIPaper.instance.renderPlain("<red>Usage: /mui reload"))
            return
        }

        MenzelUIPaper.instance.loadSettings()
        MenzelUIPaper.instance.updateAll()
        source.sender.sendMessage(MenzelUIPaper.instance.renderPlain("<green>MenzelUI Paper config reloaded."))
    }

    override fun permission(): String {
        return "mui.command.reload"
    }
}