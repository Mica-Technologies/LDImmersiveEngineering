/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.link;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.CommonProxy;
import blusunrize.immersiveengineering.common.items.ItemNetworkLinker;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.ItemNBTHelper;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetChunkLoader;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetSaveData;
import blusunrize.immersiveengineering.common.util.grid.GridChunkLoader;
import blusunrize.immersiveengineering.common.util.grid.GridSaveData;
import blusunrize.immersiveengineering.common.util.link.LinkerLogic.Outcome;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The state a linking tool carries, and what it does when it is clicked on something.
 * <p>
 * The console is where infrastructure is <em>managed</em>; this is how it is <em>wired</em>. Picking
 * a segment from a list once and then walking a street tapping thirty service units is the gesture
 * that was missing, and it is the one Flux Networks got right: the tool remembers the network, and
 * every click after that is an assignment with no window in the way.
 * <p>
 * <strong>Both linkers live in one class on purpose.</strong> The power grid and the fluid network
 * are deliberate mirrors of each other -- see {@code api/fluid/network} -- and the standing rule in
 * this repository is that a change to one is checked against the other. Two files three hundred
 * lines apart is exactly how that rule stops being followed; two methods you can read side by side
 * is how it keeps being followed. The decision itself is not duplicated at all: both call
 * {@link LinkerLogic}.
 * <p>
 * Locks are re-checked on both ends of every move, on every path. A tool in hand must not be a way
 * around a lock.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public final class NetworkLinker
{
	private NetworkLinker()
	{
	}

	/**
	 * The selected network's id. The authority: a rename or a recolour between picking and pasting
	 * makes the cached label below stale, never the assignment wrong.
	 */
	private static final String KEY_ID = "linkTarget";
	/**
	 * The selection's name and colour as they were when it was picked, purely so the tooltip and the
	 * item's own readout can be drawn on the client, which has no copy of either network unless a
	 * window happens to be open.
	 */
	private static final String KEY_NAME = "linkTargetName";
	private static final String KEY_COLOUR = "linkTargetColour";
	/**
	 * The device whose click opened the chooser, so that picking from the list also links the box
	 * that was clicked. Without it the first box of a run would be the one box the tool did not
	 * assign, which is a small thing that feels broken every single time.
	 */
	private static final String KEY_PENDING = "linkPendingPos";

	//	=================================
	//		THE TOOL'S OWN STATE
	//	=================================

	@Nullable
	public static UUID getSelection(ItemStack stack)
	{
		if(!ItemNBTHelper.hasKey(stack, KEY_ID))
			return null;
		return GridDevice.parseUUID(ItemNBTHelper.getString(stack, KEY_ID));
	}

	public static boolean hasSelection(ItemStack stack)
	{
		return getSelection(stack)!=null;
	}

	/**
	 * @return the name the selection had when it was picked up, for tooltips
	 */
	public static String describeSelection(ItemStack stack)
	{
		String name = ItemNBTHelper.getString(stack, KEY_NAME);
		return name==null||name.isEmpty()?"?": name;
	}

	/**
	 * @return the colour the selection had when it was picked up, for the tooltip swatch
	 */
	public static int selectionColour(ItemStack stack)
	{
		return ItemNBTHelper.hasKey(stack, KEY_COLOUR)?ItemNBTHelper.getInt(stack, KEY_COLOUR): 0xFFFFFF;
	}

	private static void store(ItemStack stack, UUID id, String name, int colour)
	{
		ItemNBTHelper.setString(stack, KEY_ID, id.toString());
		ItemNBTHelper.setString(stack, KEY_NAME, name);
		ItemNBTHelper.setInt(stack, KEY_COLOUR, colour);
	}

	private static void forget(ItemStack stack)
	{
		ItemNBTHelper.remove(stack, KEY_ID);
		ItemNBTHelper.remove(stack, KEY_NAME);
		ItemNBTHelper.remove(stack, KEY_COLOUR);
	}

	/**
	 * Empties the tool. Bound to sneak-rightclick in the air, so dropping a selection never
	 * requires finding a particular block to click.
	 *
	 * @return true if it had been holding something
	 */
	public static boolean clear(ItemStack stack, EntityPlayer player)
	{
		ItemNBTHelper.remove(stack, KEY_PENDING);
		if(!ItemNBTHelper.hasKey(stack, KEY_ID))
			return false;
		forget(stack);
		say(player, TextFormatting.GRAY+"Linker cleared.");
		return true;
	}

	//	=================================
	//		THE PENDING TARGET
	//	=================================

	/**
	 * Remembers the box that opened the chooser, so the pick that follows also links it.
	 */
	public static void rememberTarget(ItemStack stack, World world, BlockPos pos)
	{
		ItemNBTHelper.setIntArray(stack, KEY_PENDING, new int[]{
				world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ()});
	}

	public static void forgetTarget(ItemStack stack)
	{
		ItemNBTHelper.remove(stack, KEY_PENDING);
	}

	@Nullable
	private static DimensionBlockPos takeTarget(ItemStack stack)
	{
		if(!ItemNBTHelper.hasKey(stack, KEY_PENDING))
			return null;
		int[] stored = ItemNBTHelper.getIntArray(stack, KEY_PENDING);
		ItemNBTHelper.remove(stack, KEY_PENDING);
		if(stored==null||stored.length < 4)
			return null;
		return new DimensionBlockPos(stored[1], stored[2], stored[3], stored[0]);
	}

	//	=================================
	//		PICKING FROM THE CHOOSER
	//	=================================

	/**
	 * Applies a pick made in the chooser window: stores it on the tool, and links whichever box
	 * opened the window if one did.
	 * <p>
	 * The permission checks are the same ones a click makes, run again here rather than trusted from
	 * the client -- this arrives as a packet, and the window it came from is not the trust boundary.
	 */
	public static void select(ItemStack stack, EntityPlayer player, @Nullable UUID chosen)
	{
		boolean fluid = ItemNetworkLinker.isFluid(stack);
		if(chosen==null)
		{
			clear(stack, player);
			return;
		}
		String name;
		int colour;
		if(fluid)
		{
			FluidMain main = VirtualFluidNet.INSTANCE.getMain(chosen);
			if(main==null)
				return;
			if(!main.canEdit(player.getUniqueID()))
			{
				say(player, TextFormatting.RED+"That main is locked.");
				return;
			}
			name = main.getName();
			colour = main.getColor();
		}
		else
		{
			GridSegment segment = VirtualGrid.INSTANCE.getSegment(chosen);
			if(segment==null)
				return;
			if(!segment.canEdit(player.getUniqueID()))
			{
				say(player, TextFormatting.RED+"That segment is locked.");
				return;
			}
			name = segment.getName();
			colour = segment.getColor();
		}
		store(stack, chosen, name, colour);
		say(player, TextFormatting.GREEN+"Linker loaded with "+TextFormatting.WHITE+name
				+TextFormatting.GRAY+" -- rightclick boxes to link them.");

		DimensionBlockPos pending = takeTarget(stack);
		if(pending!=null)
			apply(stack, player, pending, fluid);
	}

	//	=================================
	//		CLICKING A DEVICE
	//	=================================

	/**
	 * Handles a rightclick on a box with a linker. Server side only -- the caller is the box's own
	 * tile entity, which already knows the click landed on a device.
	 * <p>
	 * A loaded tool links the box. An empty one opens the chooser and remembers the box, so picking
	 * from the list links it as well: the first box of a run must not be the one box the tool
	 * skipped.
	 */
	public static void onDeviceClicked(ItemStack stack, EntityPlayer player, EnumHand hand,
									   World world, BlockPos pos)
	{
		DimensionBlockPos target = new DimensionBlockPos(pos, world);
		boolean fluid = ItemNetworkLinker.isFluid(stack);
		if(!hasSelection(stack))
		{
			openChooser(stack, player, hand, world, pos);
			return;
		}
		apply(stack, player, target, fluid);
	}

	/**
	 * Opens the chooser, optionally remembering a box for the pick to link.
	 */
	public static void openChooser(ItemStack stack, EntityPlayer player, EnumHand hand,
								   @Nullable World world, @Nullable BlockPos pos)
	{
		if(world!=null&&pos!=null)
			rememberTarget(stack, world, pos);
		else
			forgetTarget(stack);
		CommonProxy.openGuiForItem(player, hand==EnumHand.MAIN_HAND
				?EntityEquipmentSlot.MAINHAND: EntityEquipmentSlot.OFFHAND);
	}

	private static boolean apply(ItemStack stack, EntityPlayer player, DimensionBlockPos pos, boolean fluid)
	{
		return fluid?applyFluid(stack, player, pos): applyGrid(stack, player, pos);
	}

	private static boolean applyGrid(ItemStack stack, EntityPlayer player, DimensionBlockPos pos)
	{
		VirtualGrid grid = VirtualGrid.INSTANCE;
		GridDevice device = grid.getDevice(pos);
		UUID held = getSelection(stack);
		GridSegment selection = held==null?null: grid.getSegment(held);
		GridSegment current = device==null?null: grid.getSegment(device.getSegment());
		UUID owner = player.getUniqueID();

		Outcome outcome = LinkerLogic.decide(device!=null, held!=null, selection!=null,
				selection!=null&&selection.canEdit(owner),
				current==null||current.canEdit(owner),
				device!=null&&held!=null&&held.equals(device.getSegment()));

		if(LinkerLogic.clearsTool(outcome))
			forget(stack);
		switch(outcome)
		{
			case ASSIGN:
				if(!grid.assignDevice(device, held))
				{
					//The only way this fails is the cross-dimension rule.
					say(player, TextFormatting.RED+"That segment cannot take a device from this dimension.");
					return true;
				}
				GridSaveData.setDirty();
				GridChunkLoader.refresh();
				say(player, TextFormatting.GREEN+"Linked to "+TextFormatting.WHITE+selection.getName());
				return true;
			default:
				return report(player, outcome, "segment");
		}
	}

	private static boolean applyFluid(ItemStack stack, EntityPlayer player, DimensionBlockPos pos)
	{
		VirtualFluidNet net = VirtualFluidNet.INSTANCE;
		FluidDevice device = net.getDevice(pos);
		UUID held = getSelection(stack);
		FluidMain selection = held==null?null: net.getMain(held);
		FluidMain current = device==null?null: net.getMain(device.getMain());
		UUID owner = player.getUniqueID();

		Outcome outcome = LinkerLogic.decide(device!=null, held!=null, selection!=null,
				selection!=null&&selection.canEdit(owner),
				current==null||current.canEdit(owner),
				device!=null&&held!=null&&held.equals(device.getMain()));

		if(LinkerLogic.clearsTool(outcome))
			forget(stack);
		switch(outcome)
		{
			case ASSIGN:
				if(!net.assignDevice(device, held))
				{
					say(player, TextFormatting.RED+"That main cannot take a fitting from this dimension.");
					return true;
				}
				FluidNetSaveData.setDirty();
				FluidNetChunkLoader.refresh();
				say(player, TextFormatting.GREEN+"Linked to "+TextFormatting.WHITE+selection.getName());
				return true;
			default:
				return report(player, outcome, "main");
		}
	}

	/**
	 * Says what happened, in the network's own vocabulary, and reports whether the click was used.
	 * <p>
	 * {@link Outcome#OPEN_CHOOSER} says nothing here: the item opens the window on that path, and a
	 * chat line explaining that a window is about to appear is noise.
	 */
	private static boolean report(EntityPlayer player, Outcome outcome, String kind)
	{
		switch(outcome)
		{
			case NOT_A_DEVICE:
				//A real box whose record is missing: the chunk is mid-load, or the network was
				//cleared under it. Said out loud, because clicking a box and getting silence is
				//indistinguishable from the tool being broken.
				say(player, TextFormatting.YELLOW+"This box is not on the network yet.");
				break;
			case SELECTION_GONE:
				say(player, TextFormatting.RED+"That "+kind+" no longer exists. Linker cleared.");
				break;
			case SELECTION_LOCKED:
			case CURRENT_LOCKED:
				say(player, TextFormatting.RED+"That "+kind+" is locked.");
				break;
			case ALREADY_LINKED:
				say(player, TextFormatting.GRAY+"Already linked.");
				break;
			default:
				break;
		}
		return LinkerLogic.consumesClick(outcome);
	}

	private static void say(EntityPlayer player, String message)
	{
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(message));
	}
}
