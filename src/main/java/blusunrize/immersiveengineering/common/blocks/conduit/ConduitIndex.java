/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitRoute.Node;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where every piece of conduit hardware on the server is, kept so that a walk can cross a stretch
 * nobody is standing in.
 *
 * <h3>Why this exists</h3>
 * A junction box learns what it is joined to by walking the conduit, and {@link ConduitWorldProbe}
 * answers nothing at all for an unloaded position -- deliberately, because a walk that looked would
 * drag chunks in behind it forever. So a run whose middle is unloaded never links. On a server with a
 * view distance of seven that is <em>every</em> inter-town run, essentially always: the two-hundred
 * block line between Alewife and Mt. Manchester was simply absent from the wire graph, and only
 * appeared if a rebuild happened to run in the one moment every block of it was loaded at once.
 * <p>
 * IE's own wires do not have this problem, and the reason is exactly this: the wire graph is global
 * and saved, so a route search steps over an unloaded connector without needing to see it. This is
 * the same answer for conduit. The blocks are written down as they load, the walk reads the note for
 * anything it cannot see, and a run links whether or not anybody is standing on it.
 *
 * <h3>What is remembered, and what is not</h3>
 * Exactly the three things the walk asks about a position: which kind of hardware is there, which
 * face a conduit is clipped to, and which axis a feeder passes a run along. All three live on a tile
 * entity and all three change only when somebody builds something.
 * <p>
 * <strong>Not solidity.</strong> Whether an inner corner has a block to turn around is a question
 * about an ordinary world block -- a wall, a hillside, anything -- and indexing every solid block on
 * the map to answer it would be a copy of the world. {@code ConduitRoute.cornerSupported} takes the
 * optimistic reading instead; see the comment there for why that is safe.
 *
 * <h3>Filled on load, emptied on break -- and never the other way round</h3>
 * An entry is written when the block loads or is placed, updated when its mount or axis changes, and
 * dropped when the block is genuinely broken. <strong>A chunk unloading drops nothing</strong>, which
 * is the entire point: an entry for an unloaded position is precisely what this table is for. In
 * 1.12.2 that distinction is {@code Block.breakBlock} (the block is gone) against
 * {@code TileEntity.invalidate} and {@code onChunkUnload} (the block may well still be there) -- and
 * {@code invalidate} runs for both, so it is no use here.
 * <p>
 * Anything that puts a block down without going through the break path -- WorldEdit, {@code /setblock},
 * a mod moving blocks around -- leaves an entry describing hardware that is not there. That is what
 * {@link #sweepChunk} is for: when a chunk loads, the handful of entries recorded <em>in that chunk</em>
 * are checked against the blocks that are actually there. Keyed by chunk so that costs a map lookup
 * rather than a walk of the whole table.
 * <p>
 * World-free and testable, exactly as {@link ConduitRoute} is: the store, the per-chunk lookup, the
 * sweep's decision and the NBT are all here, and the only thing that reads a block is the
 * {@link Hardware} the sweep is handed.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitIndex
{
	public static final ConduitIndex INSTANCE = new ConduitIndex();

	/**
	 * One remembered block. Immutable, so an entry handed out cannot be edited behind the table's
	 * back and the dirty flag cannot be missed.
	 */
	public static final class Entry
	{
		private final Node node;
		@Nullable
		private final EnumFacing mount;
		@Nullable
		private final EnumFacing.Axis axis;

		Entry(Node node, @Nullable EnumFacing mount, @Nullable EnumFacing.Axis axis)
		{
			this.node = node;
			this.mount = mount;
			this.axis = axis;
		}

		public Node getNode()
		{
			return node;
		}

		@Nullable
		public EnumFacing getMount()
		{
			return mount;
		}

		@Nullable
		public EnumFacing.Axis getAxis()
		{
			return axis;
		}

		boolean sameAs(Node node, @Nullable EnumFacing mount, @Nullable EnumFacing.Axis axis)
		{
			return this.node==node&&this.mount==mount&&this.axis==axis;
		}
	}

	/**
	 * What the sweep reads the world through: the blocks that are really there, and nothing borrowed
	 * from this table.
	 * <p>
	 * <strong>It has to be the blocks and not the tile entities.</strong> A chunk's block storage is
	 * right the moment the chunk exists, while its tile entities arrive on their own schedule -- and a
	 * sweep that read tile entities could run in the window before they land, find nothing anywhere,
	 * and delete a whole chunk's worth of perfectly good index. Kinds are all this needs anyway: a
	 * mount or an axis that has genuinely changed is rewritten by the block's own load, which is the
	 * only thing that knows it.
	 */
	public interface Hardware
	{
		Node nodeAt(BlockPos pos);
	}

	/**
	 * Per dimension, per chunk, per position. Three levels rather than one flat map keyed by a
	 * dimension-and-position pair, because {@link #sweepChunk} has to be able to ask for one chunk's
	 * entries without walking the table -- a chunk load happens constantly and the table has a few
	 * thousand entries in it on the map this was written for.
	 */
	private final Map<Integer, Map<Long, Map<BlockPos, Entry>>> byDimension = new HashMap<>();

	@Nullable
	private Runnable dirtyListener;

	// ------------------------------------------------------------------
	// Writing it down
	// ------------------------------------------------------------------

	/**
	 * Remember a length of conduit and the face it is clipped to.
	 *
	 * @return true if this is news, which is what makes the save file dirty. A chunk reloading
	 * rewrites every entry in it unchanged, so answering honestly here is what keeps a chunk load from
	 * marking the save dirty for nothing.
	 */
	public boolean rememberConduit(int dimension, BlockPos pos, EnumFacing mount)
	{
		return remember(dimension, pos, Node.CONDUIT, mount, null);
	}

	public boolean rememberJunction(int dimension, BlockPos pos)
	{
		return remember(dimension, pos, Node.JUNCTION, null, null);
	}

	public boolean rememberFeeder(int dimension, BlockPos pos, EnumFacing.Axis axis)
	{
		return remember(dimension, pos, Node.PASS_THROUGH, null, axis);
	}

	public boolean remember(int dimension, BlockPos pos, Node node, @Nullable EnumFacing mount,
							@Nullable EnumFacing.Axis axis)
	{
		if(node==Node.NOTHING)
			return forget(dimension, pos);
		BlockPos key = pos.toImmutable();
		Map<BlockPos, Entry> chunk = byDimension
				.computeIfAbsent(dimension, d -> new HashMap<>())
				.computeIfAbsent(chunkKey(key), c -> new LinkedHashMap<>());
		Entry existing = chunk.get(key);
		if(existing!=null&&existing.sameAs(node, mount, axis))
			return false;
		chunk.put(key, new Entry(node, mount, axis));
		markDirty();
		return true;
	}

	/**
	 * Forget a position: the block there is gone, not merely out of sight. See the class comment for
	 * which of 1.12.2's callbacks means which.
	 */
	public boolean forget(int dimension, BlockPos pos)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return false;
		long key = chunkKey(pos);
		Map<BlockPos, Entry> chunk = chunks.get(key);
		if(chunk==null||chunk.remove(pos)==null)
			return false;
		//Empty buckets are dropped rather than left lying about. A player mining out a corridor would
		//otherwise leave one map per chunk they passed through, for ever.
		if(chunk.isEmpty())
			chunks.remove(key);
		if(chunks.isEmpty())
			byDimension.remove(dimension);
		markDirty();
		return true;
	}

	// ------------------------------------------------------------------
	// Reading it back
	// ------------------------------------------------------------------

	@Nullable
	public Entry get(int dimension, BlockPos pos)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return null;
		Map<BlockPos, Entry> chunk = chunks.get(chunkKey(pos));
		return chunk==null?null: chunk.get(pos);
	}

	/**
	 * @return what kind of hardware is remembered there, or {@link Node#NOTHING}. The shape
	 * {@code ConduitRoute.Probe} asks in, so the probe can hand an unloaded position straight over.
	 */
	public Node nodeAt(int dimension, BlockPos pos)
	{
		Entry entry = get(dimension, pos);
		return entry==null?Node.NOTHING: entry.getNode();
	}

	/**
	 * @return true if this table has anything to say about that exact position
	 */
	public boolean knows(int dimension, BlockPos pos)
	{
		return get(dimension, pos)!=null;
	}

	/**
	 * @return true if this table has anything at all in the chunk that position falls in -- which is
	 * the other half of what {@code isLoaded} has come to mean for a walk. See
	 * {@code Remembering.isLoaded} for why the question is asked about the chunk rather than the
	 * position.
	 */
	public boolean knowsChunk(int dimension, BlockPos pos)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		return chunks!=null&&chunks.containsKey(chunkKey(pos));
	}

	public boolean isEmpty()
	{
		return byDimension.isEmpty();
	}

	// ------------------------------------------------------------------
	// Standing in for what cannot be seen
	// ------------------------------------------------------------------

	/**
	 * The same world, with this table answering for everything in it that is not loaded.
	 * <p>
	 * <strong>Loaded first, remembered second, and never the other way round.</strong> A position the
	 * world will answer for is answered by the world, because the world is what is actually there; the
	 * table is consulted only where the world has refused to look. Stated once, here, rather than at
	 * each call site -- {@code ConduitWorldProbe.of} is the only caller in the game and this is the
	 * only implementation -- so that the rule can be asserted against a world held in a map with no
	 * Minecraft anywhere near it.
	 * <p>
	 * One wrapper per walk, because it remembers whether it has had to borrow anything: see
	 * {@link ConduitRoute.Probe#answeredFromMemory()} for what that is worth to the caller.
	 */
	public ConduitRoute.Probe backing(int dimension, ConduitRoute.Probe world)
	{
		return new Remembering(dimension, world);
	}

	private final class Remembering implements ConduitRoute.Probe
	{
		private final int dimension;
		private final ConduitRoute.Probe world;
		private boolean borrowed;

		Remembering(int dimension, ConduitRoute.Probe world)
		{
			this.dimension = dimension;
			this.world = world;
		}

		/**
		 * @return what is written down about a position the world would not answer for, or null. Marks
		 * the walk as having leant on memory, which is what stops its caller pruning.
		 */
		@Nullable
		private Entry remembered(BlockPos pos)
		{
			if(world.isLoaded(pos))
				return null;
			Entry entry = get(dimension, pos);
			if(entry!=null)
				borrowed = true;
			return entry;
		}

		@Override
		public Node nodeAt(BlockPos pos)
		{
			Node node = world.nodeAt(pos);
			if(node!=Node.NOTHING)
				return node;
			Entry entry = remembered(pos);
			return entry==null?Node.NOTHING: entry.getNode();
		}

		@Override
		@Nullable
		public EnumFacing mountAt(BlockPos pos)
		{
			EnumFacing mount = world.mountAt(pos);
			if(mount!=null)
				return mount;
			Entry entry = remembered(pos);
			return entry==null?null: entry.getMount();
		}

		@Override
		@Nullable
		public EnumFacing.Axis axisAt(BlockPos pos)
		{
			EnumFacing.Axis axis = world.axisAt(pos);
			if(axis!=null)
				return axis;
			Entry entry = remembered(pos);
			return entry==null?null: entry.getAxis();
		}

		/**
		 * Solidity is the one question this table cannot stand in for: it is about ordinary world
		 * blocks -- a wall, a hillside, the underside of a staircase -- and remembering which of those
		 * are solid would be a copy of the map.
		 * <p>
		 * <strong>So an unloaded one is taken on trust, and the trust is cheap to justify.</strong>
		 * This is asked from exactly one place, {@code ConduitRoute.cornerSupported}, and only once
		 * <em>both</em> pieces of an inner corner are already known -- seen or remembered -- each
		 * clipped to a face perpendicular to the other in precisely the arrangement the corner makes.
		 * Two lengths of conduit do not end up like that without the block they turn around, because a
		 * conduit is clipped to a surface and the surface in question <em>is</em> the corner block.
		 * <p>
		 * Being wrong here costs one spurious edge in the wire graph, which the next walk with those
		 * chunks loaded takes out again. Being wrong the other way -- refusing every corner nobody is
		 * standing at -- costs an inter-town run that never links, which is the whole bug. And the
		 * guess is written down as a borrowing, so the walk that made it may add a run and may not
		 * delete one, exactly as if it had read an entry.
		 */
		@Override
		public boolean isMountable(BlockPos pos, EnumFacing face)
		{
			if(world.isLoaded(pos))
				return world.isMountable(pos, face);
			borrowed = true;
			return true;
		}

		/**
		 * Seen, or in a chunk this table has seen.
		 *
		 * <h3>Why the chunk and not the position</h3>
		 * A walk probes the cells around every length of conduit it follows, and most of those are
		 * empty air. If "indexed" meant "there is an entry at this exact position", every one of those
		 * empty cells in an unloaded chunk would read as unseen and <em>every</em> walk down an
		 * unloaded run would come back truncated -- which would leave the flag meaning what it meant
		 * before this table existed, and leave a box retrying for ever.
		 * <p>
		 * A chunk with an entry in it is a chunk that has been loaded since the index came in, and
		 * every piece of conduit hardware in a loaded chunk writes itself down. So for such a chunk
		 * "there is no entry here" is an authoritative "there is nothing there", which is exactly what
		 * {@code isLoaded} is asked to certify. A chunk with no entry at all has either never been
		 * visited or has no conduit in it, and the two cannot be told apart -- so it reads as unseen,
		 * the walk truncates, and the box tries again later. That is the case the retry exists for.
		 */
		@Override
		public boolean isLoaded(BlockPos pos)
		{
			return world.isLoaded(pos)||knowsChunk(dimension, pos);
		}

		@Override
		public boolean answeredFromMemory()
		{
			return borrowed||world.answeredFromMemory();
		}
	}

	// ------------------------------------------------------------------
	// The sweep
	// ------------------------------------------------------------------

	/**
	 * Check one chunk's entries against the blocks that are really there, and drop what is not.
	 * <p>
	 * Called when a chunk loads, which is the one moment the answer can be had for free and the one
	 * moment it is worth having: an entry is only ever wrong because something put a block down
	 * without going through the break path, and the next thing that happens to such a block is
	 * somebody walking up to it.
	 * <p>
	 * <strong>Drops only.</strong> An entry whose kind still matches is left exactly as it is, mount
	 * and axis included: the block's own load rewrites those, and it is the only thing that knows
	 * them. This deliberately cannot repair an entry, only remove a lie.
	 *
	 * @param world the blocks as built, with nothing borrowed from this table -- see {@link Hardware}
	 *
	 * @return how many entries were dropped
	 */
	public int sweepChunk(int dimension, int chunkX, int chunkZ, Hardware world)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return 0;
		long key = ChunkPos.asLong(chunkX, chunkZ);
		Map<BlockPos, Entry> chunk = chunks.get(key);
		if(chunk==null||chunk.isEmpty())
			return 0;
		List<BlockPos> stale = null;
		for(Map.Entry<BlockPos, Entry> remembered : chunk.entrySet())
			if(world.nodeAt(remembered.getKey())!=remembered.getValue().getNode())
			{
				//Gathered rather than removed on the spot: this is iterating the very map being
				//edited, and there are never more than a handful of these.
				if(stale==null)
					stale = new ArrayList<>(4);
				stale.add(remembered.getKey());
			}
		if(stale==null)
			return 0;
		for(BlockPos pos : stale)
			chunk.remove(pos);
		if(chunk.isEmpty())
			chunks.remove(key);
		if(chunks.isEmpty())
			byDimension.remove(dimension);
		markDirty();
		return stale.size();
	}

	// ------------------------------------------------------------------
	// Counting, for the operator's command
	// ------------------------------------------------------------------

	public int size()
	{
		int total = 0;
		for(int dimension : byDimension.keySet())
			total += size(dimension);
		return total;
	}

	public int size(int dimension)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return 0;
		int total = 0;
		for(Map<BlockPos, Entry> chunk : chunks.values())
			total += chunk.size();
		return total;
	}

	public int count(int dimension, Node node)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return 0;
		int total = 0;
		for(Map<BlockPos, Entry> chunk : chunks.values())
			for(Entry entry : chunk.values())
				if(entry.getNode()==node)
					total++;
		return total;
	}

	public int chunkCount(int dimension)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		return chunks==null?0: chunks.size();
	}

	/** @return the dimensions with anything in them, in a settled order so a listing reads the same twice */
	public Set<Integer> dimensions()
	{
		return Collections.unmodifiableSet(new TreeSet<>(byDimension.keySet()));
	}

	/** @return every position remembered in one chunk. For tests and for the command; nothing per tick. */
	public Collection<BlockPos> inChunk(int dimension, int chunkX, int chunkZ)
	{
		Map<Long, Map<BlockPos, Entry>> chunks = byDimension.get(dimension);
		if(chunks==null)
			return Collections.emptyList();
		Map<BlockPos, Entry> chunk = chunks.get(ChunkPos.asLong(chunkX, chunkZ));
		return chunk==null?Collections.emptyList(): Collections.unmodifiableCollection(
				new ArrayList<>(chunk.keySet()));
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	public void clear()
	{
		byDimension.clear();
	}

	public void setDirtyListener(@Nullable Runnable listener)
	{
		this.dirtyListener = listener;
	}

	public void markDirty()
	{
		if(dirtyListener!=null)
			dirtyListener.run();
	}

	/**
	 * Which chunk a position belongs to, as one number.
	 * <p>
	 * {@code ChunkPos.asLong} rather than a {@code ChunkPos} object: this is on the path of every
	 * index read, which is on the path of every walk over an unloaded stretch.
	 */
	private static long chunkKey(BlockPos pos)
	{
		return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	/**
	 * One tag per dimension holding one compound per entry.
	 * <p>
	 * The position is packed into a single long the way {@code BlockPos.toLong} does it rather than
	 * written as three integers, and the kind, mount and axis are single bytes: the map this was
	 * written for has two and a half thousand conduit blocks on it, and a file that is a megabyte
	 * instead of fifty kilobytes for no reason is a file somebody eventually deletes.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList dimensions = new NBTTagList();
		for(Map.Entry<Integer, Map<Long, Map<BlockPos, Entry>>> dimension : byDimension.entrySet())
		{
			NBTTagList entries = new NBTTagList();
			for(Map<BlockPos, Entry> chunk : dimension.getValue().values())
				for(Map.Entry<BlockPos, Entry> remembered : chunk.entrySet())
				{
					Entry entry = remembered.getValue();
					NBTTagCompound tag = new NBTTagCompound();
					tag.setLong("p", remembered.getKey().toLong());
					tag.setByte("k", (byte)entry.getNode().ordinal());
					//Plus one, so that zero means "none" -- an EnumFacing ordinal of zero is DOWN, and
					//a junction box written with no mount would come back clipped to the floor.
					tag.setByte("m", (byte)(entry.getMount()==null?0: entry.getMount().ordinal()+1));
					tag.setByte("a", (byte)(entry.getAxis()==null?0: entry.getAxis().ordinal()+1));
					entries.appendTag(tag);
				}
			NBTTagCompound perDimension = new NBTTagCompound();
			perDimension.setInteger("dim", dimension.getKey());
			perDimension.setTag("at", entries);
			dimensions.appendTag(perDimension);
		}
		nbt.setTag("conduitIndex", dimensions);
		return nbt;
	}

	public void readFromNBT(NBTTagCompound nbt)
	{
		byDimension.clear();
		NBTTagList dimensions = nbt.getTagList("conduitIndex", 10);
		for(int i = 0; i < dimensions.tagCount(); i++)
		{
			NBTTagCompound perDimension = dimensions.getCompoundTagAt(i);
			int dimension = perDimension.getInteger("dim");
			NBTTagList entries = perDimension.getTagList("at", 10);
			for(int j = 0; j < entries.tagCount(); j++)
			{
				NBTTagCompound tag = entries.getCompoundTagAt(j);
				//Bounds-checked rather than trusted, the same way every other reader in this feature
				//is: the bytes came off disk, and EnumFacing.byIndex wraps rather than failing, which
				//would silently remount a remembered conduit onto the wrong wall.
				int kind = tag.getByte("k");
				if(kind < 0||kind >= Node.values().length)
					continue;
				Node node = Node.values()[kind];
				if(node==Node.NOTHING)
					continue;
				int mount = tag.getByte("m")-1;
				int axis = tag.getByte("a")-1;
				remember(dimension, BlockPos.fromLong(tag.getLong("p")), node,
						mount >= 0&&mount < EnumFacing.VALUES.length?EnumFacing.VALUES[mount]: null,
						axis >= 0&&axis < EnumFacing.Axis.values().length
								?EnumFacing.Axis.values()[axis]: null);
			}
		}
	}
}
