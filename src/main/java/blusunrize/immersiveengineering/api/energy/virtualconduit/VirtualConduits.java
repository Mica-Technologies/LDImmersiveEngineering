/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitLink.Kind;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The server-wide table of conduit outlets, and of the places power gets into a run.
 * <p>
 * <strong>Two tables, because they answer two different questions.</strong> An <em>outlet</em> says
 * where a conductor can leave a box -- it exists while the breakout exists, lit or dark, and it is
 * what the engine replays while the box is unloaded. A <em>feed</em> says where a conductor is being
 * supplied from outside the run. Whether an outlet may deliver is the two put together: a conductor
 * is live when a feed reaches it over the saved bundles, which is a question neither table answers
 * alone and neither end of a run needs to be loaded to ask.
 * <p>
 * The first version had only the first table and used the far box's own {@code held} for the second, which
 * meant an outlet could only exist while both ends of a run happened to be loaded at once. See
 * {@link VirtualConduitFeed} for how that failed in the field.
 * <p>
 * A process-global singleton, exactly as {@code VirtualGeneration} and {@code VirtualGrid} are, and
 * cleared when a world's save data loads so a second world in one session starts clean.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduits
{
	public static final VirtualConduits INSTANCE = new VirtualConduits();

	/**
	 * A ceiling on the boxes one liveness flood will walk, so a pathological build cannot turn a
	 * conductor's liveness into an unbounded walk. Far above any real installation -- the map this
	 * was written for has sixteen boxes on it in total.
	 */
	private static final int MAX_BOXES_PER_FLOOD = 512;

	private final Map<LinkKey, VirtualConduitLink> links = new LinkedHashMap<>();
	private final Map<FeedKey, VirtualConduitFeed> feeds = new LinkedHashMap<>();
	@Nullable
	private Runnable dirtyListener;

	/**
	 * The bundle graph, for the liveness flood. Set once the server is up; null in a unit test that
	 * does not care, and then a conductor is live only where it is fed directly.
	 */
	@Nullable
	private IVirtualConduitWorld world;

	/**
	 * Per dimension, per conductor, the boxes a feed reaches. Rebuilt lazily after
	 * {@link #invalidateLiveness()}; see {@link #isLive} for why it is cached at all.
	 */
	private final Map<Integer, Set<BlockPos>[]> liveCache = new HashMap<>();
	private boolean liveDirty = true;

	@Nullable
	public VirtualConduitLink get(int dimension, BlockPos boxPos, WireChannel channel, Kind kind)
	{
		return links.get(new LinkKey(dimension, boxPos, channel, kind));
	}

	/**
	 * Record the catenary strung to a conductor's breakout face.
	 * <p>
	 * Creating the record and updating it are one call because the caller -- a box refreshing what it
	 * can see of itself -- does not care which it is, and splitting them would mean every call site
	 * doing the same null dance.
	 */
	public VirtualConduitLink observeWire(int dimension, BlockPos boxPos, WireChannel channel,
										  EnumFacing face, int rate, String wireTypeName, BlockPos wireEnd)
	{
		return observe(dimension, boxPos, channel, Kind.WIRE, rate, face, wireTypeName, wireEnd);
	}

	/**
	 * Record the connector bolted against a conductor's breakout face.
	 */
	public VirtualConduitLink observeNeighbour(int dimension, BlockPos boxPos, WireChannel channel,
											   EnumFacing face, int rate)
	{
		return observe(dimension, boxPos, channel, Kind.NEIGHBOUR, rate, face, null, null);
	}

	private VirtualConduitLink observe(int dimension, BlockPos boxPos, WireChannel channel, Kind kind,
									   int rate, @Nullable EnumFacing face,
									   @Nullable String wireTypeName, @Nullable BlockPos wireEnd)
	{
		LinkKey key = new LinkKey(dimension, boxPos, channel, kind);
		VirtualConduitLink link = links.get(key);
		if(link==null)
		{
			link = new VirtualConduitLink(dimension, boxPos, channel, kind);
			links.put(key, link);
			link.observe(rate, face, wireTypeName, wireEnd);
			markDirty();
			return link;
		}
		if(link.observe(rate, face, wireTypeName, wireEnd))
			markDirty();
		return link;
	}

	/**
	 * Forget one outlet: its breakout is gone, not merely dark. Going dark is not grounds for this --
	 * see {@link VirtualConduitFeed}.
	 */
	public boolean remove(int dimension, BlockPos boxPos, WireChannel channel, Kind kind)
	{
		boolean removed = links.remove(new LinkKey(dimension, boxPos, channel, kind))!=null;
		if(removed)
			markDirty();
		return removed;
	}

	/**
	 * Forget a whole box, outlets and feed alike, for when the block itself is gone.
	 *
	 * @return how many records were dropped
	 */
	public int removeBox(int dimension, BlockPos boxPos)
	{
		int dropped = 0;
		for(WireChannel channel : WireChannel.VALUES)
		{
			for(Kind kind : Kind.VALUES)
				if(links.remove(new LinkKey(dimension, boxPos, channel, kind))!=null)
					dropped++;
			if(feeds.remove(new FeedKey(dimension, boxPos, channel))!=null)
			{
				dropped++;
				invalidateLiveness();
			}
		}
		if(dropped > 0)
			markDirty();
		return dropped;
	}

	public Collection<VirtualConduitLink> getLinks()
	{
		return Collections.unmodifiableCollection(links.values());
	}

	public int size()
	{
		return links.size();
	}

	/**
	 * @return whether this registry has anything at all to say. The gate every loaded junction box
	 * checks before doing any virtual-conduit work of its own, so a server with no conduit on it --
	 * or one with the feature switched off -- pays a boolean and an emptiness test per box per tick
	 * and nothing else.
	 */
	public boolean isActive()
	{
		return VirtualConduitConfig.enabled&&(!links.isEmpty()||!feeds.isEmpty());
	}

	// ------------------------------------------------------------------
	// Feeds
	// ------------------------------------------------------------------

	/**
	 * Note that a conductor is being credited from outside its own run.
	 *
	 * @return true if this is news, which is what the caller pays a save write for
	 */
	public boolean addFeed(int dimension, BlockPos boxPos, WireChannel channel)
	{
		FeedKey key = new FeedKey(dimension, boxPos, channel);
		if(feeds.containsKey(key))
			return false;
		feeds.put(key, new VirtualConduitFeed(dimension, boxPos, channel));
		invalidateLiveness();
		markDirty();
		return true;
	}

	public boolean removeFeed(int dimension, BlockPos boxPos, WireChannel channel)
	{
		if(feeds.remove(new FeedKey(dimension, boxPos, channel))==null)
			return false;
		invalidateLiveness();
		markDirty();
		return true;
	}

	public boolean hasFeed(int dimension, BlockPos boxPos, WireChannel channel)
	{
		return feeds.containsKey(new FeedKey(dimension, boxPos, channel));
	}

	/**
	 * @return one bit per conductor this box is recorded as feeding, so a box coming back into the
	 * world picks its own records up again rather than leaving them to be dropped by nobody
	 */
	public int feedMaskAt(int dimension, BlockPos boxPos)
	{
		int mask = 0;
		for(WireChannel channel : WireChannel.VALUES)
			if(feeds.containsKey(new FeedKey(dimension, boxPos, channel)))
				mask |= channel.getMask();
		return mask;
	}

	public Collection<VirtualConduitFeed> getFeeds()
	{
		return Collections.unmodifiableCollection(feeds.values());
	}

	public int feedCount()
	{
		return feeds.size();
	}

	// ------------------------------------------------------------------
	// Liveness
	// ------------------------------------------------------------------

	/**
	 * The bundle graph this registry floods over. Server-side plumbing; a test may leave it unset,
	 * and then only a directly fed conductor reads as live.
	 */
	public void setWorld(@Nullable IVirtualConduitWorld world)
	{
		this.world = world;
		invalidateLiveness();
	}

	/**
	 * Throw away the cached floods. Cheap -- it sets a flag -- so anything that could have moved a
	 * feed or a bundle may call it without thinking about the cost.
	 */
	public void invalidateLiveness()
	{
		liveDirty = true;
	}

	/**
	 * Is this conductor, at this box, being supplied?
	 * <p>
	 * <strong>Answered from the feeds and the bundle graph, never from a box.</strong> Both of those
	 * are global and saved, so this works with every block of the run unloaded -- which is the whole
	 * point, and the thing the first version could not do. A conductor is live when some box on its run is
	 * recorded as fed on that conductor, where "on its run" means reachable over bundle edges that
	 * carry that conductor.
	 * <p>
	 * <strong>Cached, and deliberately a little stale.</strong> This is asked once per outlet per
	 * tick by the engine, and once per patched face per tick by every loaded box in city mode. The
	 * answer changes when a feed appears or goes, or when a run is made or broken, and all three
	 * invalidate the cache; the tick handler drops it once a second besides, so nothing can stay
	 * wrong for longer than that even if something forgets to say so.
	 */
	@SuppressWarnings("unchecked")
	public boolean isLive(int dimension, BlockPos boxPos, WireChannel channel)
	{
		//The common case on a server with no conduit on it, and the cheapest possible answer.
		if(!VirtualConduitConfig.enabled||feeds.isEmpty()||channel==null)
			return false;
		if(liveDirty)
		{
			liveCache.clear();
			liveDirty = false;
		}
		Set<BlockPos>[] byChannel = liveCache.get(dimension);
		if(byChannel==null)
		{
			byChannel = new Set[WireChannel.VALUES.length];
			liveCache.put(dimension, byChannel);
		}
		Set<BlockPos> live = byChannel[channel.ordinal()];
		if(live==null)
		{
			live = flood(dimension, channel);
			byChannel[channel.ordinal()] = live;
		}
		return live.contains(boxPos);
	}

	/**
	 * Every box one conductor's feeds reach, over bundles that carry that conductor.
	 * <p>
	 * Breadth-first over the wire graph's bundle edges, which are global and cost no chunk access --
	 * see {@link IVirtualConduitWorld#bundleNeighbours}. Nothing here may load anything, for the
	 * reason the whole feature exists.
	 */
	private Set<BlockPos> flood(int dimension, WireChannel channel)
	{
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		for(VirtualConduitFeed feed : feeds.values())
			if(feed.getDimension()==dimension&&feed.getChannel()==channel&&seen.add(feed.getBoxPos()))
				open.add(feed.getBoxPos());
		if(world==null)
			//No graph to walk. A directly fed conductor is still honestly live, and standing in for
			//the rest would be guessing.
			return seen;
		while(!open.isEmpty()&&seen.size() < MAX_BOXES_PER_FLOOD)
		{
			BlockPos here = open.poll();
			for(BlockPos peer : world.bundleNeighbours(dimension, here, channel))
				if(seen.add(peer))
					open.add(peer);
		}
		return seen;
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	public void clear()
	{
		links.clear();
		feeds.clear();
		invalidateLiveness();
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

	public void readFromNBT(NBTTagCompound nbt)
	{
		links.clear();
		feeds.clear();
		NBTTagList list = nbt.getTagList("links", 10);
		for(int i = 0; i < list.tagCount(); i++)
		{
			VirtualConduitLink link = VirtualConduitLink.readFromNBT(list.getCompoundTagAt(i));
			if(link!=null)
				links.put(new LinkKey(link.getDimension(), link.getBoxPos(), link.getChannel(),
						link.getKind()), link);
		}
		NBTTagList fed = nbt.getTagList("feeds", 10);
		for(int i = 0; i < fed.tagCount(); i++)
		{
			VirtualConduitFeed feed = VirtualConduitFeed.readFromNBT(fed.getCompoundTagAt(i));
			if(feed!=null)
				feeds.put(new FeedKey(feed.getDimension(), feed.getBoxPos(), feed.getChannel()), feed);
		}
		invalidateLiveness();
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList list = new NBTTagList();
		for(VirtualConduitLink link : links.values())
			list.appendTag(link.writeToNBT());
		nbt.setTag("links", list);
		NBTTagList fed = new NBTTagList();
		for(VirtualConduitFeed feed : feeds.values())
			fed.appendTag(feed.writeToNBT());
		nbt.setTag("feeds", fed);
		return nbt;
	}

	private static final class LinkKey
	{
		private final int dimension;
		private final BlockPos pos;
		private final WireChannel channel;
		private final Kind kind;

		LinkKey(int dimension, BlockPos pos, WireChannel channel, Kind kind)
		{
			this.dimension = dimension;
			//Immutable: a BlockPos.MutableBlockPos used as a key would change under the map.
			this.pos = pos.toImmutable();
			this.channel = channel;
			this.kind = kind;
		}

		@Override
		public boolean equals(Object o)
		{
			if(this==o)
				return true;
			if(!(o instanceof LinkKey))
				return false;
			LinkKey other = (LinkKey)o;
			return dimension==other.dimension&&channel==other.channel&&kind==other.kind
					&&pos.equals(other.pos);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(dimension, pos, channel, kind);
		}
	}

	private static final class FeedKey
	{
		private final int dimension;
		private final BlockPos pos;
		private final WireChannel channel;

		FeedKey(int dimension, BlockPos pos, WireChannel channel)
		{
			this.dimension = dimension;
			this.pos = pos.toImmutable();
			this.channel = channel;
		}

		@Override
		public boolean equals(Object o)
		{
			if(this==o)
				return true;
			if(!(o instanceof FeedKey))
				return false;
			FeedKey other = (FeedKey)o;
			return dimension==other.dimension&&channel==other.channel&&pos.equals(other.pos);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(dimension, pos, channel);
		}
	}
}
