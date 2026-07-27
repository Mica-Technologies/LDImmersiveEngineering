/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui.elements;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/**
 * A small two-series line plot for the console's Stats tab.
 * <p>
 * Drawn with filled rectangles rather than GL line primitives: at this size a line would
 * be a single hairline that disappears against the panel, and a filled column reads as a
 * bar-ish trace that stays legible at any GUI scale. Both series share one vertical scale
 * so intake and delivery can be compared by eye.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiLineGraph extends Gui
{
	private static final int COL_BACKDROP = 0xFF141414;
	private static final int COL_FRAME = 0xFF3A3A3A;
	private static final int COL_GRID = 0xFF262626;

	private final int x, y, width, height;

	public GuiLineGraph(int x, int y, int width, int height)
	{
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	/**
	 * @param in    per-second intake samples, oldest first
	 * @param out   per-second delivery samples, oldest first
	 * @param inCol colour for the intake trace
	 * @param outCol colour for the delivery trace
	 * @return the peak value the plot was scaled to, so a caller can label the axis
	 */
	public int draw(int[] in, int[] out, int inCol, int outCol)
	{
		drawRect(x, y, x+width, y+height, COL_FRAME);
		drawRect(x+1, y+1, x+width-1, y+height-1, COL_BACKDROP);

		//Horizontal quarter lines, so the eye has something to judge height against.
		for(int i = 1; i < 4; i++)
		{
			int gy = y+1+(height-2)*i/4;
			drawRect(x+1, gy, x+width-1, gy+1, COL_GRID);
		}

		int peak = 0;
		for(int value : in)
			peak = Math.max(peak, value);
		for(int value : out)
			peak = Math.max(peak, value);
		if(peak <= 0)
		{
			GlStateManager.color(1, 1, 1, 1);
			return 0;
		}

		//Delivery underneath, intake on top: intake is usually the larger of the two, so
		//drawing it second keeps it from being hidden.
		plot(out, peak, outCol);
		plot(in, peak, inCol);
		GlStateManager.color(1, 1, 1, 1);
		return peak;
	}

	private void plot(int[] samples, int peak, int colour)
	{
		if(samples.length==0)
			return;
		int plotWidth = width-2;
		int plotHeight = height-2;
		//Newest sample sits at the right edge; a partly-filled history leaves the left blank
		//rather than stretching a handful of samples across the whole width.
		for(int i = 0; i < samples.length; i++)
		{
			int columns = Math.max(1, plotWidth/Math.max(1, samples.length));
			int cx = x+1+plotWidth-(samples.length-i)*columns;
			if(cx < x+1)
				continue;
			int barHeight = (int)((long)samples[i]*plotHeight/peak);
			if(barHeight <= 0&&samples[i] > 0)
				barHeight = 1;
			if(barHeight <= 0)
				continue;
			drawRect(cx, y+1+plotHeight-barHeight, Math.min(cx+columns, x+width-1),
					y+1+plotHeight, colour);
		}
	}
}
