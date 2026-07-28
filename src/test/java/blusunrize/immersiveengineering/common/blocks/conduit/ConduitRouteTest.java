/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitRoute.Node;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Following a conduit run from one junction box to the next.
 * <p>
 * The walk is what turns a corridor full of blocks into one edge in the wire graph, so it is worth
 * over-testing: a bug here is either a run that silently does not connect, or -- worse -- one that
 * connects things that are not joined, which no amount of staring at the wall would reveal.
 * <p>
 * The world is a hand-drawn map rather than a real one. {@link ConduitRoute} takes a probe for
 * exactly this reason.
 */
class ConduitRouteTest
{
	/** A building drawn a block at a time. */
	private static class Map3D implements ConduitRoute.Probe
	{
		private final Map<BlockPos, Node> nodes = new HashMap<>();
		private final Map<BlockPos, EnumFacing> mounts = new HashMap<>();
		int probes;

		Map3D conduit(int x, int y, int z, EnumFacing mount)
		{
			BlockPos pos = new BlockPos(x, y, z);
			nodes.put(pos, Node.CONDUIT);
			mounts.put(pos, mount);
			return this;
		}

		Map3D junction(int x, int y, int z)
		{
			nodes.put(new BlockPos(x, y, z), Node.JUNCTION);
			return this;
		}

		@Override
		public Node nodeAt(BlockPos pos)
		{
			probes++;
			Node node = nodes.get(pos);
			return node==null?Node.NOTHING: node;
		}

		@Override
		public EnumFacing mountAt(BlockPos pos)
		{
			return mounts.get(pos);
		}
	}

	private Map3D world;

	@BeforeEach
	void setUp()
	{
		world = new Map3D();
	}

	private Map<BlockPos, Integer> from(int x, int y, int z)
	{
		return ConduitRoute.junctionsFrom(new BlockPos(x, y, z), world);
	}

	@Nested
	@DisplayName("simple runs")
	class Simple
	{
		@Test
		@DisplayName("a box on its own reaches nothing")
		void loneBoxReachesNothing()
		{
			world.junction(0, 0, 0);
			assertTrue(from(0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("a box with conduit going nowhere reaches nothing")
		void danglingRunReachesNothing()
		{
			world.junction(0, 0, 0);
			for(int z = 1; z <= 5; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			assertTrue(from(0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("a straight run joins two boxes and reports its length")
		void straightRunJoins()
		{
			//Floor-mounted conduit from z=1 to z=5, a box at each end.
			world.junction(0, 0, 0);
			for(int z = 1; z <= 5; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			world.junction(0, 0, 6);

			Map<BlockPos, Integer> found = from(0, 0, 0);
			assertEquals(1, found.size());
			assertEquals(6, found.get(new BlockPos(0, 0, 6)),
					"the length should count the conduit blocks plus the step onto the box");
		}

		@Test
		@DisplayName("the walk is symmetric")
		void walkIsSymmetric()
		{
			//If one end sees a run and the other does not, the connection is built and torn down on
			//alternate block updates forever.
			world.junction(0, 0, 0);
			for(int z = 1; z <= 4; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			world.junction(0, 0, 5);
			assertEquals(from(0, 0, 0).get(new BlockPos(0, 0, 5)),
					from(0, 0, 5).get(new BlockPos(0, 0, 0)));
		}

		@Test
		@DisplayName("a run that turns a corner still joins")
		void cornerRunJoins()
		{
			world.junction(0, 0, 0);
			for(int z = 1; z <= 3; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			for(int x = 1; x <= 3; x++)
				world.conduit(x, 0, 3, EnumFacing.DOWN);
			world.junction(4, 0, 3);
			assertEquals(1, from(0, 0, 0).size());
		}

		@Test
		@DisplayName("the starting box never appears in its own result")
		void startIsNotItsOwnPeer()
		{
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.conduit(1, 0, 1, EnumFacing.DOWN);
			world.conduit(1, 0, 0, EnumFacing.DOWN);
			assertFalse(from(0, 0, 0).containsKey(new BlockPos(0, 0, 0)));
		}
	}

	@Nested
	@DisplayName("what a run refuses to cross")
	class Refusals
	{
		@Test
		@DisplayName("a gap breaks a run")
		void gapBreaksTheRun()
		{
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			//z=2 missing
			world.conduit(0, 0, 3, EnumFacing.DOWN);
			world.junction(0, 0, 4);
			assertTrue(from(0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("a change of surface breaks a run")
		void surfaceChangeBreaksTheRun()
		{
			//The deliberate limit from P3: a run stays on one face, and a plane change goes through
			//a box. Two conduits on different walls do not silently join in the middle of a run.
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.conduit(0, 0, 2, EnumFacing.WEST);
			world.junction(0, 0, 3);
			assertTrue(from(0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("a conduit is only picked up along its own surface")
		void boxDoesNotPickUpAcrossThePlane()
		{
			//A conduit lying on the floor directly above the box runs north-south-east-west, so the
			//step down onto the box is off its surface and is not a join. The run itself passes
			//over that box untouched, between two others at its own level.
			world.junction(0, 0, 0);
			world.junction(0, 1, -1);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 1, EnumFacing.DOWN);
			world.junction(0, 1, 2);
			assertTrue(from(0, 0, 0).isEmpty(), "the box joined a run that passes over it");
			assertEquals(1, from(0, 1, 2).size(), "the run itself should still be intact");
			assertTrue(from(0, 1, 2).containsKey(new BlockPos(0, 1, -1)));
		}

		@Test
		@DisplayName("a run ends at the first box it meets")
		void runStopsAtTheFirstBox()
		{
			//Otherwise a corridor of boxes would be a fully-connected graph, and a long trunk with
			//boxes at every corner would cost the square of its length in edges.
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.junction(0, 0, 2);
			world.conduit(0, 0, 3, EnumFacing.DOWN);
			world.junction(0, 0, 4);

			Map<BlockPos, Integer> found = from(0, 0, 0);
			assertEquals(1, found.size());
			assertTrue(found.containsKey(new BlockPos(0, 0, 2)));
			assertFalse(found.containsKey(new BlockPos(0, 0, 4)),
					"the walk carried on through a box instead of ending at it");
		}
	}

	@Nested
	@DisplayName("awkward shapes")
	class Awkward
	{
		@Test
		@DisplayName("a tee reaches both far ends")
		void teeReachesBoth()
		{
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.conduit(0, 0, 2, EnumFacing.DOWN);
			world.conduit(-1, 0, 2, EnumFacing.DOWN);
			world.conduit(1, 0, 2, EnumFacing.DOWN);
			world.junction(-2, 0, 2);
			world.junction(2, 0, 2);
			assertEquals(2, from(0, 0, 0).size());
		}

		@Test
		@DisplayName("a loop terminates and reports the shorter way round")
		void loopTerminatesAndTakesTheShortPath()
		{
			//A ring main. Without a visited set this never finishes; with one it should still pick
			//the length somebody tracing the run with their eye would get.
			world.junction(0, 0, 0);
			for(int z = 1; z <= 2; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			world.junction(0, 0, 3);
			//A long way round: east, along, and back west.
			world.conduit(1, 0, 0, EnumFacing.DOWN);
			for(int z = 1; z <= 3; z++)
				world.conduit(1, 0, z, EnumFacing.DOWN);
			world.conduit(1, 0, 4, EnumFacing.DOWN);
			world.conduit(0, 0, 4, EnumFacing.DOWN);

			Map<BlockPos, Integer> found = from(0, 0, 0);
			assertEquals(1, found.size());
			assertEquals(3, found.get(new BlockPos(0, 0, 3)), "it took the long way round");
		}

		@Test
		@DisplayName("two runs to the same box are one connection")
		void parallelRunsAreOneConnection()
		{
			//Two physically separate paths between the same pair of boxes. One edge, not two: the
			//wire graph has no way to express a second wire between the same two nodes anyway.
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.conduit(0, 0, 2, EnumFacing.DOWN);
			world.conduit(1, 0, 0, EnumFacing.DOWN);
			world.conduit(1, 0, 1, EnumFacing.DOWN);
			world.conduit(1, 0, 2, EnumFacing.DOWN);
			world.junction(0, 0, 3);
			world.conduit(1, 0, 3, EnumFacing.DOWN);

			assertEquals(1, from(0, 0, 0).size());
		}

		@Test
		@DisplayName("a run on a ceiling works exactly like one on a floor")
		void ceilingRunWorks()
		{
			world.junction(0, 0, 0);
			for(int z = 1; z <= 3; z++)
				world.conduit(0, 0, z, EnumFacing.UP);
			world.junction(0, 0, 4);
			assertEquals(1, from(0, 0, 0).size());
		}

		@Test
		@DisplayName("a run up a wall works, since up is in a wall's plane")
		void wallRunClimbs()
		{
			//A conduit clipped to a north wall runs up, down, east and west. Climbing is ordinary
			//in-plane movement for it -- it is only leaving the wall that needs a box.
			world.junction(0, 0, 0);
			for(int y = 1; y <= 4; y++)
				world.conduit(0, y, 0, EnumFacing.NORTH);
			world.junction(0, 5, 0);
			assertEquals(1, from(0, 0, 0).size());
		}

		@Test
		@DisplayName("an enormous run stops at the node cap instead of hanging")
		void hugeRunIsBounded()
		{
			//The bound exists so a pathological build cannot hang the server thread. It has to be
			//generous enough that no real run hits it and finite anyway.
			world.junction(0, 0, 0);
			for(int z = 1; z < ConduitRoute.MAX_NODES+64; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			world.junction(0, 0, ConduitRoute.MAX_NODES+64);
			assertTrue(from(0, 0, 0).isEmpty(), "a run past the cap should simply not connect");
			assertTrue(world.probes < ConduitRoute.MAX_NODES*6+64,
					"the walk examined far more than its own bound");
		}
	}
}
