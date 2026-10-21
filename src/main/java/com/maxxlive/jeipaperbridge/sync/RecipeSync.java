package com.maxxlive.jeipaperbridge.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.jspecify.annotations.Nullable;

import com.maxxlive.jeipaperbridge.JEIPaperBridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Sends the server's recipes to Fabric clients with Fabric API's recipe sync protocol
 * (see {@code net.fabricmc.fabric.impl.recipe.sync}), which JEI uses on Fabric to show the server's recipes.
 * <p>
 * The client only accepts recipes it received right before a {@link ClientboundUpdateRecipesPacket}, so a netty
 * handler puts the cached recipe payload in front of every such packet. The cache is built on the main thread and
 * checked again after each recipe update; if recipes changed in the meantime, the player gets a fresh payload and
 * another recipe update.
 */
public final class RecipeSync {
	public static final Identifier RECIPE_SYNC = Identifier.fromNamespaceAndPath("fabric", "recipe_sync");
	private static final Identifier SPLIT = Identifier.fromNamespaceAndPath("fabric", "split");
	private static final Key LISTENER_KEY = Key.key("jeipaperbridge", "recipe_sync");
	private static final String HANDLER_NAME = "jeipaperbridge_recipe_sync";
	/** Fabric API splits packets that are at least as large as a vanilla custom payload may be. */
	private static final int SPLIT_SIZE = 1048576;
	/** Fabric API's limit for the recipe sync payload. */
	private static final int MAX_PAYLOAD_SIZE = 64 * 1024 * 1024;

	private final JEIPaperBridge plugin;
	/** Recipe updates sent by this class, which must not trigger another check. */
	private final Set<ClientboundUpdateRecipesPacket> ownUpdates = ConcurrentHashMap.newKeySet();
	/** Players that got a recipe update, with the cache version that was sent in front of it (0 for none). */
	private final Map<UUID, Integer> pendingChecks = new ConcurrentHashMap<>();
	private final AtomicBoolean checkScheduled = new AtomicBoolean();
	private volatile @Nullable Payload cache;
	private int version;

	private record Payload(int version, byte[] bytes, List<Packet<?>> packets) {
	}

	public RecipeSync(JEIPaperBridge plugin) {
		this.plugin = plugin;
	}

	public void enable() {
		rebuild();
		ChannelInitializeListenerHolder.addListener(LISTENER_KEY, channel -> addHandler(channel.pipeline()));
		for (Connection connection : MinecraftServer.getServer().getConnection().getConnections()) {
			connection.channel.eventLoop().execute(() -> addHandler(connection.channel.pipeline()));
		}
	}

	public void disable() {
		ChannelInitializeListenerHolder.removeListener(LISTENER_KEY);
		for (Connection connection : MinecraftServer.getServer().getConnection().getConnections()) {
			connection.channel.eventLoop().execute(() -> {
				if (connection.channel.pipeline().get(HANDLER_NAME) != null) {
					connection.channel.pipeline().remove(HANDLER_NAME);
				}
			});
		}
	}

	private void addHandler(ChannelPipeline pipeline) {
		if (pipeline.get(HANDLER_NAME) == null && pipeline.get(HandlerNames.PACKET_HANDLER) != null) {
			pipeline.addBefore(HandlerNames.PACKET_HANDLER, HANDLER_NAME, new Handler());
		}
	}

	/**
	 * Main thread: encodes all recipes again, e.g. after a config reload.
	 */
	public void rebuild() {
		try {
			cache = build();
		} catch (Exception e) {
			cache = null;
			plugin.getLogger().log(Level.SEVERE, "Failed to encode the recipes for Fabric clients", e);
		}
	}

	/**
	 * Main thread: sends the recipes and a recipe update, which makes JEI restart with these recipes.
	 */
	public void resync(ServerPlayer player) {
		Payload payload = cache;
		if (payload == null || !shouldSync(player)) {
			return;
		}
		payload.packets.forEach(player.connection::send);
		RecipeManager recipeManager = player.level().getServer().getRecipeManager();
		ClientboundUpdateRecipesPacket update = new ClientboundUpdateRecipesPacket(
				recipeManager.getSynchronizedItemProperties(),
				recipeManager.getSynchronizedStonecutterRecipes());
		ownUpdates.add(update);
		player.connection.send(update);
	}

	/**
	 * Any thread. Fabric clients without JEI ignore the payload, but announce their channels only after the
	 * first recipe update, so the client brand decides.
	 */
	public String describe() {
		Payload payload = cache;
		if (!plugin.settings().recipeSync()) {
			return "false";
		}
		if (payload == null) {
			return "true (no recipes encoded, see the server log)";
		}
		return "true (%d bytes in %d packets)".formatted(payload.bytes.length, payload.packets.size());
	}

	private boolean shouldSync(ServerPlayer player) {
		if (!plugin.settings().recipeSync()) {
			return false;
		}
		String brand = player.connection == null ? null : player.connection.clientBrand;
		return brand != null && brand.toLowerCase(Locale.ROOT).contains("fabric");
	}

	private void scheduleCheck(UUID player, int sentVersion) {
		pendingChecks.put(player, sentVersion);
		if (checkScheduled.compareAndSet(false, true) && plugin.isEnabled()) {
			plugin.getServer().getScheduler().runTask(plugin, this::check);
		}
	}

	private void check() {
		checkScheduled.set(false);
		rebuild();
		Payload payload = cache;
		int currentVersion = payload == null ? 0 : payload.version;
		for (UUID uuid : List.copyOf(pendingChecks.keySet())) {
			Integer sentVersion = pendingChecks.remove(uuid);
			ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
			if (player != null && sentVersion != null && sentVersion != currentVersion) {
				plugin.debug(Level.INFO, "Recipes changed, sending them again to " + player.getPlainTextName(), null);
				resync(player);
			}
		}
	}

	private @Nullable Payload build() {
		MinecraftServer server = MinecraftServer.getServer();
		RegistryAccess registryAccess = server.registryAccess();

		// Fabric clients mark the vanilla serializers as synced (JEI does that), so only those are sent.
		Map<Identifier, List<byte[]>> recipesBySerializer = new LinkedHashMap<>();
		int skipped = 0;
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			RecipeSerializer<?> serializer = holder.value().getSerializer();
			Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
			if (serializerId == null || !serializerId.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
				skipped++;
				continue;
			}
			byte[] encoded = encode(holder, serializer, registryAccess);
			if (encoded == null) {
				skipped++;
				continue;
			}
			recipesBySerializer.computeIfAbsent(serializerId, $ -> new ArrayList<>()).add(encoded);
		}

		// Byte layout of ClientboundRecipeSyncPayload: list of (serializer id, list of (recipe key, recipe))
		ByteBuf buf = Unpooled.buffer();
		VarInt.write(buf, recipesBySerializer.size());
		int count = 0;
		for (Map.Entry<Identifier, List<byte[]>> entry : recipesBySerializer.entrySet()) {
			Identifier.STREAM_CODEC.encode(buf, entry.getKey());
			VarInt.write(buf, entry.getValue().size());
			entry.getValue().forEach(buf::writeBytes);
			count += entry.getValue().size();
		}
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		buf.release();

		if (bytes.length > MAX_PAYLOAD_SIZE) {
			plugin.getLogger().warning("The recipes are too large for Fabric clients (%d bytes)".formatted(bytes.length));
			return null;
		}
		if (cache != null && Arrays.equals(bytes, cache.bytes)) {
			return cache;
		}
		List<Packet<?>> packets = toPackets(bytes, registryAccess);
		plugin.debug(Level.INFO, "Encoded %d recipes for Fabric clients (%d bytes in %d packets, %d skipped)".formatted(
				count, bytes.length, packets.size(), skipped), null);
		return new Payload(++version, bytes, packets);
	}

	/**
	 * Encodes the recipe and decodes it again, so a recipe the client can't read never breaks the whole payload.
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	private byte @Nullable [] encode(RecipeHolder<?> holder, RecipeSerializer<?> serializer, RegistryAccess registryAccess) {
		StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec = (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) serializer.streamCodec();
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			buf.writeResourceKey(holder.id());
			codec.encode(buf, holder.value());
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);

			RegistryFriendlyByteBuf check = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess);
			check.readResourceKey(Registries.RECIPE);
			codec.decode(check);
			if (check.isReadable()) {
				throw new IllegalStateException(check.readableBytes() + " bytes left after decoding");
			}
			return bytes;
		} catch (Exception e) {
			plugin.debug(Level.WARNING, "Skipping recipe %s for Fabric clients".formatted(holder.id().identifier()), e);
			return null;
		} finally {
			buf.release();
		}
	}

	/**
	 * Packets of at least {@link #SPLIT_SIZE} bytes are sent like Fabric API's {@code FabricPacketSplitter}:
	 * the whole encoded packet in {@code fabric:split} payloads, the first one starting with the total size.
	 */
	private static List<Packet<?>> toPackets(byte[] payload, RegistryAccess registryAccess) {
		ByteBuf packet = Unpooled.buffer();
		VarInt.write(packet, customPayloadPacketId(registryAccess));
		Identifier.STREAM_CODEC.encode(packet, RECIPE_SYNC);
		packet.writeBytes(payload);
		try {
			if (packet.readableBytes() < SPLIT_SIZE) {
				return List.of(new ClientboundCustomPayloadPacket(new DiscardedPayload(RECIPE_SYNC, payload)));
			}
			List<Packet<?>> packets = new ArrayList<>();
			ByteBuf first = Unpooled.buffer(SPLIT_SIZE);
			VarInt.write(first, packet.readableBytes());
			first.writeBytes(packet, SPLIT_SIZE - first.readableBytes());
			packets.add(splitPacket(first));
			while (packet.isReadable()) {
				packets.add(splitPacket(packet.readBytes(Math.min(packet.readableBytes(), SPLIT_SIZE))));
			}
			return packets;
		} finally {
			packet.release();
		}
	}

	private static Packet<?> splitPacket(ByteBuf chunk) {
		byte[] bytes = new byte[chunk.readableBytes()];
		chunk.readBytes(bytes);
		chunk.release();
		return new ClientboundCustomPayloadPacket(new DiscardedPayload(SPLIT, bytes));
	}

	private static int customPayloadPacketId(RegistryAccess registryAccess) {
		ByteBuf buf = Unpooled.buffer();
		try {
			GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec()
					.encode(buf, new ClientboundCustomPayloadPacket(new DiscardedPayload(RECIPE_SYNC, new byte[0])));
			return VarInt.read(buf);
		} finally {
			buf.release();
		}
	}

	/**
	 * Runs on the netty event loop of a single connection.
	 */
	private final class Handler extends ChannelOutboundHandlerAdapter {
		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (msg instanceof ClientboundUpdateRecipesPacket update && !ownUpdates.remove(update)) {
				try {
					beforeRecipeUpdate(ctx);
				} catch (Exception e) {
					plugin.debug(Level.WARNING, "Failed to send recipes before a recipe update", e);
				}
			}
			super.write(ctx, msg, promise);
		}

		private void beforeRecipeUpdate(ChannelHandlerContext ctx) {
			if (!(ctx.pipeline().get(HandlerNames.PACKET_HANDLER) instanceof Connection connection)) {
				return;
			}
			ServerPlayer player = connection.getPlayer();
			if (player == null || !shouldSync(player)) {
				return;
			}
			Payload payload = cache;
			if (payload != null) {
				for (Packet<?> packet : payload.packets) {
					ctx.write(packet);
				}
			}
			scheduleCheck(player.getUUID(), payload == null ? 0 : payload.version);
		}
	}
}
