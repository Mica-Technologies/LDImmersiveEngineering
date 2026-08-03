/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
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
	public final FluidTank tank = new FluidTank(WellheadFlow.capacityFor(PetroleumConfig.peakFlowRate))
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			//A bore brings up what is underneath it and nothing else. Without this a bucket of
			//water piped in would leave room measured in volume but fill() returning zero, so
			//every pass would deplete the reservoir and destroy the oil it drew.
			return fluid!=null&&fluid.getFluid()!=null
					&&!"ie_sour_gas".equals(fluid.getFluid().getName());
		}
	};
	/**
	 * Associated gas. Oil comes up with gas dissolved in it whether or not anything wants the
	 * gas, so this fills on its own and, with nothing plumbed to it, backs up and stops the
	 * well. That is the pressure a flare stack exists to relieve.
	 */
	public final FluidTank gasTank = new FluidTank(WellheadFlow.capacityFor(PetroleumConfig.peakFlowRate))
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return fluid!=null&&fluid.getFluid()!=null
					&&"ie_sour_gas".equals(fluid.getFluid().getName());
		}
	};

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
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), PetroleumTickHandler.PRODUCTION_INTERVAL);
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
		{
			//Still drain and reset, or a wellhead over a dry cell keeps whatever it holds forever
			//and stays flagged as pumped.
			pushOut();
			pumped = false;
			return;
		}
		ReservoirType type = reservoir.resolveType();
		if(type==null)
		{
			pushOut();
			pumped = false;
			return;
		}
		Fluid fluid = FluidRegistry.getFluid(type.getFluidName());
		if(fluid==null)
		{
			pushOut();
			pumped = false;
			return;
		}

		//Gas comes up with the oil whether or not there is anywhere to put it, so a full gas
		//tank throttles the whole well. Without this the excess would be quietly destroyed and
		//a flare stack or a scrubber would be decoration rather than a requirement.
		int room = WellheadFlow.drawRoom(tank.getCapacity()-tank.getFluidAmount(),
				gasTank.getCapacity()-gasTank.getFluidAmount(),
				PetroleumConfig.associatedGasRatio);
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
		int gas = WellheadFlow.associatedGas(crude, PetroleumConfig.associatedGasRatio);
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
		//Oil leaves sideways and down, gas leaves upward -- which is both how a real wellhead is
		//arranged and, more importantly, the only way a player can plumb the two separately. They
		//previously shared one set of faces in one order, so whichever pipe was found first took
		//both streams and ended up carrying a mixture no machine downstream would accept.
		pushTank(tank, false);
		pushTank(gasTank, true);
	}

	private void pushTank(FluidTank tank, boolean upwards)
	{
		if(tank.getFluidAmount() <= 0)
			return;
		if(facesDirty)
			rescanFaces();
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(tank.getFluidAmount() <= 0)
				break;
			if(!outputFaces[side.ordinal()]||(side==EnumFacing.UP)!=upwards)
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
			//Same split as the push side: gas off the crown, oil from everywhere else. A puller
			//that could reach both through one face would make the separation unenforceable.
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(
					facing==EnumFacing.UP?gasTank: tank);
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
		if(isBackedUp())
			return new String[]{TextFormatting.RED+"Backed up"+TextFormatting.RESET,
					"Nothing is taking what it produces"};
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
