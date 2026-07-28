/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The Steam Turbine Hall: the biggest generator in the mod, and the slowest to forgive being
 * switched on and off.
 * <p>
 * A Diesel Generator is a flat number the instant fuel reaches it. A Gas Turbine at least has a
 * fuel-hungry ignition to punish a cold start. This machine goes further than either: it is not
 * merely slow to spin up ({@link #SPOOL_UP_TICKS}, twenty seconds) -- the steam it drinks on the
 * way up buys proportionally less power than the steam it drinks once it is turning at rated
 * speed, because {@link #efficiencyAt(int)} itself is a curve and not a constant. A generator
 * that only got slower to start would still be worth cycling if the demand justified it; one
 * whose <em>rate</em> gets worse the further it is from full speed is worth leaving on a steady
 * base load and nothing else. That is the whole design brief for this section of the plan, and
 * it lives entirely in three static functions below -- no world, no fluid, no neighbours -- so
 * it is exactly as testable as the Gas Turbine's own pair of curves.
 * <p>
 * <strong>Why steam, and not just flux, is what is metered.</strong> {@link #outputAt(int)} says
 * what the hall delivers; {@link #efficiencyAt(int)} says how many Flux one millibucket of steam
 * is worth at that spool; {@link #steamPerPassAt(int)} is what actually gets drained from the
 * tank, and it is <em>not</em> simply the first two divided through. A pass that would round its
 * own cost down to nothing because the spool is barely alight is charged
 * {@link #MIN_STEAM_PER_PASS} instead -- the boiler line has to be open and delivering something
 * for the hall to be trying to run at all, exactly as a real turbine needs steam on the chest
 * before it can turn, whether or not it is yet making rated power. Skipping that floor is the
 * exact shape of bug this plan calls out by name: an affordability check of the form
 * {@code stored < cost} is false whenever both sides are zero, and a machine that can compute a
 * zero cost for itself runs forever on an empty tank. Charging a floor here is what keeps the
 * check honest at the one spool value -- cold, cell zero -- where the naive arithmetic would
 * otherwise hand out free steam.
 * <p>
 * <strong>City mode</strong> follows {@link TileEntityGasTurbine} to the letter: metering drops
 * (a token sip proves the boiler line is live, starvation is never checked), and the output curve
 * is bypassed in favour of {@link #MAX_OUTPUT} outright the instant the hall is lit. The spool
 * counter itself is still stepped every tick -- it is an integer and a pure function, so keeping
 * it costs nothing -- and the readout still visibly winds up, but nothing waits on it: from an
 * operator's chair the hall reads as switching straight to full output, which is what "instant
 * spool" in the brief for this machine means once it is read next to "follow the turbine exactly".
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntitySteamTurbineHall extends TileEntityMultiblockPart<TileEntitySteamTurbineHall>
		implements IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		THE SPOOL
	//	=================================

	/**
	 * Full output, in Flux per tick.
	 * <p>
	 * The pinned figure for this machine: a millibucket of steam is worth 100 Flux at full spool,
	 * and the hall can draw {@link #MAX_STEAM_PER_TICK} of them a tick, so 30,000 is not a taste
	 * number -- it falls straight out of the other two.
	 */
	public static final int MAX_OUTPUT = 30000;

	/**
	 * Peak steam draw, in millibuckets per tick, at full spool. Pinned alongside {@link #MAX_OUTPUT}
	 * so the two can never be tuned out of step with each other.
	 */
	public static final int MAX_STEAM_PER_TICK = 300;

	/**
	 * The spool counter at full speed, in internal units rather than ticks.
	 * <p>
	 * Twenty seconds -- four hundred ticks -- is the pinned cold-to-full time, and the decay rate
	 * is pinned at half of whatever the build rate is. Neither of those is expressible with a
	 * single integer step per tick: half of "one unit a tick" is not an integer. Counting in
	 * finer-grained units sidesteps that the same way the Gas Turbine's own spool does -- two units
	 * up, one down, four hundred ticks to climb eight hundred of them -- rather than inventing a
	 * fractional-tick accumulator nothing else in the class needs.
	 */
	public static final int SPOOL_FULL = 800;
	public static final int SPOOL_UP_STEP = 2;
	/**
	 * Exactly half {@link #SPOOL_UP_STEP}. Restarting a warm hall is expensive, per the brief for
	 * this machine, but not ruinous: the rotor coasts down over twice as long as it took to spin
	 * up, so a short interruption to the steam supply is recovered from a warm rotor rather than a
	 * cold one, precisely as the Gas Turbine's own asymmetric spool already rewards.
	 */
	public static final int SPOOL_DOWN_STEP = SPOOL_UP_STEP/2;
	public static final int SPOOL_UP_TICKS = SPOOL_FULL/SPOOL_UP_STEP;
	public static final int SPOOL_DOWN_TICKS = SPOOL_FULL/SPOOL_DOWN_STEP;

	/**
	 * Ticks between passes. Steam accounting, the redstone switch and the search for terminals all
	 * happen here and nowhere else -- once every five ticks is still four times as often as the Gas
	 * Turbine bothers, which is the right trade for the single biggest tickable structure in the
	 * mod: running its logic every tick would be the most expensive per-block cost in the expansion
	 * for a number that changes as slowly as this one does.
	 */
	public static final int WORK_INTERVAL = 5;

	/**
	 * @param spool the accumulated spool count
	 * @param fed   whether steam is being drawn this tick
	 * @return the spool count after one tick, clamped to the machine's range
	 */
	public static int spoolAfter(int spool, boolean fed)
	{
		if(fed)
			return Math.min(SPOOL_FULL, Math.max(0, spool)+SPOOL_UP_STEP);
		return Math.max(0, Math.min(SPOOL_FULL, spool)-SPOOL_DOWN_STEP);
	}

	/**
	 * The output curve: linear in the spool count, exactly as the Gas Turbine's own is, so a
	 * player reading a percentage off the hall is reading its power directly.
	 *
	 * @return Flux per tick the hall delivers at this spool
	 */
	public static int outputAt(int spool)
	{
		int clamped = Math.max(0, Math.min(SPOOL_FULL, spool));
		return MAX_OUTPUT*clamped/SPOOL_FULL;
	}

	/**
	 * How many Flux one millibucket of steam is worth at this spool.
	 * <p>
	 * This is the part-load penalty stated outright, and it is deliberately its own curve rather
	 * than something derived from {@link #outputAt(int)} -- a rotor a long way from synchronous
	 * speed still extracts real work from the steam passing through it, it just is not yet handing
	 * any of that work to the grid as Flux. Forty at a standstill, a hundred at full speed: below
	 * full spool the hall is always worse at turning steam into Flux than it will be once it gets
	 * there, which is the arithmetic reason it is worth leaving running rather than throttled on
	 * and off against a fluctuating demand.
	 *
	 * @return between 40 and 100, inclusive, and never outside that range whatever it is handed
	 */
	public static int efficiencyAt(int spool)
	{
		int clamped = Math.max(0, Math.min(SPOOL_FULL, spool));
		return IDLE_EFFICIENCY+(FULL_EFFICIENCY-IDLE_EFFICIENCY)*clamped/SPOOL_FULL;
	}

	public static final int IDLE_EFFICIENCY = 40;
	/**
	 * Pinned: a millibucket of steam is worth exactly a hundred Flux once the hall is fully
	 * spooled, and {@link #MAX_STEAM_PER_TICK} of them a tick is where {@link #MAX_OUTPUT} comes
	 * from.
	 */
	public static final int FULL_EFFICIENCY = 100;

	/**
	 * The floor under a pass's steam bill, in millibuckets.
	 * <p>
	 * Without it, a pass at a low spool can compute a real cost of zero -- {@link #outputAt(int)}
	 * is small, {@link #efficiencyAt(int)} divides it down further, and integer division rounds the
	 * remainder away -- and {@code tank amount >= 0} is true even of an empty tank. That is the
	 * exact shape of bug the industrial burner shipped: an affordability check that is vacuously
	 * satisfied whenever both sides happen to be zero. A hall attempting to run always owes at
	 * least this much steam a pass, whether or not the spool is yet high enough to be worth
	 * anything, which both closes the loophole and gives the cold start real weight of its own --
	 * steam spent turning a rotor that is not yet generating is exactly the cost this machine's
	 * whole design is built to make a player feel.
	 */
	public static final int MIN_STEAM_PER_PASS = 100;

	/**
	 * @param spool the accumulated spool count
	 * @return millibuckets of steam one whole pass costs at this spool, never zero while the hall
	 * is attempting to run
	 */
	public static int steamPerPassAt(int spool)
	{
		int clamped = Math.max(0, Math.min(SPOOL_FULL, spool));
		//Deliberately not "outputAt(spool)*WORK_INTERVAL/efficiencyAt(spool)": that composes two
		//curves that have each already been rounded down on their own, and two independent
		//roundings can fight each other -- there is a spool value in this machine's own range
		//where that composition briefly gets cheaper as the spool climbs, which is exactly the
		//"it only ever climbs" property the design brief depends on. Multiplying the two curves'
		//exact (unrounded) fractions out into a single ratio and rounding once, at the end, is
		//provably monotonic: with A, B, C all positive constants, A*n/(B+C*n) is strictly
		//increasing in n, and a ceiling can only preserve that ordering, never break it.
		long numerator = (long)MAX_OUTPUT*WORK_INTERVAL*clamped;
		long denominator = (long)IDLE_EFFICIENCY*SPOOL_FULL+(long)(FULL_EFFICIENCY-IDLE_EFFICIENCY)*clamped;
		//Rounded up, not down: a pass must never be able to buy more Flux than the steam it paid
		//for was worth, which a truncating division would eventually allow at some spool value.
		long ideal = denominator <= 0?0: (numerator+denominator-1)/denominator;
		return (int)Math.max(MIN_STEAM_PER_PASS, ideal);
	}

	//	=================================
	//		STEAM
	//	=================================

	/**
	 * Deep enough to ride out a stutter in the boiler line without being deep enough to run the
	 * hall from a delivered tankerload -- sixty seconds at peak draw, the same shallow-buffer
	 * philosophy the Gas Turbine's own tank follows at the bottom of its own range. A plant this
	 * size is meant to sit on the end of a pipe from a boiler house, not to be topped up by hand.
	 */
	public static final int TANK_CAPACITY = 360000;

	/**
	 * City mode steam sip: a token millibucket per pass, so a tank still visibly empties and
	 * plumbing the hall to a boiler still matters, but nothing is metered. Same trade the Gas
	 * Turbine and the Diesel Generator both make.
	 */
	private static final int CITY_STEAM_SIP = 1;

	private static final TileEntity[] NO_TERMINALS = new TileEntity[0];

	public final FluidTank tank = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return fluid!=null&&fluid.getFluid()==IEContent.fluidSteam;
		}

		@Override
		public boolean canDrainFluidType(FluidStack fluid)
		{
			//A hall is where steam is spent, not a tank another machine can raid it back out of.
			return false;
		}
	};

	/**
	 * The accumulated spool count. Ticked identically on both sides off the same pure curve, so it
	 * is written to the description packet only as a starting point for a client that has just
	 * loaded the chunk -- never as a per-tick update. See {@link TileEntityGasTurbine#spool} for
	 * the same argument in more detail; nothing about it changes at this machine's scale.
	 */
	public int spool;
	/**
	 * Whether steam is currently being drawn. The one field that has to cross the wire: it drives
	 * the vent particles, the overlay and the client's own copy of the spool.
	 */
	public boolean active;

	private int stagger = -1;
	/**
	 * What is standing on the switchyard bus waiting to be fed, resolved on the pass and held in
	 * between. See {@link TileEntityGasTurbine#terminals} for why this is cached rather than
	 * looked up every tick; the argument only gets stronger the bigger the structure is.
	 */
	private TileEntity[] terminals = NO_TERMINALS;

	/**
	 * The one block of the structure a comparator reads from: the foundation raft under the steam
	 * inlet skid. Every block answering would turn the whole building into one giant comparator
	 * face, which is the same reason the Gas Turbine gates its own readout to a single cell.
	 */
	public static final int REDSTONE_INDEX = PetroleumGeometry.structureIndex(
			PetroleumGeometry.HALL_SIZE, 0, MultiblockSteamTurbineHall.INLET_DEPTH, 0);

	public TileEntitySteamTurbineHall()
	{
		super(PetroleumGeometry.HALL_SIZE);
	}

	//	=================================
	//		RUNNING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(isDummy()||!formed)
			return;
		if(world.isRemote)
		{
			//The client runs the same curve off the synced flag rather than being sent a number
			//every tick, exactly as the Gas Turbine's own client half does.
			spool = spoolAfter(spool, active);
			if(spool > 0&&world.getTotalWorldTime()%4==0)
				spawnVent();
			return;
		}

		if((world.getTotalWorldTime()+getStagger())%WORK_INTERVAL==0)
			runPass();
		spool = spoolAfter(spool, active);
		//Flux is handed over every tick because a connector accepts only a limited amount per tick
		//and would throw away anything delivered in a lump, but this is a walk of at most three
		//references resolved on the pass -- no world lookup happens here.
		if(active)
			pushOutput();
	}

	/**
	 * One pass: settle a whole interval's steam, read the switch, and re-find the terminals.
	 */
	private void runPass()
	{
		refreshTerminals();

		//Read once, on the pass, off the master's own block. A hall with nothing to deliver into is
		//shut down rather than run against an open breaker -- it is a base load machine, and
		//burning steam for a load that is not there is the one thing it must never look sensible
		//for.
		boolean stopped = terminals.length==0||world.getRedstonePowerFromNeighbors(getPos()) > 0;

		boolean lit;
		if(stopped)
			lit = false;
		else if(CityMode.petroleum())
		{
			//City mode: steam is cosmetic and nothing is metered. The spool is kept -- it costs
			//nothing and it is what makes the hall still visibly wind up -- but the steam curve and
			//the output curve are both dropped: a token sip proves the boiler line is live and the
			//delivered power is flat, the same trade the Gas Turbine already makes.
			lit = tank.getFluidAmount() >= CITY_STEAM_SIP;
			if(lit)
				tank.drain(CITY_STEAM_SIP, true);
		}
		else
		{
			int demand = steamPerPassAt(spool);
			lit = tank.getFluidAmount() >= demand;
			if(lit)
				tank.drain(demand, true);
		}

		if(lit!=active)
		{
			active = lit;
			markContainingBlockForUpdate(null);
		}
		//The tank moved and so did the spool, and a chunk saved without them comes back holding
		//steam it already spent or claiming a rotor speed it never reached. Once every five ticks,
		//master only.
		markDirty();
	}

	/**
	 * Hands the tick's flux to the terminals, split as evenly as whole units allow.
	 */
	private void pushOutput()
	{
		//City mode drops the output curve with the steam curve: metering half a plant's power
		//against a rotor speed is exactly the accounting the mode exists to skip.
		int output = CityMode.petroleum()?MAX_OUTPUT: outputAt(spool);
		if(output <= 0)
			return;
		int live = 0;
		for(TileEntity terminal : terminals)
			if(terminal!=null&&!terminal.isInvalid())
				live++;
		if(live < 1)
			return;
		int share = output/live;
		int leftover = output%live;
		for(TileEntity terminal : terminals)
			if(terminal!=null&&!terminal.isInvalid())
				EnergyHelper.insertFlux(terminal, EnumFacing.DOWN, share+(leftover-- > 0?1: 0), false);
	}

	/**
	 * Re-reads what is standing on the switchyard bus. That bus is the open deck of the structure,
	 * so "put the connectors over the switchyard" is the whole instruction a player needs, and it
	 * is visible from outside the building.
	 */
	private void refreshTerminals()
	{
		TileEntity[] found = null;
		int count = 0;
		for(int w = 0; w < MultiblockSteamTurbineHall.TERMINAL_COUNT; w++)
		{
			BlockPos deck = getBlockPosForPos(MultiblockSteamTurbineHall.terminalPos(w)).up();
			TileEntity te = Utils.getExistingTileEntity(world, deck);
			if(!EnergyHelper.isFluxReceiver(te, EnumFacing.DOWN))
				continue;
			if(found==null)
				found = new TileEntity[MultiblockSteamTurbineHall.TERMINAL_COUNT];
			found[count++] = te;
		}
		terminals = found==null?NO_TERMINALS: Arrays.copyOf(found, count);
	}

	/**
	 * Spreads passes across ticks by position, so a hall never settles its steam on the same tick
	 * as every other throttled machine at the same coordinates would.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), WORK_INTERVAL);
		return stagger;
	}

	private void spawnVent()
	{
		BlockPos vent = getBlockPosForPos(PetroleumGeometry.structureIndex(PetroleumGeometry.HALL_SIZE,
				2, MultiblockSteamTurbineHall.CONDENSER_WALL, MultiblockSteamTurbineHall.WIDTH/2));
		//The condenser wall sits at the front of the structure (depth zero), so its outward face is
		//against the direction the hall was built in.
		EnumFacing outward = facing.getOpposite();
		//Venting off the condenser face, and harder the closer the hall is to full speed, so the
		//spool is legible from outside the building without anyone having to walk in and read a
		//number.
		world.spawnParticle(EnumParticleTypes.CLOUD,
				vent.getX()+.5+outward.getXOffset()*.6, vent.getY()+.5, vent.getZ()+.5+outward.getZOffset()*.6,
				0, .01+.05*spool/(double)SPOOL_FULL, 0);
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		//Read off the master rather than off whatever block was looked at: only the master runs the
		//spool, so a player pointing at the switchyard would otherwise be told a hall plainly
		//delivering power was cold.
		TileEntitySteamTurbineHall master = master();
		if(!formed||master==null||!master.formed)
			return null;
		int spool = Math.min(Math.max(master.spool, 0), SPOOL_FULL);
		int percent = spool*100/SPOOL_FULL;
		if(master.active)
			return new String[]{
					(spool >= SPOOL_FULL?TextFormatting.GREEN+"On line": TextFormatting.GOLD+"Spooling up")
							+TextFormatting.RESET,
					percent+"% -- "+outputAt(spool)+" IF/t"};
		if(spool > 0)
			//Named rather than left as a falling percentage, because "why is the rotor still
			//turning with nothing coming out" is the one question the hall has to answer out loud.
			return new String[]{TextFormatting.GRAY+"Coasting down"+TextFormatting.RESET,
					percent+"% -- no output"};
		return new String[]{TextFormatting.GRAY+"Cold"+TextFormatting.RESET};
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
		TileEntitySteamTurbineHall master = master();
		if(master==null||!master.formed)
			return 0;
		//Spool level, not tank level: what an operator wiring a comparator to a 30,000 FE/t plant
		//wants to automate on is whether it is up to speed, not how much steam happens to be
		//sitting in its header.
		return master.spool*15/SPOOL_FULL;
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The hall is a box of boxes; there is no shape to carve.
		return null;
	}

	/**
	 * @return whether steam may be pushed into this part of the hall
	 */
	public static boolean isSteamPort(int pos)
	{
		//The whole foundation raft, plus the inlet skid itself. Generous on purpose: a steam main
		//that has to find one exact block of a building this size is a main a player gets wrong,
		//and there is nothing to be gained from that being fiddly.
		if(pos < 0)
			return false;
		return pos==MultiblockSteamTurbineHall.MASTER_POS
				||PetroleumGeometry.heightOf(PetroleumGeometry.HALL_SIZE, pos)==0;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntitySteamTurbineHall master = master();
		if(master!=null&&formed&&isSteamPort(pos))
			return new IFluidTank[]{master.tank};
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return resource!=null&&resource.getFluid()==IEContent.fluidSteam;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Steam goes in and is spent turning the rotor. Letting it back out would make the hall a
		//free steam store that happens to also be the biggest generator in the mod.
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the hall is five different blocks, and
		//handing back two hundred-odd oilfield frames would turn every disassembly into a free
		//trade of sheetmetal and engineering blocks for frames.
		int perLayer = PetroleumGeometry.HALL_DEPTH*PetroleumGeometry.HALL_WIDTH;
		ItemStack original = pos < 0?null
				: MultiblockSteamTurbineHall.instance.getStructureManual()
				[pos/perLayer]
				[pos%perLayer/PetroleumGeometry.HALL_WIDTH]
				[pos%PetroleumGeometry.HALL_WIDTH];
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
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		active = nbt.getBoolean("active");
		spool = nbt.getInteger("spool");
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		nbt.setBoolean("active", active);
		//Saved so a reloaded chunk resumes from a warm rotor rather than a cold one, and sent so a
		//client that has just come into range starts its own count from the right place. The
		//description packet only goes out when the hall lights or goes out, so this costs one
		//integer on a packet that is already rare -- not a number on the wire every tick.
		nbt.setInteger("spool", spool);
	}
}
