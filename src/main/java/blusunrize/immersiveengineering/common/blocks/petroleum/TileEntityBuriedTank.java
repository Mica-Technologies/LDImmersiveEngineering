/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IActiveState;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IUsesBooleanProperty;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.petroleum.BuriedTankGeometry.Tier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A tank in the ground. Shared behaviour of all three tiers.
 * <p>
 * <strong>It is dumb on purpose.</strong> No recipe, no power, no process queue, no per-tick
 * work of any kind: a capacity, a fill cap, and a gauge. Everything that moves fluid in or out
 * is somebody else's block pulling on the capability, which is what makes a tank something a
 * player can put in the ground and stop thinking about.
 * <p>
 * The <strong>fill cap is the master</strong> -- see {@code MultiblockBuriedTank.form} for why --
 * so the tank's contents, its readout and its comparator level all live on the one block that is
 * still visible after the hole is backfilled, and the client sync goes to that block rather than
 * to a buried corner in some other chunk.
 * <p>
 * The cap is also the <strong>only</strong> face that connects to anything. That is not a
 * limitation to work around, it is the tier's whole proposition: an invisible tank with one
 * surface fitting, exactly like the domestic oil tank it is modelled on. A wall block accepting
 * fluid would let a player tap the tank from inside a tunnel and make the cap decorative.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public abstract class TileEntityBuriedTank<T extends TileEntityBuriedTank<T>>
		extends TileEntityMultiblockPart<T> implements IBlockOverlayText, IComparatorOverride, IActiveState
{
	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];

	public final Tier tier;
	/**
	 * Master only. Every part builds one because they all share a constructor, but only the cap's
	 * is ever read, written or synced.
	 */
	public final FluidTank tank;

	/**
	 * The division last sent to the client, so {@link #onLevelChanged()} can tell a cosmetic
	 * change from one worth a packet.
	 */
	private transient int syncedDivision = -1;

	protected TileEntityBuriedTank(Tier tier)
	{
		super(tier.size);
		this.tier = tier;
		this.tank = new FluidTank(tier.capacity)
		{
			@Override
			protected void onContentsChanged()
			{
				markDirty();
				onLevelChanged();
			}
		};
	}

	@Override
	public void update()
	{
		//A tank has nothing to do, ever. The dummies take themselves out of the tick list on their
		//first tick; the cap keeps a no-op tick because that is the only hook the base class gives
		//for de-ticking, and one virtual call per tank per tick is not worth a bespoke mechanism.
		ApiUtils.checkForNeedlessTicking(this);
	}

	/**
	 * Sends the gauge reading to the client, but only when it has actually moved far enough to
	 * draw differently.
	 */
	private void onLevelChanged()
	{
		if(world==null||world.isRemote)
			return;
		int division = BuriedTankGeometry.divisionOf(tank.getFluidAmount(), tier.capacity);
		if(division==syncedDivision)
			return;
		syncedDivision = division;
		markContainingBlockForUpdate(null);
	}

	/**
	 * @return whether this part is the fill cap, which is the only part that connects to anything
	 */
	public boolean isCap()
	{
		return pos==tier.capIndex();
	}

	/**
	 * Drives {@code boolean0} in the blockstate, which is how the cap gets its own texture while
	 * every other block of the shell keeps the sheetmetal one.
	 * <p>
	 * A slightly unusual use of the "active" flag -- nothing about a tank is ever active -- but it
	 * is the one per-block boolean the blockstate carries, and "is this the fitting" is exactly the
	 * kind of thing it exists to express. The tanks are the only structures on
	 * {@code petroleum_multiblock} that implement {@link IActiveState}, so nothing else can be
	 * caught by the {@code boolean0=true} variant.
	 */
	@Override
	public boolean getIsActive()
	{
		return formed&&isCap();
	}

	@Override
	public IEProperties.PropertyBoolInverted getBoolProperty(Class<? extends IUsesBooleanProperty> inf)
	{
		return IEProperties.BOOLEANS[0];
	}

	//	=================================
	//		PORTS
	//	=================================

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		if(!formed||!isCap())
			return NO_TANKS;
		T master = master();
		//The cap is the master, so this is normally just "this". The null branch covers the tick
		//between a chunk loading and the structure being whole again.
		return master==null?NO_TANKS: new IFluidTank[]{master.tank};
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		//Any fluid, because a storage tank that only took the fluids this expansion happens to
		//define would be a worse object than the sheetmetal tank it sits beside. FluidTank already
		//refuses a second fluid while it holds a first, which is the only rule a tank needs.
		return isCap();
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		return isCap();
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(!formed||!isCap())
			return null;
		T master = master();
		if(master==null)
			return null;
		FluidStack held = master.tank.getFluid();
		//The gauge is the point of the cap. An invisible tank whose level cannot be read from the
		//surface is a bad object, so this is the one readout that has to be right.
		if(held==null||held.amount <= 0)
			return new String[]{
					tankName(),
					TextFormatting.GRAY+"Empty"+TextFormatting.RESET+"  0 / "+tier.capacity+" mB"};
		return new String[]{
				tankName(),
				held.getLocalizedName(),
				held.amount+" / "+tier.capacity+" mB  ("
						+(int)(100L*held.amount/tier.capacity)+"%)"};
	}

	private String tankName()
	{
		return TextFormatting.GOLD+getTankLabel()+TextFormatting.RESET;
	}

	/**
	 * @return the human name for this tier, used in the cap's readout
	 */
	protected abstract String getTankLabel();

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		//Only the cap answers. Every block answering would turn the whole shell into one enormous
		//comparator face -- and on a buried tank that face is underground, where a comparator
		//placed against it by accident would be very hard to find again.
		if(!isCap())
			return 0;
		T master = master();
		if(master==null||!master.formed)
			return 0;
		int amount = master.tank.getFluidAmount();
		if(amount <= 0)
			return 0;
		return Math.max(1, Math.min(15, (int)(15L*amount/tier.capacity)));
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Full cubes. The cap's fitting is in its model, not in its collision box: a tank cap set
		//into a driveway that a player trips over would be a worse object than a flat one.
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		if(pos >= 0&&pos < tier.cellCount())
		{
			int h = PetroleumGeometry.heightOf(tier.size, pos);
			int l = pos%(tier.depth*tier.width)/tier.width;
			int w = pos%tier.width;
			if(tier.isCap(h, l, w))
				return new ItemStack(IEContent.blockPetroleumDevice, 1,
						BlockTypes_PetroleumDevice.TANK_FILL_CAP.getMeta());
		}
		//Everything that is not the cap is wall, including a block whose structure index has
		//somehow been lost: handing back sheetmetal is the safe way to be wrong, because the cap
		//is the rarer and more expensive of the two.
		return new ItemStack(IEContent.blockSheetmetal, 1, getWallMetal().getMeta());
	}

	/**
	 * @return the metal this tier's shell is built from, for disassembly drops
	 */
	protected abstract BlockTypes_MetalsAll getWallMetal();

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		//Written by the cap only, and read wherever it turns up: a dummy that somehow carries a
		//tank tag reads it into a tank nobody asks about, which is harmless, while skipping it on
		//the client would leave the gauge blank.
		if(nbt.hasKey("tank"))
			tank.readFromNBT(nbt.getCompoundTag("tank"));
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		//Only the cap holds contents, so only the cap writes them. On a bulk depot that is one
		//tank tag rather than two hundred and ninety empty ones, every time the chunk saves.
		if(isCap())
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
	}
}
