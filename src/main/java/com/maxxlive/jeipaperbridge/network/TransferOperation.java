package com.maxxlive.jeipaperbridge.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Port of JEI's {@code mezz.jei.common.transfer.TransferOperation}:
 * transferring an ItemStack from inventorySlot to craftingSlot.
 */
public record TransferOperation(int inventorySlotId, int craftingSlotId, int count) {
	/** Highest stack size an item can have, larger counts can never be satisfied. */
	private static final int MAX_COUNT = 99;

	public static final StreamCodec<ByteBuf, TransferOperation> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT,
			TransferOperation::inventorySlotId,
			ByteBufCodecs.VAR_INT,
			TransferOperation::craftingSlotId,
			(inventorySlotId, craftingSlotId) -> new TransferOperation(inventorySlotId, craftingSlotId, 1));

	public static final StreamCodec<ByteBuf, TransferOperation> COUNTED_STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT,
			TransferOperation::inventorySlotId,
			ByteBufCodecs.VAR_INT,
			TransferOperation::craftingSlotId,
			ByteBufCodecs.VAR_INT,
			TransferOperation::count,
			TransferOperation::new);

	public TransferOperation {
		if (count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("Transfer operation count out of range: " + count);
		}
	}

	public Slot inventorySlot(AbstractContainerMenu container) {
		return container.getSlot(inventorySlotId);
	}

	public Slot craftingSlot(AbstractContainerMenu container) {
		return container.getSlot(craftingSlotId);
	}
}
