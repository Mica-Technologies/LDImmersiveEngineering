/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.render;

import blusunrize.immersiveengineering.client.ClientUtils;
import blusunrize.immersiveengineering.common.blocks.signage.SignLayout;
import blusunrize.immersiveengineering.common.blocks.signage.SignTextFlow;
import blusunrize.immersiveengineering.common.blocks.signage.TileEntityUtilitySign;
import blusunrize.immersiveengineering.common.blocks.signage.UtilitySignKind;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;

/**
 * Draws the lettering on a utility pole tag. The plate itself is not drawn here.
 * <p>
 * <strong>Only the text costs anything per frame.</strong> The plate is a flat textured slab baked
 * into the chunk mesh like any other block model -- one of sixty-four, picked by the blockstate from
 * the kind and the facing -- because a pole line is dozens of tags and a renderer that drew the
 * plate as well would be paying for the ninety-nine percent of a sign that never changes. The tile
 * entity's {@code getMaxRenderDistanceSquared} then keeps even this off the books past forty-eight
 * blocks, where the plate is two pixels across and the number was never legible.
 * <p>
 * <strong>It does not billboard.</strong> The text is fixed to the plate and turns with it, so a
 * tag bolted to the south face of a pole reads from the south and is invisible from the north --
 * correctly. Text that swivelled to follow the player would give away that there is nothing really
 * there, which is the same reason the Gas Station Pump's price is painted on its panel rather than
 * floated beside the crosshair.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class TileRenderUtilitySign extends TileEntitySpecialRenderer<TileEntityUtilitySign>
{
	/**
	 * How far the lettering stands off the plate. Enough to beat depth-buffer precision at the
	 * distance a sign is read from, small enough that it never shows as a gap from an angle.
	 */
	private static final double LIFT = 0.002;

	@Override
	public void render(TileEntityUtilitySign tile, double x, double y, double z, float partialTicks,
					   int destroyStage, float alpha)
	{
		if(tile.getWorld()==null||!tile.getWorld().isBlockLoaded(tile.getPos(), false))
			return;
		UtilitySignKind kind = tile.getKind();
		if(kind.getLines()==0)
			//The line crossing diamond is a symbol, not a label. Nothing to draw and no matrix to
			//push for it.
			return;
		FontRenderer font = ClientUtils.font();
		if(font==null)
			return;
		boolean anything = false;
		for(int i = 0; i < kind.getLines(); i++)
			if(!tile.getLine(i).isEmpty())
			{
				anything = true;
				break;
			}
		if(!anything)
			//A blank plate is a perfectly ordinary thing to hang, and drawing nothing should cost
			//nothing.
			return;

		EnumFacing facing = tile.getFacing();
		GlStateManager.pushMatrix();
		GlStateManager.translate(x+0.5, y+0.5, z+0.5);
		//Turn the frame so that local +z points out of the plate. The facing is the direction the
		//plate's back points -- toward the pole -- so the readable face looks the other way.
		GlStateManager.rotate(180-facing.getHorizontalAngle(), 0, 1, 0);
		GlStateManager.translate(0, 0, -(0.5-TileEntityUtilitySign.THICKNESS/16d)+LIFT);
		//Lettering is paint, not a lit surface: a pole number that went black at dusk would be
		//useless at exactly the hour somebody is out with a torch reading it.
		GlStateManager.disableLighting();
		if(kind.isTurned())
		{
			//The one plate that really is a line lying on its side: a quarter turn clockwise about
			//the plate's own normal, so the string advances downwards with every character's top
			//edge toward the right, which is how a tower number is stencilled onto a leg.
			GlStateManager.rotate(-90, 0, 0, 1);
			drawLine(font, kind, tile.getLine(0), 0);
		}
		else if(kind.isStacked())
			//A strip six pixels wide and fourteen tall holds "M31390V" only one way round, and it
			//is the way the real ones are printed: a column of upright characters reading
			//downwards, not a line of them turned on their side.
			drawStacked(font, kind, tile);
		else
			for(int i = 0; i < kind.getLines(); i++)
				drawLine(font, kind, tile.getLine(i), i);

		GlStateManager.enableLighting();
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.popMatrix();
	}

	/**
	 * One line, centred along the plate and scaled to fill it -- see {@link SignLayout}, which the
	 * editing window's preview uses too so the two cannot disagree.
	 */
	private void drawLine(FontRenderer font, UtilitySignKind kind, String text, int index)
	{
		if(text.isEmpty())
			return;
		int width = font.getStringWidth(text);
		if(width <= 0)
			return;
		float scale = SignLayout.scaleFor(kind, index, width)/16f;
		GlStateManager.pushMatrix();
		//Down the plate, in the frame the rotation above has already turned.
		GlStateManager.translate(0, -SignLayout.lineCentre(kind, index)/16d, 0);
		//Negative Y because a font renderer draws downwards and the world does not.
		GlStateManager.scale(scale, -scale, scale);
		//Centred on the paint rather than on the glyph cell: the cell's last row is the descender,
		//which a pole number never uses, and the trailing pixel of a string width is the gap after
		//the last letter. Centring on those puts the line half a pixel high and half a pixel left.
		font.drawString(text, -SignLayout.inkWidth(width)/2f, -SignLayout.INK_HEIGHT/2f,
				kind.getTextColour(), false);
		GlStateManager.popMatrix();
	}

	/**
	 * Columns of upright characters, one to a row, reading downwards -- what a vertical strip and
	 * both transmission tower identifiers are actually printed as. Leading and trailing space is
	 * trimmed off each group before it is split into rows, the same way a line's own trailing gap
	 * is trimmed by {@link SignLayout#inkWidth}, so a plate with only spaces typed into it is not a
	 * column that starts several blank rows down.
	 * <p>
	 * Every character on the plate shares one scale -- see {@link SignLayout#stackedScale} -- so
	 * the columns read as one uniform run the way a printed tag does, rather than as a short group
	 * printed larger than a long one beneath it. Each is then centred on its own ink horizontally,
	 * exactly as {@link #drawLine} centres a whole line.
	 * <p>
	 * A tower tag's last group is the exception: it is a word read <em>across</em> the foot of the
	 * plate rather than a column, so it is fitted to the plate's width like a line while still
	 * occupying one row of the same stack. See {@link SignTextFlow#DOWN_FOOT_ACROSS}.
	 */
	private void drawStacked(FontRenderer font, UtilitySignKind kind, TileEntityUtilitySign tile)
	{
		int lines = kind.getLines();
		String[] text = new String[lines];
		int[] rows = new int[lines];
		for(int i = 0; i < lines; i++)
		{
			text[i] = tile.getLine(i).trim();
			rows[i] = text[i].isEmpty()?0: kind.isFootLine(i)?1: text[i].length();
		}
		SignLayout.StackPlan plan = SignLayout.planStack(kind, rows);
		int widest = 0;
		for(int i = 0; i < lines; i++)
			if(rows[i] > 0&&!kind.isFootLine(i))
				for(int c = 0; c < text[i].length(); c++)
					widest = Math.max(widest, font.getStringWidth(String.valueOf(text[i].charAt(c))));
		float scale = SignLayout.stackedScale(kind, plan, widest)/16f;
		for(int i = 0; i < lines; i++)
		{
			if(rows[i]==0)
				continue;
			if(kind.isFootLine(i))
			{
				int width = font.getStringWidth(text[i]);
				drawRow(font, kind, text[i], width, plan.rowCentre(i, 0),
						SignLayout.rowScale(kind, plan, plan.rowCentre(i, 0), width)/16f);
			}
			else
				for(int row = 0; row < text[i].length(); row++)
				{
					String ch = String.valueOf(text[i].charAt(row));
					drawRow(font, kind, ch, font.getStringWidth(ch), plan.rowCentre(i, row), scale);
				}
		}
	}

	/** One row of a stacked plate: a single character, or the word across its foot. */
	private void drawRow(FontRenderer font, UtilitySignKind kind, String text, int width,
						 float centre, float scale)
	{
		GlStateManager.pushMatrix();
		GlStateManager.translate(0, -centre/16d, 0);
		GlStateManager.scale(scale, -scale, scale);
		font.drawString(text, -SignLayout.inkWidth(width)/2f, -SignLayout.INK_HEIGHT/2f,
				kind.getTextColour(), false);
		GlStateManager.popMatrix();
	}
}
