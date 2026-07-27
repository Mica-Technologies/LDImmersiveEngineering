/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.tool.ExternalHeaterHandler;
import blusunrize.immersiveengineering.api.tool.ExternalHeaterHandler.HeatableAdapter;
import blusunrize.immersiveengineering.api.tool.ExternalHeaterHandler.IExternalHeatable;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockIndustrialBurner;
import blusunrize.immersiveengineering.common.util.CityMode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Industrial Burner: turns the fuels nothing else wants into process heat.
 * <p>
 * It makes no Flux at all, and that is the entire point. Heavy fuel oil is deliberately not a
 * Diesel Generator fuel -- it is the cheap, dirty residue left at the bottom of the distillation
 * column -- and a firebox is what makes owning a barrel of it better than owning nothing. Had
 * this machine produced IF it would simply have been a worse Diesel Generator, and every fuel
 * would have collapsed back onto one scale with diesel at the top of it.
 * <p>
 * <strong>The contract a neighbouring machine reads</strong> is small and deliberately does not
 * mention fluids or multiblocks: {@link #isBurning()}, {@link #getHeatRate()},
 * {@link #getStoredHeat()} and {@link #drawHeat(int, boolean)}. Any of the twenty-seven blocks
 * answers on behalf of the whole machine, so a consumer that finds a
 * {@code TileEntityIndustrialBurner} against one of its own faces needs no knowledge of where
 * the master is.
 * <p>
 * Heat is measured in heat units (HU). One HU is one tick of fire at one unit of intensity; the
 * absolute scale only matters relative to {@link #getHeatRate()}, which is what a consumer sizes
 * itself against.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityIndustrialBurner extends TileEntityMultiblockPart<TileEntityIndustrialBurner>
		implements IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		FUEL
	//	=================================

	/**
	 * Heat units yielded by a bucket of each fuel, keyed by fluid registry name.
	 * <p>
	 * The ordering is volumetric energy density -- energy per litre in the tank, not per kilogram
	 * -- because a tank holds millibuckets, and that is the axis on which heavy fuel oil actually
	 * beats the light cuts in real firing practice. Liquefied natural gas carries roughly 22 MJ/L,
	 * propane 25, diesel 36 and heavy fuel oil 40; the constants are those figures at 500 HU per
	 * MJ/L, which lands the whole table on round numbers.
	 * <p>
	 * So heavy fuel oil is not merely usable here, it is the best thing to put in, by a clear
	 * margin over diesel and by nearly double over gas. That has to be true for the machine to
	 * mean anything: heavy fuel oil has no other consumer at all, whereas every other fluid on
	 * this list is worth more somewhere else -- diesel in a generator or a drill, propane and
	 * natural gas in a generator. Feeding a burner diesel is legal, and it is a waste.
	 */
	public static final int HEAT_PER_BUCKET_NATURAL_GAS = 11000;
	public static final int HEAT_PER_BUCKET_PROPANE = 12500;
	public static final int HEAT_PER_BUCKET_DIESEL = 18000;
	public static final int HEAT_PER_BUCKET_HEAVY_FUEL_OIL = 20000;

	private static final Map<String, Integer> FUELS = new HashMap<String, Integer>();

	static
	{
		//Keyed by registry name rather than by Fluid so the table can be read -- and checked --
		//without a fluid registry, which is also how DieselHandler stores its own.
		registerFuel("natural_gas", HEAT_PER_BUCKET_NATURAL_GAS);
		registerFuel("propane", HEAT_PER_BUCKET_PROPANE);
		registerFuel("ie_diesel", HEAT_PER_BUCKET_DIESEL);
		registerFuel("ie_heavy_fuel_oil", HEAT_PER_BUCKET_HEAVY_FUEL_OIL);
	}

	/**
	 * @param fluidName     the fluid's registry name
	 * @param heatPerBucket heat units a full bucket of it yields; zero or less removes the fuel
	 */
	public static void registerFuel(String fluidName, int heatPerBucket)
	{
		if(fluidName==null)
			return;
		if(heatPerBucket > 0)
			FUELS.put(fluidName, heatPerBucket);
		else
			FUELS.remove(fluidName);
	}

	/**
	 * @return heat units a bucket of the named fluid yields, or 0 if the burner will not take it
	 */
	public static int getHeatPerBucket(String fluidName)
	{
		Integer heat = fluidName==null?null: FUELS.get(fluidName);
		return heat==null?0: heat;
	}

	public static boolean isValidFuel(String fluidName)
	{
		return getHeatPerBucket(fluidName) > 0;
	}

	public static boolean isValidFuel(@Nullable Fluid fluid)
	{
		return fluid!=null&&isValidFuel(fluid.getName());
	}

	/**
	 * @return heat units per tick the named fuel sustains at the burner's firing rate
	 */
	public static int getHeatRateFor(String fluidName)
	{
		return heatFromBurning(getHeatPerBucket(fluidName), FIRING_RATE);
	}

	/**
	 * @return the heat yielded by burning a volume of a fuel, in heat units
	 */
	public static int heatFromBurning(int heatPerBucket, int millibuckets)
	{
		if(heatPerBucket <= 0||millibuckets <= 0)
			return 0;
		return heatPerBucket*millibuckets/1000;
	}

	//	=================================
	//		FIRING
	//	=================================

	/**
	 * Millibuckets per tick at the burner's one firing rate. A firebox runs at the temperature it
	 * runs at; what changes between fuels is how much heat that volume carries, not how fast the
	 * valve is open. A bucket therefore lasts 250 ticks whatever it is, and a full tank a little
	 * over five minutes of continuous firing.
	 */
	public static final int FIRING_RATE = 4;
	/**
	 * Ticks between stoking passes. The machine does no fuel accounting in between; it works out
	 * a whole interval's worth in one go, exactly as the wellheads do.
	 */
	public static final int BURN_INTERVAL = 20;
	/**
	 * Millibuckets consumed by one full stoking pass.
	 */
	public static final int CHARGE = FIRING_RATE*BURN_INTERVAL;
	/**
	 * A tank deep enough that refuelling is an occasional errand rather than a chore, matching the
	 * diesel generator's.
	 */
	public static final int TANK_CAPACITY = 24000;
	/**
	 * The firebox's thermal mass, in heat units: two passes of the best fuel.
	 * <p>
	 * This is what makes the machine demand-driven without any demand tracking. A burner whose
	 * store is full has nothing to gain from stoking and so burns nothing; one being drawn from
	 * makes room every tick and so keeps firing. Nothing has to tell it whether a consumer is
	 * attached -- the store answers that by itself -- and a burner left running with its tower
	 * switched off quietly stops eating fuel.
	 */
	public static final int HEAT_CAPACITY = 2*HEAT_PER_BUCKET_HEAVY_FUEL_OIL*CHARGE/1000;

	/**
	 * City mode fuel sip: a token millibucket per pass, so tanks still visibly empty and
	 * refuelling still matters, but nothing is metered. Same trade the diesel generator makes.
	 */
	private static final int CITY_FUEL_SIP = 1;

	/**
	 * Heat units a vanilla furnace's heater adapter is charged per unit of Flux it asks for.
	 * <p>
	 * {@link ExternalHeaterHandler} speaks in Flux and this machine does not, so the two are
	 * bridged one to one. At that rate a furnace kept lit costs {@code heater_consumption} per
	 * tick and one running at doubled speed costs four times that again, which is what makes a
	 * burner on heavy fuel oil worth about two furnaces at full tilt -- a fair return for a
	 * twenty-seven block machine, and self-limiting if more are stacked on top of it.
	 */
	private static final int HEAT_PER_FURNACE_FLUX = 1;

	public final FluidTank tank = new FluidTank(TANK_CAPACITY);

	/**
	 * Stored heat, in heat units. Master only; the dummies delegate.
	 */
	private int heatBuffer;
	/**
	 * Heat units per tick the current fuel sustains. Synced, because it is what the overlay reads
	 * and what tells a player at a glance whether they put the right thing in the tank.
	 */
	private int heatRate;
	/**
	 * Whether the fire is lit. Synced: it drives the smoke from the flue and the overlay.
	 */
	public boolean active;

	private int stagger = -1;
	/**
	 * What is sitting on the crown waiting to be heated, resolved on the stoking interval and
	 * held in between. Scanning nine positions every tick to find a furnace that has not moved
	 * would be exactly the sort of poll this machine is meant not to do.
	 */
	/**
	 * The tower this firebox is fuelling, if any. Cached on the stoking interval.
	 */
	private TileEntityDistillationTower towerTarget;
	private TileEntity[] heatTargets = new TileEntity[0];

	/**
	 * The one block of the structure a comparator reads from: a front corner of the firebox.
	 * <p>
	 * Every block used to answer, which turned the whole machine into one big comparator face
	 * and made redstone next to it behave differently from every other multiblock in the mod.
	 */
	public static final int REDSTONE_INDEX = PetroleumGeometry.structureIndex(PetroleumGeometry.BURNER_SIZE, 0, 0, 0);

	public TileEntityIndustrialBurner()
	{
		super(PetroleumGeometry.BURNER_SIZE);
	}

	//	=================================
	//		THE HEAT CONTRACT
	//	=================================

	/**
	 * @return whether the firebox is lit and has heat to give, asked of any block of the machine
	 */
	public boolean isBurning()
	{
		TileEntityIndustrialBurner master = master();
		return master!=null&&master.formed&&master.active;
	}

	/**
	 * The intensity of the fire, in heat units per tick, which is the rate a consumer can expect
	 * to be able to sustain. Zero when the machine is cold.
	 * <p>
	 * A consumer that needs more than this can still take it -- {@link #getStoredHeat()} is real
	 * heat and can be spent faster than it is made -- but it will run the store down and then run
	 * at this rate instead. Sizing against this value is the way to run continuously.
	 */
	public int getHeatRate()
	{
		TileEntityIndustrialBurner master = master();
		return master!=null&&master.formed?master.heatRate: 0;
	}

	/**
	 * @return heat units available to take right now
	 */
	public int getStoredHeat()
	{
		TileEntityIndustrialBurner master = master();
		return master!=null&&master.formed?master.heatBuffer: 0;
	}

	/**
	 * Takes heat out of the firebox.
	 *
	 * @param max      the most the caller can use this tick, in heat units
	 * @param simulate true to ask without taking
	 * @return how much was granted, never more than {@code max}
	 */
	public int drawHeat(int max, boolean simulate)
	{
		TileEntityIndustrialBurner master = master();
		if(master==null||!master.formed||max <= 0)
			return 0;
		int drawn = Math.min(max, master.heatBuffer);
		if(drawn > 0&&!simulate)
			master.heatBuffer -= drawn;
		return drawn;
	}

	//	=================================
	//		BURNING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote)
		{
			//Only the master knows whether the fire is lit, and only the master's own block
			//position is needed to place the plume, so the other twenty-six do nothing at all.
			if(formed&&!isDummy()&&active&&world.getTotalWorldTime()%4==0)
			{
				BlockPos flue = getBlockPosForPos(MultiblockIndustrialBurner.FLUE_POS);
				world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
						flue.getX()+.5, flue.getY()+1.1, flue.getZ()+.5, 0, .02, 0);
			}
			return;
		}
		if(isDummy()||!formed)
			return;

		if((world.getTotalWorldTime()+getStagger())%BURN_INTERVAL==0)
			runPass();
		//Furnaces have to be fed every tick: their heater adapter adds at most four burn ticks per
		//call and a lit furnace spends one per tick, so anything slower would cool it down rather
		//than keep it going. The scan for them is still on the interval -- this is a walk of an
		//array that is almost always empty.
		if(heatTargets.length > 0&&heatBuffer > 0)
			heatCrown();
	}

	/**
	 * One stoking pass: work out an interval's worth of fuel and heat in a single go.
	 */
	private void runPass()
	{
		refreshHeatTargets();

		FluidStack fuel = tank.getFluid();
		int heatPerBucket = fuel!=null&&fuel.getFluid()!=null
				?getHeatPerBucket(fuel.getFluid().getName()): 0;
		int previousRate = heatRate;
		heatRate = heatFromBurning(heatPerBucket, FIRING_RATE);

		if(CityMode.petroleum())
		{
			//City mode: the fire is a fact, not a simulation. Fuel is still required and a tank
			//still empties, so a burner nobody refuels still goes out and the gesture survives --
			//but nothing is metered against demand and the store is simply held full. Same trade
			//the diesel generator makes with its own fuel.
			if(heatPerBucket > 0&&tank.getFluidAmount() >= CITY_FUEL_SIP)
			{
				tank.drain(CITY_FUEL_SIP, true);
				heatBuffer = HEAT_CAPACITY;
			}
			else
				heatRate = 0;
			heatAdjacentTower();
			finishPass(previousRate);
			return;
		}

		//Only stoke when there is somewhere for the heat to go. A full store means nothing has
		//been drawing, and a fire that nobody is using should not be eating a bucket a minute.
		if(heatPerBucket > 0&&HEAT_CAPACITY-heatBuffer >= heatFromBurning(heatPerBucket, CHARGE))
		{
			int burnt = Math.min(CHARGE, tank.getFluidAmount());
			if(burnt > 0)
			{
				tank.drain(burnt, true);
				heatBuffer = Math.min(HEAT_CAPACITY, heatBuffer+heatFromBurning(heatPerBucket, burnt));
			}
		}
		heatAdjacentTower();
		finishPass(previousRate);
	}

	private void finishPass(int previousRate)
	{
		boolean lit = heatBuffer > 0&&heatRate > 0;
		if(lit!=active||heatRate!=previousRate)
		{
			active = lit;
			markContainingBlockForUpdate(null);
		}
		//The tank and the store both moved, and a chunk saved without them comes back holding
		//fuel or heat it has already spent. Once a second, and only on the master.
		markDirty();
	}

	/**
	 * Feeds whatever is sitting on the crown. Vanilla furnaces are the case this exists for: it
	 * gives the burner a job from the moment it is built, rather than leaving it inert until
	 * something is built that speaks its own heat contract.
	 */
	private void heatCrown()
	{
		//Never more than the fire actually sustains, so a crown stacked with furnaces degrades
		//into all of them running slowly rather than into the store being drained flat in a tick
		//and the whole row flickering.
		int budget = Math.min(heatBuffer, heatRate)/HEAT_PER_FURNACE_FLUX;
		if(budget <= 0)
			return;
		int spent = 0;
		for(TileEntity target : heatTargets)
		{
			if(target==null||target.isInvalid())
				continue;
			int consumed;
			if(target instanceof IExternalHeatable)
				consumed = ((IExternalHeatable)target).doHeatTick(budget-spent, false);
			else
			{
				HeatableAdapter adapter = ExternalHeaterHandler.getHeatableAdapter(target.getClass());
				consumed = adapter==null?0: adapter.doHeatTick(target, budget-spent, false);
			}
			if(consumed > 0)
			{
				spent += consumed;
				if(spent >= budget)
					break;
			}
		}
		if(spent > 0)
			heatBuffer -= Math.min(heatBuffer, spent*HEAT_PER_FURNACE_FLUX);
	}

	/**
	 * Fires the reboiler of any Distillation Tower stood against the firebox.
	 * <p>
	 * Deliberately from the side rather than the crown: the crown is where a furnace goes, and a
	 * twelve-block column balanced on top of a firebox would be a silly thing to ask anyone to
	 * build. Beside it is also how a real one is arranged.
	 * <p>
	 * The tower's contract is one-way and decays on its own, so this only has to keep saying
	 * "there is fire under you" while the fire is lit. A burner that goes out or is torn down
	 * needs to tell nobody -- the tower simply stops being heated a few seconds later.
	 */
	private void heatAdjacentTower()
	{
		if(towerTarget==null||towerTarget.isInvalid())
			return;
		//Do not spend fuel heating a tower with nothing to distil.
		if(!towerTarget.isDistilling())
			return;
		//A cold firebox heats nothing. Without this an unfuelled burner charges zero and
		//passes the affordability check trivially, handing the tower a permanent free
		//rebate while reporting itself as cold.
		if(!isBurning())
			return;
		//A whole interval's worth, charged in one go, matching how the fuel for it was burnt.
		int cost = heatRate*BURN_INTERVAL;
		if(heatBuffer < cost)
			return;
		//Grant longer than the interval so a pass that lands late does not let the column go
		//cold for a tick; the tower clamps and decays this on its own, so overshooting is free.
		if(towerTarget.supplyProcessHeat(BURN_INTERVAL*2))
			heatBuffer -= cost;
	}

	/**
	 * Re-reads what is standing on the crown. The crown is the working face of the machine, so
	 * "set a furnace on top of the burner" is the whole instruction a player needs.
	 */
	private void refreshHeatTargets()
	{
		refreshTowerTarget();
		List<TileEntity> found = null;
		for(int l = 0; l < PetroleumGeometry.BURNER_DEPTH; l++)
			for(int w = 0; w < PetroleumGeometry.BURNER_WIDTH; w++)
			{
				BlockPos above = getBlockPosForPos(PetroleumGeometry.structureIndex(
						PetroleumGeometry.BURNER_SIZE, PetroleumGeometry.BURNER_HEIGHT-1, l, w)).up();
				TileEntity te = Utils.getExistingTileEntity(world, above);
				if(te==null||!isHeatable(te))
					continue;
				if(found==null)
					found = new ArrayList<TileEntity>(4);
				found.add(te);
			}
		heatTargets = found==null?new TileEntity[0]: found.toArray(new TileEntity[found.size()]);
	}

	/**
	 * Looks around the firebox wall for a tower to fire. Resolved on the stoking interval and
	 * cached, like the crown targets, so the per-tick path never touches the world.
	 */
	private void refreshTowerTarget()
	{
		towerTarget = null;
		int chamber = PetroleumGeometry.BURNER_HEIGHT/2;
		for(int l = 0; l < PetroleumGeometry.BURNER_DEPTH&&towerTarget==null; l++)
			for(int w = 0; w < PetroleumGeometry.BURNER_WIDTH&&towerTarget==null; w++)
			{
				BlockPos cell = getBlockPosForPos(PetroleumGeometry.structureIndex(
						PetroleumGeometry.BURNER_SIZE, chamber, l, w));
				for(EnumFacing side : EnumFacing.HORIZONTALS)
				{
					TileEntity te = Utils.getExistingTileEntity(world, cell.offset(side));
					if(te instanceof TileEntityDistillationTower)
					{
						towerTarget = (TileEntityDistillationTower)te;
						break;
					}
				}
			}
	}

	private static boolean isHeatable(TileEntity te)
	{
		return te instanceof IExternalHeatable
				||ExternalHeaterHandler.getHeatableAdapter(te.getClass())!=null;
	}

	/**
	 * Spreads stoking passes across ticks by position, so a row of burners never all fire on the
	 * same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), BURN_INTERVAL);
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
		if(isBurning())
			return new String[]{TextFormatting.GOLD+"Firing"+TextFormatting.RESET,
					getHeatRate()+" HU/t"};
		//A cold burner is either empty or holding something it will not take, and the tank readout
		//the player already gets from the fluid handler answers which.
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
		TileEntityIndustrialBurner master = master();
		if(master==null||!master.formed)
			return 0;
		//Fuel level, which is what anybody wiring a comparator to a firebox wants to automate.
		return master.tank.getFluidAmount()*15/TANK_CAPACITY;
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". A firebox is a solid box; there is no shape to carve.
		return null;
	}

	/**
	 * @return whether fuel may be pushed into this part of the machine
	 */
	public static boolean isFuelPort(int pos)
	{
		//The hearth course all round, plus the burner head itself. Generous on purpose: a fuel
		//line that has to find one exact block of a twenty-seven block machine is a fuel line the
		//player gets wrong, and there is nothing to be gained from that being fiddly.
		if(pos < 0)
			return false;
		return pos==MultiblockIndustrialBurner.MASTER_POS
				||pos < PetroleumGeometry.BURNER_DEPTH*PetroleumGeometry.BURNER_WIDTH;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityIndustrialBurner master = master();
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
		//Fuel goes in and is burnt. Letting it back out would make the tank a free fluid store
		//that happens to also be a machine.
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the machine is three different blocks,
		//and handing back twenty-seven oilfield frames would turn every disassembly into a free
		//trade of brick and sheetmetal for frames.
		ItemStack original = pos < 0?null
				: MultiblockIndustrialBurner.instance.getStructureManual()
				[pos/(PetroleumGeometry.BURNER_DEPTH*PetroleumGeometry.BURNER_WIDTH)]
				[pos%(PetroleumGeometry.BURNER_DEPTH*PetroleumGeometry.BURNER_WIDTH)/PetroleumGeometry.BURNER_WIDTH]
				[pos%PetroleumGeometry.BURNER_WIDTH];
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
		heatRate = nbt.getInteger("heatRate");
		if(!descPacket)
			heatBuffer = nbt.getInteger("heatBuffer");
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		nbt.setBoolean("active", active);
		nbt.setInteger("heatRate", heatRate);
		//The store moves every tick a consumer draws. Nobody can see it, so it is saved but never
		//sent -- a description packet a second carrying one changing integer is not worth it.
		if(!descPacket)
			nbt.setInteger("heatBuffer", heatBuffer);
	}
}
