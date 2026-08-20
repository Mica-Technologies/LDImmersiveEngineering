/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.common.util.network.MessageRequestBlockUpdate;
import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.TargetingInfo;
import blusunrize.immersiveengineering.api.energy.wires.IICProxy;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.WireNetTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.IExtendedBlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Predicate;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSet;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ConduitWireType;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IHammerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ICacheData;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPropertyPassthrough;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridDevice;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityConnectorLV;
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
import net.minecraft.util.text.TextComponentTranslation;
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
 * <strong>A wire may be strung straight at a face, with no connector in between.</strong> That is
 * the same seam the Grid Feed and Service Units closed: hanging a connector on the wall beside a box
 * so that a wire has something to attach to is a block and a rule that exist only because nobody
 * wrote this. One wire per face and the face is the one that was clicked, so six wires on a box are
 * six circuits on six conductors with no configuration beyond the gesture -- and the tier of each
 * circuit is the tier of the wire on it, exactly as it is the tier of the connector when a connector
 * is what is bolted there. An HV wire on one face and an LV wire on another are two circuits of two
 * tiers down one bundle, which is the whole point of a bundle.
 * <p>
 * The connector option has not gone anywhere. A box with three connectors round it and no wires in
 * sight is what an underground feeder looks like, and it still auto-patches and still works.
 * <p>
 * Runs are discovered rather than drawn: laying conduit between two boxes creates the connection,
 * and breaking it removes it. There is no coil and no linking tool. The connection carries the
 * sixteen channels; the hundred conduit blocks in between are not nodes in the wire graph, which is
 * the decision this whole feature's cost rests on.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class TileEntityJunctionBox extends TileEntityIEBase implements IImmersiveConnectable,
		INeighbourChangeTile, IPlayerInteraction, IHammerInteraction, IBlockOverlayText, IStatusLineProvider,
		IFluxReceiver, IFluxProvider, IComparatorOverride, IRedstoneOutput, ITickable,
		IPropertyPassthrough, ICacheData
{
	/**
	 * The most one channel may hold. One tick's worth of its own wire: a box is a relay, not a
	 * battery, and a larger buffer would only mean a longer wait before a run notices a load being
	 * switched off.
	 */
	public static final int CHANNEL_CAPACITY = ConduitWireType.TRANSFER_RATE;

	private final ConduitPatch patch = new ConduitPatch();

	/**
	 * Which wire is strung to each face. Saved; the wire graph itself is IE's and is saved with the
	 * world, so this only records which of this box's six terminals each of its wires is on.
	 */
	private final JunctionWires wires = new JunctionWires();

	/**
	 * The resolved {@link WireType} per face, so the per-tick push does not look a name up in the
	 * registry sixteen times a second. Rebuilt whenever the name it was resolved from changes.
	 */
	private final WireType[] wireCache = new WireType[6];

	/**
	 * Scratch for the wire push, owned here for the same reason the connector and the Grid Service
	 * Unit own one: this runs once a tick per live conductor with a wire on it, and a map allocated
	 * per call is exactly the shape of thing this mod's profiling history is about. Server thread
	 * only, and used serially -- nothing downstream re-enters this box.
	 */
	private final Map<AbstractConnection, IImmersiveConnectable> transferEndCache = new HashMap<>();

	/**
	 * The surface this box is drawn against, or null when it has not been worked out yet.
	 * <p>
	 * A box has no facing of its own -- no field, no placement rule, no packet. It is derived from
	 * the runs that reach it, in {@link ConduitGeometry#junctionBoxMount}, which is what puts the
	 * housing in the same plane as its conduit. That derivation costs six neighbour lookups, and a
	 * wire's attachment point is asked for far more often than the neighbours change, so it is
	 * cached here and dropped whenever anything next door does.
	 */
	@Nullable
	private transient EnumFacing mount;

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
	 * <p>
	 * This is a <em>time</em>, not an amount a source has to keep up with. In city mode any credit
	 * at all sets the conductor to full (see {@link #credit}), and it then has twenty ticks to be fed
	 * again -- so an LV connector's 256 a tick keeps a conductor lit exactly as an HV one's does. It
	 * used to be a plain debit against whatever had been credited, and since a twentieth of a channel
	 * is far more than any single LV or MV wire delivers in a tick, a conductor fed by one went dark
	 * on the very tick it was lit and the run read as dead. Only {@link #CITY_HOP} is charged along
	 * the run itself, so presence reaches the far end of a long corridor.
	 */
	private static final int CITY_DECAY = Math.max(1, CHANNEL_CAPACITY/20);

	/**
	 * What one hop along a run costs a conductor's presence in city mode: next to nothing. The decay
	 * above is what makes a run go dark, hop by hop from the source, once the source stops; the hop
	 * charge only has to be non-zero so that presence never runs in a circle forever.
	 */
	private static final int CITY_HOP = 1;

	/** Per channel, what left this box last tick, for the readouts. Deliberately not saved. */
	private final int[] lastMoved = new int[WireChannel.VALUES.length];

	/** Client only: the tick the readout last asked the server for fresh figures. */
	private long lastReadoutRequest = Long.MIN_VALUE;

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

	public JunctionWires getWires()
	{
		return wires;
	}

	// ------------------------------------------------------------------
	// Which surface the box is against
	// ------------------------------------------------------------------

	/**
	 * Which faces a run physically touches, and the plane each of those runs is in.
	 * <p>
	 * Stated once, here, because two things need the answer and they must not disagree:
	 * {@code ConduitJunctionModel} draws the box's housing and stubs and picks the plane from it,
	 * and the tile entity works out where a wire attaches from it. A box drawn against one surface
	 * with its wires leaving from another would be a bug with nothing in it to find.
	 *
	 * @param mounts filled in per side with the mount face of the conduit joining there, or null
	 *
	 * @return per side, whether a run touches the box there
	 */
	public static boolean[] joiningRuns(IBlockAccess world, BlockPos pos, EnumFacing[] mounts)
	{
		boolean[] joins = new boolean[EnumFacing.VALUES.length];
		for(EnumFacing side : EnumFacing.VALUES)
		{
			BlockPos at = pos.offset(side);
			//Unloaded is "nothing there", as everywhere else in the feature: this is asked from the
			//server when a wire's attachment point is wanted and from the client's chunk-render
			//threads, and neither may pull the chunk next door in to answer. A ChunkCache reports
			//everything it holds as loaded, so the client path is unchanged by the guard.
			TileEntity neighbour = world instanceof World&&!((World)world).isBlockLoaded(at)
					?null: world.getTileEntity(at);
			EnumFacing neighbourMount = neighbour instanceof TileEntityConduit
					?((TileEntityConduit)neighbour).facing: null;
			EnumFacing.Axis feederAxis = neighbour instanceof TileEntityGroundFeeder
					?((TileEntityGroundFeeder)neighbour).getAxis(): null;
			joins[side.ordinal()] = ConduitGeometry.joinsJunctionBox(side, neighbourMount, feederAxis);
			mounts[side.ordinal()] = joins[side.ordinal()]?neighbourMount: null;
		}
		return joins;
	}

	/**
	 * @return the surface this box is bolted to, which is {@link EnumFacing#DOWN} for a box no run
	 * reaches -- a box sitting on the floor of its cell, which is what one with no runs has always
	 * looked like
	 */
	public EnumFacing getMount()
	{
		if(mount!=null)
			return mount;
		if(world==null)
			return EnumFacing.DOWN;
		EnumFacing[] mounts = new EnumFacing[EnumFacing.VALUES.length];
		joiningRuns(world, getPos(), mounts);
		mount = ConduitGeometry.junctionBoxMount(mounts);
		return mount;
	}

	/**
	 * Forgets the cached mount. Cheap, and called from everything that could have moved a run.
	 */
	private void forgetMount()
	{
		mount = null;
	}

	//	=================================
	//		WHAT THE MODEL DRAWS
	//	=================================

	/**
	 * Everything about a box that is visible, packed into one integer.
	 * <p>
	 * Bits 0-2 are the mounting face's ordinal and bits 3-8 are one flag per face that has something
	 * to meet -- a run arriving, or simply a block or a connector set down against it -- which
	 * between them pick the housing model and the set of stubs grown out to reach them. {@code ConduitJunctionModel} composes its quads from exactly this and caches them
	 * under it, and {@link #getCacheData} hands the same number to the connection model so that two
	 * differently-shaped boxes cannot share one baked model.
	 * <p>
	 * <strong>Computed fresh every time rather than cached like {@link #mount}.</strong> The client
	 * has no way to know it has gone stale: {@code BlockIETileProvider.neighborChanged} forwards a
	 * neighbour change to the tile entity on the server only, so a box whose run was extended while
	 * it watched would keep drawing the shape it had when the chunk was last built, with no second
	 * chance to notice. Six tile lookups against a {@code ChunkCache} is the cheaper mistake.
	 */
	public int getRenderShape()
	{
		if(world==null)
			return EnumFacing.DOWN.ordinal();
		EnumFacing[] mounts = new EnumFacing[EnumFacing.VALUES.length];
		boolean[] joins = joiningRuns(world, getPos(), mounts);
		int shape = ConduitGeometry.junctionBoxMount(mounts).ordinal();
		for(int i = 0; i < joins.length; i++)
			if(joins[i]||meetsNeighbour(EnumFacing.byIndex(i)))
				shape |= 1 << (3+i);
		return shape;
	}

	/**
	 * Whether there is anything against that face for the housing to reach out and meet.
	 * <p>
	 * <strong>The housing used to stop three pixels short of every face a run did not arrive on</strong>
	 * -- so an LV connector bolted to a box, or a machine set down beside one, stood clear of it with
	 * a gap you could see the ground through. It was reported twice in one testing round, once for
	 * blocks and once for connectors, and it is the same three pixels both times: the box is ten
	 * pixels across in a sixteen-pixel cell, and nothing but a conduit's own arm ever closed the
	 * rest.
	 * <p>
	 * Solid-sided or wiring hardware, and nothing else. A solid side is the block somebody has just
	 * set down next to the box; wiring hardware is the connector or grid unit that is the whole
	 * reason a box has faces. Grass, a torch or a snow layer is neither, and a box growing a stub
	 * out to meet a dandelion would be a worse bug than the one this fixes.
	 */
	private boolean meetsNeighbour(EnumFacing face)
	{
		BlockPos other = getPos().offset(face);
		if(!world.isBlockLoaded(other))
			return false;
		IBlockState state = world.getBlockState(other);
		if(state.getBlock().isAir(state, world, other))
			return false;
		if(state.isSideSolid(world, other, face.getOpposite()))
			return true;
		//A connector's model is a small thing hugging the face it is bolted to, which is not a solid
		//side by any test -- and is exactly what the gap was most visible against.
		return Utils.getExistingTileEntity(world, other) instanceof IImmersiveConnectable;
	}

	/** @return one bit per patched face, which is the set of coloured plates the box wears */
	public int getPatchMask()
	{
		int mask = 0;
		for(EnumFacing face : EnumFacing.VALUES)
			if(patch.isPatched(face))
				mask |= 1 << face.ordinal();
		return mask;
	}

	/**
	 * What tells one box's baked model from another's.
	 * <p>
	 * {@code ConnModelReal} caches an assembled model -- the housing plus whatever wires are strung
	 * to it -- under the block's listed properties, and a conduit has almost none left: the twelve
	 * per-face booleans that used to spell a box's shape now live on this tile entity instead, where
	 * a cache key cannot see them. Without this every box in a save would hash to the same key and
	 * the first one built would be drawn everywhere.
	 */
	@Override
	public Object[] getCacheData()
	{
		return new Object[]{getRenderShape(), getPatchMask()};
	}

	// ------------------------------------------------------------------
	// Runs
	// ------------------------------------------------------------------

	/**
	 * Set when something next door changed; cleared by the next server tick, which does the walk.
	 * <p>
	 * A neighbour change asks for a rebuild rather than performing one, and the tick performs at
	 * most one however many asked. Placing a single length of conduit against a box reaches it
	 * three ways -- the neighbour update, the conduit's own block update, and {@code wakeBoxes} --
	 * and each used to be a full walk of the run.
	 */
	private boolean rebuildQueued;

	/**
	 * Which faces had conduit hardware against them when the runs were last walked, one bit per
	 * {@link EnumFacing} ordinal. What lets a neighbour update be ignored: a comparator flickering
	 * against a live box, a hopper, a piston somewhere on the far side -- none of them can have
	 * changed a run, and none of them is worth a walk. Only a change on a face that has, or had,
	 * conduit hardware on it can. Zero until the first walk, and a zero mask defers to the queue,
	 * so a box that has never walked still walks on its first neighbour update.
	 */
	private int hardwareMask;

	/**
	 * Set when a neighbour change may have moved a redstone input; cleared by the next server tick,
	 * which re-derives the run's signals once. The same bargain as {@link #rebuildQueued}: a lever
	 * being spammed against a box with a redstone breakout costs one walk over the run's boxes a
	 * tick, not one per update, and nothing that bounces an update back can turn that into a loop.
	 */
	private boolean signalsQueued;

	/**
	 * @return one bit per face with a conduit, box or feeder against it right now
	 */
	private int hardwareAround()
	{
		int mask = 0;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			BlockPos at = getPos().offset(side);
			TileEntity te = world.isBlockLoaded(at)?world.getTileEntity(at): null;
			if(te instanceof TileEntityConduit||te instanceof TileEntityJunctionBox
					||te instanceof TileEntityGroundFeeder)
				mask |= 1<<side.ordinal();
		}
		return mask;
	}

	/**
	 * Whether a change at {@code other} could have altered the runs reaching this box.
	 * <p>
	 * True when there is conduit hardware there now, when there was some there at the last walk,
	 * or when the question cannot be answered cheaply -- a change that is not next door, or a box
	 * that has never walked. False for the common case this exists for: a neighbour that is not
	 * conduit and never was, changing state next to a box, which used to cost a full walk of the
	 * run and could do so every tick.
	 */
	private boolean concernsRuns(BlockPos other)
	{
		if(hardwareMask==0||other==null)
			return true;
		int dx = other.getX()-getPos().getX();
		int dy = other.getY()-getPos().getY();
		int dz = other.getZ()-getPos().getZ();
		if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)
			return true;
		EnumFacing side = EnumFacing.getFacingFromVector(dx, dy, dz);
		if((hardwareMask&(1<<side.ordinal()))!=0)
			return true;
		TileEntity te = world.isBlockLoaded(other)?world.getTileEntity(other): null;
		return te instanceof TileEntityConduit||te instanceof TileEntityJunctionBox
				||te instanceof TileEntityGroundFeeder;
	}

	/**
	 * Ask for the runs to be re-walked on the next server tick. Safe to call any number of times a
	 * tick, from anything, at any depth: it sets a flag.
	 */
	public void queueRebuild()
	{
		if(world!=null&&!world.isRemote)
			rebuildQueued = true;
	}

	/**
	 * Rebuild this box's connections from the conduit actually on the walls.
	 * <p>
	 * Both sides of a run are torn down and rebuilt rather than patched, because working out which
	 * single conduit block changed and what that implies is far more code than re-walking a run,
	 * and this happens when somebody places a block rather than every tick.
	 * <p>
	 * <strong>Notifies only when the graph actually changed.</strong> This used to send a block
	 * update unconditionally, and a block update notifies the neighbours, and a neighbouring box
	 * answers a neighbour update by rebuilding -- so two boxes side by side rebuilt each other
	 * forever. Not once a tick, either: {@code BlockIETileProvider.neighborChanged} defers the
	 * call onto the server's future-task queue, and the server drains that queue to empty inside a
	 * single tick, so each rebuild queued the next and the tick never ended. The client kept
	 * drawing at full frame rate while the integrated server sat in that loop, which is exactly
	 * the "locks up but only while conduit is placed" a playtester reported, and a dedicated
	 * server's watchdog kills it, which is the crash he saw. Callers that want the walk go through
	 * {@link #queueRebuild()} now, which also bounds a box to one walk a tick against anything
	 * else that might bounce an update back.
	 *
	 * @return true if a run was made or dropped
	 */
	public boolean rebuildRuns()
	{
		if(world==null||world.isRemote)
			return false;
		rebuildQueued = false;
		hardwareMask = hardwareAround();
		Map<BlockPos, Integer> peers = ConduitRoute.junctionsFrom(getPos(), new ConduitWorldProbe(world));
		Set<BlockPos> wanted = new HashSet<>(peers.keySet());
		boolean changed = false;

		//Drop what is no longer reachable first, so a run rerouted onto a different wall does not
		//briefly exist twice.
		for(Connection existing : new ArrayList<>(currentBundles()))
		{
			BlockPos other = existing.end;
			if(!wanted.remove(other))
			{
				//removeConnection, not removeConnectionAndDrop: a bundle has no coil, and the drop
				//variant spawned an item entity holding nothing for every run torn down.
				ImmersiveNetHandler.INSTANCE.removeConnection(world, existing);
				changed = true;
			}
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
			changed = true;
		}
		if(changed)
			markContainingBlockForUpdate(null);
		return changed;
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
				//Whichever face it came off may still have a connector or a wire on it, and a face
				//with hardware on it and no conductor behind it is a dead circuit nobody asked for.
				//Auto-patching is what claims that face again.
				autoPatch();
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
		//**Both hands, not just this one.** A right click is offered to the main hand and then, if
		//that says it did nothing, to the off hand -- and the client cannot know that the hammer's
		//cycle did anything, because IHammerInteraction is dispatched on the server only. So a
		//sneaking player with a hammer in one hand and nothing in the other sent two clicks: the
		//first recoloured the face and the second, arriving empty-handed, unpatched it again. The
		//breakout appeared to vanish on the first hit and never come back.
		if(heldItem.isEmpty()&&player.getHeldItemMainhand().isEmpty()&&player.isSneaking())
		{
			if(!patch.isPatched(side))
				return false;
			//A wire on a face is what asked for that face's breakout, so unpatching underneath one
			//would leave a circuit strung to a conductor that is no longer there -- live-looking
			//hardware carrying nothing, which is the worst of the ways to be wrong. Said rather
			//than silently refused: a right click that does nothing reads as a broken block.
			if(wires.has(side))
			{
				if(!world.isRemote)
					ChatUtils.sendServerNoSpamMessages(player,
							new TextComponentTranslation(Lib.CHAT_WARN+"conduit.wireHoldsFace"));
				return true;
			}
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
	 * Sneak and hit a face with an Engineer's Hammer to cycle what is broken out on it.
	 * <p>
	 * <strong>Because a dye was the only way, and a dye is not a thing anybody guesses.</strong>
	 * Changing a breakout's colour meant working out that dyes do it at all, then carrying sixteen
	 * of them up a pole to try one. The hammer is already in the hand of anybody building wiring --
	 * it is what rotates every other piece of IE hardware -- so it is the tool the gesture belongs
	 * on. Dyes still work, and are still the way to ask for one particular colour in one hit.
	 * <p>
	 * The cycle is every colour this box has not already spent somewhere else, and then bare. Taken
	 * colours are stepped over rather than refused: the same conductor on two faces is a short, and
	 * a click that visibly does nothing reads as a broken block. Bare is skipped too when a wire is
	 * strung to the face, for the reason {@link #interact} gives -- live hardware on no conductor is
	 * the worst of the ways for this to be wrong.
	 */
	@Override
	public boolean hammerUseSide(EnumFacing side, EntityPlayer player, float hitX, float hitY, float hitZ)
	{
		//Not the plain hit: that is how a hammer rotates hardware, and a box has nothing to rotate,
		//so a player swinging at one is far more likely to be hitting the wall behind it.
		if(!player.isSneaking()||side==getMount())
			return false;
		if(!cycleBreakout(side))
			return false;
		markDirty();
		markContainingBlockForUpdate(null);
		//A conductor that was carrying redstone has just moved or gone. The run re-derives rather
		//than being pushed at, exactly as it does when the mode itself is cycled.
		if(patch.hasRedstone())
			propagateSignals();
		world.notifyNeighborsOfStateChange(getPos().offset(side), getBlockType(), false);
		return true;
	}

	/**
	 * One step of the hammer's cycle on one face.
	 *
	 * @return true if anything changed
	 */
	private boolean cycleBreakout(EnumFacing side)
	{
		WireChannel current = patch.get(side);
		int taken = 0;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			WireChannel channel = patch.get(face);
			if(channel!=null&&face!=side)
				taken |= channel.getMask();
		}
		int next = JunctionBoxLogic.nextBreakout(current==null?-1: current.ordinal(), taken,
				!wires.has(side), WireChannel.VALUES.length);
		return patch.set(side, next < 0?null: WireChannel.byIndex(next));
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
	// Auto-patching
	// ------------------------------------------------------------------

	/**
	 * Break a free conductor out onto any bare face that has power hardware bolted to it.
	 * <p>
	 * Putting an LV connector on a junction box and having it do nothing was the single most
	 * cumbersome thing about conduit: the block that makes it work is a <em>dye</em>, which is not a
	 * thing anybody guesses. The gesture already says what it means -- somebody who bolts a
	 * connector or a grid unit to a box wants power at that face -- so the box now answers it, and
	 * answers it visibly: the face wears its conductor's plate exactly as a hand-dyed one does, and
	 * a dye re-patches it afterwards like any other face.
	 * <p>
	 * <strong>Only wiring hardware.</strong> A connector, or a Grid Feed or Service Unit. Not any
	 * machine that happens to accept flux: a box dropped beside a capacitor bank to turn a corner
	 * must not quietly start draining the run into it, and a rule a player can state -- "connectors
	 * and grid boxes claim a face" -- beats one that depends on what a neighbouring mod implements.
	 * <p>
	 * Never <em>un</em>patches. Taking a connector down leaves the breakout where it was, because
	 * the alternative is a box that forgets a deliberate configuration the moment something is mined
	 * next to it.
	 *
	 * @return true if anything changed
	 */
	private boolean autoPatch()
	{
		if(world==null||world.isRemote)
			return false;
		boolean changed = false;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			if(patch.isPatched(face)||!wantsBreakout(face))
				continue;
			int free = JunctionBoxLogic.preferredChannel(face.ordinal(), patchedMask(),
					WireChannel.VALUES.length);
			if(free < 0)
				//Sixteen conductors, all spoken for. Nothing sensible left to do, and stealing one
				//from another face would break a working circuit to make a new one.
				break;
			patch.set(face, WireChannel.byIndex(free));
			changed = true;
		}
		return changed;
	}

	/**
	 * @return one bit per conductor already broken out somewhere on this box
	 */
	private int patchedMask()
	{
		int mask = 0;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			WireChannel channel = patch.get(face);
			if(channel!=null)
				mask |= channel.getMask();
		}
		return mask;
	}

	/**
	 * @return true if the block against that face is a connector or a grid unit, and so is asking
	 * for a breakout by being there
	 */
	private boolean wantsBreakout(EnumFacing face)
	{
		//A wire strung to the face is the plainest way of asking. connectCable patches the face as it
		//attaches, so this is the safety net -- a face whose colour was dyed away from it, or a wire
		//adopted by reconcileWires, still ends up with a conductor behind it.
		if(wires.has(face))
			return true;
		TileEntity target = Utils.getExistingTileEntity(world, getPos().offset(face));
		if(target==null)
			return false;
		if(target instanceof TileEntityGridDevice)
			//A Feed or Service Unit bolted straight onto a box. A Signal Unit moves nothing and is
			//refused by the same test the wire graph uses.
			return ((TileEntityGridDevice)target).getDeviceType().movesEnergy();
		//A connector, and not a relay: acceptingSide answers null for a relay, which neither takes
		//nor gives energy and would sit on a conductor doing nothing.
		return target instanceof TileEntityConnectorLV
				&&EnergyHelper.acceptingSide(target, face.getOpposite())!=null;
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
		//A place the network may put energy: every face with a wire on it is one. Answered from the
		//wire table rather than unconditionally, so a box that nobody has strung anything to is not
		//offered as a destination to every route search that passes near it. Safe to depend on
		//state because the state only changes when a connection is added or removed, and both of
		//those already reset the route cache.
		//
		//A bundle connection is not one of these. Energy crosses between boxes in passAlong, which
		//is per conductor; the bundle exists to record the topology, not to route power along.
		return !wires.isEmpty();
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		//Without knowing which wire it came down there is no conductor to put it on, and guessing
		//would put a workshop's power on the lighting circuit. See the four-argument form.
		return 0;
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType, @Nullable Connection arriving)
	{
		if(amount <= 0||world==null||world.isRemote||arriving==null)
			return 0;
		EnumFacing face = faceOf(arriving);
		WireChannel channel = patch.get(face);
		if(channel==null)
			return 0;
		int index = channel.ordinal();
		if(simulate)
			return Math.max(0, Math.min(CHANNEL_CAPACITY-held[index], amount));
		return credit(channel, amount);
	}

	/**
	 * @return which of this box's faces a connection is the wire on, or null if it is not one of
	 * them -- a bundle, or a wire this box does not know about
	 */
	@Nullable
	private EnumFacing faceOf(@Nullable Connection con)
	{
		if(con==null||con.cableType==null||con.isBundle())
			return null;
		//Either direction: removeConnection hands both ends both halves of the pair, and a route's
		//last hop arrives pointing at us.
		BlockPos other = getPos().equals(con.end)?con.start: con.end;
		return wires.faceOf(other, con.cableType.getUniqueName());
	}

	/**
	 * @return the wire on that face, resolved from the name the table holds
	 */
	@Nullable
	private WireType wireOn(@Nullable EnumFacing face)
	{
		String name = wires.typeOf(face);
		if(name==null)
			return null;
		WireType cached = wireCache[face.ordinal()];
		if(cached!=null&&name.equals(cached.getUniqueName()))
			return cached;
		cached = WireType.getValue(name);
		wireCache[face.ordinal()] = cached;
		return cached;
	}

	/**
	 * @return true if this is one of the three power tiers a box will take. Structural cable holds
	 * things up and redstone wire carries no flux, so neither has anything to do on a conductor.
	 */
	public static boolean isTierWire(@Nullable WireType wire)
	{
		if(wire==null)
			return false;
		String category = wire.getCategory();
		return WireType.LV_CATEGORY.equals(category)||WireType.MV_CATEGORY.equals(category)
				||WireType.HV_CATEGORY.equals(category);
	}

	/**
	 * Why a wire offered to a face would be refused, or {@link JunctionBoxLogic.WireRefusal#NONE}.
	 */
	private JunctionBoxLogic.WireRefusal refusalFor(@Nullable WireType cableType,
													@Nullable TargetingInfo target)
	{
		if(target==null||target.side==null)
			return JunctionBoxLogic.WireRefusal.WRONG_KIND;
		return JunctionBoxLogic.canTakeWire(wires.mask(), target.side.ordinal(),
				getMount().ordinal(), isTierWire(cableType));
	}

	// ------------------------------------------------------------------
	// Energy
	// ------------------------------------------------------------------

	/**
	 * Set on load, cleared on the first server tick, when {@link #rebuildRuns()} and
	 * {@link #reconcileWires()} can be trusted: see {@link #onLoad()} for why they cannot run there.
	 */
	private boolean reconcilePending;

	@Override
	public void update()
	{
		if(reconcilePending&&world!=null&&!world.isRemote)
		{
			reconcilePending = false;
			rebuildRuns();
			if(reconcileWires())
				markDirty();
		}
		//One walk a tick, however many neighbour changes asked for it -- see queueRebuild. Cheap
		//when nothing asked: a boolean, before the live-mask check below.
		if(rebuildQueued&&world!=null&&!world.isRemote&&rebuildRuns())
			//The run's shape changed: if this box carries signals, the walk over its boxes has to
			//be redone on the new graph.
			signalsQueued = true;
		if(signalsQueued&&world!=null&&!world.isRemote)
		{
			signalsQueued = false;
			if(patch.hasRedstone())
				propagateSignals();
		}
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
				//The far box looked up once per run, not once per live conductor: sixteen tile
				//lookups a run a tick was most of what a fully lit bundle cost.
				TileEntity te = world.isBlockLoaded(run.end)?world.getTileEntity(run.end): null;
				if(!(te instanceof TileEntityJunctionBox))
					continue;
				TileEntityJunctionBox peer = (TileEntityJunctionBox)te;
				for(WireChannel channel : WireChannel.VALUES)
					if((liveMask&channel.getMask())!=0)
					{
						if(city)
							energise(run, peer, channel);
						else
							passAlong(run, peer, channel);
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
	 * The peer is brought up to what this box holds, less {@link #CITY_HOP}, rather than given a
	 * share, so presence spreads along a run at one box per tick and every box on it reports much
	 * the same thing; when the source stops, the decay in {@link #update()} takes the run down from
	 * the source end at the same pace. No gradient to speak of, no loss, no arithmetic beyond a
	 * comparison.
	 */
	private void energise(Connection run, TileEntityJunctionBox peer, WireChannel channel)
	{
		if(run.channels==null||run.channels.getSpec(channel)==null)
			return;
		int index = channel.ordinal();
		int give = held[index]-CITY_HOP;
		if(give <= 0||peer.held[index] >= give)
			return;
		peer.held[index] = give;
		peer.liveMask |= channel.getMask();
	}

	/**
	 * Get a conductor's energy out of the box, by whichever of the two ways its breakout face offers.
	 * <p>
	 * <strong>A face is one breakout, reachable two ways.</strong> Something bolted against it takes
	 * flux block-to-block, and a wire strung to it takes flux along a catenary; both are the same
	 * conductor arriving at the same face, so a face can serve both at once and neither is a second
	 * budget -- what the neighbour takes is gone before the wire is offered anything.
	 * <p>
	 * The neighbour is served first. Touching is the stronger claim, the same order the Grid Service
	 * Unit settled on, and it keeps a box bolted onto a machine behaving as it did before wires could
	 * be strung to one at all.
	 */
	private void drainToBreakout(WireChannel channel)
	{
		EnumFacing face = patch.faceOf(channel);
		if(face==null)
			return;
		int index = channel.ordinal();
		int offered = ConduitTransfer.drain(held[index], ConduitWireType.TRANSFER_RATE);
		if(offered <= 0)
			return;
		//City mode delivers without debiting: the conductor is energised, and where the energy came
		//from is precisely the accounting it exists to stop doing. The decay in update() is what
		//keeps that from being a free generator that never switches off. It is also why the two
		//consumers below do not share out one figure there -- an energised conductor is live at both.
		boolean cityConduit = CityMode.conduits();
		int taken = handToNeighbour(face, index, offered);
		if(!cityConduit)
			offered -= taken;
		if(offered > 0)
			handToWire(face, index, offered);
	}

	/**
	 * @return how much the block against that face accepted
	 */
	private int handToNeighbour(EnumFacing face, int index, int offered)
	{
		TileEntity target = Utils.getExistingTileEntity(world, getPos().offset(face));
		if(target==null)
			return 0;
		//acceptingSide rather than face.getOpposite(): a connector takes flux only on the face it is
		//bolted to, so one mounted on the *wall* beside a box -- the same gesture as far as a player
		//is concerned, and often the only one the geometry leaves room for -- used to sit on a live
		//breakout doing nothing. The Grid Service Unit has had this exemption since it shipped; the
		//breakout is the other half of the same seam, and auto-patching would otherwise hand such a
		//connector a conductor it could never be fed from.
		EnumFacing into = EnergyHelper.acceptingSide(target, face.getOpposite());
		if(into==null)
			return 0;
		int accepted = EnergyHelper.insertFlux(target, into, offered, false);
		if(!CityMode.conduits())
			held[index] -= accepted;
		lastMoved[index] += accepted;
		return accepted;
	}

	/**
	 * Push a conductor's energy out along the wire strung to its breakout face.
	 * <p>
	 * The same code path a connector uses -- see {@link WireNetTransfer} -- so line loss, the
	 * proportional split between competing outputs and what an Energy Meter in the middle reads are
	 * all identical to what a connector bolted beside this box used to produce. Removing the
	 * connector removed a block, not a rule.
	 * <p>
	 * <strong>Restricted to this face's own wire.</strong> A box has up to six of them and each is a
	 * different circuit; the unrestricted push every other node uses would spend the blue conductor's
	 * energy down the red conductor's wire, which is exactly the thing sixteen conductors exist not
	 * to do.
	 * <p>
	 * The rate is the wire's own, which is what makes "the tier of a circuit is the tier of the wire
	 * on it" true rather than merely said: an LV wire on one face and an HV wire on another are two
	 * circuits of two tiers, capped by their own hardware with no second number to disagree.
	 *
	 * @return how much left the box
	 */
	private int handToWire(EnumFacing face, int index, int offered)
	{
		BlockPos end = wires.endOf(face);
		WireType wire = wireOn(face);
		if(end==null||wire==null)
			return 0;
		int rate = wire.getTransferRate();
		if(rate <= 0)
			return 0;
		Predicate<AbstractConnection> onlyThisWire = route -> {
			Connection first = WireNetTransfer.firstHop(route);
			return first!=null&&first.cableType==wire&&end.equals(first.end);
		};
		//City mode's flag here is the *wire* one, because this is a push onto a wire network and the
		//nodes on the far side of it are living by that rule. Whether the box debits itself is the
		//conduit's own question and is asked separately, just below.
		int sent = CityMode.wires()
				?WireNetTransfer.city(world, getPos(), Math.min(offered, rate), onlyThisWire)
				:WireNetTransfer.transfer(world, getPos(), rate, rate, offered, false, 0,
						transferEndCache, onlyThisWire);
		if(sent <= 0)
			return 0;
		if(!CityMode.conduits())
			held[index] -= sent;
		lastMoved[index] += sent;
		return sent;
	}

	private void passAlong(Connection run, TileEntityJunctionBox peer, WireChannel channel)
	{
		ChannelSpec spec = run.channels==null?null: run.channels.getSpec(channel);
		if(spec==null)
			return;
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
		int taken = JunctionBoxLogic.credit(held[index], amount, CHANNEL_CAPACITY);
		if(taken <= 0)
			return 0;
		//City mode: presence, not accounting. Being fed at all is what makes a conductor live, so
		//any credit fills it, and CITY_DECAY then measures how long it stays lit unfed. Outside city
		//mode the figure is real flux and is added as such.
		held[index] = CityMode.conduits()?CHANNEL_CAPACITY: held[index]+taken;
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
		return JunctionBoxLogic.comparatorLevel(held, CHANNEL_CAPACITY);
	}

	@Override
	public BlockPos getConnectionMaster(@Nullable WireType cableType, TargetingInfo target)
	{
		return getPos();
	}

	@Override
	public boolean canConnectCable(WireType cableType, TargetingInfo target)
	{
		//Its own kind always -- a bundle is made by laying conduit and never by a player with a coil
		//in hand, so this is a formality -- and one LV, MV or HV wire per face besides.
		if(cableType==ConduitWireType.INSTANCE)
			return true;
		return refusalFor(cableType, target)==JunctionBoxLogic.WireRefusal.NONE;
	}

	@Override
	public boolean canConnectCable(WireType cableType, TargetingInfo target, Vec3i offset)
	{
		//The offset is for multiblocks whose clicked block is not their master. A box is one block.
		return canConnectCable(cableType, target);
	}

	@Nullable
	@Override
	public String getCableRefusal(WireType cableType, TargetingInfo target)
	{
		if(cableType==ConduitWireType.INSTANCE)
			return null;
		switch(refusalFor(cableType, target))
		{
			case WRONG_KIND:
				return Lib.CHAT_WARN+"conduit.wireKind";
			case MOUNT_FACE:
				return Lib.CHAT_WARN+"conduit.wireMountFace";
			case FACE_TAKEN:
				return Lib.CHAT_WARN+"conduit.wireFaceTaken";
			case BOX_FULL:
				return Lib.CHAT_WARN+"conduit.wireBoxFull";
			default:
				return null;
		}
	}

	@Override
	public void connectCable(WireType cableType, TargetingInfo target, IImmersiveConnectable other)
	{
		if(world==null||world.isRemote||cableType==ConduitWireType.INSTANCE)
			return;
		if(target==null||target.side==null||other==null||!isTierWire(cableType))
			return;
		wires.set(target.side, ApiUtils.toBlockPos(other), cableType.getUniqueName());
		//A bare face takes the colour that face reaches for -- red on the left, blue in the middle,
		//green on the right -- exactly as one does when a connector is bolted against it. The
		//gesture says "power, here", and answering it with silence until somebody finds out about
		//dyes is the awkwardness auto-patching exists to end. A face that is already patched keeps
		//its colour, so a hand-dyed circuit survives having a wire strung to it.
		if(!patch.isPatched(target.side))
		{
			int free = JunctionBoxLogic.preferredChannel(target.side.ordinal(), patchedMask(),
					WireChannel.VALUES.length);
			if(free >= 0)
				patch.set(target.side, WireChannel.byIndex(free));
		}
		markDirty();
		markContainingBlockForUpdate(null);
	}

	@Nullable
	@Override
	public WireType getCableLimiter(TargetingInfo target)
	{
		//What a wire cutter clicked on a face cuts: the wire on that face, and nothing else. A box's
		//terminals are its faces, so "which wire did you mean" is answered by which one was clicked,
		//and cutting one circuit off a box leaves the other five alone.
		//
		//Deliberately never the bundle. Runs are discovered rather than drawn -- laying conduit makes
		//the connection and breaking it removes it -- so a cutter that deleted a run would delete
		//something that reappears the next time anything near it changes, which is worse than a
		//cutter that does nothing.
		return target==null?null: wireOn(target.side);
	}

	@Override
	public boolean allowEnergyToPass(Connection con)
	{
		//A box is an endpoint, never a through-route.
		//
		//Each face is its own circuit on its own conductor, so a route search allowed to walk in on
		//one wire and out on another -- or in on a wire and along the bundle to the next box -- would
		//quietly make the sixteen conductors into one wire with extra steps, bypassing every channel
		//in the feature. Refusing every real connection makes the box a leaf: routes may end here
		//and may start here, and none pass through.
		//
		//Null asks "do all of them pass?", which is what a node is asked before it may draw on the
		//network for wire-shock damage, and before city mode will deliver to it. A box carries flux,
		//so that answer is yes.
		return con==null;
	}

	@Override
	public void removeCable(Connection connection)
	{
		//The runs are re-walked from the world whenever anything changes, so a bundle leaves nothing
		//to forget. A wire does: the face it was on becomes free for another one.
		//
		//Never unpatches. Taking a wire down leaves the breakout where it was, which is the same rule
		//a connector taken off a box has always followed -- the alternative is a box that forgets a
		//deliberate configuration the moment something is mined next to it.
		if(connection==null)
		{
			if(wires.isEmpty())
				return;
			wires.clearAll();
		}
		else
		{
			EnumFacing face = faceOf(connection);
			if(face==null||!wires.clear(face))
				return;
		}
		markDirty();
		if(world!=null)
			markContainingBlockForUpdate(null);
	}

	@Override
	public Vec3d getConnectionOffset(Connection con)
	{
		return terminal(faceOf(con));
	}

	@Override
	public Vec3d getConnectionOffset(Connection con, TargetingInfo target, Vec3i offsetLink)
	{
		//Asked before the connection exists, so the face comes from the click rather than the table.
		//It has to agree with the form above or the wire is drawn from somewhere it was not strung.
		return terminal(target==null?null: target.side);
	}

	/**
	 * Where a wire attaches: the middle of the face's patch plate, on the surface of the housing.
	 *
	 * @param face the face the wire is on, or null for anything that is not a wire
	 */
	private Vec3d terminal(@Nullable EnumFacing face)
	{
		if(face==null)
			//Centre of the block, which is where a bundle's endpoint has always been. Nothing draws
			//it -- a run's visible form is the conduit itself -- but the catenary maths still runs
			//over it, so it has to be somewhere sane.
			return new Vec3d(.5, .5, .5);
		float[] point = ConduitGeometry.junctionTerminal(getMount(), face);
		return new Vec3d(point[0], point[1], point[2]);
	}

	/**
	 * The connection set the smart model draws this box's catenaries from.
	 */
	public Set<Connection> genConnBlockstate()
	{
		return ApiUtils.genConnBlockstate(world, getPos());
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	@Override
	public void onLoad()
	{
		super.onLoad();
		forgetMount();
		if(world==null||world.isRemote)
			return;
		//The runs are re-walked and the wire table is put back in step with the graph on the first
		//tick rather than here. The spawn chunks load before FMLServerStartedEvent, which is where
		//IE reads its wire graph back in, so a box in one of them sees an empty graph from here: it
		//would drop the run it saved, and it would forget every wire it has -- and save that --
		//while the wires themselves come back a moment later. By the first tick the graph is in and
		//the neighbouring chunks are far more likely to be too. See rebuildRuns and reconcileWires.
		reconcilePending = true;
		//Also on load, not only on a neighbour change: a box placed against a connector that was
		//already there hears nothing afterwards, and settled hardware never fires another update.
		if(autoPatch())
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
		//A lever thrown while the chunk was unloaded left no trace, so the run has to re-derive
		//itself once on the way back rather than trusting what it saved.
		if(patch.hasRedstone())
			propagateSignals();
	}

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		//Before the side guard: a neighbouring run is what decides which surface this box is drawn
		//against, and the client draws the wires as well as the housing.
		forgetMount();
		if(world==null||world.isRemote)
			return;
		//Queued, not done -- see rebuildRuns for the loop this used to be -- and only when the change
		//is on a face that has, or had, conduit against it. Anything else next to a box is somebody
		//else's hardware and cannot have moved a run.
		if(concernsRuns(other))
			queueRebuild();
		if(autoPatch())
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
		//A neighbour changing is the only thing that can move a redstone input, so it is the only
		//thing that has to re-derive the run's signals. Queued for the same reason the walk is.
		if(patch.hasRedstone())
			signalsQueued = true;
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
		//Gathered first, resolved second: the "strongest wins per channel" rule lives in
		//JunctionBoxLogic so it can be asserted without a run of real boxes to drive it.
		List<int[]> inputs = new ArrayList<>();
		for(TileEntityJunctionBox box : run)
			for(EnumFacing face : EnumFacing.VALUES)
			{
				WireChannel channel = box.patch.get(face);
				if(channel==null||box.patch.modeOf(face)!=ConduitPatch.Mode.REDSTONE_IN)
					continue;
				inputs.add(new int[]{channel.ordinal(), box.readInput(face)});
			}
		int[] strongest = JunctionBoxLogic.strongestPerChannel(WireChannel.VALUES.length,
				inputs.toArray(new int[0][]));
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
				//Except this box: it is the emitting face's neighbour too, and telling it would only
				//queue another walk to find the signals it just set.
				world.notifyNeighborsOfStateExcept(getPos().offset(face), getBlockType(),
						face.getOpposite());
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
		//Runs and wires alike, and the coils come back for the wires -- a bundle has none, because a
		//run is made by laying conduit rather than by unrolling a coil. BlockIETileProvider.breakBlock
		//does the same for any connectable a moment later, so whichever of the two arrives first
		//finds the connections and the other finds nothing.
		ImmersiveNetHandler.INSTANCE.clearAllConnectionsFor(getPos(), world, this,
				world.getGameRules().getBoolean("doTileDrops"));
	}

	// ------------------------------------------------------------------
	// Chunk lifecycle -- the wire graph's half of it
	// ------------------------------------------------------------------
	// Everything below is what TileEntityImmersiveConnectable does for a connector. A box is a wire
	// endpoint without being able to extend that class, so it says the same three things itself.

	@Override
	public void validate()
	{
		super.validate();
		if(world==null||world.isRemote)
			return;
		//Deferred: this runs during chunk load, and the walk below touches the far ends of this
		//box's connections, which may not be loaded yet.
		ApiUtils.addFutureServerTask(world, () -> {
			Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(world, getPos());
			if(conns!=null)
				for(Connection con : conns)
					//Bundles deliberately excluded, which is why this is spelled out here rather than
					//being onTEValidated. addBlockData walks the whole span of a connection to record
					//where an entity could touch it -- and a bundle's span is a hundred conduit
					//blocks that are not a hazard and are not drawn as a wire. Registering them would
					//be a walk over every run in every chunk that loads, to build a map of a danger
					//that does not exist.
					if(!con.isBundle())
						ImmersiveNetHandler.INSTANCE.addBlockData(world, con);
			ImmersiveNetHandler.INSTANCE.queueConnectivityFlood(world, getPos());
			ImmersiveNetHandler.INSTANCE.setProxy(new DimensionBlockPos(getPos(), world), null);
		});
	}

	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		//Only when something is actually strung to this box. The proxy exists so the far end of a
		//*wire* can still be told the wire is gone while this end is unloaded, and its removeCable
		//pulls the chunk back in for a tick to do it. A run needs none of that -- runs are re-walked
		//from the world whenever anything changes -- and a proxy for every box would turn tearing
		//down a run into a chunk load.
		if(world!=null&&!world.isRemote&&!wires.isEmpty())
			ImmersiveNetHandler.INSTANCE.addProxy(new IICProxy(this));
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		if(world!=null&&world.isRemote&&!Minecraft.getMinecraft().isSingleplayer())
			ImmersiveNetHandler.INSTANCE.clearAllConnectionsFor(pos, world, this, false);
	}

	@Override
	public boolean receiveClientEvent(int id, int arg)
	{
		//-1 is the wire graph's own signal, sent by addAndGetConnection and removeConnection.
		//TileEntityIEBase does not know it; TileEntityImmersiveConnectable does, and a box has to
		//as well or a wire strung to one is not drawn until something else happens to update it.
		if(id==-1)
		{
			markContainingBlockForUpdate(null);
			return true;
		}
		if(id==254&&world!=null)
		{
			IBlockState state = world.getBlockState(pos);
			if(state instanceof IExtendedBlockState)
			{
				state = state.getActualState(world, getPos());
				state = state.getBlock().getExtendedState(state, world, getPos());
				ImmersiveEngineering.proxy.removeStateFromSmartModelCache((IExtendedBlockState)state);
				ImmersiveEngineering.proxy.removeStateFromConnectionModelCache((IExtendedBlockState)state);
			}
			world.notifyBlockUpdate(pos, state, state, 3);
			return true;
		}
		return super.receiveClientEvent(id, arg);
	}

	/**
	 * Puts the face-to-wire table back in step with the wire graph.
	 * <p>
	 * The graph is the authority: it is IE's, it is saved with the world, and a wire in it that this
	 * box has no face for would be a circuit that silently carries nothing. Anything the table
	 * remembers that the graph does not have is forgotten, and anything the graph has that the table
	 * does not is adopted onto the lowest free face -- which is also what gives a box loaded from a
	 * world saved before boxes took wires a sane starting point rather than an empty one.
	 *
	 * @return true if anything changed
	 */
	private boolean reconcileWires()
	{
		Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(world, getPos());
		boolean changed = false;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			BlockPos end = wires.endOf(face);
			if(end==null)
				continue;
			String type = wires.typeOf(face);
			boolean stillThere = false;
			if(conns!=null)
				for(Connection con : conns)
					if(!con.isBundle()&&con.cableType!=null&&end.equals(con.end)
							&&con.cableType.getUniqueName().equals(type))
					{
						stillThere = true;
						break;
					}
			if(!stillThere)
				changed |= wires.clear(face);
		}
		if(conns!=null)
			for(Connection con : conns)
			{
				if(con.isBundle()||con.cableType==null)
					continue;
				String type = con.cableType.getUniqueName();
				if(wires.faceOf(con.end, type)!=null)
					continue;
				int free = -1;
				for(int i = 0; i < JunctionBoxLogic.FACES&&free < 0; i++)
					if(i!=getMount().ordinal()&&(wires.mask()&(1 << i))==0)
						free = i;
				if(free < 0)
					break;
				EnumFacing face = EnumFacing.byIndex(free);
				changed |= wires.set(face, con.end, type);
				//An adopted wire is a wire strung to a bare face, and gets what connectCable gives one:
				//that face's own colour. Without this the table knows the wire and the patch does
				//not, and the circuit is silently dead -- the graph accepts energy for a face that
				//has no conductor to put it on.
				if(!patch.isPatched(face))
				{
					int channel = JunctionBoxLogic.preferredChannel(face.ordinal(), patchedMask(),
							WireChannel.VALUES.length);
					if(channel >= 0)
					{
						patch.set(face, WireChannel.byIndex(channel));
						changed = true;
					}
				}
			}
		if(changed&&world!=null)
			markContainingBlockForUpdate(null);
		return changed;
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setTag("patch", patch.writeToNBT());
		//Which face each wire is on. Sent on a description packet as well as saved, because the
		//client works out where a catenary leaves the box from it.
		nbt.setTag("wires", wires.writeToNBT());
		//Saved because a run that emptied itself on every reload would leak whatever was in flight,
		//and a box holding a tick's worth is holding a tick's worth of somebody's coal. Sent to the
		//client as well, with what moved last tick, because the readouts are drawn client-side and
		//a box that always said "0 IF/t" while carrying a full circuit looked dead. Sixteen ints
		//twice, and only when something asks -- see getOverlayText.
		nbt.setIntArray("held", held);
		if(descPacket)
			nbt.setIntArray("lastMoved", lastMoved);
		//Saved so a run comes back up in the state its levers left it, rather than dark until
		//somebody happens to change a block next to one of its inputs.
		nbt.setIntArray("signal", signal);
		//And the catenaries themselves, which is what the client draws them from.
		if(descPacket)
			ApiUtils.writeConnsToNBT(world, getPos(), nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		patch.readFromNBT(nbt.getCompoundTag("patch"));
		wires.readFromNBT(nbt.getCompoundTag("wires"));
		//The resolved types are a cache of what the table just replaced.
		Arrays.fill(wireCache, null);
		if(descPacket)
		{
			forgetMount();
			ApiUtils.loadConnsFromNBT(world, getPos(), nbt);
		}
		int[] signals = nbt.getIntArray("signal");
		for(int i = 0; i < signal.length; i++)
			//Clamped: the array came off disk, and a redstone strength outside 0-15 would either be
			//invisible or would drive comparators into nonsense.
			signal[i] = i < signals.length?Math.max(0, Math.min(15, signals[i])): 0;
		if(descPacket)
		{
			int[] moved = nbt.getIntArray("lastMoved");
			for(int i = 0; i < lastMoved.length; i++)
				lastMoved[i] = i < moved.length?Math.max(0, moved[i]): 0;
		}
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
		//The figures come from the server, and only while somebody is reading them: once a second
		//ask for a fresh description packet, the same way the voltmeter does. A box nobody is
		//looking at sends nothing.
		//Gated on the tick the last request went out on, not on the world time alone: this is
		//called once per frame, and "world time is a multiple of twenty" is true for every frame of
		//that tick, which at a hundred frames a second was five or six requests where one was meant.
		if(world!=null&&world.isRemote&&world.getTotalWorldTime()-lastReadoutRequest >= 20)
		{
			lastReadoutRequest = world.getTotalWorldTime();
			ImmersiveEngineering.packetHandler.sendToServer(new MessageRequestBlockUpdate(getPos()));
		}
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
				//The tier is worth saying on a wired face and only on a wired face: it is the one
				//thing about the circuit that is decided by hardware the player cannot see from
				//here, since the wire leaves the block rather than sitting against it.
				WireType wire = wireOn(face);
				return getLastMoved(channel)+" IF/t"
						+(wire==null?"": " over "+wire.getCategory()+" wire");
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
			//Said because a box that does nothing looks identical to one that is broken. The
			//connector half is first because it is the one somebody is about to do anyway; the dye
			//is what you reach for when you want to choose which conductor.
			lines.add(TextFormatting.YELLOW+"Nothing patched. String an LV, MV or HV wire to a face, "
					+"put a connector or grid box against one -- or dye one -- to break a channel "
					+"out."+TextFormatting.RESET);
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
