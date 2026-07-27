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
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ITileDrop;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
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
		IBlockOverlayText, IComparatorOverride
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
