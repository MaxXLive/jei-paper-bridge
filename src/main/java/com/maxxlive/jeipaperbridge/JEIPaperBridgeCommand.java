package com.maxxlive.jeipaperbridge;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * /jeipaperbridge reload | status
 */
public final class JEIPaperBridgeCommand implements TabExecutor {
	private final JEIPaperBridge plugin;

	public JEIPaperBridgeCommand(JEIPaperBridge plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			return false;
		}
		switch (args[0].toLowerCase()) {
			case "reload" -> {
				plugin.reload();
				sender.sendMessage("JEIPaperBridge reloaded.");
			}
			case "status" -> {
				Settings settings = plugin.settings();
				List<String> names = plugin.jeiPlayers().stream()
						.map(Player::getName)
						.sorted()
						.toList();
				sender.sendMessage("JEI clients online (%d): %s".formatted(names.size(), String.join(", ", names)));
				sender.sendMessage("Recipe transfer: %s, cheat mode: %s, recipe sync: %s".formatted(
						settings.recipeTransfer(), settings.cheatMode(), plugin.recipeSync().describe()));
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length != 1) {
			return List.of();
		}
		return List.of("reload", "status").stream()
				.filter($ -> $.regionMatches(true, 0, args[0], 0, args[0].length()))
				.toList();
	}
}
