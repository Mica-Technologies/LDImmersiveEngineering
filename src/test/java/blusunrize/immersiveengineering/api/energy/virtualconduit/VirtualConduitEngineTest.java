package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One tick of virtual conduit, over an imagined world.
 * <p>
 * The two properties worth defending: a loaded box is never stood in for (delivering twice looks
 * like everything working, which is the worst way for this to be wrong), and a conductor's energy
 * stays on its own face's wire while unloaded, because that is the whole reason the record is per
 * conductor rather than per box.
 */
@DisplayName("VirtualConduitEngine")
class VirtualConduitEngineTest
{
	private VirtualConduits reg;
	private FakeWorld world;

	private final BlockPos box = new BlockPos(2067, 90, -5911);
	private final BlockPos redEnd = new BlockPos(2100, 90, -5911);
	private final BlockPos greenEnd = new BlockPos(2000, 90, -5911);

	@BeforeEach
	void setUp()
	{
		reg = new VirtualConduits();
		world = new FakeWorld();
		VirtualConduitConfig.reset();
	}

	/** What a push was asked to do, so a test can say who was offered what down which wire. */
	private static final class Push
	{
		final BlockPos box;
		final int amount;
		final String wire;
		final BlockPos end;

		Push(BlockPos box, int amount, String wire, BlockPos end)
		{
			this.box = box;
			this.amount = amount;
			this.wire = wire;
			this.end = end;
		}
	}

	private static final class FakeWorld implements IVirtualConduitWorld
	{
		final Set<Integer> dimensions = new HashSet<>();
		final Set<BlockPos> loaded = new HashSet<>();
		final Set<BlockPos> stillThere = new HashSet<>();
		final List<Push> pushes = new ArrayList<>();
		/** How much of any offer the network takes; -1 means "all of it". */
		int accepts = -1;

		FakeWorld()
		{
			dimensions.add(0);
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
		public int push(int dimension, BlockPos boxPos, int amount, String wireTypeName, BlockPos wireEnd)
		{
			pushes.add(new Push(boxPos, amount, wireTypeName, wireEnd));
			return accepts < 0?amount: Math.min(accepts, amount);
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
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			assertEquals(256, VirtualConduitEngine.tick(reg, world, true));
			assertEquals(1, world.pushes.size());
			assertEquals(box, world.pushes.get(0).box);
			assertEquals(256, world.pushes.get(0).amount);
		}

		@Test
		@DisplayName("a loaded box is left alone -- it is doing this itself")
		void loadedIsSkipped()
		{
			//Delivering both ways would double a town's supply, and more power looks like
			//everything working, so this failure would not get reported.
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			world.loaded.add(box);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("nothing happens outside city mode")
		void normalModeDoesNothing()
		{
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, false));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("the config switch turns the whole thing off")
		void disabledDoesNothing()
		{
			//Off must restore exactly what conduit did before this existed: fine while loaded,
			//dark otherwise.
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			VirtualConduitConfig.enabled = false;
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("an unloaded dimension is not walked")
		void unloadedDimensionIsSkipped()
		{
			reg.observe(7, box, WireChannel.RED, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("a record with no wire on it is not pushed")
		void unusableIsSkipped()
		{
			reg.observe(0, box, WireChannel.RED, 256, null, null);
			reg.observe(0, box, WireChannel.BLUE, 0, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.tick(reg, world, true));
			assertTrue(world.pushes.isEmpty());
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
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			reg.observe(0, box, WireChannel.GREEN, 64, "ELECTRUM", greenEnd);
			VirtualConduitEngine.tick(reg, world, true);

			assertEquals(2, world.pushes.size());
			Push red = world.pushes.stream().filter(p -> p.end.equals(redEnd)).findFirst().orElse(null);
			Push green = world.pushes.stream().filter(p -> p.end.equals(greenEnd)).findFirst().orElse(null);
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
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			world.accepts = 100;
			assertEquals(100, VirtualConduitEngine.tick(reg, world, true));
			assertEquals(100, reg.get(0, box, WireChannel.RED).getLastDelivered());
		}

		@Test
		@DisplayName("a link that delivered nothing this tick says so")
		void deliveredIsClearedEachTick()
		{
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			VirtualConduitEngine.tick(reg, world, true);
			assertEquals(256, reg.get(0, box, WireChannel.RED).getLastDelivered());
			//The box comes back: the readout must stop claiming a virtual delivery.
			world.loaded.add(box);
			VirtualConduitEngine.tick(reg, world, true);
			assertEquals(0, reg.get(0, box, WireChannel.RED).getLastDelivered());
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
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			world.loaded.add(box);
			//loaded, and stillThere does not contain it
			assertEquals(1, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(0, reg.size());
		}

		@Test
		@DisplayName("a breakout that is still there is kept")
		void presentIsKept()
		{
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
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
			reg.observe(0, box, WireChannel.RED, 256, "COPPER", redEnd);
			assertEquals(0, VirtualConduitEngine.sweepOrphans(reg, world));
			assertEquals(1, reg.size(), "an unloaded box is not evidence of anything");
		}
	}
}
