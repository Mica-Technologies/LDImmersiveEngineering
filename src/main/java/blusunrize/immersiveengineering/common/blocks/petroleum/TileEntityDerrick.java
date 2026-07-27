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
import blusunrize.immersiveengineering.api.petroleum.ReservoirHandler;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Drilling Derrick: sinks a bore and leaves a Wellhead behind.
 * <p>
 * Temporary by design. It drills, hands over a Wellhead, then comes apart so it can travel to
 * the next site -- the rig is equipment, not architecture.
 * <p>
 * The one thing this must never do is stand there looking busy over nothing. A deposit is
 * invisible: there is no ore to spot, no texture in the stone, nothing to dig up. So a rig
 * sited over an empty cell says so in chat the moment it is assembled, says so again on the
 * block overlay for as long as it stands, and never draws a single FE. "Nothing is happening"
 * has to be a statement the game makes, not a conclusion the player is left to reach.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityDerrick extends TileEntityMultiblockPart<TileEntityDerrick>
		implements IBlockOverlayText, IComparatorOverride, IPlayerInteraction, IIEInternalFluxHandler
{
	/**
	 * Ticks of work a bore takes. A minute: long enough that a rig is something you watch
	 * finish, short enough that siting one is not a commitment.
	 */
	public static final int DRILL_TIME = 1200;
	/**
	 * Draw while drilling, in FE/t. 307,200 FE for a complete well -- meaningful for a base
	 * running off a couple of thermoelectrics, trivial once a diesel generator is up. Finding
	 * the deposit is the expensive part of a well; turning the bit is not.
	 */
	public static final int ENERGY_PER_TICK = 256;
	/**
	 * Ticks between work passes. Drilling is a countdown, and revisiting a countdown twenty
	 * times a second buys nothing; a rig is forty-six tile entities and a field is several rigs.
	 */
	public static final int DRILL_INTERVAL = 10;
	/**
	 * Buffer, in FE. Five seconds of drilling at {@link #ENERGY_PER_TICK}, so a rig on a thin
	 * wire keeps turning through the gaps instead of stuttering between stalled and running.
	 */
	public static final int ENERGY_CAPACITY = 25600;
	/**
	 * City mode's token draw per pass: enough to prove the rig is wired to something live.
	 */
	public static final int CITY_SIP = 10;

	/**
	 * Where the bore comes up: the middle of the drill floor, and the block that becomes the
	 * Wellhead.
	 */
	public static final int BORE_INDEX = PetroleumGeometry.structureIndex(
			PetroleumGeometry.DERRICK_SIZE, 0,
			PetroleumGeometry.DERRICK_DEPTH/2, PetroleumGeometry.DERRICK_WIDTH/2);

	//The extract limit is zero on purpose: the rig spends its own charge through
	//modifyEnergyStored, and nothing wired to it should ever be able to siphon that back out.
	private final FluxStorage energyStorage = new FluxStorage(ENERGY_CAPACITY, 4*ENERGY_PER_TICK, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Ticks of bore sunk so far. Synced, because the overlay is drawn client-side.
	 */
	public int drillProgress;
	/**
	 * Whether the last pass found nothing in the buffer. Synced for the same reason.
	 */
	public boolean stalled;
	/**
	 * Whether this site rolled no deposit at all. Synced for the same reason.
	 */
	public boolean dryHole;

	private boolean siteChecked;
	private int stagger = -1;

	public TileEntityDerrick()
	{
		super(PetroleumGeometry.DERRICK_SIZE);
	}

	//	=================================
	//		DRILLING
	//	=================================

	@Override
	public void update()
	{
		//Only the master ever has work to do. Dropping the other forty-five out of the ticking
		//list is cheaper than forty-five early returns every tick, for the rig's whole lifetime.
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote||isDummy()||!formed)
			return;
		if((world.getTotalWorldTime()+getStagger())%DRILL_INTERVAL!=0)
			return;
		if(!resolveSite()||drillProgress >= DRILL_TIME)
			return;

		boolean wasStalled = stalled;
		stalled = !drawPower();
		if(stalled)
		{
			if(!wasStalled)
			{
				markDirty();
				markContainingBlockForUpdate(null);
			}
			return;
		}
		drillProgress += DRILL_INTERVAL;
		if(drillProgress >= DRILL_TIME)
		{
			completeWell();
			return;
		}
		markDirty();
		markContainingBlockForUpdate(null);
	}

	/**
	 * Spreads work passes across ticks by position, so a field of rigs never all fire on the
	 * same one.
	 */
	private int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), DRILL_INTERVAL);
		return stagger;
	}

	/**
	 * The deposit under the bore specifically, rather than under whichever corner happens to be
	 * the master: a rig straddling a cell boundary has to agree with the Wellhead it will leave.
	 *
	 * @return the deposit, never null; an absent one reads as empty
	 */
	public Reservoir getReservoir()
	{
		BlockPos bore = getBlockPosForPos(BORE_INDEX);
		return ReservoirHandler.getReservoir(world.getSeed(), world.provider.getDimension(),
				bore.getX() >> 4, bore.getZ() >> 4);
	}

	/**
	 * Resolves -- once -- whether there is anything down there, and caches the answer. Rolling a
	 * cell is deterministic, so the result cannot change under a standing rig.
	 *
	 * @return whether this site is worth drilling
	 */
	private boolean resolveSite()
	{
		if(!siteChecked)
		{
			siteChecked = true;
			boolean dry = getReservoir().isEmpty();
			if(dry!=dryHole)
			{
				dryHole = dry;
				markDirty();
				markContainingBlockForUpdate(null);
			}
		}
		return !dryHole;
	}

	/**
	 * @return whether this pass could be paid for
	 */
	private boolean drawPower()
	{
		if(CityMode.petroleum())
		{
			//City mode's trade everywhere else is to keep the look and the gesture and drop the
			//simulation cost, so the rig still has to be wired up and still takes its minute --
			//it just stops metering. Making drilling instant would delete the gesture; making it
			//free would delete the reason to run a wire out to the site at all. Neither is a
			//saving worth having: the drilling itself costs one arithmetic pass every ten ticks.
			if(energyStorage.getEnergyStored() <= 0)
				return false;
			energyStorage.modifyEnergyStored(-CITY_SIP);
			return true;
		}
		int cost = ENERGY_PER_TICK*DRILL_INTERVAL;
		if(energyStorage.getEnergyStored() < cost)
			return false;
		energyStorage.modifyEnergyStored(-cost);
		return true;
	}

	/**
	 * Hands over the well and packs the rig up.
	 * <p>
	 * Done by hand rather than through {@code disassemble()}, which is written for the block-break
	 * path: it restores every part in place and drops only the block being broken, which would
	 * leave a finished derrick standing as a tower of inert frame for the player to mine down.
	 * The rig is equipment. It comes back as a stack at the wellhead's feet, ready to be carried
	 * to the next site, and nothing is consumed but the power and the minute.
	 */
	private void completeWell()
	{
		BlockPos bore = getBlockPosForPos(BORE_INDEX);
		List<BlockPos> parts = collectParts();
		//Clearing `formed` first is load-bearing: removing a part's block runs
		//BlockIEMultiblock's break handling, and a part that still believes it is formed would
		//start the framework's own disassembly in the middle of ours.
		for(BlockPos part : parts)
		{
			TileEntity te = world.getTileEntity(part);
			if(te instanceof TileEntityDerrick)
				((TileEntityDerrick)te).formed = false;
		}
		for(BlockPos part : parts)
			world.setBlockToAir(part);

		world.setBlockState(bore, IEContent.blockPetroleumDevice
				.getStateFromMeta(BlockTypes_PetroleumDevice.WELLHEAD.getMeta()));
		if(!parts.isEmpty())
			world.spawnEntity(new EntityItem(world, bore.getX()+.5, bore.getY()+1.5, bore.getZ()+.5,
					new ItemStack(IEContent.blockPetroleumDevice, parts.size(),
							BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta())));
	}

	/**
	 * @return every block belonging to this derrick. Membership is decided by asking the part
	 * itself, not by re-deriving the shape: a cell only counts if the part standing in it agrees
	 * it belongs to this master, so an adjacent rig can never be torn down by mistake.
	 */
	private List<BlockPos> collectParts()
	{
		List<BlockPos> parts = new ArrayList<>();
		int cells = structureDimensions[0]*structureDimensions[1]*structureDimensions[2];
		for(int i = 0; i < cells; i++)
		{
			BlockPos target = getBlockPosForPos(i);
			TileEntity te = world.getTileEntity(target);
			if(!(te instanceof TileEntityDerrick))
				continue;
			TileEntityDerrick part = (TileEntityDerrick)te;
			if(part.formed&&part.pos==i&&part.master()==this)
				parts.add(target);
		}
		return parts;
	}

	//	=================================
	//		READOUT
	//	=================================

	/**
	 * @return progress as a whole percentage, rounded <em>up</em> so a rig that has started
	 * always reads as having started. One pass out of a hundred and twenty is under a percent,
	 * and "0%" is indistinguishable from "broken".
	 */
	public static int progressPercent(int ticksDone, int ticksTotal)
	{
		if(ticksTotal <= 0)
			return 100;
		int percent = (int)Math.ceil(100.0*ticksDone/ticksTotal);
		return percent < 0?0: percent > 100?100: percent;
	}

	/**
	 * @return seconds of drilling left, rounded up
	 */
	public int secondsRemaining()
	{
		return Math.max(0, (DRILL_TIME-drillProgress+19)/20);
	}

	/**
	 * Tells a player what they have just sited the rig over. Called once, at formation, because
	 * that is the moment they can still cheaply move it.
	 */
	public void reportSiteTo(EntityPlayer player)
	{
		if(world.isRemote)
			return;
		if(resolveSite())
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
					TextFormatting.GOLD+"Drilling"+TextFormatting.RESET+": about "
							+(DRILL_TIME/20)+"s at "+ENERGY_PER_TICK+" FE/t"));
		else
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
					TextFormatting.RED+"Dry hole"+TextFormatting.RESET
							+" -- there is no reservoir under this site. This derrick will not"
							+" drill; move it and try again."));
	}

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		if(!formed||Utils.isHammer(heldItem))
			return false;
		if(world.isRemote)
			return true;
		TileEntityDerrick master = master();
		if(master==null)
			return false;
		if(master.dryHole)
		{
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
					TextFormatting.RED+"Dry hole"+TextFormatting.RESET
							+" -- there is no reservoir under this site."));
			return true;
		}
		ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
				TextFormatting.GOLD+"Drilling"+TextFormatting.RESET+": "
						+progressPercent(master.drillProgress, DRILL_TIME)+"%, about "
						+master.secondsRemaining()+"s remaining"));
		if(master.stalled)
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(
					TextFormatting.RED+"No power"+TextFormatting.RESET+" -- the rig draws "
							+ENERGY_PER_TICK+" FE/t"));
		return true;
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		TileEntityDerrick master = master();
		if(!formed||master==null)
			return null;
		if(master.dryHole)
			return new String[]{TextFormatting.RED+"Dry hole"+TextFormatting.RESET
					+" -- nothing under this site"};
		String progress = "Drilling: "+progressPercent(master.drillProgress, DRILL_TIME)+"%";
		if(master.stalled)
			return new String[]{progress,
					TextFormatting.RED+"No power"+TextFormatting.RESET+" ("+ENERGY_PER_TICK+" FE/t)"};
		return new String[]{progress};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	/**
	 * Reports drilling progress, so a comparator can fire a hopper or a light when the well is
	 * about to hand itself over.
	 */
	@Override
	public int getComparatorInputOverride()
	{
		TileEntityDerrick master = master();
		if(master==null||master.dryHole)
			return 0;
		return Math.min(15, (int)Math.floor(15.0*master.drillProgress/DRILL_TIME));
	}

	//	=================================
	//		FLUX
	//	=================================

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		TileEntityDerrick master = master();
		return master!=null?master.energyStorage: energyStorage;
	}

	/**
	 * @return whether this part can be wired to. A rig gets dropped on rough ground and torn down
	 * again a minute later, so the whole drill floor accepts power from any face rather than one
	 * exact connector block nobody would find twice.
	 */
	private boolean isEnergyPos()
	{
		return PetroleumGeometry.heightOf(PetroleumGeometry.DERRICK_SIZE, pos)==0;
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

	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		//A tower drawn as one model from the master reaches nine blocks up and a block out in
		//every direction; without this it vanishes the moment its base leaves the screen.
		return isDummy()?super.getRenderBoundingBox()
				: new AxisAlignedBB(
				getPos().add(-PetroleumGeometry.DERRICK_DEPTH, 0, -PetroleumGeometry.DERRICK_DEPTH),
				getPos().add(PetroleumGeometry.DERRICK_DEPTH+1, PetroleumGeometry.DERRICK_HEIGHT,
						PetroleumGeometry.DERRICK_DEPTH+1));
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
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
		return new ItemStack(IEContent.blockPetroleumDevice, 1,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		drillProgress = nbt.getInteger("drillProgress");
		stalled = nbt.getBoolean("stalled");
		dryHole = nbt.getBoolean("dryHole");
		if(!descPacket)
			energyStorage.readFromNBT(nbt);
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("drillProgress", drillProgress);
		nbt.setBoolean("stalled", stalled);
		nbt.setBoolean("dryHole", dryHole);
		if(!descPacket)
			energyStorage.writeToNBT(nbt);
	}
}
