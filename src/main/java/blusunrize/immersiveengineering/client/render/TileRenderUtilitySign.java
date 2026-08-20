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
 * into the chunk mesh like any other block model -- one of fifty-two, picked by the blockstate from
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
		if(kind.isRotated())
			//A strip six pixels wide and fourteen tall holds "M31390V" only one way round, and it
			//is the way the real ones are printed: reading downwards, with the letters turned
			//clockwise so the top of each faces the pole's left.
			GlStateManager.rotate(90, 0, 0, 1);

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
		float scale = SignLayout.scaleFor(kind, width)/16f;
		GlStateManager.pushMatrix();
		//Down the plate, in the frame the rotation above has already turned.
		GlStateManager.translate(0, -SignLayout.lineCentre(kind, index)/16d, 0);
		//Negative Y because a font renderer draws downwards and the world does not.
		GlStateManager.scale(scale, -scale, scale);
		font.drawString(text, -width/2f, -SignLayout.FONT_HEIGHT/2f, kind.getTextColour(), false);
		GlStateManager.popMatrix();
	}
}
