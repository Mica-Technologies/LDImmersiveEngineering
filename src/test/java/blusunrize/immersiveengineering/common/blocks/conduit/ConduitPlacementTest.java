/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which surface a freshly placed conduit clips to.
 * <p>
 * Worth testing thoroughly because the failure it exists to prevent is invisible: a conduit mounted
 * to the wrong surface looks exactly like one mounted to the right surface until you notice the run
 * is not carrying anything, and the fix -- break it and click a different face -- is not guessable.
 * That is the bug a playtester reported as "the conduit is cumbersome to assemble".
 * <p>
 * The world is drawn by hand. {@link ConduitPlacement} takes a {@link ConduitPlacement.Surroundings}
 * for exactly this reason.
 */
class ConduitPlacementTest
{
	/** A building drawn a block at a time. */
	private static class Map3D implements ConduitPlacement.Surroundings
	{
		private final Map<BlockPos, EnumFacing> conduits = new HashMap<>();
		private final Set<BlockPos> junctions = new HashSet<>();
		private final Map<BlockPos, EnumFacing.Axis> feeders = new HashMap<>();
		private final Set<BlockPos> solid = new HashSet<>();

		Map3D conduit(int x, int y, int z, EnumFacing mount)
		{
			conduits.put(new BlockPos(x, y, z), mount);
			return this;
		}

		Map3D junction(int x, int y, int z)
		{
			junctions.add(new BlockPos(x, y, z));
			return this;
		}

		/** A ground feeder. Unlike the other conduit hardware it is a whole solid cube. */
		Map3D feeder(int x, int y, int z, EnumFacing.Axis axis)
		{
			feeders.put(new BlockPos(x, y, z), axis);
			solid.add(new BlockPos(x, y, z));
			return this;
		}

		/** A block of building. Conduits are deliberately never solid, exactly as BlockConduit says. */
		Map3D wall(int x, int y, int z)
		{
			solid.add(new BlockPos(x, y, z));
			return this;
		}

		/** A whole floor at y, so a run has something to lie on. */
		Map3D floor(int y)
		{
			for(int x = -4; x <= 4; x++)
				for(int z = -4; z <= 4; z++)
					solid.add(new BlockPos(x, y, z));
			return this;
		}

		@Override
		public EnumFacing conduitMountAt(BlockPos pos)
		{
			return conduits.get(pos);
		}

		@Override
		public boolean isJunctionAt(BlockPos pos)
		{
			return junctions.contains(pos);
		}

		@Override
		public EnumFacing.Axis feederAxisAt(BlockPos pos)
		{
			return feeders.get(pos);
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

	private EnumFacing place(int x, int y, int z, EnumFacing clickedSide)
	{
		return ConduitPlacement.mountFor(new BlockPos(x, y, z), clickedSide, world);
	}

	@Nested
	@DisplayName("clicking bare wall")
	class BareSurface
	{
		@Test
		@DisplayName("clips to the face that was clicked, as it always did")
		void clipsToClickedFace()
		{
			world.floor(0);
			//Clicking the top of the floor puts the conduit above it, clipped downwards.
			assertEquals(EnumFacing.DOWN, place(0, 1, 0, EnumFacing.UP));
		}

		@Test
		@DisplayName("works the same on a wall and a ceiling")
		void everyOrientation()
		{
			world.wall(0, 0, 0);
			assertEquals(EnumFacing.NORTH, place(0, 0, 1, EnumFacing.SOUTH));
			assertEquals(EnumFacing.UP, place(0, -1, 0, EnumFacing.DOWN));
			assertEquals(EnumFacing.WEST, place(1, 0, 0, EnumFacing.EAST));
		}

		@Test
		@DisplayName("does not demand a solid surface, so nothing that used to place stops placing")
		void doesNotRegress()
		{
			//No wall recorded anywhere: the fallback still answers, because refusing here would be a
			//new restriction rather than a fix.
			assertEquals(EnumFacing.DOWN, place(0, 1, 0, EnumFacing.UP));
		}
	}

	@Nested
	@DisplayName("continuing a run")
	class Continuing
	{
		@Test
		@DisplayName("inherits the surface of the conduit that was clicked")
		void inheritsNeighbourSurface()
		{
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN);
			//Clicking the north face of that conduit, which puts the new length one step north of it.
			//The old rule answered SOUTH -- mounted to the conduit -- which lands in a plane that
			//cannot reach it, and the run silently stopped.
			assertEquals(EnumFacing.DOWN, place(0, 1, -1, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("the inherited surface actually connects the two")
		void inheritedRunConnects()
		{
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN);
			EnumFacing mount = place(0, 1, -1, EnumFacing.NORTH);
			//The point of the whole change: what it returns has to satisfy the rule the run walker
			//and the renderer both use, or it is just a different flavour of dead stub. The step back
			//to the conduit that was clicked is south.
			assertEquals(true, ConduitGeometry.connects(mount, EnumFacing.DOWN, EnumFacing.SOUTH));
		}

		@Test
		@DisplayName("works along a wall as well as a floor")
		void wallRun()
		{
			world.wall(0, 1, 0).wall(0, 2, 0).conduit(0, 1, 1, EnumFacing.NORTH);
			assertEquals(EnumFacing.NORTH, place(0, 2, 1, EnumFacing.UP));
		}

		@Test
		@DisplayName("a step off the surface in the open is not a continuation")
		void stepOffSurfaceFallsBack()
		{
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN);
			//Clicking the exposed top of a floor conduit in the middle of a room. There is no wall
			//to climb, so there is no corner to turn and the old rule answers.
			assertEquals(EnumFacing.DOWN, place(0, 2, 0, EnumFacing.UP));
		}

		@Test
		@DisplayName("does not inherit a surface that is not there")
		void refusesToInheritIntoThinAir()
		{
			//A floor exactly one block wide, with a conduit on it. Continuing north would inherit
			//DOWN with nothing underneath, so the rule declines and the old answer stands.
			world.wall(0, 0, 0).conduit(0, 1, 0, EnumFacing.DOWN);
			assertEquals(EnumFacing.SOUTH, place(0, 1, -1, EnumFacing.NORTH));
		}
	}

	@Nested
	@DisplayName("turning a corner off the end of a run")
	class TurningACorner
	{
		@Test
		@DisplayName("clicking the top of the last floor length climbs the wall it has reached")
		void climbsTheWall()
		{
			//	=================================
			//	The gesture the corner rules needed.
			//	=================================
			//A floor run has reached a wall, and the obvious next click is the exposed top of the
			//last length. That used to answer DOWN, laying the new piece flat on top of a conduit --
			//which is not a surface, so the piece hung there joined to nothing. Clicking the wall
			//itself always worked and still does; this makes the other way of saying it work too.
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN).wall(1, 1, 0).wall(1, 2, 0);
			assertEquals(EnumFacing.EAST, place(0, 2, 0, EnumFacing.UP));
		}

		@Test
		@DisplayName("the surface it picks actually turns the corner")
		void theCornerIsReal()
		{
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN).wall(1, 1, 0).wall(1, 2, 0);
			EnumFacing mount = place(0, 2, 0, EnumFacing.UP);
			//The step from the new piece back down to the run it is continuing.
			assertTrue(ConduitGeometry.joinsInnerCorner(mount, EnumFacing.DOWN, EnumFacing.DOWN),
					"the piece it placed does not join the run it was placed off");
			assertEquals(new BlockPos(1, 1, 0),
					ConduitGeometry.innerCornerSupport(new BlockPos(0, 2, 0), mount,
							EnumFacing.DOWN, EnumFacing.DOWN),
					"the corner it turns is not around the wall that was there");
		}

		@Test
		@DisplayName("it needs the wall beside the run as well as beside the new piece")
		void needsBothHalvesOfTheWall()
		{
			//Only the upper block of the wall is there -- an overhang. The riser has nothing to hug
			//on its way up, so this is not the gesture and the old answer stands.
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN).wall(1, 2, 0);
			assertEquals(EnumFacing.DOWN, place(0, 2, 0, EnumFacing.UP));
		}

		@Test
		@DisplayName("a feeder is not a corner to turn")
		void feederInTheCornerIsNotAWall()
		{
			//A feeder is solid, so it passes the "is there a block there" test on its own. A run
			//reaching one goes through it rather than climbing it, so this must decline.
			world.floor(0).conduit(0, 1, 0, EnumFacing.DOWN).feeder(1, 1, 0, EnumFacing.Axis.X)
					.wall(1, 2, 0);
			assertEquals(EnumFacing.DOWN, place(0, 2, 0, EnumFacing.UP));
		}

		@Test
		@DisplayName("it works off a wall run as well, which is the same corner the other way up")
		void offAWallRun()
		{
			//A run down the inside of a north wall, reaching the floor. Clicking the exposed south
			//face of the bottom length should lay the next piece on that floor rather than hang it
			//in the air clipped to the conduit.
			world.floor(0).wall(0, 1, 0).conduit(0, 1, 1, EnumFacing.NORTH);
			assertEquals(EnumFacing.DOWN, place(0, 1, 2, EnumFacing.SOUTH));
		}

		@Test
		@DisplayName("clicking the far side of an edge still just clips to the wall")
		void wrappingAnEdgeNeedsNoNewRule()
		{
			//The outer corner needs nothing here at all: the block round the edge is bare wall, and
			//the plain rule has always clipped a conduit to the face it was put against. Asserted
			//so that a later tidy-up of these branches cannot quietly take it away.
			world.wall(0, 0, 0).conduit(0, 1, 0, EnumFacing.DOWN);
			assertEquals(EnumFacing.WEST, place(1, 0, 0, EnumFacing.EAST));
		}
	}

	@Nested
	@DisplayName("starting a run off a junction box")
	class OffABox
	{
		@Test
		@DisplayName("finds the floor rather than mounting to the box")
		void findsTheFloor()
		{
			world.floor(0).junction(0, 1, 0);
			//The old rule answered SOUTH -- clipped to the box -- and a conduit clipped to a box sits
			//in the one plane that cannot reach it. The first length off a box always disappointed.
			assertEquals(EnumFacing.DOWN, place(0, 1, -1, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("the surface it picks reaches back to the box")
		void reachesTheBox()
		{
			world.floor(0).junction(0, 1, 0);
			EnumFacing mount = place(0, 1, -1, EnumFacing.NORTH);
			//ConduitRoute steps from the box to the conduit and requires the step to lie in the
			//conduit's plane. If that fails, the box never sees the run at all.
			assertEquals(true, ConduitGeometry.isInPlane(mount, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("takes a wall when there is no floor")
		void takesAWall()
		{
			//Box in mid-air with a wall to the west of where the conduit is going.
			world.wall(-1, 5, -1).junction(0, 5, 0);
			assertEquals(EnumFacing.WEST, place(0, 5, -1, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("falls back to the box when there is no surface at all")
		void nothingToClipTo()
		{
			world.junction(0, 5, 0);
			assertEquals(EnumFacing.SOUTH, place(0, 5, -1, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("never picks a surface lying along the step away from the box")
		void onlyInPlaneSurfaces()
		{
			//The only solid block is straight ahead of the new length, along the very axis it is
			//travelling. Clipping to that would be a conduit facing the way it runs, which is not a
			//surface at all -- so the rule declines it and the old answer stands.
			world.junction(0, 5, 0).wall(0, 5, -2);
			assertEquals(EnumFacing.SOUTH, place(0, 5, -1, EnumFacing.NORTH));
		}
	}

	@Nested
	@DisplayName("a conduit is not a surface")
	class ConduitsAreNotWalls
	{
		@Test
		@DisplayName("the box rule will not clip a conduit to another conduit")
		void boxRuleSkipsConduits()
		{
			//A conduit below where the new one is going, but no actual floor. BlockConduit reports
			//every side non-solid, and the drawn world matches, so there is nothing to mount to.
			world.junction(0, 5, 0).conduit(0, 4, -1, EnumFacing.DOWN);
			assertEquals(EnumFacing.SOUTH, place(0, 5, -1, EnumFacing.NORTH));
		}
	}

	@Nested
	@DisplayName("starting a run off a ground feeder")
	class OffAFeeder
	{
		@Test
		@DisplayName("clicking the top of a feeder in a floor clips the conduit to a wall, not to the feeder")
		void feederEndFaceFindsAWall()
		{
			//	=================================
			//	The one this branch exists for.
			//	=================================
			//
			// A feeder set into a floor at the foot of a north wall, clicked on its top face. The
			// naive answer -- and the one the junction box rule would give, since it tries floors
			// first -- is DOWN: lay the new length flat on the feeder's own lid. That length runs
			// horizontally, so the step down onto the feeder is off its surface and it cannot reach
			// the feeder at all. It would look placed and carry nothing.
			world.floor(0).feeder(0, 0, 0, EnumFacing.Axis.Y).wall(0, 1, -1);
			assertEquals(EnumFacing.NORTH, place(0, 1, 0, EnumFacing.UP),
					"the conduit was laid flat on the feeder, in the one plane that cannot reach it");
		}

		@Test
		@DisplayName("the same from below, so a run continues down the shaft")
		void feederBottomFaceFindsAWall()
		{
			world.feeder(0, 0, 0, EnumFacing.Axis.Y).wall(0, -1, -1);
			assertEquals(EnumFacing.NORTH, place(0, -1, 0, EnumFacing.DOWN));
		}

		@Test
		@DisplayName("a feeder through a wall is no different, only turned")
		void horizontalFeederFindsAFloor()
		{
			//Axis Z, clicked on its north face. The new length has to be on a surface whose plane
			//contains Z, and the floor beneath it is the first one the preference order offers.
			world.feeder(0, 0, 0, EnumFacing.Axis.Z).wall(0, -1, -1);
			assertEquals(EnumFacing.DOWN, place(0, 0, -1, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("clicking a feeder's side is not this gesture at all")
		void feederSideFallsThroughToTheWallRule()
		{
			//Nothing to the side of a vertical feeder is on the pass-through path, so there is no run
			//to continue and the plain rule stands: clip to the face that was clicked. Getting this
			//wrong would silently reorient conduit placed against a feeder that is merely in the way.
			world.floor(0).feeder(0, 1, 0, EnumFacing.Axis.Y).wall(0, 0, -1);
			assertEquals(EnumFacing.SOUTH, place(0, 1, -1, EnumFacing.NORTH),
					"clicking the side of a feeder was treated as continuing a run through it");
		}

		@Test
		@DisplayName("with no surface anywhere it falls back to the clicked face")
		void noWallLeavesTheOldAnswer()
		{
			//A feeder floating in the air. There is nothing to clip to, so the rule declines rather
			//than inventing a mounting -- exactly as the box rule does.
			world.feeder(0, 0, 0, EnumFacing.Axis.Y);
			assertEquals(EnumFacing.DOWN, place(0, 1, 0, EnumFacing.UP));
		}
	}
}
