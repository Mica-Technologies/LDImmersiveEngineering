/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.render;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.client.ClientProxy;
import blusunrize.immersiveengineering.client.ClientUtils;
import blusunrize.immersiveengineering.common.Config.IEConfig;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasPump;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

/**
 * Draws the price on the pump's own display panel.
 * <p>
 * The pump used to report its price as overlay text beside the crosshair, which is what a
 * playtester described as "the price floating in the air": a forecourt of pumps all read blank
 * until you aimed at one, and then the number appeared nowhere near the thing it belonged to. A
 * price board is a physical object, so it is drawn on the physical object.
 * <p>
 * <strong>It does not billboard.</strong> The text is fixed to the panel face and turns with the
 * pump, which means it is unreadable from behind -- correctly. A number that swivelled to follow
 * the player would give away that there is nothing really there, and the whole reason for moving
 * it out of the HUD was to stop it reading as an annotation.
 * <p>
 * The body of the pump is <em>not</em> drawn here. It is an OBJ baked into the chunk mesh like any
 * other block model, hidden and turned per block by {@link TileEntityGasPump}'s
 * {@code IOBJModelCallback}; only the text, which changes, costs anything per frame.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileRenderGasPump extends TileEntitySpecialRenderer<TileEntityGasPump>
{
	/**
	 * The panel's front face, from the model, less a hair so the text is not coplanar with it.
	 * Move the panel in make_gaspump_assets.py and this moves with it.
	 */
	private static final double PANEL_FRONT = 3.25/16d-0.002d;
	/** Where the two lines sit up the pump, in blocks from the foot of the lower block. */
	private static final double PRICE_Y = 20.0/16d;
	private static final double METER_Y = 17.0/16d;
	/** The widest the text may be before it is shrunk to fit: the panel's glass, less its bezel. */
	private static final double PANEL_WIDTH = 8.5/16d;

	private static final float PRICE_SCALE = 1/48f;
	private static final float METER_SCALE = 1/86f;

	private static final int COL_METER = 0x9A8F72;

	@Override
	public void render(TileEntityGasPump tile, double x, double y, double z, float partialTicks,
					   int destroyStage, float alpha)
	{
		//Only the base of an assembled pump has a panel; a loose block is a crate and the head
		//above the base has nothing of its own to draw.
		if(!tile.isFormed()||tile.isDummy()||tile.getWorld()==null
				||!tile.getWorld().isBlockLoaded(tile.getPos(), false))
			return;

		FontRenderer digits = ClientProxy.nixieFontOptional;
		FontRenderer small = ClientUtils.font();
		if(digits==null||small==null)
			return;
		int price = tile.getPrice();
		//The same colour the rest of the mod's mechanical digits use, so a forecourt matches the
		//nixie tubes on everything else -- unless the player has turned that font off, in which
		//case the panel is plain amber rather than nixie orange on the vanilla font.
		int col = IEConfig.nixietubeFont?Lib.colour_nixieTubeText: 0xFFB000;

		GlStateManager.pushMatrix();
		GlStateManager.translate(x+0.5, y, z+0.5);
		GlStateManager.rotate(tile.modelRotationDegrees(), 0, 1, 0);
		GlStateManager.translate(0, 0, -(0.5-PANEL_FRONT));
		//Turned to look out of the panel: a rotation, not a mirror, so the text still reads
		//left-to-right from the front of the pump.
		GlStateManager.rotate(180, 0, 1, 0);
		GlStateManager.disableLighting();

		drawCentred(digits, price > 0?Integer.toString(price): "FREE", PRICE_Y, PRICE_SCALE, col);
		drawCentred(small, meterLine(tile), METER_Y, METER_SCALE, COL_METER);

		GlStateManager.enableLighting();
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.popMatrix();
	}

	/**
	 * The second line: what this pump has ever sold, which is the number a forecourt owner
	 * actually watches. Abbreviated past ten buckets, because the panel is ten pixels wide and a
	 * seven-digit total shrunk to fit is a smear rather than a reading.
	 */
	private String meterLine(TileEntityGasPump tile)
	{
		if(CityMode.petroleum())
			return "CITY";
		long dispensed = tile.getLifetimeDispensed();
		if(dispensed >= 10000)
			return (dispensed/1000)+"B";
		return dispensed+"mB";
	}

	/**
	 * One line, centred on the panel and shrunk if it would run off the edge of it.
	 */
	private void drawCentred(FontRenderer font, String text, double height, float scale, int colour)
	{
		int width = font.getStringWidth(text);
		if(width <= 0)
			return;
		if(width*scale > PANEL_WIDTH)
			scale = (float)(PANEL_WIDTH/width);
		GlStateManager.pushMatrix();
		GlStateManager.translate(0, height, 0);
		//Negative Y because a font renderer draws downwards and the world does not.
		GlStateManager.scale(scale, -scale, scale);
		font.drawString(text, -width/2f, 0, colour, false);
		GlStateManager.popMatrix();
	}
}
