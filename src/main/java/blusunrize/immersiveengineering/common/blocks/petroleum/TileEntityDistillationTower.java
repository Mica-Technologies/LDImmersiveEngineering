/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.crafting.DistillationRecipe;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockDistillationTower;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Distillation Tower: splits crude into its cuts, drawn off at heights matching the column
 * order.
 * <p>
 * One crude tank at the foot of the column and one tank per cut, each reachable only from the
 * height its cut belongs to -- gas off the top, residue out of the bottom. That is the machine's
 * only real mechanic: a finished plant is legible from across the map because the pipe leaving
 * each height can only be carrying one thing, and getting the plumbing right is a spatial puzzle
 * rather than a matter of reading a GUI. {@link MultiblockDistillationTower#drawHeight} owns the
 * mapping, and the visible nozzles are placed from that same function, so what the tower looks
 * like and what it will actually hand over can never disagree.
 * <p>
 * Because the cuts come out several at a time, a batch is only started when every one of them has
 * somewhere to go. Half a run cannot be produced: the process queue's own per-tick check refuses
 * to advance a batch whose output tanks have filled up in the meantime, so a tower that runs out
 * of room stalls with its crude intact rather than jamming with a fraction of a run made.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityDistillationTower
		extends TileEntityMultiblockMetal<TileEntityDistillationTower, DistillationRecipe>
		implements IBlockOverlayText
{
	public static final int CUT_COUNT = MultiblockDistillationTower.CUT_COUNT;
	/**
	 * The crude tank. Deep enough that a well feeding the tower through a single pipe never runs
	 * it dry between batches.
	 */
	public static final int TANK_FEED = 0;
	/**
	 * Cut {@code i} lives in tank {@code TANK_FIRST_CUT+i}, in the recipe's own order.
	 */
	public static final int TANK_FIRST_CUT = 1;
	public static final int FEED_CAPACITY = 24000;
	/**
	 * Per cut. The lightest cut of the shipped recipe is a tenth of the heaviest by volume, so
	 * sizing every tank the same wastes a little space and saves the player from having to know
	 * which cuts fill fastest.
	 */
	public static final int CUT_CAPACITY = 8000;
	public static final int ENERGY_CAPACITY = 32000;
	/**
	 * How much of the flux cost external process heat pays for, as a percentage. Distillation is
	 * mostly heat and only incidentally work, so a tower with a firebox under it should be
	 * markedly cheaper to run than one boiling its own feed with resistance heating.
	 */
	public static final int HEATED_ENERGY_REBATE = 60;
	/**
	 * The longest a single delivery of heat can keep the column warm. A heat source has to keep
	 * proving it is there, the same way the pumpjack's {@code setPumped} flag decays.
	 */
	public static final int MAX_HEAT_TICKS = 200;
	/**
	 * City mode: what a tick of distilling costs when power is presence rather than accounting.
	 */
	private static final int CITY_SIP = 1;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];
	private static final int[] NO_SLOTS = new int[0];
	private static final NonNullList<ItemStack> NO_INVENTORY = NonNullList.create();
	private static final int[] OUTPUT_TANKS = outputTanks();

	private static final int[] ENERGY_POS = {
			PetroleumGeometry.structureIndex(PetroleumGeometry.TOWER_SIZE, 0, 0, 0),
			PetroleumGeometry.structureIndex(PetroleumGeometry.TOWER_SIZE, 0, 0,
					PetroleumGeometry.TOWER_WIDTH-1),
			PetroleumGeometry.structureIndex(PetroleumGeometry.TOWER_SIZE, 0,
					PetroleumGeometry.TOWER_DEPTH-1, 0),
			PetroleumGeometry.structureIndex(PetroleumGeometry.TOWER_SIZE, 0,
					PetroleumGeometry.TOWER_DEPTH-1, PetroleumGeometry.TOWER_WIDTH-1)};
	private static final int[] REDSTONE_POS = {
			PetroleumGeometry.structureIndex(PetroleumGeometry.TOWER_SIZE, 0, 0, 1)};

	public final FluidTank[] tanks = buildTanks();
	/**
	 * Ticks of process heat left in hand. Master only; see {@link #supplyProcessHeat}.
	 */
	private int heatTicks;

	public TileEntityDistillationTower()
	{
		super(MultiblockDistillationTower.instance, PetroleumGeometry.TOWER_SIZE,
				ENERGY_CAPACITY, true);
	}

	//	=================================
	//		TANKS
	//	=================================

	private static int[] outputTanks()
	{
		int[] out = new int[CUT_COUNT];
		for(int cut = 0; cut < CUT_COUNT; cut++)
			out[cut] = TANK_FIRST_CUT+cut;
		return out;
	}

	/**
	 * Every tank knows what belongs in it.
	 * <p>
	 * This is what makes the multiblock's generic output plumbing route seven fluids correctly
	 * without a line of special-case code: it hands each product to the first output tank that
	 * will take all of it, and because a cut tank refuses anything that is not its own cut, the
	 * first such tank is always the right one. The same property makes the queue's "is there room
	 * for this?" check exact rather than approximate, which is precisely the check that stops a
	 * half-finished run.
	 */
	private static FluidTank[] buildTanks()
	{
		FluidTank[] built = new FluidTank[1+CUT_COUNT];
		built[TANK_FEED] = new FluidTank(FEED_CAPACITY)
		{
			@Override
			public boolean canFillFluidType(FluidStack fluid)
			{
				//Matched ignoring amount: a pipe delivering a few millibuckets at a time is still
				//delivering crude, and refusing it until a full batch arrived would deadlock.
				return !DistillationRecipe.findIncompleteRecipes(fluid).isEmpty();
			}
		};
		for(int cut = 0; cut < CUT_COUNT; cut++)
			built[TANK_FIRST_CUT+cut] = new CutTank(cut);
		return built;
	}

	private static class CutTank extends FluidTank
	{
		private final int cut;

		CutTank(int cut)
		{
			super(CUT_CAPACITY);
			this.cut = cut;
		}

		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return isCutOf(cut, fluid);
		}
	}

	/**
	 * @return whether any registered recipe yields this fluid as its {@code cut}-th product
	 */
	public static boolean isCutOf(int cut, @Nullable FluidStack fluid)
	{
		if(fluid==null||cut < 0)
			return false;
		for(int i = 0; i < DistillationRecipe.recipeList.size(); i++)
		{
			FluidStack[] outputs = DistillationRecipe.recipeList.get(i).outputs;
			if(cut < outputs.length&&outputs[cut]!=null&&outputs[cut].isFluidEqual(fluid))
				return true;
		}
		return false;
	}

	/**
	 * @return the layer of the structure this block sits in, or -1 if it is not part of one
	 */
	private int structureHeight()
	{
		return pos < 0?-1: PetroleumGeometry.heightOf(PetroleumGeometry.TOWER_SIZE, pos);
	}

	//	=================================
	//		DISTILLING
	//	=================================

	@Override
	public void update()
	{
		//Ticks the queue, and de-ticks the dummies on the way past.
		super.update();
		if(world.isRemote||isDummy())
			return;
		if(heatTicks > 0)
			heatTicks--;
		rebateOperatingCost();
		if(isRSDisabled())
			return;
		//Everything below is the search for a new batch, which the base class throttles for us:
		//a running tower only tops its queue up every few ticks, and in city mode an idle one is
		//throttled too rather than re-scanning the recipe list forever.
		if(processQueue.size() >= getProcessQueueMaxLength()
				||!shouldScanForRecipe(processQueue.isEmpty()))
			return;
		if(energyStorage.getEnergyStored() <= 0||tanks[TANK_FEED].getFluidAmount() <= 0)
			return;
		DistillationRecipe recipe = DistillationRecipe.findRecipe(tanks[TANK_FEED].getFluid());
		if(recipe==null||!hasRoomForEveryCut(recipe))
			return;
		MultiblockProcessInMachine<DistillationRecipe> process =
				new MultiblockProcessInMachine<>(recipe).setInputTanks(TANK_FEED);
		if(addProcessToQueue(process, true))
		{
			addProcessToQueue(process, false);
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	/**
	 * Whether a whole run of this recipe would fit.
	 * <p>
	 * Checked before the batch starts, not after: a column that produced its light ends and then
	 * found nowhere to put the residue would have to either destroy it or wedge itself, and both
	 * of those are worse than simply not starting.
	 */
	public boolean hasRoomForEveryCut(DistillationRecipe recipe)
	{
		for(int cut = 0; cut < recipe.outputs.length; cut++)
		{
			FluidStack output = recipe.outputs[cut];
			if(output==null||output.amount <= 0)
				continue;
			//A recipe with more cuts than the column has draw ports has no honest place to put the
			//extra ones, so it is never started rather than quietly losing them.
			if(cut >= CUT_COUNT||tanks[TANK_FIRST_CUT+cut].fill(output, false)!=output.amount)
				return false;
		}
		return true;
	}

	/**
	 * Hands back part of what the queue just spent on this tick.
	 * <p>
	 * The per-tick cost lives inside the shared process object and there is no hook to lower it,
	 * so a discount has to be a refund. The visible effect is the same -- the tower draws less
	 * from the grid -- and it keeps both discounts in one place instead of forking the recipe or
	 * the process class. Safe against the multi-tick accelerator that watches average insertion,
	 * because distillation recipes never opt into it.
	 */
	private void rebateOperatingCost()
	{
		if(tickedProcesses <= 0||processQueue.isEmpty())
			return;
		int spent = processQueue.get(0).energyPerTick;
		int rebate;
		if(CityMode.petroleum())
			//Presence, not consumption -- the same trade the pumpjack and the virtual grid make.
			//An unpowered tower still stops, because the queue will not tick without the full cost
			//in the buffer; it just does not stay spent.
			rebate = spent-CITY_SIP;
		else if(isHeated())
			rebate = spent*HEATED_ENERGY_REBATE/100;
		else
			return;
		if(rebate > 0)
			energyStorage.receiveEnergy(rebate, false);
	}

	//	=================================
	//		PROCESS HEAT
	//	=================================

	/**
	 * Offers the column process heat from an outside source, such as a firebox burning the cuts
	 * nothing else wants.
	 * <p>
	 * The contract is deliberately one-way and stateless from the heater's side: it says "there is
	 * fire under this thing for the next {@code ticks} ticks" and the column decides what that is
	 * worth ({@link #HEATED_ENERGY_REBATE} off the flux cost while it lasts). The heat is not
	 * stored, cannot be stockpiled beyond {@link #MAX_HEAT_TICKS}, and decays on its own, so a
	 * heater that is destroyed or starved stops mattering within a few seconds without anything
	 * having to notice it went away. May be called on any block of the structure.
	 *
	 * @param ticks how long this delivery keeps the column warm
	 * @return whether the tower took it; false means the structure is gone or not formed, and the
	 * heater should stop wasting fuel on it. Ask {@link #isDistilling()} first if the heater wants
	 * to avoid firing at an idle column.
	 */
	public boolean supplyProcessHeat(int ticks)
	{
		if(ticks <= 0)
			return false;
		TileEntityDistillationTower master = master();
		if(master==null||!master.formed)
			return false;
		master.heatTicks = Math.min(MAX_HEAT_TICKS, Math.max(master.heatTicks, ticks));
		return true;
	}

	/**
	 * @return whether the column is currently running on external heat
	 */
	public boolean isHeated()
	{
		TileEntityDistillationTower master = master();
		return master!=null&&master.heatTicks > 0;
	}

	/**
	 * @return whether there is a batch in progress; safe on either side, since the process queue
	 * rides along in the description packet
	 */
	public boolean isDistilling()
	{
		TileEntityDistillationTower master = master();
		return master!=null&&!master.processQueue.isEmpty();
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed)
			return null;
		int height = structureHeight();
		if(height==MultiblockDistillationTower.FEED_LAYER)
			return new String[]{"Crude feed", statusLine()};
		int cut = MultiblockDistillationTower.cutAtHeight(height);
		if(cut < 0)
			return null;
		//Which cut leaves at this height is static, so it needs no synced state -- and it is the
		//one thing a player standing at a nozzle with a pipe in hand actually wants to know.
		String name = cutName(cut);
		return new String[]{name==null?"Draw port": name, statusLine()};
	}

	private String statusLine()
	{
		return isDistilling()?TextFormatting.GREEN+"Distilling"+TextFormatting.RESET
				: TextFormatting.YELLOW+"Idle"+TextFormatting.RESET;
	}

	@Nullable
	private static String cutName(int cut)
	{
		for(int i = 0; i < DistillationRecipe.recipeList.size(); i++)
		{
			FluidStack[] outputs = DistillationRecipe.recipeList.get(i).outputs;
			if(cut < outputs.length&&outputs[cut]!=null)
				return outputs[cut].getLocalizedName();
		}
		return null;
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(!isRedstonePos())
			return 0;
		TileEntityDistillationTower master = master();
		if(master==null)
			return 0;
		FluidTank feed = master.tanks[TANK_FEED];
		if(feed.getFluidAmount() <= 0)
			return 0;
		//A tower with crude in it must never read the same as an empty one, however little is left.
		return Math.max(1, 15*feed.getFluidAmount()/feed.getCapacity());
	}

	//	=================================
	//		FLUID ACCESS
	//	=================================

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityDistillationTower master = master();
		if(master==null||!formed)
			return NO_TANKS;
		int height = structureHeight();
		if(height==MultiblockDistillationTower.FEED_LAYER)
			return new IFluidTank[]{master.tanks[TANK_FEED]};
		int cut = MultiblockDistillationTower.cutAtHeight(height);
		//Plain shell. Every side of it is deliberately inert: a pipe run climbing the tower must
		//not pick up whatever cut happens to be nearest.
		if(cut < 0)
			return NO_TANKS;
		return new IFluidTank[]{master.tanks[TANK_FIRST_CUT+cut]};
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		//Only the feed deck fills, and the tank itself refuses anything no recipe wants.
		return structureHeight()==MultiblockDistillationTower.FEED_LAYER;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Only the nozzle heights drain, and each of them exposes exactly one tank.
		return MultiblockDistillationTower.cutAtHeight(structureHeight()) >= 0;
	}

	@Override
	public IFluidTank[] getInternalTanks()
	{
		return tanks;
	}

	@Override
	public int[] getOutputTanks()
	{
		return OUTPUT_TANKS;
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The structure is made of full cubes; the shape comes from the
		//model, not from per-block bounds.
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the column is built from three different
		//blocks, and handing back a hundred-odd frames would turn every disassembly into a free
		//trade of sheetmetal and scaffolding for frames.
		int cells = PetroleumGeometry.TOWER_HEIGHT*PetroleumGeometry.TOWER_DEPTH
				*PetroleumGeometry.TOWER_WIDTH;
		ItemStack original = pos < 0||pos >= cells?null
				: MultiblockDistillationTower.instance.getStructureManual()
				[structureHeight()]
				[pos%(PetroleumGeometry.TOWER_DEPTH*PetroleumGeometry.TOWER_WIDTH)/PetroleumGeometry.TOWER_WIDTH]
				[pos%PetroleumGeometry.TOWER_WIDTH];
		//An unformed block dropped in the world is always a frame; that is the only part of the
		//machine that is a placeable item in its own right.
		return original==null?new ItemStack(IEContent.blockPetroleumDevice, 1,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()): original.copy();
	}

	@Override
	public int[] getEnergyPos()
	{
		//All four corners of the deck. The column is square and looks the same from every side, so
		//making the player guess which corner is the back would be a puzzle with no content.
		return ENERGY_POS;
	}

	@Override
	public int[] getRedstonePos()
	{
		return REDSTONE_POS;
	}

	@Override
	public boolean isInWorldProcessingMachine()
	{
		return false;
	}

	@Override
	public boolean additionalCanProcessCheck(MultiblockProcess<DistillationRecipe> process)
	{
		//The feed cannot be drained from outside, so this only ever fails if the structure was
		//tampered with; it costs nothing and it means a batch can never be finished out of crude
		//that is no longer there.
		FluidStack input = process.recipe.input;
		if(input==null)
			return true;
		FluidStack held = tanks[TANK_FEED].getFluid();
		return held!=null&&held.containsFluid(input);
	}

	@Override
	public void doProcessOutput(ItemStack output)
	{
		//Distillation yields fluids only.
	}

	@Override
	public void doProcessFluidOutput(FluidStack output)
	{
		//Unreachable: every cut has a dedicated tank, so the generic path never falls through to
		//here. Dropping the fluid is still the right answer if a recipe ever declares a cut the
		//column has no port for -- that recipe is refused before it can start.
	}

	@Override
	public void onProcessFinish(MultiblockProcess<DistillationRecipe> process)
	{
		markDirty();
		markContainingBlockForUpdate(null);
	}

	@Override
	public int getMaxProcessPerTick()
	{
		return 1;
	}

	@Override
	public int getProcessQueueMaxLength()
	{
		//One batch at a time. A second queued run would have to be checked for room against tanks
		//the first run has not filled yet, which is exactly the guess that lets a column jam.
		return 1;
	}

	@Override
	public float getMinProcessDistance(MultiblockProcess<DistillationRecipe> process)
	{
		return 0;
	}

	@Override
	public DistillationRecipe findRecipeForInsertion(ItemStack inserting)
	{
		return null;
	}

	@Override
	protected DistillationRecipe readRecipeFromNBT(NBTTagCompound tag)
	{
		return DistillationRecipe.loadFromNBT(tag);
	}

	@Override
	public NonNullList<ItemStack> getInventory()
	{
		//The tower is plumbed, not loaded: everything in and out of it is a fluid.
		return NO_INVENTORY;
	}

	@Override
	public boolean isStackValid(int slot, ItemStack stack)
	{
		return false;
	}

	@Override
	public int getSlotLimit(int slot)
	{
		return 0;
	}

	@Override
	public int[] getOutputSlots()
	{
		return NO_SLOTS;
	}

	@Override
	public void doGraphicalUpdates(int slot)
	{
		markDirty();
		markContainingBlockForUpdate(null);
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		heatTicks = nbt.getInteger("heatTicks");
		if(!descPacket)
			for(int i = 0; i < tanks.length; i++)
				tanks[i].readFromNBT(nbt.getCompoundTag("tank"+i));
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("heatTicks", heatTicks);
		//Eight tanks kept out of the description packet on purpose. The base class already sends
		//one of those per tick while a machine runs, and nothing on the client reads the contents:
		//the overlay names its cut from the recipe list and takes its running state from the
		//process queue, both of which are there already.
		if(!descPacket)
			for(int i = 0; i < tanks.length; i++)
				nbt.setTag("tank"+i, tanks[i].writeToNBT(new NBTTagCompound()));
	}
}
