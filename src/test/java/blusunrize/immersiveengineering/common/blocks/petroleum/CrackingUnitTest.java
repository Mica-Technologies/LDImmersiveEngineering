/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockCrackingUnit;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityCrackingUnit.Cracking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Cracking Unit's arithmetic and shape.
 * <p>
 * This machine's whole point is that the split between its two products moves with how hard its
 * firebox is driving it. If that relationship is backwards, or flat, or silently clamped, the
 * machine still runs and still makes fuel -- it just stops being a decision, and nothing in game
 * would say so. That is exactly the class of bug worth a test.
 */
class CrackingUnitTest
{
	private static final String HFO = "ie_heavy_fuel_oil";
	private static final String NAPHTHA = "ie_naphtha";

	@Nested
	@DisplayName("the severity dial")
	class Severity
	{
		@Test
		@DisplayName("too little heat is refused rather than cracked badly")
		void belowColdIsRefused()
		{
			//A machine producing a trickle of the wrong thing is harder to diagnose than one that
			//has stopped, so the cold end is a threshold and not a taper.
			int volume = 1000;
			int cold = TileEntityCrackingUnit.HEAT_PER_BUCKET_COLD;
			assertEquals(-1, TileEntityCrackingUnit.severityFor(cold-1, volume));
			assertEquals(0, TileEntityCrackingUnit.severityFor(cold, volume));
		}

		@Test
		@DisplayName("the full hot rate reads as full severity, and nothing reads past it")
		void hotEndSaturates()
		{
			int volume = 1000;
			int hot = TileEntityCrackingUnit.HEAT_PER_BUCKET_HOT;
			assertEquals(100, TileEntityCrackingUnit.severityFor(hot, volume));
			assertEquals(100, TileEntityCrackingUnit.severityFor(hot*4, volume),
					"a hotter firebox than the machine can use must not overflow the dial");
		}

		@Test
		@DisplayName("severity climbs with heat and never falls")
		void severityIsMonotonic()
		{
			int volume = 4000;
			int previous = -2;
			int hot = (int)((long)volume*TileEntityCrackingUnit.HEAT_PER_BUCKET_HOT/1000L);
			for(int granted = 0; granted <= hot*2; granted += hot/64+1)
			{
				int severity = TileEntityCrackingUnit.severityFor(granted, volume);
				assertTrue(severity >= previous,
						"severity fell from "+previous+" to "+severity+" at "+granted+" HU");
				assertTrue(severity <= 100, "severity ran past 100 at "+granted+" HU");
				previous = severity;
			}
		}

		@Test
		@DisplayName("a deep pass does not overflow the heat arithmetic")
		void deepPassDoesNotOverflow()
		{
			//A full tank times the hot rate overflows an int. If that is ever done in ints again
			//the negative reads as "no heat" on a machine with a roaring firebox against it.
			int volume = TileEntityCrackingUnit.TANK_CAPACITY;
			int hot = (int)((long)volume*TileEntityCrackingUnit.HEAT_PER_BUCKET_HOT/1000L);
			assertEquals(100, TileEntityCrackingUnit.severityFor(hot, volume));
			assertTrue(TileEntityCrackingUnit.heatCost(volume, 100) > 0,
					"the cost of a full pass came back non-positive");
		}

		@Test
		@DisplayName("what a run is charged matches the severity it ran at")
		void costTracksSeverity()
		{
			int volume = 2000;
			int cold = TileEntityCrackingUnit.heatCost(volume, 0);
			int hot = TileEntityCrackingUnit.heatCost(volume, 100);
			assertEquals((int)((long)volume*TileEntityCrackingUnit.HEAT_PER_BUCKET_COLD/1000L), cold);
			assertEquals((int)((long)volume*TileEntityCrackingUnit.HEAT_PER_BUCKET_HOT/1000L), hot);
			assertTrue(hot > cold, "a hot run should cost more than a cold one");
			int middle = TileEntityCrackingUnit.heatCost(volume, 50);
			assertTrue(middle > cold&&middle < hot);
		}
	}

	@Nested
	@DisplayName("the yields")
	class Yields
	{
		@Test
		@DisplayName("hotter means more gasoline and less diesel, for every feed")
		void severityShiftsTheSplit()
		{
			//This is the machine. If it ever stops being true, the cracker is a second refinery
			//with extra steps.
			for(String feed : new String[]{HFO, NAPHTHA})
			{
				Cracking recipe = TileEntityCrackingUnit.getCracking(feed);
				assertNotNull(recipe, feed+" is not registered");
				assertTrue(recipe.lightFrom(1000, 100) > recipe.lightFrom(1000, 0),
						feed+": a hot run should yield more gasoline than a cold one");
				assertTrue(recipe.heavyFrom(1000, 100) < recipe.heavyFrom(1000, 0),
						feed+": a hot run should yield less diesel than a cold one");
			}
		}

		@Test
		@DisplayName("nothing yields more product than it was fed")
		void noFreeVolume()
		{
			for(String feed : new String[]{HFO, NAPHTHA})
			{
				Cracking recipe = TileEntityCrackingUnit.getCracking(feed);
				for(int severity = 0; severity <= 100; severity += 5)
				{
					int total = recipe.lightFrom(1000, severity)+recipe.heavyFrom(1000, severity);
					assertTrue(total <= 1000,
							feed+" at severity "+severity+" produced "+total+" mB from 1000");
					assertTrue(total > 0, feed+" at severity "+severity+" produced nothing at all");
				}
			}
		}

		@Test
		@DisplayName("naphtha is the gasoline feed and heavy fuel oil is the diesel one")
		void feedsHaveDistinctCharacters()
		{
			//Two feeds that behaved the same way would make one of them pointless. Naphtha starts
			//light, so even a cold run favours gasoline; HFO starts heavy, so even a hot one yields
			//a serious amount of diesel.
			Cracking hfo = TileEntityCrackingUnit.getCracking(HFO);
			Cracking naphtha = TileEntityCrackingUnit.getCracking(NAPHTHA);
			assertTrue(naphtha.lightFrom(1000, 0) > hfo.lightFrom(1000, 0),
					"cold naphtha should beat cold HFO on gasoline");
			assertTrue(hfo.heavyFrom(1000, 100) > naphtha.heavyFrom(1000, 100),
					"hot HFO should beat hot naphtha on diesel");
		}

		@Test
		@DisplayName("only the heavy feed makes coke")
		void cokeComesFromTheHeavyFeed()
		{
			assertTrue(TileEntityCrackingUnit.getCracking(HFO).inputPerCoke > 0);
			assertEquals(0, TileEntityCrackingUnit.getCracking(NAPHTHA).inputPerCoke,
					"there is nothing heavy enough left in naphtha to coke out");
		}

		@Test
		@DisplayName("a pass is sized so both products fit")
		void roomCheckCoversBothProducts()
		{
			//A run that filled the gasoline tank and then had nowhere to put the diesel would have
			//to destroy one of them, and a refinery that quietly bins a cut is the most expensive
			//kind of bug to notice.
			Cracking recipe = TileEntityCrackingUnit.getCracking(HFO);
			for(int severity = 0; severity <= 100; severity += 25)
			{
				int volume = recipe.volumeForRoom(1000, 100, severity);
				assertTrue(recipe.lightFrom(volume, severity) <= 1000,
						"light product overflowed at severity "+severity);
				assertTrue(recipe.heavyFrom(volume, severity) <= 100,
						"heavy product overflowed at severity "+severity);
			}
		}

		@Test
		@DisplayName("no room means no run")
		void noRoomMeansNoRun()
		{
			Cracking recipe = TileEntityCrackingUnit.getCracking(HFO);
			assertEquals(0, recipe.volumeForRoom(0, 0, 50));
			assertEquals(0, recipe.volumeForRoom(-5, -5, 50), "negative room must not read as room");
		}
	}

	@Nested
	@DisplayName("the ports")
	class Ports
	{
		@Test
		@DisplayName("the two products never share a face")
		void productFacesAreDisjoint()
		{
			//The wellhead shipped two fluids on one face once, and the separation was
			//unenforceable: one pipe took whichever arrived first.
			int cells = PetroleumGeometry.CRACKER_HEIGHT*PetroleumGeometry.CRACKER_DEPTH
					*PetroleumGeometry.CRACKER_WIDTH;
			for(int pos = 0; pos < cells; pos++)
				assertFalse(MultiblockCrackingUnit.isLightPort(pos)
								&&MultiblockCrackingUnit.isHeavyPort(pos),
						"structure index "+pos+" carries both products");
		}

		@Test
		@DisplayName("feed goes in at the bottom and products come out at the top")
		void portsAreAtTheRightHeights()
		{
			int width = PetroleumGeometry.CRACKER_WIDTH;
			int depth = PetroleumGeometry.CRACKER_DEPTH;
			for(int w = 0; w < width; w++)
			{
				assertTrue(MultiblockCrackingUnit.isFeedPort(
						PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE, 0, 0, w)));
				assertTrue(MultiblockCrackingUnit.isLightPort(
						PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE,
								PetroleumGeometry.CRACKER_HEIGHT-1, 0, w)));
				assertTrue(MultiblockCrackingUnit.isHeavyPort(
						PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE,
								PetroleumGeometry.CRACKER_HEIGHT-1, depth-1, w)));
			}
		}

		@Test
		@DisplayName("the middle course connects to nothing")
		void theShellIsInert()
		{
			//A pipe run climbing past the reactors must not pick up a stream on the way.
			for(int h = 1; h < PetroleumGeometry.CRACKER_HEIGHT-1; h++)
				for(int l = 0; l < PetroleumGeometry.CRACKER_DEPTH; l++)
					for(int w = 0; w < PetroleumGeometry.CRACKER_WIDTH; w++)
					{
						int pos = PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE, h, l, w);
						assertFalse(MultiblockCrackingUnit.isFeedPort(pos)
										||MultiblockCrackingUnit.isLightPort(pos)
										||MultiblockCrackingUnit.isHeavyPort(pos),
								"the shell at "+h+","+l+","+w+" is a port");
					}
		}

		@Test
		@DisplayName("the middle row of the head is deliberately dead")
		void theHeadHasAnInertRow()
		{
			int middle = PetroleumGeometry.CRACKER_DEPTH/2;
			for(int w = 0; w < PetroleumGeometry.CRACKER_WIDTH; w++)
			{
				int pos = PetroleumGeometry.structureIndex(PetroleumGeometry.CRACKER_SIZE,
						PetroleumGeometry.CRACKER_HEIGHT-1, middle, w);
				assertFalse(MultiblockCrackingUnit.isLightPort(pos)
								||MultiblockCrackingUnit.isHeavyPort(pos),
						"the head's middle row should separate the two products, not carry one");
			}
		}
	}

	@Nested
	@DisplayName("the shape")
	class Shape
	{
		@Test
		@DisplayName("the deck is solid and the head is complete")
		void decksAndHeadsAreWhole()
		{
			for(int l = 0; l < PetroleumGeometry.CRACKER_DEPTH; l++)
				for(int w = 0; w < PetroleumGeometry.CRACKER_WIDTH; w++)
				{
					assertTrue(MultiblockCrackingUnit.isPart(0, l, w), "deck gap at "+l+","+w);
					assertTrue(MultiblockCrackingUnit.isPart(PetroleumGeometry.CRACKER_HEIGHT-1, l, w),
							"head gap at "+l+","+w);
				}
		}

		@Test
		@DisplayName("the coke drum runs the full height between deck and head")
		void theDrumIsContinuous()
		{
			int middle = PetroleumGeometry.CRACKER_WIDTH/2;
			for(int h = 1; h < PetroleumGeometry.CRACKER_HEIGHT-1; h++)
				for(int l = 0; l < PetroleumGeometry.CRACKER_DEPTH; l++)
					assertEquals(MultiblockCrackingUnit.FRAME,
							MultiblockCrackingUnit.shapeAt(h, l, middle),
							"the drum is broken at height "+h);
		}

		@Test
		@DisplayName("the material counts are what the manual will claim")
		void materialCounts()
		{
			assertEquals(15, MultiblockCrackingUnit.blockCount(MultiblockCrackingUnit.DECK));
			assertEquals(24, MultiblockCrackingUnit.blockCount(MultiblockCrackingUnit.VESSEL));
			//The head is fifteen, plus twelve of coke drum.
			assertEquals(27, MultiblockCrackingUnit.blockCount(MultiblockCrackingUnit.FRAME));
		}
	}
}
