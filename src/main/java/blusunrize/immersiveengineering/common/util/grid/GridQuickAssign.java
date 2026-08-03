/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.ItemNBTHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Copy-and-paste linking of grid devices with an Engineer's Voltmeter.
 * <p>
 * Linking one box at a time through a panel is fine; linking the thirty service units of a
 * street-lighting run is not, and neither is walking back to a console between each one.
 * Sneak-clicking a linked box with the voltmeter loads the tool with that segment, and
 * every sneak-click after that assigns the box you clicked to it. Sneak-clicking the air
 * empties the tool again.
 * <p>
 * The voltmeter rather than a new tool: it is already the grid's diagnostic instrument, and
 * a segment assignment is exactly the sort of thing an electrician's meter would hold
 * between two junction boxes. (The plan called for an Engineer's Screwdriver, which does not
 * exist in 1.12 -- it is a 1.16+ item.)
 * <p>
 * Every path re-checks {@link GridSegment#canEdit}, on both the segment being copied from
 * and the one being assigned to: a tool in hand must not be a way around a segment lock.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public final class GridQuickAssign
{
	private GridQuickAssign()
	{
	}

	/**
	 * NBT key holding the segment the tool is loaded with.
	 */
	private static final String KEY = "gridSegment";
	/**
	 * The segment's name at the moment it was picked up, purely so the tooltip can be drawn
	 * on the client -- which has no copy of the grid unless a console window is open. The id
	 * stays authoritative: a rename between pickup and paste makes this label stale, never
	 * the assignment wrong.
	 */
	private static final String KEY_NAME = "gridSegmentName";

	@Nullable
	public static UUID getHeldSegment(ItemStack stack)
	{
		if(!ItemNBTHelper.hasKey(stack, KEY))
			return null;
		return GridDevice.parseUUID(ItemNBTHelper.getString(stack, KEY));
	}

	public static boolean hasHeldSegment(ItemStack stack)
	{
		return getHeldSegment(stack)!=null;
	}

	/**
	 * @return the name the held segment had when it was picked up, for tooltips
	 */
	public static String describeHeldSegment(ItemStack stack)
	{
		String name = ItemNBTHelper.getString(stack, KEY_NAME);
		return name==null||name.isEmpty()?"?": name;
	}

	/**
	 * Empties the tool. Bound to sneak-right-click in the air, so getting out of paste mode
	 * never requires finding a particular block to click.
	 *
	 * @return true if it had been holding something
	 */
	public static boolean clear(ItemStack stack, EntityPlayer player)
	{
		if(!ItemNBTHelper.hasKey(stack, KEY))
			return false;
		ItemNBTHelper.remove(stack, KEY);
		ItemNBTHelper.remove(stack, KEY_NAME);
		say(player, TextFormatting.GRAY+"Voltmeter cleared.");
		return true;
	}

	/**
	 * Handles one sneak-click on a grid device.
	 *
	 * @return true if the click was consumed
	 */
	public static boolean onDeviceClicked(ItemStack stack, EntityPlayer player, DimensionBlockPos pos)
	{
		VirtualGrid grid = VirtualGrid.INSTANCE;
		GridDevice device = grid.getDevice(pos);
		if(device==null)
			return false;

		UUID held = getHeldSegment(stack);
		if(held==null)
			return copyFrom(stack, player, grid, device);
		return pasteTo(stack, player, grid, device, held);
	}

	private static boolean copyFrom(ItemStack stack, EntityPlayer player, VirtualGrid grid,
									GridDevice device)
	{
		GridSegment source = grid.getSegment(device.getSegment());
		if(source==null)
		{
			//An empty tool on an unlinked box: there is nothing to copy and nothing to paste,
			//so say what would make this work rather than failing silently.
			say(player, TextFormatting.YELLOW+"This box is unlinked. "
					+"Sneak-click a linked one first to pick up its segment.");
			return true;
		}
		if(!source.canEdit(player.getUniqueID()))
		{
			say(player, TextFormatting.RED+"That segment is locked.");
			return true;
		}
		ItemNBTHelper.setString(stack, KEY, source.getId().toString());
		ItemNBTHelper.setString(stack, KEY_NAME, source.getName());
		say(player, TextFormatting.GREEN+"Picked up segment "+TextFormatting.WHITE+source.getName()
				+TextFormatting.GRAY+" -- sneak-click boxes to assign them, or the air to clear.");
		return true;
	}

	private static boolean pasteTo(ItemStack stack, EntityPlayer player, VirtualGrid grid,
								   GridDevice device, UUID held)
	{
		GridSegment target = grid.getSegment(held);
		if(target==null)
		{
			//The segment was deleted while the tool still held it.
			ItemNBTHelper.remove(stack, KEY);
			ItemNBTHelper.remove(stack, KEY_NAME);
			say(player, TextFormatting.RED+"That segment no longer exists. Voltmeter cleared.");
			return true;
		}
		if(!target.canEdit(player.getUniqueID()))
		{
			say(player, TextFormatting.RED+"That segment is locked.");
			return true;
		}
		if(held.equals(device.getSegment()))
		{
			say(player, TextFormatting.GRAY+"Already on "+target.getName()+".");
			return true;
		}
		GridSegment current = grid.getSegment(device.getSegment());
		if(current!=null&&!current.canEdit(player.getUniqueID()))
		{
			say(player, TextFormatting.RED+"That box belongs to a locked segment.");
			return true;
		}

		if(!grid.assignDevice(device, held))
		{
			//The only way this fails is the cross-dimension rule.
			say(player, TextFormatting.RED+"That segment cannot take a device from this dimension.");
			return true;
		}
		GridSaveData.setDirty();
		GridChunkLoader.refresh();
		say(player, TextFormatting.GREEN+"Assigned to "+TextFormatting.WHITE+target.getName());
		return true;
	}

	private static void say(EntityPlayer player, String message)
	{
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(message));
	}
}
