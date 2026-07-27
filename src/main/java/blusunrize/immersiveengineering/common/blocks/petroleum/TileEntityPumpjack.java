/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.petroleum.Reservoir;
import blusunrize.immersiveengineering.api.petroleum.ReservoirModel;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockPumpjack;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.petroleum.PetroleumTickHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Pumpjack: drives a Wellhead whose deposit no longer has the pressure to flow on its own.
 * <p>
 * The visual centrepiece of an oilfield -- walking beam, counterweight, nodding horsehead --
 * and mechanically the answer to a field falling past its free-flow threshold.
 * <p>
 * It carries no fluid of its own. All it does is assert {@code setPumped} on the well beneath it
 * once per production interval; the wellhead still owns the tank, the plumbing and the reservoir
 * maths. Keeping the two apart is what lets a well be tapped without a pump, pumped later, and
 * left running by a pump that is destroyed only until the flag decays.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPumpjack extends TileEntityMultiblockPart<TileEntityPumpjack>
		implements IIEInternalFluxHandler, IBlockOverlayText
{
	/**
	 * Draw while the beam is actually stroking, in IF/t. Enough that a field of pumpjacks is a
	 * real load on a grid -- the point of the machine is that late oil costs power -- but inside
	 * what a single MV connector will carry, so one pumpjack does not demand HV infrastructure.
	 */
	public static final int ENERGY_PER_TICK = 512;
	/**
	 * The machine bills once per production pass rather than every tick, so this is what a pass
	 * actually costs. The average draw is unchanged; only the number of bookkeeping operations
	 * differs, by a factor of twenty.
	 */
	private static final int ENERGY_PER_PASS = ENERGY_PER_TICK*PetroleumTickHandler.PRODUCTION_INTERVAL;
	/**
	 * Four passes of headroom. A lumpy draw needs a buffer deep enough that a wire delivering the
	 * average rate never leaves the machine a few IF short on the tick the bill falls due.
	 */
	private static final int ENERGY_CAPACITY = 4*ENERGY_PER_PASS;
	private static final int ENERGY_MAX_RECEIVE = 4096;
	/**
	 * City mode: what a pass costs when the grid is running on presence rather than accounting.
	 */
	private static final int CITY_SIP = 1;
	/**
	 * Ticks per complete up-and-down stroke. A real pumpjack strokes about ten times a minute,
	 * which in Minecraft reads as broken rather than slow; two seconds is unhurried but alive.
	 */
	private static final int STROKE_TICKS = 40;

	private final FluxStorage energyStorage = new FluxStorage(ENERGY_CAPACITY, ENERGY_MAX_RECEIVE, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Whether the beam is stroking. Synced, because it is the one thing the client cannot work
	 * out for itself -- everything else about the animation is derived from world time.
	 */
	public boolean active;

	private int stagger = -1;
	/**
	 * Resolved lazily and held until it goes invalid. Re-reading it every pass would be a chunk
	 * lookup per pumpjack per second for an answer that only changes when somebody breaks the
	 * well.
	 */
	private TileEntityWellhead wellhead;

	public TileEntityPumpjack()
	{
		super(PetroleumGeometry.PUMPJACK_SIZE);
	}

	//	=================================
	//		PUMPING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote||isDummy()||!formed)
			return;
		//The well it drives only produces once per production interval, so anything finer would
		//be work with nowhere to go. Staggered by position so a field of pumpjacks never all bill
		//and re-assert on the same tick.
		if((world.getTotalWorldTime()+getStagger())%PetroleumTickHandler.PRODUCTION_INTERVAL!=0)
			return;
		setActive(runPass());
	}

	/**
	 * @return whether the machine did any work, and therefore whether it should be animating
	 */
	private boolean runPass()
	{
		TileEntityWellhead well = getWellhead();
		if(well==null)
			return false;
		if(!shouldPump(well.getReservoir(), CityMode.petroleum()))
			return false;
		if(!drawPower())
			return false;
		//The flag decays at the end of every production pass by design, so this is not a switch
		//being flipped once -- it is the machine proving each interval that it is still there.
		well.setPumped(true);
		//The buffer moved, and a chunk that saves without it comes back holding energy it already
		//spent. Once a second, only while running.
		markDirty();
		return true;
	}

	/**
	 * Whether a deposit is worth spending power on.
	 * <p>
	 * A deposit still above its free-flow threshold reaches the surface unaided, so pumping one
	 * would buy nothing at all: {@code ReservoirModel.flowRate} already returns the peak rate for
	 * it whether or not a pump is attached. Idling until the field declines is both the honest
	 * behaviour and the one that does not quietly burn a kilo-IF per tick for nothing.
	 * <p>
	 * City mode is the exception. Reservoirs there never deplete, so "has this field fallen past
	 * free flow yet" can never become true -- gating on it would leave every pumpjack in a city
	 * world permanently decorative. Since city mode charges a token sip rather than a real cost,
	 * running unconditionally costs nothing and keeps the machine looking like the machine it is.
	 *
	 * @param cityMode see {@code CityMode.petroleum()}
	 */
	public static boolean shouldPump(@Nullable Reservoir reservoir, boolean cityMode)
	{
		if(reservoir==null||reservoir.isEmpty())
			return false;
		return cityMode||!ReservoirModel.isFreeFlowing(reservoir);
	}

	/**
	 * @return whether the pass could be paid for; nothing is consumed when it could not
	 */
	private boolean drawPower()
	{
		if(CityMode.petroleum())
		{
			//Presence, not consumption -- the same trade the virtual grid and the diesel
			//generator make. Charging a real 512 IF/t here would demand exactly the accounting
			//city mode exists to remove, while a sip still means an unpowered pumpjack stops.
			if(energyStorage.getEnergyStored() <= 0)
				return false;
			energyStorage.modifyEnergyStored(-CITY_SIP);
			return true;
		}
		if(energyStorage.getEnergyStored() < ENERGY_PER_PASS)
			return false;
		//Not extractEnergy: this storage is built with an extract limit of zero so that
		//nothing wired to the pumpjack can siphon its buffer back out, and that limit
		//applies to the machine itself too. Going through it meant the pumpjack could
		//never spend a single flux, so it never drove its well and the whole progression
		//past free-flow was dead.
		energyStorage.modifyEnergyStored(-ENERGY_PER_PASS);
		return true;
	}

	/**
	 * Finds the well this machine stands over.
	 * <p>
	 * The bay at the front of the skid is the obvious answer and the one the structure is shaped
	 * around, but a well sunk into a dug cellar sits a block lower and looks no less like the
	 * machine's own well, so both count. Two fixed positions, no search: a pumpjack can never
	 * drift onto a neighbour's well, and two wells can never fight over one pump.
	 */
	@Nullable
	private TileEntityWellhead getWellhead()
	{
		if(wellhead!=null&&!wellhead.isInvalid())
			return wellhead;
		BlockPos bay = getPos().offset(facing.getOpposite());
		TileEntity te = Utils.getExistingTileEntity(world, bay);
		if(!(te instanceof TileEntityWellhead))
			te = Utils.getExistingTileEntity(world, bay.down());
		wellhead = te instanceof TileEntityWellhead?(TileEntityWellhead)te: null;
		return wellhead;
	}

	/**
	 * Spreads passes across ticks by position, so a field of pumpjacks never all fire on the
	 * same one. Doubles as the animation's phase offset, so they nod out of step for free.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(),
					PetroleumTickHandler.PRODUCTION_INTERVAL);
		return stagger;
	}

	private void setActive(boolean now)
	{
		if(active==now)
			return;
		active = now;
		markDirty();
		markContainingBlockForUpdate(null);
	}

	//	=================================
	//		ANIMATION
	//	=================================

	/**
	 * @return whether this machine's beam is stroking; safe on either side
	 */
	public boolean isActive()
	{
		TileEntityPumpjack master = master();
		return master!=null&&master.active;
	}

	/**
	 * Where in its stroke the horsehead is, 0 at the bottom of the downstroke through 1 back to
	 * it again.
	 * <p>
	 * Derived from world time and never synced: a nod is not worth a packet, and this is a block
	 * explicitly expected to appear twenty times in one view. The renderer therefore needs no
	 * per-frame state and no tile entity updates beyond the one boolean that says whether the
	 * machine is running at all. An idle machine reports rest rather than a frozen mid-stroke,
	 * which is why stopping snaps rather than easing -- a spin-down would need state that would
	 * have to be either ticked or sent.
	 */
	public float getStrokePhase(float partialTicks)
	{
		if(!isActive())
			return 0;
		double ticks = world.getTotalWorldTime()+partialTicks;
		double offset = getStagger()/(double)PetroleumTickHandler.PRODUCTION_INTERVAL;
		return (float)((ticks/STROKE_TICKS+offset)%1.0);
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		if(isDummy())
			return super.getRenderBoundingBox();
		//The beam and horsehead are animated from the master and reach the far end of the
		//footprint, so the master must not be culled while its own block is off screen.
		int reach = PetroleumGeometry.PUMPJACK_DEPTH;
		return new AxisAlignedBB(getPos().add(-reach, 0, -reach),
				getPos().add(reach, PetroleumGeometry.PUMPJACK_HEIGHT, reach));
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed)
			return null;
		if(isActive())
			return new String[]{TextFormatting.GREEN+"Pumping"+TextFormatting.RESET};
		//Why it is idle -- no well, no power, or a field that still flows on its own -- is a
		//question about the well, and the wellhead's own readout already answers it precisely.
		return new String[]{TextFormatting.YELLOW+"Idle"+TextFormatting.RESET};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	//	=================================
	//		FLUX
	//	=================================

	/**
	 * @return true if this part is one of the motor blocks across the back of the machine, which
	 * are the only faces that take a connector
	 */
	private boolean isEnergyPos()
	{
		if(pos < 0)
			return false;
		int depth = pos%(PetroleumGeometry.PUMPJACK_DEPTH*PetroleumGeometry.PUMPJACK_WIDTH)
				/PetroleumGeometry.PUMPJACK_WIDTH;
		return depth==PetroleumGeometry.PUMPJACK_DEPTH-1;
	}

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		TileEntityPumpjack master = master();
		return master!=null?master.energyStorage: energyStorage;
	}

	@Nonnull
	@Override
	public SideConfig getEnergySideConfig(@Nullable EnumFacing facing)
	{
		return formed&&isEnergyPos()?SideConfig.INPUT: SideConfig.NONE;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		if(facing==null||!formed||!isEnergyPos())
			return null;
		return wrappers[facing.ordinal()];
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The structure is made of full cubes; the shape comes from
		//the model, not from per-block bounds.
		return null;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		//The machine never holds oil. It drives the well; the well owns the tank.
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return false;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the machine is built from four different
		//blocks, and handing back thirty-five oilfield frames would turn every disassembly into a
		//free trade of scaffolding and engineering blocks for frames.
		ItemStack original = pos < 0?null
				: MultiblockPumpjack.instance.getStructureManual()
				[pos/(PetroleumGeometry.PUMPJACK_DEPTH*PetroleumGeometry.PUMPJACK_WIDTH)]
				[pos%(PetroleumGeometry.PUMPJACK_DEPTH*PetroleumGeometry.PUMPJACK_WIDTH)/PetroleumGeometry.PUMPJACK_WIDTH]
				[pos%PetroleumGeometry.PUMPJACK_WIDTH];
		//An unformed block dropped in the world is always a frame; that is the only part of the
		//machine that is a placeable item in its own right.
		return original==null?new ItemStack(IEContent.blockPetroleumDevice, 1,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()): original.copy();
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		active = nbt.getBoolean("active");
		if(!descPacket)
			energyStorage.readFromNBT(nbt);
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setBoolean("active", active);
		if(!descPacket)
			energyStorage.writeToNBT(nbt);
	}
}
