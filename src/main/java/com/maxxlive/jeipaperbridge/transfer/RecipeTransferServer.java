package com.maxxlive.jeipaperbridge.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.jspecify.annotations.Nullable;

import com.maxxlive.jeipaperbridge.JEIPaperBridge;
import com.maxxlive.jeipaperbridge.network.TransferOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Port of JEI's {@code BasicRecipeTransferHandlerServer} and {@code RecipeTransferUtil#validateSlots}.
 * In addition to JEI's checks, only menus with a vanilla JEI transfer handler are accepted, items may only
 * come from the player's own inventory, and other plugins get a chance to cancel the transfer.
 */
public final class RecipeTransferServer {
	/** Menus JEI's VanillaPlugin registers transfer handlers for. */
	private static final List<Class<? extends AbstractContainerMenu>> SUPPORTED_MENUS = List.of(
			InventoryMenu.class,
			CraftingMenu.class,
			CrafterMenu.class,
			AbstractFurnaceMenu.class,
			BrewingStandMenu.class,
			AnvilMenu.class,
			SmithingMenu.class);

	private final JEIPaperBridge plugin;

	public RecipeTransferServer(JEIPaperBridge plugin) {
		this.plugin = plugin;
	}

	/**
	 * Puts the items in place and reports whether the transfer was applied.
	 */
	public boolean transfer(
			ServerPlayer player,
			List<TransferOperation> transferOperations,
			List<Integer> craftingSlotIds,
			List<Integer> inventorySlotIds,
			boolean maxTransfer,
			boolean requireCompleteSets) {
		AbstractContainerMenu container = player.containerMenu;
		if (!isSupportedMenu(player, container)) {
			return false;
		}
		List<Slot> craftingSlots = getSlots(container, craftingSlotIds);
		List<Slot> inventorySlots = getSlots(container, inventorySlotIds);
		if (craftingSlots == null || inventorySlots == null) {
			return false;
		}
		if (!validateSlots(player, transferOperations, craftingSlots, inventorySlots)) {
			return false;
		}
		if (!canClearCraftingSlots(player, craftingSlots)) {
			return false;
		}

		List<RequiredTransfer> requiredTransfers = calculateRequiredTransfers(transferOperations, player);
		if (requiredTransfers == null || !callClickEvent(player, craftingSlots)) {
			return false;
		}

		// Transfer as many items as possible only if it has been explicitly requested by the implementation
		// and a max-transfer operation has been requested by the player.
		boolean transferAsCompleteSets = requireCompleteSets || !maxTransfer;

		Map<Slot, ItemStack> recipeSlotToTakenStacks = takeItemsFromInventory(
				player,
				requiredTransfers,
				craftingSlots,
				inventorySlots,
				transferAsCompleteSets,
				maxTransfer);

		if (recipeSlotToTakenStacks.isEmpty()) {
			reject(player, "unable to remove any items from the inventory");
			return false;
		}

		// clear the crafting grid
		List<ItemStack> clearedCraftingItems = clearCraftingGrid(craftingSlots, player);

		// put items into the crafting grid
		List<ItemStack> remainderItems = putItemsIntoCraftingGrid(recipeSlotToTakenStacks, requireCompleteSets);

		// put leftover items back into the inventory
		stowItems(player, inventorySlots, clearedCraftingItems);
		stowItems(player, inventorySlots, remainderItems);

		container.broadcastChanges();
		return true;
	}

	private boolean isSupportedMenu(ServerPlayer player, AbstractContainerMenu container) {
		if (!container.stillValid(player)) {
			reject(player, "the open menu is no longer valid");
			return false;
		}
		for (Class<? extends AbstractContainerMenu> menuClass : SUPPORTED_MENUS) {
			if (menuClass.isInstance(container)) {
				return true;
			}
		}
		reject(player, "unsupported menu " + container.getClass().getSimpleName());
		return false;
	}

	/**
	 * Lets protection and GUI plugins cancel the transfer, like they would cancel a click in this menu.
	 */
	private boolean callClickEvent(ServerPlayer player, List<Slot> craftingSlots) {
		if (!plugin.settings().fireInventoryClickEvent() || craftingSlots.isEmpty()) {
			return true;
		}
		AbstractContainerMenu menu = player.containerMenu;
		InventoryView view = menu.getBukkitView();
		int rawSlot = craftingSlots.getFirst().index;
		InventoryType.SlotType slotType = view.getSlotType(rawSlot);
		InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, ClickType.UNKNOWN, InventoryAction.UNKNOWN);
		plugin.getServer().getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			reject(player, "cancelled by an InventoryClickEvent listener");
			return false;
		}
		// a listener may have closed or replaced the menu
		return player.containerMenu == menu;
	}

	@Nullable
	private List<Slot> getSlots(AbstractContainerMenu container, List<Integer> slotIds) {
		if (slotIds.size() > container.slots.size()) {
			plugin.debug(Level.INFO, "Recipe transfer has too many slot ids %d for %s with %d slots".formatted(
					slotIds.size(), container.getClass().getSimpleName(), container.slots.size()), null);
			return null;
		}
		List<Slot> slots = new ArrayList<>(slotIds.size());
		for (int slotId : slotIds) {
			if (!isValidSlotId(container, slotId)) {
				plugin.debug(Level.INFO, "Recipe transfer has invalid slot id %d for %s".formatted(slotId, container.getClass().getSimpleName()), null);
				return null;
			}
			slots.add(container.getSlot(slotId));
		}
		return slots;
	}

	private boolean validateSlots(
			ServerPlayer player,
			Collection<TransferOperation> transferOperations,
			Collection<Slot> craftingSlots,
			Collection<Slot> inventorySlots) {
		AbstractContainerMenu container = player.containerMenu;
		boolean invalidOperationSlots = transferOperations.stream()
				.flatMap(op -> Stream.of(op.inventorySlotId(), op.craftingSlotId()))
				.anyMatch(slotId -> !isValidSlotId(container, slotId));
		if (invalidOperationSlots) {
			reject(player, "invalid slot ids in its transfer operations");
			return false;
		}

		Set<Integer> inventorySlotIndexes = inventorySlots.stream().map(s -> s.index).collect(Collectors.toSet());
		Set<Integer> craftingSlotIndexes = craftingSlots.stream().map(s -> s.index).collect(Collectors.toSet());

		// check that all craftingTargetSlots are included in craftingSlots
		if (transferOperations.stream().anyMatch(op -> !craftingSlotIndexes.contains(op.craftingSlotId()))) {
			reject(player, "destination slots that are not in the list of crafting slots");
			return false;
		}

		// check that all ingredientTargetSlots are included in inventorySlots or recipeSlots
		boolean invalidSources = transferOperations.stream()
				.anyMatch(op -> !inventorySlotIndexes.contains(op.inventorySlotId()) && !craftingSlotIndexes.contains(op.inventorySlotId()));
		if (invalidSources) {
			reject(player, "source slots that are not in the list of inventory or crafting slots");
			return false;
		}

		// check that crafting slots and inventory slots do not overlap
		if (inventorySlotIndexes.stream().anyMatch(craftingSlotIndexes::contains)) {
			reject(player, "overlapping inventory and crafting slots");
			return false;
		}

		// check that all slots are real (not output slots)
		if (Stream.concat(craftingSlots.stream(), inventorySlots.stream()).anyMatch(Slot::isFake)) {
			reject(player, "fake slots (recipe outputs)");
			return false;
		}

		// items may only come from the player's own inventory, never from another container in the menu
		if (inventorySlots.stream().anyMatch(slot -> slot.container != player.getInventory())) {
			reject(player, "inventory slots that do not belong to the player");
			return false;
		}
		return true;
	}

	private static boolean isValidSlotId(AbstractContainerMenu container, int slotId) {
		return slotId >= 0 && slotId < container.slots.size();
	}

	private boolean canClearCraftingSlots(ServerPlayer player, List<Slot> craftingSlots) {
		for (Slot craftingSlot : craftingSlots) {
			ItemStack stack = craftingSlot.getItem();
			if (!stack.isEmpty() && (!craftingSlot.mayPickup(player) || !craftingSlot.mayPlace(stack))) {
				reject(player, "crafting slot %d contains an item that cannot be moved".formatted(craftingSlot.index));
				return false;
			}
		}
		return true;
	}

	private static int getSlotStackLimit(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets) {
		if (!requireCompleteSets) {
			return Integer.MAX_VALUE;
		}
		return recipeSlotToTakenStacks.entrySet().stream()
				.mapToInt(e -> {
					Slot craftingSlot = e.getKey();
					ItemStack transferItem = e.getValue();
					if (craftingSlot.mayPlace(transferItem)) {
						return craftingSlot.getMaxStackSize(transferItem);
					}
					return Integer.MAX_VALUE;
				})
				.min()
				.orElse(Integer.MAX_VALUE);
	}

	private static List<ItemStack> clearCraftingGrid(List<Slot> craftingSlots, Player player) {
		List<ItemStack> clearedCraftingItems = new ArrayList<>();
		for (Slot craftingSlot : craftingSlots) {
			if (!craftingSlot.mayPickup(player)) {
				continue;
			}
			ItemStack item = craftingSlot.getItem();
			if (!item.isEmpty() && craftingSlot.mayPlace(item)) {
				clearedCraftingItems.add(craftingSlot.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, player));
			}
		}
		return clearedCraftingItems;
	}

	private static List<ItemStack> putItemsIntoCraftingGrid(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets) {
		final int slotStackLimit = getSlotStackLimit(recipeSlotToTakenStacks, requireCompleteSets);
		List<ItemStack> remainderItems = new ArrayList<>();
		recipeSlotToTakenStacks.forEach((slot, stack) -> {
			ItemStack remainder = slot.safeInsert(stack, slotStackLimit);
			if (!remainder.isEmpty()) {
				remainderItems.add(remainder);
			}
		});
		return remainderItems;
	}

	@Nullable
	private List<RequiredTransfer> calculateRequiredTransfers(List<TransferOperation> transferOperations, ServerPlayer player) {
		List<RequiredTransfer> requiredTransfers = new ArrayList<>(transferOperations.size());
		Map<Slot, ItemStack> targetSlotStacks = new HashMap<>();
		for (TransferOperation transferOperation : transferOperations) {
			Slot recipeSlot = transferOperation.craftingSlot(player.containerMenu);
			Slot inventorySlot = transferOperation.inventorySlot(player.containerMenu);
			if (!inventorySlot.allowModification(player)) {
				reject(player, "an inventory slot the player can't pick up from: " + inventorySlot.index);
				return null;
			}
			final ItemStack slotStack = inventorySlot.getItem();
			if (slotStack.isEmpty()) {
				reject(player, "an empty inventory slot as an ingredient source: " + inventorySlot.index);
				return null;
			}
			ItemStack stack = slotStack.copy();
			stack.setCount(transferOperation.count());
			if (!recipeSlot.mayPlace(stack)) {
				reject(player, "crafting slot %d does not accept ingredient %s".formatted(recipeSlot.index, stack));
				return null;
			}
			ItemStack targetSlotStack = targetSlotStacks.putIfAbsent(recipeSlot, stack);
			if (targetSlotStack != null && !ItemStack.isSameItemSameComponents(targetSlotStack, stack)) {
				reject(player, "different ingredients for the same crafting slot " + recipeSlot.index);
				return null;
			}
			requiredTransfers.add(new RequiredTransfer(recipeSlot, inventorySlot, stack));
		}
		return requiredTransfers;
	}

	private static Map<Slot, ItemStack> takeItemsFromInventory(
			Player player,
			List<RequiredTransfer> requiredTransfers,
			List<Slot> craftingSlots,
			List<Slot> inventorySlots,
			boolean transferAsCompleteSets,
			boolean maxTransfer) {
		if (!maxTransfer) {
			return removeOneSetOfItemsFromInventory(player, requiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets);
		}

		List<RequiredTransfer> remainingRequiredTransfers = new ArrayList<>(requiredTransfers);
		final Map<Slot, ItemStack> recipeSlotToResult = new HashMap<>(requiredTransfers.size());
		while (true) {
			removeFullRecipeSlots(remainingRequiredTransfers, recipeSlotToResult);
			if (remainingRequiredTransfers.isEmpty()) {
				break;
			}

			final Map<Slot, ItemStack> foundItemsInSet = removeOneSetOfItemsFromInventory(
					player,
					remainingRequiredTransfers,
					craftingSlots,
					inventorySlots,
					transferAsCompleteSets);

			if (foundItemsInSet.isEmpty()) {
				break;
			}

			// Merge the contents of the temporary map with the result map.
			merge(recipeSlotToResult, foundItemsInSet);
		}
		return recipeSlotToResult;
	}

	private static void removeFullRecipeSlots(List<RequiredTransfer> requiredTransfers, Map<Slot, ItemStack> recipeSlotToResult) {
		Set<Slot> fullRecipeSlots = new HashSet<>();
		for (RequiredTransfer requiredTransfer : requiredTransfers) {
			Slot recipeSlot = requiredTransfer.recipeSlot;
			ItemStack resultStack = recipeSlotToResult.get(recipeSlot);
			if (resultStack == null) {
				continue;
			}
			int requiredCount = getRequiredCount(requiredTransfers, recipeSlot);
			int maxStackSize = Integer.MAX_VALUE;
			if (recipeSlot.mayPlace(resultStack)) {
				maxStackSize = recipeSlot.getMaxStackSize(resultStack);
			}
			if (resultStack.getCount() + requiredCount > maxStackSize) {
				fullRecipeSlots.add(recipeSlot);
			}
		}
		requiredTransfers.removeIf(requiredTransfer -> fullRecipeSlots.contains(requiredTransfer.recipeSlot));
	}

	private static int getRequiredCount(List<RequiredTransfer> requiredTransfers, Slot recipeSlot) {
		return requiredTransfers.stream()
				.filter(requiredTransfer -> requiredTransfer.recipeSlot == recipeSlot)
				.mapToInt(requiredTransfer -> requiredTransfer.stack.getCount())
				.sum();
	}

	private static Map<Slot, ItemStack> removeOneSetOfItemsFromInventory(
			Player player,
			List<RequiredTransfer> requiredTransfers,
			List<Slot> craftingSlots,
			List<Slot> inventorySlots,
			boolean transferAsCompleteSets) {
		// Only needed to roll back slot changes when a complete set isn't found.
		Map<Slot, ItemStack> originalSlotContents = transferAsCompleteSets ? new HashMap<>() : null;

		// Items found in this set iteration, added to the result after each complete set iteration.
		final Map<Slot, ItemStack> foundItemsInSet = new HashMap<>(requiredTransfers.size());

		for (RequiredTransfer requiredTransfer : requiredTransfers) {
			final Slot recipeSlot = requiredTransfer.recipeSlot;
			final ItemStack requiredStack = requiredTransfer.stack;

			// Locate a slot that has what we need.
			final Slot sourceSlot = getSlotWithStack(player, requiredStack, craftingSlots, inventorySlots, requiredTransfer.hint).orElse(null);
			if (sourceSlot != null) {
				// Keep a copy of the slot's original contents in case we need to roll back.
				if (originalSlotContents != null && !originalSlotContents.containsKey(sourceSlot)) {
					originalSlotContents.put(sourceSlot, sourceSlot.getItem().copy());
				}

				// Reduce the size of the found slot.
				ItemStack removedItemStack = sourceSlot.safeTake(requiredStack.getCount(), Integer.MAX_VALUE, player);
				merge(foundItemsInSet, recipeSlot, removedItemStack);
			} else if (originalSlotContents != null) {
				// The full set requirement wasn't satisfied, roll back the slot changes of this set iteration.
				originalSlotContents.forEach(Slot::set);
				return Map.of();
			}
		}
		return foundItemsInSet;
	}

	private static void merge(Map<Slot, ItemStack> result, Map<Slot, ItemStack> addition) {
		addition.forEach((slot, itemStack) -> merge(result, slot, itemStack));
	}

	private static void merge(Map<Slot, ItemStack> result, Slot slot, ItemStack itemStack) {
		ItemStack resultItemStack = result.get(slot);
		if (resultItemStack == null) {
			result.put(slot, itemStack);
		} else {
			resultItemStack.grow(itemStack.getCount());
		}
	}

	private static Optional<Slot> getSlotWithStack(Player player, ItemStack stack, List<Slot> craftingSlots, List<Slot> inventorySlots, Slot hint) {
		if (isValidAndMatches(player, hint, stack)) {
			return Optional.of(hint);
		}
		return getSlotWithStack(player, craftingSlots, stack)
				.or(() -> getSlotWithStack(player, inventorySlots, stack));
	}

	private static Optional<Slot> getSlotWithStack(Player player, Collection<Slot> slots, ItemStack itemStack) {
		return slots.stream()
				.filter(slot -> isValidAndMatches(player, slot, itemStack))
				.findFirst();
	}

	private static boolean isValidAndMatches(Player player, Slot slot, ItemStack stack) {
		ItemStack containedStack = slot.getItem();
		return ItemStack.isSameItemSameComponents(stack, containedStack) &&
				containedStack.getCount() >= stack.getCount() &&
				slot.allowModification(player);
	}

	private static void stowItems(Player player, List<Slot> inventorySlots, List<ItemStack> itemStacks) {
		for (ItemStack itemStack : itemStacks) {
			ItemStack remainder = stowItem(player, inventorySlots, itemStack);
			if (!remainder.isEmpty() && !player.getInventory().add(remainder)) {
				player.drop(remainder, false, Prediction.SERVER_ONLY);
			}
		}
	}

	private static ItemStack stowItem(Player player, Collection<Slot> slots, ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack remainder = stack.copy();

		// Add to existing stacks first
		for (Slot slot : slots) {
			if (!slot.mayPickup(player)) {
				continue;
			}
			final ItemStack inventoryStack = slot.getItem();
			if (!inventoryStack.isEmpty() && inventoryStack.isStackable()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) {
					return ItemStack.EMPTY;
				}
			}
		}

		// Try adding to empty slots
		for (Slot slot : slots) {
			if (slot.getItem().isEmpty()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) {
					return ItemStack.EMPTY;
				}
			}
		}
		return remainder;
	}

	private void reject(ServerPlayer player, String reason) {
		plugin.debug(Level.INFO, "Rejected recipe transfer of %s: %s".formatted(player.getPlainTextName(), reason), null);
	}

	private record RequiredTransfer(Slot recipeSlot, Slot hint, ItemStack stack) {
	}
}
