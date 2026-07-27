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
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * A button drawn from primitives, for panels that carry no texture sheet.
 * <p>
 * {@link GuiButtonIE} blits a rectangle out of a texture at a given u,v; pointing it at a
 * sheet that has no button artwork there just stretches whatever pixels happen to sit at
 * that corner behind the label. The grid panels are deliberately texture-free so their
 * layout can be reworked without round-tripping a PNG, so they need a button that matches.
 * <p>
 * Supports an {@link #active} flag: toggles read as lit or unlit at a glance rather than
 * forcing the player to parse the caption.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiButtonFlat extends GuiButton
{
	private static final int COL_EDGE = 0xFF4A4A4A;
	private static final int COL_EDGE_HOVER = 0xFF6E6E6E;
	private static final int COL_IDLE = 0xFF2B2B2B;
	private static final int COL_HOVER = 0xFF3D3D3D;
	private static final int COL_DISABLED = 0xFF1E1E1E;
	private static final int COL_ACTIVE = 0xFF2E4A32;
	private static final int COL_ACTIVE_HOVER = 0xFF3C6142;

	/**
	 * Lit state for toggle-style buttons. Ignored by plain action buttons.
	 */
	public boolean active;

	public GuiButtonFlat(int buttonId, int x, int y, int w, int h, String label)
	{
		super(buttonId, x, y, w, h, label);
	}

	public GuiButtonFlat setActive(boolean active)
	{
		this.active = active;
		return this;
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks)
	{
		if(!visible)
			return;
		this.hovered = enabled&&mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;

		int fill = !enabled?COL_DISABLED
				: active?(hovered?COL_ACTIVE_HOVER: COL_ACTIVE)
				: (hovered?COL_HOVER: COL_IDLE);
		drawRect(x, y, x+width, y+height, hovered?COL_EDGE_HOVER: COL_EDGE);
		drawRect(x+1, y+1, x+width-1, y+height-1, fill);

		FontRenderer font = mc.fontRenderer;
		int textColour = !enabled?0x707070: hovered?Lib.COLOUR_I_ImmersiveOrange: 0xD0D0D0;
		//Trim rather than overflow: several of these captions carry a segment name.
		String label = displayString;
		if(font.getStringWidth(label) > width-6)
			label = font.trimStringToWidth(label, width-6);
		drawCenteredString(font, label, x+width/2, y+(height-8)/2, textColour);
		GlStateManager.color(1, 1, 1, 1);
	}

	@Override
	public boolean mousePressed(Minecraft mc, int mouseX, int mouseY)
	{
		return enabled&&visible
				&&mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;
	}
}
