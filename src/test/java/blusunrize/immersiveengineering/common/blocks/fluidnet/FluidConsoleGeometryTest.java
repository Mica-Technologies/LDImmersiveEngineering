/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.common.blocks.fluidnet.FluidConsoleGeometry.Part;
import blusunrize.immersiveengineering.common.blocks.grid.GridConsoleGeometry;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fluid Control Console's 2x2 detection geometry and its kit of parts.
 * <p>
 * Worth testing in isolation because the failure mode is invisible: an off-by-one here means the
 * structure silently refuses to form when hammered, with no message and nothing in the log.
 * <p>
 * The mirror of {@code GridConsoleGeometryTest} -- and the two are asserted to agree, because a
 * player who has built one console should be able to build the other by muscle memory.
 */
class FluidConsoleGeometryTest
{
	private static Set<BlockPos> setOf(BlockPos... positions)
	{
		return new HashSet<>(Arrays.asList(positions));
	}

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
			assertArrayEquals(GridConsoleGeometry.SIZE, FluidConsoleGeometry.SIZE);
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
			assertEquals(0, FluidConsoleGeometry.structureIndex(0, 0), "the master is index 0");
		}

		/**
		 * The inverse of structureIndex, and it has to agree with the arithmetic
		 * TileEntityMultiblockPart.getBlockPosForPos does on the same number, because that is what
		 * walks the structure when a console is taken apart -- and what the drops are read from.
		 */
		@Test
		@DisplayName("height and width round-trip through the structure index")
		void indexRoundTrips()
		{
			for(int h = 0; h < FluidConsoleGeometry.HEIGHT; h++)
				for(int w = 0; w < FluidConsoleGeometry.WIDTH; w++)
				{
					int index = FluidConsoleGeometry.structureIndex(h, w);
					assertEquals(h, FluidConsoleGeometry.heightOf(index), "height of "+index);
					assertEquals(w, FluidConsoleGeometry.widthOf(index), "width of "+index);
				}
		}
	}

	@Nested
	@DisplayName("the kit of parts")
	class Parts
	{
		/**
		 * One of each, never four of one. Four identical housings is what the recipe used to be on
		 * both consoles, and a duplicate here would quietly bring it back.
		 */
		@Test
		@DisplayName("all four components are used exactly once")
		void everyPartAppearsOnce()
		{
			Set<Part> seen = EnumSet.noneOf(Part.class);
			for(int i = 0; i < FluidConsoleGeometry.HEIGHT*FluidConsoleGeometry.WIDTH; i++)
				assertTrue(seen.add(FluidConsoleGeometry.partAt(i)),
						"part at index "+i+" is a duplicate");
			assertEquals(EnumSet.allOf(Part.class), seen);
		}

		@Test
		@DisplayName("the terminal is the upper left block, where the screen is")
		void terminalIsTopLeft()
		{
			assertEquals(Part.TERMINAL, FluidConsoleGeometry.partAt(1, 0));
			assertEquals(Part.LOGIC, FluidConsoleGeometry.partAt(1, 1));
			assertEquals(Part.DESK, FluidConsoleGeometry.partAt(0, 0));
			assertEquals(Part.POWER, FluidConsoleGeometry.partAt(0, 1));
		}

		@Test
		@DisplayName("the master carries the lower left part")
		void masterIsTheLowerLeft()
		{
			assertEquals(FluidConsoleGeometry.partAt(0, 0), FluidConsoleGeometry.partAt(0));
		}

		/**
		 * The two consoles take the same parts in the same places. Only the terminal differs --
		 * each network has its own housing block -- and that one is the mirror's whole point:
		 * whichever console a player learnt, the other is built the same way round.
		 */
		@Test
		@DisplayName("the layout mirrors the grid console's, cell for cell")
		void layoutMirrorsTheGridConsole()
		{
			List<String> fluid = new ArrayList<>(), grid = new ArrayList<>();
			for(int i = 0; i < FluidConsoleGeometry.HEIGHT*FluidConsoleGeometry.WIDTH; i++)
			{
				fluid.add(FluidConsoleGeometry.partAt(i).name());
				grid.add(GridConsoleGeometry.partAt(i).name());
			}
			assertEquals(grid, fluid);
		}
	}

	@Nested
	@DisplayName("footprint")
	class Footprint
	{
		@Test
		@DisplayName("the four cells are distinct and form a 2x2 wall")
		void cellsFormASquare()
		{
			//facing EAST means the console's face points west and its width runs south.
			BlockPos origin = new BlockPos(10, 64, 20);
			Set<BlockPos> cells = setOf(FluidConsoleGeometry.cells(origin, EnumFacing.EAST));
			assertEquals(4, cells.size(), "four distinct blocks");
			assertEquals(setOf(
					new BlockPos(10, 64, 20), new BlockPos(10, 64, 21),
					new BlockPos(10, 65, 20), new BlockPos(10, 65, 21)), cells);
		}

		/**
		 * The regression that matters most here, and the reason this console had a pass of its own.
		 * TileEntityMultiblockPart walks a formed structure along {@code facing.rotateY()} and skips
		 * anything whose recorded offset does not match; a console laid out the other way round
		 * therefore disassembled only half of itself and left two blocks formed, stuck and
		 * undroppable. The grid console had the identical bug and got the identical fix.
		 */
		@Test
		@DisplayName("width runs along facing.rotateY(), the way the base class walks it")
		void widthFollowsTheMultiblockConvention()
		{
			for(EnumFacing facing : EnumFacing.HORIZONTALS)
			{
				BlockPos origin = new BlockPos(0, 64, 0);
				BlockPos[] cells = FluidConsoleGeometry.cells(origin, facing);
				for(int h = 0; h < FluidConsoleGeometry.HEIGHT; h++)
					for(int w = 0; w < FluidConsoleGeometry.WIDTH; w++)
						assertEquals(origin.offset(facing.rotateY(), w).add(0, h, 0),
								cells[FluidConsoleGeometry.structureIndex(h, w)],
								"facing="+facing+", h="+h+", w="+w);
			}
		}

		@Test
		@DisplayName("the footprint is one block deep for every orientation")
		void footprintIsFlat()
		{
			for(EnumFacing facing : EnumFacing.HORIZONTALS)
			{
				BlockPos origin = new BlockPos(0, 64, 0);
				for(BlockPos cell : FluidConsoleGeometry.cells(origin, facing))
				{
					int along = facing.getXOffset()*(cell.getX()-origin.getX())
							+facing.getZOffset()*(cell.getZ()-origin.getZ());
					assertEquals(0, along,
							"console is 1 deep, but "+cell+" is offset for facing="+facing);
				}
			}
		}
	}

	@Nested
	@DisplayName("cells and origins")
	class Cells
	{
		@Test
		@DisplayName("every candidate origin puts the clicked block inside the square")
		void candidateOriginsCoverTheClick()
		{
			//The player may hammer any of the four, so each has to be reachable from some origin.
			BlockPos clicked = new BlockPos(3, 70, -8);
			for(EnumFacing facing : EnumFacing.HORIZONTALS)
			{
				BlockPos[] origins = FluidConsoleGeometry.candidateOrigins(clicked, facing);
				assertEquals(4, origins.length);
				for(BlockPos origin : origins)
					assertTrue(setOf(FluidConsoleGeometry.cells(origin, facing)).contains(clicked),
							"origin "+origin+" does not contain the clicked block for facing="+facing);
			}
		}

		/**
		 * The property that actually matters: whichever of the four blocks the player hammers, the
		 * real origin has to be among the candidates that get tried.
		 */
		@Test
		@DisplayName("hammering any of the four blocks finds the true origin")
		void anyClickedBlockFindsTheOrigin()
		{
			for(EnumFacing facing : EnumFacing.HORIZONTALS)
			{
				BlockPos origin = new BlockPos(-3, 70, 8);
				for(BlockPos clicked : FluidConsoleGeometry.cells(origin, facing))
					assertTrue(setOf(FluidConsoleGeometry.candidateOrigins(clicked, facing))
									.contains(origin),
							"clicking "+clicked+" (facing="+facing+") must offer origin "+origin);
			}
		}

		@Test
		@DisplayName("the candidate origins are distinct")
		void candidatesAreDistinct()
		{
			for(EnumFacing facing : EnumFacing.HORIZONTALS)
				assertEquals(4, setOf(FluidConsoleGeometry.candidateOrigins(
								new BlockPos(0, 64, 0), facing)).size(),
						"a repeated origin means one of the four positions can never be hammered");
		}

		@Test
		@DisplayName("an origin is never above the clicked block")
		void candidatesNeverReachAbove()
		{
			BlockPos clicked = new BlockPos(0, 64, 0);
			for(BlockPos candidate : FluidConsoleGeometry.candidateOrigins(clicked, EnumFacing.EAST))
			{
				int dy = candidate.getY()-clicked.getY();
				assertTrue(dy==0||dy==-1, "origin must be the clicked row or the one below, was "+dy);
			}
		}

		@Test
		@DisplayName("negative coordinates behave the same as positive ones")
		void negativeCoordinatesWork()
		{
			//Integer division and offsets are where a sign error hides.
			BlockPos clicked = new BlockPos(-100, 5, -100);
			for(BlockPos origin : FluidConsoleGeometry.candidateOrigins(clicked, EnumFacing.WEST))
				assertTrue(setOf(FluidConsoleGeometry.cells(origin, EnumFacing.WEST)).contains(clicked));
		}
	}
}
