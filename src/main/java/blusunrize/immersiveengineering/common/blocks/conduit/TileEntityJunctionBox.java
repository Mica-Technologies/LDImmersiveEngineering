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
import net.minecraft.block.state.IBlockState;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IRedstoneOutput;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.util.ITickable;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ConduitTransfer;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSpec;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxReceiver;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxProvider;
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
import java.util.Deque;
import java.util.ArrayDeque;
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
		INeighbourChangeTile, IPlayerInteraction, IBlockOverlayText, IStatusLineProvider,
		IFluxReceiver, IFluxProvider, IComparatorOverride, IRedstoneOutput, ITickable
{
	/**
	 * The most one channel may hold. One tick's worth of its own wire: a box is a relay, not a
	 * battery, and a larger buffer would only mean a longer wait before a run notices a load being
	 * switched off.
	 */
	public static final int CHANNEL_CAPACITY = ConduitWireType.TRANSFER_RATE;

	private final ConduitPatch patch = new ConduitPatch();

	/**
	 * What each conductor is holding, one entry per channel.
	 * <p>
	 * A plain array rather than sixteen {@code FluxStorage} objects: this is touched every tick for
	 * every box carrying anything, and sixteen objects per box is a lot of pointer chasing for what
	 * amounts to one number each.
	 */
	private final int[] held = new int[WireChannel.VALUES.length];

	/**
	 * Which channels are holding something, one bit each.
	 * <p>
	 * The entire point of this field is the first line of {@link #update()}. A base with two
	 * hundred boxes in it, three of which are carrying anything, should cost two hundred integer
	 * comparisons per tick rather than two hundred loops over sixteen channels and their
	 * neighbours.
	 */
	private int liveMask;

	/**
	 * How fast an unfed conductor goes dark in city mode, per tick.
	 * <p>
	 * A twentieth of a channel, so a circuit whose source stops is visibly out within a second. A
	 * latch with no decay would leave a corridor lit by a generator somebody dismantled last week,
	 * which is exactly the sort of quiet wrongness city mode is not allowed to introduce.
	 */
	private static final int CITY_DECAY = Math.max(1, CHANNEL_CAPACITY/20);

	/** Per channel, what left this box last tick, for the readouts. Deliberately not saved. */
	private final int[] lastMoved = new int[WireChannel.VALUES.length];

	/**
	 * What each conductor is carrying as a redstone signal, 0-15.
	 * <p>
	 * A conductor carries power or a signal, never both -- that is a property of how its faces are
	 * patched rather than of the conductor, so nothing here enforces it. Somebody who patches a
	 * power breakout and a redstone breakout onto the same colour gets both, independently, which
	 * is odd but not wrong.
	 * <p>
	 * <strong>Recomputed on change rather than per tick.</strong> Redstone is an edge-driven thing:
	 * a lever is thrown perhaps once a minute, and a run that re-derived its signals sixty times a
	 * second to discover nothing had happened would be the same mistake this mod has already paid
	 * for once.
	 */
	private final int[] signal = new int[WireChannel.VALUES.length];

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
		Map<BlockPos, Integer> peers = ConduitRoute.junctionsFrom(getPos(), new ConduitWorldProbe(world));
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
		//Redstone dust cycles what a patched face does. A face is power, an input or an output and
		//never two at once: reading and emitting on the same face is how a redstone network latches
		//itself on and never lets go.
		if(patch.isPatched(side)&&isRedstoneDust(heldItem))
		{
			if(!world.isRemote)
			{
				patch.setMode(side, patch.modeOf(side).next());
				markDirty();
				markContainingBlockForUpdate(null);
				propagateSignals();
				world.notifyNeighborsOfStateChange(getPos().offset(side), getBlockType(), false);
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

	public static boolean isRedstoneDust(ItemStack stack)
	{
		if(stack==null||stack.isEmpty())
			return false;
		//By ore dictionary, so dust from any mod works, exactly as the dyes do.
		for(int id : OreDictionary.getOreIDs(stack))
			if("dustRedstone".equals(OreDictionary.getOreName(id)))
				return true;
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
		//Not on the catenary graph's terms. Energy leaves a box through a connector hung on a
		//breakout face, which is an ordinary block-to-block handover; the bundle connection exists
		//to record the topology, not to route power along.
		return false;
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		return 0;
	}

	// ------------------------------------------------------------------
	// Energy
	// ------------------------------------------------------------------

	@Override
	public void update()
	{
		//The cheap exit, and the reason a base full of idle conduit costs nothing: one comparison.
		if(world==null||world.isRemote||liveMask==0)
			return;

		//Out to the connectors first: a box that can get rid of energy should, because the gradient
		//that opens is what pulls more down the run behind it.
		for(WireChannel channel : WireChannel.VALUES)
		{
			if((liveMask&channel.getMask())!=0)
			{
				lastMoved[channel.ordinal()] = 0;
				drainToBreakout(channel);
			}
		}

		//Then along the runs. Connections outside, channels inside, and the handler's own set used
		//in place rather than copied: this is the one loop in the feature that runs every tick, and
		//a set allocated per live box per tick is exactly the shape of thing this mod's profiling
		//history is about.
		boolean city = CityMode.conduits();
		Set<Connection> runs = ImmersiveNetHandler.INSTANCE.getConnections(world, getPos());
		if(runs!=null)
			for(Connection run : runs)
			{
				if(!run.isBundle())
					continue;
				for(WireChannel channel : WireChannel.VALUES)
					if((liveMask&channel.getMask())!=0)
					{
						if(city)
							energise(run, channel);
						else
							passAlong(run, channel);
					}
			}

		for(WireChannel channel : WireChannel.VALUES)
		{
			int index = channel.ordinal();
			//City mode's only outgoing debit. Nothing else takes anything off a conductor there --
			//an energised one delivers without being drained -- so this is what makes a circuit go
			//out when its source does rather than staying lit forever.
			if(city)
				held[index] -= CITY_DECAY;
			if(held[index] <= 0)
			{
				held[index] = 0;
				liveMask &= ~channel.getMask();
			}
		}
	}

	/**
	 * City mode's version of {@link #passAlong}: hand the neighbour the fact that this conductor is
	 * live, rather than a quantity of flux.
	 * <p>
	 * The peer is topped up to full rather than given a share, so presence spreads along a run at
	 * one box per tick and every box on it reports the same thing. No gradient, no loss, no
	 * arithmetic beyond a comparison.
	 */
	private void energise(Connection run, WireChannel channel)
	{
		if(run.channels==null||run.channels.getSpec(channel)==null)
			return;
		TileEntity te = world.isBlockLoaded(run.end)?world.getTileEntity(run.end): null;
		if(!(te instanceof TileEntityJunctionBox))
			return;
		TileEntityJunctionBox peer = (TileEntityJunctionBox)te;
		int index = channel.ordinal();
		if(peer.held[index] >= held[index]-CITY_DECAY)
			return;
		peer.held[index] = held[index]-CITY_DECAY;
		peer.liveMask |= channel.getMask();
	}

	private void drainToBreakout(WireChannel channel)
	{
		EnumFacing face = patch.faceOf(channel);
		if(face==null)
			return;
		int index = channel.ordinal();
		int offered = ConduitTransfer.drain(held[index], ConduitWireType.TRANSFER_RATE);
		if(offered <= 0)
			return;
		TileEntity target = Utils.getExistingTileEntity(world, getPos().offset(face));
		if(target==null)
			return;
		int accepted = EnergyHelper.insertFlux(target, face.getOpposite(), offered, false);
		//City mode delivers without debiting: the conductor is energised, and where the energy came
		//from is precisely the accounting it exists to stop doing. The decay in update() is what
		//keeps that from being a free generator that never switches off.
		if(!CityMode.conduits())
			held[index] -= accepted;
		lastMoved[index] += accepted;
	}

	private void passAlong(Connection run, WireChannel channel)
	{
		ChannelSpec spec = run.channels==null?null: run.channels.getSpec(channel);
		if(spec==null)
			return;
		TileEntity te = world.isBlockLoaded(run.end)?world.getTileEntity(run.end): null;
		if(!(te instanceof TileEntityJunctionBox))
			return;
		TileEntityJunctionBox peer = (TileEntityJunctionBox)te;
		int index = channel.ordinal();
		ConduitTransfer.Moved moved = ConduitTransfer.hop(held[index], peer.held[index],
				CHANNEL_CAPACITY, spec.getTransferRate(), spec.getLossRatio());
		if(moved.isNothing())
			return;
		held[index] -= moved.taken;
		lastMoved[index] += moved.taken;
		peer.credit(channel, moved.delivered);
	}

	/**
	 * Take energy onto a channel, from a neighbour along the run or from a connector at the door.
	 *
	 * @return how much was actually taken
	 */
	private int credit(WireChannel channel, int amount)
	{
		int index = channel.ordinal();
		int taken = Math.max(0, Math.min(CHANNEL_CAPACITY-held[index], amount));
		if(taken <= 0)
			return 0;
		held[index] += taken;
		liveMask |= channel.getMask();
		return taken;
	}

	public int getHeld(WireChannel channel)
	{
		return channel==null?0: held[channel.ordinal()];
	}

	public int getLastMoved(WireChannel channel)
	{
		return channel==null?0: lastMoved[channel.ordinal()];
	}

	// -- The face a connector hangs on ---------------------------------

	@Override
	public boolean canConnectEnergy(EnumFacing side)
	{
		//Only a patched face takes or gives power, and only on its own conductor. A bare face is
		//the side of a box, and a connector hung on one should visibly do nothing rather than
		//quietly work on whichever channel happened to come first.
		return patch.isPatched(side);
	}

	@Override
	public int receiveEnergy(EnumFacing side, int amount, boolean simulate)
	{
		WireChannel channel = patch.get(side);
		if(channel==null||world==null||world.isRemote)
			return 0;
		if(simulate)
			return Math.max(0, Math.min(CHANNEL_CAPACITY-held[channel.ordinal()], amount));
		return credit(channel, amount);
	}

	@Override
	public int extractEnergy(EnumFacing side, int amount, boolean simulate)
	{
		//Pushed rather than pulled: update() hands each breakout to its connector. Leaving this
		//open as well would let a machine take the same energy a second time in the same tick.
		return 0;
	}

	@Override
	public int getEnergyStored(EnumFacing side)
	{
		return getHeld(patch.get(side));
	}

	@Override
	public int getMaxEnergyStored(EnumFacing side)
	{
		return patch.isPatched(side)?CHANNEL_CAPACITY: 0;
	}

	@Override
	public int getComparatorInputOverride()
	{
		//The busiest conductor, not the total: a bundle where one channel is saturated and fifteen
		//are idle is a bundle with a problem, and an average would hide it.
		int busiest = 0;
		for(int value : held)
			busiest = Math.max(busiest, value);
		if(busiest <= 0)
			return 0;
		return Math.max(1, Math.min(15, busiest*15/CHANNEL_CAPACITY));
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
		if(world==null||world.isRemote)
			return;
		rebuildRuns();
		//A lever thrown while the chunk was unloaded left no trace, so the run has to re-derive
		//itself once on the way back rather than trusting what it saved.
		if(patch.hasRedstone())
			propagateSignals();
	}

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		if(world==null||world.isRemote)
			return;
		rebuildRuns();
		//A neighbour changing is the only thing that can move a redstone input, so it is the only
		//thing that has to re-derive the run's signals.
		if(patch.hasRedstone())
			propagateSignals();
	}

	// ------------------------------------------------------------------
	// Redstone
	// ------------------------------------------------------------------

	/**
	 * Re-derive every redstone conductor on this run, from every input on it.
	 * <p>
	 * The whole run at once, from scratch, rather than pushing a change outward: a signal that is
	 * only ever raised propagates beautifully and then never falls, which is the classic way a
	 * redstone network latches itself on. Recomputing the maximum over all inputs means switching
	 * the last lever off drops the run to zero with no special case for it.
	 * <p>
	 * Costs a walk over the boxes on a run -- never over the conduit blocks -- and only when
	 * something actually changed next to one of them.
	 */
	public void propagateSignals()
	{
		if(world==null||world.isRemote)
			return;
		List<TileEntityJunctionBox> run = boxesOnRun();
		int[] strongest = new int[WireChannel.VALUES.length];
		for(TileEntityJunctionBox box : run)
			for(EnumFacing face : EnumFacing.VALUES)
			{
				WireChannel channel = box.patch.get(face);
				if(channel==null||box.patch.modeOf(face)!=ConduitPatch.Mode.REDSTONE_IN)
					continue;
				int index = channel.ordinal();
				strongest[index] = Math.max(strongest[index], box.readInput(face));
			}
		for(TileEntityJunctionBox box : run)
			box.applySignals(strongest);
	}

	/**
	 * @return what the block against that face is putting into it, 0-15
	 */
	private int readInput(EnumFacing face)
	{
		BlockPos from = getPos().offset(face);
		if(!world.isBlockLoaded(from))
			return 0;
		//getRedstonePower rather than isBlockPowered: this asks what the neighbour offers toward
		//us, so a box cannot read the signal it is emitting on some other face of itself.
		return world.getRedstonePower(from, face);
	}

	private void applySignals(int[] strongest)
	{
		boolean changed = false;
		for(int i = 0; i < signal.length; i++)
			if(signal[i]!=strongest[i])
			{
				signal[i] = strongest[i];
				changed = true;
			}
		if(!changed)
			return;
		markDirty();
		markContainingBlockForUpdate(null);
		//Only the faces that emit need to tell the world, and only if this box has any. A run of
		//twenty boxes with one output on it should cause one neighbour notification, not twenty.
		for(EnumFacing face : EnumFacing.VALUES)
			if(patch.isPatched(face)&&patch.modeOf(face)==ConduitPatch.Mode.REDSTONE_OUT)
				world.notifyNeighborsOfStateChange(getPos().offset(face), getBlockType(), false);
	}

	/**
	 * Every box reachable along conduit from this one, including this one.
	 * <p>
	 * Breadth-first over bundle connections. The conduit blocks are not walked -- they are not
	 * nodes -- so this is a walk over the handful of boxes on a run rather than over its length.
	 */
	private List<TileEntityJunctionBox> boxesOnRun()
	{
		List<TileEntityJunctionBox> found = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		seen.add(getPos());
		open.add(getPos());
		while(!open.isEmpty()&&found.size() < MAX_BOXES_PER_RUN)
		{
			BlockPos here = open.poll();
			TileEntity te = world.isBlockLoaded(here)?world.getTileEntity(here): null;
			if(!(te instanceof TileEntityJunctionBox))
				continue;
			found.add((TileEntityJunctionBox)te);
			Set<Connection> links = ImmersiveNetHandler.INSTANCE.getConnections(world, here);
			if(links==null)
				continue;
			for(Connection link : links)
				if(link.isBundle()&&seen.add(link.end))
					open.add(link.end);
		}
		return found;
	}

	/**
	 * A ceiling on one run's box count, so a pathological build cannot turn a lever into an
	 * unbounded walk. Far above any real installation.
	 */
	private static final int MAX_BOXES_PER_RUN = 512;

	public int getSignal(WireChannel channel)
	{
		return channel==null?0: signal[channel.ordinal()];
	}

	@Override
	public int getStrongRSOutput(IBlockState state, EnumFacing side)
	{
		//side is the face of *this* block that the asking block is on the other side of, so it
		//arrives already reversed relative to how the patch table is keyed.
		EnumFacing face = side.getOpposite();
		if(patch.modeOf(face)!=ConduitPatch.Mode.REDSTONE_OUT)
			return 0;
		return getSignal(patch.get(face));
	}

	@Override
	public boolean canConnectRedstone(IBlockState state, EnumFacing side)
	{
		if(side==null)
			return patch.hasRedstone();
		return patch.modeOf(side.getOpposite()).isRedstone();
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
		//Saved because a run that emptied itself on every reload would leak whatever was in flight,
		//and a box holding a tick's worth is holding a tick's worth of somebody's coal.
		if(!descPacket)
			nbt.setIntArray("held", held);
		//Saved so a run comes back up in the state its levers left it, rather than dark until
		//somebody happens to change a block next to one of its inputs.
		nbt.setIntArray("signal", signal);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		patch.readFromNBT(nbt.getCompoundTag("patch"));
		int[] signals = nbt.getIntArray("signal");
		for(int i = 0; i < signal.length; i++)
			//Clamped: the array came off disk, and a redstone strength outside 0-15 would either be
			//invisible or would drive comparators into nonsense.
			signal[i] = i < signals.length?Math.max(0, Math.min(15, signals[i])): 0;
		if(descPacket)
			return;
		int[] stored = nbt.getIntArray("held");
		liveMask = 0;
		for(int i = 0; i < held.length; i++)
		{
			//Bounds-checked and clamped: the array came off disk, and a box that loaded a negative
			//or an enormous figure would either eat energy or mint it.
			held[i] = i < stored.length?Math.max(0, Math.min(CHANNEL_CAPACITY, stored[i])): 0;
			if(held[i] > 0)
				liveMask |= 1 << i;
		}
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
				here!=null?describeFace(side, here)
						: patch.count()+" of "+WireChannel.VALUES.length+" patched"
		};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	/**
	 * One line's worth of what a face is doing. The mode decides the units, because "0 IF/t" on a
	 * face wired to a lever is a true statement that answers the wrong question.
	 */
	private String describeFace(EnumFacing face, WireChannel channel)
	{
		switch(patch.modeOf(face))
		{
			case REDSTONE_IN:
				return "reads redstone ("+readInputSafely(face)+")";
			case REDSTONE_OUT:
				return "emits redstone ("+getSignal(channel)+")";
			default:
				return getLastMoved(channel)+" IF/t";
		}
	}

	private int readInputSafely(EnumFacing face)
	{
		return world==null||world.isRemote?0: readInput(face);
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
					lines.add("  "+face.getName()+": "+channel.getName()+" -- "+describeFace(face, channel));
			}
		return lines;
	}
}
