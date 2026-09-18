package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitLink.Kind;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The table of conduit outlets and feeds, and the liveness question the two of them answer together.
 * <p>
 * The things worth pinning down here:
 * <ul>
 * <li>a record is per <em>conductor</em>, not per box and not per run -- sixteen circuits sharing one
 * bundle must not merge the moment nobody is standing near them;</li>
 * <li>a bolted connector is an outlet in its own right, and a save written before it was survives;</li>
 * <li>liveness comes from the feeds and the bundles, so it can be answered with every block of a run
 * unloaded, which is the whole reason the feature exists.</li>
 * </ul>
 */
@DisplayName("VirtualConduits")
class VirtualConduitsTest
{
	private VirtualConduits reg;
	private final BlockPos box = new BlockPos(100, 64, -200);
	private final BlockPos end = new BlockPos(140, 70, -200);

	@BeforeEach
	void setUp()
	{
		reg = new VirtualConduits();
		VirtualConduitConfig.reset();
	}

	@Nested
	@DisplayName("the outlet table")
	class Table
	{
		@Test
		@DisplayName("observing a conductor twice updates one record rather than making two")
		void observeReplaces()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 128, "COPPER", end);
			assertEquals(1, reg.size());
			assertEquals(128, reg.get(0, box, WireChannel.RED, Kind.WIRE).getRate());
		}

		@Test
		@DisplayName("two conductors on one box are two records")
		void conductorsAreSeparate()
		{
			//The whole point of sixteen conductors, and the property that would quietly disappear if
			//the key were just the box.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeWire(0, box, WireChannel.GREEN, EnumFacing.EAST, 32, "COPPER", end);
			assertEquals(2, reg.size());
			assertEquals(64, reg.get(0, box, WireChannel.RED, Kind.WIRE).getRate());
			assertEquals(32, reg.get(0, box, WireChannel.GREEN, Kind.WIRE).getRate());
		}

		@Test
		@DisplayName("a wire and a bolted connector on one conductor are two outlets")
		void kindsAreSeparate()
		{
			//A face serves both at once -- something touching it takes flux block to block while a
			//catenary takes it along the wire -- so one conductor really can have two outlets.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeNeighbour(0, box, WireChannel.RED, EnumFacing.WEST, 256);
			assertEquals(2, reg.size());
			assertEquals(64, reg.get(0, box, WireChannel.RED, Kind.WIRE).getRate());
			assertEquals(256, reg.get(0, box, WireChannel.RED, Kind.NEIGHBOUR).getRate());
		}

		@Test
		@DisplayName("the same box in two dimensions is two records")
		void dimensionsAreSeparate()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeWire(-1, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			assertEquals(2, reg.size());
		}

		@Test
		@DisplayName("an outlet that is gone is forgotten, and only that one")
		void removeOne()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeNeighbour(0, box, WireChannel.RED, EnumFacing.WEST, 64);
			reg.observeWire(0, box, WireChannel.GREEN, EnumFacing.EAST, 64, "COPPER", end);
			assertTrue(reg.remove(0, box, WireChannel.RED, Kind.WIRE));
			assertFalse(reg.remove(0, box, WireChannel.RED, Kind.WIRE), "removing twice is not a change");
			assertEquals(2, reg.size());
			assertNull(reg.get(0, box, WireChannel.RED, Kind.WIRE));
			assertNotNull(reg.get(0, box, WireChannel.RED, Kind.NEIGHBOUR), "the other kind is untouched");
			assertNotNull(reg.get(0, box, WireChannel.GREEN, Kind.WIRE), "its neighbour is untouched");
		}

		@Test
		@DisplayName("a box that is gone takes its outlets and its feeds with it")
		void removeWholeBox()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeNeighbour(0, box, WireChannel.BLUE, EnumFacing.UP, 64);
			reg.addFeed(0, box, WireChannel.RED);
			reg.observeWire(0, new BlockPos(0, 0, 0), WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			assertEquals(3, reg.removeBox(0, box));
			assertEquals(1, reg.size(), "the other box is untouched");
			assertEquals(0, reg.feedCount());
		}

		@Test
		@DisplayName("a mutable position cannot change under the key")
		void mutablePositionIsCopied()
		{
			BlockPos.MutableBlockPos moving = new BlockPos.MutableBlockPos(1, 2, 3);
			reg.observeWire(0, moving, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			moving.setPos(9, 9, 9);
			assertNotNull(reg.get(0, new BlockPos(1, 2, 3), WireChannel.RED, Kind.WIRE),
					"the record must still be filed where it was observed");
		}
	}

	@Nested
	@DisplayName("what an outlet is good for")
	class Usable
	{
		@Test
		@DisplayName("a catenary outlet with a rate and a wire can be pushed")
		void completeIsUsable()
		{
			assertTrue(reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end).isUsable());
		}

		@Test
		@DisplayName("a conductor delivering nothing is not")
		void zeroRateIsNotUsable()
		{
			assertFalse(reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 0, "COPPER", end).isUsable());
			assertFalse(reg.observeNeighbour(0, box, WireChannel.BLUE, EnumFacing.UP, 0).isUsable());
		}

		@Test
		@DisplayName("a catenary outlet with no wire on it is not")
		void noWireIsNotUsable()
		{
			assertFalse(reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, null, null).isUsable());
			assertFalse(reg.observeWire(0, box, WireChannel.BLUE, EnumFacing.UP, 64, "", end).isUsable());
		}

		@Test
		@DisplayName("a bolted outlet needs no wire at all, which is the point of it")
		void boltedNeedsNoWire()
		{
			VirtualConduitLink link = reg.observeNeighbour(0, box, WireChannel.RED, EnumFacing.WEST, 256);
			assertTrue(link.isUsable());
			assertNull(link.getWireEnd());
			assertEquals(box.offset(EnumFacing.WEST), link.getOutletPos(),
					"a bolted outlet pushes from the connector, not from the box");
		}

		@Test
		@DisplayName("a catenary outlet pushes from the box")
		void wirePushesFromTheBox()
		{
			assertEquals(box, reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end)
					.getOutletPos());
		}
	}

	@Nested
	@DisplayName("saving and loading")
	class Persistence
	{
		@Test
		@DisplayName("a table survives a round trip")
		void roundTrip()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.observeWire(0, box, WireChannel.GREEN, EnumFacing.EAST, 128, "ELECTRUM", new BlockPos(-5, 6, -7));
			reg.observeWire(-1, new BlockPos(1, 2, 3), WireChannel.WHITE, EnumFacing.UP, 8, "STEEL", end);

			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));

			assertEquals(reg.size(), back.size());
			VirtualConduitLink green = back.get(0, box, WireChannel.GREEN, Kind.WIRE);
			assertNotNull(green);
			assertEquals(128, green.getRate());
			assertEquals("ELECTRUM", green.getWireTypeName());
			assertEquals(new BlockPos(-5, 6, -7), green.getWireEnd());
			assertEquals(EnumFacing.EAST, green.getFace());
			assertNotNull(back.get(-1, new BlockPos(1, 2, 3), WireChannel.WHITE, Kind.WIRE));
		}

		@Test
		@DisplayName("a bolted outlet survives a round trip as one")
		void neighbourRoundTrips()
		{
			reg.observeNeighbour(0, box, WireChannel.RED, EnumFacing.UP, 4096);
			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));

			VirtualConduitLink link = back.get(0, box, WireChannel.RED, Kind.NEIGHBOUR);
			assertNotNull(link, "the kind is part of the key and has to come back with it");
			assertEquals(Kind.NEIGHBOUR, link.getKind());
			assertEquals(EnumFacing.UP, link.getFace());
			assertEquals(4096, link.getRate());
			assertEquals(box.offset(EnumFacing.UP), link.getOutletPos());
			assertTrue(link.isUsable());
		}

		@Test
		@DisplayName("a record written before bolted outlets existed reads as a catenary")
		void oldRecordReadsAsWire()
		{
			//Every record in a save from before this change is a wire, because the catenary path was
			//the only one that ever wrote one.
			NBTTagCompound old = new NBTTagCompound();
			old.setInteger("dim", 0);
			old.setLong("box", box.toLong());
			old.setString("channel", WireChannel.RED.getName());
			old.setInteger("rate", 64);
			old.setString("wire", "COPPER");
			old.setLong("end", end.toLong());
			NBTTagCompound saved = new NBTTagCompound();
			net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
			list.appendTag(old);
			saved.setTag("links", list);

			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(saved);
			VirtualConduitLink link = back.get(0, box, WireChannel.RED, Kind.WIRE);
			assertNotNull(link, "no kind in the tag means the kind that used to be the only one");
			assertEquals(Kind.WIRE, link.getKind());
			assertEquals("COPPER", link.getWireTypeName());
			assertNull(link.getFace(), "an old record never knew which face it was on");
			assertTrue(link.isUsable(), "and it still has everything the push needs");
		}

		@Test
		@DisplayName("a kind this build does not have reads as a catenary rather than throwing")
		void unknownKindReadsAsWire()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("dim", 0);
			tag.setLong("box", box.toLong());
			tag.setString("channel", WireChannel.RED.getName());
			tag.setString("kind", "SEMAPHORE");
			tag.setInteger("rate", 64);
			VirtualConduitLink link = VirtualConduitLink.readFromNBT(tag);
			assertNotNull(link);
			assertEquals(Kind.WIRE, link.getKind());
		}

		@Test
		@DisplayName("negative coordinates survive, which is most of a map")
		void negativeCoordinates()
		{
			BlockPos far = new BlockPos(-2048, 250, -6045);
			reg.observeWire(0, far, WireChannel.BLUE, EnumFacing.NORTH, 40, "COPPER",
					new BlockPos(-2050, 250, -6045));
			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));
			VirtualConduitLink link = back.get(0, far, WireChannel.BLUE, Kind.WIRE);
			assertNotNull(link, "a long packs to a negative number and must come back the same");
			assertEquals(new BlockPos(-2050, 250, -6045), link.getWireEnd());
		}

		@Test
		@DisplayName("feeds survive a round trip beside the outlets")
		void feedsRoundTrip()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			reg.addFeed(0, box, WireChannel.RED);
			reg.addFeed(-1, new BlockPos(1, 2, 3), WireChannel.WHITE);

			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));
			assertEquals(2, back.feedCount());
			assertTrue(back.hasFeed(0, box, WireChannel.RED));
			assertTrue(back.hasFeed(-1, new BlockPos(1, 2, 3), WireChannel.WHITE));
			assertFalse(back.hasFeed(0, box, WireChannel.GREEN));
		}

		@Test
		@DisplayName("a conductor this build does not have is dropped rather than thrown over")
		void unknownChannelIsDropped()
		{
			//A save written by a version carrying more conductors must load, minus the ones that
			//mean nothing here.
			NBTTagCompound tag = reg.writeToNBT(new NBTTagCompound());
			NBTTagCompound stray = new NBTTagCompound();
			stray.setInteger("dim", 0);
			stray.setLong("box", box.toLong());
			stray.setString("channel", "chartreuse");
			stray.setInteger("rate", 64);
			tag.getTagList("links", 10).appendTag(stray);

			VirtualConduits back = new VirtualConduits();
			assertDoesNotThrow(() -> back.readFromNBT(tag));
			assertEquals(0, back.size());
		}

		@Test
		@DisplayName("reading replaces what was there rather than adding to it")
		void readingClearsFirst()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 64, "COPPER", end);
			NBTTagCompound saved = reg.writeToNBT(new NBTTagCompound());
			reg.observeWire(0, box, WireChannel.BLUE, EnumFacing.UP, 64, "COPPER", end);
			reg.addFeed(0, box, WireChannel.BLUE);
			reg.readFromNBT(saved);
			assertEquals(1, reg.size(), "a second world in one session starts clean");
			assertEquals(0, reg.feedCount());
			assertNull(reg.get(0, box, WireChannel.BLUE, Kind.WIRE));
		}
	}

	@Nested
	@DisplayName("feeds and liveness")
	class Liveness
	{
		private final BlockPos near = new BlockPos(0, 64, 0);
		private final BlockPos middle = new BlockPos(0, 64, 100);
		private final BlockPos far = new BlockPos(0, 64, 200);
		private final BlockPos elsewhere = new BlockPos(500, 64, 500);

		/** A bundle graph and nothing else: near -- middle -- far, and a lone box off on its own. */
		private FakeBundles bundles;

		@BeforeEach
		void wireItUp()
		{
			bundles = new FakeBundles();
			bundles.join(near, middle);
			bundles.join(middle, far);
			reg.setWorld(bundles);
		}

		@Test
		@DisplayName("with no feed anywhere, nothing is live")
		void noFeedIsDark()
		{
			assertFalse(reg.isLive(0, far, WireChannel.RED));
		}

		@Test
		@DisplayName("a feed at one end makes the whole run live")
		void feedFloodsTheRun()
		{
			//The property the whole feature turns on: the far box is live with every block between
			//it and the feed unloaded, because this is answered from the bundles and not from a box.
			reg.addFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, near, WireChannel.RED));
			assertTrue(reg.isLive(0, middle, WireChannel.RED));
			assertTrue(reg.isLive(0, far, WireChannel.RED));
		}

		@Test
		@DisplayName("a box on no run of its own stays dark")
		void unconnectedStaysDark()
		{
			reg.addFeed(0, near, WireChannel.RED);
			assertFalse(reg.isLive(0, elsewhere, WireChannel.RED));
		}

		@Test
		@DisplayName("the conductors stay apart across the flood")
		void channelsAreSeparate()
		{
			//A bundle carrying a lighting circuit and a workshop circuit must not have one vouch for
			//the other simply because they share a run.
			reg.addFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, far, WireChannel.RED));
			assertFalse(reg.isLive(0, far, WireChannel.GREEN));
		}

		@Test
		@DisplayName("a bundle that does not carry the conductor does not carry the feed")
		void bundleWithoutTheChannelBlocks()
		{
			bundles.drop(middle, far, WireChannel.RED);
			reg.addFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, middle, WireChannel.RED));
			assertFalse(reg.isLive(0, far, WireChannel.RED), "the last hop does not carry red");
		}

		@Test
		@DisplayName("dimensions do not bleed into each other")
		void dimensionsAreSeparate()
		{
			reg.addFeed(0, near, WireChannel.RED);
			assertFalse(reg.isLive(-1, far, WireChannel.RED));
		}

		@Test
		@DisplayName("the run goes dark when the last feed goes")
		void removingTheFeedGoesDark()
		{
			//The other half of the same property: a plant switched off must take the town with it,
			//and must do so without waiting for anybody to walk the line.
			reg.addFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, far, WireChannel.RED));
			reg.removeFeed(0, near, WireChannel.RED);
			assertFalse(reg.isLive(0, far, WireChannel.RED));
		}

		@Test
		@DisplayName("one of two feeds going leaves the run live")
		void secondFeedHoldsItUp()
		{
			reg.addFeed(0, near, WireChannel.RED);
			reg.addFeed(0, far, WireChannel.RED);
			reg.removeFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, middle, WireChannel.RED));
		}

		@Test
		@DisplayName("a run made after the answer was cached is picked up once it is invalidated")
		void invalidationIsWhatMakesTheCacheSafe()
		{
			reg.addFeed(0, near, WireChannel.RED);
			assertFalse(reg.isLive(0, elsewhere, WireChannel.RED));
			bundles.join(far, elsewhere);
			assertFalse(reg.isLive(0, elsewhere, WireChannel.RED), "the cached flood is still standing");
			reg.invalidateLiveness();
			assertTrue(reg.isLive(0, elsewhere, WireChannel.RED));
		}

		@Test
		@DisplayName("with the feature switched off nothing is live at all")
		void disabledIsAllDark()
		{
			reg.addFeed(0, near, WireChannel.RED);
			VirtualConduitConfig.enabled = false;
			assertFalse(reg.isLive(0, near, WireChannel.RED));
		}

		@Test
		@DisplayName("with no graph to walk, only a directly fed conductor is live")
		void noWorldMeansNoFlood()
		{
			reg.setWorld(null);
			reg.addFeed(0, near, WireChannel.RED);
			assertTrue(reg.isLive(0, near, WireChannel.RED));
			assertFalse(reg.isLive(0, far, WireChannel.RED),
					"standing in for a graph that is not there would be guessing");
		}

		@Test
		@DisplayName("a box says which of its conductors it is recorded as feeding")
		void feedMask()
		{
			reg.addFeed(0, near, WireChannel.RED);
			reg.addFeed(0, near, WireChannel.BLUE);
			reg.addFeed(0, far, WireChannel.GREEN);
			assertEquals(WireChannel.RED.getMask()|WireChannel.BLUE.getMask(), reg.feedMaskAt(0, near));
			assertEquals(WireChannel.GREEN.getMask(), reg.feedMaskAt(0, far));
			assertEquals(0, reg.feedMaskAt(0, elsewhere));
		}
	}

	/**
	 * A bundle graph with no world under it, which is all the liveness flood needs to be asked
	 * anything -- the point of {@link IVirtualConduitWorld} being an interface.
	 */
	private static final class FakeBundles implements IVirtualConduitWorld
	{
		/** position to peer to the conductors that hop carries */
		private final Map<BlockPos, Map<BlockPos, Integer>> edges = new HashMap<>();

		void join(BlockPos a, BlockPos b)
		{
			edges.computeIfAbsent(a, k -> new HashMap<>()).put(b, WireChannel.ALL_MASK);
			edges.computeIfAbsent(b, k -> new HashMap<>()).put(a, WireChannel.ALL_MASK);
		}

		void drop(BlockPos a, BlockPos b, WireChannel channel)
		{
			edges.get(a).computeIfPresent(b, (k, mask) -> mask&~channel.getMask());
			edges.get(b).computeIfPresent(a, (k, mask) -> mask&~channel.getMask());
		}

		@Override
		public boolean isDimensionLoaded(int dimension)
		{
			return true;
		}

		@Override
		public boolean isLoaded(int dimension, BlockPos pos)
		{
			return false;
		}

		@Override
		public boolean stillBreaksOut(int dimension, BlockPos boxPos, VirtualConduitLink link)
		{
			return true;
		}

		@Override
		public Collection<BlockPos> bundleNeighbours(int dimension, BlockPos boxPos, WireChannel channel)
		{
			if(dimension!=0)
				return Collections.emptyList();
			Map<BlockPos, Integer> peers = edges.get(boxPos);
			if(peers==null)
				return Collections.emptyList();
			List<BlockPos> out = new ArrayList<>();
			for(Map.Entry<BlockPos, Integer> peer : peers.entrySet())
				if((peer.getValue()&channel.getMask())!=0)
					out.add(peer.getKey());
			return out;
		}

		@Override
		public int push(VirtualConduitLink link)
		{
			return 0;
		}
	}
}
