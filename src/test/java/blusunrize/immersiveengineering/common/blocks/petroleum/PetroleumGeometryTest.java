/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared multiblock index maths, and the dimensions every petroleum structure is built to.
 * <p>
 * {@code TileEntityMultiblockPart.pos} is an <strong>int structure index</strong>, not a
 * {@code BlockPos}. Every port predicate in this feature -- which layer a cut is drawn off, which
 * face takes the feed, where the exhaust end is -- is written against that index, so if
 * {@link PetroleumGeometry#structureIndex} and {@link PetroleumGeometry#heightOf} ever disagree,
 * ports move silently and a tower starts handing back the wrong fluid at the wrong height.
 * <p>
 * The dimensions themselves are frozen deliberately. They are what a player's existing build is
 * shaped like; changing one does not fail, it stops every already-built machine of that kind from
 * forming.
 */
class PetroleumGeometryTest
{
	/** Every structure, as {name, size}, so each property below is asserted against all of them. */
	private static final Object[][] STRUCTURES = {
			{"derrick", PetroleumGeometry.DERRICK_SIZE},
			{"pumpjack", PetroleumGeometry.PUMPJACK_SIZE},
			{"tower", PetroleumGeometry.TOWER_SIZE},
			{"burner", PetroleumGeometry.BURNER_SIZE},
			{"scrubber", PetroleumGeometry.SCRUBBER_SIZE},
			{"turbine", PetroleumGeometry.TURBINE_SIZE},
			{"boiler", PetroleumGeometry.BOILER_SIZE},
			{"hrsg", PetroleumGeometry.HRSG_SIZE},
			{"hall", PetroleumGeometry.HALL_SIZE},
			{"engine", PetroleumGeometry.ENGINE_SIZE},
			{"cracker", PetroleumGeometry.CRACKER_SIZE},
			{"gantry", PetroleumGeometry.GANTRY_SIZE},
	};

	@Nested
	@DisplayName("the structure index")
	class Index
	{
		@Test
		@DisplayName("every cell of every structure gets its own index, with no gaps and no repeats")
		void indexIsABijection()
		{
			for(Object[] entry : STRUCTURES)
			{
				String name = (String)entry[0];
				int[] size = (int[])entry[1];
				Set<Integer> seen = new HashSet<>();
				for(int h = 0; h < size[0]; h++)
					for(int d = 0; d < size[1]; d++)
						for(int w = 0; w < size[2]; w++)
						{
							int index = PetroleumGeometry.structureIndex(size, h, d, w);
							assertTrue(index >= 0, name+": negative index at "+h+","+d+","+w);
							assertTrue(seen.add(index),
									name+": index "+index+" reused at "+h+","+d+","+w);
						}
				int volume = size[0]*size[1]*size[2];
				assertEquals(volume, seen.size(), name+": not every cell has an index");
				for(int i = 0; i < volume; i++)
					assertTrue(seen.contains(i), name+": index "+i+" belongs to no cell");
			}
		}

		@Test
		@DisplayName("heightOf inverts structureIndex for every cell of every structure")
		void heightRoundTrips()
		{
			//The property the ports actually depend on: a cut drawn at layer 7 must read back as
			//layer 7 from the index the tile entity was handed.
			for(Object[] entry : STRUCTURES)
			{
				String name = (String)entry[0];
				int[] size = (int[])entry[1];
				for(int h = 0; h < size[0]; h++)
					for(int d = 0; d < size[1]; d++)
						for(int w = 0; w < size[2]; w++)
						{
							int index = PetroleumGeometry.structureIndex(size, h, d, w);
							assertEquals(h, PetroleumGeometry.heightOf(size, index),
									name+": height lost for cell "+h+","+d+","+w+" (index "+index+")");
						}
			}
		}

		@Test
		@DisplayName("the whole of a layer shares one height and consecutive layers do not overlap")
		void layersAreContiguous()
		{
			for(Object[] entry : STRUCTURES)
			{
				String name = (String)entry[0];
				int[] size = (int[])entry[1];
				int perLayer = size[1]*size[2];
				for(int h = 0; h < size[0]; h++)
				{
					int first = PetroleumGeometry.structureIndex(size, h, 0, 0);
					assertEquals(h*perLayer, first, name+": layer "+h+" does not start where it should");
					int last = PetroleumGeometry.structureIndex(size, h, size[1]-1, size[2]-1);
					assertEquals(h*perLayer+perLayer-1, last, name+": layer "+h+" does not end where it should");
				}
			}
		}

		@Test
		@DisplayName("the origin is index zero for every structure")
		void originIsZero()
		{
			for(Object[] entry : STRUCTURES)
				assertEquals(0, PetroleumGeometry.structureIndex((int[])entry[1], 0, 0, 0),
						entry[0]+": the origin must be index zero");
		}
	}

	@Nested
	@DisplayName("the size arrays")
	class Sizes
	{
		@Test
		@DisplayName("each size array agrees with its own height, depth and width constants")
		void arraysMatchTheirConstants()
		{
			//The arrays and the named constants are two statements of the same fact, and code reads
			//both -- the multiblock shapes use the array, the tile entities use the constants.
			assertArrayEquals(new int[]{PetroleumGeometry.DERRICK_HEIGHT, PetroleumGeometry.DERRICK_DEPTH,
					PetroleumGeometry.DERRICK_WIDTH}, PetroleumGeometry.DERRICK_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.PUMPJACK_HEIGHT, PetroleumGeometry.PUMPJACK_DEPTH,
					PetroleumGeometry.PUMPJACK_WIDTH}, PetroleumGeometry.PUMPJACK_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.TOWER_HEIGHT, PetroleumGeometry.TOWER_DEPTH,
					PetroleumGeometry.TOWER_WIDTH}, PetroleumGeometry.TOWER_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.BURNER_HEIGHT, PetroleumGeometry.BURNER_DEPTH,
					PetroleumGeometry.BURNER_WIDTH}, PetroleumGeometry.BURNER_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.SCRUBBER_HEIGHT, PetroleumGeometry.SCRUBBER_DEPTH,
					PetroleumGeometry.SCRUBBER_WIDTH}, PetroleumGeometry.SCRUBBER_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.TURBINE_HEIGHT, PetroleumGeometry.TURBINE_DEPTH,
					PetroleumGeometry.TURBINE_WIDTH}, PetroleumGeometry.TURBINE_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.BOILER_HEIGHT, PetroleumGeometry.BOILER_DEPTH,
					PetroleumGeometry.BOILER_WIDTH}, PetroleumGeometry.BOILER_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.HRSG_HEIGHT, PetroleumGeometry.HRSG_DEPTH,
					PetroleumGeometry.HRSG_WIDTH}, PetroleumGeometry.HRSG_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.HALL_HEIGHT, PetroleumGeometry.HALL_DEPTH,
					PetroleumGeometry.HALL_WIDTH}, PetroleumGeometry.HALL_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.ENGINE_HEIGHT, PetroleumGeometry.ENGINE_DEPTH,
					PetroleumGeometry.ENGINE_WIDTH}, PetroleumGeometry.ENGINE_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.CRACKER_HEIGHT, PetroleumGeometry.CRACKER_DEPTH,
					PetroleumGeometry.CRACKER_WIDTH}, PetroleumGeometry.CRACKER_SIZE);
			assertArrayEquals(new int[]{PetroleumGeometry.GANTRY_HEIGHT, PetroleumGeometry.GANTRY_DEPTH,
					PetroleumGeometry.GANTRY_WIDTH}, PetroleumGeometry.GANTRY_SIZE);
		}

		@Test
		@DisplayName("every structure has three positive dimensions")
		void allDimensionsArePositive()
		{
			for(Object[] entry : STRUCTURES)
			{
				int[] size = (int[])entry[1];
				assertEquals(3, size.length, entry[0]+": a size is height, depth and width");
				for(int d : size)
					assertTrue(d > 0, entry[0]+": a structure cannot have a zero dimension");
			}
		}

		@Test
		@DisplayName("no structure is larger than a chunk in any horizontal direction")
		void nothingExceedsAChunk()
		{
			//A structure wider than sixteen cannot be built inside one chunk, which makes forming it
			//depend on what happens to be loaded.
			for(Object[] entry : STRUCTURES)
			{
				int[] size = (int[])entry[1];
				assertTrue(size[1] <= 16, entry[0]+": depth "+size[1]+" exceeds a chunk");
				assertTrue(size[2] <= 16, entry[0]+": width "+size[2]+" exceeds a chunk");
			}
		}
	}

	@Nested
	@DisplayName("the dimensions are frozen -- changing one stops existing builds forming")
	class Frozen
	{
		@Test
		@DisplayName("every structure is exactly the shape it shipped as")
		void shapesAreUnchanged()
		{
			assertAll(
					() -> assertArrayEquals(new int[]{9, 3, 3}, PetroleumGeometry.DERRICK_SIZE, "derrick"),
					() -> assertArrayEquals(new int[]{5, 6, 3}, PetroleumGeometry.PUMPJACK_SIZE, "pumpjack"),
					() -> assertArrayEquals(new int[]{14, 4, 4}, PetroleumGeometry.TOWER_SIZE, "tower"),
					() -> assertArrayEquals(new int[]{3, 3, 3}, PetroleumGeometry.BURNER_SIZE, "burner"),
					() -> assertArrayEquals(new int[]{6, 3, 3}, PetroleumGeometry.SCRUBBER_SIZE, "scrubber"),
					() -> assertArrayEquals(new int[]{3, 6, 3}, PetroleumGeometry.TURBINE_SIZE, "turbine"),
					() -> assertArrayEquals(new int[]{5, 5, 7}, PetroleumGeometry.BOILER_SIZE, "boiler"),
					() -> assertArrayEquals(new int[]{3, 5, 3}, PetroleumGeometry.HRSG_SIZE, "hrsg"),
					() -> assertArrayEquals(new int[]{5, 9, 5}, PetroleumGeometry.HALL_SIZE, "hall"),
					() -> assertArrayEquals(new int[]{4, 5, 5}, PetroleumGeometry.ENGINE_SIZE, "engine"),
					() -> assertArrayEquals(new int[]{6, 3, 5}, PetroleumGeometry.CRACKER_SIZE, "cracker"),
					() -> assertArrayEquals(new int[]{4, 1, 3}, PetroleumGeometry.GANTRY_SIZE, "gantry")
			);
		}

		@Test
		@DisplayName("the HRSG's intake face matches the Gas Turbine's exhaust end")
		void hrsgMatchesTheTurbine()
		{
			//The combined cycle depends on these two docking. The manual promises "three wide and
			//three tall, which is exactly the turbine's exhaust end" -- if either changes alone, an
			//HRSG can no longer be butted onto a turbine and the whole section stops working.
			assertEquals(PetroleumGeometry.TURBINE_HEIGHT, PetroleumGeometry.HRSG_HEIGHT,
					"the HRSG and the turbine must be the same height to dock");
			assertEquals(PetroleumGeometry.TURBINE_WIDTH, PetroleumGeometry.HRSG_WIDTH,
					"the HRSG and the turbine must be the same width to dock");
		}
	}
}
