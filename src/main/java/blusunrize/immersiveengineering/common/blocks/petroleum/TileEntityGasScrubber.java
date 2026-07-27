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
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The Gas Scrubber: cleans raw wellhead gas and drops sulfur out of it.
 * <p>
 * Sour gas is the one thing an oilfield produces that is worth nothing at all. It cannot be
 * burnt, no machine accepts it, and it comes up the bore whether the player wants it or not, so
 * until this machine exists the only thing to do with it is flare it away. The scrubber turns
 * that disposal problem into two things worth having: natural gas, which a Diesel Generator burns
 * and the Refinery fractionates into propane, and sulfur, which feeds the existing gunpowder
 * chain. That is the whole design -- not a marginal upgrade to an existing stream, but the
 * difference between a waste product and a second product.
 * <p>
 * <strong>The operating cost is heat, not flux.</strong> Sweetening gas is a reboiler duty in
 * reality and the machine is modelled that way: it draws heat units from an
 * {@link TileEntityIndustrialBurner} standing against its skid, and does nothing at all without
 * one. That is deliberate and it is what stops the machine being free money. A burner will run on
 * the scrubber's own natural gas, so the plant can bootstrap itself at a cost of roughly a
 * quarter of what it makes; feeding it heavy fuel oil instead -- the cut that has no other
 * consumer -- keeps the whole of the gas. Two waste streams cancelling each other out is a better
 * reward than either of them alone.
 * <p>
 * Partial heat is honoured rather than refused: a pass scrubs however much gas the heat on offer
 * pays for. An underfed scrubber runs slowly, which is legible, instead of stalling on a
 * threshold nothing tells the player about.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityGasScrubber extends TileEntityMultiblockPart<TileEntityGasScrubber>
		implements IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		THE SCRUBBING TABLE
	//	=================================

	/**
	 * One conversion: a sour fluid in, a sweet one plus sulfur out.
	 * <p>
	 * Deliberately not a {@code MultiblockRecipe}. The machine has one input, one fluid output and
	 * one item output at fixed ratios and no notion of a batch, so the process queue would buy it
	 * nothing but a dependency on {@code FluidStack} in the one place that most wants to stay
	 * arithmetic. Keyed by fluid registry name rather than by {@code Fluid} for the same reason the
	 * burner's fuel table is: the numbers can then be read -- and checked -- without a fluid
	 * registry.
	 */
	public static final class Scrubbing
	{
		public final String input;
		public final String output;
		/**
		 * What fraction of the input volume survives as sweet gas. The rest is the acid gas that
		 * becomes sulfur.
		 */
		public final int outputPercent;
		/**
		 * Millibuckets of input per dust of sulfur; zero for a conversion that yields none.
		 */
		public final int inputPerSulfur;
		/**
		 * Heat units a bucket of this input costs to sweeten.
		 */
		public final int heatPerBucket;

		Scrubbing(String input, String output, int outputPercent, int inputPerSulfur, int heatPerBucket)
		{
			this.input = input;
			this.output = output;
			this.outputPercent = Math.max(0, Math.min(100, outputPercent));
			this.inputPerSulfur = Math.max(0, inputPerSulfur);
			this.heatPerBucket = Math.max(0, heatPerBucket);
		}

		/**
		 * @return the sweet gas a volume of input yields, in millibuckets
		 */
		public int sweetFrom(int volume)
		{
			return volume <= 0?0: volume*outputPercent/100;
		}

		/**
		 * @return the input volume whose product would exactly fill the given room. Rounded down, so
		 * a pass sized by this can never overflow the product tank.
		 */
		public int volumeForSweet(int room)
		{
			if(room <= 0)
				return 0;
			//A conversion that yields no fluid at all is never limited by the product tank.
			return outputPercent <= 0?Integer.MAX_VALUE: room*100/outputPercent;
		}

		/**
		 * @return the heat a volume of input costs, in heat units
		 */
		public int heatFor(int volume)
		{
			return volume <= 0?0: volume*heatPerBucket/1000;
		}

		/**
		 * @return the input volume a quantity of heat pays for
		 */
		public int volumeForHeat(int heat)
		{
			if(heat <= 0)
				return 0;
			return heatPerBucket <= 0?Integer.MAX_VALUE: heat*1000/heatPerBucket;
		}
	}

	/**
	 * Percentage of the sour gas that survives as sweet gas.
	 * <p>
	 * The missing fifth is the acid gas, and it is what the sulfur is made of, so the loss is not
	 * a tax -- it is the second product. Generous on purpose: the input had no value whatsoever, so
	 * a stingy ratio would only make the machine not worth the forty blocks it costs.
	 */
	public static final int SWEET_PERCENT = 80;
	/**
	 * Millibuckets of sour gas per dust of sulfur -- two buckets each.
	 * <p>
	 * Paced against the gunpowder recipe, which wants four saltpeter to every one sulfur. At this
	 * rate a scrubber running flat out yields sulfur faster than a player can find saltpeter for
	 * it, which is the right way round: the bonus should never be the thing that gates the chain.
	 */
	public static final int SOUR_PER_SULFUR = 2000;
	/**
	 * Heat units a bucket of sour gas costs to sweeten.
	 * <p>
	 * Chosen against the burner: a firebox on natural gas sustains 44 HU/t, and a scrubber at full
	 * rate wants 40, so the machine can be run off its own product with a little to spare. Doing
	 * that costs a quarter of the gas it makes, which is the number the whole design turns on --
	 * high enough that heavy fuel oil is clearly the better thing to burn, low enough that a player
	 * without any is not locked out.
	 */
	public static final int HEAT_PER_BUCKET = 2000;

	private static final Map<String, Scrubbing> SCRUBBINGS = new HashMap<String, Scrubbing>();

	static
	{
		//Registered here rather than in IEContent because there is nothing to register: the table is
		//plain strings and integers, so it costs nothing at class-load and needs no fluid registry.
		registerScrubbing("ie_sour_gas", "natural_gas", SWEET_PERCENT, SOUR_PER_SULFUR, HEAT_PER_BUCKET);
	}

	/**
	 * @param inputFluid     the sour fluid's registry name
	 * @param outputFluid    the sweet fluid's registry name
	 * @param outputPercent  what fraction of the input volume survives, 0 to 100
	 * @param inputPerSulfur millibuckets of input per dust of sulfur; zero for none
	 * @param heatPerBucket  heat units a bucket costs to sweeten
	 */
	public static void registerScrubbing(String inputFluid, String outputFluid, int outputPercent,
										 int inputPerSulfur, int heatPerBucket)
	{
		if(inputFluid==null||outputFluid==null)
			return;
		SCRUBBINGS.put(inputFluid, new Scrubbing(inputFluid, outputFluid, outputPercent,
				inputPerSulfur, heatPerBucket));
	}

	public static void removeScrubbing(String inputFluid)
	{
		if(inputFluid!=null)
			SCRUBBINGS.remove(inputFluid);
	}

	/**
	 * @return what the scrubber does with the named fluid, or null if it will not take it
	 */
	@Nullable
	public static Scrubbing getScrubbing(String inputFluid)
	{
		return inputFluid==null?null: SCRUBBINGS.get(inputFluid);
	}

	@Nullable
	public static Scrubbing getScrubbing(@Nullable Fluid fluid)
	{
		return fluid==null?null: getScrubbing(fluid.getName());
	}

	public static boolean isScrubbable(@Nullable FluidStack stack)
	{
		return stack!=null&&getScrubbing(stack.getFluid())!=null;
	}

	//	=================================
	//		SULFUR
	//	=================================

	/**
	 * The ore name rather than a fixed item, so a pack that ships its own sulfur gets its own back
	 * out of the machine. Immersive Engineering registers its Sulfur Dust under this name itself,
	 * so it always resolves in practice; the null path exists only so a pack that has somehow
	 * stripped it still gets a working gas sweetener rather than a machine that will not run.
	 */
	private static final String SULFUR_ORE = "dustSulfur";
	/**
	 * Dust the machine will hold before it stops taking gas. Deep enough that emptying it is an
	 * occasional errand, shallow enough that a scrubber nobody has plumbed does not quietly bank a
	 * chest's worth.
	 */
	public static final int SULFUR_BUFFER = 64;
	/**
	 * Resolved on first use rather than in a static initialiser, so this class stays loadable
	 * outside a running game.
	 */
	private static ItemStack sulfurTemplate;
	private static boolean sulfurResolved;

	/**
	 * @return whether the pack has any sulfur for the machine to hand out
	 */
	private static boolean hasSulfur()
	{
		if(!sulfurResolved)
		{
			sulfurResolved = true;
			NonNullList<ItemStack> ores = OreDictionary.getOres(SULFUR_ORE);
			for(int i = 0; i < ores.size(); i++)
			{
				ItemStack ore = ores.get(i);
				//A wildcard entry names a whole item rather than one of its metas and cannot be
				//handed out as a product.
				if(!ore.isEmpty()&&ore.getMetadata()!=OreDictionary.WILDCARD_VALUE)
				{
					sulfurTemplate = ore;
					break;
				}
			}
		}
		return sulfurTemplate!=null;
	}

	/**
	 * @return a stack of sulfur, or null if nothing in the pack registers any
	 */
	@Nullable
	private static ItemStack sulfur(int count)
	{
		if(!hasSulfur()||count <= 0)
			return null;
		ItemStack out = sulfurTemplate.copy();
		out.setCount(count);
		return out;
	}

	/**
	 * The most input a pass may take without producing sulfur it has nowhere to keep.
	 * <p>
	 * Exact rather than conservative: partial progress towards the next dust is allowed to carry
	 * on accumulating even when the buffer is full, so a scrubber does not stop a whole dust early.
	 *
	 * @param progress   millibuckets already banked towards the next dust
	 * @param perSulfur  millibuckets per dust; zero means sulfur is not being made at all
	 * @param dustRoom   how many more dust the buffer will take
	 */
	public static int volumeForSulfurRoom(int progress, int perSulfur, int dustRoom)
	{
		if(perSulfur <= 0)
			return Integer.MAX_VALUE;
		if(dustRoom < 0)
			dustRoom = 0;
		return Math.max(0, dustRoom*perSulfur+perSulfur-1-progress);
	}

	//	=================================
	//		RATES
	//	=================================

	/**
	 * Millibuckets of sour gas per tick at full rate. Sized against a wellhead at peak so that one
	 * scrubber comfortably keeps up with the gas a field produces.
	 */
	public static final int SCRUB_RATE = 20;
	/**
	 * Ticks between passes. The machine does no accounting in between; it works out a whole
	 * interval's worth in one go, exactly as the wellheads and the burner do.
	 */
	public static final int SCRUB_INTERVAL = 20;
	/**
	 * Millibuckets one full pass consumes.
	 */
	public static final int CHARGE = SCRUB_RATE*SCRUB_INTERVAL;
	/**
	 * Both tanks are a burner's worth deep, so refuelling and emptying are errands rather than
	 * chores.
	 */
	public static final int TANK_CAPACITY = 24000;
	/**
	 * City mode heat sip: a token unit per pass, so a burner is still required and still has to be
	 * lit, but nothing is metered. Same trade the diesel generator makes with its fuel.
	 */
	private static final int CITY_HEAT_SIP = 1;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];

	public final FluidTank tankSour = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return isScrubbable(fluid);
		}
	};
	public final FluidTank tankSweet = new FluidTank(TANK_CAPACITY);

	/**
	 * Dust waiting to be pushed out. Master only.
	 */
	private int sulfurBuffer;
	/**
	 * Millibuckets banked towards the next dust. Master only.
	 */
	private int sulfurProgress;
	/**
	 * Nothing to do: the feed is empty, or holds something the machine will not take.
	 */
	public static final int STATUS_IDLE = 0;
	public static final int STATUS_SCRUBBING = 1;
	/**
	 * No lit firebox is leaning on the skid, so there is no reboiler duty to be had.
	 */
	public static final int STATUS_NO_HEAT = 2;
	/**
	 * The product tank or the sulfur buffer is full, so a pass would have to destroy something.
	 */
	public static final int STATUS_BACKED_UP = 3;

	/**
	 * What the machine did, or did not do, on its last pass.
	 * <p>
	 * Synced, and the only thing that is: it drives the vapour plume and it is the whole of the
	 * overlay. The three ways a scrubber stops are not interchangeable, and a player who cannot
	 * tell them apart will spend the evening re-plumbing a machine whose firebox has simply gone
	 * out.
	 */
	public int status;

	private int stagger = -1;
	/**
	 * The firebox this machine is leaning on, held between passes. Re-scanning a dozen positions
	 * every tick for something that has not moved would be exactly the poll this machine is meant
	 * not to do.
	 */
	private TileEntityIndustrialBurner heatSource;

	public TileEntityGasScrubber()
	{
		super(PetroleumGeometry.SCRUBBER_SIZE);
	}

	//	=================================
	//		SCRUBBING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote)
		{
			//Only the master knows whether the machine is working, and only its own position is
			//needed to place the plume, so the other thirty-nine do nothing at all.
			if(formed&&!isDummy()&&active&&world.getTotalWorldTime()%4==0)
				spawnPlume();
			return;
		}
		if(isDummy()||!formed)
			return;

		if((world.getTotalWorldTime()+getStagger())%SCRUB_INTERVAL==0)
			runPass();
	}

	/**
	 * One pass: an interval's worth of gas, heat and sulfur worked out in a single go.
	 */
	private void runPass()
	{
		boolean wasActive = active;
		Scrubbing recipe = getScrubbing(tankSour.getFluid()==null?null: tankSour.getFluid().getFluid());
		active = recipe!=null&&scrub(recipe) > 0;
		ejectSulfur();
		if(active!=wasActive)
			markContainingBlockForUpdate(null);
		//Both tanks and the sulfur buffer moved, and a chunk saved without them comes back holding
		//gas it has already sweetened. Once a second, and only on the master.
		markDirty();
	}

	/**
	 * @return how much sour gas was actually put through, in millibuckets
	 */
	private int scrub(Scrubbing recipe)
	{
		Fluid product = FluidRegistry.getFluid(recipe.output);
		if(product==null)
			return 0;
		int volume = Math.min(CHARGE, tankSour.getFluidAmount());
		volume = Math.min(volume, recipe.volumeForSweet(tankSweet.getCapacity()-tankSweet.getFluidAmount()));
		//Sulfur is a product, not a by-product: a machine with nowhere to put it backs up rather
		//than quietly destroying it, exactly as the distillation tower refuses a run it cannot
		//finish. A pack with no sulfur item at all skips this cap entirely.
		int perSulfur = sulfur(1)==null?0: recipe.inputPerSulfur;
		volume = Math.min(volume, volumeForSulfurRoom(sulfurProgress, perSulfur, SULFUR_BUFFER-sulfurBuffer));
		if(volume <= 0)
			return 0;

		TileEntityIndustrialBurner burner = heatSource();
		if(burner==null)
			return 0;
		if(CityMode.petroleum())
			//City mode: the reboiler is a fact, not a simulation. A lit burner is still required and
			//still has to be leaning on the machine, so the gesture and the plant layout survive --
			//but the duty is not metered and the rate never sags. Same trade the tower makes.
			burner.drawHeat(CITY_HEAT_SIP, false);
		else
		{
			int granted = burner.drawHeat(recipe.heatFor(volume), true);
			volume = Math.min(volume, recipe.volumeForHeat(granted));
			if(volume <= 0)
				return 0;
			burner.drawHeat(recipe.heatFor(volume), false);
		}

		int sweet = recipe.sweetFrom(volume);
		tankSour.drain(volume, true);
		if(sweet > 0)
			tankSweet.fill(new FluidStack(product, sweet), true);
		if(perSulfur > 0)
		{
			sulfurProgress += volume;
			int dust = sulfurProgress/perSulfur;
			if(dust > 0)
			{
				sulfurProgress -= dust*perSulfur;
				sulfurBuffer += dust;
			}
		}
		return volume;
	}

	/**
	 * Pushes the sulfur into whatever inventory stands against the front of the skid. One lookup
	 * per pass, at one known position, so "put a chest here" is the whole instruction.
	 */
	private void ejectSulfur()
	{
		if(sulfurBuffer <= 0)
			return;
		ItemStack stack = sulfur(sulfurBuffer);
		if(stack==null)
			return;
		BlockPos outlet = getBlockPosForPos(MultiblockGasScrubber.SULFUR_OUTLET_POS)
				.offset(facing.getOpposite());
		TileEntity target = Utils.getExistingTileEntity(world, outlet);
		if(target==null)
			return;
		ItemStack left = Utils.insertStackIntoInventory(target, stack, facing);
		sulfurBuffer = left.isEmpty()?0: left.getCount();
	}

	/**
	 * @return a lit firebox leaning on the skid, or null. Cached while it stays lit; a cold or
	 * broken one costs a rescan of the twelve positions around the footprint, once per pass.
	 */
	@Nullable
	private TileEntityIndustrialBurner heatSource()
	{
		if(heatSource!=null&&!heatSource.isInvalid()&&heatSource.isBurning())
			return heatSource;
		heatSource = findHeatSource();
		return heatSource;
	}

	@Nullable
	private TileEntityIndustrialBurner findHeatSource()
	{
		int depth = PetroleumGeometry.SCRUBBER_DEPTH;
		int width = PetroleumGeometry.SCRUBBER_WIDTH;
		//The whole ring around the skid, rather than one exact block. A machine that only works
		//when the firebox is on one particular side is a machine the player builds wrong once and
		//then cannot debug, and there is nothing to be gained from that being fiddly.
		for(int w = 0; w < width; w++)
		{
			TileEntityIndustrialBurner found = burnerAt(0, w, facing.getOpposite());
			if(found!=null)
				return found;
			found = burnerAt(depth-1, w, facing);
			if(found!=null)
				return found;
		}
		for(int l = 0; l < depth; l++)
		{
			TileEntityIndustrialBurner found = burnerAt(l, 0, facing.rotateYCCW());
			if(found!=null)
				return found;
			found = burnerAt(l, width-1, facing.rotateY());
			if(found!=null)
				return found;
		}
		return null;
	}

	@Nullable
	private TileEntityIndustrialBurner burnerAt(int l, int w, EnumFacing outward)
	{
		BlockPos cell = getBlockPosForPos(
				PetroleumGeometry.structureIndex(PetroleumGeometry.SCRUBBER_SIZE, 0, l, w));
		TileEntity te = Utils.getExistingTileEntity(world, cell.offset(outward));
		if(!(te instanceof TileEntityIndustrialBurner))
			return null;
		TileEntityIndustrialBurner burner = (TileEntityIndustrialBurner)te;
		return burner.isBurning()?burner: null;
	}

	private void spawnPlume()
	{
		//From the top of either vessel, which is where the sweet gas leaves.
		for(int w = 0; w < PetroleumGeometry.SCRUBBER_WIDTH; w += PetroleumGeometry.SCRUBBER_WIDTH-1)
		{
			BlockPos top = getBlockPosForPos(PetroleumGeometry.structureIndex(
					PetroleumGeometry.SCRUBBER_SIZE, PetroleumGeometry.SCRUBBER_HEIGHT-1,
					PetroleumGeometry.SCRUBBER_DEPTH/2, w));
			world.spawnParticle(EnumParticleTypes.CLOUD,
					top.getX()+.5, top.getY()+1.1, top.getZ()+.5, 0, .01, 0);
		}
	}

	/**
	 * Spreads passes across ticks by position, so a row of scrubbers never all fire on the same
	 * one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = Math.floorMod(getPos().getX()^getPos().getZ()*31, SCRUB_INTERVAL);
		return stagger;
	}

	//	=================================
	//		READOUT
	//	=================================

	/**
	 * @return whether the machine did work on its last pass, asked of any block of it
	 */
	public boolean isScrubbing()
	{
		TileEntityGasScrubber master = master();
		return master!=null&&master.formed&&master.active;
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed)
			return null;
		TileEntityGasScrubber master = master();
		if(master==null)
			return null;
		if(isFeedPort(pos))
			return new String[]{"Sour gas: "+master.tankSour.getFluidAmount()+" mB", master.statusLine()};
		if(isProductPort(pos))
			return new String[]{"Sweet gas: "+master.tankSweet.getFluidAmount()+" mB", master.statusLine()};
		return null;
	}

	private String statusLine()
	{
		if(active)
			return TextFormatting.GREEN+"Scrubbing"+TextFormatting.RESET;
		//The three ways a scrubber stops are not interchangeable, and a player who cannot tell them
		//apart will spend the evening re-plumbing a machine whose firebox has simply gone out.
		if(heatSource==null||!heatSource.isBurning())
			return TextFormatting.RED+"No heat"+TextFormatting.RESET;
		if(sulfurBuffer >= SULFUR_BUFFER)
			return TextFormatting.YELLOW+"Sulfur full"+TextFormatting.RESET;
		return TextFormatting.YELLOW+"Idle"+TextFormatting.RESET;
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		TileEntityGasScrubber master = master();
		if(master==null||!master.formed)
			return 0;
		//The feed level, which is what anybody wiring a comparator to a scrubber wants to automate:
		//a well backing up means the machine is not keeping pace.
		if(master.tankSour.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, 15*master.tankSour.getFluidAmount()/TANK_CAPACITY);
	}

	//	=================================
	//		PORTS
	//	=================================

	/**
	 * @return whether sour gas may be pushed into this part of the machine
	 */
	public static boolean isFeedPort(int pos)
	{
		//The whole skid course. Generous on purpose, as the burner's fuel ports are: a feed line
		//that has to find one exact block of a forty-block machine is a feed line the player gets
		//wrong.
		return pos >= 0&&PetroleumGeometry.heightOf(PetroleumGeometry.SCRUBBER_SIZE, pos)==0;
	}

	/**
	 * @return whether sweet gas may be drawn out of this part of the machine
	 */
	public static boolean isProductPort(int pos)
	{
		//The head course, and nothing below it. Gas off the top is the same convention the
		//distillation tower's lightest cut follows, and it means a pipe run climbing past the
		//vessels can never pick up the wrong stream.
		return pos >= 0&&PetroleumGeometry.heightOf(PetroleumGeometry.SCRUBBER_SIZE, pos)
				==PetroleumGeometry.SCRUBBER_HEIGHT-1;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityGasScrubber master = master();
		if(master==null||!formed)
			return NO_TANKS;
		if(isFeedPort(pos))
			return new IFluidTank[]{master.tankSour};
		if(isProductPort(pos))
			return new IFluidTank[]{master.tankSweet};
		//Plain vessel. Every face of it is deliberately inert.
		return NO_TANKS;
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		//Only the skid fills, and the tank itself refuses anything the machine cannot sweeten.
		return isFeedPort(pos);
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Only the head drains. Letting the feed back out would make the skid a free fluid store
		//that happens to also be a machine.
		return isProductPort(pos);
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The structure is made of full cubes; the shape comes from the
		//model and from the alley left open between the vessels, not from per-block bounds.
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the machine is three different blocks, and
		//handing back forty frames would turn every disassembly into a free trade of sheetmetal and
		//scaffolding for frames.
		int depth = PetroleumGeometry.SCRUBBER_DEPTH;
		int width = PetroleumGeometry.SCRUBBER_WIDTH;
		int cells = PetroleumGeometry.SCRUBBER_HEIGHT*depth*width;
		ItemStack original = pos < 0||pos >= cells?null
				: MultiblockGasScrubber.instance.getStructureManual()
				[PetroleumGeometry.heightOf(PetroleumGeometry.SCRUBBER_SIZE, pos)]
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
		active = nbt.getBoolean("active");
		if(!descPacket)
		{
			tankSour.readFromNBT(nbt.getCompoundTag("tankSour"));
			tankSweet.readFromNBT(nbt.getCompoundTag("tankSweet"));
			sulfurBuffer = nbt.getInteger("sulfurBuffer");
			sulfurProgress = nbt.getInteger("sulfurProgress");
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setBoolean("active", active);
		//The tanks and the buffer are kept out of the description packet on purpose: nothing on the
		//client reads them, and the overlay that shows them is server-side text. Only the running
		//flag, which drives the plume, is worth a packet.
		if(!descPacket)
		{
			nbt.setTag("tankSour", tankSour.writeToNBT(new NBTTagCompound()));
			nbt.setTag("tankSweet", tankSweet.writeToNBT(new NBTTagCompound()));
			nbt.setInteger("sulfurBuffer", sulfurBuffer);
			nbt.setInteger("sulfurProgress", sulfurProgress);
		}
	}
}
