/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Substation's cell layout.
 * <p>
 * Twelve blocks in three dimensions, formed from whichever of them the player happened to hit, in
 * any of four orientations. That is the sort of arithmetic that is right in testing and wrong in a
 * corner of a build somebody notices a week later, so it is checked here rather than by placing
 * blocks.
 * <p>
 * The invariant with real consequences is the index packing: {@code TileEntityMultiblockPart.pos}
 * is written into save data, so a change to it silently reshuffles every substation already built.
 */
class SubstationGeometryTest
{
	private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);

	@Nested
	@DisplayName("indexing")
	class Indexing
	{
		@Test
		@DisplayName("twelve cells, and the size array matches them")
		void sizeIsConsistent()
		{
			assertEquals(12, SubstationGeometry.BLOCK_COUNT);
			assertEquals(SubstationGeometry.HEIGHT*SubstationGeometry.DEPTH*SubstationGeometry.WIDTH,
					SubstationGeometry.BLOCK_COUNT);
			assertArrayEquals(new int[]{SubstationGeometry.HEIGHT, SubstationGeometry.DEPTH,
					SubstationGeometry.WIDTH}, SubstationGeometry.SIZE);
		}

		@Test
		@DisplayName("every cell has a distinct index, and they fill 0..11 exactly")
		void indicesAreABijection()
		{
			//A collision means two blocks of the structure believing they are the same part, which
			//is how a multiblock ends up with two masters or none.
			Set<Integer> seen = new HashSet<>();
			for(int h = 0; h < SubstationGeometry.HEIGHT; h++)
				for(int d = 0; d < SubstationGeometry.DEPTH; d++)
					for(int w = 0; w < SubstationGeometry.WIDTH; w++)
					{
						int index = SubstationGeometry.structureIndex(h, d, w);
						assertTrue(seen.add(index), "index "+index+" is used twice");
						assertTrue(SubstationGeometry.isPart(index));
					}
			assertEquals(SubstationGeometry.BLOCK_COUNT, seen.size());
			for(int i = 0; i < SubstationGeometry.BLOCK_COUNT; i++)
				assertTrue(seen.contains(i), "index "+i+" is never produced");
		}

		@Test
		@DisplayName("an index decomposes back into the cell it came from")
		void indexRoundTrips()
		{
			//This packing is in save data. If it ever changes, every substation already built
			//reshuffles itself silently.
			for(int h = 0; h < SubstationGeometry.HEIGHT; h++)
				for(int d = 0; d < SubstationGeometry.DEPTH; d++)
					for(int w = 0; w < SubstationGeometry.WIDTH; w++)
					{
						int index = SubstationGeometry.structureIndex(h, d, w);
						assertEquals(h, SubstationGeometry.heightOf(index));
						assertEquals(d, SubstationGeometry.depthOf(index));
						assertEquals(w, SubstationGeometry.widthOf(index));
					}
		}

		@Test
		@DisplayName("anything outside the structure is not part of it")
		void outOfRangeIsNotAPart()
		{
			assertFalse(SubstationGeometry.isPart(-1));
			assertFalse(SubstationGeometry.isPart(SubstationGeometry.BLOCK_COUNT));
		}

		@Test
		@DisplayName("the two devices are in different cells")
		void devicesDoNotShareACell()
		{
			//The grid keys a device by its block position, so a feed and a service in one cell
			//would be one device that kept overwriting itself.
			assertNotEquals(SubstationGeometry.FEED_INDEX, SubstationGeometry.SERVICE_INDEX);
			assertTrue(SubstationGeometry.isPart(SubstationGeometry.FEED_INDEX));
			assertTrue(SubstationGeometry.isPart(SubstationGeometry.SERVICE_INDEX));
			//Both on the bottom front rank, so both are reachable from outside the yard.
			assertEquals(0, SubstationGeometry.heightOf(SubstationGeometry.FEED_INDEX));
			assertEquals(0, SubstationGeometry.heightOf(SubstationGeometry.SERVICE_INDEX));
			assertEquals(0, SubstationGeometry.depthOf(SubstationGeometry.FEED_INDEX));
			assertEquals(0, SubstationGeometry.depthOf(SubstationGeometry.SERVICE_INDEX));
		}
	}

	@Nested
	@DisplayName("placing it in the world")
	class Placement
	{
		@Test
		@DisplayName("twelve distinct positions, in every orientation")
		void cellsAreDistinct()
		{
			for(EnumFacing front : EnumFacing.HORIZONTALS)
			{
				List<BlockPos> cells = SubstationGeometry.cells(ORIGIN, front, front.rotateYCCW());
				assertEquals(SubstationGeometry.BLOCK_COUNT, cells.size());
				assertEquals(SubstationGeometry.BLOCK_COUNT, new HashSet<>(cells).size(),
						front+": two cells landed on the same block");
			}
		}

		@Test
		@DisplayName("the yard occupies a solid 3x2x2 box")
		void cellsFormASolidBox()
		{
			//Not a shell and not a scatter: every position inside the bounding box is used.
			for(EnumFacing front : EnumFacing.HORIZONTALS)
			{
				List<BlockPos> cells = SubstationGeometry.cells(ORIGIN, front, front.rotateYCCW());
				int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
				int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
				for(BlockPos cell : cells)
				{
					minX = Math.min(minX, cell.getX());
					maxX = Math.max(maxX, cell.getX());
					minY = Math.min(minY, cell.getY());
					maxY = Math.max(maxY, cell.getY());
					minZ = Math.min(minZ, cell.getZ());
					maxZ = Math.max(maxZ, cell.getZ());
				}
				int volume = (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);
				assertEquals(SubstationGeometry.BLOCK_COUNT, volume,
						front+": the cells do not fill their own bounding box");
				assertEquals(SubstationGeometry.HEIGHT, maxY-minY+1, front+": wrong height");
			}
		}

		@Test
		@DisplayName("the origin is one of the cells")
		void originIsInside()
		{
			for(EnumFacing front : EnumFacing.HORIZONTALS)
				assertTrue(SubstationGeometry.cells(ORIGIN, front, front.rotateYCCW()).contains(ORIGIN),
						front+": the origin is not part of its own structure");
		}

		@Test
		@DisplayName("hammering any block of it finds the right origin")
		void everyCellRecoversTheOrigin()
		{
			//The player may strike any of the twelve, so every one of them has to produce the true
			//origin among its candidates. Without this, a yard forms from one corner and refuses
			//from another, which reads as the structure being built wrong.
			for(EnumFacing front : EnumFacing.HORIZONTALS)
			{
				EnumFacing right = front.rotateYCCW();
				for(BlockPos struck : SubstationGeometry.cells(ORIGIN, front, right))
					assertTrue(SubstationGeometry.candidateOrigins(struck, front, right).contains(ORIGIN),
							front+": striking "+struck+" never proposes the real origin");
			}
		}

		@Test
		@DisplayName("it proposes no more origins than it has cells")
		void candidatesAreBounded()
		{
			//One candidate per cell. More would mean the formation code testing the same origin
			//twice on every hammer strike.
			assertEquals(SubstationGeometry.BLOCK_COUNT,
					SubstationGeometry.candidateOrigins(ORIGIN, EnumFacing.NORTH,
							EnumFacing.NORTH.rotateYCCW()).size());
		}

		@Test
		@DisplayName("turning the yard round moves it, rather than leaving it in place")
		void orientationMatters()
		{
			//A structure whose cells did not depend on its facing would form fine and then have its
			//devices on whichever side the arithmetic happened to pick.
			Set<BlockPos> north = new HashSet<>(SubstationGeometry.cells(ORIGIN, EnumFacing.NORTH,
					EnumFacing.NORTH.rotateYCCW()));
			Set<BlockPos> east = new HashSet<>(SubstationGeometry.cells(ORIGIN, EnumFacing.EAST,
					EnumFacing.EAST.rotateYCCW()));
			assertNotEquals(north, east);
		}
	}
}
