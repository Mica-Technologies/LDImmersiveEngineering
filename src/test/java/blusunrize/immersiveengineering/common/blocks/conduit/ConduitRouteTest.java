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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
		private final Map<BlockPos, EnumFacing.Axis> axes = new HashMap<>();
		private final Set<BlockPos> solid = new HashSet<>();
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

		Map3D feeder(int x, int y, int z, EnumFacing.Axis axis)
		{
			BlockPos pos = new BlockPos(x, y, z);
			nodes.put(pos, Node.PASS_THROUGH);
			axes.put(pos, axis);
			return this;
		}

		/**
		 * A block of building. It carries no run, but a run turning an inner corner needs one in
		 * the corner to turn around -- that is the block the riser hugs on its way up.
		 */
		Map3D wall(int x, int y, int z)
		{
			solid.add(new BlockPos(x, y, z));
			return this;
		}

		/** A whole floor at y, so a run has something to lie on and a wall has a foot. */
		Map3D floor(int y)
		{
			for(int x = -4; x <= 4; x++)
				for(int z = -4; z <= 4; z++)
					solid.add(new BlockPos(x, y, z));
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

		@Override
		public EnumFacing.Axis axisAt(BlockPos pos)
		{
			return axes.get(pos);
		}

		@Override
		public boolean isMountable(BlockPos pos, EnumFacing face)
		{
			return solid.contains(pos);
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
			//A run turns corners, but only corners: two conduits on perpendicular faces that are
			//not at a corner of anything -- there is no wall in this world at all -- are as
			//unjoined as they ever were.
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
	@DisplayName("runs that turn corners")
	class Corners
	{
		@Test
		@DisplayName("a floor run reaches a wall and carries on up it")
		void innerCornerJoins()
		{
			//	=================================
			//	The one the playtester asked for.
			//	=================================
			//A run along the floor, a wall in its way, and the run continuing up the wall without a
			//junction box at the corner. Before this the two halves were two runs, and the second
			//one was joined to nothing at all.
			world.floor(0);
			for(int y = 1; y <= 3; y++)
				world.wall(1, y, 0);
			world.junction(-2, 1, 0);
			world.conduit(-1, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 2, 0, EnumFacing.EAST);
			world.conduit(0, 3, 0, EnumFacing.EAST);
			world.junction(0, 4, 0);

			Map<BlockPos, Integer> found = from(-2, 1, 0);
			assertEquals(1, found.size(), "the run stopped where the floor did");
			assertTrue(found.containsKey(new BlockPos(0, 4, 0)));
		}

		@Test
		@DisplayName("and the walk agrees from the other end")
		void innerCornerIsSymmetric()
		{
			world.floor(0);
			for(int y = 1; y <= 3; y++)
				world.wall(1, y, 0);
			world.junction(-2, 1, 0);
			world.conduit(-1, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 2, 0, EnumFacing.EAST);
			world.conduit(0, 3, 0, EnumFacing.EAST);
			world.junction(0, 4, 0);

			assertEquals(from(-2, 1, 0).get(new BlockPos(0, 4, 0)),
					from(0, 4, 0).get(new BlockPos(-2, 1, 0)),
					"one end sees the corner and the other does not");
		}

		@Test
		@DisplayName("with no wall in the corner there is no corner")
		void innerCornerNeedsSomethingToTurnAround()
		{
			//The riser hugs the block in front of the lower conduit on its way up. Take that block
			//away -- a doorway, say -- and the two conduits are a floor run and a wall run that
			//happen to be near each other, which is what they look like.
			world.floor(0);
			world.wall(1, 2, 0);
			world.wall(1, 3, 0);
			world.junction(-2, 1, 0);
			world.conduit(-1, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 2, 0, EnumFacing.EAST);
			world.junction(0, 3, 0);

			assertTrue(from(-2, 1, 0).isEmpty(),
					"the run turned a corner around a wall that is not there");
		}

		@Test
		@DisplayName("a feeder in the corner is passed through, not climbed")
		void aFeederIsNotACorner()
		{
			//A ground feeder is solid, so it would pass the "is there a block to turn around" test
			//on its own. It must not: a run reaching one goes through it, which is the entire point
			//of the block, and a run that climbed one instead would take the wrong path silently.
			world.floor(0);
			world.wall(1, 2, 0);
			world.feeder(1, 1, 0, EnumFacing.Axis.Y);
			world.junction(-2, 1, 0);
			world.conduit(-1, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 2, 0, EnumFacing.EAST);
			world.junction(0, 3, 0);

			assertTrue(from(-2, 1, 0).isEmpty(), "the run climbed a ground feeder");
		}

		@Test
		@DisplayName("a run reaching the end of a beam follows its edge round")
		void outerCornerJoins()
		{
			//The other corner, and the one the annotated screenshot was of: a run along the top of
			//a beam reaching the beam's end and continuing down its end face. The two conduits are
			//diagonal neighbours bolted to the same last block of the beam.
			world.junction(-1, 1, 0);
			for(int x = 0; x <= 2; x++)
				world.conduit(x, 1, 0, EnumFacing.DOWN);
			world.conduit(3, 0, 0, EnumFacing.WEST);
			world.conduit(3, -1, 0, EnumFacing.WEST);
			world.junction(3, -2, 0);

			Map<BlockPos, Integer> found = from(-1, 1, 0);
			assertEquals(1, found.size(), "the run stopped at the edge instead of following it");
			assertTrue(found.containsKey(new BlockPos(3, -2, 0)));
			assertEquals(from(-1, 1, 0).get(new BlockPos(3, -2, 0)),
					from(3, -2, 0).get(new BlockPos(-1, 1, 0)),
					"one end sees the corner and the other does not");
		}

		@Test
		@DisplayName("a diagonal neighbour facing any other way is not a corner")
		void wrongMountIsNotAWrap()
		{
			//The far piece has to be clipped to the same block this one is, on the face round the
			//edge. Anything else is a conduit that happens to be diagonally adjacent, which in a
			//base full of conduit is most of them.
			world.junction(-1, 1, 0);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(1, 0, 0, EnumFacing.DOWN);
			world.junction(1, -1, 0);

			assertTrue(from(-1, 1, 0).isEmpty(), "a diagonal neighbour joined without a corner");
		}

		@Test
		@DisplayName("an inner corner works in every mount and every direction")
		void innerCornersEverywhere()
		{
			//Six mounts times four arms, each built as its own little world: the corner rules are
			//written in terms of facings, and a sign error in one of them would show up as conduit
			//that wraps on three walls and not the fourth.
			BlockPos origin = new BlockPos(0, 0, 0);
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					world = new Map3D();
					BlockPos wall = origin.offset(mount.getOpposite());
					BlockPos far = wall.offset(mount.getOpposite());
					BlockPos near = origin.offset(dir.getOpposite());
					world.junction(near.getX(), near.getY(), near.getZ());
					world.conduit(origin.getX(), origin.getY(), origin.getZ(), mount);
					world.conduit(wall.getX(), wall.getY(), wall.getZ(), dir);
					world.junction(far.getX(), far.getY(), far.getZ());
					BlockPos corner = origin.offset(dir);
					world.wall(corner.getX(), corner.getY(), corner.getZ());

					Map<BlockPos, Integer> found =
							ConduitRoute.junctionsFrom(near, world);
					assertEquals(1, found.size(),
							mount+" running "+dir+" does not climb its corner");
					assertTrue(found.containsKey(far));
				}
		}

		@Test
		@DisplayName("an outer corner works in every mount and every direction")
		void outerCornersEverywhere()
		{
			BlockPos origin = new BlockPos(0, 0, 0);
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					world = new Map3D();
					BlockPos near = origin.offset(dir.getOpposite());
					BlockPos wrapped = ConduitGeometry.outerCornerCell(origin, mount, dir);
					BlockPos onward = wrapped.offset(mount);
					BlockPos far = onward.offset(mount);
					world.junction(near.getX(), near.getY(), near.getZ());
					world.conduit(origin.getX(), origin.getY(), origin.getZ(), mount);
					EnumFacing wrapMount = ConduitGeometry.outerCornerMount(dir);
					world.conduit(wrapped.getX(), wrapped.getY(), wrapped.getZ(), wrapMount);
					world.conduit(onward.getX(), onward.getY(), onward.getZ(), wrapMount);
					world.junction(far.getX(), far.getY(), far.getZ());

					Map<BlockPos, Integer> found = ConduitRoute.junctionsFrom(near, world);
					assertEquals(1, found.size(),
							mount+" running "+dir+" does not follow its edge round");
					assertTrue(found.containsKey(far));
				}
		}

		@Test
		@DisplayName("a run that turns corners is still one edge between two boxes")
		void stillOneEdge()
		{
			//The property the whole feature's cost rests on, restated with corners in it: however
			//many times a run turns, the wire graph sees one connection between the two boxes at
			//its ends and the blocks in between are never nodes.
			world.floor(0);
			for(int y = 1; y <= 4; y++)
				world.wall(1, y, 0);
			world.junction(-3, 1, 0);
			world.conduit(-2, 1, 0, EnumFacing.DOWN);
			world.conduit(-1, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 1, 0, EnumFacing.DOWN);
			world.conduit(0, 2, 0, EnumFacing.EAST);
			world.conduit(0, 3, 0, EnumFacing.EAST);
			world.conduit(0, 4, 0, EnumFacing.EAST);
			world.junction(0, 5, 0);

			Map<BlockPos, Integer> found = from(-3, 1, 0);
			assertEquals(1, found.size());
			assertEquals(7, found.get(new BlockPos(0, 5, 0)),
					"the length should count every block of run, corner included");
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
			//Roughly thirteen probes per node now: two per step along the surface (one to slide
			//through whatever is in the way, one to look at what the step landed on), one per step
			//diagonally around an outer corner, and one out of the surface for the inner one. The
			//number is a sanity bound on "proportional to MAX_NODES" rather than an exact count --
			//the assertion that actually pins the behaviour is the one above.
			assertTrue(world.probes < ConduitRoute.MAX_NODES*20,
					"the walk examined far more than its own bound");
		}
	}

	@Nested
	@DisplayName("ground feeders")
	class Feeders
	{
		/**
		 * The shape the block exists for: a run comes down a wall, crosses a floor through a feeder,
		 * and carries on down the wall of the shaft below.
		 */
		private void wallRunThroughAFloor(EnumFacing above, EnumFacing below, EnumFacing.Axis axis)
		{
			world.junction(0, 5, 0);
			world.conduit(0, 4, 0, above);
			world.conduit(0, 3, 0, above);
			world.feeder(0, 2, 0, axis);
			world.conduit(0, 1, 0, below);
			world.conduit(0, 0, 0, below);
			world.junction(0, -1, 0);
		}

		@Test
		@DisplayName("a run crosses a feeder and joins the boxes either side of it")
		void runCrossesAFeeder()
		{
			wallRunThroughAFloor(EnumFacing.NORTH, EnumFacing.NORTH, EnumFacing.Axis.Y);
			Map<BlockPos, Integer> found = from(0, 5, 0);
			assertEquals(1, found.size(), "the feeder broke the run instead of passing it through");
			assertEquals(6, found.get(new BlockPos(0, -1, 0)),
					"the feeder should count toward the run's length -- it is a block the run goes "
							+"through, not a teleport");
		}

		@Test
		@DisplayName("crossing a feeder is the one place a run may change surface")
		void feederLicensesAPlaneChange()
		{
			//The same run, but the conduit below the floor is clipped to a different wall. Two
			//conduits meeting like this anywhere else do not join -- see surfaceChangeBreaksTheRun --
			//and that a feeder makes it legal is the whole reason it can replace a junction box
			//outdoors.
			wallRunThroughAFloor(EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.Axis.Y);
			assertEquals(1, from(0, 5, 0).size(),
					"a feeder should let a run come out on a different surface");
		}

		@Test
		@DisplayName("the far conduit still has to have the crossing in its own plane")
		void feederDoesNotLicenseAnyMounting()
		{
			//A feeder relaxes "same surface", not "on a surface the run can travel". A conduit lying
			//flat on the floor of the shaft runs horizontally, so a run arriving vertically cannot
			//continue into it any more than it could into one below a junction box.
			wallRunThroughAFloor(EnumFacing.NORTH, EnumFacing.DOWN, EnumFacing.Axis.Y);
			assertTrue(from(0, 5, 0).isEmpty(),
					"the run continued into a conduit whose surface it cannot travel");
		}

		@Test
		@DisplayName("a feeder laid the wrong way round is a wall")
		void wrongAxisStopsTheRun()
		{
			//The failure has to be visible rather than clever: TileEntityConduit draws the arm on
			//exactly this test, so a run stopped by a sideways feeder also looks stopped.
			wallRunThroughAFloor(EnumFacing.NORTH, EnumFacing.NORTH, EnumFacing.Axis.X);
			assertTrue(from(0, 5, 0).isEmpty(), "the run crossed a feeder against its grain");
		}

		@Test
		@DisplayName("a feeder beside a run is not part of it")
		void feederOffTheAxisIsIgnored()
		{
			//A floor run passing a feeder set into the floor next to it. The feeder conducts up and
			//down; the run is going east and west; they have nothing to do with each other.
			world.junction(2, 2, 0);
			world.conduit(1, 2, 0, EnumFacing.DOWN);
			world.feeder(0, 2, 0, EnumFacing.Axis.Y);
			world.conduit(-1, 2, 0, EnumFacing.DOWN);
			world.junction(-2, 2, 0);
			assertTrue(from(2, 2, 0).isEmpty());
		}

		@Test
		@DisplayName("a thick floor is several feeders and still one crossing")
		void stackedFeedersCross()
		{
			world.junction(0, 4, 0);
			world.conduit(0, 3, 0, EnumFacing.NORTH);
			for(int y = 0; y < 3; y++)
				world.feeder(0, 2-y, 0, EnumFacing.Axis.Y);
			world.conduit(0, -1, 0, EnumFacing.NORTH);
			world.junction(0, -2, 0);

			Map<BlockPos, Integer> found = from(0, 4, 0);
			assertEquals(1, found.size(), "a two-block floor should not break a run");
			assertEquals(6, found.get(new BlockPos(0, -2, 0)),
					"every block the run passes through counts toward its length");
		}

		@Test
		@DisplayName("a bored tunnel of feeders stops at the cap")
		void tooManyStackedFeedersRefuse()
		{
			//The cap is there so a feeder cannot be used as a free wire across a mountain. Well past
			//any floor, and finite.
			int deep = ConduitRoute.MAX_STACKED_FEEDERS+1;
			world.junction(0, 1, 0);
			world.conduit(0, 0, 0, EnumFacing.NORTH);
			for(int y = 1; y <= deep; y++)
				world.feeder(0, -y, 0, EnumFacing.Axis.Y);
			world.conduit(0, -deep-1, 0, EnumFacing.NORTH);
			world.junction(0, -deep-2, 0);
			assertTrue(from(0, 1, 0).isEmpty());
		}

		@Test
		@DisplayName("a box, a feeder and a box are joined with no conduit at all")
		void boxFeederBoxJoins()
		{
			//A floor with a box either side of it. The feeder is a length of run, so this is a run.
			world.junction(0, 1, 0);
			world.feeder(0, 0, 0, EnumFacing.Axis.Y);
			world.junction(0, -1, 0);

			Map<BlockPos, Integer> found = from(0, 1, 0);
			assertEquals(1, found.size());
			assertEquals(2, found.get(new BlockPos(0, -1, 0)));
		}

		@Test
		@DisplayName("two boxes touching each other are still not joined")
		void adjacentBoxesStayApart()
		{
			//The rule the case above must not have relaxed. A run ends at the first box it meets, and
			//a box is not a length of conduit.
			world.junction(0, 1, 0);
			world.junction(0, 0, 0);
			assertTrue(from(0, 1, 0).isEmpty());
		}
	}

	@Nested
	@DisplayName("waking the boxes on a run")
	class Waking
	{
		private java.util.Set<BlockPos> around(int x, int y, int z)
		{
			return ConduitRoute.junctionsAround(new BlockPos(x, y, z), world);
		}

		@Test
		@DisplayName("a conduit in the middle of a run finds the boxes at both ends")
		void conduitFindsBothEnds()
		{
			//This is what stops a run joined in the middle from being invisible to the graph: neither
			//box is anywhere near the block that closed it, so the block that closed it has to say so.
			world.junction(0, 0, 0);
			for(int z = 1; z <= 5; z++)
				world.conduit(0, 0, z, EnumFacing.DOWN);
			world.junction(0, 0, 6);

			assertEquals(2, around(0, 0, 3).size());
		}

		@Test
		@DisplayName("a feeder finds the boxes at both ends too")
		void feederFindsBothEnds()
		{
			world.junction(0, 5, 0);
			world.conduit(0, 4, 0, EnumFacing.NORTH);
			world.feeder(0, 3, 0, EnumFacing.Axis.Y);
			world.conduit(0, 2, 0, EnumFacing.NORTH);
			world.junction(0, 1, 0);

			assertEquals(2, around(0, 3, 0).size(), "dropping this feeder in joins two half-runs, and "
					+"neither box would otherwise ever hear about it");
		}

		@Test
		@DisplayName("it wakes the boxes on this run and not the ones past them")
		void wakingStopsAtTheFirstBox()
		{
			//A box beyond the two ends of this run holds a run of its own that this change cannot
			//have touched. Waking it would be a walk to discover nothing, and on a corridor with a
			//box at every corner it would be a walk per box per placement.
			world.junction(0, 0, 0);
			world.conduit(0, 0, 1, EnumFacing.DOWN);
			world.junction(0, 0, 2);
			world.conduit(0, 0, 3, EnumFacing.DOWN);
			world.junction(0, 0, 4);

			assertEquals(2, around(0, 0, 1).size());
			assertFalse(around(0, 0, 1).contains(new BlockPos(0, 0, 4)));
		}

		@Test
		@DisplayName("a feeder joined to nothing wakes nothing")
		void loneFeederWakesNothing()
		{
			world.feeder(0, 0, 0, EnumFacing.Axis.Y);
			assertTrue(around(0, 0, 0).isEmpty());
		}
	}
}
