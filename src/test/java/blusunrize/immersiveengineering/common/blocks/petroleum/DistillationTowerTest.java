/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockDistillationTower;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Distillation Tower's shape and, above all, its height-to-cut mapping.
 * <p>
 * The mapping is the machine. It decides both which face a pipe can take a given cut from and
 * where the nozzles are drawn, so an error in it does not produce a wrong number somewhere -- it
 * produces a tower whose plumbing does not match its own silhouette, which the player can only
 * diagnose by trial and error. The shape is worth pinning for the usual reason: a wrong cell
 * means hammering does nothing, with no message and nothing in the log.
 * <p>
 * Nothing here touches a {@code FluidStack}: the recipe side of the machine cannot be tested
 * without a Forge bootstrap, and is covered by the server smoke run instead.
 */
class DistillationTowerTest
{
	private static final int HEIGHT = PetroleumGeometry.TOWER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.TOWER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.TOWER_WIDTH;
	private static final int CUTS = MultiblockDistillationTower.CUT_COUNT;

	/**
	 * A cell identity that survives being put in a set.
	 */
	private static int key(int h, int l, int w)
	{
		return (h*DEPTH+l)*WIDTH+w;
	}

	@Nested
	@DisplayName("draw ports")
	class Ports
	{
		@Test
		@DisplayName("the light cuts leave high and the heavy ones leave low")
		void portsDescendWithWeight()
		{
			for(int cut = 1; cut < CUTS; cut++)
				assertTrue(MultiblockDistillationTower.drawHeight(cut)
								< MultiblockDistillationTower.drawHeight(cut-1),
						"cut "+cut+" is heavier than "+(cut-1)+" and must leave below it");
		}

		/**
		 * The mapping as it stands, spelled out. Changing it is allowed -- changing it by accident
		 * is not, because every tower already built in a save is plumbed to these heights.
		 * <p>
		 * These were 11, 9, 8, 6, 4, 3, 1 on the twelve-layer column, which put two pairs of
		 * ports on adjacent layers; the column grew to fourteen so all seven land two apart.
		 */
		@Test
		@DisplayName("gas off the top, residue out of the bottom")
		void theMapping()
		{
			assertArrayEquals(new int[]{13, 11, 9, 7, 5, 3, 1}, heights());
			assertEquals(HEIGHT-1, MultiblockDistillationTower.drawHeight(0),
					"the lightest cut leaves at the very top of the column");
			assertEquals(MultiblockDistillationTower.BOTTOM_PORT,
					MultiblockDistillationTower.drawHeight(CUTS-1),
					"the residue leaves at the lowest port");
		}

		@Test
		@DisplayName("no two cuts share a height")
		void portsAreDistinct()
		{
			Set<Integer> seen = new HashSet<>();
			for(int cut = 0; cut < CUTS; cut++)
				assertTrue(seen.add(MultiblockDistillationTower.drawHeight(cut)),
						"two cuts drawn at the same height cannot be told apart by a pipe");
		}

		@Test
		@DisplayName("nothing is drawn off the layer crude goes in at")
		void theFeedLayerIsNotAPort()
		{
			assertEquals(-1, MultiblockDistillationTower.cutAtHeight(
					MultiblockDistillationTower.FEED_LAYER),
					"a face that both fills and drains would make the plumbing ambiguous");
		}

		@Test
		@DisplayName("every port height maps back to the cut it belongs to")
		void roundTrip()
		{
			for(int cut = 0; cut < CUTS; cut++)
				assertEquals(cut, MultiblockDistillationTower.cutAtHeight(
						MultiblockDistillationTower.drawHeight(cut)));
		}

		@Test
		@DisplayName("heights that are not ports report no cut")
		void plainShellHasNoPort()
		{
			Set<Integer> ports = new HashSet<>();
			for(int cut = 0; cut < CUTS; cut++)
				ports.add(MultiblockDistillationTower.drawHeight(cut));
			for(int h = -1; h <= HEIGHT; h++)
				if(!ports.contains(h))
					assertEquals(-1, MultiblockDistillationTower.cutAtHeight(h),
							"height "+h+" is plain shell");
		}

		@Test
		@DisplayName("a cut the column has no port for is refused rather than guessed at")
		void outOfRangeCuts()
		{
			assertEquals(-1, MultiblockDistillationTower.drawHeight(-1));
			assertEquals(-1, MultiblockDistillationTower.drawHeight(CUTS));
			assertEquals(-1, MultiblockDistillationTower.drawHeight(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("every port is inside the column")
		void portsAreInTheStructure()
		{
			for(int cut = 0; cut < CUTS; cut++)
			{
				int h = MultiblockDistillationTower.drawHeight(cut);
				assertTrue(h > MultiblockDistillationTower.FEED_LAYER&&h < HEIGHT,
						"cut "+cut+" is drawn at "+h+", which is not part of the tower");
			}
		}

		private int[] heights()
		{
			int[] out = new int[CUTS];
			for(int cut = 0; cut < CUTS; cut++)
				out[cut] = MultiblockDistillationTower.drawHeight(cut);
			return out;
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the tower is fourteen tall on a four by four footprint")
		void size()
		{
			assertArrayEquals(PetroleumGeometry.TOWER_SIZE, MultiblockDistillationTower.SIZE,
					"the structure definition and the geometry must agree on H, L, W");
			assertEquals(14, HEIGHT);
			assertEquals(4, DEPTH);
			assertEquals(4, WIDTH);
		}

		@Test
		@DisplayName("cells outside the box are never part of the structure")
		void outOfBoundsIsNotAPart()
		{
			assertFalse(MultiblockDistillationTower.isPart(-1, 1, 1));
			assertFalse(MultiblockDistillationTower.isPart(HEIGHT, 1, 1));
			assertFalse(MultiblockDistillationTower.isPart(0, -1, 1));
			assertFalse(MultiblockDistillationTower.isPart(0, DEPTH, 1));
			assertFalse(MultiblockDistillationTower.isPart(0, 1, -1));
			assertFalse(MultiblockDistillationTower.isPart(0, 1, WIDTH));
		}

		@Test
		@DisplayName("the vessel runs the full height of the tower")
		void theColumnIsContinuous()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 1; l < DEPTH-1; l++)
					for(int w = 1; w < WIDTH-1; w++)
						assertTrue(MultiblockDistillationTower.isPart(h, l, w),
								"a gap in the vessel at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("the feed deck is a solid layer")
		void theDeckIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(MultiblockDistillationTower.isPart(
							MultiblockDistillationTower.FEED_LAYER, l, w),
							"the deck has a hole at "+l+","+w);
		}

		/**
		 * The property that makes the machine legible: what sticks out of the tower is exactly what
		 * can be tapped. A nozzle at a height with no cut would be a port that gives nothing, and a
		 * cut at a height with no nozzle would be a port with nothing to show for it.
		 */
		@Test
		@DisplayName("nozzles stand at the draw heights and nowhere else")
		void nozzlesMarkThePorts()
		{
			for(int h = MultiblockDistillationTower.FEED_LAYER+1; h < HEIGHT; h++)
			{
				boolean isPort = MultiblockDistillationTower.cutAtHeight(h) >= 0;
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(MultiblockDistillationTower.isNozzle(l, w))
							assertEquals(isPort, MultiblockDistillationTower.isPart(h, l, w),
									"nozzle at "+h+","+l+","+w);
			}
		}

		@Test
		@DisplayName("the corners are open above the deck")
		void cornersAreOpen()
		{
			for(int h = MultiblockDistillationTower.FEED_LAYER+1; h < HEIGHT; h++)
				for(int l : new int[]{0, DEPTH-1})
					for(int w : new int[]{0, WIDTH-1})
						assertFalse(MultiblockDistillationTower.isPart(h, l, w),
								"corner at "+h+","+l+","+w+" would foul the nozzles beside it");
		}

		@Test
		@DisplayName("the column is a column and not a solid block")
		void theTowerIsMostlyAir()
		{
			assertEquals(124, MultiblockDistillationTower.blockCount());
			assertTrue(MultiblockDistillationTower.blockCount() < HEIGHT*DEPTH*WIDTH*3/4,
					"a tower filling its own box is a chimney, not a column");
		}

		/**
		 * The property that decides whether the structure is buildable at all: every block has to
		 * be placeable against one that is already there. A floating cell cannot be reached with a
		 * right-click, so a structure containing one can never be completed by hand and the only
		 * symptom is that hammering it does nothing.
		 */
		@Test
		@DisplayName("every block can be placed against one already standing")
		void nothingFloats()
		{
			Set<Integer> placed = new HashSet<>();
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockDistillationTower.isPart(0, l, w))
						placed.add(key(0, l, w));

			boolean grew = true;
			while(grew)
			{
				grew = false;
				for(int h = 0; h < HEIGHT; h++)
					for(int l = 0; l < DEPTH; l++)
						for(int w = 0; w < WIDTH; w++)
						{
							if(!MultiblockDistillationTower.isPart(h, l, w)||placed.contains(key(h, l, w)))
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
						if(MultiblockDistillationTower.isPart(h, l, w))
							assertTrue(placed.contains(key(h, l, w)),
									"nothing to place against at "+h+","+l+","+w);
		}

		/**
		 * The footprint has to look the same mirrored, or the structure check would have to try
		 * both handednesses and the tile entity would have to remember which one it formed as.
		 */
		@Test
		@DisplayName("the tower is its own mirror image")
		void isSymmetric()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockDistillationTower.isPart(h, l, w),
								MultiblockDistillationTower.isPart(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}
	}

	@Nested
	@DisplayName("cell classification")
	class Cells
	{
		@Test
		@DisplayName("a cell is at most one of vessel and nozzle")
		void classesDoNotOverlap()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertFalse(MultiblockDistillationTower.isColumn(l, w)
									&&MultiblockDistillationTower.isNozzle(l, w),
							"cell "+l+","+w+" cannot be both shell and nozzle");
		}

		@Test
		@DisplayName("each face of the tower carries a pair of nozzles")
		void nozzlesPerFace()
		{
			int count = 0;
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockDistillationTower.isNozzle(l, w))
						count++;
			assertEquals(8, count, "four faces, two nozzles each");
		}

		@Test
		@DisplayName("the structure index agrees with the height it is read back as")
		void indexRoundTrip()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(h, PetroleumGeometry.heightOf(PetroleumGeometry.TOWER_SIZE,
								PetroleumGeometry.structureIndex(
										PetroleumGeometry.TOWER_SIZE, h, l, w)),
								"the tile entity works out its draw height from this");
		}
	}

	@Nested
	@DisplayName("draw port spacing")
	class DrawPorts
	{
		/**
		 * The regression this class exists for.
		 * <p>
		 * At a twelve-layer column the rounding put ports on layers 11, 9, 8, 6, 4, 3 and 1 --
		 * two pairs on adjacent layers. Each block exposes only its own cut, so nothing was
		 * mixed by standing still; but a pipe climbing the tower to reach the upper port of a
		 * pair connects to the lower one as it passes, and two cuts end up in one line. Which
		 * is exactly the failure the spacing rule was written to prevent.
		 */
		@Test
		@DisplayName("no two draw ports sit on adjacent layers")
		void portsAreNeverAdjacent()
		{
			for(int cut = 1; cut < MultiblockDistillationTower.CUT_COUNT; cut++)
			{
				int gap = MultiblockDistillationTower.drawHeight(cut-1)
						-MultiblockDistillationTower.drawHeight(cut);
				assertTrue(gap >= 2, "cuts "+(cut-1)+" and "+cut+" are only "+gap+" layers apart");
			}
		}

		@Test
		@DisplayName("ports run top down, one per cut, within the column")
		void portsAreOrderedAndInRange()
		{
			Set<Integer> seen = new HashSet<>();
			int previous = Integer.MAX_VALUE;
			for(int cut = 0; cut < MultiblockDistillationTower.CUT_COUNT; cut++)
			{
				int height = MultiblockDistillationTower.drawHeight(cut);
				assertTrue(height > MultiblockDistillationTower.FEED_LAYER,
						"a port on the feed deck would both fill and drain the same face");
				assertTrue(height < PetroleumGeometry.TOWER_HEIGHT, "port above the column");
				assertTrue(height < previous, "the lighter cut must leave higher up");
				assertTrue(seen.add(height), "two cuts share a port");
				assertEquals(cut, MultiblockDistillationTower.cutAtHeight(height),
						"the tile entity resolves the tank by reading the height back");
				previous = height;
			}
			assertEquals(MultiblockDistillationTower.TOP_PORT,
					MultiblockDistillationTower.drawHeight(0), "the lightest cut leaves at the top");
			assertEquals(MultiblockDistillationTower.BOTTOM_PORT,
					MultiblockDistillationTower.drawHeight(MultiblockDistillationTower.CUT_COUNT-1),
					"the residue leaves just above the deck");
		}

		@Test
		@DisplayName("layers between ports are inert")
		void shellLayersExposeNothing()
		{
			int ports = 0;
			for(int h = 0; h < PetroleumGeometry.TOWER_HEIGHT; h++)
				if(MultiblockDistillationTower.cutAtHeight(h) >= 0)
					ports++;
			assertEquals(MultiblockDistillationTower.CUT_COUNT, ports,
					"every port height must belong to exactly one cut and no others");
		}
	}
}
