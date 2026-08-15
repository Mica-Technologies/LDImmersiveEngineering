/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.lib.manual.gui;

import blusunrize.lib.manual.IManualPage;
import blusunrize.lib.manual.ManualInstance;
import blusunrize.lib.manual.ManualInstance.ManualEntry;
import blusunrize.lib.manual.ManualInstance.ManualLink;
import blusunrize.lib.manual.ManualUtils;
import blusunrize.lib.manual.gui.GuiManualSidebar.Row;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;

/**
 * The manual, as a full-screen two-pane reader.
 * <p>
 * <strong>The book used to be a 186x198 texture and everything was measured against it.</strong>
 * That is why entries ran off the bottom of the page: the text column was 120 pixels wide and about
 * sixteen lines tall no matter how big the screen was, and a fork with this many entries has pages
 * that simply do not fit in it. The frame is drawn from flat rectangles now instead of a sprite, so
 * it can be any size and stays crisp at every GUI scale, and every measurement the page types need
 * comes from {@link #getPageWidth()} / {@link #getPageHeight()} rather than a constant.
 * <p>
 * The index is always on the left and the search box above it is always live, because the old
 * arrangement -- a category list that replaced itself with an entry list which replaced itself with
 * the page, and a search box that only existed while you happened to be looking at a list -- made
 * finding anything a matter of remembering where it was.
 */
public class GuiManual extends GuiScreen
{
	//	=================================
	//	Drawn, not textured.
	//	=================================
	//
	// A texture would have to be authored for one size and then stretched; these are flat rects, so
	// the frame is pixel-crisp whether the player runs GUI scale 1 or 4. Black leather with warm
	// stitching outside, white paper with near-black text inside.
	public static final int COLOUR_EDGE = 0xff000000;
	public static final int COLOUR_LEATHER = 0xff17130e;
	public static final int COLOUR_LEATHER_GRAIN = 0x0affffff;
	public static final int COLOUR_LEATHER_BEVEL = 0x18ffffff;
	public static final int COLOUR_STITCH = 0xff5a4a35;
	public static final int COLOUR_PANEL = 0xff0e0c0a;
	public static final int COLOUR_PANEL_EDGE = 0xff3a3129;
	public static final int COLOUR_PAPER = 0xfffdfbf5;
	public static final int COLOUR_PAPER_EDGE = 0xff9c9384;
	public static final int COLOUR_PAPER_SHADOW = 0x50000000;
	public static final int COLOUR_TEXT = 0xffd8cdb8;
	public static final int COLOUR_TEXT_CATEGORY = 0xfff0e4cc;
	public static final int COLOUR_TEXT_HOVER = 0xfff7a24b;
	public static final int COLOUR_TEXT_SELECTED = 0xfff78034;
	public static final int COLOUR_LABEL = 0xff9a8f7c;
	public static final int COLOUR_TITLE = 0xfff0a256;
	public static final int COLOUR_ROW_HOVER = 0x22ffffff;
	public static final int COLOUR_ROW_SELECTED = 0x3df78034;
	public static final int COLOUR_SCROLL_TRACK = 0x30000000;
	public static final int COLOUR_SCROLL_BAR = 0x80f0a256;
	public static final int COLOUR_SEARCH_BG = 0xff080706;
	public static final int COLOUR_SEARCH_EDGE = 0xff4a4038;

	//Geometry lives in ManualLayout, which has no Minecraft in it and can therefore be tested.
	private static final int PADDING = ManualLayout.PADDING;
	private static final int TITLE_BAR = ManualLayout.TITLE_BAR;
	private static final int PAGE_PADDING = ManualLayout.PAGE_PADDING;
	/**
	 * Which glyph sheet every title uses. See {@link #drawTitle}.
	 */
	private static final boolean TITLE_UNICODE = false;

	int xSize;
	int ySize;
	int guiLeft;
	int guiTop;
	int manualTick = 0;
	List<GuiButton> pageButtons = new ArrayList();

	public String selectedCategory;
	private String selectedEntry;
	public Stack<String> previousSelectedEntry = new Stack();
	public int page;
	public static GuiManual activeManual;

	ManualInstance manual;
	public String texture;
	boolean buttonHeld = false;
	int[] lastClick;
	int[] lastDrag;
	GuiTextField searchField;
	int prevGuiScale = -1;

	private GuiManualSidebar sidebar;
	private int sidebarX;
	private int sidebarWidth;
	private int searchX;
	private int searchY;
	private int searchWidth;
	private int searchHeight;
	private int pageX;
	private int pageY;
	private int pageWidth;
	private int pageHeight;
	private int textX;
	private int textY;
	private int textWidth;
	private int navY;
	/**
	 * Entry keys the search turned up, or null while the index is showing.
	 */
	private List<String> searchResults;
	private List<String> searchSuggestions = new ArrayList<>();

	public GuiManual(ManualInstance manual, String texture)
	{
		super();
		this.manual = manual;
		this.texture = texture;

		prevGuiScale = Minecraft.getMinecraft().gameSettings.guiScale;
		//	=================================
		//	Only ever scale up.
		//	=================================
		//
		// This used to force GUI scale 2 on anyone running 3 or 4, because a 186x198 book had to hold
		// a page of text no matter what. The layout follows the screen now, so a large scale is simply
		// a player who wants large text and gets it; only scale 1 still needs help, because nine
		// point text at scale 1 is unreadable on anything modern.
		if(prevGuiScale==1&&manual.allowGuiRescale())
			Minecraft.getMinecraft().gameSettings.guiScale = 2;
		activeManual = this;
	}

	@Override
	public boolean doesGuiPauseGame()
	{
		return false;
	}

	public String getSelectedEntry()
	{
		return selectedEntry;
	}

	public void setSelectedEntry(String string)
	{
		selectedEntry = string;
		if(string!=null)
			manual.openEntry(string);
	}

	public ManualInstance getManual()
	{
		return this.manual;
	}

	/**
	 * Width of the text column, and of everything the page types lay out inside it. Was the constant
	 * 120 in every page type.
	 */
	public int getPageWidth()
	{
		//Never zero: this ends up as the width argument to the font renderer's line splitter, and a
		//zero there is a page of one character per line.
		return Math.max(ManualLayout.MIN_TEXT_WIDTH, textWidth);
	}

	/**
	 * Height available to a page below its title, for anything that needs to know how much room it
	 * has.
	 */
	public int getPageHeight()
	{
		return Math.max(0, pageY+pageHeight-PAGE_PADDING-textY);
	}

	public int getPageX()
	{
		return textX;
	}

	public int getPageY()
	{
		return textY;
	}

	@Override
	public void initGui()
	{
		this.manual.openManual();

		//	=================================
		//	Fits the screen, not a sprite.
		//	=================================
		//
		// Everything below is measured off the screen size instead of a 186x198 book texture.
		ManualLayout layout = new ManualLayout(this.width, this.height, getRowSpace());
		guiLeft = layout.left;
		guiTop = layout.top;
		xSize = layout.width;
		ySize = layout.height;
		sidebarX = layout.sidebarX;
		sidebarWidth = layout.sidebarWidth;
		searchX = layout.getSearchX();
		searchY = layout.searchY;
		searchWidth = layout.getSearchWidth();
		searchHeight = ManualLayout.SEARCH_HEIGHT;
		pageX = layout.pageX;
		pageY = layout.pageY;
		pageWidth = layout.pageWidth;
		pageHeight = layout.pageHeight;
		navY = layout.navY;
		textX = layout.textX;
		textWidth = layout.textWidth;

		ManualEntry entry = manual.getEntry(selectedEntry);
		int header = 0;
		if(entry!=null)
		{
			header = manual.fontRenderer.FONT_HEIGHT+6;
			if(!manual.formatEntrySubtext(entry.getName()).isEmpty())
				header += manual.fontRenderer.FONT_HEIGHT;
			header += 4;
		}
		textY = layout.getTextY(header);

		this.buttonList.clear();
		this.pageButtons.clear();

		Keyboard.enableRepeatEvents(true);
		String previousSearch = searchField!=null?searchField.getText(): "";
		searchField = new GuiTextField(99, this.fontRenderer, searchX+4, searchY+3, searchWidth-8, searchHeight-6);
		searchField.setText(previousSearch);
		searchField.setTextColor(COLOUR_TEXT);
		searchField.setDisabledTextColour(COLOUR_LABEL);
		searchField.setEnableBackgroundDrawing(false);
		searchField.setMaxStringLength(48);
		searchField.setFocused(true);
		searchField.setCanLoseFocus(false);

		if(sidebar==null)
			sidebar = new GuiManualSidebar(this);
		sidebar.setBounds(sidebarX, layout.listY, sidebarWidth, layout.listHeight);
		rebuildSidebar();

		//	=================================
		//	Drawn buttons, not the book's arrow sprites.
		//	=================================
		//
		// The page arrows in manual.png are black: they were made to sit on tan paper. These three sit
		// on the leather, where black is invisible, so they are text on a panel like the close button.
		//Back is only meaningful once something is open; the index is always in view now.
		if(entry!=null||!previousSelectedEntry.isEmpty())
			this.buttonList.add(frameButton(1, guiLeft+PADDING+2, guiTop+4, 12, 12, "«"));
		this.buttonList.add(new GuiButtonManual(this, 4, guiLeft+xSize-PADDING-14, guiTop+4, 12, 12, "X")
				.setColour(0xff8c1e14, 0xffd62b1c).setTextColour(0xffffe4de, 0xffffffff));

		if(entry!=null&&entry.getPages().length > 1)
		{
			GuiButtonManual prev = frameButton(2, pageX+4, navY-1, 16, 12, "<");
			GuiButtonManual next = frameButton(3, pageX+pageWidth-20, navY-1, 16, 12, ">");
			prev.visible = page > 0;
			next.visible = page < entry.getPages().length-1;
			this.buttonList.add(prev);
			this.buttonList.add(next);
		}

		if(entry!=null)
		{
			IManualPage mPage = (page < 0||page >= entry.getPages().length)?null: entry.getPages()[page];
			if(mPage!=null)
				mPage.initPage(this, textX, textY, pageButtons);
			buttonList.addAll(pageButtons);
		}
	}

	/**
	 * A button that reads on the leather rather than on the paper.
	 */
	private GuiButtonManual frameButton(int id, int x, int y, int w, int h, String label)
	{
		return new GuiButtonManual(this, id, x, y, w, h, label)
				.setColour(0x30ffffff, 0x40f0a256)
				.setTextColour(COLOUR_TEXT, COLOUR_TEXT_HOVER);
	}

	private int getRowSpace()
	{
		return manual.fontRenderer.FONT_HEIGHT+3;
	}

	/**
	 * Rebuilds the index: search results if a search is running, otherwise the category tree with the
	 * open category expanded underneath itself.
	 */
	private void rebuildSidebar()
	{
		List<Row> rows = new ArrayList<>();
		if(searchResults!=null)
		{
			for(String key : searchResults)
				rows.add(new Row(GuiManualSidebar.TYPE_ENTRY, key, manual.formatEntryName(key), false));
			if(!searchSuggestions.isEmpty())
			{
				rows.add(new Row(GuiManualSidebar.TYPE_LABEL, null, "It looks like you meant:", false));
				for(String key : searchSuggestions)
					rows.add(new Row(GuiManualSidebar.TYPE_ENTRY, key, manual.formatEntryName(key), true));
			}
			if(rows.isEmpty())
				rows.add(new Row(GuiManualSidebar.TYPE_LABEL, null, "No entries found", false));
		}
		else
		{
			String[] categories = manual.getSortedCategoryList();
			if(categories==null||categories.length <= 1)
			{
				for(ManualEntry e : manual.manualContents.values())
					if(manual.showEntryInList(e))
						rows.add(new Row(GuiManualSidebar.TYPE_ENTRY, e.getName(), manual.formatEntryName(e.getName()), false));
			}
			else
				for(String category : categories)
					if(manual.showCategoryInList(category))
					{
						rows.add(new Row(GuiManualSidebar.TYPE_CATEGORY, category, manual.formatCategoryName(category), false));
						if(category.equals(selectedCategory))
							for(ManualEntry e : manual.manualContents.get(category))
								if(manual.showEntryInList(e))
									rows.add(new Row(GuiManualSidebar.TYPE_ENTRY, e.getName(), manual.formatEntryName(e.getName()), true));
					}
		}
		sidebar.setRows(rows);
	}

	/**
	 * Re-runs the search against whatever is in the box. Same matching the manual always had -- entry
	 * name, then the pages' own {@code listForSearch}, then a spelling pass -- only now it runs on
	 * every keystroke and its results land in the index instead of a popup.
	 */
	private void updateSearch()
	{
		String search = searchField!=null?searchField.getText(): null;
		searchSuggestions.clear();
		if(search==null||search.trim().isEmpty())
		{
			searchResults = null;
			rebuildSidebar();
			return;
		}
		search = search.toLowerCase(Locale.ENGLISH);
		List<String> found = new ArrayList<>();
		HashMap<String, String> spellcheck = new HashMap<>();
		for(ManualEntry e : manual.manualContents.values())
			if(manual.showEntryInList(e))
			{
				if(manual.formatEntryName(e.getName()).toLowerCase(Locale.ENGLISH).contains(search))
					found.add(e.getName());
				else
					spellcheck.put(manual.formatEntryName(e.getName()), e.getName());
			}
		ArrayList<String> corrections = ManualUtils.getPrimitiveSpellingCorrections(search,
				spellcheck.keySet().toArray(new String[spellcheck.keySet().size()]), 4);
		for(String key : spellcheck.keySet())
			if(!corrections.contains(key))
			{
				ManualEntry e = manual.getEntry(spellcheck.get(key));
				if(e==null)
					continue;
				for(IManualPage page : e.getPages())
					if(page.listForSearch(search))
					{
						found.add(e.getName());
						break;
					}
			}
		for(String correction : corrections)
			searchSuggestions.add(spellcheck.get(correction));
		searchResults = found;
		sidebar.resetScroll();
		rebuildSidebar();
	}

	private void openEntryFromList(String key)
	{
		previousSelectedEntry.clear();
		setSelectedEntry(key);
		page = 0;
		//Keep the tree pointing at where the entry actually lives, so clearing a search leaves the
		//index open on it rather than collapsed.
		ManualEntry entry = manual.getEntry(key);
		if(entry!=null&&entry.getCategory()!=null)
			selectedCategory = entry.getCategory();
		this.initGui();
	}

	@Override
	public void drawScreen(int mx, int my, float f)
	{
		manualTick++;
		this.drawDefaultBackground();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		boolean uni = manual.fontRenderer.getUnicodeFlag();
		manual.fontRenderer.setUnicodeFlag(true);
		manual.entryRenderPre();

		drawFrame();

		//Sidebar
		drawPanel(sidebarX-3, guiTop+TITLE_BAR-3, sidebarX+sidebarWidth+3, guiTop+ySize-PADDING+1);
		drawRect(searchX, searchY, searchX+searchWidth, searchY+searchHeight, COLOUR_SEARCH_BG);
		drawOutline(searchX, searchY, searchX+searchWidth, searchY+searchHeight, COLOUR_SEARCH_EDGE);
		sidebar.draw(mx, my);

		//Page
		drawRect(pageX+2, pageY+2, pageX+pageWidth+2, pageY+pageHeight+2, COLOUR_PAPER_SHADOW);
		drawRect(pageX, pageY, pageX+pageWidth, pageY+pageHeight, COLOUR_PAPER);
		drawOutline(pageX, pageY, pageX+pageWidth, pageY+pageHeight, COLOUR_PAPER_EDGE);

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		ManualEntry entry = manual.getEntry(selectedEntry);
		if(entry!=null)
		{
			int titleY = pageY+PAGE_PADDING;
			drawTitle(manual.formatEntryName(entry.getName()), pageX+pageWidth/2,
					titleY+manual.fontRenderer.FONT_HEIGHT/2, manual.getTitleColour(), true);
			String subtext = manual.formatEntrySubtext(entry.getName());
			if(!subtext.isEmpty())
				drawTitle(subtext, pageX+pageWidth/2,
						titleY+manual.fontRenderer.FONT_HEIGHT+manual.fontRenderer.FONT_HEIGHT/2, manual.getSubTitleColour(), false);

			//A rule under the header, so the title reads as a heading and not as the first line.
			drawRect(textX, textY-6, textX+textWidth, textY-5, 0x22000000);

			GlStateManager.color(1, 1, 1);
			IManualPage mPage = (page < 0||page >= entry.getPages().length)?null: entry.getPages()[page];
			if(mPage!=null)
				mPage.renderPage(this, textX, textY, mx, my);

			//Page counter, on the frame under the page rather than on the paper.
			String counter = (page+1)+" / "+entry.getPages().length;
			this.drawCenteredStringScaled(manual.fontRenderer, counter, pageX+pageWidth/2, navY+5,
					manual.getPagenumberColour(), 1, false);
		}
		else
			drawWelcomePage();

		//Title bar
		drawTitle(manual.getManualName(), guiLeft+xSize/2, guiTop+TITLE_BAR/2-1, COLOUR_TITLE, true);

		this.searchField.drawTextBox();
		if(searchField.getText().isEmpty())
		{
			String label = manual.getSearchLabel();
			if(!label.isEmpty())
				//Clear of the caret, which sits at the start of an empty field.
				this.fontRenderer.drawString(label, searchX+9, searchY+4, COLOUR_LABEL);
		}

		manual.fontRenderer.setUnicodeFlag(uni);
		super.drawScreen(mx, my, f);
		GlStateManager.enableBlend();
		manual.entryRenderPost();
	}

	/**
	 * =================================
	 * Every title is drawn in the same font, whatever the page did.
	 * =================================
	 * <p>
	 * <strong>This is why the title bar used to change size as you turned pages.</strong> The manual's
	 * font renderer is one shared object, and the unicode flag on it decides which glyph sheet the
	 * text comes from -- the ASCII sheet, which is wide and letter-spaced, or the unicode sheet, which
	 * is noticeably tighter and thinner at the same {@code FONT_HEIGHT}. {@link #drawScreen} turns the
	 * flag on for the whole screen, but several page types turned it off to draw items and their
	 * tooltips and never turned it back (see the balanced flags in {@code ManualPages},
	 * {@code ManualPageBlueprint} and {@code ManualPageMultiblock}, which this pairs with), and the
	 * title bar is drawn <em>after</em> the page. A recipe page therefore left the title in the ASCII
	 * font and a plain text page left it in the unicode one.
	 * <p>
	 * Fixing the leaks alone would have settled on the tighter of the two, so the flag is pinned here
	 * instead of inherited. It is pinned <em>off</em> because that is the rendering the title is
	 * designed for: {@link ManualInstance#titleRenderPre()} widens the letter spacing through
	 * {@code renderDefaultChar}, which only the ASCII path calls, while {@code getStringWidth} counts
	 * the spacing either way -- so with the flag on, the hook does nothing to the glyphs and the
	 * string is measured wider than it draws, leaving the title off-centre as well as tighter.
	 */
	private void drawTitle(String title, int x, int y, int colour, boolean bold)
	{
		boolean uni = manual.fontRenderer.getUnicodeFlag();
		manual.fontRenderer.setUnicodeFlag(TITLE_UNICODE);
		manual.titleRenderPre();
		this.drawCenteredStringScaled(manual.fontRenderer, (bold?TextFormatting.BOLD.toString(): "")+title,
				x, y, colour, 1, false);
		manual.titleRenderPost();
		manual.fontRenderer.setUnicodeFlag(uni);
	}

	/**
	 * The page that is showing before an entry is picked: the manual's name, then the welcome text,
	 * all of it centred.
	 * <p>
	 * The paragraphs are laid out as one block by {@link ManualLayout#stackBlocks}, so on a roomy
	 * screen the whole thing sits in the middle of the page and on a cramped one it gives up its
	 * paragraph spacing and rides the top rather than spilling past the paper. Nothing is drawn below
	 * the bottom margin either way -- a welcome page that runs off the paper is the first thing a
	 * player would see.
	 */
	private void drawWelcomePage()
	{
		FontRenderer fr = manual.fontRenderer;
		int centreX = pageX+pageWidth/2;
		int top = pageY+PAGE_PADDING;
		int bottom = pageY+pageHeight-PAGE_PADDING;

		//Measured in the font the paragraphs are actually drawn in, which is the screen's, not the
		//title's -- the two disagree about how many lines a paragraph takes.
		String[] paragraphs = manual.getWelcomeText();
		int[] blockLines = new int[paragraphs.length+1];
		blockLines[0] = 1;//the title
		for(int i = 0; i < paragraphs.length; i++)
			blockLines[i+1] = ManualUtils.splitToWidth(fr, paragraphs[i], textWidth).size();

		int[] offsets = ManualLayout.stackBlocks(bottom-top, blockLines, fr.FONT_HEIGHT, fr.FONT_HEIGHT);
		drawTitle(manual.getManualName(), centreX, top+offsets[0]+fr.FONT_HEIGHT/2, manual.getTitleColour(), true);
		for(int i = 0; i < paragraphs.length; i++)
			ManualUtils.drawCenteredSplitString(fr, paragraphs[i], centreX, top+offsets[i+1], textWidth,
					manual.getTextColour(), bottom);
	}

	private void drawFrame()
	{
		int right = guiLeft+xSize;
		int bottom = guiTop+ySize;
		drawRect(guiLeft-1, guiTop-1, right+1, bottom+1, COLOUR_EDGE);
		drawRect(guiLeft, guiTop, right, bottom, COLOUR_LEATHER);
		//Grain: evenly spaced faint streaks. Cheap, deterministic, and it never blurs.
		for(int y = guiTop+4; y < bottom-3; y += 7)
			drawRect(guiLeft+2, y, right-2, y+1, COLOUR_LEATHER_GRAIN);
		drawRect(guiLeft, guiTop, right, guiTop+1, COLOUR_LEATHER_BEVEL);
		drawStitching(guiLeft+3, guiTop+3, right-3, bottom-3);
		//Separator under the title bar
		drawRect(guiLeft+PADDING, guiTop+TITLE_BAR-3, right-PADDING, guiTop+TITLE_BAR-2, COLOUR_STITCH);
	}

	private void drawStitching(int x0, int y0, int x1, int y1)
	{
		for(int x = x0; x < x1-3; x += 6)
		{
			drawRect(x, y0, x+3, y0+1, COLOUR_STITCH);
			drawRect(x, y1-1, x+3, y1, COLOUR_STITCH);
		}
		for(int y = y0; y < y1-3; y += 6)
		{
			drawRect(x0, y, x0+1, y+3, COLOUR_STITCH);
			drawRect(x1-1, y, x1, y+3, COLOUR_STITCH);
		}
	}

	private void drawPanel(int x0, int y0, int x1, int y1)
	{
		drawRect(x0, y0, x1, y1, COLOUR_PANEL);
		drawOutline(x0, y0, x1, y1, COLOUR_PANEL_EDGE);
	}

	private void drawOutline(int x0, int y0, int x1, int y1, int colour)
	{
		drawRect(x0, y0, x1, y0+1, colour);
		drawRect(x0, y1-1, x1, y1, colour);
		drawRect(x0, y0, x0+1, y1, colour);
		drawRect(x1-1, y0, x1, y1, colour);
	}

	@Override
	public void onGuiClosed()
	{
		this.manual.closeManual();
		Keyboard.enableRepeatEvents(false);
		super.onGuiClosed();
		if(prevGuiScale!=-1&&manual.allowGuiRescale())
			Minecraft.getMinecraft().gameSettings.guiScale = prevGuiScale;
	}

	@Override
	public void actionPerformed(GuiButton button)
	{
		if(buttonHeld)
			return;
		if(button.id==1)
		{
			goBack();
		}
		else if(button.id==2)
		{
			if(page > 0)
			{
				page--;
				this.initGui();
			}
		}
		else if(button.id==3)
		{
			ManualEntry entry = manual.getEntry(selectedEntry);
			if(entry!=null&&page < entry.getPages().length-1)
			{
				page++;
				this.initGui();
			}
		}
		else if(button.id==4)
		{
			this.mc.displayGuiScreen(null);
			return;
		}
		else if(pageButtons.contains(button)&&manual.getEntry(selectedEntry)!=null)
		{
			ManualEntry entry = manual.getEntry(selectedEntry);
			IManualPage mPage = (page < 0||page >= entry.getPages().length)?null: entry.getPages()[page];
			if(mPage!=null)
				mPage.buttonPressed(this, button);
		}
		buttonHeld = true;
	}

	private void goBack()
	{
		if(selectedEntry!=null)
			setSelectedEntry(previousSelectedEntry.isEmpty()?null: previousSelectedEntry.pop());
		else if(selectedCategory!=null)
			selectedCategory = null;
		page = 0;
		this.initGui();
	}

	public void drawCenteredStringScaled(FontRenderer fr, String s, int x, int y, int colour, float scale, boolean shadow)
	{
		int xx = (int)Math.floor(x/scale-(fr.getStringWidth(s)/2));
		int yy = (int)Math.floor(y/scale-(fr.FONT_HEIGHT/2));
		if(scale!=1)
			GlStateManager.scale(scale, scale, scale);
		fr.drawString(s, xx, yy, colour, shadow);
		if(scale!=1)
			GlStateManager.scale(1/scale, 1/scale, 1/scale);
	}

	@Override
	public void drawGradientRect(int x1, int y1, int x2, int y2, int colour1, int colour2)
	{
		super.drawGradientRect(x1, y1, x2, y2, colour1, colour2);
		GlStateManager.enableBlend();
	}

	@Override
	public void renderToolTip(ItemStack stack, int x, int y)
	{
		super.renderToolTip(stack, x, y);
	}

	@Override
	public List<String> getItemToolTip(ItemStack stack)
	{
		List<String> tooltip = super.getItemToolTip(stack);
		ManualEntry entry = manual.getEntry(selectedEntry);
		if(entry!=null)
		{
			IManualPage mPage = (page < 0||page >= entry.getPages().length)?null: entry.getPages()[page];
			if(mPage!=null&&mPage.getHighlightedStack()==stack)
			{
				ManualLink link = this.manual.getManualLink(stack);
				if(link!=null)
					tooltip.add(manual.formatLink(link));
			}
		}
		return tooltip;
	}

	@Override
	public void drawHoveringText(List text, int x, int y, FontRenderer font)
	{
		manual.tooltipRenderPre();
		super.drawHoveringText(text, x, y, font);
		manual.tooltipRenderPost();
	}

	@Override
	public void handleMouseInput() throws IOException
	{
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if(wheel==0)
			return;
		int mx = Mouse.getEventX()*this.width/this.mc.displayWidth;
		int my = this.height-Mouse.getEventY()*this.height/this.mc.displayHeight-1;
		if(sidebar!=null&&sidebar.isMouseOver(mx, my))
		{
			sidebar.scroll(wheel > 0?-1: 1);
			return;
		}
		ManualEntry entry = manual.getEntry(selectedEntry);
		if(entry!=null)
		{
			if(wheel > 0&&page > 0)
			{
				page--;
				this.initGui();
			}
			else if(wheel < 0&&page < entry.getPages().length-1)
			{
				page++;
				this.initGui();
			}
		}
	}

	@Override
	public void mouseClicked(int mx, int my, int button) throws IOException
	{
		super.mouseClicked(mx, my, button);
		if(button==0)
		{
			Row row = sidebar!=null?sidebar.getRowAt(mx, my): null;
			if(row!=null)
			{
				if(row.type==GuiManualSidebar.TYPE_CATEGORY)
				{
					selectedCategory = row.key.equals(selectedCategory)?null: row.key;
					this.initGui();
				}
				else
					openEntryFromList(row.key);
				return;
			}
			ManualEntry entry = manual.getEntry(selectedEntry);
			if(entry!=null)
			{
				IManualPage mPage = (page < 0||page >= entry.getPages().length)?null: entry.getPages()[page];
				if(mPage!=null)
				{
					ItemStack highlighted = mPage.getHighlightedStack();
					if(!highlighted.isEmpty())
					{
						ManualLink link = this.getManual().getManualLink(highlighted);
						if(link!=null)
							link.changePage(this);
					}
				}
			}
		}
		else if(button==1)
		{
			if(searchField!=null&&searchField.getText()!=null&&!searchField.getText().isEmpty())
			{
				searchField.setText("");
				updateSearch();
			}
			else
				goBack();
		}
		lastClick = new int[]{mx, my};
		if(this.searchField!=null)
			this.searchField.mouseClicked(mx, my, button);
	}

	@Override
	protected void mouseReleased(int mx, int my, int action)
	{
		super.mouseReleased(mx, my, action);
		if(buttonHeld&&(action==0||action==1))
			buttonHeld = false;
		lastClick = null;
		lastDrag = null;
	}

	@Override
	protected void mouseClickMove(int mx, int my, int button, long time)
	{
		//Dragging only means anything inside the page pane -- the multiblock preview spins on it.
		if(lastClick!=null&&isOverPage(lastClick[0], lastClick[1])&&manual.getEntry(selectedEntry)!=null
				&&page >= 0&&page < manual.getEntry(selectedEntry).getPages().length)
		{
			ManualEntry entry = manual.getEntry(selectedEntry);
			if(lastDrag==null)
				lastDrag = new int[]{mx, my};
			entry.getPages()[page].mouseDragged(textX, textY, lastClick[0], lastClick[1], mx, my, lastDrag[0], lastDrag[1], button);
			lastDrag = new int[]{mx, my};
		}
	}

	private boolean isOverPage(int mx, int my)
	{
		return mx >= pageX&&mx < pageX+pageWidth&&my >= pageY&&my < pageY+pageHeight;
	}

	@Override
	protected void keyTyped(char c, int i) throws IOException
	{
		if(i==Keyboard.KEY_ESCAPE)
		{
			//Escape backs out of a search before it backs out of the manual.
			if(searchField!=null&&!searchField.getText().isEmpty())
			{
				searchField.setText("");
				updateSearch();
				return;
			}
			super.keyTyped(c, i);
			return;
		}
		if(this.searchField!=null&&this.searchField.textboxKeyTyped(c, i))
			updateSearch();
		else
			super.keyTyped(c, i);
	}
}
