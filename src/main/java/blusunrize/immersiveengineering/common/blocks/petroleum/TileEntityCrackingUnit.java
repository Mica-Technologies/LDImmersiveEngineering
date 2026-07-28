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
import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockCrackingUnit;
import blusunrize.immersiveengineering.common.items.ItemPetroleum;
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
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The Cracking Unit: the machine that breaks the heavy cuts into the light ones, and the only
 * place in the expansion where the player decides <em>what</em> a barrel turns into rather than
 * merely how fast.
 * <p>
 * <strong>The dial is the burner.</strong> The design notes call for a temperature control in a
 * GUI; this has none, and is better for it. The cracker always asks for the heat of a maximum-
 * severity run and cracks at whatever severity the heat it actually gets pays for. A firebox on
 * heavy fuel oil runs it hot and yields gasoline; one on natural gas runs it cool and yields
 * diesel. The knob is a real decision made out of blocks and fuel rather than a slider, and it
 * makes the Industrial Burner's fuel table matter twice.
 * <p>
 * Products leave on <strong>opposite rows of the head course</strong>: gasoline out of the front,
 * diesel out of the back. Sharing a face would make the split unenforceable -- one pipe would take
 * whichever arrived first -- which is a mistake this expansion has already made once, on the
 * wellhead.
 * <p>
 * Petcoke drops out of the bottom of the coke drum into whatever inventory stands against it. It
 * burns hotter than coal, which is what stops the byproduct being a nuisance.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityCrackingUnit extends TileEntityMultiblockPart<TileEntityCrackingUnit>
		implements IBlockOverlayText, IComparatorOverride
{
	//	=================================
	//		THE CRACKING TABLE
	//	=================================

	/**
	 * One feedstock: what it becomes, and how the split moves with severity.
	 * <p>
	 * Deliberately not a {@code MultiblockRecipe}, for the same reason the scrubber's table is not:
	 * the machine has one input, two fluid outputs and one item output at ratios that are a
	 * function of severity, which no existing recipe type expresses -- and keeping it as plain
	 * strings and integers means the numbers can be read, and checked, with no fluid registry.
	 */
	public static final class Cracking
	{
		public final String input;
		public final String light;
		public final String heavy;
		/**
		 * Millibuckets of light product per bucket of feed, at zero and at full severity.
		 */
		public final int lightAtCold;
		public final int lightAtHot;
		/**
		 * Millibuckets of heavy product per bucket of feed, at zero and at full severity.
		 */
		public final int heavyAtCold;
		public final int heavyAtHot;
		/**
		 * Millibuckets of feed per lump of petcoke; zero for a feed that makes none.
		 */
		public final int inputPerCoke;

		Cracking(String input, String light, String heavy, int lightAtCold, int lightAtHot,
				 int heavyAtCold, int heavyAtHot, int inputPerCoke)
		{
			this.input = input;
			this.light = light;
			this.heavy = heavy;
			this.lightAtCold = Math.max(0, lightAtCold);
			this.lightAtHot = Math.max(0, lightAtHot);
			this.heavyAtCold = Math.max(0, heavyAtCold);
			this.heavyAtHot = Math.max(0, heavyAtHot);
			this.inputPerCoke = Math.max(0, inputPerCoke);
		}

		/**
		 * @param severity 0 (as cool as the machine will run) to 100 (as hot as it will run)
		 * @return millibuckets of light product from a volume of feed
		 */
		public int lightFrom(int volume, int severity)
		{
			return yield(volume, lerp(lightAtCold, lightAtHot, severity));
		}

		/**
		 * @return millibuckets of heavy product from a volume of feed
		 */
		public int heavyFrom(int volume, int severity)
		{
			return yield(volume, lerp(heavyAtCold, heavyAtHot, severity));
		}

		private static int yield(int volume, int perBucket)
		{
			return volume <= 0?0: (int)((long)volume*perBucket/1000L);
		}

		private static int lerp(int cold, int hot, int severity)
		{
			int clamped = severity < 0?0: severity > 100?100: severity;
			return cold+(hot-cold)*clamped/100;
		}

		/**
		 * The largest feed volume whose products both fit in the room available.
		 * <p>
		 * Both, not either: a run that filled the gasoline tank and then had nowhere to put the
		 * diesel would have to destroy one of them, and a refinery that quietly bins a cut is the
		 * single most expensive kind of bug to notice.
		 */
		public int volumeForRoom(int lightRoom, int heavyRoom, int severity)
		{
			int perLight = lerp(lightAtCold, lightAtHot, severity);
			int perHeavy = lerp(heavyAtCold, heavyAtHot, severity);
			long byLight = perLight <= 0?Long.MAX_VALUE: (long)Math.max(0, lightRoom)*1000L/perLight;
			long byHeavy = perHeavy <= 0?Long.MAX_VALUE: (long)Math.max(0, heavyRoom)*1000L/perHeavy;
			return (int)Math.min(Integer.MAX_VALUE, Math.min(byLight, byHeavy));
		}
	}

	/**
	 * Heat units a bucket of feed costs at zero severity -- the coolest run the machine will do.
	 * Below this it does not crack at all rather than cracking badly, because a machine producing
	 * a trickle of the wrong thing is harder to diagnose than one that has stopped.
	 */
	public static final int HEAT_PER_BUCKET_COLD = 2500;
	/**
	 * Heat units a bucket costs at full severity. The gap between the two is the dial.
	 * <p>
	 * Sized against the burner: a firebox on natural gas sustains 44 HU/t and one on heavy fuel oil
	 * considerably more, so gas gets a cracker running and HFO gets it running hot. That is exactly
	 * the trade the machine exists to offer.
	 */
	public static final int HEAT_PER_BUCKET_HOT = 7500;

	private static final Map<String, Cracking> CRACKINGS = new HashMap<String, Cracking>();

	static
	{
		//Heavy fuel oil: the classic cracker feed, and the cut with the fewest other uses. Yields
		//shift from three-to-one diesel at the cold end to two-to-one gasoline at the hot end, and
		//it is the only feed that makes coke.
		registerCracking("ie_heavy_fuel_oil", "ie_gasoline", "ie_diesel",
				250, 550, 500, 200, 2000);
		//Naphtha is already light, so cracking it is a way of turning a fuel nobody loves into the
		//one that runs tools. It makes no coke -- there is nothing heavy enough left in it.
		registerCracking("ie_naphtha", "ie_gasoline", "ie_diesel",
				500, 750, 250, 100, 0);
	}

	public static void registerCracking(String input, String light, String heavy, int lightAtCold,
										int lightAtHot, int heavyAtCold, int heavyAtHot,
										int inputPerCoke)
	{
		if(input==null||light==null||heavy==null)
			return;
		CRACKINGS.put(input, new Cracking(input, light, heavy, lightAtCold, lightAtHot,
				heavyAtCold, heavyAtHot, inputPerCoke));
	}

	public static void removeCracking(String input)
	{
		if(input!=null)
			CRACKINGS.remove(input);
	}

	@Nullable
	public static Cracking getCracking(@Nullable String input)
	{
		return input==null?null: CRACKINGS.get(input);
	}

	@Nullable
	public static Cracking getCracking(@Nullable Fluid fluid)
	{
		return fluid==null?null: getCracking(fluid.getName());
	}

	public static boolean isCrackable(@Nullable FluidStack stack)
	{
		return stack!=null&&getCracking(stack.getFluid())!=null;
	}

	/**
	 * Turns the heat actually granted into a severity, 0-100.
	 *
	 * @param granted heat units the burner handed over for this pass
	 * @param volume  millibuckets the pass would put through
	 * @return -1 if there was not even enough heat for a cold run
	 */
	public static int severityFor(int granted, int volume)
	{
		if(volume <= 0)
			return -1;
		//In longs: a full pass of a deep tank times the hot heat rate overflows an int, and the
		//negative that comes back would read as "no heat" on a machine with a roaring firebox
		//against it.
		long cold = (long)volume*HEAT_PER_BUCKET_COLD/1000L;
		long hot = (long)volume*HEAT_PER_BUCKET_HOT/1000L;
		if(granted < cold)
			return -1;
		if(hot <= cold)
			return 100;
		long above = granted-cold;
		return (int)Math.max(0, Math.min(100, above*100/(hot-cold)));
	}

	/**
	 * @return the heat a pass of this size asks for -- always the full-severity figure, because the
	 * machine's whole design is that it takes whatever it is given and cracks accordingly
	 */
	public static int heatWanted(int volume)
	{
		return volume <= 0?0: (int)((long)volume*HEAT_PER_BUCKET_HOT/1000L);
	}

	//	=================================
	//		RATES
	//	=================================

	/**
	 * Millibuckets of feed per tick at full rate. Half the distillation tower's throughput: this is
	 * a finishing step on one cut, not the machine the whole barrel goes through.
	 */
	public static final int CRACK_RATE = 10;
	public static final int CRACK_INTERVAL = 20;
	public static final int CHARGE = CRACK_RATE*CRACK_INTERVAL;
	public static final int TANK_CAPACITY = 24000;
	/**
	 * Lumps the machine holds before it stops taking feed.
	 */
	public static final int COKE_BUFFER = 64;
	private static final int CITY_HEAT_SIP = 1;
	/**
	 * City mode: the severity a cracker runs at when nothing is being metered. The middle, because
	 * picking either end would silently make one product unobtainable in a city pack.
	 */
	private static final int CITY_SEVERITY = 50;

	public static final int STATUS_IDLE = 0;
	public static final int STATUS_CRACKING = 1;
	public static final int STATUS_NO_HEAT = 2;
	public static final int STATUS_BACKED_UP = 3;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];

	public final FluidTank tankFeed = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return isCrackable(fluid);
		}
	};
	public final FluidTank tankLight = new FluidTank(TANK_CAPACITY);
	public final FluidTank tankHeavy = new FluidTank(TANK_CAPACITY);

	private int cokeBuffer;
	private int cokeProgress;
	/**
	 * Synced. Drives the flare of the coke drum and the whole of the overlay.
	 */
	public int status;
	/**
	 * Synced, and the number the machine exists for: what the last pass actually ran at.
	 */
	public int lastSeverity;

	private int stagger = -1;
	private TileEntityIndustrialBurner heatSource;

	public static final int REDSTONE_INDEX = PetroleumGeometry.structureIndex(
			PetroleumGeometry.CRACKER_SIZE, 0, 0, 0);

	public TileEntityCrackingUnit()
	{
		super(PetroleumGeometry.CRACKER_SIZE);
	}

	//	=================================
	//		CRACKING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote)
		{
			if(formed&&!isDummy()&&status==STATUS_CRACKING&&world.getTotalWorldTime()%4==0)
				spawnPlume();
			return;
		}
		if(isDummy()||!formed)
			return;
		if((world.getTotalWorldTime()+getStagger())%CRACK_INTERVAL==0)
			runPass();
	}

	private void runPass()
	{
		int previous = status;
		status = STATUS_IDLE;
		FluidStack feed = tankFeed.getFluid();
		Cracking recipe = feed==null?null: getCracking(feed.getFluid());
		if(recipe!=null)
			crack(recipe);
		ejectCoke();
		if(status!=previous)
			markContainingBlockForUpdate(null);
		markDirty();
	}

	private void crack(Cracking recipe)
	{
		Fluid light = FluidRegistry.getFluid(recipe.light);
		Fluid heavy = FluidRegistry.getFluid(recipe.heavy);
		if(light==null||heavy==null)
			return;
		int available = Math.min(CHARGE, tankFeed.getFluidAmount());
		if(available <= 0)
			return;

		//Severity has to be known before the room check, because it decides the yields -- so the
		//heat is surveyed first, on the untrimmed volume, and the volume is trimmed afterwards.
		int severity;
		TileEntityIndustrialBurner burner = heatSource();
		if(CityMode.petroleum())
		{
			if(burner==null)
			{
				status = STATUS_NO_HEAT;
				return;
			}
			//A lit firebox is still required and still has to be against the deck, so the plant
			//layout survives; only the metering goes away.
			burner.drawHeat(CITY_HEAT_SIP, false);
			severity = CITY_SEVERITY;
		}
		else
		{
			if(burner==null)
			{
				status = STATUS_NO_HEAT;
				return;
			}
			int granted = burner.drawHeat(heatWanted(available), true);
			severity = severityFor(granted, available);
			if(severity < 0)
			{
				status = STATUS_NO_HEAT;
				return;
			}
		}

		int volume = Math.min(available, recipe.volumeForRoom(
				tankLight.getCapacity()-tankLight.getFluidAmount(),
				tankHeavy.getCapacity()-tankHeavy.getFluidAmount(), severity));
		int perCoke = recipe.inputPerCoke;
		volume = Math.min(volume, TileEntityGasScrubber.volumeForSulfurRoom(
				cokeProgress, perCoke, COKE_BUFFER-cokeBuffer));
		if(volume <= 0)
		{
			status = STATUS_BACKED_UP;
			return;
		}
		//Charged on the trimmed volume, at the severity that was surveyed: a run that had to be cut
		//short must not be billed for heat it never used.
		if(!CityMode.petroleum())
			burner.drawHeat(heatCost(volume, severity), false);

		int lightOut = recipe.lightFrom(volume, severity);
		int heavyOut = recipe.heavyFrom(volume, severity);
		tankFeed.drain(volume, true);
		if(lightOut > 0)
			tankLight.fill(new FluidStack(light, lightOut), true);
		if(heavyOut > 0)
			tankHeavy.fill(new FluidStack(heavy, heavyOut), true);
		if(perCoke > 0)
		{
			cokeProgress += volume;
			int lumps = cokeProgress/perCoke;
			if(lumps > 0)
			{
				cokeProgress -= lumps*perCoke;
				cokeBuffer += lumps;
			}
		}
		lastSeverity = severity;
		status = STATUS_CRACKING;
	}

	/**
	 * @return the heat a volume actually costs at a given severity
	 */
	public static int heatCost(int volume, int severity)
	{
		if(volume <= 0)
			return 0;
		int clamped = severity < 0?0: severity > 100?100: severity;
		long perBucket = HEAT_PER_BUCKET_COLD
				+(long)(HEAT_PER_BUCKET_HOT-HEAT_PER_BUCKET_COLD)*clamped/100L;
		return (int)Math.min(Integer.MAX_VALUE, (long)volume*perBucket/1000L);
	}

	private void ejectCoke()
	{
		if(cokeBuffer <= 0)
			return;
		BlockPos outlet = getBlockPosForPos(MultiblockCrackingUnit.COKE_OUTLET_POS)
				.offset(facing.getOpposite());
		TileEntity target = Utils.getExistingTileEntity(world, outlet);
		if(target==null)
			return;
		ItemStack stack = new ItemStack(IEContent.itemPetroleum, cokeBuffer, ItemPetroleum.PETCOKE);
		ItemStack left = Utils.insertStackIntoInventory(target, stack, facing);
		cokeBuffer = left.isEmpty()?0: left.getCount();
	}

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
		int depth = PetroleumGeometry.CRACKER_DEPTH;
		int width = PetroleumGeometry.CRACKER_WIDTH;
		//The whole ring around the deck, as the scrubber does: a machine that only works when the
		//firebox is on one exact side is one the player builds wrong once and cannot then debug.
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
				PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE, 0, l, w));
		TileEntity te = Utils.getExistingTileEntity(world, cell.offset(outward));
		if(!(te instanceof TileEntityIndustrialBurner))
			return null;
		TileEntityIndustrialBurner burner = (TileEntityIndustrialBurner)te;
		return burner.isBurning()?burner: null;
	}

	private void spawnPlume()
	{
		BlockPos top = getBlockPosForPos(PetroleumGeometry.structureIndex(
				PetroleumGeometry.CRACKER_SIZE, PetroleumGeometry.CRACKER_HEIGHT-1,
				PetroleumGeometry.CRACKER_DEPTH/2, PetroleumGeometry.CRACKER_WIDTH/2));
		world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
				top.getX()+.5, top.getY()+1.1, top.getZ()+.5, 0, .02, 0);
	}

	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), CRACK_INTERVAL);
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
		TileEntityCrackingUnit master = master();
		if(master==null)
			return null;
		if(MultiblockCrackingUnit.isFeedPort(pos))
			return new String[]{"Feed in", statusLine(master.status), severityLine(master)};
		if(MultiblockCrackingUnit.isLightPort(pos))
			return new String[]{"Gasoline out", severityLine(master)};
		if(MultiblockCrackingUnit.isHeavyPort(pos))
			return new String[]{"Diesel out", severityLine(master)};
		return null;
	}

	private static String severityLine(TileEntityCrackingUnit master)
	{
		if(master.status!=STATUS_CRACKING)
			return TextFormatting.GRAY+"Severity: --"+TextFormatting.RESET;
		String word = master.lastSeverity >= 67?TextFormatting.GOLD+"hot, favouring gasoline"
				: master.lastSeverity >= 34?TextFormatting.YELLOW+"medium"
				: TextFormatting.AQUA+"cool, favouring diesel";
		return "Severity: "+master.lastSeverity+"%  "+word+TextFormatting.RESET;
	}

	private static String statusLine(int status)
	{
		switch(status)
		{
			case STATUS_CRACKING:
				return TextFormatting.GREEN+"Cracking"+TextFormatting.RESET;
			case STATUS_NO_HEAT:
				return TextFormatting.RED+"Not hot enough"+TextFormatting.RESET;
			case STATUS_BACKED_UP:
				return TextFormatting.GOLD+"Backed up"+TextFormatting.RESET;
			default:
				return TextFormatting.YELLOW+"Idle"+TextFormatting.RESET;
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
		TileEntityCrackingUnit master = master();
		if(master==null||!master.formed||master.tankFeed.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, 15*master.tankFeed.getFluidAmount()/TANK_CAPACITY);
	}

	//	=================================
	//		PORTS
	//	=================================

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityCrackingUnit master = master();
		if(master==null||!formed)
			return NO_TANKS;
		if(MultiblockCrackingUnit.isFeedPort(pos))
			return new IFluidTank[]{master.tankFeed};
		if(MultiblockCrackingUnit.isLightPort(pos))
			return new IFluidTank[]{master.tankLight};
		if(MultiblockCrackingUnit.isHeavyPort(pos))
			return new IFluidTank[]{master.tankHeavy};
		return NO_TANKS;
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return MultiblockCrackingUnit.isFeedPort(pos);
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		return MultiblockCrackingUnit.isLightPort(pos)||MultiblockCrackingUnit.isHeavyPort(pos);
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		int depth = PetroleumGeometry.CRACKER_DEPTH;
		int width = PetroleumGeometry.CRACKER_WIDTH;
		int cells = PetroleumGeometry.CRACKER_HEIGHT*depth*width;
		ItemStack original = pos < 0||pos >= cells?null
				: MultiblockCrackingUnit.instance.getStructureManual()
				[PetroleumGeometry.heightOf(PetroleumGeometry.CRACKER_SIZE, pos)]
				[pos%(depth*width)/width]
				[pos%width];
		return original==null?new ItemStack(IEContent.blockMetalDecoration1, 1,
				BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta()): original.copy();
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		status = nbt.getInteger("status");
		lastSeverity = nbt.getInteger("severity");
		if(!descPacket)
		{
			tankFeed.readFromNBT(nbt.getCompoundTag("tankFeed"));
			tankLight.readFromNBT(nbt.getCompoundTag("tankLight"));
			tankHeavy.readFromNBT(nbt.getCompoundTag("tankHeavy"));
			cokeBuffer = nbt.getInteger("cokeBuffer");
			cokeProgress = nbt.getInteger("cokeProgress");
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("status", status);
		nbt.setInteger("severity", lastSeverity);
		if(!descPacket)
		{
			nbt.setTag("tankFeed", tankFeed.writeToNBT(new NBTTagCompound()));
			nbt.setTag("tankLight", tankLight.writeToNBT(new NBTTagCompound()));
			nbt.setTag("tankHeavy", tankHeavy.writeToNBT(new NBTTagCompound()));
			nbt.setInteger("cokeBuffer", cokeBuffer);
			nbt.setInteger("cokeProgress", cokeProgress);
		}
	}

	/**
	 * Unused, but kept so a future variant that wants a metal shell has the constant to hand rather
	 * than reaching for a literal.
	 */
	@SuppressWarnings("unused")
	private static ItemStack shell()
	{
		return new ItemStack(IEContent.blockSheetmetal, 1, BlockTypes_MetalsAll.STEEL.getMeta());
	}
}
