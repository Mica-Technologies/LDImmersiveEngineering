/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry.Shape;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a conduit may run, and the box it fills while doing it.
 * <p>
 * Both halves are arithmetic on facings, so both can be checked without a game. The half that
 * matters most is {@link Bounds}: a hitbox and a model that disagree is glaring in a screenshot
 * and invisible in code, and the asset script generates its boxes from the same constants this
 * asserts against.
 */
class ConduitGeometryTest
{
	private static int mask(EnumFacing mount, EnumFacing... dirs)
	{
		int out = 0;
		for(EnumFacing dir : dirs)
		{
			int index = ConduitGeometry.armIndex(mount, dir);
			assertTrue(index >= 0, dir+" is not in "+mount+"'s plane");
			out |= 1 << index;
		}
		return out;
	}

	@Nested
	@DisplayName("the plane a conduit runs in")
	class Plane
	{
		@Test
		@DisplayName("every mount has exactly four directions")
		void fourPerMount()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				assertEquals(ConduitGeometry.ARMS, ConduitGeometry.inPlane(mount).length);
		}

		@Test
		@DisplayName("the plane excludes the mounting axis, both ways")
		void planeExcludesTheMountAxis()
		{
			//Not just the mount itself: the direction straight out from the surface is excluded
			//too, because leaving the surface is a junction box's job.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				assertFalse(ConduitGeometry.isInPlane(mount, mount));
				assertFalse(ConduitGeometry.isInPlane(mount, mount.getOpposite()));
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					assertNotEquals(mount.getAxis(), dir.getAxis());
			}
		}

		@Test
		@DisplayName("the four are distinct and are two opposite pairs")
		void planeIsTwoPairs()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				Set<EnumFacing> seen = new HashSet<>();
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					assertTrue(seen.add(dir), mount+" lists "+dir+" twice");
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					assertTrue(seen.contains(dir.getOpposite()),
							mount+"'s plane has "+dir+" without its opposite");
			}
		}

		@Test
		@DisplayName("arm indices are 0..3 and unique within a plane")
		void armIndicesAreUnique()
		{
			//These indices are what the connection mask's bits mean, and the mask is saved.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				Set<Integer> seen = new HashSet<>();
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					int index = ConduitGeometry.armIndex(mount, dir);
					assertTrue(index >= 0&&index < ConduitGeometry.ARMS);
					assertTrue(seen.add(index));
				}
			}
		}

		@Test
		@DisplayName("a direction out of the plane has no index")
		void outOfPlaneHasNoIndex()
		{
			assertEquals(-1, ConduitGeometry.armIndex(EnumFacing.DOWN, EnumFacing.UP));
			assertEquals(-1, ConduitGeometry.armIndex(EnumFacing.DOWN, EnumFacing.DOWN));
			assertEquals(-1, ConduitGeometry.armIndex(EnumFacing.DOWN, null));
		}
	}

	@Nested
	@DisplayName("what joins to what")
	class Joining
	{
		@Test
		@DisplayName("two conduits on the same surface join along it")
		void sameSurfaceJoins()
		{
			assertTrue(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.DOWN, EnumFacing.NORTH));
			assertTrue(ConduitGeometry.connects(EnumFacing.EAST, EnumFacing.EAST, EnumFacing.UP));
		}

		@Test
		@DisplayName("two conduits on different surfaces do not join")
		void differentSurfacesDoNotJoin()
		{
			//The deliberate limit: a run stays in one plane and a plane change goes through a
			//junction box. Saying so plainly beats a run that looks continuous and is not.
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.NORTH));
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("nothing joins through its own mounting axis")
		void noJoinAlongTheMountAxis()
		{
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.DOWN, EnumFacing.DOWN));
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.DOWN, EnumFacing.UP));
		}

		@Test
		@DisplayName("nothing joins to nothing")
		void nullsDoNotJoin()
		{
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, null, EnumFacing.NORTH));
			assertFalse(ConduitGeometry.connects(EnumFacing.DOWN, EnumFacing.DOWN, null));
		}

		@Test
		@DisplayName("joining is symmetric")
		void joiningIsSymmetric()
		{
			//If A thinks it joins B and B disagrees, the run draws with an arm into a blank face.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing other : EnumFacing.VALUES)
					for(EnumFacing towards : EnumFacing.VALUES)
						assertEquals(ConduitGeometry.connects(mount, other, towards),
								ConduitGeometry.connects(other, mount, towards.getOpposite()),
								mount+" to "+other+" via "+towards+" is not symmetric");
		}
	}

	@Nested
	@DisplayName("what a junction box thinks is touching it")
	class JoiningABox
	{
		@Test
		@DisplayName("a conduit joins the box if the step is in the conduit's own plane")
		void conduitInPlaneJoins()
		{
			//A conduit mounted on a north wall runs up, down, east and west -- so a box above or
			//below it, or to either side, all join. This is the exact case from the playtest report:
			//a box on top of or underneath a run looked disconnected even though the run reached it.
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.UP, EnumFacing.NORTH, null));
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.DOWN, EnumFacing.NORTH, null));
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.EAST, EnumFacing.NORTH, null));
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.WEST, EnumFacing.NORTH, null));
		}

		@Test
		@DisplayName("a conduit does not join across its own mounting axis")
		void conduitOffPlaneDoesNotJoin()
		{
			//A box sitting off the wall a conduit is clipped to -- the direction a plane change would
			//need a second box for -- is not a join, the same as two conduits meeting there are not.
			assertFalse(ConduitGeometry.joinsJunctionBox(EnumFacing.NORTH, EnumFacing.NORTH, null));
			assertFalse(ConduitGeometry.joinsJunctionBox(EnumFacing.SOUTH, EnumFacing.NORTH, null));
		}

		@Test
		@DisplayName("a feeder joins the box along its own axis and no other")
		void feederOnAxisJoins()
		{
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.UP, null, EnumFacing.Axis.Y));
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.DOWN, null, EnumFacing.Axis.Y));
			assertFalse(ConduitGeometry.joinsJunctionBox(EnumFacing.NORTH, null, EnumFacing.Axis.Y));
		}

		@Test
		@DisplayName("nothing, or another box, does not join")
		void nothingJoins()
		{
			//Covers both a bare neighbour and a second box touching this one: a run ends at the
			//first box it meets, and a box is not a length of conduit.
			for(EnumFacing dir : EnumFacing.VALUES)
				assertFalse(ConduitGeometry.joinsJunctionBox(dir, null, null));
		}

		@Test
		@DisplayName("the wall run that was reported: a box at the end of it joins westward")
		void theReportedWallRun()
		{
			//	=================================
			//	Straight from a dev-client session.
			//	=================================
			//
			// A run along a wall facing south -- so every length of it is clipped to its own north
			// face -- ending in a box one block further east. The box looks west at a conduit
			// mounted north, and has to say yes, in the same breath as it says yes to the box
			// directly above the run one block along.
			//
			// Both were checked in-game and this is not where the fault was. The rule is here so
			// that the next person reading the two of them together can see that at a glance
			// rather than working it out again: what was wrong was the *shape* the yes produced,
			// which JunctionBoxMount below and ConduitAssetsTest.RunStubs now hold.
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.WEST, EnumFacing.NORTH, null),
					"a box at the east end of a north-mounted wall run does not see the run");
			assertTrue(ConduitGeometry.joinsJunctionBox(EnumFacing.DOWN, EnumFacing.NORTH, null),
					"a box directly above a north-mounted wall run does not see the run");
		}
	}

	@Nested
	@DisplayName("which surface a junction box sits against")
	class JunctionBoxMount
	{
		private EnumFacing[] nothing()
		{
			return new EnumFacing[EnumFacing.VALUES.length];
		}

		@Test
		@DisplayName("a box with no runs sits on the floor of its cell")
		void bareBoxIsFloorMounted()
		{
			//Which is what a box has always looked like, so an untouched one in an old world does
			//not move the first time somebody walks past it.
			assertEquals(EnumFacing.DOWN, ConduitGeometry.junctionBoxMount(nothing()));
		}

		@Test
		@DisplayName("a box takes the plane of the run that reaches it")
		void takesTheRunsPlane()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//
			// A conduit occupies the first three pixels off the face it is clipped to and nothing
			// else. A box in a different plane from its run is therefore not merely offset from it:
			// it is in a part of the block the run never enters, and no amount of growing the box
			// toward the boundary can make the two meet. That was the defect -- a wall run ended
			// flush at a boundary with the box's housing three pixels clear of the wall behind it.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing side : ConduitGeometry.inPlane(mount))
				{
					EnumFacing[] around = nothing();
					around[side.ordinal()] = mount;
					assertEquals(mount, ConduitGeometry.junctionBoxMount(around),
							"a box with a "+mount.getName()+"-mounted run to its "+side.getName()
									+" does not sit in that run's plane");
				}
		}

		@Test
		@DisplayName("a box where two planes meet picks the same one every time")
		void planeChangeIsStable()
		{
			//A box is *for* changing planes, so it will routinely be reached by two runs it cannot
			//both sit in. Being wrong for one of them is unavoidable; being wrong differently on
			//two different days is not.
			//A floor run arriving from the west and a wall run arriving from above: both join, and
			//the box can only be in one of the two planes.
			EnumFacing[] around = nothing();
			around[EnumFacing.WEST.ordinal()] = EnumFacing.DOWN;
			around[EnumFacing.UP.ordinal()] = EnumFacing.NORTH;
			assertEquals(EnumFacing.DOWN, ConduitGeometry.junctionBoxMount(around));
			//And the answer does not depend on which side the runs happen to be on.
			EnumFacing[] mirrored = nothing();
			mirrored[EnumFacing.NORTH.ordinal()] = EnumFacing.DOWN;
			mirrored[EnumFacing.DOWN.ordinal()] = EnumFacing.NORTH;
			assertEquals(EnumFacing.DOWN, ConduitGeometry.junctionBoxMount(mirrored));
		}

		@Test
		@DisplayName("a run that could not be in that plane is ignored")
		void offPlaneNeighbourIsIgnored()
		{
			//A conduit is never joined across its own mounting axis -- see joinsJunctionBox -- so a
			//mount recorded on such a side is not a run reaching this box and must not move it.
			EnumFacing[] around = nothing();
			around[EnumFacing.NORTH.ordinal()] = EnumFacing.NORTH;
			assertEquals(EnumFacing.DOWN, ConduitGeometry.junctionBoxMount(around));
		}
	}

	@Nested
	@DisplayName("shapes")
	class Shapes
	{
		@Test
		@DisplayName("nothing joined is bare")
		void bare()
		{
			assertEquals(Shape.BARE, ConduitGeometry.shapeOf(EnumFacing.DOWN, 0));
		}

		@Test
		@DisplayName("one arm is the end of a run")
		void end()
		{
			assertEquals(Shape.END, ConduitGeometry.shapeOf(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH)));
		}

		@Test
		@DisplayName("opposite arms make a straight")
		void straight()
		{
			assertEquals(Shape.STRAIGHT, ConduitGeometry.shapeOf(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH)));
			assertEquals(Shape.STRAIGHT, ConduitGeometry.shapeOf(EnumFacing.EAST,
					mask(EnumFacing.EAST, EnumFacing.UP, EnumFacing.DOWN)));
		}

		@Test
		@DisplayName("adjacent arms make a corner")
		void corner()
		{
			//The shape the whole look is built around, so it gets its own case rather than being
			//"two arms" alongside a straight.
			assertEquals(Shape.CORNER, ConduitGeometry.shapeOf(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.EAST)));
			assertEquals(Shape.CORNER, ConduitGeometry.shapeOf(EnumFacing.EAST,
					mask(EnumFacing.EAST, EnumFacing.UP, EnumFacing.NORTH)));
		}

		@Test
		@DisplayName("three and four arms are a tee and a cross")
		void teeAndCross()
		{
			assertEquals(Shape.TEE, ConduitGeometry.shapeOf(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST)));
			assertEquals(Shape.CROSS, ConduitGeometry.shapeOf(EnumFacing.DOWN, 0xF));
		}

		@Test
		@DisplayName("bits above the four arms are ignored")
		void extraBitsIgnored()
		{
			//The mask comes off disk. A stray high bit must not turn a straight into a cross.
			assertEquals(Shape.STRAIGHT, ConduitGeometry.shapeOf(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH)|0xFFF0));
		}

		@Test
		@DisplayName("every mask on every mount classifies to something")
		void everyMaskClassifies()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				for(int m = 0; m <= 0xF; m++)
					assertNotNull(ConduitGeometry.shapeOf(mount, m));
		}
	}

	@Nested
	@DisplayName("bounds")
	class Bounds
	{
		private static final float U = 1/16f;

		@Test
		@DisplayName("a bare conduit hugs its own surface")
		void bareHugsTheSurface()
		{
			//Mounted DOWN, so it lies in the bottom three sixteenths and nowhere near the middle
			//of the block.
			float[] box = ConduitBounds.of(EnumFacing.DOWN, 0);
			assertEquals(0f, box[1], 1e-6);
			assertEquals(ConduitBounds.DEPTH*U, box[4], 1e-6);
			assertEquals((8-ConduitBounds.HALF_WIDTH)*U, box[0], 1e-6);
			assertEquals((8+ConduitBounds.HALF_WIDTH)*U, box[3], 1e-6);
		}

		@Test
		@DisplayName("a conduit on the ceiling hugs the ceiling")
		void positiveMountSitsAtTheFarSide()
		{
			float[] box = ConduitBounds.of(EnumFacing.UP, 0);
			assertEquals((16-ConduitBounds.DEPTH)*U, box[1], 1e-6);
			assertEquals(1f, box[4], 1e-6);
		}

		@Test
		@DisplayName("an arm reaches the block edge so two conduits meet flush")
		void armsReachTheEdge()
		{
			//If an arm stopped short, every joint in every run would show a gap.
			float[] box = ConduitBounds.of(EnumFacing.DOWN, mask(EnumFacing.DOWN, EnumFacing.NORTH));
			assertEquals(0f, box[2], 1e-6);
			float[] south = ConduitBounds.of(EnumFacing.DOWN, mask(EnumFacing.DOWN, EnumFacing.SOUTH));
			assertEquals(1f, south[5], 1e-6);
		}

		@Test
		@DisplayName("a straight run spans the block in one axis only")
		void straightSpansOneAxis()
		{
			float[] box = ConduitBounds.of(EnumFacing.DOWN,
					mask(EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH));
			assertEquals(0f, box[2], 1e-6);
			assertEquals(1f, box[5], 1e-6);
			assertEquals((8-ConduitBounds.HALF_WIDTH)*U, box[0], 1e-6, "it also spread sideways");
			assertEquals((8+ConduitBounds.HALF_WIDTH)*U, box[3], 1e-6);
			assertEquals(ConduitBounds.DEPTH*U, box[4], 1e-6, "it also grew off the surface");
		}

		@Test
		@DisplayName("a cross spans the whole face and stays thin")
		void crossSpansTheFace()
		{
			float[] box = ConduitBounds.of(EnumFacing.DOWN, 0xF);
			assertEquals(0f, box[0], 1e-6);
			assertEquals(0f, box[2], 1e-6);
			assertEquals(1f, box[3], 1e-6);
			assertEquals(1f, box[5], 1e-6);
			assertEquals(ConduitBounds.DEPTH*U, box[4], 1e-6);
		}

		@Test
		@DisplayName("every mount and mask gives a box inside the block, min before max")
		void everyBoxIsSane()
		{
			//The cheap sweep that catches an axis mixed up somewhere: a box with min above max is
			//invisible and unclickable, which reads as the block simply not being there.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(int m = 0; m <= 0xF; m++)
				{
					float[] box = ConduitBounds.of(mount, m);
					assertEquals(6, box.length);
					for(int axis = 0; axis < 3; axis++)
					{
						assertTrue(box[axis] >= 0f&&box[axis] <= 1f, mount+"/"+m+" min out of block");
						assertTrue(box[axis+3] >= 0f&&box[axis+3] <= 1f, mount+"/"+m+" max out of block");
						assertTrue(box[axis+3] > box[axis], mount+"/"+m+" is inside out on axis "+axis);
					}
				}
		}

		@Test
		@DisplayName("an arm never grows the box along the mounting axis")
		void armsStayFlat()
		{
			//The one mistake that would make conduit read as pipe: an arm that thickened the run
			//off its surface instead of extending it along it.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				float[] bare = ConduitBounds.of(mount, 0);
				for(int m = 0; m <= 0xF; m++)
				{
					float[] box = ConduitBounds.of(mount, m);
					int axis = mount.getAxis().ordinal();
					assertEquals(bare[axis], box[axis], 1e-6, mount+"/"+m+" moved off its surface");
					assertEquals(bare[axis+3], box[axis+3], 1e-6, mount+"/"+m+" thickened");
				}
			}
		}
	}
}
