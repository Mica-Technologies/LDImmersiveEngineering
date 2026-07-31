/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deciding what a ground feeder should wear.
 * <p>
 * The rule is a scoring function over the blocks around it, and the reason it is worth testing at
 * this length is that every way of getting it wrong looks the same from inside the code and
 * completely different from inside the game. A feeder wearing dirt in the middle of a lawn is not a
 * crash, is not logged, and is exactly what the obvious implementation does.
 * <p>
 * The world is drawn out of strings. {@link ConduitCamouflage} takes candidates as opaque tokens for
 * this reason -- nothing here needs Minecraft to be running.
 */
class ConduitCamouflageTest
{
	/** A patch of ground drawn a block at a time. Anything not placed is air. */
	private static class Ground implements ConduitCamouflage.Neighbourhood<String>
	{
		private final Map<BlockPos, String> blocks = new HashMap<>();
		/** Positions that are not to count as full opaque cubes -- air, and anything unwearable. */
		private final Set<BlockPos> unwearable = new HashSet<>();

		Ground put(int x, int y, int z, String block)
		{
			blocks.put(new BlockPos(x, y, z), block);
			return this;
		}

		/** A block that is there -- so it hides what is behind it -- but that a feeder cannot wear. */
		Ground obstacle(int x, int y, int z, String block)
		{
			blocks.put(new BlockPos(x, y, z), block);
			unwearable.add(new BlockPos(x, y, z));
			return this;
		}

		/** Fill a solid slab, the way real terrain arrives. */
		Ground slab(int y, int radius, String block)
		{
			for(int x = -radius; x <= radius; x++)
				for(int z = -radius; z <= radius; z++)
					put(x, y, z, block);
			return this;
		}

		@Nullable
		@Override
		public String candidateAt(BlockPos pos)
		{
			return unwearable.contains(pos)?null: blocks.get(pos);
		}

		@Override
		public boolean isExposed(BlockPos pos)
		{
			//Exposed means "some face of it touches something that is not a solid block", so here:
			//touches a position nothing was drawn at.
			for(EnumFacing face : EnumFacing.VALUES)
			{
				BlockPos beside = pos.offset(face);
				//The feeder itself is a full opaque cube, so it hides its neighbours rather than
				//exposing them. Getting this wrong would make the dirt under a lawn count as visible
				//through the very hole the feeder is filling, and the lawn case would pass by luck.
				if(beside.equals(BlockPos.ORIGIN))
					continue;
				if(!blocks.containsKey(beside))
					return true;
			}
			return false;
		}
	}

	private Ground ground;

	@BeforeEach
	void setUp()
	{
		ground = new Ground();
	}

	private String choose()
	{
		return ConduitCamouflage.choose(BlockPos.ORIGIN, ground);
	}

	@Nested
	@DisplayName("the case the whole rule exists for")
	class TheLawn
	{
		/**
		 * A feeder set into the surface of a grass field: a ring of grass at its own level, solid
		 * dirt underneath, open sky above.
		 */
		private void lawn()
		{
			ground.slab(0, 2, "grass");
			ground.slab(-1, 2, "dirt");
			ground.slab(-2, 2, "dirt");
		}

		@Test
		@DisplayName("a feeder in a lawn wears grass, not the dirt underneath it")
		void lawnWearsGrass()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//
			// Counting neighbours plainly gives dirt: eight grass blocks around the feeder against
			// nine dirt blocks below it. Dirt is genuinely the commonest block nearby and is
			// completely the wrong answer, because none of it can be seen. This is the whole reason
			// the score weights exposure rather than counting.
			lawn();
			assertEquals("grass", choose(),
					"a feeder buried in a lawn came out dirt -- the survey is counting blocks "
							+"instead of counting what is visible");
		}

		@Test
		@DisplayName("a plain count really would have said dirt")
		void thePlainCountWouldBeWrong()
		{
			//Pinned down rather than asserted in a comment, so that if somebody later simplifies the
			//scoring back to a count they see this fail with the reason attached.
			lawn();
			Map<String, Integer> tally = new HashMap<>();
			for(int dy = -1; dy <= 1; dy++)
				for(int dz = -1; dz <= 1; dz++)
					for(int dx = -1; dx <= 1; dx++)
					{
						//The feeder's own block is not part of its surroundings.
						if(dx==0&&dy==0&&dz==0)
							continue;
						String at = ground.candidateAt(new BlockPos(dx, dy, dz));
						if(at!=null)
							tally.merge(at, 1, Integer::sum);
					}
			assertEquals(9, tally.get("dirt").intValue());
			assertEquals(8, tally.get("grass").intValue());
		}

		@Test
		@DisplayName("a feeder deep underground wears the stone around it")
		void buriedWearsStone()
		{
			//The other side of the same rule: down here the stone is what is exposed to the shaft, so
			//weighting exposure does not stop the buried case working.
			for(int y = -2; y <= 2; y++)
				ground.slab(y, 2, "stone");
			assertEquals("stone", choose());
		}
	}

	@Nested
	@DisplayName("what it refuses to wear")
	class Refusals
	{
		@Test
		@DisplayName("nothing around means nothing worn")
		void emptyAirWearsNothing()
		{
			assertNull(choose(), "a feeder in mid-air should stay bare rather than invent a disguise");
		}

		@Test
		@DisplayName("blocks it cannot wear are not counted, however many there are")
		void unwearableBlocksDoNotWin()
		{
			//Chests, fences, fluids: they are there, they hide what is behind them, and a feeder
			//cannot pass for one. The rule must not pick the commonest block it has been told to
			//ignore.
			for(int x = -1; x <= 1; x++)
				for(int z = -1; z <= 1; z++)
					ground.obstacle(x, -1, z, "chest");
			ground.put(1, 0, 0, "stone");
			assertEquals("stone", choose());
		}
	}

	@Nested
	@DisplayName("the weighting")
	class Weighting
	{
		@Test
		@DisplayName("a block you touch counts for more than one meeting you at a corner")
		void touchingBeatsDiagonal()
		{
			//One face-neighbour against one corner-neighbour, both equally exposed.
			ground.put(1, 0, 0, "stone");
			ground.put(1, 1, 1, "planks");
			assertEquals("stone", choose());
		}

		@Test
		@DisplayName("a visible block counts for more than a buried one")
		void exposedBeatsBuried()
		{
			//One exposed corner-neighbour against one buried face-neighbour. Exposure has to be
			//worth more than adjacency or the lawn comes out dirt, so this is that ordering on its
			//own, with everything else held equal.
			ground.put(1, 1, 1, "grass");
			ground.put(0, -1, 0, "dirt");
			//Bury the dirt behind blocks a feeder cannot wear, so that walling it in does not also
			//stuff the survey with candidates and decide the test by weight of numbers.
			ground.obstacle(0, -2, 0, "bedrock");
			ground.obstacle(1, -1, 0, "bedrock");
			ground.obstacle(-1, -1, 0, "bedrock");
			ground.obstacle(0, -1, 1, "bedrock");
			ground.obstacle(0, -1, -1, "bedrock");

			assertFalse(ground.isExposed(new BlockPos(0, -1, 0)),
					"the test's own setup is wrong: the dirt below is supposed to be buried");
			assertTrue(ground.isExposed(new BlockPos(1, 1, 1)),
					"the test's own setup is wrong: the grass above is supposed to be visible");
			assertEquals("grass", choose());
		}

		@Test
		@DisplayName("the same surroundings always give the same answer")
		void tiesAreStable()
		{
			//A feeder that redecorated itself whenever a neighbour changed would be worse than one
			//that guessed wrong: wrong is at least something a right-click can pin.
			ground.put(1, 0, 0, "stone");
			ground.put(-1, 0, 0, "planks");
			String first = choose();
			for(int i = 0; i < 20; i++)
				assertEquals(first, choose());
		}
	}
}
