package byd.cxkcxkckx.fuckportal.commands;

import byd.cxkcxkckx.fuckportal.IrregularPortalModule;
import byd.cxkcxkckx.fuckportal.fuckportal;
import byd.cxkcxkckx.fuckportal.func.AbstractModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.bukkit.ChatColor;
import top.mrxiaom.pluginbase.func.AutoRegister;

@AutoRegister
public class CommandConfirm extends AbstractModule implements CommandExecutor, Listener {
    public CommandConfirm(fuckportal plugin) {
        super(plugin);
        registerCommand("ccb", this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.YELLOW + "仅玩家可用此命令。");
            return true;
        }
        Player p = (Player) sender;
        IrregularPortalModule.confirmPendingByPlayer(p);
        return true;
    }
}
