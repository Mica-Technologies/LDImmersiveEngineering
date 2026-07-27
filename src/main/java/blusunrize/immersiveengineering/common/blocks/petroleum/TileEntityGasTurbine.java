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
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine;
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
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The Gas Turbine: burns gas for flux, spooling up rather than switching on.
 * <p>
 * The Diesel Generator is a flat 4096 Flux the instant it has fuel, and switching it on and off
 * costs nothing at all. This machine is deliberately the opposite trade. It makes three times the
 * power, it takes ten seconds to get there, and while it is getting there it is burning gas out of
 * all proportion to what it is producing. Run it as a steady base load and it is the most
 * efficient thing in the mod per millibucket; cycle it against a fluctuating demand and it will
 * eat a tank proving it.
 * <p>
 * <strong>The two curves are the machine.</strong> {@link #outputAt(int)} and
 * {@link #fuelRateAt(int)} are pure functions of one accumulated tick count and nothing else --
 * no world, no fluid, no neighbours. That is what makes the interesting half of this class
 * directly testable, and it is also why the spool needs no client sync: the client ticks the same
 * count off the one synced {@code active} flag and arrives at the same number the server did.
 * <p>
 * Coasting down produces nothing. The fuel valve is shut and the breaker is open, so what is left
 * turning is rotor inertia -- the machine's memory of having been running, not a power source.
 * That memory is still worth something: a turbine that loses its gas for a few seconds restarts
 * from a warm rotor and pays only part of the spool again, which is what separates "a brief
 * interruption" from "cycling the plant".
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityGasTurbine extends TileEntityMultiblockPart<TileEntityGasTurbine>
		implements IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		THE SPOOL
	//	=================================

	/**
	 * Full output, in Flux per tick.
	 * <p>
	 * Exactly three Diesel Generators. Three is the number the machine has to hit for its own
	 * arithmetic to be readable -- it divides evenly across the three terminals at 4096 each, which
	 * is the very figure a player already knows a generator makes -- and it is enough that a
	 * turbine replaces a row of generators rather than joining one.
	 */
	public static final int MAX_OUTPUT = 3*4096;

	/**
	 * The spool counter at full speed.
	 * <p>
	 * Counted in units rather than ticks so that spinning up and coasting down can run at different
	 * rates without either needing a fractional step or a parity check. Ten seconds up under power,
	 * twenty seconds down freewheeling: a rotor accelerated by its own combustor comes up faster
	 * than it slows down against nothing but bearing drag, and the long tail is what makes a brief
	 * gas interruption cheap to recover from.
	 */
	public static final int SPOOL_FULL = 400;
	public static final int SPOOL_UP_STEP = 2;
	public static final int SPOOL_DOWN_STEP = 1;
	public static final int SPOOL_UP_TICKS = SPOOL_FULL/SPOOL_UP_STEP;
	public static final int SPOOL_DOWN_TICKS = SPOOL_FULL/SPOOL_DOWN_STEP;

	/**
	 * Fuel flow at the moment of ignition, in thousandths of the full-load flow.
	 * <p>
	 * This single number is the machine's whole character. A turbine has to keep its combustor lit
	 * and its compressor turning before it produces anything at all, so the flow does not start at
	 * nothing -- it starts at well over half. Output meanwhile starts at nothing and climbs
	 * linearly, so at half spool the machine delivers half its power on eight tenths of its gas.
	 * <p>
	 * Integrated over a whole cold start that is a hundred and sixty ticks of full-load gas spent
	 * to deliver a hundred ticks of full-load power: every start throws away three seconds of fuel
	 * before the machine has broken even. Ten starts cost what half a minute of running does.
	 */
	public static final int IDLE_FLOW_PERMILLE = 600;

	/**
	 * Ticks between passes. Fuel accounting, the redstone switch and the search for terminals all
	 * happen here and nowhere else; a whole interval's worth is settled in one go, exactly as the
	 * burner and the wellheads do.
	 * <p>
	 * A second of latency on the redstone switch would be glaring on a machine that responds
	 * instantly. On one that takes ten seconds to come up it is invisible.
	 */
	public static final int BURN_INTERVAL = 20;

	/**
	 * @param spool  the accumulated spool count
	 * @param firing whether the combustor is lit this tick
	 * @return the spool count after one tick, clamped to the machine's range
	 */
	public static int spoolAfter(int spool, boolean firing)
	{
		if(firing)
			return Math.min(SPOOL_FULL, Math.max(0, spool)+SPOOL_UP_STEP);
		return Math.max(0, Math.min(SPOOL_FULL, spool)-SPOOL_DOWN_STEP);
	}

	/**
	 * The output curve: linear in the spool count, so a player reading a percentage off the machine
	 * is reading its power directly and not a curve they have to guess at.
	 *
	 * @return Flux per tick the turbine delivers at this spool
	 */
	public static int outputAt(int spool)
	{
		if(spool <= 0)
			return 0;
		if(spool >= SPOOL_FULL)
			return MAX_OUTPUT;
		return MAX_OUTPUT*spool/SPOOL_FULL;
	}

	/**
	 * The fuel curve, in thousandths of the full-load flow.
	 * <p>
	 * A fraction rather than millibuckets because the volumetric flow depends on which gas is in
	 * the tank -- see {@link #getFullLoadFlow(String)} -- while the <em>shape</em> of the curve is a
	 * property of the machine and the same for every fuel.
	 *
	 * @return between {@link #IDLE_FLOW_PERMILLE} and 1000
	 */
	public static int fuelRateAt(int spool)
	{
		if(spool <= 0)
			return IDLE_FLOW_PERMILLE;
		if(spool >= SPOOL_FULL)
			return 1000;
		return IDLE_FLOW_PERMILLE+(1000-IDLE_FLOW_PERMILLE)*spool/SPOOL_FULL;
	}

	/**
	 * How well the machine is turning gas into flux, in thousandths of what it manages at full
	 * output. This is the part-load penalty stated outright: a thousand at full spool, six hundred
	 * and twenty five at half, and nothing at all at the moment of ignition.
	 */
	public static int efficiencyPermilleAt(int spool)
	{
		if(spool <= 0)
			return 0;
		int output = Math.min(spool, SPOOL_FULL)*1000/SPOOL_FULL;
		return output*1000/fuelRateAt(spool);
	}

	/**
	 * @param spool        the accumulated spool count
	 * @param fullLoadFlow the fuel's full-load flow, in millibuckets per tick
	 * @return millibuckets the turbine draws over one whole pass at this spool
	 */
	public static int fuelPerPassAt(int spool, int fullLoadFlow)
	{
		if(fullLoadFlow <= 0)
			return 0;
		return fullLoadFlow*BURN_INTERVAL*fuelRateAt(spool)/1000;
	}

	//	=================================
	//		FUEL
	//	=================================

	/**
	 * Full-load flow of each gas, in millibuckets per tick, keyed by fluid registry name.
	 * <p>
	 * Gases only, and its own table rather than {@link blusunrize.immersiveengineering.api.energy.DieselHandler}'s,
	 * because that table is by definition the compression engine's: it holds crude, biodiesel,
	 * naphtha and diesel, none of which belong anywhere near a turbine. The engine-type split is
	 * the point -- gasoline already cannot run a diesel generator -- and sharing the registry would
	 * quietly undo it.
	 * <p>
	 * The figures are set against what a Diesel Generator draws for its 4096: five millibuckets per
	 * tick of natural gas, four of propane. The turbine makes three times that power on twelve and
	 * ten, so a millibucket is worth a fifth to a quarter more here than in a compression engine --
	 * and propane stays the better gas by about the same margin it already was, so nothing about
	 * the existing ordering of the cuts is disturbed. That efficiency edge is the reward for
	 * accepting the spool; it is only real if the machine is left running.
	 */
	public static final int FULL_LOAD_FLOW_NATURAL_GAS = 12;
	public static final int FULL_LOAD_FLOW_PROPANE = 10;

	private static final Map<String, Integer> FUELS = new HashMap<String, Integer>();

	static
	{
		//Keyed by registry name rather than by Fluid so the table can be read -- and checked --
		//without a fluid registry, which is also how DieselHandler stores its own.
		registerFuel("natural_gas", FULL_LOAD_FLOW_NATURAL_GAS);
		registerFuel("propane", FULL_LOAD_FLOW_PROPANE);
	}

	/**
	 * @param fluidName    the fluid's registry name
	 * @param fullLoadFlow millibuckets per tick at full output; zero or less removes the fuel
	 */
	public static void registerFuel(String fluidName, int fullLoadFlow)
	{
		if(fluidName==null)
			return;
		if(fullLoadFlow > 0)
			FUELS.put(fluidName, fullLoadFlow);
		else
			FUELS.remove(fluidName);
	}

	/**
	 * @return millibuckets per tick the named gas is drawn at full output, or 0 if the turbine will
	 * not take it
	 */
	public static int getFullLoadFlow(String fluidName)
	{
		Integer flow = fluidName==null?null: FUELS.get(fluidName);
		return flow==null?0: flow;
	}

	public static boolean isValidFuel(String fluidName)
	{
		return getFullLoadFlow(fluidName) > 0;
	}

	public static boolean isValidFuel(@Nullable Fluid fluid)
	{
		return fluid!=null&&isValidFuel(fluid.getName());
	}

	/**
	 * Deep enough that a turbine is not a refuelling chore, and no deeper. Two hundred seconds of
	 * natural gas at full output: a machine this size is meant to sit on a line from a scrubber,
	 * and the tank is a buffer against the line stuttering rather than a way to run it from
	 * buckets.
	 */
	public static final int TANK_CAPACITY = 48000;

	/**
	 * City mode fuel sip: a token millibucket per pass, so tanks still visibly empty and refuelling
	 * still matters, but nothing is metered. Same trade the diesel generator and the burner make.
	 */
	private static final int CITY_FUEL_SIP = 1;

	private static final TileEntity[] NO_TERMINALS = new TileEntity[0];

	public final FluidTank tank = new FluidTank(TANK_CAPACITY);

	/**
	 * The accumulated spool count. Ticked on both sides off the same pure curve, so it is written
	 * to the description packet only as a starting point for a client that has just loaded the
	 * chunk -- never as a per-tick update.
	 */
	public int spool;
	/**
	 * Whether the combustor is lit. The one thing that has to cross the wire: it drives the plume,
	 * the overlay and the client's own copy of the spool.
	 */
	public boolean active;

	private int stagger = -1;
	/**
	 * What is standing on the terminal deck waiting to be fed, resolved on the pass and held in
	 * between. Looking three positions up every tick to find connectors that have not moved would
	 * be exactly the sort of poll this machine is meant not to do.
	 */
	private TileEntity[] terminals = NO_TERMINALS;

	public TileEntityGasTurbine()
	{
		super(PetroleumGeometry.TURBINE_SIZE);
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
			//every tick. Both sides start from the same count and step it by the same rule, so the
			//animation and the overlay agree with the server without a single extra packet.
			spool = spoolAfter(spool, active);
			if(spool > 0&&world.getTotalWorldTime()%4==0)
				spawnPlume();
			return;
		}

		if((world.getTotalWorldTime()+getStagger())%BURN_INTERVAL==0)
			runPass();
		spool = spoolAfter(spool, active);
		//Flux has to be handed over every tick -- a connector accepts a limited amount per tick and
		//would throw away anything delivered in a lump -- but this is a walk of an array of at most
		//three references that were resolved on the pass. No world lookup happens here.
		if(active)
			pushOutput();
	}

	/**
	 * One pass: settle a whole interval's fuel, read the switch, and re-find the terminals.
	 */
	private void runPass()
	{
		refreshTerminals();

		FluidStack fuel = tank.getFluid();
		int flow = fuel!=null&&fuel.getFluid()!=null?getFullLoadFlow(fuel.getFluid().getName()): 0;
		//Read once, on the pass, off the master's own block. A turbine with nothing to deliver into
		//is shut down rather than run against an open breaker: it is a base load machine, and
		//burning gas for a load that is not there is the one thing it must never look sensible for.
		boolean stopped = flow <= 0||terminals.length==0
				||world.getRedstonePowerFromNeighbors(getPos()) > 0;

		boolean lit;
		if(stopped)
			lit = false;
		else if(CityMode.petroleum())
		{
			//City mode: fuel is cosmetic and nothing is metered. The spool is kept -- it is an int
			//and a pure function, so it costs nothing and the machine still visibly winds up and
			//down -- but the fuel curve and the output curve are both dropped: gas is a token sip
			//and the delivered power is flat, which is what the diesel generator already does.
			lit = tank.getFluidAmount() >= CITY_FUEL_SIP;
			if(lit)
				tank.drain(CITY_FUEL_SIP, true);
		}
		else
		{
			int demand = fuelPerPassAt(spool, flow);
			lit = tank.getFluidAmount() >= demand;
			if(lit)
				tank.drain(demand, true);
		}

		if(lit!=active)
		{
			active = lit;
			markContainingBlockForUpdate(null);
		}
		//The tank moved and so did the spool, and a chunk saved without them comes back holding gas
		//it has already burnt or claiming a rotor speed it never had. Once a second, master only.
		markDirty();
	}

	/**
	 * Hands the tick's flux to the terminals, split as evenly as whole units allow.
	 */
	private void pushOutput()
	{
		//City mode drops the output curve with the fuel curve: metering half a machine's power
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
	 * Re-reads what is standing on the deck above the alternator. That deck is the open course of
	 * the structure, so "put the connectors on top of the generator end" is the whole instruction a
	 * player needs, and it is visible from outside the machine.
	 */
	private void refreshTerminals()
	{
		TileEntity[] found = null;
		int count = 0;
		for(int w = 0; w < PetroleumGeometry.TURBINE_WIDTH; w++)
		{
			BlockPos deck = getBlockPosForPos(MultiblockGasTurbine.terminalPos(w)).up();
			TileEntity te = Utils.getExistingTileEntity(world, deck);
			if(!EnergyHelper.isFluxReceiver(te, EnumFacing.DOWN))
				continue;
			if(found==null)
				found = new TileEntity[PetroleumGeometry.TURBINE_WIDTH];
			found[count++] = te;
		}
		terminals = found==null?NO_TERMINALS: Arrays.copyOf(found, count);
	}

	/**
	 * Spreads passes across ticks by position, so a row of turbines never all settle their fuel on
	 * the same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = Math.floorMod(getPos().getX()^getPos().getZ()*31, BURN_INTERVAL);
		return stagger;
	}

	private void spawnPlume()
	{
		BlockPos stack = getBlockPosForPos(MultiblockGasTurbine.STACK_POS);
		//Rising out of the stack mouth, and faster the harder the machine is working, so the spool
		//is legible from across the base without anyone having to look at a number.
		world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
				stack.getX()+.5, stack.getY()+1.1, stack.getZ()+.5,
				0, .02+.08*spool/(double)SPOOL_FULL, 0);
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		//Read off the master rather than off whatever block was looked at: only the master runs the
		//spool, so a player pointing at the nacelle would otherwise be told a machine plainly
		//throwing out a plume was cold.
		TileEntityGasTurbine master = master();
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
			//Named rather than left as a falling percentage, because "why is it not making anything
			//while it is still turning" is the one question the machine has to answer out loud.
			return new String[]{TextFormatting.GRAY+"Coasting down"+TextFormatting.RESET,
					percent+"% -- no output"};
		//A cold turbine is either empty, holding something it will not take, switched off, or has
		//nowhere to put its flux. The tank readout and the redstone answer the first three, and the
		//bare deck answers the last.
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
		TileEntityGasTurbine master = master();
		if(master==null||!master.formed)
			return 0;
		//Fuel level, which is what anybody wiring a comparator to a generator wants to automate.
		return master.tank.getFluidAmount()*15/TANK_CAPACITY;
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The package is a box of boxes; there is no shape to carve.
		return null;
	}

	/**
	 * @return whether gas may be pushed into this part of the machine
	 */
	public static boolean isFuelPort(int pos)
	{
		//The skid course all round, plus the fuel skid itself. Generous on purpose: a gas line that
		//has to find one exact block of a forty-two block machine is a gas line the player gets
		//wrong, and there is nothing to be gained from that being fiddly.
		if(pos < 0)
			return false;
		return pos==MultiblockGasTurbine.MASTER_POS
				||pos < PetroleumGeometry.TURBINE_DEPTH*PetroleumGeometry.TURBINE_WIDTH;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityGasTurbine master = master();
		if(master!=null&&formed&&isFuelPort(pos))
			return new IFluidTank[]{master.tank};
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return resource!=null&&isValidFuel(resource.getFluid());
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Gas goes in and is burnt. Letting it back out would make the tank a free fluid store that
		//happens to also be a machine.
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the package is five different blocks, and
		//handing back forty-two oilfield frames would turn every disassembly into a free trade of
		//sheetmetal and engineering blocks for frames.
		int perLayer = PetroleumGeometry.TURBINE_DEPTH*PetroleumGeometry.TURBINE_WIDTH;
		ItemStack original = pos < 0?null
				: MultiblockGasTurbine.instance.getStructureManual()
				[pos/perLayer]
				[pos%perLayer/PetroleumGeometry.TURBINE_WIDTH]
				[pos%PetroleumGeometry.TURBINE_WIDTH];
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
		//description packet only goes out when the machine lights or goes out, so this costs one
		//integer on a packet that is already rare -- not a number on the wire every tick.
		nbt.setInteger("spool", spool);
	}
}
