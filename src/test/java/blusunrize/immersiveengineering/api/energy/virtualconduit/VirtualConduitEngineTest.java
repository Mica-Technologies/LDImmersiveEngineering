package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitLink.Kind;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One tick of virtual conduit, over an imagined world.
 * <p>
 * The properties worth defending: a loaded box is never stood in for (delivering twice looks like
 * everything working, which is the worst way for this to be wrong), a conductor nothing feeds
 * delivers nothing however good its hardware is, and a conductor's energy stays on its own face's
 * wire while unloaded -- which is the whole reason a record is per conductor rather than per box.
 */
@DisplayName("VirtualConduitEngine")
class VirtualConduitEngineTest
{
	private VirtualConduits reg;
	private FakeWorld world;

	private final BlockPos box = new BlockPos(2067, 90, -5911);
	private final BlockPos peer = new BlockPos(2067, 96, -5711);
	private final BlockPos redEnd = new BlockPos(2100, 90, -5911);
	private final BlockPos greenEnd = new BlockPos(2000, 90, -5911);

	@BeforeEach
	void setUp()
	{
		reg = new VirtualConduits();
		world = new FakeWorld();
		reg.setWorld(world);
		VirtualConduitConfig.reset();
		//Every outlet in this file is on a run somebody is feeding unless a test says otherwise:
		//liveness is its own file's business, and an engine test that forgot it would only ever be
		//asserting that nothing happens.
		reg.addFeed(0, box, WireChannel.RED);
		reg.addFeed(0, box, WireChannel.GREEN);
		reg.addFeed(7, box, WireChannel.RED);
	}

	/** What a push was asked to do, so a test can say who was offered what down which wire. */
	private static final class Push
	{
		final BlockPos from;
		final int amount;
		final String wire;
		final BlockPos end;

		Push(VirtualConduitLink link)
		{
			this.from = link.getOutletPos();
			this.amount = link.getRate();
			this.wire = link.getWireTypeName();
			this.end = link.getWireEnd();
		}
	}

	private static final class FakeWorld implements IVirtualConduitWorld
	{
		final Set<Integer> dimensions = new HashSet<>();
		final Set<BlockPos> loaded = new HashSet<>();
		final Set<BlockPos> stillThere = new HashSet<>();
		final Map<BlockPos, List<BlockPos>> bundles = new HashMap<>();
		final List<Push> pushes = new ArrayList<>();
		/** How much of any offer the network takes; -1 means "all of it". */
		int accepts = -1;

		FakeWorld()
		{
			dimensions.add(0);
		}

		void join(BlockPos a, BlockPos b)
		{
			bundles.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
			bundles.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
		}

		@Override
		public boolean isDimensionLoaded(int dimension)
		{
			return dimensions.contains(dimension);
		}

		@Override
		public boolean isLoaded(int dimension, BlockPos pos)
		{
			return loaded.contains(pos);
		}

		@Override
		public boolean stillBreaksOut(int dimension, BlockPos boxPos, VirtualConduitLink link)
		{
			return stillThere.contains(boxPos);
		}

		@Override
		public Collection<BlockPos> bundleNeighbours(int dimension, BlockPos boxPos, WireChannel channel)
		{
			return bundles.getOrDefault(boxPos, Collections.emptyList());
		}

		@Override
		public int push(VirtualConduitLink link)
		{
			pushes.add(new Push(link));
			return accepts < 0?link.getRate(): Math.min(accepts, link.getRate());
		}
	}

	@Nested
	@DisplayName("who gets stood in for")
	class WhoIsPushed
	{
		@Test
		@DisplayName("an unloaded box's conductor is delivered for it")
		void unloadedIsPushed()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(256, VirtualConduitEngine.tick(reg, world, true));
			assertEquals(1, world.pushes.size());
			assertEquals(box, world.pushes.get(0).from);
			assertEquals(256, world.pushes.get(0).amount);
		}

		@Test
		@DisplayName("a bolted connector's outlet pushes from the connector, unfiltered")
		void boltedPushesFromTheConnector()
		{
			//The kind that was never recorded at all, and the kind every breakout on the poles this
			//was written for actually is. A connector has one terminal, so there is no wire filter
			//to rebuild -- filtering on a wire it does not have would deliver nothing.
			reg.observeNeighbour(0, box, WireChannel.RED, EnumFacing.UP, 4096);
			assertEquals(4096, VirtualConduitEngine.tick(reg, world, true));
			assertEquals(1, world.pushes.size());
			assertEquals(box.offset(EnumFacing.UP), world.pushes.get(0).from);
			assertNull(world.pushes.get(0).wire);
		}

		@Test
		@DisplayName("a loaded box is left alone -- it is doing this itself")
		void loadedIsSkipped()
		{
			//Delivering both ways would double a town's supply, and more power looks like
			//everything working, so this failure would not get reported.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			world.loaded.add(box);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("nothing happens outside city mode")
		void normalModeDoesNothing()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, false));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("the config switch turns the whole thing off")
		void disabledDoesNothing()
		{
			//Off must restore exactly what conduit did before this existed: fine while loaded,
			//dark otherwise.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			VirtualConduitConfig.enabled = false;
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("an unloaded dimension is not walked")
		void unloadedDimensionIsSkipped()
		{
			reg.observeWire(7, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("a record with nothing on that face to deliver into is not pushed")
		void unusableIsSkipped()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, null, null);
			reg.observeWire(0, box, WireChannel.GREEN, EnumFacing.EAST, 0, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}
	}

	@Nested
	@DisplayName("a conductor nothing is feeding")
	class Dark
	{
		@Test
		@DisplayName("an outlet on a run with no feed delivers nothing")
		void darkIsSkipped()
		{
			//An outlet exists while its hardware exists, which says nothing about the circuit being
			//switched on. Without this a run would go on supplying a town from a generator somebody
			//dismantled last week.
			reg.removeFeed(0, box, WireChannel.RED);
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("an outlet whose feed is at the other end of the run delivers")
		void feedAtTheFarEndCounts()
		{
			//The case the whole feature is for: the town's box and the plant's box are hundreds of
			//blocks apart and have never been loaded at the same time.
			reg.removeFeed(0, box, WireChannel.RED);
			reg.addFeed(0, peer, WireChannel.RED);
			world.join(box, peer);
			reg.invalidateLiveness();
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(256, VirtualConduitEngine.tick(reg, world, true));
		}

		@Test
		@DisplayName("the run goes quiet within a tick of the last feed going, and the outlet stays")
		void feedRemovedGoesQuiet()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(256, VirtualConduitEngine.tick(reg, world, true));
			reg.removeFeed(0, box, WireChannel.RED);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			//Dark is not gone. The hardware is still on the wall, and deleting the record for a
			//circuit that is merely switched off is what made the far end's own visit take the far
			//town down for good.
			assertEquals(1, reg.size());
			assertNotNull(reg.get(0, box, WireChannel.RED, Kind.WIRE));
		}

		@Test
		@DisplayName("a conductor that comes back delivers again with nothing rebuilt")
		void feedReturningLightsItAgain()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			reg.removeFeed(0, box, WireChannel.RED);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			reg.addFeed(0, box, WireChannel.RED);
			assertEquals(256, VirtualConduitEngine.tick(reg, world, true),
					"the outlet was still there, so the plant coming back is all it took");
		}
	}

	@Nested
	@DisplayName("keeping the conductors apart")
	class Channels
	{
		@Test
		@DisplayName("each conductor is pushed down its own face's wire")
		void eachChannelKeepsItsWire()
		{
			//The property the whole per-conductor record exists for: a bundle carrying a lighting
			//circuit and a workshop circuit must not merge them once nobody is standing there.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			reg.observeWire(0, box, WireChannel.GREEN, EnumFacing.EAST, 64, "ELECTRUM", greenEnd);
			VirtualConduitEngine.tick(reg, world, true);

			assertEquals(2, world.pushes.size());
			Push red = world.pushes.stream().filter(p -> redEnd.equals(p.end)).findFirst().orElse(null);
			Push green = world.pushes.stream().filter(p -> greenEnd.equals(p.end)).findFirst().orElse(null);
			assertNotNull(red);
			assertNotNull(green);
			assertEquals(256, red.amount);
			assertEquals("COPPER", red.wire);
			assertEquals(64, green.amount);
			assertEquals("ELECTRUM", green.wire);
		}
	}

	@Nested
	@DisplayName("what it reports")
	class Reporting
	{
		@Test
		@DisplayName("a link records what the network actually took")
		void deliveredIsWhatWasTaken()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			world.accepts = 100;
			assertEquals(100, VirtualConduitEngine.tick(reg, world, true));
			assertEquals(100, reg.get(0, box, WireChannel.RED, Kind.WIRE).getLastDelivered());
		}

		@Test
		@DisplayName("a link that delivered nothing this tick says so")
		void deliveredIsClearedEachTick()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			VirtualConduitEngine.tick(reg, world, true);
			assertEquals(256, reg.get(0, box, WireChannel.RED, Kind.WIRE).getLastDelivered());
			//The box comes back: the readout must stop claiming a virtual delivery.
			world.loaded.add(box);
			VirtualConduitEngine.tick(reg, world, true);
			assertEquals(0, reg.get(0, box, WireChannel.RED, Kind.WIRE).getLastDelivered());
		}
	}

	@Nested
	@DisplayName("the orphan sweep")
	class Sweep
	{
		@Test
		@DisplayName("a breakout that is gone is forgotten")
		void goneIsDropped()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			world.loaded.add(box);
			//loaded, and stillThere does not contain it
			assertEquals(1, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(0, reg.size());
		}

		@Test
		@DisplayName("a breakout that is still there is kept")
		void presentIsKept()
		{
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			world.loaded.add(box);
			world.stillThere.add(box);
			assertEquals(0, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(1, reg.size());
		}

		@Test
		@DisplayName("an unloaded box is never swept -- that is the case the record is for")
		void unloadedIsNeverSwept()
		{
			//Reading "cannot see it" as "it is gone" is the mistake that was deleting whole runs
			//out of the wire graph. It must not be repeated here.
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(1, reg.size(), "an unloaded box is not evidence of anything");
		}

		@Test
		@DisplayName("a dark outlet is not swept either -- dark is not gone")
		void darkIsNotSwept()
		{
			//The round-one bug, stated as a test: a conductor going out is a fact about the circuit,
			//not about the hardware, and deleting the record for it is what made a far box's own
			//visit take the far town down.
			reg.removeFeed(0, box, WireChannel.RED);
			reg.observeWire(0, box, WireChannel.RED, EnumFacing.WEST, 256, "COPPER", redEnd);
			world.loaded.add(box);
			world.stillThere.add(box);
			assertEquals(0, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(1, reg.size(), "the breakout is still there, so the record stays");
		}
	}
}
