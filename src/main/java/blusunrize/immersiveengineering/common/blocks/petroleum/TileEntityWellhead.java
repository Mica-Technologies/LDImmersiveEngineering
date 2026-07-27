/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.petroleum.*;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.petroleum.PetroleumSaveData;
import blusunrize.immersiveengineering.common.util.petroleum.PetroleumTickHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The valve stack capping a bore -- what a drilled well leaves behind.
 * <p>
 * On its own it is enough for a fresh, high-pressure deposit: the fluid reaches the surface
 * unaided and the wellhead simply collects it. Once pressure falls past
 * {@link PetroleumConfig#freeFlowThreshold} it produces nothing without a Pumpjack driving it,
 * which is the whole early progression -- find a field, tap it cheaply, then invest in
 * machinery when the easy oil runs out.
 * <p>
 * Not {@code ITickable}: {@code PetroleumTickHandler} drives every wellhead on a staggered
 * interval and the reservoir maths computes the interval's output in one go.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityWellhead extends TileEntityIEBase implements IPlayerInteraction,
		IBlockOverlayText, IComparatorOverride, INeighbourChangeTile
{
	/**
	 * Buffer between production passes, sized to two of them at the configured peak rate: big
	 * enough that a pass is never wasted for want of room, small enough that a well nobody has
	 * plumbed yet does not quietly bank a tank's worth of oil.
	 * <p>
	 * Read from the config rather than hard-coded, or raising {@code peakFlowRate} would leave
	 * the buffer behind and the well would throttle itself against a tank it had outgrown.
	 */
	public static int capacityFor(int peakFlowRate)
	{
		return Math.max(1000, 2*PetroleumTickHandler.PRODUCTION_INTERVAL*Math.max(1, peakFlowRate));
	}

	public final FluidTank tank = new FluidTank(capacityFor(PetroleumConfig.peakFlowRate));
	/**
	 * Associated gas. Oil comes up with gas dissolved in it whether or not anything wants the
	 * gas, so this fills on its own and, with nothing plumbed to it, backs up and stops the
	 * well. That is the pressure a flare stack exists to relieve.
	 */
	public final FluidTank gasTank = new FluidTank(capacityFor(PetroleumConfig.peakFlowRate));

	/**
	 * Set by an attached Pumpjack. Without one a depleted well produces nothing at all.
	 */
	private boolean pumped;

	/**
	 * Which neighbouring faces accepted fluid last time we looked. Recomputed on neighbour
	 * change rather than every pass.
	 */
	private final boolean[] outputFaces = new boolean[6];
	private boolean facesDirty = true;

	private int stagger = -1;

	//	=================================
	//		REGISTRATION
	//	=================================

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote)
			PetroleumTickHandler.register(this);
		facesDirty = true;
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		PetroleumTickHandler.unregister(this);
	}

	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		PetroleumTickHandler.unregister(this);
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		facesDirty = true;
	}

	/**
	 * Spreads production passes across ticks by position, so a field of wells never all fire
	 * on the same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = Math.floorMod(pos.getX()^pos.getZ()*31, PetroleumTickHandler.PRODUCTION_INTERVAL);
		return stagger;
	}

	//	=================================
	//		THE WELL
	//	=================================

	/**
	 * @return the deposit under this block, never null; an absent one reads as empty
	 */
	public Reservoir getReservoir()
	{
		return ReservoirHandler.getReservoir(world.getSeed(), world.provider.getDimension(),
				pos.getX() >> 4, pos.getZ() >> 4);
	}

	public boolean isPumped()
	{
		return pumped;
	}

	/**
	 * Called by an attached Pumpjack every production interval. The flag decays on its own if
	 * the pump stops asserting it, so a broken pump does not leave a well running forever.
	 */
	public void setPumped(boolean pumped)
	{
		this.pumped = pumped;
	}

	/**
	 * Collects this interval's production into the buffer and pushes what it can into whatever
	 * is plumbed to it.
	 *
	 * @param elapsedTicks ticks of production to account for
	 */
	public void produce(int elapsedTicks)
	{
		if(world==null||world.isRemote)
			return;
		Reservoir reservoir = getReservoir();
		if(reservoir.isEmpty())
			return;
		ReservoirType type = reservoir.resolveType();
		if(type==null)
			return;
		Fluid fluid = FluidRegistry.getFluid(type.getFluidName());
		if(fluid==null)
			return;

		int room = tank.getCapacity()-tank.getFluidAmount();
		if(room > 0)
		{
			int drawn = ReservoirModel.extract(reservoir, room, elapsedTicks, pumped,
					CityMode.petroleum(), false);
			if(drawn > 0)
			{
				tank.fill(new FluidStack(fluid, drawn), true);
				produceAssociatedGas(drawn);
				//Only a real draw dirties the save; a city-mode well changes nothing on disk.
				if(!CityMode.petroleum())
					PetroleumSaveData.setDirty();
				markDirty();
			}
		}
		pushOut();
		//The pump must re-assert every interval. Otherwise breaking a pumpjack would leave the
		//well behaving as though it were still attached.
		pumped = false;
	}

	/**
	 * Fills the gas tank alongside the oil.
	 * <p>
	 * Deliberately silent when the tank is full rather than voiding the excess: a well with
	 * nowhere to put its gas should back up and stop, which is what makes a flare stack or a
	 * scrubber worth building rather than optional scenery.
	 */
	private void produceAssociatedGas(int crude)
	{
		double ratio = PetroleumConfig.associatedGasRatio;
		if(ratio <= 0||crude <= 0)
			return;
		int gas = (int)Math.floor(crude*ratio);
		if(gas <= 0)
			return;
		Fluid sour = FluidRegistry.getFluid("ie_sour_gas");
		if(sour==null)
			return;
		gasTank.fill(new FluidStack(sour, gas), true);
	}

	/**
	 * @return true if either tank is full, which stops the well until something drains it
	 */
	public boolean isBackedUp()
	{
		return tank.getFluidAmount() >= tank.getCapacity()
				||(PetroleumConfig.associatedGasRatio > 0
				&&gasTank.getFluidAmount() >= gasTank.getCapacity());
	}

	private void pushOut()
	{
		pushTank(tank);
		pushTank(gasTank);
	}

	private void pushTank(FluidTank tank)
	{
		if(tank.getFluidAmount() <= 0)
			return;
		if(facesDirty)
			rescanFaces();
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(tank.getFluidAmount() <= 0)
				break;
			if(!outputFaces[side.ordinal()])
				continue;
			TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
			if(target==null||target instanceof TileEntityWellhead)
				continue;
			IFluidHandler handler = target.getCapability(
					CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side.getOpposite());
			if(handler==null)
				continue;
			FluidStack offered = tank.drain(tank.getFluidAmount(), false);
			if(offered==null)
				break;
			int accepted = handler.fill(offered, true);
			if(accepted > 0)
				tank.drain(accepted, true);
		}
	}

	private void rescanFaces()
	{
		for(EnumFacing side : EnumFacing.VALUES)
		{
			TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
			outputFaces[side.ordinal()] = target!=null&&!(target instanceof TileEntityWellhead)
					&&target.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,
					side.getOpposite());
		}
		facesDirty = false;
	}

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
	//		READOUT
	//	=================================

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		if(world.isRemote)
			return true;
		Reservoir reservoir = getReservoir();
		if(reservoir.isEmpty())
		{
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
					TextFormatting.RED+"Dry hole"+TextFormatting.RESET
							+" -- there is no reservoir under this block."));
			return true;
		}
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
				TextFormatting.GOLD+"Wellhead"+TextFormatting.RESET+": "
						+String.format(Locale.ENGLISH, "%.1f%%", 100*reservoir.getFraction())
						+" remaining"));
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
				ReservoirModel.isFreeFlowing(reservoir)
						?TextFormatting.GREEN+"Free-flowing"+TextFormatting.RESET
						: pumped?TextFormatting.YELLOW+"Pumped"+TextFormatting.RESET
						: TextFormatting.RED+"Needs a pumpjack"+TextFormatting.RESET));
		return true;
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(tank.getFluidAmount() <= 0)
			return null;
		return new String[]{tank.getFluid().getLocalizedName()+": "+tank.getFluidAmount()+" mB"};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	/**
	 * Reports how full the buffer is, so a comparator can gate a pump on the well backing up.
	 */
	@Override
	public int getComparatorInputOverride()
	{
		if(tank.getCapacity() <= 0)
			return 0;
		return Math.min(15, (int)Math.ceil(15.0*tank.getFluidAmount()/tank.getCapacity()));
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		nbt.setTag("gasTank", gasTank.writeToNBT(new NBTTagCompound()));
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		gasTank.readFromNBT(nbt.getCompoundTag("gasTank"));
	}
}
