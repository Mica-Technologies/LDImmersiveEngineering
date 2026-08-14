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
import blusunrize.immersiveengineering.client.models.IOBJModelCallback;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IGuiTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.chickenbones.Matrix4;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.model.TRSRTransformation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

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
 * <p>
 * <strong>It is two blocks, assembled with a hammer.</strong> A single cube was reported as "the
 * fuel pump does not look like a fuel pump": a forecourt of them read as a row of crates. Two
 * stacked pumps struck with an Engineer's Hammer become one bowser -- the lower block keeps the
 * fuel, the GUI and the plumbing, the upper one becomes a head that draws nothing of its own, and
 * the OBJ model spanning both is drawn from the lower block. Breaking either takes it apart again.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityGasPump extends TileEntityIEBase implements IBlockOverlayText,
		IComparatorOverride, IPlayerInteraction, IDirectionalTile, IGuiTile,
		IOBJModelCallback<IBlockState>
{
	/**
	 * The group in {@code models/block/petroleum/gas_pump.obj.ie} that is the loose block.
	 * <p>
	 * A blockstate variant names exactly one model, and this block has to be able to look like a
	 * crate before it is assembled and like a pump afterwards -- so both are groups of the same
	 * OBJ and the tile shows one or the other, never both.
	 */
	public static final String GROUP_UNFORMED = "unformed";
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
	/**
	 * Whether this block is part of an assembled pump. Synced, because it decides what is drawn.
	 */
	public boolean formed;
	/**
	 * Set on the upper block of an assembled pump: the head. It draws nothing, holds nothing, and
	 * forwards everything asked of it to the block below.
	 */
	public boolean dummy;

	//	=================================
	//		ASSEMBLY
	//	=================================

	public boolean isFormed()
	{
		return formed;
	}

	public boolean isDummy()
	{
		return formed&&dummy;
	}

	/**
	 * The block that actually holds the fuel: this one, or -- for the head of an assembled pump --
	 * the one below it.
	 * <p>
	 * Never null, so every caller can use it without a guard: a head whose base has somehow gone
	 * answers for itself rather than for nothing, which is an empty pump rather than a crash.
	 */
	@Nonnull
	public TileEntityGasPump getFuelStore()
	{
		if(!isDummy()||world==null)
			return this;
		TileEntity below = world.getTileEntity(getPos().down());
		if(below instanceof TileEntityGasPump&&((TileEntityGasPump)below).formed
				&&!((TileEntityGasPump)below).dummy)
			return (TileEntityGasPump)below;
		return this;
	}

	@Nullable
	private TileEntityGasPump loosePumpAt(BlockPos pos)
	{
		if(world==null||!world.isBlockLoaded(pos))
			return null;
		TileEntity te = world.getTileEntity(pos);
		return te instanceof TileEntityGasPump&&!((TileEntityGasPump)te).formed
				?(TileEntityGasPump)te: null;
	}

	/**
	 * @return the two loose pumps a hammer here would join, base first, or null if there is no
	 * pair
	 */
	@Nullable
	private TileEntityGasPump[] assemblyPair()
	{
		if(formed||world==null)
			return null;
		//Either way round, because a player who has just placed the upper block is holding the
		//hammer over the upper block.
		TileEntityGasPump above = loosePumpAt(getPos().up());
		if(above!=null)
			return new TileEntityGasPump[]{this, above};
		TileEntityGasPump below = loosePumpAt(getPos().down());
		return below!=null?new TileEntityGasPump[]{below, this}: null;
	}

	public boolean canAssemble()
	{
		return assemblyPair()!=null;
	}

	private void assemble(TileEntityGasPump base, TileEntityGasPump head)
	{
		//Whatever the head had in it goes into the base rather than being stranded in a tank
		//nothing can reach any more. Somebody who piped fuel into the wrong block of a pump they
		//were about to assemble should not lose it for that.
		FluidStack held = head.tank.getFluid();
		if(held!=null&&held.amount > 0)
		{
			int moved = base.tank.fill(held.copy(), true);
			if(moved > 0)
				head.tank.drainInternal(moved, true);
		}
		base.formed = true;
		base.dummy = false;
		head.formed = true;
		head.dummy = true;
		//The head keeps the base's facing so that breaking the pump apart leaves two blocks
		//pointing the same way, which is what the player last chose.
		head.facing = base.facing;
		base.syncState();
		head.syncState();
	}

	/**
	 * Takes the pump apart from either of its blocks, leaving both where they are.
	 * <p>
	 * Called on block break rather than by a gesture of its own: the hammer is already spoken for
	 * -- it assembles a loose pair and turns an assembled one -- and a pump is dismantled by
	 * breaking it, exactly as the Storage Tank is.
	 */
	public void disassemble()
	{
		if(world==null||!formed)
			return;
		BlockPos base = dummy?getPos().down(): getPos();
		unform(base);
		unform(base.up());
	}

	private void unform(BlockPos at)
	{
		if(!world.isBlockLoaded(at))
			return;
		TileEntity te = world.getTileEntity(at);
		if(!(te instanceof TileEntityGasPump))
			return;
		TileEntityGasPump pump = (TileEntityGasPump)te;
		if(!pump.formed)
			return;
		pump.formed = false;
		pump.dummy = false;
		pump.syncState();
	}

	private void syncState()
	{
		markDirty();
		markContainingBlockForUpdate(null);
	}

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
		//Asked of the head -- a nozzle taken off the upper block remembers that block -- the base
		//answers. Done here rather than at each call site so that every way of asking for fuel
		//goes to the one tank without knowing the pump has two halves.
		TileEntityGasPump store = getFuelStore();
		if(store!=this)
			return store.fillItem(player, hand);
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
		TileEntityGasPump store = getFuelStore();
		if(store!=this)
			return store.fillTarget(player, block, entity, side);
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
		int capped = GasPumpAccounting.requested(wanted, available.amount);
		if(capped <= 0||world==null||world.isRemote)
			return 0;
		String name = available.getFluid()==null?"": available.getFluid().getName();
		FuelDispensedEvent event = new FuelDispensedEvent(player, getPos(), name, capped, price, target);
		boolean cancelled = MinecraftForge.EVENT_BUS.post(event);
		return GasPumpAccounting.granted(event.getAmount(), available.amount, cancelled);
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
		tank.drainInternal(GasPumpAccounting.tankDebit(amount, CityMode.petroleum()), true);
		lifetimeDispensed += GasPumpAccounting.odometerAdvance(amount);
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
		//Through the store, so the Forecourt Price Sign reads the same number whichever half of a
		//pump happened to be the nearest block to it.
		TileEntityGasPump store = getFuelStore();
		return store==this?price: store.getPrice();
	}

	public void setPrice(int price)
	{
		TileEntityGasPump store = getFuelStore();
		if(store!=this)
		{
			store.setPrice(price);
			return;
		}
		this.price = GasPumpAccounting.clampPrice(price);
		markDirty();
		markContainingBlockForUpdate(null);
	}

	public long getLifetimeDispensed()
	{
		TileEntityGasPump store = getFuelStore();
		return store==this?lifetimeDispensed: store.getLifetimeDispensed();
	}

	public void resetOdometer()
	{
		TileEntityGasPump store = getFuelStore();
		if(store!=this)
		{
			store.resetOdometer();
			return;
		}
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
		//A hammer assembles the pump. The rotation branch in BlockIETileProvider runs before this
		//one and switches itself off through canHammerRotate exactly when there is a pair to join,
		//so a hammer that reaches here always means "put this together".
		if(Utils.isHammer(heldItem))
		{
			TileEntityGasPump[] pair = assemblyPair();
			if(pair==null)
				return false;
			if(!world.isRemote)
			{
				assemble(pair[0], pair[1]);
				ChatUtils.sendServerNoSpamMessages(player,
						new TextComponentTranslation(Lib.CHAT_INFO+"petroleum.pumpAssembled"));
			}
			return true;
		}
		//A held container is filled directly. That is the common case -- a bucket, a jerrycan, a
		//drill -- and making the player fetch the nozzle for it would be ceremony, not immersion.
		if(!heldItem.isEmpty()&&FluidUtil.getFluidHandler(copyOne(heldItem))!=null)
		{
			if(!world.isRemote)
				getFuelStore().fillItem(player, hand);
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
		//The head opens the base's panel: one pump, one window, whichever half of it was clicked.
		return getFuelStore();
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		TileEntityGasPump store = getFuelStore();
		FluidStack held = store.tank.getFluid();
		String level = held==null||held.amount <= 0
				?TextFormatting.GRAY+"Empty"+TextFormatting.RESET
				: held.getLocalizedName()+"  "+held.amount+" / "+CAPACITY+" mB";
		//An assembled pump carries its price on its own display panel, which is the whole point of
		//having one; repeating it beside the crosshair is the "price floating in the air" a
		//playtester asked to be rid of. An unassembled one has no panel to read, so it still says.
		if(formed)
			return new String[]{level};
		return new String[]{
				level,
				price > 0?"Price: "+price+" per bucket": TextFormatting.GRAY+"No price set"+TextFormatting.RESET,
				TextFormatting.GRAY+"Not assembled -- stack two and strike one with an Engineer's Hammer"
						+TextFormatting.RESET
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
		TileEntityGasPump store = getFuelStore();
		return GasPumpAccounting.comparatorLevel(store.tank.getFluidAmount(), CAPACITY);
	}

	//	=================================
	//		PLACEMENT
	//	=================================

	@Override
	public EnumFacing getFacing()
	{
		//One pump, one facing: the model is drawn from the base, so the head reports the base's.
		return getFuelStore().facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		this.facing = facing;
		TileEntityGasPump store = getFuelStore();
		if(store!=this)
		{
			//Turning the head turns the pump. Resent by hand because the rotation that called this
			//only marks the block it was aimed at, and the model hangs off the other one. Both
			//copies are written so that taking the pump apart leaves two blocks agreeing about
			//which way they point.
			store.facing = facing;
			store.syncState();
		}
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
		//A hammer that has something to assemble assembles it; otherwise it is still the tool that
		//turns a block round. An assembled pump therefore keeps turning, which matters more here
		//than on anything else in this feature: the model faces the road, and a pump built facing
		//the wrong way is the first thing anybody will want to fix.
		return !canAssemble();
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
		//make the buried tank harder to connect for no gain. An assembled pump answers with its
		//base's tank from both of its blocks, so a pipe run at head height still lands in the pump
		//rather than in a second tank nobody can see.
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(getFuelStore().tank);
		return super.getCapability(capability, facing);
	}

	//	=================================
	//		MODEL
	//	=================================

	/**
	 * Which parts of the OBJ this block draws.
	 * <p>
	 * Three answers, and between them they are the whole of the visual change: a loose block is
	 * the crate it always was, the base of an assembled pump is the entire bowser, and the head
	 * above it draws nothing at all -- the model that covers it hangs off the base.
	 */
	@Override
	public boolean shouldRenderGroup(IBlockState object, String group)
	{
		if(!formed)
			return GROUP_UNFORMED.equals(group);
		return !dummy&&!GROUP_UNFORMED.equals(group);
	}

	/**
	 * Turns the model to the pump's facing.
	 * <p>
	 * Done here rather than by a facing property on the blockstate because {@code petroleum_device}
	 * deliberately has none -- adding one would give every meta on the block four states and a
	 * rotation submap that applies to all of them. The model is authored facing north, which is
	 * also this tile's default facing, so an unrotated pump is a north-facing pump.
	 */
	@Override
	public Optional<TRSRTransformation> applyTransformations(IBlockState object, String group,
															 Optional<TRSRTransformation> transform)
	{
		if(!formed||dummy)
			return transform;
		Matrix4 mat = transform.isPresent()?new Matrix4(transform.get().getMatrix()): new Matrix4();
		mat = mat.translate(.5, 0, .5).rotate(modelRotation(), 0, 1, 0).translate(-.5, 0, -.5);
		return Optional.of(new TRSRTransformation(mat.toMatrix4f()));
	}

	/**
	 * Radians about +Y that carry a north-facing model round to {@link #facing}.
	 * <p>
	 * Shared with the renderer that draws the price on the panel, in degrees, so the text and the
	 * panel it sits on can never disagree about which way the pump is pointing.
	 */
	public double modelRotation()
	{
		return Math.toRadians(modelRotationDegrees());
	}

	public float modelRotationDegrees()
	{
		return GasPumpAccounting.modelRotation(facing.getHorizontalIndex());
	}

	@Override
	public String getCacheKey(IBlockState object)
	{
		//Every input the two methods above read. Without it the baked quads for one pump would be
		//handed to the next one at a different facing.
		return (formed?(dummy?"head": "pump"): "loose")+","+facing.getIndex();
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		//Both sent as well as saved: they decide what the block draws, and a client that did not
		//know would show a crate standing in a formed pump's place until the chunk reloaded.
		nbt.setBoolean("formed", formed);
		nbt.setBoolean("dummy", dummy);
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
		formed = nbt.getBoolean("formed");
		dummy = nbt.getBoolean("dummy");
		price = nbt.getInteger("price");
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		lifetimeDispensed = nbt.getLong("dispensed");
	}
}
