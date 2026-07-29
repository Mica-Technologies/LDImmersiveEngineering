/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.petroleum.FuelDispensedEvent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IGuiTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Gas Station Pump: the block that turns a refinery into a forecourt.
 * <p>
 * Everything else in this expansion moves fuel between machines. This is the one piece that hands
 * it to a <em>person</em> -- into a jerrycan, a Mining Drill, a Chemthrower, or, through the
 * nozzle, into a Portable Generator standing across the yard. That is a different kind of object
 * from a pipe, and it is what makes a station a place a player goes back to rather than a
 * decoration.
 * <p>
 * <strong>It does not tick.</strong> A pump spends nearly all of its life doing nothing at all,
 * and a forecourt is several of them; dispensing happens on the interaction that asks for it. The
 * "flowing over a few seconds" theatre in the design notes was traded for that -- a sound and a
 * meter that moves are enough, and a ticking pump per forecourt is not.
 * <p>
 * <strong>The price is not a currency.</strong> This fork implements no money. The price is a
 * number the owner sets and the pump reports through {@link FuelDispensedEvent}, for a server
 * whose economy plugin knows what to do with it. Shipping half an economy inside a tech mod would
 * be worse than shipping none.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityGasPump extends TileEntityIEBase implements IBlockOverlayText,
		IComparatorOverride, IPlayerInteraction, IDirectionalTile, IGuiTile
{
	/**
	 * Sixteen buckets. Deep enough that a pump piped to nothing is still useful for an afternoon,
	 * shallow enough that a forecourt worth the name is plumbed to a buried tank.
	 */
	public static final int CAPACITY = 16000;
	/**
	 * The furthest the nozzle will reach from the pump it is racked on.
	 * <p>
	 * Five blocks in the design notes; eight here, because a canopy over two pump islands is
	 * already six blocks across and a hose that will not reach the far side of your own forecourt
	 * is a worse object than a slightly generous one.
	 */
	public static final int NOZZLE_RANGE = 8;
	/**
	 * City mode: the token amount a fill actually costs the tank. Same trade the generators make.
	 */
	private static final int CITY_SIP = 1;

	public final FluidTank tank = new FluidTank(CAPACITY)
	{
		@Override
		protected void onContentsChanged()
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	};

	public EnumFacing facing = EnumFacing.NORTH;
	/**
	 * Per bucket, in whatever unit the server's economy plugin uses. Zero -- the default -- reads
	 * as "free", which is the right default for a single-player world.
	 */
	private int price;
	/**
	 * The odometer. Persisted and never reset by anything but the button that says so, because
	 * the whole point of a lifetime counter is that it is a lifetime counter.
	 */
	private long lifetimeDispensed;

	//	=================================
	//		DISPENSING
	//	=================================

	/**
	 * Fills a held container from the pump.
	 *
	 * @return millibuckets actually handed over
	 */
	public int fillItem(EntityPlayer player, EnumHand hand)
	{
		ItemStack held = player.getHeldItem(hand);
		if(held.isEmpty())
			return 0;
		//	=================================
		//	Always one, always written back.
		//	=================================
		//
		// This used to wrap the player's own stack when they held exactly one, on the theory that
		// filling it in place was tidier. It is not tidy, it is wrong: an NBT-backed container like
		// the jerrycan does mutate in place, but a bucket-like one does not -- FluidBucketWrapper
		// swaps the item its wrapper holds and leaves the original stack untouched, so the fill
		// only exists in getContainer(). The pump debited its tank, the player got nothing, and the
		// fuel was gone. Copying and writing the result back is correct for both kinds.
		ItemStack one = copyOne(held);
		IFluidHandlerItem handler = FluidUtil.getFluidHandler(one);
		if(handler==null)
			return 0;
		FluidStack available = tank.getFluid();
		if(available==null||available.amount <= 0)
			return 0;

		int room = handler.fill(available.copy(), false);
		int granted = authorise(player, available, room, describeItem(held));
		if(granted <= 0)
			return 0;
		FluidStack offered = available.copy();
		offered.amount = granted;
		int accepted = handler.fill(offered, true);
		if(accepted <= 0)
			return 0;

		ItemStack filled = handler.getContainer();
		if(held.getCount()==1)
			//Straight back into the hand, so a filled bucket does not wander off to another hotbar
			//slot the way addItemStackToInventory would send it.
			player.setHeldItem(hand, filled);
		else
		{
			held.shrink(1);
			if(!player.inventory.addItemStackToInventory(filled))
				player.dropItem(filled, false);
		}
		spend(accepted);
		return accepted;
	}

	/**
	 * Fills any block or entity that will take fluid. This is the nozzle's path.
	 *
	 * @return millibuckets actually handed over
	 */
	public int fillTarget(EntityPlayer player, @Nullable TileEntity block, @Nullable Entity entity,
						  @Nullable EnumFacing side)
	{
		IFluidHandler handler = null;
		String description = null;
		if(block!=null&&block.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side))
		{
			handler = block.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side);
			description = block.getBlockType()==null?"block": block.getBlockType().getTranslationKey();
		}
		else if(entity!=null&&entity.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side))
		{
			//Entities are handled the same way as blocks so that a vehicle, if one is ever added,
			//needs no change here at all. That is the whole reason this method takes both.
			handler = entity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side);
			description = entity.getName();
		}
		if(handler==null)
			return 0;

		FluidStack available = tank.getFluid();
		if(available==null||available.amount <= 0)
			return 0;
		int room = handler.fill(available.copy(), false);
		int granted = authorise(player, available, room, description);
		if(granted <= 0)
			return 0;
		FluidStack offered = available.copy();
		offered.amount = granted;
		int accepted = handler.fill(offered, true);
		spend(accepted);
		return accepted;
	}

	/**
	 * Runs the transaction past whatever the server's economy is, and returns what it allows.
	 * <p>
	 * Fired before anything moves, so a listener that finds the player cannot pay simply stops
	 * the sale rather than having to claw fuel back afterwards. A listener that lowers the amount
	 * gets what it asked for; one that raises it gets no more than the pump actually holds.
	 */
	private int authorise(EntityPlayer player, FluidStack available, int wanted, @Nullable String target)
	{
		int capped = Math.min(wanted, available.amount);
		if(capped <= 0||world==null||world.isRemote)
			return 0;
		String name = available.getFluid()==null?"": available.getFluid().getName();
		FuelDispensedEvent event = new FuelDispensedEvent(player, getPos(), name, capped, price, target);
		if(MinecraftForge.EVENT_BUS.post(event))
			return 0;
		return Math.min(event.getAmount(), available.amount);
	}

	/**
	 * Debits the tank and advances the odometer.
	 * <p>
	 * City mode takes a token sip instead of the real amount: the meter still turns, the sound
	 * still plays, the lifetime counter still climbs, and the tank never runs dry. That is the
	 * trade city mode makes everywhere else, and this is the block it suits best -- a convincing
	 * station in a roleplay pack with no industry behind it at all.
	 */
	private void spend(int amount)
	{
		if(amount <= 0)
			return;
		tank.drainInternal(CityMode.petroleum()?Math.min(CITY_SIP, amount): amount, true);
		lifetimeDispensed += amount;
		markDirty();
		//One block update per dispense, which is a discrete gesture -- somebody filling a container,
		//once, with a sound to match -- rather than a per-tick cost. Without it the odometer in an
		//open GUI sits still while fuel visibly leaves the tank.
		markContainingBlockForUpdate(null);
		if(world!=null)
			world.playSound(null, getPos(), net.minecraft.init.SoundEvents.BLOCK_BREWING_STAND_BREW,
					SoundCategory.BLOCKS, 0.6F, 1.4F);
	}

	private static ItemStack copyOne(ItemStack stack)
	{
		ItemStack one = stack.copy();
		one.setCount(1);
		return one;
	}

	private static String describeItem(ItemStack stack)
	{
		return stack.getDisplayName();
	}

	//	=================================
	//		SETTINGS
	//	=================================

	public int getPrice()
	{
		return price;
	}

	public void setPrice(int price)
	{
		this.price = Math.max(0, Math.min(1000000, price));
		markDirty();
		markContainingBlockForUpdate(null);
	}

	public long getLifetimeDispensed()
	{
		return lifetimeDispensed;
	}

	public void resetOdometer()
	{
		lifetimeDispensed = 0;
		markDirty();
		//Resynced, exactly as setPrice does. Without this the server forgot the total and the client
		//went on showing whatever it had, so the button that says "Reset meter" appeared to do
		//nothing at all -- which is how it was reported.
		markContainingBlockForUpdate(null);
	}

	//	=================================
	//		INTERACTION
	//	=================================

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		//A held container is filled directly. That is the common case -- a bucket, a jerrycan, a
		//drill -- and making the player fetch the nozzle for it would be ceremony, not immersion.
		if(!heldItem.isEmpty()&&FluidUtil.getFluidHandler(copyOne(heldItem))!=null)
		{
			if(!world.isRemote)
				fillItem(player, hand);
			return true;
		}
		//Everything else opens the panel, which is where the price, the level and the odometer are.
		return false;
	}

	//	=================================
	//		GUI
	//	=================================

	@Override
	public boolean canOpenGui()
	{
		return true;
	}

	@Override
	public int getGuiID()
	{
		return Lib.GUIID_GasPump;
	}

	@Nullable
	@Override
	public TileEntity getGuiMaster()
	{
		return this;
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		FluidStack held = tank.getFluid();
		String level = held==null||held.amount <= 0
				?TextFormatting.GRAY+"Empty"+TextFormatting.RESET
				: held.getLocalizedName()+"  "+held.amount+" / "+CAPACITY+" mB";
		return new String[]{
				level,
				price > 0?"Price: "+price+" per bucket": TextFormatting.GRAY+"No price set"+TextFormatting.RESET
		};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(tank.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, Math.min(15, 15*tank.getFluidAmount()/CAPACITY));
	}

	//	=================================
	//		PLACEMENT
	//	=================================

	@Override
	public EnumFacing getFacing()
	{
		return facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		this.facing = facing;
	}

	@Override
	public int getFacingLimitation()
	{
		//Horizontal, from the placer's yaw: a pump is furniture and wants to face the road, not
		//whichever face happened to be clicked.
		return 2;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return true;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ, EntityLivingBase entity)
	{
		return true;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return axis==EnumFacing.UP||axis==EnumFacing.DOWN;
	}

	//	=================================
	//		CAPABILITY
	//	=================================

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing)
	{
		return capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
				||super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing)
	{
		//Every face, fill and drain both: the pump is plumbed from underneath in a real forecourt
		//and from whichever side is convenient in a Minecraft one, and refusing a face would only
		//make the buried tank harder to connect for no gain.
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tank);
		return super.getCapability(capability, facing);
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		nbt.setInteger("price", price);
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		//Sent to the client as well as saved. It used to be save-only, on the reasoning that a
		//lifetime total is bookkeeping rather than something the world needs -- but the pump's own
		//GUI reads it, and the GUI is a client. The client's copy was therefore always zero, so the
		//odometer read "0 mB dispensed" no matter how much fuel had gone through, and resetting it
		//changed nothing visible because it was already showing the reset value.
		nbt.setLong("dispensed", lifetimeDispensed);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
		price = nbt.getInteger("price");
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		lifetimeDispensed = nbt.getLong("dispensed");
	}
}
