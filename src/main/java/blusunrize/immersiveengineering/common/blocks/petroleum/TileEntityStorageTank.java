/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.CityModeTank;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One block of a Storage Tank: a hollow box of any rectangular shape holding what it encloses.
 * <p>
 * <strong>This deliberately does not extend {@code TileEntityMultiblockPart}.</strong> That class
 * carries a fixed {@code int[] size} and packs a cell's position into a single index against it,
 * which is exactly right for a structure whose shape is known in advance and cannot express one
 * whose shape the player chooses. Instead every block of a formed tank remembers the position of
 * its master, and only the master holds the fluid, the dimensions and the capability.
 * <p>
 * The cost of that choice is that a tank is only as coherent as its master's chunk: a wall block
 * whose master is unloaded reports nothing rather than guessing. That is the honest behaviour --
 * the alternative is a tank that appears empty from one end and full from the other.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityStorageTank extends TileEntityIEBase implements IComparatorOverride,
		IStatusLineProvider, IPlayerInteraction
{
	/**
	 * Where this block's master is, or null while the block is loose steel.
	 * <p>
	 * The master's own copy points at itself. Storing it rather than deriving it is what lets a
	 * wall block answer a capability request without walking anything.
	 */
	@Nullable
	public BlockPos master;

	/** Set on the master only: the bounding box of the shell, in blocks. */
	public int width, height, depth;

	/** The fluid, on the master only. Null on a wall block and on an unformed one. */
	@Nullable
	public CityModeTank tank;

	/**
	 * The fluid last sent to the client, so the tank can tell a change worth a packet from the
	 * hundreds of millibucket-sized ones a pipe makes. Transient: it describes what the client has
	 * been told, which is nothing at all after a reload.
	 */
	@Nullable
	private transient String syncedKind;

	public boolean isMaster()
	{
		return master!=null&&master.equals(getPos());
	}

	public boolean isFormed()
	{
		return master!=null;
	}

	/**
	 * @return the tile entity holding this tank's fluid, or null if the tank is unformed or its
	 * master is in an unloaded chunk
	 */
	@Nullable
	public TileEntityStorageTank getMaster()
	{
		if(master==null||world==null)
			return null;
		if(isMaster())
			return this;
		if(!world.isBlockLoaded(master))
			return null;
		TileEntity te = world.getTileEntity(master);
		return te instanceof TileEntityStorageTank?(TileEntityStorageTank)te: null;
	}

	/**
	 * Turn this block into the master of a tank of the given shape.
	 */
	public void formAsMaster(int width, int height, int depth)
	{
		this.master = getPos();
		this.width = width;
		this.height = height;
		this.depth = depth;
		int capacity = StorageTankGeometry.capacity(width, height, depth);
		//Kept rather than replaced when a tank is re-formed at the same size, so hammering a tank
		//you have just enlarged does not pour its contents on the floor.
		FluidStack existing = tank==null?null: tank.getFluid();
		this.tank = newTank(capacity);
		if(existing!=null)
			this.tank.fill(existing, true);
		markDirty();
		markContainingBlockForUpdate(null);
	}

	/**
	 * The master's tank, wired so that changing it saves and shows.
	 * <p>
	 * The override is not decoration. {@link #getCapability} hands this tank straight out, so a pipe
	 * or a Fluid Outlet filling the tank mutates it with nothing else in the call: without an
	 * {@code onContentsChanged} the chunk was never marked dirty, and a tank filled only by pipe
	 * could go back to disk empty. The client copy went equally stale, so the level in the readout
	 * stopped moving until something else happened to resend the block.
	 */
	private CityModeTank newTank(int capacity)
	{
		return new CityModeTank(capacity)
		{
			@Override
			protected void onContentsChanged()
			{
				markDirty();
				//Guarded, because readCustomNBT builds the tank and then reads into it, which lands
				//here with no world attached yet -- and markContainingBlockForUpdate dereferences it.
				if(world==null||world.isRemote)
					return;
				//Only when what it holds changes kind, not every millibucket. A pipe filling this
				//tank changes the amount on most ticks, and a block update per tick for a number
				//nothing draws is exactly the per-tick cost the rest of this feature avoids. The
				//buried tanks throttle to gauge divisions for the same reason; they have a gauge to
				//draw and this has not.
				FluidStack now = getFluid();
				String kind = now==null||now.amount <= 0||now.getFluid()==null
						?null: now.getFluid().getName();
				if(java.util.Objects.equals(kind, syncedKind))
					return;
				syncedKind = kind;
				markContainingBlockForUpdate(null);
			}
		};
	}

	public void formAsWall(BlockPos master)
	{
		this.master = master;
		this.tank = null;
		markDirty();
		markContainingBlockForUpdate(null);
	}

	/**
	 * Break the tank apart, from any block of it.
	 * <p>
	 * Walks the shell from the master rather than searching, because the shape is known once you
	 * have the master and a search would have to guess at it again.
	 */
	public void disassemble()
	{
		if(world==null||world.isRemote)
			return;
		TileEntityStorageTank head = getMaster();
		if(head==null)
		{
			//The master is gone or unloaded. Forget the link locally so this block is at least not
			//pointing at something that is not there.
			master = null;
			tank = null;
			markDirty();
			return;
		}
		BlockPos origin = head.getPos();
		for(int x = 0; x < head.width; x++)
			for(int y = 0; y < head.height; y++)
				for(int z = 0; z < head.depth; z++)
				{
					if(!StorageTankGeometry.isShell(x, y, z, head.width, head.height, head.depth))
						continue;
					BlockPos at = origin.add(x, y, z);
					if(!world.isBlockLoaded(at))
						continue;
					TileEntity te = world.getTileEntity(at);
					if(!(te instanceof TileEntityStorageTank))
						continue;
					TileEntityStorageTank part = (TileEntityStorageTank)te;
					if(!origin.equals(part.master))
						continue;
					part.master = null;
					part.tank = null;
					part.markDirty();
					part.markContainingBlockForUpdate(null);
				}
	}

	/**
	 * A hammer forms the tank; a hammer on a formed one takes it apart again.
	 * <p>
	 * Both on the same gesture, because a player who has just built the wrong shape wants to undo
	 * it with the tool already in their hand, and a tank is otherwise only dismantled by breaking
	 * a wall -- which spills what is in it.
	 */
	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		if(!Utils.isHammer(heldItem))
			return fillFromHeld(player, hand);
		if(world.isRemote)
			return true;
		if(isFormed())
		{
			//Contents are kept on the master rather than dropped: taking a tank apart to make it
			//bigger is the ordinary reason to do it, and losing a full tank of diesel for it would
			//make the gesture something nobody dared use.
			disassemble();
			ChatUtils.sendServerNoSpamMessages(player,
					new TextComponentTranslation(Lib.CHAT_INFO+"petroleum.tankDisassembled"));
			return true;
		}
		StorageTankFormation.Found found = StorageTankFormation.find(getPos(), this::isFreeTankBlock);
		if(found==null)
		{
			ChatUtils.sendServerNoSpamMessages(player,
					new TextComponentTranslation(Lib.CHAT_INFO+"petroleum.tankNotAShell"));
			return true;
		}
		form(found);
		ChatUtils.sendServerNoSpamMessages(player,
				new TextComponentTranslation(Lib.CHAT_INFO+"petroleum.tankFormed",
						found.width+"x"+found.height+"x"+found.depth, found.capacity()));
		return true;
	}

	/**
	 * Bucket, can or any other fluid container, against any wall of a formed tank.
	 * <p>
	 * Without this the click falls through to the item, and a vanilla bucket answers a right-click
	 * on a block by placing its contents against the face you clicked. Filling a tank by hand is the
	 * obvious first thing anybody tries, and what it did instead was pour a source block of propane
	 * down the outside of the tank -- once per attempt. The Sheetmetal Tank this sits beside has
	 * always handled the gesture; not handling it here was the whole of the bug.
	 *
	 * @return true if the held item was a container this tank did something with
	 */
	private boolean fillFromHeld(EntityPlayer player, EnumHand hand)
	{
		TileEntityStorageTank head = getMaster();
		if(head==null||head.tank==null)
			return false;
		//Through Utils rather than Forge's helper directly, so a tank that is full or already holds
		//something else still swallows the click instead of letting the bucket empty itself onto the
		//wall. Saving and resync are the tank's own job -- see newTank.
		return Utils.interactWithTank(player, hand, head.tank);
	}

	/**
	 * @return true if that position holds a tank wall that is not already part of a tank
	 */
	private boolean isFreeTankBlock(BlockPos pos)
	{
		if(!world.isBlockLoaded(pos))
			return false;
		TileEntity te = world.getTileEntity(pos);
		return te instanceof TileEntityStorageTank&&!((TileEntityStorageTank)te).isFormed();
	}

	private void form(StorageTankFormation.Found found)
	{
		TileEntity headTile = world.getTileEntity(found.origin);
		if(!(headTile instanceof TileEntityStorageTank))
			return;
		((TileEntityStorageTank)headTile).formAsMaster(found.width, found.height, found.depth);
		for(int x = 0; x < found.width; x++)
			for(int y = 0; y < found.height; y++)
				for(int z = 0; z < found.depth; z++)
				{
					if(!StorageTankGeometry.isShell(x, y, z, found.width, found.height, found.depth))
						continue;
					BlockPos at = found.origin.add(x, y, z);
					if(at.equals(found.origin))
						continue;
					TileEntity te = world.getTileEntity(at);
					if(te instanceof TileEntityStorageTank)
						((TileEntityStorageTank)te).formAsWall(found.origin);
				}
	}

	// ------------------------------------------------------------------
	// Fluid
	// ------------------------------------------------------------------

	@Override
	public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
	{
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			//Every wall block accepts, so a tank can be plumbed anywhere along its side rather than
			//only at one fitting. That is the difference between this and the buried tiers, where
			//the single cap is the whole point.
			return isFormed();
		return super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
	{
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
		{
			TileEntityStorageTank head = getMaster();
			if(head==null||head.tank==null)
				return null;
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast((IFluidHandler)head.tank);
		}
		return super.getCapability(capability, facing);
	}

	// ------------------------------------------------------------------
	// Readouts
	// ------------------------------------------------------------------

	@Override
	public int getComparatorInputOverride()
	{
		TileEntityStorageTank head = getMaster();
		if(head==null||head.tank==null)
			return 0;
		return StorageTankGeometry.comparatorLevel(head.tank.getFluidAmount(),
				head.tank.getCapacity());
	}

	@Override
	public List<String> getStatusLines()
	{
		List<String> lines = new ArrayList<>();
		TileEntityStorageTank head = getMaster();
		if(head==null||head.tank==null)
		{
			//Said plainly, because loose tank blocks and a formed tank look identical and the fix
			//-- hammer any wall of it -- is not guessable.
			lines.add(TextFormatting.GOLD+"Storage Tank"+TextFormatting.RESET+": "
					+TextFormatting.YELLOW+"not formed"+TextFormatting.RESET);
			lines.add("Build a hollow box and strike a wall with an Engineer's Hammer.");
			return lines;
		}
		lines.add(TextFormatting.GOLD+"Storage Tank"+TextFormatting.RESET+": "
				+head.width+"x"+head.height+"x"+head.depth);
		FluidStack held = head.tank.getFluid();
		if(held==null||held.amount <= 0)
			lines.add("Empty -- "+head.tank.getCapacity()+" mB capacity");
		else
			lines.add(held.getLocalizedName()+": "+held.amount+" / "+head.tank.getCapacity()+" mB"
					+(CityMode.tanks()?TextFormatting.AQUA+"  [bottomless]"+TextFormatting.RESET: ""));
		return lines;
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		if(master!=null)
		{
			nbt.setLong("master", master.toLong());
			if(isMaster())
			{
				nbt.setInteger("width", width);
				nbt.setInteger("height", height);
				nbt.setInteger("depth", depth);
				if(tank!=null)
					nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
			}
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		master = nbt.hasKey("master")?BlockPos.fromLong(nbt.getLong("master")): null;
		tank = null;
		if(master==null||!isMaster())
			return;
		//Clamped to the shapes the geometry allows, because these came off disk. A tank that loaded
		//a 0x0x0 would have zero capacity and swallow whatever was piped into it.
		width = clampSide(nbt.getInteger("width"));
		height = clampSide(nbt.getInteger("height"));
		depth = clampSide(nbt.getInteger("depth"));
		tank = newTank(StorageTankGeometry.capacity(width, height, depth));
		if(nbt.hasKey("tank"))
			tank.readFromNBT(nbt.getCompoundTag("tank"));
	}

	private static int clampSide(int side)
	{
		return Math.max(StorageTankGeometry.MIN_SIDE,
				Math.min(StorageTankGeometry.MAX_SIDE, side));
	}
}
