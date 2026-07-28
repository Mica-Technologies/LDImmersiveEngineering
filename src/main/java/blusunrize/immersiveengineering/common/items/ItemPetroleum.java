/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasPump;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The petroleum expansion's loose items: the things that are not blocks and not fluids.
 * <p>
 * One item with subtypes rather than a class each, because none of them carries behaviour worth
 * its own file except the nozzle -- and giving the nozzle a class of its own while five inert
 * materials each got one too would be the wrong ratio of files to ideas.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class ItemPetroleum extends ItemIEBase
{
	public static final int NOZZLE = 0;
	public static final int DRILL_PIPE = 1;
	public static final int BLOWOUT_PREVENTER = 2;
	public static final int ABSORBENT_PAD = 3;
	public static final int PETCOKE = 4;
	public static final int SURVEY_KIT = 5;

	public ItemPetroleum()
	{
		super("petroleum", 64, PetroleumItemNames.SUB_NAMES);
	}

	@Override
	public int getItemStackLimit(ItemStack stack)
	{
		//The nozzle and the survey kit are tools; the rest are materials.
		int meta = stack.getMetadata();
		return meta==NOZZLE||meta==SURVEY_KIT?1: 64;
	}

	//	=================================
	//		THE NOZZLE
	//	=================================
	//
	// Right-click a Gas Station Pump to take the nozzle off its cradle; the pump's position is
	// written onto the item. Right-click anything that takes fluid, within range of that pump, and
	// the pump fills it. Sneak-right-click the air to rack it again.
	//
	// The design notes call for a rendered hose tethering the nozzle to the pump. That is not here:
	// the catenary renderer is solved in this codebase but wiring an item to it is a piece of
	// client work with no bearing on whether the loop is fun, and the loop is the same either way.
	// The range check does the job the hose length would have done, and the item says which pump it
	// is attached to, so nothing about the interaction is mysterious without it.

	private static final String TAG_POS = "pumpPos";
	private static final String TAG_DIM = "pumpDim";

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
									  EnumFacing side, float hitX, float hitY, float hitZ)
	{
		ItemStack stack = player.getHeldItem(hand);
		if(stack.getMetadata()!=NOZZLE)
			return EnumActionResult.PASS;

		TileEntity clicked = Utils.getExistingTileEntity(world, pos);
		if(clicked instanceof TileEntityGasPump)
		{
			if(!world.isRemote)
			{
				bind(stack, pos, world.provider.getDimension());
				say(player, TextFormatting.GREEN+"Nozzle taken from the pump at "
						+pos.getX()+", "+pos.getY()+", "+pos.getZ()+TextFormatting.RESET);
			}
			return EnumActionResult.SUCCESS;
		}

		TileEntityGasPump pump = resolvePump(stack, world, pos, player);
		if(pump==null)
			return EnumActionResult.PASS;
		if(!world.isRemote)
		{
			int moved = pump.fillTarget(player, clicked, null, side);
			if(moved > 0)
				say(player, TextFormatting.GREEN+"Dispensed "+moved+" mB"+TextFormatting.RESET);
			else
				say(player, TextFormatting.YELLOW+"That will not take any fuel."+TextFormatting.RESET);
		}
		return EnumActionResult.SUCCESS;
	}

	@Override
	public boolean itemInteractionForEntity(ItemStack stack, EntityPlayer player, net.minecraft.entity.EntityLivingBase target, EnumHand hand)
	{
		//Entities are handled at all so that a vehicle, if one is ever added, needs no change here.
		//That is the same reason the pump's fill path takes an entity.
		if(stack.getMetadata()!=NOZZLE||player.world.isRemote)
			return false;
		TileEntityGasPump pump = resolvePump(stack, player.world, target.getPosition(), player);
		if(pump==null)
			return false;
		int moved = pump.fillTarget(player, null, target, null);
		if(moved > 0)
			say(player, TextFormatting.GREEN+"Dispensed "+moved+" mB"+TextFormatting.RESET);
		return moved > 0;
	}

	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand)
	{
		ItemStack stack = player.getHeldItem(hand);
		if(stack.getMetadata()==NOZZLE&&player.isSneaking())
		{
			if(!world.isRemote&&isBound(stack))
			{
				unbind(stack);
				say(player, TextFormatting.GRAY+"Nozzle racked."+TextFormatting.RESET);
			}
			return new ActionResult<>(EnumActionResult.SUCCESS, stack);
		}
		return new ActionResult<>(EnumActionResult.PASS, stack);
	}

	/**
	 * @return the pump this nozzle is attached to, if it still exists and the target is inside its
	 * reach; otherwise null, having told the player which of those it was
	 */
	@Nullable
	private TileEntityGasPump resolvePump(ItemStack stack, World world, BlockPos target, EntityPlayer player)
	{
		if(!isBound(stack))
		{
			if(!world.isRemote)
				say(player, TextFormatting.YELLOW
						+"Take the nozzle off a Gas Station Pump first."+TextFormatting.RESET);
			return null;
		}
		NBTTagCompound tag = stack.getTagCompound();
		if(tag==null||world.provider.getDimension()!=tag.getInteger(TAG_DIM))
			return null;
		BlockPos pumpPos = BlockPos.fromLong(tag.getLong(TAG_POS));
		TileEntity te = Utils.getExistingTileEntity(world, pumpPos);
		if(!(te instanceof TileEntityGasPump))
		{
			if(!world.isRemote)
			{
				unbind(stack);
				say(player, TextFormatting.RED+"That pump is gone."+TextFormatting.RESET);
			}
			return null;
		}
		//Squared distance, so a diagonal reach is the same as a straight one -- a hose does not
		//care which way it is pointing.
		if(pumpPos.distanceSq(target) > (double)TileEntityGasPump.NOZZLE_RANGE*TileEntityGasPump.NOZZLE_RANGE)
		{
			if(!world.isRemote)
				say(player, TextFormatting.YELLOW+"The hose will not reach that far."+TextFormatting.RESET);
			return null;
		}
		return (TileEntityGasPump)te;
	}

	public static boolean isBound(ItemStack stack)
	{
		NBTTagCompound tag = stack.getTagCompound();
		return tag!=null&&tag.hasKey(TAG_POS);
	}

	private static void bind(ItemStack stack, BlockPos pos, int dimension)
	{
		NBTTagCompound tag = stack.getTagCompound();
		if(tag==null)
			stack.setTagCompound(tag = new NBTTagCompound());
		tag.setLong(TAG_POS, pos.toLong());
		tag.setInteger(TAG_DIM, dimension);
	}

	private static void unbind(ItemStack stack)
	{
		NBTTagCompound tag = stack.getTagCompound();
		if(tag!=null)
		{
			tag.removeTag(TAG_POS);
			tag.removeTag(TAG_DIM);
		}
	}

	private static void say(EntityPlayer player, String text)
	{
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(text));
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
							   net.minecraft.client.util.ITooltipFlag flag)
	{
		if(stack.getMetadata()==NOZZLE&&isBound(stack))
		{
			NBTTagCompound tag = stack.getTagCompound();
			BlockPos pos = BlockPos.fromLong(tag.getLong(TAG_POS));
			tooltip.add(TextFormatting.GRAY+"Attached to the pump at "
					+pos.getX()+", "+pos.getY()+", "+pos.getZ()+TextFormatting.RESET);
		}
	}

	/**
	 * Petcoke is a solid fuel: the cracking unit's leftover, and a genuinely good one.
	 */
	@Override
	public int getItemBurnTime(ItemStack stack)
	{
		//Half again as long as coal. Petcoke has a higher calorific value than coal in reality, and
		//in play it is the thing that stops the cracker's byproduct being a nuisance.
		return stack.getMetadata()==PETCOKE?2400: 0;
	}

	/**
	 * @return whether this entity is something a nozzle could plausibly fill
	 */
	public static boolean canFill(@Nullable Entity entity)
	{
		return entity!=null&&entity.hasCapability(
				net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
	}
}
