/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Seismic Survey Kit's bearings and ranges.
 * <p>
 * A survey that points the wrong way sends a player on a four-hundred-block walk to nothing, and
 * nothing in game ever says why -- they simply conclude the kit does not work. Minecraft's north
 * being negative Z is exactly the sort of thing that gets mirrored by accident, so it is checked
 * here rather than trusted.
 */
class SeismicSurveyTest
{
	@Nested
	@DisplayName("bearings")
	class Bearings
	{
		@Test
		@DisplayName("the four cardinals point where Minecraft says they do")
		void cardinalsAreRight()
		{
			//North is -Z. If this is ever mirrored, every survey sends the player the wrong way and
			//the kit looks broken rather than wrong.
			assertEquals("north", SeismicSurvey.bearing(0, -100));
			assertEquals("south", SeismicSurvey.bearing(0, 100));
			assertEquals("east", SeismicSurvey.bearing(100, 0));
			assertEquals("west", SeismicSurvey.bearing(-100, 0));
		}

		@Test
		@DisplayName("the diagonals fall between their cardinals")
		void diagonalsAreRight()
		{
			assertEquals("north-east", SeismicSurvey.bearing(100, -100));
			assertEquals("south-east", SeismicSurvey.bearing(100, 100));
			assertEquals("south-west", SeismicSurvey.bearing(-100, 100));
			assertEquals("north-west", SeismicSurvey.bearing(-100, -100));
		}

		@Test
		@DisplayName("a slight lean does not change the answer")
		void nearCardinalsRoundToTheCardinal()
		{
			//Rounded to the nearest eighth rather than floored: a target one block east of due
			//north should read as north, and a floor would call it north-east.
			assertEquals("north", SeismicSurvey.bearing(1, -100));
			assertEquals("north", SeismicSurvey.bearing(-1, -100));
			assertEquals("east", SeismicSurvey.bearing(100, 1));
		}

		@Test
		@DisplayName("standing on it says so")
		void zeroOffsetIsHere()
		{
			assertEquals("here", SeismicSurvey.bearing(0, 0));
		}

		@Test
		@DisplayName("every offset produces a bearing from the published list")
		void bearingsAreAlwaysFromTheList()
		{
			for(int dx = -200; dx <= 200; dx += 7)
				for(int dz = -200; dz <= 200; dz += 7)
				{
					String bearing = SeismicSurvey.bearing(dx, dz);
					if("here".equals(bearing))
						continue;
					boolean known = false;
					for(String candidate : SeismicSurvey.BEARINGS)
						if(candidate.equals(bearing))
							known = true;
					assertTrue(known, dx+","+dz+" produced an unknown bearing: "+bearing);
				}
		}
	}

	@Nested
	@DisplayName("ranges")
	class Ranges
	{
		@Test
		@DisplayName("the distance is rounded to the nearest ten")
		void distanceIsRounded()
		{
			//A survey is a bearing and a rough range. Reporting an exact figure would imply a
			//precision the reading does not have -- and would make the Core Sample Drill pointless.
			assertEquals(100, SeismicSurvey.roundedDistance(100, 0));
			assertEquals(0, SeismicSurvey.roundedDistance(0, 0));
			assertEquals(10, SeismicSurvey.roundedDistance(7, 7));
			assertEquals(0, SeismicSurvey.roundedDistance(3, 0));
		}

		@Test
		@DisplayName("a long diagonal does not overflow")
		void longDiagonalIsSane()
		{
			//In doubles, or a survey near the world border comes back negative and reads as a
			//deposit on top of the player.
			int far = 30000000;
			assertTrue(SeismicSurvey.roundedDistance(far, far) > far);
		}
	}

	@Nested
	@DisplayName("cell centres")
	class CellCentres
	{
		@Test
		@DisplayName("a cell's centre is half a cell in from its corner")
		void centreIsInTheMiddle()
		{
			//A bearing taken to a cell's corner is off by up to forty-five degrees on a near cell,
			//which is the difference between "north" and "north-east" on the very reading a player
			//is most likely to act on.
			assertEquals(64, SeismicSurvey.cellCentreBlock(0, 8));
			assertEquals(192, SeismicSurvey.cellCentreBlock(1, 8));
			assertEquals(-64, SeismicSurvey.cellCentreBlock(-1, 8));
		}

		@Test
		@DisplayName("a degenerate cell size does not divide by zero")
		void zeroCellSizeIsSafe()
		{
			assertEquals(8, SeismicSurvey.cellCentreBlock(0, 0));
		}

		@Test
		@DisplayName("neighbouring cells are one cell apart")
		void cellsAreEvenlySpaced()
		{
			for(int size : new int[]{1, 4, 8, 16})
				for(int cell = -3; cell < 3; cell++)
					assertEquals(size*16, SeismicSurvey.cellCentreBlock(cell+1, size)
									-SeismicSurvey.cellCentreBlock(cell, size),
							"cell size "+size+" spaced its cells wrongly");
		}
	}
}
