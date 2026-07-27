/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui.elements;

import blusunrize.immersiveengineering.api.Lib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * One entry in the console's vertical tab column.
 * <p>
 * Drawn entirely from primitives rather than a texture sheet: the console GUI is
 * deliberately art-asset free for now so its layout can be iterated without round-tripping
 * a PNG (see the plan's risk table).
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiTabButton extends GuiButton
{
	private static final int COL_IDLE = 0xFF2B2B2B;
	private static final int COL_HOVER = 0xFF3D3D3D;
	private static final int COL_SELECTED = 0xFF4A4A4A;
	private static final int COL_DISABLED = 0xFF232323;
	private static final int COL_ACCENT = 0xFF000000|Lib.COLOUR_I_ImmersiveOrange;

	public boolean selected;
	/**
	 * Shown when the tab is disabled, to say why rather than just being dead.
	 */
	public String disabledHint = "";

	public GuiTabButton(int buttonId, int x, int y, int w, int h, String label)
	{
		super(buttonId, x, y, w, h, label);
	}

	public GuiTabButton setSelected(boolean selected)
	{
		this.selected = selected;
		return this;
	}

	public GuiTabButton setDisabledHint(String hint)
	{
		this.disabledHint = hint;
		this.enabled = false;
		return this;
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks)
	{
		if(!visible)
			return;
		this.hovered = mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;

		int background = !enabled?COL_DISABLED: selected?COL_SELECTED: hovered?COL_HOVER: COL_IDLE;
		drawRect(x, y, x+width, y+height, background);
		//A lit edge marks the active tab, the way a selected page tab would be.
		if(selected)
			drawRect(x+width-2, y, x+width, y+height, COL_ACCENT);

		int textColour = !enabled?0x707070: selected?0xFFFFFF: hovered?Lib.COLOUR_I_ImmersiveOrange: 0xC0C0C0;
		mc.fontRenderer.drawString(displayString, x+6, y+(height-8)/2, textColour);
		GlStateManager.color(1, 1, 1, 1);
	}

	@Override
	public boolean mousePressed(Minecraft mc, int mouseX, int mouseY)
	{
		return enabled&&visible&&mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;
	}
}
