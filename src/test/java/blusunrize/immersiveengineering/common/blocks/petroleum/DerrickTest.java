/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockDerrick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Drilling Derrick's shape and its progress readout.
 * <p>
 * Both are worth pinning because both fail silently. A wrong cell in the shape means the
 * structure refuses to form when hammered, with no message and nothing in the log; a wrong
 * percentage means a rig that is working reads as a rig that is stuck, which is precisely the
 * ambiguity the whole feature is built to avoid.
 */
class DerrickTest
{
	private static final int HEIGHT = PetroleumGeometry.DERRICK_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.DERRICK_DEPTH;
	private static final int WIDTH = PetroleumGeometry.DERRICK_WIDTH;

	private static boolean isCorner(int l, int w)
	{
		return (l==0||l==DEPTH-1)&&(w==0||w==WIDTH-1);
	}

	private static boolean isCentre(int l, int w)
	{
		return l==DEPTH/2&&w==WIDTH/2;
	}

	/**
	 * A cell identity that survives being put in a set.
	 */
	private static int key(int h, int l, int w)
	{
		return (h*DEPTH+l)*WIDTH+w;
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the derrick is nine tall on a three by three footprint")
		void size()
		{
			assertArrayEquals(PetroleumGeometry.DERRICK_SIZE, MultiblockDerrick.SIZE,
					"the structure definition and the geometry must agree on H, L, W");
			assertEquals(9, HEIGHT);
			assertEquals(3, DEPTH);
			assertEquals(3, WIDTH);
		}

		@Test
		@DisplayName("cells outside the box are never part of the structure")
		void outOfBoundsIsNotAPart()
		{
			assertFalse(MultiblockDerrick.isPart(-1, 0, 0));
			assertFalse(MultiblockDerrick.isPart(HEIGHT, 0, 0));
			assertFalse(MultiblockDerrick.isPart(0, -1, 0));
			assertFalse(MultiblockDerrick.isPart(0, DEPTH, 0));
			assertFalse(MultiblockDerrick.isPart(0, 0, -1));
			assertFalse(MultiblockDerrick.isPart(0, 0, WIDTH));
		}

		@Test
		@DisplayName("the drill floor and the water table are solid layers")
		void solidLayers()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					assertTrue(MultiblockDerrick.isPart(0, l, w),
							"drill floor is solid, missing "+l+","+w);
					assertTrue(MultiblockDerrick.isPart(MultiblockDerrick.TABLE_LAYER, l, w),
							"water table is solid, missing "+l+","+w);
				}
		}

		@Test
		@DisplayName("between the bands the tower is four corner legs")
		void legsAreCorners()
		{
			for(int h = 1; h < MultiblockDerrick.TABLE_LAYER; h++)
			{
				if(h==MultiblockDerrick.GIRT_LAYER)
					continue;
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(isCorner(l, w), MultiblockDerrick.isPart(h, l, w),
								"layer "+h+" should be legs only, at "+l+","+w);
			}
		}

		@Test
		@DisplayName("the girt band rings the shaft without closing it")
		void girtBandIsARing()
		{
			int h = MultiblockDerrick.GIRT_LAYER;
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(!isCentre(l, w), MultiblockDerrick.isPart(h, l, w),
							"girt band at "+l+","+w);
		}

		@Test
		@DisplayName("the crown is a single block over the middle of the floor")
		void crownIsOneBlock()
		{
			int count = 0;
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockDerrick.isPart(MultiblockDerrick.CROWN_LAYER, l, w))
					{
						count++;
						assertTrue(isCentre(l, w), "the crown sits over the bore, not at "+l+","+w);
					}
			assertEquals(1, count);
			assertEquals(HEIGHT-1, MultiblockDerrick.CROWN_LAYER, "the crown is the top layer");
		}

		@Test
		@DisplayName("the tower is open: most of its box is air")
		void theTowerIsOpen()
		{
			assertEquals(47, MultiblockDerrick.blockCount());
			assertTrue(MultiblockDerrick.blockCount() < HEIGHT*DEPTH*WIDTH*3/4,
					"a derrick that is mostly solid is a chimney, not a derrick");
		}

		/**
		 * The property that decides whether the structure is buildable at all: every block has to
		 * be placeable against one that is already there. A floating cell cannot be reached with
		 * a right-click, so a structure containing one can never be completed by hand and the
		 * only symptom is that hammering it does nothing.
		 */
		@Test
		@DisplayName("every block can be placed against one already standing")
		void nothingFloats()
		{
			Set<Integer> placed = new HashSet<>();
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockDerrick.isPart(0, l, w))
						placed.add(key(0, l, w));

			boolean grew = true;
			while(grew)
			{
				grew = false;
				for(int h = 0; h < HEIGHT; h++)
					for(int l = 0; l < DEPTH; l++)
						for(int w = 0; w < WIDTH; w++)
						{
							if(!MultiblockDerrick.isPart(h, l, w)||placed.contains(key(h, l, w)))
								continue;
							if(placed.contains(key(h-1, l, w))||placed.contains(key(h+1, l, w))
									||placed.contains(key(h, l-1, w))||placed.contains(key(h, l+1, w))
									||placed.contains(key(h, l, w-1))||placed.contains(key(h, l, w+1)))
							{
								placed.add(key(h, l, w));
								grew = true;
							}
						}
			}
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(MultiblockDerrick.isPart(h, l, w))
							assertTrue(placed.contains(key(h, l, w)),
									"nothing to place against at "+h+","+l+","+w);
		}
	}

	@Nested
	@DisplayName("the bore")
	class Bore
	{
		@Test
		@DisplayName("the bore comes up through the middle of the drill floor")
		void borePosition()
		{
			assertEquals(0, PetroleumGeometry.heightOf(
					PetroleumGeometry.DERRICK_SIZE, TileEntityDerrick.BORE_INDEX),
					"the wellhead is left at ground level, not up the tower");
			assertEquals(PetroleumGeometry.structureIndex(
					PetroleumGeometry.DERRICK_SIZE, 0, DEPTH/2, WIDTH/2),
					TileEntityDerrick.BORE_INDEX);
			assertTrue(MultiblockDerrick.isPart(0, DEPTH/2, WIDTH/2),
					"the derrick has to own the block it converts into a wellhead");
		}

		@Test
		@DisplayName("the crown hangs directly over the bore")
		void crownIsOverTheBore()
		{
			assertTrue(MultiblockDerrick.isPart(MultiblockDerrick.CROWN_LAYER, DEPTH/2, WIDTH/2));
		}
	}

	@Nested
	@DisplayName("progress readout")
	class Progress
	{
		@Test
		@DisplayName("an untouched rig reads zero and a finished one reads a hundred")
		void endpoints()
		{
			assertEquals(0, TileEntityDerrick.progressPercent(0, TileEntityDerrick.DRILL_TIME));
			assertEquals(100, TileEntityDerrick.progressPercent(
					TileEntityDerrick.DRILL_TIME, TileEntityDerrick.DRILL_TIME));
		}

		/**
		 * The reason the percentage rounds up rather than down: a single pass out of a hundred and
		 * twenty is under one percent, and a rig that is working must never report the same number
		 * as a rig that is not.
		 */
		@Test
		@DisplayName("a rig that has started never still reads zero")
		void startedNeverReadsZero()
		{
			for(int ticks = 1; ticks <= TileEntityDerrick.DRILL_TIME; ticks++)
				assertTrue(TileEntityDerrick.progressPercent(ticks, TileEntityDerrick.DRILL_TIME) > 0,
						"reads 0% after "+ticks+" ticks of work");
		}

		@Test
		@DisplayName("the readout never runs past a hundred or below zero")
		void isClamped()
		{
			assertEquals(100, TileEntityDerrick.progressPercent(
					TileEntityDerrick.DRILL_TIME*3, TileEntityDerrick.DRILL_TIME));
			assertEquals(0, TileEntityDerrick.progressPercent(-50, TileEntityDerrick.DRILL_TIME));
			assertEquals(100, TileEntityDerrick.progressPercent(0, 0),
					"a zero-length job is already done rather than dividing by zero");
		}

		@Test
		@DisplayName("progress only ever climbs")
		void isMonotonic()
		{
			int previous = 0;
			for(int ticks = 0; ticks <= TileEntityDerrick.DRILL_TIME;
				ticks += TileEntityDerrick.DRILL_INTERVAL)
			{
				int percent = TileEntityDerrick.progressPercent(ticks, TileEntityDerrick.DRILL_TIME);
				assertTrue(percent >= previous, "went backwards at "+ticks+" ticks");
				previous = percent;
			}
			assertEquals(100, previous, "the last pass has to land exactly on done");
		}
	}

	@Nested
	@DisplayName("drilling cost")
	class Cost
	{
		@Test
		@DisplayName("a well is a minute of work at a few hundred FE/t")
		void theAdvertisedNumbers()
		{
			assertEquals(60, TileEntityDerrick.DRILL_TIME/20, "a minute of drilling");
			assertEquals(307200, TileEntityDerrick.DRILL_TIME*TileEntityDerrick.ENERGY_PER_TICK,
					"total cost of a well, in FE");
		}

		@Test
		@DisplayName("the work interval divides the job exactly")
		void intervalDividesTheJob()
		{
			assertEquals(0, TileEntityDerrick.DRILL_TIME%TileEntityDerrick.DRILL_INTERVAL,
					"a leftover part-interval would leave a rig one pass short of finishing");
		}

		@Test
		@DisplayName("the buffer holds at least one pass")
		void bufferCoversAPass()
		{
			assertTrue(TileEntityDerrick.ENERGY_CAPACITY
							>= TileEntityDerrick.ENERGY_PER_TICK*TileEntityDerrick.DRILL_INTERVAL,
					"a buffer too small for one pass can never pay for one, and the rig would "
							+"stall forever with a full buffer");
		}
	}
}
