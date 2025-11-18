package io.nexstudios.regen.regen.commands;

import io.nexstudios.nexus.libs.commands.BaseCommand;
import io.nexstudios.nexus.libs.commands.annotation.*;
import io.nexstudios.regen.NexRegen;
import org.bukkit.command.CommandSender;

@CommandAlias("nexregen")
public class ReloadCommand extends BaseCommand {


    @Subcommand("reload")
    @CommandPermission("nexregen.command.admin.reload")
    @Description("Reloads the plugin configuration and settings.")
    public void onReload(CommandSender sender) {

        NexRegen.getInstance().reload();
        NexRegen.getInstance().getMessageSender().send(sender, "general.reload");
    }
}
