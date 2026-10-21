package com.maxxlive.jeipaperbridge;

import java.util.List;

import net.minecraft.resources.Identifier;

/**
 * Channel ids of JEI's packets (see {@code mezz.jei.common.network.packets}).
 */
public final class JeiIds {
	public static final String NAMESPACE = "jei";

	// client to server
	public static final Identifier DELETE_PLAYER_ITEM = id("delete_player_item");
	public static final Identifier GIVE_ITEM_STACK = id("give_item_stack");
	public static final Identifier SET_HOTBAR_ITEM_STACK = id("set_hotbar_item_stack");
	public static final Identifier REQUEST_CHEAT_PERMISSION = id("request_cheat_permission");
	public static final Identifier RECIPE_TRANSFER = id("recipe_transfer");
	public static final Identifier RECIPE_TRANSFER_COUNTED = id("recipe_transfer_counted");
	public static final Identifier RECIPE_TRANSFER_WITH_RESULT = id("recipe_transfer_with_result");
	public static final Identifier RECIPE_TRANSFER_COUNTED_WITH_RESULT = id("recipe_transfer_counted_with_result");

	// server to client
	public static final Identifier CHEAT_PERMISSION = id("cheat_permission");
	public static final Identifier RECIPE_TRANSFER_RESULT = id("recipe_transfer_result");

	public static final List<Identifier> SERVERBOUND = List.of(
			DELETE_PLAYER_ITEM,
			GIVE_ITEM_STACK,
			SET_HOTBAR_ITEM_STACK,
			REQUEST_CHEAT_PERMISSION,
			RECIPE_TRANSFER,
			RECIPE_TRANSFER_COUNTED,
			RECIPE_TRANSFER_WITH_RESULT,
			RECIPE_TRANSFER_COUNTED_WITH_RESULT);

	public static final List<Identifier> CLIENTBOUND = List.of(CHEAT_PERMISSION, RECIPE_TRANSFER_RESULT);

	private JeiIds() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}
}
