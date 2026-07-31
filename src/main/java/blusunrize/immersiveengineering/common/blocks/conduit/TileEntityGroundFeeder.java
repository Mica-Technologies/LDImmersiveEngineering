/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPropertyPassthrough;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A ground feeder: the hole a conduit run goes through, wearing whatever is around it.
 * <p>
 * A run stays on one surface, and a plane change goes through a junction box -- a real block you can
 * point at, which is the right answer indoors where the box reads as hardware. Outdoors it is the
 * wrong answer twice over: a steel box sitting in a lawn is the most conspicuous thing for a hundred
 * blocks, and there is no surface to change <em>to</em> until the run is already underground. This is
 * the other answer. It passes a bundle straight through along one axis and pretends to be dirt.
 * <p>
 * <strong>It is scenery, not hardware.</strong> The feeder is never a node in the wire graph, holds
 * no energy, has no {@code update} and does nothing per tick. {@link ConduitRoute} slides straight
 * through it, so a run crossing four feeders is still one {@code Connection} between the same two
 * junction boxes it was before -- the feeders only make it four blocks longer. Everything that made
 * conduit cheap stays true with feeders in it, because from the graph's side there is nothing there.
 * <p>
 * <strong>No breakouts.</strong> A patched face on a junction box wears a plate in its conductor's
 * colour so the box says how it is wired from across the room. Hiding a box would throw exactly that
 * away and leave a base whose wiring could only be found by digging. So a feeder carries the whole
 * bundle and splits nothing: if you want a conductor out, that is what the box is for, and it stays
 * where you can see it.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class TileEntityGroundFeeder extends TileEntityIEBase implements IDirectionalTile,
		INeighbourChangeTile, IPlayerInteraction, IBlockOverlayText, IStatusLineProvider,
		IPropertyPassthrough
{
	/** Where the disguise lives in NBT. A subtag, because a block state writes several keys. */
	public static final String DISGUISE = "disguise";

	/**
	 * What a feeder falls back to when it has found nothing worth wearing -- an unmimicked block
	 * shows its own bare model, which is the honest thing for one sitting in mid-air.
	 */
	public static final IBlockState BARE = Blocks.AIR.getDefaultState();

	/**
	 * One end of the pair of faces a run passes between. Only the axis matters; keeping a facing
	 * rather than an axis is what lets IE's stock hammer rotation drive it without a special case.
	 */
	private EnumFacing facing = EnumFacing.UP;

	/**
	 * What it is currently pretending to be, or {@link #BARE}. Written to the description packet, so
	 * the client renders it without having to work anything out for itself.
	 */
	private IBlockState disguise = BARE;

	/**
	 * True once a player has told it what to wear. A pinned feeder stops surveying: the survey is a
	 * guess, and a guess that overrules the person who corrected it is not a feature.
	 */
	private boolean pinned;

	public EnumFacing.Axis getAxis()
	{
		return facing.getAxis();
	}

	public IBlockState getDisguise()
	{
		return disguise;
	}

	public boolean isPinned()
	{
		return pinned;
	}

	//	=================================
	//		WHAT IT CAN WEAR
	//	=================================

	/**
	 * Whether a feeder could pass for that block.
	 * <p>
	 * Full, opaque, ordinary cubes only. The line is drawn there because a feeder is a cube and
	 * always will be: wearing a fence's model would draw a fence with a solid hitbox, and wearing a
	 * chest's would draw a chest whose lid never opens -- both worse than the bare block they
	 * replace, because both look like a bug rather than a disguise.
	 * <p>
	 * The conduit block itself is excluded outright. Two feeders side by side would otherwise each
	 * try to wear the other, and the model would ask itself what it looked like.
	 */
	public static boolean canWear(@Nullable IBlockState state)
	{
		if(state==null||state.getBlock()==Blocks.AIR)
			return false;
		//A block drawn by anything but an ordinary model -- a fluid, a TESR, an invisible block --
		//has no quads to borrow.
		if(state.getRenderType()!=EnumBlockRenderType.MODEL)
			return false;
		if(state.getBlock() instanceof BlockConduit||state.getBlock().hasTileEntity(state))
			return false;
		return state.isFullCube()&&state.isOpaqueCube();
	}

	//	=================================
	//		THE SURVEY
	//	=================================

	/**
	 * Look around and put on whatever fits, unless a player has already said.
	 *
	 * @return true if the disguise changed, so the caller can skip a block update it does not need
	 */
	public boolean resurvey()
	{
		if(world==null||pinned)
			return false;
		IBlockState found = ConduitCamouflage.choose(getPos(), new Surroundings());
		//Finding nothing keeps whatever it had rather than stripping back to bare. The survey answers
		//"nothing" for two very different situations -- a feeder hanging in open air, and a feeder
		//whose neighbours are simply not loaded yet -- and it cannot tell them apart. Going bare on
		//the second one would undress a feeder at a chunk edge on every load, and nothing would ever
		//put it back, because settled terrain never fires another neighbour change.
		if(found==null||found==disguise)
			return false;
		disguise = found;
		return true;
	}

	/**
	 * The survey's view of the world.
	 */
	private final class Surroundings implements ConduitCamouflage.Neighbourhood<IBlockState>
	{
		@Nullable
		@Override
		public IBlockState candidateAt(BlockPos at)
		{
			//An unloaded chunk answers "nothing there" rather than being looked at: asking would
			//generate it, and a row of feeders along a border would drag chunks in behind it.
			if(!world.isBlockLoaded(at))
				return null;
			IBlockState state = world.getBlockState(at);
			return canWear(state)?state: null;
		}

		@Override
		public boolean isExposed(BlockPos at)
		{
			for(EnumFacing face : EnumFacing.VALUES)
			{
				BlockPos beside = at.offset(face);
				if(!world.isBlockLoaded(beside))
					continue;
				if(!world.getBlockState(beside).isOpaqueCube())
					return true;
			}
			return false;
		}
	}

	/**
	 * How far away a change can still move the answer: the survey reads one block out, and asks
	 * whether each of those is exposed, which reads one further. Anything past that cannot matter,
	 * and saying so is what stops a redstone line two blocks away re-surveying a feeder forever.
	 */
	private static final int INFLUENCE = ConduitCamouflage.RADIUS+1;

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		if(world==null||world.isRemote||pinned)
			return;
		if(Math.abs(other.getX()-getPos().getX()) > INFLUENCE
				||Math.abs(other.getY()-getPos().getY()) > INFLUENCE
				||Math.abs(other.getZ()-getPos().getZ()) > INFLUENCE)
			return;
		if(resurvey())
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		//Only a feeder that never found anything tries again on load. One that has settled is left
		//alone: a survey run while the chunk next door is still absent sees a different, smaller
		//neighbourhood, so re-running it every load would let a feeder at a chunk edge change its
		//mind about what it is wearing depending on which way somebody walked into the area.
		if(world!=null&&!world.isRemote&&disguise==BARE&&resurvey())
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	//	=================================
	//		INTERACTION
	//	=================================

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		//Sneak with an empty hand to hand it back to the survey. The same gesture unpatches a
		//junction box, which is the point of reusing it: one thing to remember for both halves of
		//the conduit hardware rather than two.
		if(player.isSneaking()&&heldItem.isEmpty())
		{
			if(!pinned&&disguise==BARE)
				return false;
			if(!world.isRemote)
			{
				pinned = false;
				resurvey();
				markDirty();
				markContainingBlockForUpdate(null);
				player.sendStatusMessage(new TextComponentTranslation(
						"chat.immersiveengineering.info.groundFeeder.auto"), true);
			}
			return true;
		}

		Block held = Block.getBlockFromItem(heldItem.getItem());
		if(held==Blocks.AIR)
			return false;
		//getStateFromMeta rather than anything cleverer: the item's metadata is what tells stone
		//from andesite and oak planks from birch, and that is the whole of the variation a feeder
		//can wear anyway.
		IBlockState wanted = held.getStateFromMeta(heldItem.getMetadata());
		if(!canWear(wanted))
			return false;
		if(!world.isRemote)
		{
			disguise = wanted;
			pinned = true;
			markDirty();
			markContainingBlockForUpdate(null);
		}
		//True either way, so the block in hand is not placed against the feeder as well as being
		//worn by it -- a right-click that both disguised the feeder and buried it under a fresh
		//block would be infuriating to undo.
		return true;
	}

	//	=================================
	//		PLACEMENT AND ROTATION
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
		//Zero: the side clicked. A feeder set into a floor passes a run up and down, and one set
		//into a wall passes it through the wall -- which is what the gesture already says.
		return 0;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return false;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ,
								   EntityLivingBase entity)
	{
		//Unlike a conduit, a feeder can be turned in place: only its axis matters, and getting the
		//axis wrong is the one mistake it is easy to make on a block you cannot see.
		return true;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return false;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		nbt.setBoolean("pinned", pinned);
		//The disguise goes in the description packet too. Working it out client-side instead would
		//be the same survey run twice, and the two would disagree at a chunk edge where the client
		//has terrain the server has not sent yet.
		if(disguise!=BARE)
		{
			NBTTagCompound state = new NBTTagCompound();
			Utils.stateToNBT(state, disguise);
			nbt.setTag(DISGUISE, state);
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		//Bounds-checked rather than trusted: the ordinal comes off disk and EnumFacing.byIndex wraps
		//rather than failing, which would silently turn a feeder's axis under a floor.
		int ordinal = nbt.getInteger("facing");
		facing = ordinal >= 0&&ordinal < EnumFacing.VALUES.length
				?EnumFacing.VALUES[ordinal]: EnumFacing.UP;
		pinned = nbt.getBoolean("pinned");
		if(nbt.hasKey(DISGUISE))
		{
			IBlockState read = Utils.stateFromNBT(nbt.getCompoundTag(DISGUISE));
			//Re-checked on the way in as well as on the way out. Utils.stateFromNBT answers with a
			//bookshelf for a block that is no longer in the game, and a pack losing a mod should
			//leave a bare feeder rather than a wall of bookshelves.
			disguise = canWear(read)?read: BARE;
		}
		else
			disguise = BARE;
	}

	//	=================================
	//		READOUTS
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		return new String[]{describe()};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public List<String> getStatusLines()
	{
		List<String> lines = new ArrayList<>();
		lines.add(TextFormatting.GOLD+"Ground Feeder"+TextFormatting.RESET+": "+describe());
		//Said plainly, because the axis is the one thing about this block you cannot see and the one
		//thing that stops a run joining up.
		lines.add("Passes a bundle along the "+getAxis().getName()+" axis.");
		if(!pinned)
			lines.add(TextFormatting.GRAY+"Sneak-click it empty-handed to re-survey; right-click with "
					+"a block to pin one."+TextFormatting.RESET);
		return lines;
	}

	private String describe()
	{
		if(disguise==BARE)
			return "bare";
		String name = disguise.getBlock().getLocalizedName();
		return pinned?name+" (pinned)": name;
	}
}
