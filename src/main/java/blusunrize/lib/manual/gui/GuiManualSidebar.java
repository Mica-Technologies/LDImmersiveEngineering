/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.lib.manual.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The index down the left-hand side of the manual.
 * <p>
 * <strong>Not a {@link net.minecraft.client.gui.GuiButton}.</strong> The old index was one, which is
 * why it could only ever show a flat list of strings that all meant the same thing: a button has one
 * id and one action. This holds rows that know what they are -- a category, an entry underneath it,
 * or a plain label -- so the same widget can show the category tree, an expanded category, and a set
 * of search results without the screen having to swap widgets around underneath itself.
 */
public class GuiManualSidebar
{
	public static final int TYPE_CATEGORY = 0;
	public static final int TYPE_ENTRY = 1;
	public static final int TYPE_LABEL = 2;

	public static class Row
	{
		public final int type;
		/**
		 * The category or entry name this row stands for. Null on labels, which are not clickable.
		 */
		public final String key;
		public final String display;
		public final boolean indented;

		public Row(int type, String key, String display, boolean indented)
		{
			this.type = type;
			this.key = key;
			this.display = display;
			this.indented = indented;
		}

		public boolean isClickable()
		{
			return type!=TYPE_LABEL&&key!=null;
		}
	}

	private final GuiManual gui;
	private final List<Row> rows = new ArrayList<>();
	private int x;
	private int y;
	private int width;
	private int height;
	private int offset;

	public GuiManualSidebar(GuiManual gui)
	{
		this.gui = gui;
	}

	public void setBounds(int x, int y, int width, int height)
	{
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		clampOffset();
	}

	public void setRows(List<Row> newRows)
	{
		//The offset is deliberately kept across a rebuild: expanding a category should not throw the
		//reader back to the top of a list they had scrolled halfway down.
		this.rows.clear();
		this.rows.addAll(newRows);
		clampOffset();
	}

	public void resetScroll()
	{
		this.offset = 0;
	}

	public int getRowHeight()
	{
		return gui.getManual().fontRenderer.FONT_HEIGHT+3;
	}

	public int getVisibleRows()
	{
		return Math.max(1, height/getRowHeight());
	}

	public int getMaxOffset()
	{
		return Math.max(0, rows.size()-getVisibleRows());
	}

	private void clampOffset()
	{
		if(offset > getMaxOffset())
			offset = getMaxOffset();
		if(offset < 0)
			offset = 0;
	}

	public void scroll(int amount)
	{
		offset += amount;
		clampOffset();
	}

	public boolean isMouseOver(int mx, int my)
	{
		return mx >= x&&mx < x+width&&my >= y&&my < y+height;
	}

	public Row getRowAt(int mx, int my)
	{
		if(!isMouseOver(mx, my))
			return null;
		int rowIndex = (my-y)/getRowHeight();
		//The list can be a couple of pixels taller than a whole number of rows; that strip belongs to
		//nothing, not to the row that would have been next.
		if(rowIndex >= getVisibleRows())
			return null;
		int index = offset+rowIndex;
		if(index < 0||index >= rows.size())
			return null;
		Row row = rows.get(index);
		return row.isClickable()?row: null;
	}

	public void draw(int mx, int my)
	{
		FontRenderer fr = gui.getManual().fontRenderer;
		boolean uni = fr.getUnicodeFlag();
		fr.setUnicodeFlag(true);
		GlStateManager.color(1, 1, 1, 1);

		int rowHeight = getRowHeight();
		int visible = getVisibleRows();
		boolean scrollbar = getMaxOffset() > 0;
		int textWidth = width-(scrollbar?7: 2);
		for(int i = 0; i < visible; i++)
		{
			int index = offset+i;
			if(index >= rows.size())
				break;
			Row row = rows.get(index);
			int rowY = y+i*rowHeight;
			boolean hovered = row.isClickable()&&mx >= x&&mx < x+width&&my >= rowY&&my < rowY+rowHeight;
			boolean selected = row.isClickable()&&
					(row.type==TYPE_ENTRY?row.key.equals(gui.getSelectedEntry()): row.key.equals(gui.selectedCategory));

			if(selected)
				Gui.drawRect(x, rowY, x+width, rowY+rowHeight, GuiManual.COLOUR_ROW_SELECTED);
			else if(hovered)
				Gui.drawRect(x, rowY, x+width, rowY+rowHeight, GuiManual.COLOUR_ROW_HOVER);

			int colour = row.type==TYPE_LABEL?GuiManual.COLOUR_LABEL
					: selected?GuiManual.COLOUR_TEXT_SELECTED
					: hovered?GuiManual.COLOUR_TEXT_HOVER
					: row.type==TYPE_CATEGORY?GuiManual.COLOUR_TEXT_CATEGORY: GuiManual.COLOUR_TEXT;
			int indent = row.indented?7: 1;
			String text = row.display;
			if(row.type==TYPE_CATEGORY)
			{
				//A category says whether it is open without needing a texture for it.
				String marker = row.key.equals(gui.selectedCategory)?"- ": "+ ";
				text = TextFormatting.BOLD+marker+text;
			}
			int available = textWidth-indent;
			if(fr.getStringWidth(text) > available)
				text = fr.trimStringToWidth(text, available-fr.getStringWidth("..."))+"...";
			fr.drawString(text, x+indent, rowY+2, colour, false);
		}

		if(scrollbar)
		{
			int trackX = x+width-5;
			Gui.drawRect(trackX, y, trackX+4, y+height, GuiManual.COLOUR_SCROLL_TRACK);
			int barHeight = Math.max(8, height*visible/rows.size());
			int barY = y+(height-barHeight)*offset/getMaxOffset();
			Gui.drawRect(trackX, barY, trackX+4, barY+barHeight, GuiManual.COLOUR_SCROLL_BAR);
		}

		fr.setUnicodeFlag(uni);
		GlStateManager.color(1, 1, 1, 1);
	}
}
