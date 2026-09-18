package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
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
 * The rule that stops a conduit run feeding itself through a wire network.
 * <p>
 * The latch is worth restating, because the test names below are meaningless without it. In city
 * mode any credit at all fills a conductor and it stays lit for twenty ticks. Put two breakouts of
 * one run onto the same network -- three connectors bolted to three boxes on one pole, which is what
 * a pole looks like -- and the run feeds itself: box one hands its conductor to its connector, the
 * connector pushes across the network on its own tick, the push reaches a connector bolted to box
 * two of the same run, and box two reads a credit and fills. Both poles on the playtester's line did
 * this, and their far boxes read a full channel in the save with no run connecting them to anything.
 * <p>
 * The three things this has to get right, and all three are here: the same run is skipped, a
 * <em>different</em> run is still served -- run to network to another run is a series build somebody
 * meant -- and a push from a node with no run behind it, which is virtually every push on a server,
 * is untouched and costs nothing.
 */
@DisplayName("ConduitRuns")
class ConduitRunsTest
{
	/** A bundle graph and nothing else, which is all the flood needs. */
	private static final class Bundles
	{
		private final Map<BlockPos, List<BlockPos>> edges = new HashMap<>();

		Bundles join(BlockPos a, BlockPos b)
		{
			edges.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
			edges.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
			return this;
		}

		Collection<BlockPos> of(BlockPos pos)
		{
			return edges.getOrDefault(pos, Collections.emptyList());
		}
	}

	private final BlockPos nearBox = new BlockPos(2067, 90, -5910);
	private final BlockPos nearBoxB = new BlockPos(2067, 90, -5912);
	private final BlockPos farBox = new BlockPos(2067, 96, -5710);
	private final BlockPos farBoxB = new BlockPos(2067, 96, -5712);

	@Nested
	@DisplayName("what one run is")
	class Flood
	{
		@Test
		@DisplayName("a lone box is its own run")
		void loneBox()
		{
			Bundles bundles = new Bundles();
			assertEquals(Collections.singleton(nearBox),
					ConduitRuns.boxesOfRun(nearBox, bundles::of));
		}

		@Test
		@DisplayName("a run is every box the bundles reach, however far apart they are")
		void wholeRun()
		{
			//The topology this was written for: two boxes on a pole at each end, a full mesh between
			//them, and two hundred blocks of conduit in the middle that are not nodes at all.
			Bundles bundles = new Bundles()
					.join(nearBox, farBox).join(nearBox, farBoxB)
					.join(nearBoxB, farBox).join(nearBoxB, farBoxB);
			Set<BlockPos> run = ConduitRuns.boxesOfRun(nearBox, bundles::of);
			assertEquals(new HashSet<>(java.util.Arrays.asList(nearBox, nearBoxB, farBox, farBoxB)), run);
		}

		@Test
		@DisplayName("a loop in the graph does not become a loop in the walk")
		void cyclesTerminate()
		{
			Bundles bundles = new Bundles()
					.join(nearBox, farBox).join(farBox, farBoxB).join(farBoxB, nearBox);
			assertEquals(3, ConduitRuns.boxesOfRun(nearBox, bundles::of).size());
		}

		@Test
		@DisplayName("two runs that never touch are two runs")
		void separateRunsStaySeparate()
		{
			Bundles bundles = new Bundles().join(nearBox, nearBoxB).join(farBox, farBoxB);
			assertFalse(ConduitRuns.boxesOfRun(nearBox, bundles::of).contains(farBox));
		}
	}

	@Nested
	@DisplayName("what a push from a run may not reach")
	class Shadow
	{
		@Test
		@DisplayName("the run's own boxes are skipped")
		void ownBoxesAreSkipped()
		{
			//A wire strung straight at a box face arrives at the box itself, so this is the shape
			//the latch takes when nobody bolted a connector on.
			Set<BlockPos> shadow = ConduitRuns.shadowOf(
					ConduitRuns.boxesOfRun(nearBox, new Bundles().join(nearBox, farBox)::of));
			assertTrue(ConduitRuns.blocks(shadow, farBox));
		}

		@Test
		@DisplayName("a connector bolted to a box of the same run is skipped")
		void sameRunConnectorIsSkipped()
		{
			//The latch itself: this connector, fed from the network, inserts straight back into the
			//box it is bolted to, and the run has come full circle.
			Set<BlockPos> shadow = ConduitRuns.shadowOf(
					ConduitRuns.boxesOfRun(nearBox, new Bundles().join(nearBox, farBox)::of));
			for(EnumFacing face : EnumFacing.VALUES)
				assertTrue(ConduitRuns.blocks(shadow, farBox.offset(face)),
						"a connector on the "+face.getName()+" face of a peer is the same run");
		}

		@Test
		@DisplayName("a connector on a different run is served")
		void differentRunIsServed()
		{
			//Run to network to a different run is a series build, and refusing it would break
			//something legitimate to fix something that is not.
			Set<BlockPos> shadow = ConduitRuns.shadowOf(
					ConduitRuns.boxesOfRun(nearBox, new Bundles().join(nearBox, nearBoxB)::of));
			assertFalse(ConduitRuns.blocks(shadow, farBox));
			assertFalse(ConduitRuns.blocks(shadow, farBox.offset(EnumFacing.UP)));
		}

		@Test
		@DisplayName("an ordinary consumer two blocks away is served")
		void distantConsumerIsServed()
		{
			Set<BlockPos> shadow = ConduitRuns.shadowOf(Collections.singleton(nearBox));
			assertFalse(ConduitRuns.blocks(shadow, nearBox.north(2)));
			assertFalse(ConduitRuns.blocks(shadow, new BlockPos(2664, 248, -4318)));
		}

		@Test
		@DisplayName("a push from a node with no run behind it is untouched")
		void noRunChangesNothing()
		{
			//Virtually every push on a server. It has to cost a null check and nothing else -- this
			//loop runs for every connector every tick on networks of thousands of nodes.
			assertFalse(ConduitRuns.blocks(null, nearBox));
			assertFalse(ConduitRuns.blocks(null, new BlockPos(0, 0, 0)));
		}
	}
}
