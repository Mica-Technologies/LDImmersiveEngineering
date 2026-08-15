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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

	@Nested
	@DisplayName("the text budget below a large top element")
	class TextBudget
	{
		private static final int TEXT_LINE_HEIGHT = 9;

		@Test
		@DisplayName("maxLines never leaves a line hanging off the bottom of the pane")
		void maxLinesStaysInsideThePane()
		{
			for(int availableHeight : new int[]{0, 1, 9, 20, 87, 141, 176, 357})
				for(int topOffset : new int[]{0, 8, 20, 87, 128, 141, 200})
				{
					int lines = ManualLayout.maxLines(availableHeight, topOffset, TEXT_LINE_HEIGHT);
					String at = " (available "+availableHeight+", offset "+topOffset+")";
					assertTrue(lines >= 0, "negative line count"+at);
					assertTrue(topOffset+lines*TEXT_LINE_HEIGHT <= availableHeight||lines==0&&topOffset >= availableHeight,
							"a line's baseline fell past the bottom of the pane"+at);
				}
		}

		@Test
		@DisplayName("maxTopOffset leaves exactly enough room for the lines it was asked for")
		void maxTopOffsetLeavesRoomForItsLines()
		{
			for(int availableHeight : new int[]{20, 87, 141, 176, 357})
				for(int lines : new int[]{0, 1, 3, 6, 10})
				{
					int topOffset = ManualLayout.maxTopOffset(availableHeight, lines, TEXT_LINE_HEIGHT);
					int fits = ManualLayout.maxLines(availableHeight, topOffset, TEXT_LINE_HEIGHT);
					assertTrue(fits >= lines||topOffset >= availableHeight,
							"maxTopOffset didn't actually leave room for "+lines+" lines at height "+availableHeight);
				}
		}

		@Test
		@DisplayName("reproduces the Squeezer page bleed at 854x480, GUI scale 2")
		void squeezerAtRealScreenSize()
		{
			//854x480 at GUI scale 2 hands the manual a 427x240 layout -- this is the case that
			//actually shipped with the entry's body text bleeding onto the leather frame under the
			//page, below the paper pane.
			ManualLayout l = new ManualLayout(427, 240, ROW_HEIGHT);
			//The header for an entry with no subtext, exactly as GuiManual computes it.
			int header = 9+6;
			int available = l.pageY+l.pageHeight-ManualLayout.PAGE_PADDING-l.getTextY(header);
			assertEquals(141, available, "the real budget the Squeezer page had to work with");

			//This is what ManualPageMultiblock used to compute for the Squeezer's textOffset, before
			//the preview's scale-up was made to leave room for the text below it: a 3x3x3 structure
			//at its authored scale of 13, capped only by the old (uncapped-by-text) formula.
			int buggyTextOffset = 128;
			int roomLeft = ManualLayout.maxLines(available, buggyTextOffset, TEXT_LINE_HEIGHT);
			assertTrue(roomLeft <= 1,
					"the bug: almost the whole six-line entry had nowhere left to draw but past the pane");

			//The fix: cap the preview so its offset leaves room for the lines the entry actually
			//needs, using the same arithmetic as production code.
			int neededLines = 6;
			int fixedTextOffset = ManualLayout.maxTopOffset(available, neededLines, TEXT_LINE_HEIGHT);
			assertTrue(fixedTextOffset < buggyTextOffset, "the cap should shrink the preview below the old size");
			assertTrue(ManualLayout.maxLines(available, fixedTextOffset, TEXT_LINE_HEIGHT) >= neededLines,
					"even after capping, all six lines must still fit");
		}
	}

	@Nested
	@DisplayName("the update-news page budget")
	class UpdateNewsBudget
	{
		//The line height ClientProxy actually derives this from is FontRenderer.FONT_HEIGHT, which is
		//9 for the font the manual runs with. Pinned as a literal here because this test has no
		//FontRenderer to ask -- if that ever stops being 9, ClientProxy's budget moves with it and
		//this test should be revisited alongside it.
		private static final int LINE_HEIGHT = 9;
		//No subtext on a version entry (see IEManualInstance#formatEntrySubtext), so this is always
		//the "no subtext" header GuiManual#initGui computes.
		private static final int HEADER = LINE_HEIGHT+6+4;

		/**
		 * The exact arithmetic {@code ClientProxy#addVersionToManual} runs to size LINES_PER_PAGE --
		 * duplicated here (rather than called into, since that method needs a live FontRenderer and
		 * ManualHelper) so a change to ManualLayout's constants is caught here instead of silently
		 * shrinking or overflowing an update-news page.
		 */
		private int linesPerPage(int screenWidth, int screenHeight)
		{
			ManualLayout l = new ManualLayout(screenWidth, screenHeight, LINE_HEIGHT+3);
			int available = l.pageY+l.pageHeight-ManualLayout.PAGE_PADDING-l.getTextY(HEADER);
			return Math.max(1, ManualLayout.maxLines(available, 0, LINE_HEIGHT));
		}

		@Test
		@DisplayName("the floor (ManualLayout.MIN_WIDTH x MIN_HEIGHT) is pinned")
		void floorBudgetIsPinned()
		{
			//If this changes, it is because ManualLayout's geometry changed underneath it -- update
			//the expected value deliberately, don't just chase the failure.
			assertEquals(11, linesPerPage(ManualLayout.MIN_WIDTH, ManualLayout.MIN_HEIGHT),
					"the update-news line budget drifted from what ClientProxy was built against");
		}

		@Test
		@DisplayName("the floor is never roomier than any other supported screen")
		void floorIsTheWorstCase()
		{
			//This is the property the whole scheme depends on: a budget sized at the layout's floor
			//must never be more lines than what a bigger screen can actually show, or a page built to
			//that budget would overflow there. Checked across every screen ManualLayoutTest exercises,
			//not just the two named "the floor" -- 240x180, MIN_WIDTH x MIN_HEIGHT, is the smallest of
			//them, but this proves it rather than assumes it.
			int floorBudget = linesPerPage(ManualLayout.MIN_WIDTH, ManualLayout.MIN_HEIGHT);
			for(int[] screen : SCREENS)
			{
				int budget = linesPerPage(screen[0], screen[1]);
				assertTrue(budget >= floorBudget,
						"screen "+screen[0]+"x"+screen[1]+" fits fewer lines ("+budget+
								") than the floor budget ("+floorBudget+") was sized against");
			}
		}

		@Test
		@DisplayName("a paragraph counted at the legacy width never under-counts the real render width")
		void legacyWidthNeverUndercounts()
		{
			//The other half of the safety argument: paragraphs are counted at
			//ManualLayout.LEGACY_TEXT_WIDTH (120) to decide how many lines they need. That is only a
			//safe over-estimate if the real pane is never narrower than 120 -- which is exactly what
			//"widerThanTheOldBook" already asserts for every screen. Restated here, pinned to the
			//constant ClientProxy actually reads, so a change to either one is caught in one place.
			for(int[] screen : SCREENS)
			{
				ManualLayout l = new ManualLayout(screen[0], screen[1], LINE_HEIGHT+3);
				assertTrue(l.textWidth >= ManualLayout.LEGACY_TEXT_WIDTH,
						"real text width "+l.textWidth+" at "+screen[0]+"x"+screen[1]+
								" is narrower than the width paragraphs are counted at");
			}
		}
	}

	@Nested
	@DisplayName("the welcome page")
	class Welcome
	{
		private static final int FONT_HEIGHT = 9;
		/**
		 * A deliberate over-estimate of the manual font's average advance. The welcome text is drawn
		 * in the unicode sheet, whose Latin glyphs advance about five pixels; measuring at six means
		 * anything this test says fits really does fit, and it needs no game to say it.
		 */
		private static final double CHAR_WIDTH = 6;
		/**
		 * Minecraft's own auto GUI scale never gives the game less than this, so it is the smallest
		 * screen the welcome page is expected to be fully readable on. Below it the manual is at
		 * {@link ManualLayout#MIN_WIDTH}x{@link ManualLayout#MIN_HEIGHT}, where entry pages already
		 * run out of room too.
		 */
		private static final int READABLE_WIDTH = 320;
		private static final int READABLE_HEIGHT = 240;

		@Test
		@DisplayName("a block that fits is centred in the pane")
		void centredWhenItFits()
		{
			int[] offsets = ManualLayout.stackBlocks(200, new int[]{1, 4, 6}, FONT_HEIGHT, FONT_HEIGHT);
			int height = 11*FONT_HEIGHT+2*FONT_HEIGHT;
			assertEquals((200-height)/2, offsets[0], "the block is not centred");
			assertEquals(offsets[0]+FONT_HEIGHT+FONT_HEIGHT, offsets[1], "paragraph one is misplaced");
			assertEquals(offsets[1]+4*FONT_HEIGHT+FONT_HEIGHT, offsets[2], "paragraph two is misplaced");
			assertTrue(offsets[2]+6*FONT_HEIGHT <= 200, "the block ran past the bottom of the pane");
		}

		@Test
		@DisplayName("it gives up its paragraph spacing before it gives up the top of the page")
		void spacingGoesFirst()
		{
			//Tall enough for the words, not for the air between them.
			int[] tight = ManualLayout.stackBlocks(11*FONT_HEIGHT+FONT_HEIGHT, new int[]{1, 4, 6}, FONT_HEIGHT, FONT_HEIGHT);
			assertEquals(0, tight[0], "the first line should be at the top once space is tight");
			assertTrue(tight[2]+6*FONT_HEIGHT <= 11*FONT_HEIGHT+FONT_HEIGHT,
					"halving the spacing should have been enough to fit");
		}

		@Test
		@DisplayName("no block ever starts above the top of the pane")
		void neverStartsAboveTheTop()
		{
			//A negative first offset would draw the manual's own name off the top of the paper, which
			//is what centring a block taller than its pane does if nobody stops it.
			for(int available : new int[]{0, 9, 40, 120, 156, 372})
				for(int[] blocks : new int[][]{{1}, {1, 4, 6}, {1, 12, 30}, {1, 0, 0}})
				{
					int[] offsets = ManualLayout.stackBlocks(available, blocks, FONT_HEIGHT, FONT_HEIGHT);
					assertEquals(blocks.length, offsets.length, "one offset per block");
					int previous = -1;
					for(int offset : offsets)
					{
						assertTrue(offset >= 0, "offset above the top of the pane (available "+available+")");
						assertTrue(offset >= previous, "offsets went backwards");
						previous = offset;
					}
				}
		}

		@Test
		@DisplayName("the welcome text fits the page on every screen the manual is readable at")
		void welcomeTextFits()
		{
			for(int[] screen : SCREENS)
			{
				if(screen[0] < READABLE_WIDTH||screen[1] < READABLE_HEIGHT)
					continue;
				ManualLayout l = new ManualLayout(screen[0], screen[1], ROW_HEIGHT);
				int available = l.pageHeight-2*ManualLayout.PAGE_PADDING;
				int needed = welcomeHeight(l.textWidth, available);
				assertTrue(needed <= available,
						"the welcome page needs "+needed+"px of the "+available+"px it has at "+
								screen[0]+"x"+screen[1]+" -- shorten the text in en_us.lang");
			}
		}

		@Test
		@DisplayName("even at the layout's floor it loses no more than the last few lines")
		void theFloorOnlyClipsTheTail()
		{
			//The floor is a 240x180 pane -- thirteen lines of text, which is not a welcome page. It is
			//allowed to clip there, because GuiManual draws nothing past the bottom margin, but if the
			//text ever grows to twice what the floor can show, the disclaimer has stopped being
			//something a player at that size sees any of.
			ManualLayout l = new ManualLayout(ManualLayout.MIN_WIDTH, ManualLayout.MIN_HEIGHT, ROW_HEIGHT);
			int available = l.pageHeight-2*ManualLayout.PAGE_PADDING;
			int needed = welcomeHeight(l.textWidth, available);
			assertTrue(needed <= available*2,
					"the welcome text needs "+needed+"px where the layout's floor can show "+available+"px");
		}

		/**
		 * The height {@link blusunrize.lib.manual.gui.GuiManual}'s welcome page would want, for the
		 * strings the lang file actually carries -- the point being that the text is the thing that
		 * drifts, and it drifts in a file that compiles no matter what is written in it.
		 */
		private int welcomeHeight(int wrapWidth, int available)
		{
			String lang = langFile();
			int[] blocks = {1, wrappedLines(langValue(lang, "ie.manual.welcome"), wrapWidth),
					wrappedLines(langValue(lang, "ie.manual.disclaimer"), wrapWidth)};
			int[] offsets = ManualLayout.stackBlocks(available, blocks, FONT_HEIGHT, FONT_HEIGHT);
			return offsets[blocks.length-1]-offsets[0]+blocks[blocks.length-1]*FONT_HEIGHT;
		}

		/**
		 * The same greedy break-on-spaces the font renderer's line splitter does, at a character
		 * width that cannot be measured without the game.
		 */
		private int wrappedLines(String text, int wrapWidth)
		{
			int lines = 1;
			double used = 0;
			for(String word : text.split(" "))
			{
				double word_ = word.length()*CHAR_WIDTH;
				double needed = (used > 0?CHAR_WIDTH: 0)+word_;
				if(used+needed > wrapWidth)
				{
					lines++;
					used = word_;
				}
				else
					used += needed;
			}
			return lines;
		}

		private String langValue(String lang, String key)
		{
			for(String line : lang.split("\r?\n"))
				if(line.startsWith(key+"="))
					return line.substring(key.length()+1);
			throw new AssertionError("en_us.lang has no "+key+" -- the welcome page would draw a raw key");
		}

		private String langFile()
		{
			try
			{
				return new String(Files.readAllBytes(Paths.get(
						"src/main/resources/assets/immersiveengineering/lang/en_us.lang")), StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new UncheckedIOException("could not read en_us.lang", e);
			}
		}
	}
}
