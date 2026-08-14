/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.lib.manual.gui;

/**
 * Where every part of the manual goes, for a screen of a given size.
 * <p>
 * <strong>Deliberately free of Minecraft.</strong> The old manual's geometry was a scattering of
 * literals -- {@code guiLeft+32}, {@code guiTop+179}, a text column of 120 -- that only made sense
 * against one 186x198 texture, and there was no way to check any of it without launching the game.
 * This is the same arithmetic with the screen size as an input, so the properties that actually
 * matter (it fits on the screen, the page does not sit under the index, the text column stays inside
 * the page) can be asserted.
 */
public class ManualLayout
{
	/**
	 * Below this the manual stops shrinking and starts overflowing a very small window instead --
	 * except that it is also clamped to the screen, so in practice it just fills it.
	 */
	public static final int MIN_WIDTH = 240;
	public static final int MIN_HEIGHT = 180;
	/**
	 * A line of text as wide as a modern screen is not readable, so the column is capped and centred
	 * in the page instead of being stretched to it.
	 */
	public static final int MAX_TEXT_WIDTH = 360;
	public static final int MIN_TEXT_WIDTH = 80;
	/**
	 * The text column of the book this replaced. Nothing here is allowed to end up narrower than it.
	 */
	public static final int LEGACY_TEXT_WIDTH = 120;
	public static final int MIN_SIDEBAR = 96;
	public static final int MAX_SIDEBAR = 160;
	public static final int PADDING = 6;
	public static final int TITLE_BAR = 20;
	public static final int NAV_BAR = 14;
	public static final int PAGE_PADDING = 10;
	public static final int SEARCH_HEIGHT = 14;

	public final int left;
	public final int top;
	public final int width;
	public final int height;

	public final int sidebarX;
	public final int sidebarWidth;
	public final int searchY;
	public final int listY;
	public final int listHeight;

	public final int pageX;
	public final int pageY;
	public final int pageWidth;
	public final int pageHeight;
	public final int navY;

	public final int textX;
	public final int textWidth;

	public ManualLayout(int screenWidth, int screenHeight, int rowHeight)
	{
		//Nine tenths of the screen, with a floor so it stays usable in a small window and a clamp so
		//it never spills out of one.
		int w = Math.max(MIN_WIDTH, (int)(screenWidth*0.9f));
		int h = Math.max(MIN_HEIGHT, (int)(screenHeight*0.9f));
		this.width = Math.min(w, screenWidth);
		this.height = Math.min(h, screenHeight);
		this.left = (screenWidth-this.width)/2;
		this.top = (screenHeight-this.height)/2;

		this.sidebarX = left+PADDING+2;
		//	=================================
		//	The index gives ground before the page does.
		//	=================================
		//
		// On a small screen something has to be narrow. A text column below the old book's 120 would
		// be a regression on the exact complaint this layout answers, so the index shrinks past its
		// preferred minimum instead, down to the point where it can still show a category name.
		int chrome = (PADDING+2)+(PADDING+2)+PADDING;
		int affordable = this.width-(LEGACY_TEXT_WIDTH+2*PAGE_PADDING)-chrome;
		int floor = Math.max(MIN_SIDEBAR/2, Math.min(MIN_SIDEBAR, affordable));
		int sidebar = Math.max(floor, Math.min(MAX_SIDEBAR, (int)(this.width*0.28f)));
		this.sidebarWidth = Math.min(sidebar, Math.max(1, this.width/2-PADDING*2));

		int contentTop = top+TITLE_BAR;
		int contentBottom = top+this.height-PADDING;

		this.searchY = contentTop;
		this.listY = searchY+SEARCH_HEIGHT+4;
		this.listHeight = Math.max(rowHeight, contentBottom-listY);

		this.pageX = sidebarX+sidebarWidth+PADDING+2;
		this.pageWidth = Math.max(MIN_TEXT_WIDTH+2*PAGE_PADDING, left+this.width-PADDING-pageX);
		this.pageY = contentTop;
		this.pageHeight = Math.max(NAV_BAR*2, contentBottom-NAV_BAR-pageY);
		this.navY = pageY+pageHeight+2;

		this.textWidth = Math.max(MIN_TEXT_WIDTH, Math.min(pageWidth-2*PAGE_PADDING, MAX_TEXT_WIDTH));
		this.textX = pageX+(pageWidth-textWidth)/2;
	}

	public int getSearchX()
	{
		return sidebarX;
	}

	public int getSearchWidth()
	{
		return sidebarWidth;
	}

	/**
	 * Top of the page body, once the entry's title block has taken its share.
	 */
	public int getTextY(int headerHeight)
	{
		return pageY+PAGE_PADDING+headerHeight;
	}
}
