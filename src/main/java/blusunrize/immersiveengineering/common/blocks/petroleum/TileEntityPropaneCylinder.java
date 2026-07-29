/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ITileDrop;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The bottle off the back of a barbecue: a small propane cylinder you fill somewhere and carry
 * to wherever it is needed.
 * <p>
 * The smallest rung of the tank ladder and the only portable one. Everything else in this
 * feature is infrastructure -- wells, towers, buried tanks, pipe runs -- and all of it is
 * useless to a player who just wants to run one thing in one place. A bottle filled at a tank
 * and set down next to a burner is a complete loop with no plumbing at all, and it is the
 * honest answer to "I have propane, now what" at domestic scale.
 * <p>
 * It keeps its contents when broken, so swapping an empty for a full one is the gesture rather
 * than piping anything. That is the same trick the wooden barrel uses.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPropaneCylinder extends TileEntityIEBase implements ITileDrop,
		IBlockOverlayText, IComparatorOverride, IPlayerInteraction
{
	/**
	 * Four buckets. Enough to be worth carrying, small enough that it is a bottle rather than a
	 * way to move a refinery's output around in a backpack.
	 */
	public static final int CAPACITY = 4000;

	public final FluidTank tank = new FluidTank(CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			//A propane bottle takes propane. Letting it hold anything would quietly make it the
			//cheapest portable tank in the mod for every fluid at once.
			return fluid!=null&&fluid.getFluid()!=null
					&&"propane".equals(fluid.getFluid().getName());
		}
	};

	//	=================================
	//		CAPABILITY
	//	=================================

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing)
	{
		return capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
				||super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing)
	{
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tank);
		return super.getCapability(capability, facing);
	}

	//	=================================
	//		FILLING IT BY HAND
	//	=================================

	/**
	 * A bottle is the one tank in this feature you are meant to fill by hand, so this is the one
	 * place the gesture absolutely had to work -- and it was the one place it silently did not.
	 * <p>
	 * With no handler the click reached the held item instead, and a vanilla bucket answers a
	 * right-click on a block by placing its contents against the clicked face. Every attempt to fill
	 * a cylinder therefore put a source block of propane on the ground next to it, and propane is
	 * flammable and flows.
	 * <p>
	 * Through {@link Utils#interactWithTank} rather than Forge's helper directly, because this
	 * cylinder refuses everything that is not propane: a bucket of diesel moves no fluid, and a
	 * transfer that moved no fluid must still swallow the click or the bucket spills on the refusal.
	 */
	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		if(!Utils.interactWithTank(player, hand, tank))
			return false;
		markDirty();
		markContainingBlockForUpdate(null);
		return true;
	}

	//	=================================
	//		CARRYING IT
	//	=================================

	@Override
	public ItemStack getTileDrop(@Nullable EntityPlayer player, IBlockState state)
	{
		ItemStack stack = new ItemStack(state.getBlock(), 1, state.getBlock().getMetaFromState(state));
		NBTTagCompound tag = new NBTTagCompound();
		writeTank(tag);
		if(!tag.isEmpty())
			stack.setTagCompound(tag);
		return stack;
	}

	@Override
	public void readOnPlacement(@Nullable EntityLivingBase placer, ItemStack stack)
	{
		if(stack.hasTagCompound())
			readTank(stack.getTagCompound());
	}

	private void writeTank(NBTTagCompound nbt)
	{
		if(tank.getFluidAmount() > 0)
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
	}

	private void readTank(NBTTagCompound nbt)
	{
		tank.readFromNBT(nbt.getCompoundTag("tank"));
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(tank.getFluidAmount() <= 0)
			return new String[]{TextFormatting.GRAY+"Empty"+TextFormatting.RESET};
		return new String[]{tank.getFluid().getLocalizedName(),
				tank.getFluidAmount()+" / "+CAPACITY+" mB"};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		return Math.min(15, (int)Math.ceil(15.0*tank.getFluidAmount()/CAPACITY));
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		writeTank(nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		readTank(nbt);
	}
}
