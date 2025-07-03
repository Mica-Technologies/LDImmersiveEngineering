/*
 * BluSunrize
 * Copyright (c) 2025
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.multiblocks.logic;

import blusunrize.immersiveengineering.api.IEApi;
import blusunrize.immersiveengineering.api.multiblocks.blocks.env.IInitialMultiblockContext;
import blusunrize.immersiveengineering.api.multiblocks.blocks.env.IMultiblockContext;
import blusunrize.immersiveengineering.api.multiblocks.blocks.logic.IMultiblockLogic;
import blusunrize.immersiveengineering.api.multiblocks.blocks.logic.IMultiblockState;
import blusunrize.immersiveengineering.api.multiblocks.blocks.util.CapabilityPosition;
import blusunrize.immersiveengineering.api.multiblocks.blocks.util.RelativeBlockFace;
import blusunrize.immersiveengineering.api.multiblocks.blocks.util.ShapeType;
import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.ShelfLogic.State;
import blusunrize.immersiveengineering.common.blocks.multiblocks.logic.interfaces.MBOverlayText;
import blusunrize.immersiveengineering.common.blocks.multiblocks.shapes.ShelfShapes;
import blusunrize.immersiveengineering.common.blocks.wooden.WoodenCrateBlockEntity;
import blusunrize.immersiveengineering.common.gui.ShelfMenu;
import blusunrize.immersiveengineering.common.register.IEBlocks.WoodenDevices;
import blusunrize.immersiveengineering.common.register.IEMenuTypes;
import com.google.common.base.Suppliers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component.Serializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities.ItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ShelfLogic implements IMultiblockLogic<State>, MBOverlayText<State>
{
	public static int NUM_CRATES = 24;

	public static final Supplier<Map<Item, CrateVariant>> CRATE_VARIANTS = Suppliers.memoize(() -> {
		Map<Item, CrateVariant> map = new HashMap<>();
		map.put(WoodenDevices.CRATE.asItem(), new CrateVariant("block/multiblocks/shelf_crate"));
		map.put(WoodenDevices.REINFORCED_CRATE.asItem(), new CrateVariant("block/multiblocks/shelf_crate_reinforced"));
		map.put(Blocks.SHULKER_BOX.asItem(), new CrateVariant("block/shulker_box", 0x503750));
		map.put(Blocks.WHITE_SHULKER_BOX.asItem(), new CrateVariant("block/white_shulker_box", DyeColor.WHITE.getTextColor()));
		map.put(Blocks.ORANGE_SHULKER_BOX.asItem(), new CrateVariant("block/orange_shulker_box", DyeColor.ORANGE.getTextColor()));
		map.put(Blocks.MAGENTA_SHULKER_BOX.asItem(), new CrateVariant("block/magenta_shulker_box", DyeColor.MAGENTA.getTextColor()));
		map.put(Blocks.LIGHT_BLUE_SHULKER_BOX.asItem(), new CrateVariant("block/light_blue_shulker_box", DyeColor.LIGHT_BLUE.getTextColor()));
		map.put(Blocks.YELLOW_SHULKER_BOX.asItem(), new CrateVariant("block/yellow_shulker_box", DyeColor.YELLOW.getTextColor()));
		map.put(Blocks.LIME_SHULKER_BOX.asItem(), new CrateVariant("block/lime_shulker_box", DyeColor.LIME.getTextColor()));
		map.put(Blocks.PINK_SHULKER_BOX.asItem(), new CrateVariant("block/pink_shulker_box", DyeColor.PINK.getTextColor()));
		map.put(Blocks.GRAY_SHULKER_BOX.asItem(), new CrateVariant("block/gray_shulker_box", DyeColor.GRAY.getTextColor()));
		map.put(Blocks.LIGHT_GRAY_SHULKER_BOX.asItem(), new CrateVariant("block/light_gray_shulker_box", DyeColor.LIGHT_GRAY.getTextColor()));
		map.put(Blocks.CYAN_SHULKER_BOX.asItem(), new CrateVariant("block/cyan_shulker_box", DyeColor.CYAN.getTextColor()));
		map.put(Blocks.PURPLE_SHULKER_BOX.asItem(), new CrateVariant("block/purple_shulker_box", DyeColor.PURPLE.getTextColor()));
		map.put(Blocks.BLUE_SHULKER_BOX.asItem(), new CrateVariant("block/blue_shulker_box", DyeColor.BLUE.getTextColor()));
		map.put(Blocks.BROWN_SHULKER_BOX.asItem(), new CrateVariant("block/brown_shulker_box", DyeColor.BROWN.getTextColor()));
		map.put(Blocks.GREEN_SHULKER_BOX.asItem(), new CrateVariant("block/green_shulker_box", DyeColor.GREEN.getTextColor()));
		map.put(Blocks.RED_SHULKER_BOX.asItem(), new CrateVariant("block/red_shulker_box", DyeColor.RED.getTextColor()));
		map.put(Blocks.BLACK_SHULKER_BOX.asItem(), new CrateVariant("block/black_shulker_box", DyeColor.BLACK.getTextColor()));
		return map;
	});

	@Override
	public State createInitialState(IInitialMultiblockContext<State> capabilitySource)
	{
		return new State(capabilitySource);
	}

	@Override
	public void registerCapabilities(CapabilityRegistrar<State> register)
	{
		register.register(ItemHandler.BLOCK, State::getItemHandler);
	}

	@Override
	public void dropExtraItems(State state, Consumer<ItemStack> drop)
	{
		state.crates.forEach(stack -> {
			if(!stack.isEmpty())
				drop.accept(stack.copy());
		});
	}

	@Override
	public ItemInteractionResult click(
			IMultiblockContext<State> ctx, BlockPos posInMultiblock,
			Player player, InteractionHand hand, BlockHitResult absoluteHit, boolean isClient
	)
	{
		if(posInMultiblock.getY() < 1)
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if(!isClient)
		{
			final State state = ctx.getState();
			int crateIndex = state.getCrateIndex(posInMultiblock);
			final ItemStack heldItem = player.getItemInHand(hand);
			boolean isHoldingCrate = CRATE_VARIANTS.get().containsKey(heldItem.getItem());
			final ItemStack storedCrate = state.crates.get(crateIndex);

			if(player.isCrouching()&&heldItem.isEmpty()&&!storedCrate.isEmpty())
			{
				// Get crate from shelf
				player.setItemInHand(hand, storedCrate);
				state.crates.set(crateIndex, ItemStack.EMPTY);
				ctx.markMasterDirty();
				ctx.requestMasterBESync();
			}
			else if(isHoldingCrate&&storedCrate.isEmpty())
			{
				// Place crate on shelf
				state.crates.set(crateIndex, heldItem);
				player.setItemInHand(hand, ItemStack.EMPTY);
				ctx.markMasterDirty();
				ctx.requestMasterBESync();
			}
			else
				player.openMenu(IEMenuTypes.SHELF.provide(ctx, posInMultiblock));
		}
		return ItemInteractionResult.sidedSuccess(isClient);
	}

	@Override
	public @Nullable List<Component> getOverlayText(State state, BlockPos posInMultiblock, BlockHitResult absoluteHit, Player player, boolean hammer)
	{
		if(posInMultiblock.getY() < 1||state==null)
			return List.of();
		int crateIndex = (posInMultiblock.getY()-1)*8+posInMultiblock.getX()*2+posInMultiblock.getZ();
		Component name = state.names[crateIndex];
		if(name!=null&&!Component.empty().equals(name))
			return List.of(name);
		return List.of();
	}

	@Override
	public Function<BlockPos, VoxelShape> shapeGetter(ShapeType forType)
	{
		return ShelfShapes.SHAPE_GETTER;
	}

	public static class State implements IMultiblockState
	{
		public final NonNullList<ItemStack> crates = NonNullList.withSize(NUM_CRATES, ItemStack.EMPTY);

		public ResourceLocation[] renderCrates = new ResourceLocation[NUM_CRATES];
		public Component[] names = new Component[NUM_CRATES];
		private final Runnable doUpdate;

		public State(IInitialMultiblockContext<State> ctx)
		{
			doUpdate = ctx.getBlockUpdateRunnable();
		}

		public ShelfItemHandler getItemHandler(CapabilityPosition position)
		{
			var dir = position.side();
			if(position.posInMultiblock().getY()==0||dir==null||dir==RelativeBlockFace.DOWN)
				return null;
			if(dir==RelativeBlockFace.UP&&position.posInMultiblock().getY()!=3)
				return null;
			if(dir==RelativeBlockFace.LEFT&&position.posInMultiblock().getX()!=3)
				return null;
			if(dir==RelativeBlockFace.RIGHT&&position.posInMultiblock().getX()!=0)
				return null;
			if(dir==RelativeBlockFace.FRONT&&position.posInMultiblock().getZ()!=0)
				return null;
			if(dir==RelativeBlockFace.BACK&&position.posInMultiblock().getZ()!=1)
				return null;

			int length = switch(dir)
			{
				case UP -> 3;
				case FRONT, BACK -> 2;
				case LEFT, RIGHT -> 4;
				default -> 0;
			};
			return new ShelfItemHandler(() -> IntStream.range(0, length)
					.mapToObj(value -> dir.offsetRelative(position.posInMultiblock(), -value))
					.map(cratePos -> crates.get(getCrateIndex(cratePos)))
					.filter(stack -> !stack.isEmpty()).toList()
			);
		}

		public ShelfItemHandler getMenuItemHandler(BlockPos posInMultiblock)
		{
			final int idxFront = (posInMultiblock.getY()-1)*8+posInMultiblock.getZ();
			final int idxBack = idxFront+(posInMultiblock.getZ() > 0?-1: +1);
			// access left to right, no matter from which side the shelf is accessed
			if(posInMultiblock.getZ() > 0)
				return new ShelfItemHandler(() -> IntStream.of(
						idxFront, idxFront+2, idxFront+4, idxFront+6,
						idxBack, idxBack+2, idxBack+4, idxBack+6
				).mapToObj(crates::get).toList());
			else // access right to left
				return new ShelfItemHandler(() -> IntStream.of(
						idxFront+6, idxFront+4, idxFront+2, idxFront,
						idxBack+6, idxBack+4, idxBack+2, idxBack
				).mapToObj(crates::get).toList());
		}

		public List<ItemStack> getCratesForMenu(BlockPos posInMultiblock, boolean backside)
		{
			int startIdx = (posInMultiblock.getY()-1)*8+posInMultiblock.getZ();
			if(backside)
				startIdx += posInMultiblock.getZ() > 0?-1: +1;
			// access left to right, no matter from which side the shelf is accessed
			if(posInMultiblock.getZ() > 0)
				return Stream.of(
						crates.get(startIdx), crates.get(startIdx+2), crates.get(startIdx+4), crates.get(startIdx+6)
				).toList();
			else
				return Stream.of(
						crates.get(startIdx+6), crates.get(startIdx+4), crates.get(startIdx+2), crates.get(startIdx)
				).toList();
		}

		public int getCrateIndex(BlockPos posInMultiblock)
		{
			return (posInMultiblock.getY()-1)*8+posInMultiblock.getX()*2+posInMultiblock.getZ();
		}

		@Override
		public void writeSaveNBT(CompoundTag nbt, Provider provider)
		{
			ContainerHelper.saveAllItems(nbt, crates, provider);
		}

		@Override
		public void readSaveNBT(CompoundTag nbt, Provider provider)
		{
			ContainerHelper.loadAllItems(nbt, crates, provider);
		}

		@Override
		public void writeSyncNBT(CompoundTag nbt, Provider provider)
		{
			ListTag crates = new ListTag();
			int cratesAsInt = 0;
			for(ItemStack stack : this.crates)
			{
				CompoundTag tag = new CompoundTag();
				Component name = stack.isEmpty()?Component.empty(): stack.getHoverName();
				tag.putString("name", Serializer.toJson(name, provider));
				tag.putString("id", stack.getItemHolder().getKey().location().toString());
				crates.add(tag);
				cratesAsInt = (cratesAsInt<<1)|(stack.isEmpty()?0: 1);
			}
			nbt.put("crates", crates);
		}

		@Override
		public void readSyncNBT(CompoundTag nbt, Provider provider)
		{
			ListTag names = nbt.getList("crates", 10);
			for(int i = 0; i < NUM_CRATES; i++)
			{
				CompoundTag tag = names.getCompound(i);
				Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("id")));
				CrateVariant variant = CRATE_VARIANTS.get().get(item);
				this.renderCrates[i] = variant!=null?variant.crateTexture(): null;
				this.names[i] = Serializer.fromJson(tag.getString("name"), provider);
			}
			this.doUpdate.run();
		}
	}

	public record CrateVariant(ResourceLocation crateTexture, int screenVOffset, int color)
	{
		public CrateVariant(String crateTexture)
		{
			this(IEApi.ieLoc(crateTexture), 0, -1);
		}

		public CrateVariant(String crateTexture, int color)
		{
			this(ResourceLocation.withDefaultNamespace(crateTexture), ShelfMenu.CRATE_SEGMENT, color);
		}
	}

	public record ShelfItemHandler(Supplier<List<ItemStack>> crates) implements IItemHandlerModifiable
	{
		@Override
		public int getSlots()
		{
			return crates.get().size()*WoodenCrateBlockEntity.CONTAINER_SIZE;
		}

		@Override
		public @NotNull ItemStack getStackInSlot(int slot)
		{
			if(slot < 0||slot >= getSlots())
				return ItemStack.EMPTY;
			int crateIndex = slot/WoodenCrateBlockEntity.CONTAINER_SIZE;
			int innerSlot = slot%WoodenCrateBlockEntity.CONTAINER_SIZE;
			final ItemContainerContents contents = crates.get().get(crateIndex).get(DataComponents.CONTAINER);
			if(contents==null||innerSlot >= contents.getSlots())
				return ItemStack.EMPTY;
			return contents.getStackInSlot(innerSlot);
		}

		@Override
		public void setStackInSlot(int slot, ItemStack itemStack)
		{
			if(slot < 0||slot >= getSlots()||!isItemValid(slot, itemStack))
				return;
			int crateIndex = slot/WoodenCrateBlockEntity.CONTAINER_SIZE;
			int innerSlot = slot%WoodenCrateBlockEntity.CONTAINER_SIZE;

			final ItemContainerContents contents = crates.get().get(crateIndex).get(DataComponents.CONTAINER);
			NonNullList<ItemStack> edited = NonNullList.withSize(WoodenCrateBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);
			if(contents!=null)
				contents.copyInto(edited);
			edited.set(innerSlot, itemStack.copy());
			crates.get().get(crateIndex).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(edited));
		}

		@Override
		public @NotNull ItemStack insertItem(int slot, ItemStack stackToInsert, boolean simulate)
		{
			if(slot < 0||slot >= getSlots()||!isItemValid(slot, stackToInsert))
				return stackToInsert;

			final ItemContainerContents[] crateContents = crates.get().stream().map(s -> s.get(DataComponents.CONTAINER)).toArray(ItemContainerContents[]::new);
			final int totalSlots = getSlots();
			int lastMatchingSlot = slot;
			for(int iSlot = slot; iSlot < totalSlots; iSlot++)
			{
				int crateIndex = iSlot/WoodenCrateBlockEntity.CONTAINER_SIZE;
				int innerSlot = iSlot%WoodenCrateBlockEntity.CONTAINER_SIZE;
				ItemStack inSlot = (crateContents[crateIndex]==null||innerSlot >= crateContents[crateIndex].getSlots())?ItemStack.EMPTY:
						crateContents[crateIndex].getStackInSlot(innerSlot);
				if(!inSlot.isEmpty()&&ItemStack.isSameItemSameComponents(stackToInsert, inSlot))
				{
					lastMatchingSlot = iSlot;
					if(stackToInsert.isStackable()&&inSlot.getCount() < inSlot.getMaxStackSize())
						return insertItemInternal(iSlot, stackToInsert, simulate);
				}
			}
			for(int iSlot = lastMatchingSlot; iSlot < totalSlots; iSlot++)
			{
				ItemStack ret = insertItemInternal(iSlot, stackToInsert, simulate);
				if(ret.isEmpty()||ret.getCount() < stackToInsert.getCount())
					return ret;
			}
			return insertItemInternal(slot, stackToInsert, simulate);
		}

		private ItemStack insertItemInternal(int slot, ItemStack stackToInsert, boolean simulate)
		{
			int crateIndex = slot/WoodenCrateBlockEntity.CONTAINER_SIZE;
			int innerSlot = slot%WoodenCrateBlockEntity.CONTAINER_SIZE;

			final ItemContainerContents contents = crates.get().get(crateIndex).get(DataComponents.CONTAINER);
			NonNullList<ItemStack> edited = NonNullList.withSize(WoodenCrateBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);
			if(contents!=null)
				contents.copyInto(edited);
			final ItemStack stackInSlot = edited.get(innerSlot);
			if(stackInSlot.isEmpty())
			{
				if(!simulate)
				{
					edited.set(innerSlot, stackToInsert.copy());
					crates.get().get(crateIndex).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(edited));
				}
				return ItemStack.EMPTY;
			}
			else if(ItemStack.isSameItemSameComponents(stackInSlot, stackToInsert))
			{
				int maxStack = Math.min(stackInSlot.getMaxStackSize(), 64);
				int accepted = Math.min(maxStack-stackInSlot.getCount(), stackToInsert.getCount());
				if(accepted > 0)
				{
					if(!simulate)
					{
						stackInSlot.grow(accepted);
						crates.get().get(crateIndex).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(edited));
					}
					ItemStack remainder = stackToInsert.copy();
					remainder.shrink(accepted);
					if(remainder.isEmpty())
						remainder = ItemStack.EMPTY;
					return remainder;
				}
			}
			return stackToInsert;
		}

		@Override
		public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate)
		{
			if(slot < 0||slot >= getSlots())
				return ItemStack.EMPTY;
			int crateIndex = slot/WoodenCrateBlockEntity.CONTAINER_SIZE;
			int innerSlot = slot%WoodenCrateBlockEntity.CONTAINER_SIZE;

			final ItemContainerContents contents = crates.get().get(crateIndex).get(DataComponents.CONTAINER);
			NonNullList<ItemStack> edited = NonNullList.withSize(WoodenCrateBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);
			if(contents!=null)
				contents.copyInto(edited);
			final ItemStack stackInSlot = edited.get(innerSlot);
			if(stackInSlot.isEmpty())
				return ItemStack.EMPTY;

			amount = Math.min(amount, stackInSlot.getCount());
			ItemStack result = stackInSlot.copyWithCount(amount);
			if(!simulate)
			{
				stackInSlot.shrink(amount);
				crates.get().get(crateIndex).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(edited));
			}
			return result;
		}

		@Override
		public int getSlotLimit(int i)
		{
			return 64;
		}

		@Override
		public boolean isItemValid(int i, ItemStack stack)
		{
			return IEApi.isAllowedInCrate(stack);
		}
	}
}
