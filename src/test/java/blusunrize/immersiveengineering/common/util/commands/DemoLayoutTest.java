/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.common.util.commands.DemoLayout.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The demo boulevard's geometry and sign text.
 * <p>
 * Both fail silently in a game. A station laid out wider than its spacing builds into its
 * neighbour, and the result reads as one of the two features being broken; a sign line of sixteen
 * characters is drawn squashed and unreadable, with nothing logged. Neither can be caught by
 * looking at the code, and both can be caught here.
 */
class DemoLayoutTest
{
	@Nested
	@DisplayName("sign text")
	class SignText
	{
		@Test
		@DisplayName("every line fits on a sign")
		void linesFit()
		{
			for(Station station : Station.VALUES)
				for(String[] panel : station.getSigns())
					for(String line : panel)
						assertTrue(line.length() <= DemoLayout.SIGN_LINE_LENGTH,
								station.name()+" has a line of "+line.length()+" characters "
										+"(\""+line+"\"); the sign renderer squashes anything past "
										+DemoLayout.SIGN_LINE_LENGTH);
		}

		@Test
		@DisplayName("every panel fits on a sign")
		void panelsFit()
		{
			for(Station station : Station.VALUES)
				for(String[] panel : station.getSigns())
					assertTrue(panel.length <= DemoLayout.SIGN_LINES,
							station.name()+" has a sign of "+panel.length+" lines");
		}

		@Test
		@DisplayName("every station's row fits between it and the next")
		void rowsFit()
		{
			for(Station station : Station.VALUES)
				assertTrue(station.getSigns().length <= DemoLayout.MAX_SIGNS,
						station.name()+" wants "+station.getSigns().length+" signs; the row holds "
								+DemoLayout.MAX_SIGNS);
		}

		@Test
		@DisplayName("every station has a title board and something to say")
		void everyStationIsExplained()
		{
			for(Station station : Station.VALUES)
			{
				assertTrue(station.getSigns().length >= 2,
						station.name()+" has no explanation, only a title");
				assertFalse(station.getTitle().trim().isEmpty(),
						station.name()+" has no name for the chat report");
			}
		}

		@Test
		@DisplayName("the fixes board fits its wall")
		void boardFits()
		{
			//Two rows of three, which is what CommandDemo.fixesBoard lays out. A seventh entry would
			//be placed off the end of the second row and silently vanish.
			assertEquals(6, DemoLayout.FIXES_BOARD.length,
					"the board is laid out as two rows of three");
			for(String[] panel : DemoLayout.FIXES_BOARD)
			{
				assertTrue(panel.length <= DemoLayout.SIGN_LINES, "a board sign is too tall");
				for(String line : panel)
					assertTrue(line.length() <= DemoLayout.SIGN_LINE_LENGTH,
							"board line \""+line+"\" is "+line.length()+" characters");
			}
		}
	}

	@Nested
	@DisplayName("geometry")
	class Geometry
	{
		@Test
		@DisplayName("stations march east, evenly spaced")
		void stationsAreSpaced()
		{
			int previous = Integer.MIN_VALUE;
			for(int i = 0; i < DemoLayout.stationCount(); i++)
			{
				int x = DemoLayout.stationX(1000, i);
				if(previous!=Integer.MIN_VALUE)
					assertEquals(DemoLayout.SPACING, x-previous,
							"station "+i+" is not one spacing from the one before it");
				previous = x;
			}
		}

		@Test
		@DisplayName("a station's sign row stays inside its own half of the gap")
		void signRowStaysHome()
		{
			//The widest a station may be is half the spacing either side of its centre. The row
			//starts at SIGN_ROW_START_X and runs one block per sign; if that reached past the
			//midpoint, two neighbouring rows would overwrite each other's last sign.
			int lastSignX = DemoLayout.SIGN_ROW_START_X+DemoLayout.MAX_SIGNS-1;
			assertTrue(lastSignX < DemoLayout.SPACING/2,
					"the sign row reaches "+lastSignX+" blocks east of its station, which is into "
							+"the next one");
			assertTrue(-DemoLayout.SIGN_ROW_START_X <= DemoLayout.SPACING/2,
					"the sign row starts inside the previous station");
		}

		@Test
		@DisplayName("the platform covers every station and its margins")
		void platformCoversEverything()
		{
			int origin = -37;
			assertTrue(DemoLayout.minX(origin) < DemoLayout.stationX(origin, 0),
					"the platform starts east of the first station");
			assertTrue(DemoLayout.maxX(origin)
							> DemoLayout.stationX(origin, DemoLayout.stationCount()-1),
					"the platform ends west of the last station");
			assertEquals(DemoLayout.MARGIN,
					DemoLayout.stationX(origin, 0)-DemoLayout.minX(origin),
					"the west margin is not the margin");
			assertEquals(DemoLayout.MARGIN,
					DemoLayout.maxX(origin)-DemoLayout.stationX(origin, DemoLayout.stationCount()-1),
					"the east margin is not the margin");
		}

		@Test
		@DisplayName("the sign row is on the platform, south of the centre line")
		void signRowIsOnThePlatform()
		{
			assertTrue(DemoLayout.SIGN_ROW_Z > 0,
					"the signs face the walkway, which is the south side");
			assertTrue(DemoLayout.SIGN_ROW_Z < DemoLayout.HALF_WIDTH,
					"the sign row is off the edge of the platform");
		}

		@Test
		@DisplayName("the region is a box, and a sensible one")
		void regionIsSane()
		{
			int ground = 64;
			assertTrue(DemoLayout.minY(ground) < ground, "there is no platform to carve into");
			assertTrue(DemoLayout.maxY(ground) > ground+9,
					"the tallest station is nine blocks high and would be cut off");
			assertTrue(DemoLayout.minZ(0) < DemoLayout.maxZ(0), "the platform has no width");
		}
	}

	@Nested
	@DisplayName("the catalogue")
	class Catalogue
	{
		@Test
		@DisplayName("no two stations share a title")
		void titlesAreDistinct()
		{
			Set<String> seen = new HashSet<>();
			for(Station station : Station.VALUES)
				assertTrue(seen.add(station.getTitle()),
						"two stations are called \""+station.getTitle()+"\"");
		}

		@Test
		@DisplayName("the title board of each station names its number")
		void titlesAreNumbered()
		{
			//The number on the board is what a bug report points at ("station 6 does nothing"), so
			//it has to match the order the stations are actually built in.
			for(Station station : Station.VALUES)
				assertEquals("[ "+(station.ordinal()+1)+" ]", station.getSigns()[0][0],
						station.name()+"'s title board carries the wrong number");
		}
	}
}
