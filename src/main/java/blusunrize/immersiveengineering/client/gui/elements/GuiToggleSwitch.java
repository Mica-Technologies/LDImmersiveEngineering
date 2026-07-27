/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui.elements;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * A breaker-style flip switch: the segment on/off control.
 * <p>
 * Drawn from primitives, with the throw visibly at the top or bottom of its travel so the
 * state reads at a glance from across the panel rather than needing a label.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiToggleSwitch extends GuiButton
{
	private static final int COL_BODY = 0xFF1E1E1E;
	private static final int COL_EDGE = 0xFF5A5A5A;
	private static final int COL_ON = 0xFF3FBF4F;
	private static final int COL_OFF = 0xFF8A2E2E;
	private static final int COL_TRIPPED = 0xFFD4462B;
	private static final int COL_THROW = 0xFFCFCFCF;

	public boolean state;
	/**
	 * A tripped breaker reads differently from one somebody switched off, so it gets its
	 * own colour rather than looking like an ordinary "off".
	 */
	public boolean tripped;

	public GuiToggleSwitch(int buttonId, int x, int y, int w, int h, boolean state)
	{
		super(buttonId, x, y, w, h, "");
		this.state = state;
	}

	public GuiToggleSwitch setTripped(boolean tripped)
	{
		this.tripped = tripped;
		return this;
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks)
	{
		if(!visible)
			return;
		this.hovered = mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;

		drawRect(x, y, x+width, y+height, COL_EDGE);
		drawRect(x+1, y+1, x+width-1, y+height-1, COL_BODY);

		int indicator = tripped?COL_TRIPPED: state?COL_ON: COL_OFF;
		int half = height/2;
		//The lit half shows which way the breaker is thrown.
		if(state)
			drawRect(x+2, y+2, x+width-2, y+half, indicator);
		else
			drawRect(x+2, y+half, x+width-2, y+height-2, indicator);

		//The throw itself.
		int throwY = state?y+2: y+height-6;
		drawRect(x+3, throwY, x+width-3, throwY+4, hovered?0xFFFFFFFF: COL_THROW);
		GlStateManager.color(1, 1, 1, 1);
	}

	@Override
	public boolean mousePressed(Minecraft mc, int mouseX, int mouseY)
	{
		boolean pressed = enabled&&visible
				&&mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;
		if(pressed)
			state = !state;
		return pressed;
	}
}
