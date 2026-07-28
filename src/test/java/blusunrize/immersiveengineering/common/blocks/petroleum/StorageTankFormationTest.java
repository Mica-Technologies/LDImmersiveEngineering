/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Working out the shape of a free-form tank from one block of it.
 * <p>
 * The interesting cases are all failures. A tank that forms when it should is obvious the moment
 * you hammer it; a tank that forms when it <em>should not</em> -- a solid cube, a box with a hole,
 * two tanks sharing a wall -- gives the player capacity they did not build, and there is nothing on
 * screen to say so.
 */
class StorageTankFormationTest
{
	private static class Shape implements StorageTankFormation.Probe
	{
		private final Set<BlockPos> blocks = new HashSet<>();

		Shape shell(BlockPos origin, int w, int h, int d)
		{
			for(int x = 0; x < w; x++)
				for(int y = 0; y < h; y++)
					for(int z = 0; z < d; z++)
						if(StorageTankGeometry.isShell(x, y, z, w, h, d))
							blocks.add(origin.add(x, y, z));
			return this;
		}

		Shape solid(BlockPos origin, int w, int h, int d)
		{
			for(int x = 0; x < w; x++)
				for(int y = 0; y < h; y++)
					for(int z = 0; z < d; z++)
						blocks.add(origin.add(x, y, z));
			return this;
		}

		Shape remove(BlockPos pos)
		{
			blocks.remove(pos);
			return this;
		}

		@Override
		public boolean isFreeTankBlock(BlockPos pos)
		{
			return blocks.contains(pos);
		}
	}

	private static final BlockPos O = new BlockPos(10, 64, -30);
	private Shape shape;

	@BeforeEach
	void setUp()
	{
		shape = new Shape();
	}

	@Nested
	@DisplayName("shapes that form")
	class Forms
	{
		@Test
		@DisplayName("a cube forms and reports its size")
		void cubeForms()
		{
			shape.shell(O, 5, 5, 5);
			StorageTankFormation.Found found = StorageTankFormation.find(O, shape);
			assertNotNull(found);
			assertEquals(O, found.origin);
			assertEquals(5, found.width);
			assertEquals(5, found.height);
			assertEquals(5, found.depth);
			assertEquals(StorageTankGeometry.capacity(5, 5, 5), found.capacity());
		}

		@Test
		@DisplayName("an oblong forms with the right dimensions the right way round")
		void oblongForms()
		{
			//Getting an axis crossed here would be invisible on a cube and wrong on everything else.
			shape.shell(O, 4, 7, 12);
			StorageTankFormation.Found found = StorageTankFormation.find(O, shape);
			assertNotNull(found);
			assertEquals(4, found.width);
			assertEquals(7, found.height);
			assertEquals(12, found.depth);
		}

		@Test
		@DisplayName("hammering any block of it finds the same tank")
		void anyBlockFindsIt()
		{
			//A player hammers whichever face they are standing at. A corner, an edge and the middle
			//of a face all have to reach the same answer or the block would feel broken.
			shape.shell(O, 5, 6, 7);
			for(int x = 0; x < 5; x++)
				for(int y = 0; y < 6; y++)
					for(int z = 0; z < 7; z++)
					{
						if(!StorageTankGeometry.isShell(x, y, z, 5, 6, 7))
							continue;
						BlockPos struck = O.add(x, y, z);
						StorageTankFormation.Found found = StorageTankFormation.find(struck, shape);
						assertNotNull(found, "striking "+struck+" found nothing");
						assertEquals(O, found.origin, "striking "+struck+" found a different tank");
						assertEquals(5, found.width);
						assertEquals(6, found.height);
						assertEquals(7, found.depth);
					}
		}

		@Test
		@DisplayName("the smallest legal tank forms")
		void smallestForms()
		{
			shape.shell(O, 3, 3, 3);
			assertNotNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("the largest legal tank forms")
		void largestForms()
		{
			shape.shell(O, 16, 16, 16);
			StorageTankFormation.Found found = StorageTankFormation.find(O, shape);
			assertNotNull(found);
			assertEquals(16, found.width);
		}
	}

	@Nested
	@DisplayName("shapes that must not form")
	class Refusals
	{
		@Test
		@DisplayName("a solid cube does not form")
		void solidDoesNotForm()
		{
			//The failure with teeth: a player who filled the inside in would otherwise get the
			//capacity of a box that has no inside.
			shape.solid(O, 5, 5, 5);
			assertNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("a shell with one block missing does not form")
		void holedShellDoesNotForm()
		{
			shape.shell(O, 5, 5, 5).remove(O.add(2, 0, 2));
			assertNull(StorageTankFormation.find(O.add(0, 0, 0), shape));
		}

		@Test
		@DisplayName("a shell with one block inside it does not form")
		void strayInnerBlockDoesNotForm()
		{
			//Half-filled is not a tank. Refusing it beats forming a tank whose stated capacity
			//counts a cell that has a block sitting in it.
			shape.shell(O, 5, 5, 5);
			shape.blocks.add(O.add(2, 2, 2));
			assertNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("anything smaller than three on a side does not form")
		void tooSmallDoesNotForm()
		{
			shape.solid(O, 2, 2, 2);
			assertNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("a single block does not form")
		void loneBlockDoesNotForm()
		{
			shape.blocks.add(O);
			assertNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("a flat plate does not form")
		void plateDoesNotForm()
		{
			//One block thick in an axis, so there is no inside at all.
			shape.solid(O, 5, 1, 5);
			assertNull(StorageTankFormation.find(O, shape));
		}

		@Test
		@DisplayName("striking something that is not a tank block finds nothing")
		void emptySpaceFindsNothing()
		{
			shape.shell(O, 5, 5, 5);
			assertNull(StorageTankFormation.find(O.add(-5, 0, 0), shape));
		}

		@Test
		@DisplayName("a shell longer than the limit does not form")
		void oversizedDoesNotForm()
		{
			//The bound exists so the search terminates. A player who builds past it should be told
			//by the tank not forming, rather than by the server hanging.
			shape.shell(O, 20, 5, 5);
			assertNull(StorageTankFormation.find(O, shape));
		}
	}

	@Nested
	@DisplayName("tanks next to each other")
	class Neighbours
	{
		@Test
		@DisplayName("two tanks touching wall to wall stay two tanks")
		void adjacentTanksDoNotMerge()
		{
			//Both are complete shells, so the growth step runs straight through the shared face.
			//The shell check is what refuses the combined box: its inside is not empty.
			shape.shell(O, 5, 5, 5);
			shape.shell(O.add(4, 0, 0), 5, 5, 5);
			//Struck on the far wall of the first tank, well away from the join.
			StorageTankFormation.Found found = StorageTankFormation.find(O, shape);
			assertNull(found, "two touching tanks merged into one impossible box");
		}

		@Test
		@DisplayName("two tanks with a gap between them each form")
		void separatedTanksEachForm()
		{
			shape.shell(O, 5, 5, 5);
			shape.shell(O.add(6, 0, 0), 5, 5, 5);
			assertNotNull(StorageTankFormation.find(O, shape));
			assertNotNull(StorageTankFormation.find(O.add(6, 0, 0), shape));
		}

		@Test
		@DisplayName("a block already in a tank is not free to join another")
		void claimedBlocksAreNotFree()
		{
			//The probe reports only *unformed* blocks, which is what keeps a second tank from
			//being built through the wall of an existing one.
			shape.shell(O, 5, 5, 5);
			StorageTankFormation.Probe claimed = pos -> false;
			assertNull(StorageTankFormation.find(O, claimed));
		}
	}
}
