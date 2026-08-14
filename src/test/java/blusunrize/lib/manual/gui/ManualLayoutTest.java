/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.lib.manual.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The manual's geometry, at every screen size a player can hand it.
 * <p>
 * <strong>This is the half of "the pages go off the page" that can be checked without the game.</strong>
 * The screen the manual is drawn on depends on the monitor and on the GUI scale, which between them
 * span roughly 240x180 (a small window at scale 4) to 1920x1080 (a large one at scale 1). The old
 * book ignored all of that and measured everything against one texture. These are the properties
 * that have to hold whatever the screen turns out to be.
 */
class ManualLayoutTest
{
	private static final int ROW_HEIGHT = 12;

	/**
	 * Small window at a large GUI scale, through to a large window at a small one.
	 */
	private static final int[][] SCREENS = {
			{240, 180}, {280, 200}, {320, 240}, {427, 240}, {480, 270}, {640, 360},
			{854, 480}, {960, 540}, {1280, 720}, {1920, 1080}
	};

	@Nested
	@DisplayName("it stays on the screen")
	class OnScreen
	{
		@Test
		@DisplayName("the book never extends past any edge")
		void neverOverflows()
		{
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				String at = " at "+screen[0]+"x"+screen[1];
				assertTrue(l.left >= 0, "left edge"+at);
				assertTrue(l.top >= 0, "top edge"+at);
				assertTrue(l.left+l.width <= screen[0], "right edge"+at);
				assertTrue(l.top+l.height <= screen[1], "bottom edge"+at);
			}
		}

		@Test
		@DisplayName("it is centred")
		void centred()
		{
			ManualLayout l = new ManualLayout(1920, 1080, ROW_HEIGHT);
			assertEquals(1920-(l.left+l.width), l.left, "even margin left and right");
			assertEquals(1080-(l.top+l.height), l.top, "even margin top and bottom");
		}

		@Test
		@DisplayName("it fills the screen rather than sitting in the middle of it")
		void fillsTheScreen()
		{
			//The complaint that started this was a book the size of a postage stamp on a 1080p screen.
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				assertTrue(l.width >= screen[0]*0.85, "width "+l.width+" of "+screen[0]);
				assertTrue(l.height >= screen[1]*0.85, "height "+l.height+" of "+screen[1]);
			}
		}
	}

	@Nested
	@DisplayName("the two panes")
	class Panes
	{
		@Test
		@DisplayName("the page never starts before the index ends")
		void paneDoesNotOverlapSidebar()
		{
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				assertTrue(l.pageX > l.sidebarX+l.sidebarWidth,
						"page pane overlaps the index at "+screen[0]+"x"+screen[1]);
			}
		}

		@Test
		@DisplayName("both panes have room to exist")
		void panesArePositive()
		{
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				String at = " at "+screen[0]+"x"+screen[1];
				assertTrue(l.sidebarWidth > 0, "index width"+at);
				assertTrue(l.pageWidth > 0, "page width"+at);
				assertTrue(l.pageHeight > 0, "page height"+at);
				assertTrue(l.listHeight >= ROW_HEIGHT, "index shows at least one row"+at);
			}
		}

		@Test
		@DisplayName("the index does not swallow the page on a narrow screen")
		void sidebarIsBounded()
		{
			ManualLayout narrow = new ManualLayout(240, 180, ROW_HEIGHT);
			assertTrue(narrow.sidebarWidth <= narrow.width/2, "the index took over half the book");
		}

		@Test
		@DisplayName("the page counter row sits below the page, inside the book")
		void navBarIsInside()
		{
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				assertTrue(l.navY >= l.pageY+l.pageHeight, "counter overlaps the page");
				assertTrue(l.navY+10 <= l.top+l.height, "counter falls off the bottom of the book");
			}
		}
	}

	@Nested
	@DisplayName("the text column")
	class TextColumn
	{
		@Test
		@DisplayName("it stays inside the page, whatever the page is")
		void insideThePage()
		{
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				String at = " at "+screen[0]+"x"+screen[1];
				assertTrue(l.textX >= l.pageX, "column starts before the page"+at);
				assertTrue(l.textX+l.textWidth <= l.pageX+l.pageWidth, "column runs past the page"+at);
			}
		}

		@Test
		@DisplayName("it is never so wide that a line stops being readable")
		void cappedForReading()
		{
			ManualLayout huge = new ManualLayout(1920, 1080, ROW_HEIGHT);
			assertEquals(ManualLayout.MAX_TEXT_WIDTH, huge.textWidth,
					"a text column the width of a 1080p screen is not a page, it is a log file");
			assertTrue(huge.textX > huge.pageX, "a capped column should be centred, not left-aligned");
		}

		@Test
		@DisplayName("it is always at least as wide as the book it replaced")
		void widerThanTheOldBook()
		{
			//The old page was a 120 pixel column. Anything narrower than that would be a regression on
			//the very complaint this layout exists to fix.
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				assertTrue(l.textWidth >= 120,
						"text column of "+l.textWidth+" at "+screen[0]+"x"+screen[1]+" is narrower than the old book");
			}
		}

		@Test
		@DisplayName("the body starts below the title block it was given room for")
		void headerPushesTheBodyDown()
		{
			ManualLayout l = new ManualLayout(960, 540, ROW_HEIGHT);
			assertTrue(l.getTextY(0) > l.pageY, "body starts inside the page");
			assertEquals(l.getTextY(0)+20, l.getTextY(20), "a taller title takes exactly its own height");
			assertTrue(l.getTextY(30) < l.pageY+l.pageHeight, "a title block should not fill the page");
		}
	}
}
