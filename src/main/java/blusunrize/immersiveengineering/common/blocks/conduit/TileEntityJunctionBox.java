/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.TargetingInfo;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSet;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ConduitWireType;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A junction box: where a bundle splits.
 * <p>
 * It is the endpoint of a conduit run and a patch panel for what comes down it. Dye a face and the
 * conductor of that colour leaves by it, where an ordinary LV, MV or HV connector picks it up --
 * which is how a bundle reaches machines without the conduit ever needing to know what a machine
 * is. The connector's own tier is what caps that circuit, so the tier of a channel is a thing the
 * player chooses by hanging hardware rather than by editing a number.
 * <p>
 * A box passes the whole bundle through whatever is patched. One dropped in mid-run to turn a
 * corner needs no configuration at all, which is the only behaviour that would not be infuriating.
 * <p>
 * Runs are discovered rather than drawn: laying conduit between two boxes creates the connection,
 * and breaking it removes it. There is no coil and no linking tool. The connection carries the
 * sixteen channels; the hundred conduit blocks in between are not nodes in the wire graph, which is
 * the decision this whole feature's cost rests on.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class TileEntityJunctionBox extends TileEntityIEBase implements IImmersiveConnectable,
		INeighbourChangeTile, IPlayerInteraction, IBlockOverlayText, IStatusLineProvider
{
	private final ConduitPatch patch = new ConduitPatch();

	public ConduitPatch getPatch()
	{
		return patch;
	}

	// ------------------------------------------------------------------
	// Runs
	// ------------------------------------------------------------------

	/**
	 * Rebuild this box's connections from the conduit actually on the walls.
	 * <p>
	 * Both sides of a run are torn down and rebuilt rather than patched, because working out which
	 * single conduit block changed and what that implies is far more code than re-walking a run,
	 * and this happens when somebody places a block rather than every tick.
	 */
	public void rebuildRuns()
	{
		if(world==null||world.isRemote)
			return;
		Map<BlockPos, Integer> peers = ConduitRoute.junctionsFrom(getPos(), new WorldProbe());
		Set<BlockPos> wanted = new HashSet<>(peers.keySet());

		//Drop what is no longer reachable first, so a run rerouted onto a different wall does not
		//briefly exist twice.
		for(Connection existing : new ArrayList<>(currentBundles()))
		{
			BlockPos other = existing.end;
			if(!wanted.remove(other))
				ImmersiveNetHandler.INSTANCE.removeConnectionAndDrop(existing, world, getPos());
		}
		for(BlockPos peer : wanted)
		{
			TileEntity te = world.getTileEntity(peer);
			if(!(te instanceof TileEntityJunctionBox))
				continue;
			Connection made = ImmersiveNetHandler.INSTANCE.addAndGetConnection(world, getPos(), peer,
					peers.get(peer), ConduitWireType.INSTANCE);
			//Sixteen conductors, all present. A breakout says where one leaves, not whether it
			//exists -- see ConduitPatch for why carriage cannot be derived from what is patched.
			made.channels = fullBundle();
			Connection back = ImmersiveNetHandler.INSTANCE.getReverseConnection(
					world.provider.getDimension(), made);
			if(back!=null)
				back.channels = fullBundle();
			((TileEntityJunctionBox)te).markContainingBlockForUpdate(null);
		}
		markContainingBlockForUpdate(null);
	}

	private static ChannelSet fullBundle()
	{
		ChannelSet set = new ChannelSet();
		for(WireChannel channel : WireChannel.VALUES)
			set.patch(channel, new blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSpec(
					ConduitWireType.NAME, ConduitWireType.TRANSFER_RATE, ConduitWireType.LOSS_RATIO));
		return set;
	}

	private Set<Connection> currentBundles()
	{
		Set<Connection> out = new HashSet<>();
		Set<Connection> all = ImmersiveNetHandler.INSTANCE.getConnections(world, getPos());
		if(all==null)
			return out;
		for(Connection connection : all)
			if(connection.isBundle())
				out.add(connection);
		return out;
	}

	/**
	 * The world, in the shape {@link ConduitRoute} asks for.
	 */
	private class WorldProbe implements ConduitRoute.Probe
	{
		@Override
		public ConduitRoute.Node nodeAt(BlockPos pos)
		{
			//Unloaded means "not there" rather than "look": asking would generate the chunk, and a
			//run along a border would drag chunks in behind it forever.
			if(!world.isBlockLoaded(pos))
				return ConduitRoute.Node.NOTHING;
			TileEntity te = world.getTileEntity(pos);
			if(te instanceof TileEntityConduit)
				return ConduitRoute.Node.CONDUIT;
			if(te instanceof TileEntityJunctionBox)
				return ConduitRoute.Node.JUNCTION;
			return ConduitRoute.Node.NOTHING;
		}

		@Override
		public EnumFacing mountAt(BlockPos pos)
		{
			if(!world.isBlockLoaded(pos))
				return null;
			TileEntity te = world.getTileEntity(pos);
			return te instanceof TileEntityConduit?((TileEntityConduit)te).facing: null;
		}
	}

	// ------------------------------------------------------------------
	// Patching
	// ------------------------------------------------------------------

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		WireChannel dyed = channelOf(heldItem);
		if(dyed!=null)
		{
			if(!world.isRemote)
			{
				//Moved rather than added: the same conductor arriving at two connectors is a short,
				//not a feature, so patching it somewhere new takes it off wherever it was.
				patch.moveTo(side, dyed);
				markDirty();
				markContainingBlockForUpdate(null);
			}
			return true;
		}
		if(heldItem.isEmpty()&&player.isSneaking())
		{
			if(!patch.isPatched(side))
				return false;
			if(!world.isRemote)
			{
				patch.set(side, null);
				markDirty();
				markContainingBlockForUpdate(null);
			}
			return true;
		}
		return false;
	}

	/**
	 * @return the channel a held dye stands for, or null if that is not a dye
	 */
	@Nullable
	public static WireChannel channelOf(ItemStack stack)
	{
		if(stack==null||stack.isEmpty())
			return null;
		//By ore dictionary rather than by item, so a dye from any mod patches a face. The names run
		//dyeWhite..dyeBlack in EnumDyeColor's order, which WireChannel matches deliberately.
		for(int id : OreDictionary.getOreIDs(stack))
		{
			String name = OreDictionary.getOreName(id);
			if(name.length() <= 3||!name.startsWith("dye"))
				continue;
			for(EnumDyeColor colour : EnumDyeColor.values())
				if(name.substring(3).equalsIgnoreCase(colour.getName().replace("_", "")))
					return WireChannel.byIndex(colour.getMetadata());
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Wire graph
	// ------------------------------------------------------------------

	@Override
	public boolean canConnect()
	{
		return true;
	}

	@Override
	public boolean isEnergyOutput()
	{
		//P5's job. A box that claimed to be an output before it could deliver anything would show
		//up as a machine that is powered and does nothing.
		return false;
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		return 0;
	}

	@Override
	public BlockPos getConnectionMaster(@Nullable WireType cableType, TargetingInfo target)
	{
		return getPos();
	}

	@Override
	public boolean canConnectCable(WireType cableType, TargetingInfo target)
	{
		//Only its own kind. A player cannot string an ordinary wire to a junction box; the two meet
		//at a breakout face, through a connector, which is the whole point of the block.
		return cableType==ConduitWireType.INSTANCE;
	}

	@Override
	public void connectCable(WireType cableType, TargetingInfo target, IImmersiveConnectable other)
	{
	}

	@Override
	public WireType getCableLimiter(TargetingInfo target)
	{
		return ConduitWireType.INSTANCE;
	}

	@Override
	public boolean allowEnergyToPass(Connection con)
	{
		return true;
	}

	@Override
	public void removeCable(Connection connection)
	{
		//Nothing cached per connection here -- the runs are re-walked from the world whenever
		//anything changes -- so there is nothing to forget.
	}

	@Override
	public Vec3d getConnectionOffset(Connection con)
	{
		//Centre of the block. Nothing draws this -- a bundle's visible form is the conduit itself --
		//but the catenary maths still runs over it, so it has to be somewhere sane.
		return new Vec3d(.5, .5, .5);
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote)
			rebuildRuns();
	}

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		if(world!=null&&!world.isRemote)
			rebuildRuns();
	}

	public void onBlockBroken()
	{
		if(world==null||world.isRemote)
			return;
		ImmersiveNetHandler.INSTANCE.clearAllConnectionsFor(getPos(), world, null);
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setTag("patch", patch.writeToNBT());
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		patch.readFromNBT(nbt.getCompoundTag("patch"));
	}

	// ------------------------------------------------------------------
	// Readouts
	// ------------------------------------------------------------------

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		EnumFacing side = mop==null?null: mop.sideHit;
		WireChannel here = patch.get(side);
		return new String[]{
				here!=null?"Breakout: "+here.getName(): "No breakout on this face",
				patch.count()+" of "+WireChannel.VALUES.length+" patched"
		};
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
		lines.add(TextFormatting.GOLD+"Junction Box"+TextFormatting.RESET+": "
				+currentBundles().size()+" run(s)");
		if(patch.isEmpty())
			//Said because a box that does nothing looks identical to one that is broken, and the
			//fix -- right-click a face with a dye -- is not guessable.
			lines.add(TextFormatting.YELLOW+"Nothing patched. Dye a face to break a channel out."
					+TextFormatting.RESET);
		else
			for(EnumFacing face : EnumFacing.VALUES)
			{
				WireChannel channel = patch.get(face);
				if(channel!=null)
					lines.add("  "+face.getName()+": "+channel.getName());
			}
		return lines;
	}
}
