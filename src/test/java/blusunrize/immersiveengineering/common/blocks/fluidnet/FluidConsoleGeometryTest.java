/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fluid Control Console's 2x2 detection geometry.
 * <p>
 * Worth testing in isolation because the failure mode is invisible: an off-by-one here means the
 * structure silently refuses to form when hammered, with no message and nothing in the log.
 * <p>
 * The mirror of {@code GridConsoleGeometryTest} -- and the two are asserted to agree, because a
 * player who has built one console should be able to build the other by muscle memory.
 */
class FluidConsoleGeometryTest
{
	@Nested
	@DisplayName("dimensions")
	class Dimensions
	{
		@Test
		@DisplayName("the console is 2 wide, 1 deep and 2 tall")
		void size()
		{
			assertEquals(2, FluidConsoleGeometry.HEIGHT);
			assertEquals(1, FluidConsoleGeometry.DEPTH);
			assertEquals(2, FluidConsoleGeometry.WIDTH);
			assertArrayEquals(new int[]{2, 1, 2}, FluidConsoleGeometry.SIZE,
					"TileEntityMultiblockPart expects H, L, W in that order");
		}

		@Test
		@DisplayName("it is the same shape as the grid's console")
		void matchesTheGridConsole()
		{
			//Deliberate: the two cabinets are the same gesture, and a player who has built one
			//already knows how to build the other.
			assertArrayEquals(
					blusunrize.immersiveengineering.common.blocks.grid.GridConsoleGeometry.SIZE,
					FluidConsoleGeometry.SIZE);
		}

		@Test
		@DisplayName("structure indices are row-major and unique")
		void structureIndicesAreUnique()
		{
			Set<Integer> seen = new HashSet<>();
			for(int h = 0; h < FluidConsoleGeometry.HEIGHT; h++)
				for(int w = 0; w < FluidConsoleGeometry.WIDTH; w++)
					assertTrue(seen.add(FluidConsoleGeometry.structureIndex(h, w)),
							"duplicate index for h="+h+", w="+w);
			assertEquals(4, seen.size());
			assertTrue(seen.containsAll(Arrays.asList(0, 1, 2, 3)),
					"indices must run 0..3 with no gaps -- getBlockPosForPos decodes them directly");
		}

		@Test
		@DisplayName("the upper row is where the screen goes")
		void upperRowIsTheScreen()
		{
			//This drives the blockstate: get it wrong and the console renders its switch bank above
			//its screen.
			assertFalse(FluidConsoleGeometry.isUpperRow(0));
			assertFalse(FluidConsoleGeometry.isUpperRow(1));
			assertTrue(FluidConsoleGeometry.isUpperRow(2));
			assertTrue(FluidConsoleGeometry.isUpperRow(3));
		}
	}

	@Nested
	@DisplayName("cells and origins")
	class Cells
	{
		@Test
		@DisplayName("the four cells are distinct and form a 2x2 wall")
		void cellsFormASquare()
		{
			BlockPos origin = new BlockPos(10, 64, 20);
			BlockPos[] cells = FluidConsoleGeometry.cells(origin, EnumFacing.EAST);
			assertEquals(4, cells.length);
			assertEquals(4, new HashSet<>(Arrays.asList(cells)).size(), "cells must not overlap");
			for(BlockPos cell : cells)
			{
				assertEquals(origin.getZ(), cell.getZ(), "a wall is flat");
				assertTrue(cell.getY()-origin.getY() >= 0&&cell.getY()-origin.getY() < 2);
			}
		}

		@Test
		@DisplayName("every candidate origin puts the clicked block inside the square")
		void candidateOriginsCoverTheClick()
		{
			//The player may hammer any of the four, so each has to be reachable from some origin.
			BlockPos clicked = new BlockPos(3, 70, -8);
			for(EnumFacing right : EnumFacing.HORIZONTALS)
			{
				BlockPos[] origins = FluidConsoleGeometry.candidateOrigins(clicked, right);
				assertEquals(4, origins.length);
				for(BlockPos origin : origins)
					assertTrue(Arrays.asList(FluidConsoleGeometry.cells(origin, right)).contains(clicked),
							"origin "+origin+" does not contain the clicked block for right="+right);
			}
		}

		@Test
		@DisplayName("the candidate origins are distinct")
		void candidatesAreDistinct()
		{
			BlockPos[] origins = FluidConsoleGeometry.candidateOrigins(new BlockPos(0, 64, 0), EnumFacing.NORTH);
			assertEquals(4, new HashSet<>(Arrays.asList(origins)).size(),
					"a repeated origin means one of the four positions can never be hammered");
		}

		@Test
		@DisplayName("negative coordinates behave the same as positive ones")
		void negativeCoordinatesWork()
		{
			//Integer division and offsets are where a sign error hides.
			BlockPos clicked = new BlockPos(-100, 5, -100);
			BlockPos[] origins = FluidConsoleGeometry.candidateOrigins(clicked, EnumFacing.WEST);
			for(BlockPos origin : origins)
				assertTrue(Arrays.asList(FluidConsoleGeometry.cells(origin, EnumFacing.WEST)).contains(clicked));
		}
	}
}
