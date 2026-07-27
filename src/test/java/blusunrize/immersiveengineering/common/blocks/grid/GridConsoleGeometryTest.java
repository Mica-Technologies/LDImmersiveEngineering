/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Grid Management Console's 2x2 detection geometry.
 * <p>
 * Worth testing in isolation because the failure mode is invisible: an off-by-one here
 * means the structure silently refuses to form when hammered, with no message and nothing
 * in the log to work from.
 */
class GridConsoleGeometryTest
{
	private static Set<BlockPos> setOf(BlockPos... positions)
	{
		return new HashSet<>(Arrays.asList(positions));
	}

	@Nested
	@DisplayName("dimensions")
	class Dimensions
	{
		@Test
		@DisplayName("the console is 2 wide, 1 deep and 2 tall")
		void size()
		{
			assertEquals(2, GridConsoleGeometry.HEIGHT);
			assertEquals(1, GridConsoleGeometry.DEPTH);
			assertEquals(2, GridConsoleGeometry.WIDTH);
			assertArrayEquals(new int[]{2, 1, 2}, GridConsoleGeometry.SIZE,
					"TileEntityMultiblockPart expects H, L, W in that order");
		}

		@Test
		@DisplayName("structure indices are row-major and unique")
		void structureIndicesAreUnique()
		{
			Set<Integer> seen = new HashSet<>();
			for(int h = 0; h < GridConsoleGeometry.HEIGHT; h++)
				for(int w = 0; w < GridConsoleGeometry.WIDTH; w++)
					assertTrue(seen.add(GridConsoleGeometry.structureIndex(h, w)),
							"duplicate index for h="+h+", w="+w);
			assertEquals(4, seen.size());
			assertEquals(0, GridConsoleGeometry.structureIndex(0, 0), "the master is index 0");
		}

		@Test
		@DisplayName("only the upper row carries the screen")
		void upperRowIsTheScreen()
		{
			assertFalse(GridConsoleGeometry.isUpperRow(GridConsoleGeometry.structureIndex(0, 0)));
			assertFalse(GridConsoleGeometry.isUpperRow(GridConsoleGeometry.structureIndex(0, 1)));
			assertTrue(GridConsoleGeometry.isUpperRow(GridConsoleGeometry.structureIndex(1, 0)));
			assertTrue(GridConsoleGeometry.isUpperRow(GridConsoleGeometry.structureIndex(1, 1)));
		}
	}

	@Nested
	@DisplayName("footprint")
	class Footprint
	{
		@Test
		@DisplayName("a console occupies two columns and two rows")
		void cellsFormA2x2()
		{
			Set<BlockPos> cells = setOf(GridConsoleGeometry.cells(
					new BlockPos(10, 64, 10), EnumFacing.EAST));
			assertEquals(4, cells.size(), "four distinct blocks");
			assertEquals(setOf(
					new BlockPos(10, 64, 10), new BlockPos(11, 64, 10),
					new BlockPos(10, 65, 10), new BlockPos(11, 65, 10)), cells);
		}

		@Test
		@DisplayName("the footprint is one block deep for every orientation")
		void footprintIsFlat()
		{
			for(EnumFacing right : EnumFacing.HORIZONTALS)
			{
				BlockPos origin = new BlockPos(0, 64, 0);
				EnumFacing depth = right.rotateY();
				for(BlockPos cell : GridConsoleGeometry.cells(origin, right))
				{
					int along = depth.getXOffset()*(cell.getX()-origin.getX())
							+depth.getZOffset()*(cell.getZ()-origin.getZ());
					assertEquals(0, along,
							"console is 1 deep, but "+cell+" is offset for right="+right);
				}
			}
		}

		@Test
		@DisplayName("the footprint spans exactly two heights")
		void footprintSpansTwoHeights()
		{
			Set<Integer> heights = new HashSet<>();
			for(BlockPos cell : GridConsoleGeometry.cells(new BlockPos(0, 64, 0), EnumFacing.EAST))
				heights.add(cell.getY());
			assertEquals(setOf2(64, 65), heights);
		}

		private Set<Integer> setOf2(Integer... values)
		{
			return new HashSet<>(Arrays.asList(values));
		}
	}

	@Nested
	@DisplayName("origin detection")
	class OriginDetection
	{
		@Test
		@DisplayName("four candidate origins are offered")
		void fourCandidates()
		{
			assertEquals(4, GridConsoleGeometry.candidateOrigins(
					new BlockPos(0, 64, 0), EnumFacing.EAST).length);
		}

		/**
		 * The property that actually matters: whichever of the four blocks the player
		 * hammers, the real origin has to be among the candidates that get tried.
		 */
		@Test
		@DisplayName("hammering any of the four blocks finds the true origin")
		void anyClickedBlockFindsTheOrigin()
		{
			for(EnumFacing right : EnumFacing.HORIZONTALS)
			{
				BlockPos origin = new BlockPos(-3, 70, 8);
				for(BlockPos clicked : GridConsoleGeometry.cells(origin, right))
				{
					Set<BlockPos> candidates = setOf(
							GridConsoleGeometry.candidateOrigins(clicked, right));
					assertTrue(candidates.contains(origin),
							"clicking "+clicked+" (right="+right+") must offer origin "+origin
									+", got "+candidates);
				}
			}
		}

		@Test
		@DisplayName("candidates are distinct, so no origin is tested twice")
		void candidatesAreDistinct()
		{
			for(EnumFacing right : EnumFacing.HORIZONTALS)
				assertEquals(4, setOf(GridConsoleGeometry.candidateOrigins(
						new BlockPos(0, 64, 0), right)).size(), "right="+right);
		}

		@Test
		@DisplayName("every candidate's footprint contains the clicked block")
		void candidateFootprintsContainTheClick()
		{
			BlockPos clicked = new BlockPos(5, 64, -2);
			for(EnumFacing right : EnumFacing.HORIZONTALS)
				for(BlockPos candidate : GridConsoleGeometry.candidateOrigins(clicked, right))
					assertTrue(setOf(GridConsoleGeometry.cells(candidate, right)).contains(clicked),
							"candidate "+candidate+" (right="+right+") does not contain "+clicked);
		}

		@Test
		@DisplayName("an origin is never above the clicked block")
		void candidatesNeverReachAbove()
		{
			BlockPos clicked = new BlockPos(0, 64, 0);
			for(BlockPos candidate : GridConsoleGeometry.candidateOrigins(clicked, EnumFacing.EAST))
			{
				int dy = candidate.getY()-clicked.getY();
				assertTrue(dy==0||dy==-1,
						"origin must be the clicked row or the one below, was "+dy);
			}
		}

		@Test
		@DisplayName("candidate detection is independent of absolute position")
		void detectionIsTranslationInvariant()
		{
			for(BlockPos origin : new BlockPos[]{
					new BlockPos(0, 0, 0), new BlockPos(-1000, 5, 1000), new BlockPos(30000000, 250, -30000000)})
				for(BlockPos clicked : GridConsoleGeometry.cells(origin, EnumFacing.SOUTH))
					assertTrue(setOf(GridConsoleGeometry.candidateOrigins(clicked, EnumFacing.SOUTH))
							.contains(origin), "failed at origin "+origin);
		}
	}
}
