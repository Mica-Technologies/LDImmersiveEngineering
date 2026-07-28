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
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Heat Recovery Steam Generator: the second half of a combined-cycle plant.
 * <p>
 * <strong>This machine burns nothing.</strong> Every other generator in the expansion answers the
 * question "what do I feed it"; this one answers "where do I put it". A Gas Turbine turns roughly
 * a third of its gas into flux and sends the rest up the stack as several hundred degrees of
 * exhaust that, until this block exists, simply leaves the world. Dock an HRSG onto that stack,
 * pipe its steam to a Steam Turbine Hall, and the same millibucket of gas that was making 12,288
 * IF/t is making something over 30,000 -- with no second fuel line, no second tank and nothing
 * extra to keep supplied. That is the single largest efficiency step in the feature and it is
 * bought entirely with <em>layout</em>.
 * <p>
 * <strong>Heat cannot be piped, and that is the whole design.</strong> There is no heat fluid, no
 * heat conduit and no configurable range. The machine has one intake face and it must be
 * physically against a turbine's exhaust end -- see {@link #resolveHost()} -- so the reward for
 * understanding the mechanic is that you site your plant properly rather than that you craft
 * another block. It is the same lesson the Distillation Tower's draw-port heights teach, stated
 * in one axis instead of fourteen.
 * <p>
 * <strong>What it costs.</strong> Forty flux a tick for the feedwater pumps, and nothing else.
 * That is not a balance lever -- 40 IF/t against 18,000 IF/t of recovered steam is a rounding
 * error and is meant to be. It exists so that an HRSG is a machine you have to <em>connect</em>:
 * a plant that loses its station supply stops recovering, which is exactly what a real one does,
 * and it means the block cannot quietly keep working after a player has pulled it out of a base.
 * <p>
 * The recovery arithmetic lives in {@link TileEntityGasTurbine#exhaustSteamFor(int)} rather than
 * here, because it is a property of the exhaust and not of the thing catching it: a turbine knows
 * how hard it is working, and this machine only has to ask.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityHRSG extends TileEntityMultiblockPart<TileEntityHRSG>
		implements IIEInternalFluxHandler, IBlockOverlayText, IComparatorOverride, INeighbourChangeTile
{
	//	=================================
	//		RATES
	//	=================================

	/**
	 * Ticks between passes.
	 * <p>
	 * Half the turbine's, and deliberately so. The host's output moves over a two hundred tick
	 * spool; sampling it every ten ticks tracks that curve closely enough that the steam a player
	 * sees leaving matches the plume they see over the stack, without the machine doing per-tick
	 * arithmetic for a number that changes by two parts in a thousand per tick.
	 */
	public static final int WORK_INTERVAL = 10;

	/**
	 * Flux per tick for the feedwater pumps. See the class note: a token draw, on purpose.
	 */
	public static final int ENERGY_PER_TICK = 40;
	/**
	 * The machine bills once per pass rather than every tick, so this is what a pass actually
	 * costs. The average draw is unchanged; only the number of bookkeeping operations differs.
	 */
	public static final int ENERGY_PER_PASS = ENERGY_PER_TICK*WORK_INTERVAL;
	/**
	 * Four passes of headroom, as the pumpjack keeps: a lumpy draw needs a buffer deep enough that
	 * a wire delivering the average rate never leaves the machine a few flux short on the tick the
	 * bill falls due.
	 */
	public static final int ENERGY_CAPACITY = 4*ENERGY_PER_PASS;
	/**
	 * Comfortably more than the machine can spend, so a single low-voltage connector refills the
	 * buffer between passes with room to spare. There is nothing to be gained from making a 40
	 * IF/t machine fussy about its supply.
	 */
	public static final int ENERGY_MAX_RECEIVE = 512;
	/**
	 * City mode: what a pass costs when the grid runs on presence rather than accounting. A sip,
	 * so an unwired HRSG still stops -- the gesture survives, the metering does not.
	 */
	private static final int CITY_SIP = 1;

	/**
	 * The steam registry name. Held as a string rather than a {@link Fluid} for the same reason
	 * the turbine's fuel table is: the guards below can then be read, and checked, without a fluid
	 * registry.
	 */
	public static final String STEAM = "ie_steam";

	/**
	 * The drum: thirteen seconds of recovery at full rate.
	 * <p>
	 * Exactly the depth of the turbine's own gas tank, which is not a coincidence -- the two
	 * machines are one plant and holding the same volume is the honest way to say so. In seconds
	 * it is far shallower than any other tank in the expansion, because 180 mB/t is far more than
	 * anything else in the expansion moves: this is a buffer against a Steam Turbine Hall coming
	 * off load for a moment, not a place to keep steam.
	 * <p>
	 * It being shallow is a feature. 180 mB/t needs a <em>pressurised</em> pipe run -- an ordinary
	 * fluid pipe carries 50 -- so an HRSG plumbed on the cheap backs up whatever the depth is, and
	 * it is much better that it says so within a quarter of a minute than that it takes ten to
	 * become obvious.
	 */
	public static final int TANK_CAPACITY = 48000;

	/**
	 * What a millibucket of steam is worth once a Steam Turbine Hall has had it, in flux.
	 * <p>
	 * Display only — nothing in this class converts steam to power. Read from the hall rather
	 * than repeated, because a copy that drifted would put a wrong number on the overlay of the
	 * one machine whose entire selling point is the number on its overlay.
	 */
	public static final int FLUX_PER_MILLIBUCKET_DOWNSTREAM =
			TileEntitySteamTurbineHall.FULL_EFFICIENCY;

	/**
	 * How long a claim on the host's exhaust stands without being renewed.
	 * <p>
	 * Three passes. Long enough that a single skipped pass -- an unloaded chunk boundary, a tick
	 * the machine spent backed up -- does not hand the exhaust to a neighbour, short enough that
	 * an HRSG somebody has just knocked down releases it inside two seconds.
	 */
	public static final int CLAIM_LEASE = 3*WORK_INTERVAL;

	/**
	 * The step at which the recovery figure is pushed to clients.
	 * <p>
	 * The rate climbs the whole way up a turbine's spool, and sending a description packet for
	 * every one of those steps would put twenty packets on the wire for an overlay almost nobody
	 * is reading. Twenty millibuckets keeps the displayed figure within about a tenth of full
	 * scale of the truth at all times, and both of the numbers that matter -- 180 at full output
	 * and 90 at half -- land exactly on a step, so the readout is never approximate at the two
	 * places a player is likely to check it.
	 */
	private static final int RATE_SYNC_STEP = 20;

	/**
	 * @param steamPerTick what the host's exhaust is worth, in millibuckets of steam per tick
	 * @return millibuckets one whole pass recovers, clamped to what a turbine can actually offer
	 */
	public static int steamPerPass(int steamPerTick)
	{
		if(steamPerTick <= 0)
			return 0;
		//Clamped rather than trusted. The rate arrives from another tile entity and a table an
		//addon can register into; a boiler that would happily multiply a bad number by ten and
		//bank the result is a boiler that turns one wrong config value into infinite power.
		return Math.min(steamPerTick, TileEntityGasTurbine.EXHAUST_STEAM_AT_FULL)*WORK_INTERVAL;
	}

	/**
	 * @return millibuckets a pass can actually take, once the room left in the drum is accounted
	 * for. Zero means the machine is backed up and must not spend anything.
	 */
	public static int recoverablePerPass(int steamPerTick, int tankRoom)
	{
		if(tankRoom <= 0)
			return 0;
		return Math.min(steamPerPass(steamPerTick), tankRoom);
	}

	//	=================================
	//		STATUS
	//	=================================

	/**
	 * Nothing is against the intake face, or what is against it is not a turbine's exhaust end.
	 */
	public static final int STATUS_NO_HOST = 0;
	public static final int STATUS_RECOVERING = 1;
	/**
	 * There is a turbine, and it is not producing. No output, no exhaust, no steam.
	 */
	public static final int STATUS_HOST_IDLE = 2;
	/**
	 * The feedwater pumps have no flux.
	 */
	public static final int STATUS_NO_POWER = 3;
	/**
	 * The drum is full, so the steam is not going anywhere.
	 */
	public static final int STATUS_BACKED_UP = 4;
	/**
	 * Another HRSG got to this exhaust first. See {@link TileEntityGasTurbine#claimExhaust}.
	 */
	public static final int STATUS_CLAIMED = 5;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];

	/**
	 * The drum. Nothing outside the machine may put anything in it: steam is made here or not at
	 * all, and a tank that accepted a fill would be a free steam laundry -- pour in whatever a
	 * pack has named {@code ie_steam} at one end, draw it out of a machine that never earned it at
	 * the other.
	 */
	public final FluidTank tankSteam = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return fluid!=null&&fluid.getFluid()!=null&&STEAM.equals(fluid.getFluid().getName());
		}
	};

	private final FluxStorage energyStorage = new FluxStorage(ENERGY_CAPACITY, ENERGY_MAX_RECEIVE, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * What the machine did, or did not do, on its last pass. Synced: it drives the plume and it is
	 * most of the overlay, and the six ways an HRSG can be doing nothing are not interchangeable.
	 */
	public int status = STATUS_NO_HOST;
	/**
	 * Whether a turbine's exhaust is actually against the intake face.
	 * <p>
	 * Synced, and separately from {@link #status}, because the host is only ever resolved
	 * server-side while the overlay is drawn client-side. The lubrication manifold shipped without
	 * this and told every player it was unattached while it was visibly working.
	 */
	public boolean attached;
	/**
	 * Millibuckets per tick actually being recovered. Synced on a coarse step -- see
	 * {@link #RATE_SYNC_STEP}.
	 */
	public int steamRate;

	private int stagger = -1;
	/**
	 * The turbine whose exhaust this machine sits on, resolved when the neighbourhood changes
	 * rather than searched for every pass. Master only.
	 */
	private TileEntityGasTurbine host;
	private boolean hostDirty = true;
	/**
	 * What was last put on the wire, so the coarse rate sync has something to compare against.
	 * Server side only.
	 */
	private int sentRate = -1;

	/**
	 * The one block of the structure a comparator reads from: the base corner at the cold end,
	 * which is the end a player can still reach once the machine is docked.
	 * <p>
	 * Every block answering would turn the whole machine into one big comparator face and make
	 * redstone next to it behave differently from every other multiblock in the mod.
	 */
	public static final int REDSTONE_INDEX = PetroleumGeometry.structureIndex(
			PetroleumGeometry.HRSG_SIZE, 0, PetroleumGeometry.HRSG_DEPTH-1, 0);

	public TileEntityHRSG()
	{
		super(PetroleumGeometry.HRSG_SIZE);
	}

	//	=================================
	//		RECOVERING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote)
		{
			//Only the master knows whether the machine is working, and only its own position is
			//needed to place the plume, so the other thirty-eight do nothing at all.
			if(formed&&!isDummy()&&status==STATUS_RECOVERING&&world.getTotalWorldTime()%4==0)
				spawnPlume();
			return;
		}
		if(isDummy()||!formed)
			return;
		if((world.getTotalWorldTime()+getStagger())%WORK_INTERVAL==0)
			runPass();
	}

	/**
	 * One pass: find the exhaust, take a claim on it, and bank whatever heat it is worth.
	 */
	private void runPass()
	{
		int previousStatus = status;
		boolean wasAttached = attached;

		//Re-resolved only when the neighbourhood has changed under us or the cached host has been
		//broken. A search every pass would be nine chunk lookups a second, per machine, for an
		//answer that changes when somebody swings a hammer.
		if(hostDirty||(host!=null&&host.isInvalid()))
			resolveHost();

		status = STATUS_NO_HOST;
		steamRate = 0;
		attached = host!=null&&!host.isInvalid()&&host.formed;
		boolean worked = attached&&recover();

		if(worked||status!=previousStatus)
			//The drum, the buffer and the status all moved, and a chunk saved without them comes
			//back holding steam it has already sold.
			markDirty();
		if(status!=previousStatus||attached!=wasAttached
				||sentRate/RATE_SYNC_STEP!=steamRate/RATE_SYNC_STEP)
		{
			sentRate = steamRate;
			markContainingBlockForUpdate(null);
		}
	}

	/**
	 * @return whether anything was actually banked
	 */
	private boolean recover()
	{
		//Claimed before the rate is even looked at, and held whether or not the turbine happens to
		//be running. A claim that lapsed every time a plant came off load would hand the exhaust to
		//whichever boiler's stagger came up first on the restart, and a plant would silently swap
		//which of its two boilers was live every time it was cycled.
		if(!host.claimExhaust(getPos(), CLAIM_LEASE))
		{
			status = STATUS_CLAIMED;
			return false;
		}
		int rate = host.getExhaustSteamPerTick();
		if(rate <= 0)
		{
			status = STATUS_HOST_IDLE;
			return false;
		}
		int steam = recoverablePerPass(rate, tankSteam.getCapacity()-tankSteam.getFluidAmount());
		if(steam <= 0)
		{
			status = STATUS_BACKED_UP;
			return false;
		}
		Fluid product = FluidRegistry.getFluid(STEAM);
		if(product==null)
			//A pack that has somehow stripped the fluid gets a machine that does nothing, rather
			//than one that throws every tick.
			return false;
		if(!drawPower())
		{
			status = STATUS_NO_POWER;
			return false;
		}

		int filled = tankSteam.fill(new FluidStack(product, steam), true);
		//The honest figure: what went into the drum, not what the exhaust was theoretically worth.
		//A boiler whose last pass was cut short by a full drum says the smaller number.
		steamRate = filled/WORK_INTERVAL;
		status = STATUS_RECOVERING;
		return filled > 0;
	}

	/**
	 * @return whether the pass could be paid for; nothing is consumed when it could not
	 */
	private boolean drawPower()
	{
		if(CityMode.petroleum())
		{
			//Presence, not consumption. Charging a real 40 IF/t here would demand exactly the
			//accounting city mode exists to remove, while a sip still means an unwired HRSG stops.
			//Written as "at most nothing" rather than "less than the cost", because a comparison of
			//a zero balance against a zero cost passes and the machine would run for free forever
			//-- which is the bug the industrial burner shipped with.
			if(energyStorage.getEnergyStored() <= 0)
				return false;
			energyStorage.modifyEnergyStored(-CITY_SIP);
			return true;
		}
		if(energyStorage.getEnergyStored() < ENERGY_PER_PASS)
			return false;
		//Not extractEnergy: this storage is built with an extract limit of zero so that nothing
		//wired to the machine can siphon its buffer back out, and that limit applies to the machine
		//itself too. Going through it would silently return zero every time, and the HRSG would
		//never recover a drop.
		energyStorage.modifyEnergyStored(-ENERGY_PER_PASS);
		return true;
	}

	/**
	 * Works out which Gas Turbine, if any, this machine is docked to.
	 * <p>
	 * Three conditions, and all three matter:
	 * <ol>
	 * <li>the block against a cell of the intake face is part of a formed Gas Turbine;</li>
	 * <li>that part is one of the nine cells of the turbine's <em>exhaust end</em> -- an HRSG
	 * parked against the filter house is catching cold air, and a turbine has an intake as well as
	 * an outlet;</li>
	 * <li>the turbine's exhaust actually points at the cell we asked from. That last test is
	 * geometric rather than a facing comparison so that it stays right no matter how either
	 * machine came to be oriented: the turbine's own end cell, stepped once along the turbine's
	 * own facing, has to land exactly on our intake cell.</li>
	 * </ol>
	 * The whole face is walked rather than one nominated cell, so a plant built one course off the
	 * turbine's base still couples. That is what makes an exclusive claim necessary -- see
	 * {@link TileEntityGasTurbine#claimExhaust}.
	 */
	private void resolveHost()
	{
		host = null;
		hostDirty = false;
		for(int h = 0; h < PetroleumGeometry.HRSG_HEIGHT; h++)
			for(int w = 0; w < PetroleumGeometry.HRSG_WIDTH; w++)
			{
				BlockPos face = getBlockPosForPos(MultiblockHRSG.intakePos(h, w));
				TileEntity te = Utils.getExistingTileEntity(world, face.offset(facing.getOpposite()));
				if(!(te instanceof TileEntityGasTurbine))
					continue;
				TileEntityGasTurbine part = (TileEntityGasTurbine)te;
				if(!part.formed||!TileEntityGasTurbine.isExhaustFace(part.pos))
					continue;
				if(!part.getPos().offset(part.getFacing()).equals(face))
					continue;
				TileEntityGasTurbine master = part.master();
				if(master==null||!master.formed)
					continue;
				host = master;
				return;
			}
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		hostDirty = true;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		if(world==null||world.isRemote||!formed)
			return;
		//Only the intake face can gain or lose a turbine, so the other thirty-six blocks of the
		//machine do not bother the master about their own neighbours. A power plant is a busy place
		//and this fires on every redstone flicker next to any of forty-five blocks.
		if(!isIntakeFace(pos))
			return;
		TileEntityHRSG master = master();
		if(master!=null)
			master.hostDirty = true;
		else
			hostDirty = true;
	}

	/**
	 * Spreads passes across ticks by position, so a row of HRSGs never all settle on the same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), WORK_INTERVAL);
		return stagger;
	}

	private void spawnPlume()
	{
		//A wisp off the drum, which is the one part of the machine that is meant to look alive.
		BlockPos drum = getBlockPosForPos(PetroleumGeometry.structureIndex(
				PetroleumGeometry.HRSG_SIZE, PetroleumGeometry.HRSG_HEIGHT-1,
				PetroleumGeometry.HRSG_DEPTH/2, PetroleumGeometry.HRSG_WIDTH/2));
		world.spawnParticle(EnumParticleTypes.CLOUD,
				drum.getX()+.5, drum.getY()+1.1, drum.getZ()+.5, 0, .015, 0);
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed)
			return null;
		//Read off the master, and off synced fields only. Whether this machine is coupled to
		//anything is the one question it exists to answer, and it is answered on the client.
		TileEntityHRSG master = master();
		if(master==null||!master.formed)
			return null;
		if(!master.attached)
			return new String[]{
					TextFormatting.YELLOW+"Not on a turbine's exhaust"+TextFormatting.RESET,
					TextFormatting.GRAY+"Dock the intake face to a Gas Turbine"+TextFormatting.RESET};
		switch(master.status)
		{
			case STATUS_RECOVERING:
				return new String[]{
						TextFormatting.GREEN+"Recovering"+TextFormatting.RESET,
						master.steamRate+" mB/t -- "
								+master.steamRate*FLUX_PER_MILLIBUCKET_DOWNSTREAM+" IF/t downstream"};
			case STATUS_CLAIMED:
				return new String[]{
						TextFormatting.RED+"Exhaust already tapped"+TextFormatting.RESET,
						TextFormatting.GRAY+"Another HRSG has this turbine"+TextFormatting.RESET};
			case STATUS_NO_POWER:
				return new String[]{TextFormatting.RED+"No power"+TextFormatting.RESET,
						TextFormatting.GRAY+"Feedwater pumps need "+ENERGY_PER_TICK+" IF/t"
								+TextFormatting.RESET};
			case STATUS_BACKED_UP:
				return new String[]{TextFormatting.GOLD+"Backed up"+TextFormatting.RESET,
						TextFormatting.GRAY+"The drum is full"+TextFormatting.RESET};
			default:
				return new String[]{TextFormatting.GRAY+"No exhaust heat"+TextFormatting.RESET,
						TextFormatting.GRAY+"The turbine is not on line"+TextFormatting.RESET};
		}
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(pos!=REDSTONE_INDEX)
			return 0;
		TileEntityHRSG master = master();
		if(master==null||!master.formed)
			return 0;
		//The drum level, which is what anybody wiring a comparator to a boiler wants: a drum
		//climbing means the steam line is not keeping up with the plant.
		if(master.tankSteam.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, 15*master.tankSteam.getFluidAmount()/TANK_CAPACITY);
	}

	/**
	 * @return whether the machine banked steam on its last pass, asked of any block of it
	 */
	public boolean isRecovering()
	{
		TileEntityHRSG master = master();
		return master!=null&&master.formed&&master.status==STATUS_RECOVERING;
	}

	//	=================================
	//		PORTS
	//	=================================

	private static int depthOf(int pos)
	{
		return pos%(PetroleumGeometry.HRSG_DEPTH*PetroleumGeometry.HRSG_WIDTH)
				/PetroleumGeometry.HRSG_WIDTH;
	}

	private static boolean inStructure(int pos)
	{
		return pos >= 0&&pos < PetroleumGeometry.HRSG_HEIGHT*PetroleumGeometry.HRSG_DEPTH
				*PetroleumGeometry.HRSG_WIDTH;
	}

	/**
	 * @return whether this structure index is one of the nine cells of the intake face -- the face
	 * that has to be against a turbine's exhaust end
	 */
	public static boolean isIntakeFace(int pos)
	{
		return inStructure(pos)&&depthOf(pos)==MultiblockHRSG.INTAKE_DEPTH;
	}

	/**
	 * @return whether a wire may land on this part of the machine
	 * <p>
	 * The whole skid course, which is where the feedwater pumps are. Generous on purpose, as the
	 * turbine's fuel ports are: a connector that has to find one exact block of a forty-five block
	 * machine is a connector the player gets wrong, and there is nothing to be gained from that
	 * being fiddly.
	 */
	public static boolean isEnergyPos(int pos)
	{
		return inStructure(pos)
				&&PetroleumGeometry.heightOf(PetroleumGeometry.HRSG_SIZE, pos)==0;
	}

	/**
	 * @return whether steam may be drawn out of this part of the machine
	 * <p>
	 * Two places, and both were picked for being physically reachable on a docked machine: the
	 * cold-end course above the skid, which is the far face from the turbine, and the flanks and
	 * top of the drum spine. Nothing at depth 0 is offered, because depth 0 is buried against
	 * forty-two blocks of turbine and a pipe could never reach it; and nothing on the skid course
	 * is offered either, so no cell of the machine is both a wire landing and a pipe landing.
	 */
	public static boolean isSteamPort(int pos)
	{
		if(!inStructure(pos))
			return false;
		int h = PetroleumGeometry.heightOf(PetroleumGeometry.HRSG_SIZE, pos);
		int l = depthOf(pos);
		if(h < 1)
			return false;
		if(l==PetroleumGeometry.HRSG_DEPTH-1)
			return true;
		return h==PetroleumGeometry.HRSG_HEIGHT-1&&l > MultiblockHRSG.INTAKE_DEPTH;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityHRSG master = master();
		if(master==null||!formed||!isSteamPort(pos))
			return NO_TANKS;
		return new IFluidTank[]{master.tankSteam};
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		//Nothing goes in. The drum is filled by the machine and by nothing else -- see the tank's
		//own guard, which refuses anything that is not steam even on that path.
		return false;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		return isSteamPort(pos);
	}

	//	=================================
	//		FLUX
	//	=================================

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		TileEntityHRSG master = master();
		return master!=null?master.energyStorage: energyStorage;
	}

	@Nonnull
	@Override
	public SideConfig getEnergySideConfig(@Nullable EnumFacing facing)
	{
		return formed&&isEnergyPos(pos)?SideConfig.INPUT: SideConfig.NONE;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		if(facing==null||!formed||!isEnergyPos(pos))
			return null;
		return wrappers[facing.ordinal()];
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The unit is a box of boxes; the silhouette comes from the air
		//either side of the drum, not from per-block bounds.
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the machine is four different blocks, and
		//handing back thirty-nine frames would turn every disassembly into a free trade of
		//sheetmetal and radiators for frames.
		int depth = PetroleumGeometry.HRSG_DEPTH;
		int width = PetroleumGeometry.HRSG_WIDTH;
		ItemStack original = !inStructure(pos)?null
				: MultiblockHRSG.instance.getStructureManual()
				[PetroleumGeometry.heightOf(PetroleumGeometry.HRSG_SIZE, pos)]
				[pos%(depth*width)/width]
				[pos%width];
		//An unformed block dropped in the world is always a frame; that is the part of the machine
		//that is a placeable item in its own right.
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
		status = nbt.getInteger("status");
		attached = nbt.getBoolean("attached");
		steamRate = nbt.getInteger("steamRate");
		if(!descPacket)
		{
			tankSteam.readFromNBT(nbt.getCompoundTag("tankSteam"));
			energyStorage.readFromNBT(nbt);
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		//These three and nothing else cross the wire: the plume and the overlay are the whole of
		//what a client knows about this machine. The drum and the buffer stay out of the
		//description packet -- nobody can see them, and a tank on a packet nobody reads is not
		//worth sending.
		nbt.setInteger("status", status);
		nbt.setBoolean("attached", attached);
		nbt.setInteger("steamRate", steamRate);
		if(!descPacket)
		{
			nbt.setTag("tankSteam", tankSteam.writeToNBT(new NBTTagCompound()));
			energyStorage.writeToNBT(nbt);
		}
	}
}
