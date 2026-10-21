package com.maxxlive.jeipaperbridge.cheat;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.maxxlive.jeipaperbridge.JEIPaperBridge;
import com.maxxlive.jeipaperbridge.Settings;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Port of JEI's {@code mezz.jei.common.util.ServerCommandUtil} and {@code PacketDeletePlayerItem}.
 */
public final class CheatHandler {
	public static final String PERMISSION = "jeipaperbridge.cheat";

	public enum GiveMode {
		INVENTORY, MOUSE_PICKUP
	}

	private final JEIPaperBridge plugin;

	public CheatHandler(JEIPaperBridge plugin) {
		this.plugin = plugin;
	}

	/**
	 * JEI's {@code hasPermissionForCheatMode}, plus the feature switch and the Bukkit permission.
	 */
	public boolean hasPermission(ServerPlayer player) {
		Settings settings = plugin.settings();
		if (!settings.cheatMode() || !player.getBukkitEntity().hasPermission(PERMISSION)) {
			return false;
		}
		if (settings.cheatForCreative() && player.isCreative()) {
			return true;
		}
		if (settings.cheatForOp()) {
			return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		}
		if (settings.cheatForGive()) {
			CommandNode<CommandSourceStack> give = player.level().getServer().getCommands().getDispatcher().getRoot().getChild("give");
			return give != null && give.canUse(player.createCommandSourceStack());
		}
		return false;
	}

	/**
	 * Translation keys the client lists when cheat mode is denied, see JEI's {@code PacketCheatPermission}.
	 */
	public List<String> allowedCheatingMethods() {
		Settings settings = plugin.settings();
		List<String> methods = new ArrayList<>();
		if (!settings.cheatMode()) {
			return methods;
		}
		if (settings.cheatForOp()) {
			methods.add("jei.chat.error.no.cheat.permission.op");
		}
		if (settings.cheatForCreative()) {
			methods.add("jei.chat.error.no.cheat.permission.creative");
		}
		if (settings.cheatForGive()) {
			methods.add("jei.chat.error.no.cheat.permission.give");
		}
		return methods;
	}

	public void give(ServerPlayer player, ItemStack itemStack, GiveMode giveMode) {
		if (!isValid(player, itemStack)) {
			return;
		}
		if (giveMode == GiveMode.INVENTORY) {
			giveToInventory(player, itemStack);
		} else {
			mousePickupItemStack(player, itemStack);
		}
	}

	public void setHotbarSlot(ServerPlayer player, ItemStack itemStack, int hotbarSlot) {
		if (!isValid(player, itemStack)) {
			return;
		}
		if (!Inventory.isHotbarSlot(hotbarSlot)) {
			plugin.debug(Level.INFO, "%s tried to set slot that is not in the hotbar: %d".formatted(player.getPlainTextName(), hotbarSlot), null);
			return;
		}
		ItemStack stackInSlot = player.getInventory().getItem(hotbarSlot);
		if (ItemStack.matches(stackInSlot, itemStack)) {
			return;
		}
		ItemStack itemStackCopy = itemStack.copy();
		player.getInventory().setItem(hotbarSlot, itemStack);
		playPickupSound(player);
		player.inventoryMenu.broadcastChanges();
		notifyGive(player, itemStackCopy);
	}

	/**
	 * Deletes the item on the cursor, but only if it is the item the client wants to delete.
	 */
	public void deleteCarried(ServerPlayer player, ItemStack itemStack) {
		AbstractContainerMenu menu = player.containerMenu;
		ItemStack carried = menu.getCarried();
		if (carried.getItem() == itemStack.getItem()) {
			menu.setCarried(ItemStack.EMPTY);
		} else if (!carried.isEmpty()) {
			plugin.debug(Level.INFO, "%s tried to delete %s but is holding %s".formatted(player.getPlainTextName(), itemStack, carried), null);
		}
	}

	/**
	 * The client chooses the stack, so reject what a vanilla creative inventory would reject.
	 */
	private boolean isValid(ServerPlayer player, ItemStack itemStack) {
		if (itemStack.isEmpty()) {
			return false;
		}
		if (!itemStack.isItemEnabled(player.level().enabledFeatures()) || ItemStack.validateStrict(itemStack).isError()) {
			plugin.debug(Level.INFO, "%s tried to cheat an invalid item: %s".formatted(player.getPlainTextName(), itemStack), null);
			return false;
		}
		if (itemStack.getCount() > itemStack.getMaxStackSize()) {
			itemStack.setCount(itemStack.getMaxStackSize());
		}
		return true;
	}

	private void mousePickupItemStack(ServerPlayer player, ItemStack itemStack) {
		AbstractContainerMenu menu = player.containerMenu;
		ItemStack itemStackCopy = itemStack.copy();
		ItemStack existingStack = menu.getCarried();

		final int giveCount;
		if (canStack(existingStack, itemStack)) {
			int newCount = Math.min(existingStack.getMaxStackSize(), existingStack.getCount() + itemStack.getCount());
			giveCount = newCount - existingStack.getCount();
			if (giveCount > 0) {
				existingStack.setCount(newCount);
			}
		} else {
			menu.setCarried(itemStack);
			giveCount = itemStack.getCount();
		}

		if (giveCount > 0) {
			itemStackCopy.setCount(giveCount);
			notifyGive(player, itemStackCopy);
			menu.broadcastChanges();
		}
	}

	private static boolean canStack(ItemStack a, ItemStack b) {
		return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
	}

	/**
	 * Same as vanilla's give command.
	 */
	private void giveToInventory(ServerPlayer player, ItemStack itemStack) {
		ItemStack itemStackCopy = itemStack.copy();
		boolean added = player.getInventory().add(itemStack);
		if (added && itemStack.isEmpty()) {
			itemStack.setCount(1);
			ItemEntity itemEntity = player.drop(itemStack, false, Prediction.SERVER_ONLY);
			if (itemEntity != null) {
				itemEntity.makeFakeItem();
			}
			playPickupSound(player);
			player.inventoryMenu.broadcastChanges();
		} else {
			ItemEntity itemEntity = player.drop(itemStack, false, Prediction.SERVER_ONLY);
			if (itemEntity != null) {
				itemEntity.setNoPickUpDelay();
				itemEntity.setTarget(player.getUUID());
			}
		}
		notifyGive(player, itemStackCopy);
	}

	private static void playPickupSound(ServerPlayer player) {
		float pitch = ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F;
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, pitch);
	}

	private static void notifyGive(ServerPlayer player, ItemStack stack) {
		Component message = Component.translatable("commands.give.success.single", stack.getCount(), stack.getDisplayName(), player.getDisplayName());
		player.createCommandSourceStack().sendSuccess(() -> message, true);
	}
}
