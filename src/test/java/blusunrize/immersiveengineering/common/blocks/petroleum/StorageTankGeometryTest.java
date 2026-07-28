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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Storage Tank's rules: any hollow rectangular box, capacity from what it encloses.
 * <p>
 * All arithmetic, and the arithmetic is the feature -- a player picks a shape and the tank tells
 * them what it holds. The two things worth guarding are that a degenerate shape cannot produce a
 * tank at all, and that a large one cannot overflow its own capacity into something negative.
 */
class StorageTankGeometryTest
{
	@Nested
	@DisplayName("what shapes are allowed")
	class Shapes
	{
		@Test
		@DisplayName("the smallest tank is three on a side")
		void smallestIsThree()
		{
			//Two of anything is all wall and holds nothing. A tank reporting zero capacity would be
			//a puzzle rather than a mistake anybody could see, so it is refused outright.
			assertTrue(StorageTankGeometry.isValid(3, 3, 3));
			assertFalse(StorageTankGeometry.isValid(2, 3, 3));
			assertFalse(StorageTankGeometry.isValid(3, 2, 3));
			assertFalse(StorageTankGeometry.isValid(3, 3, 2));
		}

		@Test
		@DisplayName("shapes need not be cubes")
		void oblongsAreFine()
		{
			//The whole point against the three buried tiers: fit the tank to the building.
			assertTrue(StorageTankGeometry.isValid(3, 16, 3));
			assertTrue(StorageTankGeometry.isValid(16, 3, 5));
			assertTrue(StorageTankGeometry.isValid(4, 7, 12));
		}

		@Test
		@DisplayName("nothing may exceed the search bound")
		void boundedAbove()
		{
			assertTrue(StorageTankGeometry.isValid(16, 16, 16));
			assertFalse(StorageTankGeometry.isValid(17, 3, 3));
			assertFalse(StorageTankGeometry.isValid(3, 3, 17));
		}

		@Test
		@DisplayName("nonsense dimensions are refused rather than computed")
		void nonsenseRefused()
		{
			assertFalse(StorageTankGeometry.isValid(0, 0, 0));
			assertFalse(StorageTankGeometry.isValid(-4, 5, 5));
			assertEquals(0, StorageTankGeometry.capacity(-4, 5, 5));
			assertEquals(0, StorageTankGeometry.innerVolume(0, 0, 0));
			assertEquals(0, StorageTankGeometry.shellCount(2, 2, 2));
		}
	}

	@Nested
	@DisplayName("shell and volume")
	class Volume
	{
		@Test
		@DisplayName("a 3x3x3 encloses exactly one cell")
		void smallestEnclosesOne()
		{
			assertEquals(1, StorageTankGeometry.innerVolume(3, 3, 3));
			assertEquals(26, StorageTankGeometry.shellCount(3, 3, 3));
		}

		@Test
		@DisplayName("shell plus inside is the whole box, at every size")
		void shellAndInsideAccountForEverything()
		{
			//The arithmetic that decides both how much steel a tank costs and what it holds. If
			//these ever disagreed, one of the two numbers a player sees would be a lie.
			for(int w = 3; w <= 16; w++)
				for(int h = 3; h <= 16; h++)
					for(int d = 3; d <= 16; d++)
						assertEquals(w*h*d,
								StorageTankGeometry.shellCount(w, h, d)
										+StorageTankGeometry.innerVolume(w, h, d),
								w+"x"+h+"x"+d+" does not account for every cell");
		}

		@Test
		@DisplayName("the shell test agrees with the shell count")
		void shellTestMatchesTheCount()
		{
			//isShell is what the formation code walks with; shellCount is what the manual quotes.
			//Counting one against the other is the only way to know they mean the same thing.
			for(int w = 3; w <= 8; w++)
				for(int h = 3; h <= 8; h++)
					for(int d = 3; d <= 8; d++)
					{
						int counted = 0;
						for(int x = 0; x < w; x++)
							for(int y = 0; y < h; y++)
								for(int z = 0; z < d; z++)
									if(StorageTankGeometry.isShell(x, y, z, w, h, d))
										counted++;
						assertEquals(StorageTankGeometry.shellCount(w, h, d), counted,
								w+"x"+h+"x"+d);
					}
		}

		@Test
		@DisplayName("a shell saves more the bigger the tank gets")
		void shellIsTheCheaperBuild()
		{
			//The reason walls-only was chosen, stated as the property that is actually true. At
			//3x3x3 a shell saves almost nothing -- 26 blocks against 27 -- and the saving grows
			//from there: 386 of 729 at 9x9x9, 1352 of 4096 at 16x16x16. It is the large tanks the
			//decision is for, and this is what says so if anybody "simplifies" it back to a solid.
			double previous = 1.0;
			for(int side = 3; side <= 16; side++)
			{
				double fraction = StorageTankGeometry.shellCount(side, side, side)
						/(double)(side*side*side);
				assertTrue(fraction <= previous,
						"the shell got proportionally more expensive at "+side);
				previous = fraction;
			}
			assertTrue(previous < 0.4, "a 16x16x16 shell should be well under half a solid one");
		}
	}

	@Nested
	@DisplayName("capacity")
	class Capacity
	{
		@Test
		@DisplayName("capacity is the enclosed volume, per cell")
		void capacityFollowsVolume()
		{
			assertEquals(StorageTankGeometry.CAPACITY_PER_CELL,
					StorageTankGeometry.capacity(3, 3, 3));
			assertEquals(27*StorageTankGeometry.CAPACITY_PER_CELL,
					StorageTankGeometry.capacity(5, 5, 5));
		}

		@Test
		@DisplayName("it lands sensibly against the tanks it stands beside")
		void sizesAreBalancedAgainstTheOthers()
		{
			//A 5x5x5 should be in the same conversation as the Sheetmetal Tank's 512k, and a 9x9x9
			//should comfortably beat the Bulk Depot's 4M -- 386 blocks of steel has to buy
			//something. These are judgement calls, so they are written down rather than implied.
			assertTrue(StorageTankGeometry.capacity(5, 5, 5) > 256_000
							&&StorageTankGeometry.capacity(5, 5, 5) <= 512_000,
					"a 5x5x5 is out of step with the Sheetmetal Tank");
			assertTrue(StorageTankGeometry.capacity(9, 9, 9) > 4_000_000,
					"a 9x9x9 does not beat the Bulk Depot");
		}

		@Test
		@DisplayName("the biggest tank does not overflow into a negative capacity")
		void largestIsSane()
		{
			//The failure this guards is not a wrong number, it is a tank that swallows the world.
			int biggest = StorageTankGeometry.capacity(16, 16, 16);
			assertTrue(biggest > 0, "the largest tank has a negative capacity");
			assertEquals(14*14*14*StorageTankGeometry.CAPACITY_PER_CELL, biggest);
		}

		@Test
		@DisplayName("bigger always holds more")
		void monotonic()
		{
			for(int side = 3; side < 16; side++)
				assertTrue(StorageTankGeometry.capacity(side+1, side+1, side+1)
								> StorageTankGeometry.capacity(side, side, side),
						"growing a tank did not increase its capacity at "+side);
		}
	}

	@Nested
	@DisplayName("the comparator")
	class Comparator
	{
		@Test
		@DisplayName("empty reads zero and full reads fifteen")
		void endsOfTheScale()
		{
			int capacity = StorageTankGeometry.capacity(5, 5, 5);
			assertEquals(0, StorageTankGeometry.comparatorLevel(0, capacity));
			assertEquals(15, StorageTankGeometry.comparatorLevel(capacity, capacity));
		}

		@Test
		@DisplayName("anything at all reads as at least one")
		void aTraceIsNotEmpty()
		{
			//"Some" and "none" must never be the same picture, or a comparator cannot tell a tank
			//that is nearly dry from one that is dry.
			assertEquals(1, StorageTankGeometry.comparatorLevel(1,
					StorageTankGeometry.capacity(16, 16, 16)));
		}

		@Test
		@DisplayName("a large tank does not overflow the comparator arithmetic")
		void noOverflowOnLargeTanks()
		{
			//amount*15 in an int overflows around 143 million; the largest tank holds 39 million,
			//which is close enough that doing this in longs is not paranoia.
			int capacity = StorageTankGeometry.capacity(16, 16, 16);
			for(int step = 0; step <= 15; step++)
			{
				int level = StorageTankGeometry.comparatorLevel((int)((long)capacity*step/15), capacity);
				assertTrue(level >= 0&&level <= 15, "level "+level+" out of range at step "+step);
			}
			assertEquals(15, StorageTankGeometry.comparatorLevel(capacity, capacity));
		}

		@Test
		@DisplayName("nonsense arguments read empty rather than throwing")
		void nonsenseReadsEmpty()
		{
			assertEquals(0, StorageTankGeometry.comparatorLevel(-5, 1000));
			assertEquals(0, StorageTankGeometry.comparatorLevel(500, 0));
			assertEquals(0, StorageTankGeometry.comparatorLevel(500, -1));
		}
	}
}
