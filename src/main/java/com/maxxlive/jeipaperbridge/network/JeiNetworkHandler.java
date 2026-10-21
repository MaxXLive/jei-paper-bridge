package com.maxxlive.jeipaperbridge.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.maxxlive.jeipaperbridge.JEIPaperBridge;
import com.maxxlive.jeipaperbridge.JeiIds;
import com.maxxlive.jeipaperbridge.cheat.CheatHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Implements the server side of JEI's network protocol (see {@code mezz.jei.common.network.packets}).
 * Paper calls plugin message listeners on the main thread, so everything here runs on the main thread.
 */
public final class JeiNetworkHandler implements PluginMessageListener {
	private static final long WINDOW_NANOS = 1_000_000_000L;
	/** A menu has at most a few dozen slots, so JEI never sends more entries than this. */
	private static final int MAX_LIST_SIZE = 256;

	private static final StreamCodec<ByteBuf, List<Integer>> SLOT_LIST_CODEC = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_LIST_SIZE));
	private static final StreamCodec<ByteBuf, List<TransferOperation>> OPERATIONS_CODEC = TransferOperation.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIST_SIZE));
	private static final StreamCodec<ByteBuf, List<TransferOperation>> COUNTED_OPERATIONS_CODEC = TransferOperation.COUNTED_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIST_SIZE));
	private static final StreamCodec<ByteBuf, List<String>> STRING_LIST_CODEC = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

	private final JEIPaperBridge plugin;
	private final Map<UUID, RateLimit> rateLimits = new HashMap<>();

	private static final class RateLimit {
		private long windowStart;
		private int count;
	}

	public JeiNetworkHandler(JEIPaperBridge plugin) {
		this.plugin = plugin;
	}

	public void forget(UUID player) {
		rateLimits.remove(player);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player bukkitPlayer, byte[] message) {
		ServerPlayer player = ((CraftPlayer) bukkitPlayer).getHandle();
		// an exception escaping here would get the player kicked by Paper
		try {
			if (!tryAcquire(player)) {
				plugin.debug(Level.INFO, "Ignored %s from %s: too many packets".formatted(channel, bukkitPlayer.getName()), null);
				return;
			}
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(message), player.level().registryAccess());
			handle(player, Identifier.parse(channel), buf);
		} catch (Exception e) {
			plugin.debug(Level.WARNING, "Failed to handle %s from %s".formatted(channel, bukkitPlayer.getName()), e);
		}
	}

	private void handle(ServerPlayer player, Identifier channel, RegistryFriendlyByteBuf buf) {
		CheatHandler cheat = plugin.cheatHandler();
		if (channel.equals(JeiIds.REQUEST_CHEAT_PERMISSION)) {
			sendCheatPermission(player, cheat.hasPermission(player));
		} else if (channel.equals(JeiIds.GIVE_ITEM_STACK)) {
			ItemStack itemStack = ItemStack.STREAM_CODEC.decode(buf);
			// JEI writes the enum ordinal as a VarInt
			CheatHandler.GiveMode giveMode = CheatHandler.GiveMode.values()[ByteBufCodecs.VAR_INT.decode(buf)];
			if (checkCheatPermission(player, channel)) {
				cheat.give(player, itemStack, giveMode);
			}
		} else if (channel.equals(JeiIds.SET_HOTBAR_ITEM_STACK)) {
			ItemStack itemStack = ItemStack.STREAM_CODEC.decode(buf);
			int hotbarSlot = ByteBufCodecs.VAR_INT.decode(buf);
			if (checkCheatPermission(player, channel)) {
				cheat.setHotbarSlot(player, itemStack, hotbarSlot);
			}
		} else if (channel.equals(JeiIds.DELETE_PLAYER_ITEM)) {
			ItemStack itemStack = ItemStack.STREAM_CODEC.decode(buf);
			if (checkCheatPermission(player, channel)) {
				cheat.deleteCarried(player, itemStack);
			}
		} else if (channel.equals(JeiIds.RECIPE_TRANSFER)) {
			handleTransfer(player, buf, OPERATIONS_CODEC, false);
		} else if (channel.equals(JeiIds.RECIPE_TRANSFER_COUNTED)) {
			handleTransfer(player, buf, COUNTED_OPERATIONS_CODEC, false);
		} else if (channel.equals(JeiIds.RECIPE_TRANSFER_WITH_RESULT)) {
			handleTransfer(player, buf, OPERATIONS_CODEC, true);
		} else if (channel.equals(JeiIds.RECIPE_TRANSFER_COUNTED_WITH_RESULT)) {
			handleTransfer(player, buf, COUNTED_OPERATIONS_CODEC, true);
		}
	}

	/**
	 * Like JEI, tells the client to turn off cheat mode when it is not allowed.
	 */
	private boolean checkCheatPermission(ServerPlayer player, Identifier channel) {
		if (plugin.cheatHandler().hasPermission(player)) {
			return true;
		}
		plugin.debug(Level.INFO, "%s sent %s but may not use cheat mode".formatted(player.getPlainTextName(), channel), null);
		sendCheatPermission(player, false);
		return false;
	}

	/**
	 * Byte layout of JEI's {@code PacketRecipeTransfer} and its counted/with-result variants.
	 */
	private void handleTransfer(
			ServerPlayer player,
			RegistryFriendlyByteBuf buf,
			StreamCodec<ByteBuf, List<TransferOperation>> operationsCodec,
			boolean withResult) {
		List<TransferOperation> operations = operationsCodec.decode(buf);
		List<Integer> craftingSlots = SLOT_LIST_CODEC.decode(buf);
		List<Integer> inventorySlots = SLOT_LIST_CODEC.decode(buf);
		boolean maxTransfer = buf.readBoolean();
		boolean requireCompleteSets = buf.readBoolean();
		int transferId = withResult ? ByteBufCodecs.VAR_INT.decode(buf) : 0;

		boolean successful = plugin.settings().recipeTransfer() && plugin.transferServer().transfer(
				player,
				operations,
				craftingSlots,
				inventorySlots,
				maxTransfer,
				requireCompleteSets);
		if (withResult) {
			send(player, JeiIds.RECIPE_TRANSFER_RESULT, out -> {
				ByteBufCodecs.VAR_INT.encode(out, transferId);
				out.writeBoolean(successful);
			});
		}
	}

	public void sendCheatPermission(ServerPlayer player, boolean hasPermission) {
		List<String> methods = plugin.cheatHandler().allowedCheatingMethods();
		send(player, JeiIds.CHEAT_PERMISSION, out -> {
			out.writeBoolean(hasPermission);
			STRING_LIST_CODEC.encode(out, methods);
		});
	}

	private boolean tryAcquire(ServerPlayer player) {
		RateLimit rateLimit = rateLimits.computeIfAbsent(player.getUUID(), $ -> new RateLimit());
		long now = System.nanoTime();
		if (now - rateLimit.windowStart > WINDOW_NANOS) {
			rateLimit.windowStart = now;
			rateLimit.count = 0;
		}
		return ++rateLimit.count <= plugin.settings().maxPacketsPerSecond();
	}

	/**
	 * Sends the packet directly instead of using {@code Player#sendPluginMessage}, which silently drops it
	 * when the client has not announced the channel yet.
	 */
	private static void send(ServerPlayer player, Identifier id, Consumer<RegistryFriendlyByteBuf> writer) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.level().registryAccess());
		try {
			writer.accept(buf);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)));
		} finally {
			buf.release();
		}
	}
}
