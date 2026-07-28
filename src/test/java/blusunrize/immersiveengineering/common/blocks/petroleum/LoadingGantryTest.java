/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockLoadingGantry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fluid Loading Gantry's shape and its two chest positions.
 * <p>
 * This machine has no interface at all -- where the chests go <em>is</em> the configuration -- so
 * the two positions carry the entire user-facing contract. They were briefly on opposite faces of
 * the machine, which is buildable and undiscoverable, and nothing in game would have said so.
 */
class LoadingGantryTest
{
	private static final int[] SIZE = PetroleumGeometry.GANTRY_SIZE;

	@Nested
	@DisplayName("the shape")
	class Shape
	{
		@Test
		@DisplayName("it is four tall, one deep and three wide")
		void size()
		{
			assertEquals(4, PetroleumGeometry.GANTRY_HEIGHT);
			assertEquals(1, PetroleumGeometry.GANTRY_DEPTH);
			assertEquals(3, PetroleumGeometry.GANTRY_WIDTH);
			assertArrayEquals(new int[]{4, 1, 3}, SIZE, "H, L, W in that order");
		}

		@Test
		@DisplayName("two legs and a beam, with a bay between them")
		void legsAndBeam()
		{
			for(int h = 0; h < PetroleumGeometry.GANTRY_HEIGHT-1; h++)
			{
				assertEquals(MultiblockLoadingGantry.LEG, MultiblockLoadingGantry.shapeAt(h, 0, 0));
				assertEquals(MultiblockLoadingGantry.EMPTY, MultiblockLoadingGantry.shapeAt(h, 0, 1),
						"the bay at height "+h+" must stay open -- it is where a player and a "
								+"minecart go");
				assertEquals(MultiblockLoadingGantry.LEG, MultiblockLoadingGantry.shapeAt(h, 0, 2));
			}
			for(int w = 0; w < PetroleumGeometry.GANTRY_WIDTH; w++)
				assertEquals(MultiblockLoadingGantry.BEAM,
						MultiblockLoadingGantry.shapeAt(PetroleumGeometry.GANTRY_HEIGHT-1, 0, w),
						"the beam has a gap at "+w);
		}

		@Test
		@DisplayName("the material counts are what the manual will claim")
		void materialCounts()
		{
			assertEquals(6, MultiblockLoadingGantry.blockCount(MultiblockLoadingGantry.LEG));
			assertEquals(3, MultiblockLoadingGantry.blockCount(MultiblockLoadingGantry.BEAM));
		}

		@Test
		@DisplayName("cells outside the box are not part of it")
		void outOfBoundsIsEmpty()
		{
			assertFalse(MultiblockLoadingGantry.isPart(-1, 0, 0));
			assertFalse(MultiblockLoadingGantry.isPart(0, 0, -1));
			assertFalse(MultiblockLoadingGantry.isPart(PetroleumGeometry.GANTRY_HEIGHT, 0, 0));
			assertFalse(MultiblockLoadingGantry.isPart(0, 0, PetroleumGeometry.GANTRY_WIDTH));
		}
	}

	@Nested
	@DisplayName("the two chest positions")
	class ChestPositions
	{
		@Test
		@DisplayName("intake and output are different blocks")
		void positionsAreDistinct()
		{
			assertNotEquals(MultiblockLoadingGantry.INTAKE_POS, MultiblockLoadingGantry.OUTPUT_POS,
					"one position for both would make the machine feed itself");
		}

		@Test
		@DisplayName("both sit at the foot of the machine, one on each leg")
		void bothAreAtFootLevel()
		{
			//Both chests stand on the ground in front of the gantry. Putting one of them up the
			//tower, or behind it, is buildable and impossible to guess.
			assertEquals(0, PetroleumGeometry.heightOf(SIZE, MultiblockLoadingGantry.INTAKE_POS),
					"the intake must be reachable from the ground");
			assertEquals(0, PetroleumGeometry.heightOf(SIZE, MultiblockLoadingGantry.OUTPUT_POS),
					"and so must the output");
		}

		@Test
		@DisplayName("they are the two legs, not two points on one leg")
		void positionsAreTheTwoLegs()
		{
			int width = PetroleumGeometry.GANTRY_WIDTH;
			assertEquals(0, MultiblockLoadingGantry.INTAKE_POS%width, "the left leg");
			assertEquals(width-1, MultiblockLoadingGantry.OUTPUT_POS%width, "the right leg");
		}

		@Test
		@DisplayName("both positions are actually part of the structure")
		void positionsAreRealBlocks()
		{
			//A port on a cell the structure does not occupy is a port that can never be reached.
			for(int pos : new int[]{MultiblockLoadingGantry.INTAKE_POS, MultiblockLoadingGantry.OUTPUT_POS})
			{
				int h = PetroleumGeometry.heightOf(SIZE, pos);
				int w = pos%PetroleumGeometry.GANTRY_WIDTH;
				assertTrue(MultiblockLoadingGantry.isPart(h, 0, w),
						"structure index "+pos+" is not a block of the gantry");
			}
		}
	}
}
