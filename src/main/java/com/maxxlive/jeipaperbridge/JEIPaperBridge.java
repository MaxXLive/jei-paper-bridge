package com.maxxlive.jeipaperbridge;

import java.util.List;
import java.util.logging.Level;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jspecify.annotations.Nullable;

import com.maxxlive.jeipaperbridge.cheat.CheatHandler;
import com.maxxlive.jeipaperbridge.network.JeiNetworkHandler;
import com.maxxlive.jeipaperbridge.sync.RecipeSync;
import com.maxxlive.jeipaperbridge.transfer.RecipeTransferServer;

public final class JEIPaperBridge extends JavaPlugin implements Listener {
	private final JeiNetworkHandler networkHandler = new JeiNetworkHandler(this);
	private final CheatHandler cheatHandler = new CheatHandler(this);
	private final RecipeTransferServer transferServer = new RecipeTransferServer(this);
	private final RecipeSync recipeSync = new RecipeSync(this);
	private volatile Settings settings = Settings.DEFAULT;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		settings = Settings.load(getConfig());

		// JEI clients look for these channels (announced by Paper on join) to detect JEI on the server
		Messenger messenger = getServer().getMessenger();
		for (var channel : JeiIds.SERVERBOUND) {
			messenger.registerIncomingPluginChannel(this, channel.toString(), networkHandler);
		}
		for (var channel : JeiIds.CLIENTBOUND) {
			messenger.registerOutgoingPluginChannel(this, channel.toString());
		}
		getServer().getPluginManager().registerEvents(this, this);
		recipeSync.enable();

		JEIPaperBridgeCommand command = new JEIPaperBridgeCommand(this);
		var pluginCommand = getCommand("jeipaperbridge");
		if (pluginCommand != null) {
			pluginCommand.setExecutor(command);
			pluginCommand.setTabCompleter(command);
		}

		getLogger().info("Serving JEI clients (recipe transfer: %s, cheat mode: %s, recipe sync: %s)".formatted(
				onOff(settings.recipeTransfer()),
				onOff(settings.cheatMode()),
				onOff(settings.recipeSync())));
	}

	@Override
	public void onDisable() {
		recipeSync.disable();
		getServer().getMessenger().unregisterIncomingPluginChannel(this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this);
	}

	/**
	 * Reloads config.yml and sends the current recipes to every Fabric client.
	 */
	public void reload() {
		reloadConfig();
		settings = Settings.load(getConfig());
		recipeSync.rebuild();
		for (Player player : getServer().getOnlinePlayers()) {
			recipeSync.resync(((CraftPlayer) player).getHandle());
		}
	}

	/**
	 * JEI clients announce the channels they receive on.
	 */
	public List<Player> jeiPlayers() {
		return getServer().getOnlinePlayers().stream()
				.filter(player -> player.getListeningPluginChannels().contains(JeiIds.RECIPE_TRANSFER_RESULT.toString()))
				.map(Player.class::cast)
				.toList();
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		networkHandler.forget(event.getPlayer().getUniqueId());
	}

	public void debug(Level level, String message, @Nullable Throwable throwable) {
		if (settings.debug()) {
			getLogger().log(level, message, throwable);
		}
	}

	private static String onOff(boolean value) {
		return value ? "on" : "off";
	}

	public Settings settings() {
		return settings;
	}

	public CheatHandler cheatHandler() {
		return cheatHandler;
	}

	public RecipeTransferServer transferServer() {
		return transferServer;
	}

	public RecipeSync recipeSync() {
		return recipeSync;
	}
}
