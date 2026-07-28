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
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
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
import java.util.HashMap;
import java.util.Map;

/**
 * The Fuel Oil Boiler: the furnace half of a power station. Burns heavy fuels for steam, never
 * for flux.
 * <p>
 * The Industrial Burner already proved that heavy fuel oil is worth owning once something exists
 * that wants exactly it and nothing else does. This machine is that argument at power-plant
 * scale: heavy fuel oil converts to more steam per millibucket than either crude or diesel (see
 * {@link #getSteamYield}), which is what finally makes a tank of HFO worth more than the diesel
 * it sits next to on the shelf, rather than merely being legal to burn somewhere.
 * <p>
 * <strong>Steam, not flux, and capped hard.</strong> {@link #MAX_STEAM_OUTPUT} is exactly the
 * millibucket rate one Steam Turbine Hall is built to swallow -- one boiler feeds one hall, and
 * a second boiler on the same hall's intake is wasted fuel, not free extra power. The conversion
 * from a fuel to steam is a flat multiplier ({@link #steamFromFuel}) rather than anything
 * fuel-quality-dependent, so the whole machine's character lives in three constants and the cap.
 * <p>
 * <strong>The FE draw is a service load, not a generator's fuel.</strong> 120 FE/t runs the feed
 * pumps and the forced-draught fans; it does not appear in the steam output at all, and a boiler
 * starved of power does not fire even sitting on a full tank -- see {@link #runPass()}. Spent with
 * {@link FluxStorage#modifyEnergyStored}, never {@code extractEnergy}: that storage is built with
 * an extract limit of zero so nothing wired to the boiler can siphon its own buffer back out, and
 * the same limit would silently swallow the machine's own internal spend if it went through
 * {@code extractEnergy} instead -- the exact bug that once shipped in the pumpjack.
 * <p>
 * <strong>Demand-driven, like the burner.</strong> A boiler is never charged for a pass whose
 * steam has nowhere to go: {@link #runPass()} sizes the fuel it burns to the room actually left in
 * the steam tank before it ever touches the fuel tank or the energy buffer, so a boiler backed up
 * behind an idle turbine hall simply stops spending anything, rather than boiling steam it then
 * has to discard.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityFuelOilBoiler extends TileEntityMultiblockPart<TileEntityFuelOilBoiler>
		implements IIEInternalFluxHandler, IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		STEAM YIELD
	//	=================================

	/**
	 * Millibuckets of steam yielded per millibucket of fuel burned, keyed by fluid registry name.
	 * <p>
	 * Heavy fuel oil sits on top, and that is the entire point of the machine: HFO has no other
	 * consumer at all, so if the boiler does not make it clearly the best thing to shovel in then
	 * the heaviest useful cut off the column still has nowhere to go and this is just a Diesel
	 * Generator wearing a bigger silhouette.
	 * <p>
	 * It briefly did not. Diesel was pinned at 50 against HFO's 40, which made feeding a boiler
	 * refined diesel strictly better than feeding it the residue -- exactly backwards for a machine
	 * whose stated job is burning what nothing else wants. Diesel and crude were cut rather than
	 * HFO raised, so nothing's absolute output moved: burning a fuel that three other machines want
	 * is the one genuinely wasteful thing a player can do with it, and the boiler should charge for
	 * that rather than reward it. Crude is worst for the same reason {@code DieselHandler} charges
	 * it one -- refining it is obviously the better move.
	 */
	public static final int STEAM_YIELD_CRUDE_OIL = 20;
	public static final int STEAM_YIELD_HEAVY_FUEL_OIL = 40;
	public static final int STEAM_YIELD_DIESEL = 28;

	private static final Map<String, Integer> FUELS = new HashMap<String, Integer>();

	static
	{
		//Keyed by registry name rather than by Fluid so the table can be read -- and checked --
		//without a fluid registry, the same discipline the burner's own fuel table follows.
		registerFuel("ie_crude_oil", STEAM_YIELD_CRUDE_OIL);
		registerFuel("ie_heavy_fuel_oil", STEAM_YIELD_HEAVY_FUEL_OIL);
		registerFuel("ie_diesel", STEAM_YIELD_DIESEL);
	}

	/**
	 * @param fluidName the fluid's registry name
	 * @param yield     millibuckets of steam per millibucket burned; zero or less removes the fuel
	 */
	public static void registerFuel(String fluidName, int yield)
	{
		if(fluidName==null)
			return;
		if(yield > 0)
			FUELS.put(fluidName, yield);
		else
			FUELS.remove(fluidName);
	}

	/**
	 * @return millibuckets of steam a millibucket of the named fluid yields, or 0 if the boiler
	 * will not take it
	 */
	public static int getSteamYield(String fluidName)
	{
		Integer yield = fluidName==null?null: FUELS.get(fluidName);
		return yield==null?0: yield;
	}

	public static boolean isValidFuel(String fluidName)
	{
		return getSteamYield(fluidName) > 0;
	}

	public static boolean isValidFuel(@Nullable Fluid fluid)
	{
		return fluid!=null&&isValidFuel(fluid.getName());
	}

	/**
	 * The steam a volume of fuel yields, in millibuckets.
	 * <p>
	 * A flat multiplier rather than anything divided by a thousand: the pinned figures already are
	 * "millibuckets of steam per millibucket of fuel", so there is no unit conversion left to do,
	 * and folding one in here would just be a second place the constants could drift apart from
	 * what they say they mean.
	 */
	public static int steamFromFuel(int fuelMillibuckets, int yieldPerMb)
	{
		if(fuelMillibuckets <= 0||yieldPerMb <= 0)
			return 0;
		return fuelMillibuckets*yieldPerMb;
	}

	/**
	 * @return the most fuel a volume of steam room is worth burning, rounded down so a pass sized
	 * by this can never overflow the tank it is filling
	 */
	public static int fuelForSteamCap(int capSteamMillibuckets, int yieldPerMb)
	{
		if(capSteamMillibuckets <= 0||yieldPerMb <= 0)
			return 0;
		return capSteamMillibuckets/yieldPerMb;
	}

	/**
	 * The whole of one pass's fuel arithmetic in one pure function: how much fuel a pass actually
	 * burns, once the machine's own output cap, the room left in the steam tank and the fuel
	 * actually on hand have all been weighed against each other.
	 * <p>
	 * Kept static and free of the tile entity so the number a player sees in testing is provably
	 * the number the machine runs on, not merely something inspired by it.
	 *
	 * @param fuelAvailable millibuckets of fuel in the tank
	 * @param steamRoom     millibuckets of space left in the steam tank
	 * @param yieldPerMb    the fuel's steam yield, see {@link #getSteamYield}
	 * @return millibuckets of fuel to burn this pass; never more than {@link #MAX_STEAM_PER_PASS}
	 * is worth of steam, never more than the room available, never more than what is in the tank
	 */
	public static int fuelToBurn(int fuelAvailable, int steamRoom, int yieldPerMb)
	{
		if(fuelAvailable <= 0||yieldPerMb <= 0)
			return 0;
		int cap = Math.min(MAX_STEAM_PER_PASS, Math.max(0, steamRoom));
		int capFuel = fuelForSteamCap(cap, yieldPerMb);
		return Math.max(0, Math.min(fuelAvailable, capFuel));
	}

	//	=================================
	//		FIRING
	//	=================================

	/**
	 * Millibuckets of steam per tick, flat out. Not a taste number: it is exactly what one Steam
	 * Turbine Hall is sized to swallow, so a second boiler plumbed into the same hall is wasted
	 * fuel rather than free extra power, and a boiler plumbed into anything smaller simply never
	 * reaches this rate because nothing draws it down fast enough to make room.
	 */
	public static final int MAX_STEAM_OUTPUT = 300;
	/**
	 * Ticks between firing passes. The machine does no accounting in between; it works out a whole
	 * interval's worth of fuel, power and steam in a single go, exactly as the burner, the
	 * scrubber and the turbine do.
	 */
	public static final int BOIL_INTERVAL = 10;
	/**
	 * The most steam a single pass may put in the tank.
	 */
	public static final int MAX_STEAM_PER_PASS = MAX_STEAM_OUTPUT*BOIL_INTERVAL;

	/**
	 * The fuel tank. As deep as the burner's own, for the same reason: refuelling should be an
	 * occasional errand, not a chore that competes with actually watching the plant run.
	 */
	public static final int FUEL_TANK_CAPACITY = 24000;
	/**
	 * The steam tank. Two passes deep -- enough that a turbine hall's own throttling never starves
	 * the boiler mid-pass, not so deep that the boiler becomes a place to bank steam instead of a
	 * thing a hall draws from continuously.
	 */
	public static final int STEAM_TANK_CAPACITY = 2*MAX_STEAM_PER_PASS;

	/**
	 * FE per tick to run the feed pumps and the forced-draught fans. Small on purpose: this is a
	 * hotel load, not the machine's product, and charging anything heavier here would make the
	 * boiler compete with its own steam output for a wire's capacity.
	 */
	public static final int ENERGY_PER_TICK = 120;
	/**
	 * What one firing pass actually costs.
	 */
	public static final int ENERGY_PER_PASS = ENERGY_PER_TICK*BOIL_INTERVAL;
	/**
	 * Ten seconds' worth of the hotel load. Deep enough that a wire delivering the average rate
	 * never leaves the boiler an interval short on the tick a bill falls due, shallow enough that
	 * the buffer is honestly a buffer and not a way to run the plant off a battery.
	 */
	public static final int ENERGY_CAPACITY = ENERGY_PER_TICK*20*10;
	private static final int ENERGY_MAX_RECEIVE = 4096;

	/**
	 * City mode fuel and power sips: a token millibucket and a token FE per pass, so tanks and
	 * buffers still visibly run down and refuelling and rewiring still matter, but nothing is
	 * metered against demand. Same trade the burner, the turbine and the scrubber all make.
	 */
	private static final int CITY_FUEL_SIP = 1;
	private static final int CITY_ENERGY_SIP = 1;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];
	private static final int[] ENERGY_POS = {
			PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0, 0, 0),
			PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0, 0,
					PetroleumGeometry.BOILER_WIDTH-1),
			PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0,
					PetroleumGeometry.BOILER_DEPTH-1, 0),
			PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0,
					PetroleumGeometry.BOILER_DEPTH-1, PetroleumGeometry.BOILER_WIDTH-1)};

	/**
	 * The fuel tank. Only the firing floor accepts fills, and the tank itself refuses anything
	 * that is not a registered fuel.
	 */
	public final FluidTank tank = new FluidTank(FUEL_TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return isValidFuel(fluid==null?null: fluid.getFluid());
		}
	};
	/**
	 * The steam tank. It refuses every fill outright: steam only ever enters it from
	 * {@link #runPass()}, never from a pipe. A tank that instead trusted {@code canFillTankFrom}
	 * alone to keep foreign fluid out is exactly the mistake that once let a wellhead's own output
	 * be overwritten by whatever a misplumbed pipe happened to be carrying.
	 */
	public final FluidTank steamTank = new FluidTank(STEAM_TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return false;
		}
	};

	private final FluxStorage energyStorage = new FluxStorage(ENERGY_CAPACITY, ENERGY_MAX_RECEIVE, 0);
	private final IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Whether the boiler fired on its last pass. Synced: it drives the vent plume and the overlay.
	 */
	public boolean active;
	/**
	 * Millibuckets of steam per tick the last pass sustained. Synced for the same reason the
	 * burner syncs its heat rate: it is the one number that tells a player at a glance whether the
	 * fuel in the tank is worth what they think it is.
	 */
	public int steamRate;

	private int stagger = -1;

	/**
	 * The one block of the structure a comparator reads from: a front corner of the firing floor.
	 * <p>
	 * Every block used to answer, which turned the whole machine into one big comparator face and
	 * made redstone next to it behave differently from every other multiblock in the mod.
	 */
	public static final int REDSTONE_INDEX =
			PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0, 0, 0);

	public TileEntityFuelOilBoiler()
	{
		super(PetroleumGeometry.BOILER_SIZE);
	}

	//	=================================
	//		THE FIRING CONTRACT
	//	=================================

	/**
	 * @return whether the boiler is lit, asked of any block of the machine
	 */
	public boolean isFiring()
	{
		TileEntityFuelOilBoiler master = master();
		return master!=null&&master.formed&&master.active;
	}

	//	=================================
	//		RUNNING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote)
		{
			//Only the master knows whether the boiler is lit, and only its own block position is
			//needed to place the vent plume, so the other hundred and seventy-four do nothing.
			if(formed&&!isDummy()&&active&&world.getTotalWorldTime()%4==0)
			{
				BlockPos stack = getBlockPosForPos(MultiblockFuelOilBoiler.STEAM_POS);
				world.spawnParticle(EnumParticleTypes.CLOUD,
						stack.getX()+.5, stack.getY()+1.1, stack.getZ()+.5, 0, .04, 0);
			}
			return;
		}
		if(isDummy()||!formed)
			return;

		if((world.getTotalWorldTime()+getStagger())%BOIL_INTERVAL==0)
			runPass();
	}

	/**
	 * One firing pass: a whole interval's worth of fuel, power and steam settled in a single go.
	 */
	private void runPass()
	{
		boolean previouslyActive = active;
		int previousRate = steamRate;
		boolean lit = false;
		int rate = 0;

		FluidStack fuel = tank.getFluid();
		int yield = fuel!=null&&fuel.getFluid()!=null?getSteamYield(fuel.getFluid().getName()): 0;
		//Gated here, before either buffer is touched, so a cold tank -- yield 0 -- can never reach
		//an affordability check that reads "cost 0, stored 0, proceed": the exact shape of bug
		//that once let the industrial burner heat for free forever. A boiler with nothing worth
		//burning simply never gets far enough to ask what it costs.
		if(yield > 0&&tank.getFluidAmount() > 0)
		{
			if(CityMode.petroleum())
			{
				//City mode: the fire is a fact, not a simulation. Fuel and power are still
				//required -- a tank run dry or an unpowered boiler still goes cold, so the gesture
				//and the plant layout both survive -- but nothing is metered against demand, and
				//the tank is simply topped up towards whatever room it has left rather than
				//accounted against a real burn rate. Same trade the burner makes with its own
				//heat store.
				if(energyStorage.getEnergyStored() > 0)
				{
					energyStorage.modifyEnergyStored(-CITY_ENERGY_SIP);
					tank.drain(CITY_FUEL_SIP, true);
					int room = steamTank.getCapacity()-steamTank.getFluidAmount();
					if(room > 0)
						steamTank.fill(new FluidStack(IEContent.fluidSteam,
								Math.min(room, MAX_STEAM_PER_PASS)), true);
					lit = true;
					rate = MAX_STEAM_OUTPUT;
				}
			}
			//Not extractEnergy: this storage is built with an extract limit of zero so that nothing
			//wired to the boiler can siphon its buffer back out, and that limit applies to the
			//machine itself too. Going through it would mean the boiler could never spend a single
			//flux on its own pumps -- the exact bug that once shipped in the pumpjack.
			else if(energyStorage.getEnergyStored() >= ENERGY_PER_PASS)
			{
				//Sized against the steam tank before either buffer is spent: a boiler backed up
				//behind an idle turbine hall has nowhere to put more steam, so it is charged
				//nothing at all rather than boiling a pass it then has to throw away. The burner
				//makes the same call against its own heat store.
				int room = steamTank.getCapacity()-steamTank.getFluidAmount();
				int fuelBurned = fuelToBurn(tank.getFluidAmount(), room, yield);
				int steamProduced = steamFromFuel(fuelBurned, yield);
				if(fuelBurned > 0&&steamProduced > 0)
				{
					energyStorage.modifyEnergyStored(-ENERGY_PER_PASS);
					tank.drain(fuelBurned, true);
					steamTank.fill(new FluidStack(IEContent.fluidSteam, steamProduced), true);
					lit = true;
					rate = steamProduced/BOIL_INTERVAL;
				}
			}
		}

		active = lit;
		steamRate = rate;
		if(active!=previouslyActive||steamRate!=previousRate)
			markContainingBlockForUpdate(null);
		//The tanks and the energy buffer all moved, and a chunk saved without them comes back
		//holding fuel or power it already spent. Once a second, and only on the master.
		markDirty();
	}

	/**
	 * Spreads firing passes across ticks by position, so a row of boilers never all settle their
	 * fuel and power on the same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), BOIL_INTERVAL);
		return stagger;
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed)
			return null;
		TileEntityFuelOilBoiler master = master();
		if(master==null)
			return null;
		if(isFuelPort(pos))
			return new String[]{"Fuel oil in", statusLine(master)};
		if(isSteamPort(pos))
			return new String[]{"Steam out", statusLine(master)};
		return null;
	}

	private static String statusLine(TileEntityFuelOilBoiler master)
	{
		if(master.active)
			return TextFormatting.GOLD+"Firing"+TextFormatting.RESET
					+" -- "+master.steamRate+" mB/t";
		//A cold boiler is either empty, holding something it will not take, or starved of power,
		//and the tank readout and the wire connection already answer which.
		return TextFormatting.GRAY+"Cold"+TextFormatting.RESET;
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
		TileEntityFuelOilBoiler master = master();
		if(master==null||!master.formed)
			return 0;
		//Fuel level, which is what anybody wiring a comparator to a boiler wants to automate.
		return master.tank.getFluidAmount()*15/FUEL_TANK_CAPACITY;
	}

	//	=================================
	//		FLUX
	//	=================================

	private boolean isEnergyPos()
	{
		if(pos < 0)
			return false;
		for(int energyPos : ENERGY_POS)
			if(pos==energyPos)
				return true;
		return false;
	}

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		TileEntityFuelOilBoiler master = master();
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
	//		FLUID PORTS
	//	=================================

	/**
	 * @return whether fuel may be pushed into this part of the machine: the whole firing floor,
	 * generous on purpose so a fuel line has no one exact block of a hundred-and-seventy-five
	 * block machine to hunt for.
	 */
	public static boolean isFuelPort(int pos)
	{
		return pos >= 0&&PetroleumGeometry.heightOf(PetroleumGeometry.BOILER_SIZE, pos)==0;
	}

	/**
	 * @return whether steam may be drawn out of this part of the machine: the drum roof, and
	 * nothing below it, so a pipe run climbing the water wall can never pick up steam before it
	 * has actually been made.
	 */
	public static boolean isSteamPort(int pos)
	{
		return pos >= 0&&PetroleumGeometry.heightOf(PetroleumGeometry.BOILER_SIZE, pos)
				==PetroleumGeometry.BOILER_HEIGHT-1;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityFuelOilBoiler master = master();
		if(master==null||!formed)
			return NO_TANKS;
		if(isFuelPort(pos))
			return new IFluidTank[]{master.tank};
		if(isSteamPort(pos))
			return new IFluidTank[]{master.steamTank};
		//Plain water wall. Every face of it is deliberately inert: a pipe run climbing the boiler
		//must not pick up or feed either stream partway up.
		return NO_TANKS;
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		//Only the firing floor fills, and the tank itself refuses anything that is not a
		//registered fuel.
		return isFuelPort(pos);
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Only the drum roof drains. Letting the fuel tank drain would make it a free fluid store
		//that happens to also be a machine, and the steam tank additionally refuses every fill at
		//the tank level, so a pipe that somehow reached it still could not stuff foreign fluid
		//into a boiler's own output.
		return isSteamPort(pos);
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The boiler is built from full cubes; the shape comes from the
		//model, not from per-block bounds.
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the machine is built from four different
		//blocks, and handing back a hundred and seventy-five oilfield frames would turn every
		//disassembly into a free trade of brick, sheetmetal and heavy engineering for frames.
		int perLayer = PetroleumGeometry.BOILER_DEPTH*PetroleumGeometry.BOILER_WIDTH;
		ItemStack original = pos < 0?null
				: MultiblockFuelOilBoiler.instance.getStructureManual()
				[pos/perLayer]
				[pos%perLayer/PetroleumGeometry.BOILER_WIDTH]
				[pos%PetroleumGeometry.BOILER_WIDTH];
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
		active = nbt.getBoolean("active");
		steamRate = nbt.getInteger("steamRate");
		if(!descPacket)
		{
			tank.readFromNBT(nbt.getCompoundTag("tank"));
			steamTank.readFromNBT(nbt.getCompoundTag("steamTank"));
			energyStorage.readFromNBT(nbt);
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setBoolean("active", active);
		nbt.setInteger("steamRate", steamRate);
		//Both tanks and the energy buffer are kept out of the description packet on purpose: the
		//base class already sends one changing field a second while the machine runs, and nothing
		//on the client reads any of these three.
		if(!descPacket)
		{
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
			nbt.setTag("steamTank", steamTank.writeToNBT(new NBTTagCompound()));
			energyStorage.writeToNBT(nbt);
		}
	}
}
