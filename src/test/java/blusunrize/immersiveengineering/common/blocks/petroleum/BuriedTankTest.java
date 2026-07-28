/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.petroleum.BuriedTankGeometry.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The buried tanks' shape arithmetic.
 * <p>
 * A multiblock whose shape is subtly wrong fails in the worst possible way: hammering it does
 * nothing at all and nothing says why. These tanks are worse than most, because two of the three
 * are built in a hole where a player cannot easily compare what they built against the manual.
 * The shape is derived rather than written out, so this is where the derivation gets checked.
 */
class BuriedTankTest
{
	@Nested
	@DisplayName("shell shape")
	class Shell
	{
		@Test
		@DisplayName("the block counts are what the tiers claim")
		void blockCounts()
		{
			//A two by two by two box has no interior, so the shell rule leaves it solid.
			assertEquals(8, BuriedTankGeometry.DOMESTIC.blockCount(), "domestic");
			assertEquals(0, BuriedTankGeometry.DOMESTIC.voidCount(), "domestic has no interior");
			//Two full faces of twenty-five plus two rings of sixteen.
			assertEquals(82, BuriedTankGeometry.COMMERCIAL.blockCount(), "commercial");
			assertEquals(18, BuriedTankGeometry.COMMERCIAL.voidCount(), "commercial interior");
			//Two full faces of eighty-one plus four rings of thirty-two.
			assertEquals(290, BuriedTankGeometry.BULK.blockCount(), "bulk");
			assertEquals(196, BuriedTankGeometry.BULK.voidCount(), "bulk interior");
		}

		@Test
		@DisplayName("parts and void cells account for the whole box")
		void cellsAreAccountedFor()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
				assertEquals(tier.cellCount(), tier.blockCount()+tier.voidCount(), tier.name);
		}

		@Test
		@DisplayName("everything on the outer surface is shell and everything inside it is not")
		void shellIsTheSurface()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
				for(int h = 0; h < tier.height; h++)
					for(int l = 0; l < tier.depth; l++)
						for(int w = 0; w < tier.width; w++)
						{
							boolean onSurface = h==0||h==tier.height-1||l==0||l==tier.depth-1
									||w==0||w==tier.width-1;
							assertEquals(onSurface, tier.isPart(h, l, w),
									tier.name+" at "+h+","+l+","+w);
						}
		}

		@Test
		@DisplayName("cells outside the box are not part of it")
		void outOfBoundsIsNotPart()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				assertFalse(tier.isPart(-1, 0, 0), tier.name);
				assertFalse(tier.isPart(0, -1, 0), tier.name);
				assertFalse(tier.isPart(0, 0, -1), tier.name);
				assertFalse(tier.isPart(tier.height, 0, 0), tier.name);
				assertFalse(tier.isPart(0, tier.depth, 0), tier.name);
				assertFalse(tier.isPart(0, 0, tier.width), tier.name);
			}
		}
	}

	@Nested
	@DisplayName("the fill cap")
	class Cap
	{
		@Test
		@DisplayName("there is exactly one, and it is part of the shell")
		void exactlyOneCap()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				int caps = 0;
				for(int h = 0; h < tier.height; h++)
					for(int l = 0; l < tier.depth; l++)
						for(int w = 0; w < tier.width; w++)
							if(tier.isCap(h, l, w))
							{
								caps++;
								assertTrue(tier.isPart(h, l, w),
										tier.name+"'s cap is not part of its shell");
								assertTrue(tier.isRoof(h, l, w),
										tier.name+"'s cap is not on its roof");
							}
				assertEquals(1, caps, tier.name);
			}
		}

		@Test
		@DisplayName("the cap index agrees with the cap cell")
		void capIndexRoundTrips()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				int index = tier.capIndex();
				assertTrue(index >= 0&&index < tier.cellCount(), tier.name+" cap index out of range");
				int h = PetroleumGeometry.heightOf(tier.size, index);
				int l = index%(tier.depth*tier.width)/tier.width;
				int w = index%tier.width;
				assertTrue(tier.isCap(h, l, w),
						tier.name+"'s cap index does not decode to its cap cell");
			}
		}

		@Test
		@DisplayName("the roof is covered except for the cap")
		void roofIsWholeExceptTheCap()
		{
			//The burial check walks the roof and skips the cap. If any roof cell were not a shell
			//block the check would silently accept an uncovered hole in the top of the tank.
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				int roof = 0;
				for(int l = 0; l < tier.depth; l++)
					for(int w = 0; w < tier.width; w++)
					{
						assertTrue(tier.isRoof(tier.height-1, l, w),
								tier.name+" roof gap at "+l+","+w);
						roof++;
					}
				assertEquals(tier.depth*tier.width, roof, tier.name);
			}
		}
	}

	@Nested
	@DisplayName("tier separation")
	class Tiers
	{
		@Test
		@DisplayName("a smaller tier's footprint cannot match inside a bigger one")
		void hollowInteriorsKeepTheTiersApart()
		{
			//All three tanks are the same shape and are triggered by the same cap block, so the
			//only thing stopping a bulk depot's roof from also reading as a domestic tank is that
			//the cell under the cap is interior on the bigger tiers. If that ever stopped being
			//true, hammering a depot could form the wrong structure and eat two hundred blocks.
			for(Tier tier : new Tier[]{BuriedTankGeometry.COMMERCIAL, BuriedTankGeometry.BULK})
				assertFalse(tier.isPart(tier.height-2, tier.depth/2, tier.width/2),
						tier.name+" has shell directly beneath its cap");
		}

		@Test
		@DisplayName("capacity per block rises with the tier")
		void biggerIsMoreEfficient()
		{
			//The reason to dig the bigger hole. Without this the tiers would be three ways to do
			//the same thing and a player would rationally build the small one every time.
			double domestic = perBlock(BuriedTankGeometry.DOMESTIC);
			double commercial = perBlock(BuriedTankGeometry.COMMERCIAL);
			double bulk = perBlock(BuriedTankGeometry.BULK);
			assertTrue(commercial > domestic,
					"commercial ("+commercial+") should beat domestic ("+domestic+")");
			assertTrue(bulk > commercial,
					"bulk ("+bulk+") should beat commercial ("+commercial+")");
		}

		private double perBlock(Tier tier)
		{
			return tier.capacity/(double)tier.blockCount();
		}

		@Test
		@DisplayName("every tier holds more than the one below it")
		void capacitiesAscend()
		{
			for(int i = 1; i < BuriedTankGeometry.TIERS.length; i++)
				assertTrue(BuriedTankGeometry.TIERS[i].capacity > BuriedTankGeometry.TIERS[i-1].capacity,
						BuriedTankGeometry.TIERS[i].name);
		}
	}

	@Nested
	@DisplayName("the gauge")
	class Gauge
	{
		@Test
		@DisplayName("empty is its own reading")
		void emptyIsDistinct()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				assertEquals(0, BuriedTankGeometry.divisionOf(0, tier.capacity), tier.name);
				assertNotEquals(0, BuriedTankGeometry.divisionOf(1, tier.capacity),
						tier.name+": one millibucket must not read as empty");
			}
		}

		@Test
		@DisplayName("a full tank reads full and nothing reads past it")
		void fullIsTheTop()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				assertEquals(BuriedTankGeometry.GAUGE_DIVISIONS,
						BuriedTankGeometry.divisionOf(tier.capacity, tier.capacity), tier.name);
				//The depot's capacity times sixty-four overflows an int. If that arithmetic is ever
				//done in ints again this comes back negative, and the gauge runs backwards.
				assertTrue(BuriedTankGeometry.divisionOf(tier.capacity-1, tier.capacity) > 0,
						tier.name+": a nearly-full tank read as empty or negative");
			}
		}

		@Test
		@DisplayName("the reading never falls as the tank fills")
		void readingIsMonotonic()
		{
			for(Tier tier : BuriedTankGeometry.TIERS)
			{
				int previous = -1;
				//Sampled rather than exhaustive: four million steps per tier is not worth the
				//seconds, and a monotonicity break would have to hide between two adjacent samples
				//of a function that is one division.
				for(int step = 0; step <= 512; step++)
				{
					int amount = (int)((long)tier.capacity*step/512);
					int division = BuriedTankGeometry.divisionOf(amount, tier.capacity);
					assertTrue(division >= previous,
							tier.name+" fell from "+previous+" to "+division+" at "+amount+" mB");
					assertTrue(division <= BuriedTankGeometry.GAUGE_DIVISIONS,
							tier.name+" read past full at "+amount+" mB");
					previous = division;
				}
			}
		}

		@Test
		@DisplayName("a zero-capacity tank does not divide by zero")
		void zeroCapacityIsSafe()
		{
			assertEquals(0, BuriedTankGeometry.divisionOf(0, 0));
			assertEquals(BuriedTankGeometry.GAUGE_DIVISIONS, BuriedTankGeometry.divisionOf(1, 0));
		}
	}
}
