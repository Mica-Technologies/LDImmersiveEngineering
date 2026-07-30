/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which blocks the Crawler's Breaker takes, and in what order.
 * <p>
 * This machine deletes buildings. The arithmetic choosing what it deletes is not the part to leave
 * uncovered, and it is also the only part that <em>can</em> be covered -- removing a block needs a
 * world, so the entity's half is a dozen lines with no decisions in it and this half has all of them.
 */
class CrawlerDemolitionTest
{
	@Nested
	@DisplayName("the hardness limit")
	class Hardness
	{
		@Test
		@DisplayName("ordinary building materials come apart")
		void takesTheEasyThings()
		{
			assertTrue(CrawlerDemolition.withinHardnessLimit(0F), "grass and flowers");
			assertTrue(CrawlerDemolition.withinHardnessLimit(1.5F), "stone");
			assertTrue(CrawlerDemolition.withinHardnessLimit(2F), "wood");
			assertTrue(CrawlerDemolition.withinHardnessLimit(5F), "iron blocks and the like");
		}

		@Test
		@DisplayName("obsidian is the ceiling, exactly")
		void obsidianIsTheLimit()
		{
			//The rule chosen was "anything a diamond pick could take", and that ends at obsidian.
			assertTrue(CrawlerDemolition.withinHardnessLimit(50F), "obsidian should be breakable");
			assertFalse(CrawlerDemolition.withinHardnessLimit(50.01F), "past obsidian should not be");
		}

		@Test
		@DisplayName("unbreakable blocks are refused rather than compared")
		void bedrockIsRefused()
		{
			//	=================================
			//	The trap in a naive ceiling check.
			//	=================================
			//
			// Bedrock reports -1, and -1 is less than fifty. A limit written as a single "at most"
			// comparison therefore lets the machine dig straight through the floor of the world -- and
			// past the bottom of a superflat, out of it entirely.
			assertFalse(CrawlerDemolition.withinHardnessLimit(-1F), "bedrock");
			assertFalse(CrawlerDemolition.withinHardnessLimit(-100F), "anything else unbreakable");
		}
	}

	@Nested
	@DisplayName("choosing what to bite")
	class Targets
	{
		private List<BlockPos> around(double x, double y, double z, int limit)
		{
			return CrawlerDemolition.targetsAround(x, y, z, CrawlerDemolition.REACH, limit);
		}

		@Test
		@DisplayName("it never returns more than the budget")
		void respectsTheBudget()
		{
			//The whole restraint on this machine. Unbudgeted, an arm sweeping through a wall takes every
			//block it passes, every tick -- hundreds a second.
			for(int limit = 1; limit <= 12; limit++)
				assertTrue(around(8.5, 64.5, 8.5, limit).size() <= limit,
						"asked for at most "+limit+" and got more");
		}

		@Test
		@DisplayName("a budget of nothing takes nothing")
		void zeroBudgetTakesNothing()
		{
			assertTrue(around(8.5, 64.5, 8.5, 0).isEmpty());
			assertTrue(CrawlerDemolition.targetsAround(8.5, 64.5, 8.5, 0, 5).isEmpty(),
					"a zero reach still found something to break");
		}

		@Test
		@DisplayName("nearest first, so it eats into a face instead of peppering it")
		void nearestFirst()
		{
			//	=================================
			//	Why the order matters at all.
			//	=================================
			//
			// The budget is three. Three arbitrary blocks from around the bucket punches a random
			// pattern of holes through a wall; the three nearest eat into it from the face the tool is
			// pressed against. Same number of blocks, and only one of them looks like a machine.
			List<BlockPos> targets = CrawlerDemolition.targetsAround(8.2, 64.5, 8.5, 2.5, 40);
			double previous = -1;
			for(BlockPos pos : targets)
			{
				double dx = pos.getX()+0.5-8.2, dy = pos.getY()+0.5-64.5, dz = pos.getZ()+0.5-8.5;
				double distance = dx*dx+dy*dy+dz*dz;
				assertTrue(distance >= previous-1e-9, "the list was not sorted by distance");
				previous = distance;
			}
		}

		@Test
		@DisplayName("everything it returns is actually within reach")
		void staysWithinReach()
		{
			double reach = 1.9;
			for(BlockPos pos : CrawlerDemolition.targetsAround(3.3, 70.8, -4.2, reach, 500))
			{
				double dx = pos.getX()+0.5-3.3, dy = pos.getY()+0.5-70.8, dz = pos.getZ()+0.5+4.2;
				assertTrue(Math.sqrt(dx*dx+dy*dy+dz*dz) <= reach+1e-9,
						pos+" was outside the reach it was found with");
			}
		}

		@Test
		@DisplayName("it works in negative coordinates too")
		void handlesNegativeCoordinates()
		{
			//Floor, not cast: (int)(-4.2) is -4 and floor(-4.2) is -5, and the difference is a machine
			//that digs one block off from its own bucket everywhere west or north of the origin.
			List<BlockPos> targets = CrawlerDemolition.targetsAround(-4.2, 70.5, -8.7, 1.0, 20);
			assertFalse(targets.isEmpty(), "found nothing at all in negative coordinates");
			assertTrue(targets.contains(new BlockPos(-5, 70, -9)),
					"the block the tool is actually inside was not in the list: "+targets);
		}

		@Test
		@DisplayName("the same bite twice takes the same blocks")
		void isDeterministic()
		{
			//An unstable order would make the machine's behaviour unrepeatable and this test
			//meaningless. Ties are broken by coordinate for exactly that reason.
			List<BlockPos> first = around(8.5, 64.5, 8.5, 5);
			List<BlockPos> second = around(8.5, 64.5, 8.5, 5);
			assertEquals(first, second, "two identical bites chose different blocks");
		}

		@Test
		@DisplayName("it never returns the same block twice")
		void noDuplicates()
		{
			//A duplicate would spend budget breaking a block that is already gone, so the machine would
			//quietly bite less than it claims to.
			List<BlockPos> targets = CrawlerDemolition.targetsAround(8.5, 64.5, 8.5, 2.2, 500);
			assertEquals(targets.size(), new HashSet<>(targets).size(), "the list had duplicates");
		}

		@Test
		@DisplayName("the block containing the tool is taken first")
		void startsWhereTheToolIs()
		{
			//Whatever the tool is buried in should be the first thing to go, or the machine appears to
			//break things beside what it is pointing at.
			List<BlockPos> targets = around(8.5, 64.5, 8.5, 1);
			assertEquals(new BlockPos(8, 64, 8), targets.get(0));
		}
	}
}
