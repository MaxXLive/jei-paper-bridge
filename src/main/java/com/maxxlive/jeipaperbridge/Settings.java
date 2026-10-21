package com.maxxlive.jeipaperbridge;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * @param cheatForOp       counterpart of JEI's {@code enableCheatModeForOp}
 * @param cheatForCreative counterpart of JEI's {@code enableCheatModeForCreative}
 * @param cheatForGive     counterpart of JEI's {@code enableCheatModeForGive}
 */
public record Settings(
		boolean recipeTransfer,
		boolean cheatMode,
		boolean recipeSync,
		boolean cheatForOp,
		boolean cheatForCreative,
		boolean cheatForGive,
		boolean fireInventoryClickEvent,
		int maxPacketsPerSecond,
		boolean debug) {

	public static final Settings DEFAULT = new Settings(true, true, true, true, true, false, true, 20, false);

	public static Settings load(FileConfiguration config) {
		return new Settings(
				config.getBoolean("features.recipe-transfer", true),
				config.getBoolean("features.cheat-mode", true),
				config.getBoolean("features.recipe-sync", true),
				config.getBoolean("cheat-mode.enable-for-op", true),
				config.getBoolean("cheat-mode.enable-for-creative", true),
				config.getBoolean("cheat-mode.enable-for-give", false),
				config.getBoolean("recipe-transfer.fire-inventory-click-event", true),
				Math.max(1, config.getInt("max-packets-per-second", 20)),
				config.getBoolean("debug", false));
	}
}
