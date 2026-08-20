/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.client.gui.elements.GuiButtonFlat;
import blusunrize.immersiveengineering.common.blocks.signage.SignLayout;
import blusunrize.immersiveengineering.common.blocks.signage.TileEntityUtilitySign;
import blusunrize.immersiveengineering.common.blocks.signage.UtilitySignKind;
import blusunrize.immersiveengineering.common.gui.ContainerUtilitySign;
import blusunrize.immersiveengineering.common.util.network.MessageSignText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.translation.I18n;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * The window for writing on a utility pole tag: pick a plate, type what goes on it, see it.
 * <p>
 * <strong>The preview is the point.</strong> Thirteen kinds of sign is thirteen shapes, colours and
 * text layouts, and choosing between them from a list of names would mean hanging one, climbing
 * down, looking, and climbing back up. The preview draws the real plate sprite at four times size
 * with the real lettering laid out by {@link SignLayout} -- the same arithmetic the world renderer
 * uses -- so what is in this window is what ends up on the pole.
 * <p>
 * Sent once, on Done or on closing, rather than per keystroke. Escape closes and keeps what was
 * typed, which is the behaviour of every text field anybody has used; there is nothing here worth
 * making somebody confirm.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class GuiUtilitySign extends GuiIEContainerBase
{
	private static final int WIDTH = 220;
	private static final int HEIGHT = 158;

	/** Where the preview panel sits, and how much room that leaves the kind's name beside it. */
	private static final int PREVIEW_TOP = 18;
	private static final int FIRST_LINE = 90;
	private static final int LINE_STEP = 18;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;

	/** How many screen pixels one block pixel of the plate is drawn at in the preview. */
	private static final int ZOOM = 4;
	private static final int PREVIEW = 16*ZOOM;

	private static final int ID_PREV = 0, ID_NEXT = 1, ID_DONE = 2;

	private final TileEntityUtilitySign tile;
	private final GuiTextField[] fields = new GuiTextField[UtilitySignKind.MAX_LINES];
	private UtilitySignKind kind;
	/** Set once the text has gone to the server, so closing afterwards does not send it again. */
	private boolean sent;

	public GuiUtilitySign(InventoryPlayer inventoryPlayer, TileEntityUtilitySign tile)
	{
		super(new ContainerUtilitySign(inventoryPlayer, tile));
		this.tile = tile;
		this.kind = tile.getKind();
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	@Override
	public void initGui()
	{
		super.initGui();
		Keyboard.enableRepeatEvents(true);
		this.buttonList.clear();
		for(int i = 0; i < fields.length; i++)
		{
			String existing = fields[i]!=null?fields[i].getText(): tile.getLine(i);
			fields[i] = new GuiTextField(i, this.fontRenderer, guiLeft+28,
					guiTop+FIRST_LINE+2+i*LINE_STEP, WIDTH-40, 12);
			fields[i].setMaxStringLength(UtilitySignKind.MAX_LENGTH);
			fields[i].setEnableBackgroundDrawing(false);
			fields[i].setTextColor(0xE0E0E0);
			fields[i].setText(existing);
		}
		this.buttonList.add(new GuiButtonFlat(ID_PREV, guiLeft+8, guiTop+20, 14, 14, "<"));
		this.buttonList.add(new GuiButtonFlat(ID_NEXT, guiLeft+26, guiTop+20, 14, 14, ">"));
		this.buttonList.add(new GuiButtonFlat(ID_DONE, guiLeft+WIDTH-48, guiTop+HEIGHT-20, 40, 14,
				I18n.translateToLocal("gui.done")));
	}

	@Override
	public void onGuiClosed()
	{
		//A last resort, and normally already done: every way of leaving this window on purpose sends
		//first, because closing it is what takes the container away that the packet is checked
		//against. This catches the ways that are not on purpose -- being teleported out of range,
		//the world unloading -- where the packet will be refused anyway and no harm is done.
		send();
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
	}

	/**
	 * Ship what is in the window.
	 * <p>
	 * <strong>Before the window closes, never after.</strong> {@code closeScreen} sends the vanilla
	 * close-window packet and only then runs {@code onGuiClosed}, so text sent from there arrives at
	 * a server that has already swapped the player's container back to their inventory -- and
	 * {@code MessageSignText} refuses it, correctly, because "a sign is open" is the whole of its
	 * permission check. Every line typed was silently thrown away, which is how this was found.
	 */
	private void send()
	{
		if(sent)
			return;
		sent = true;
		String[] lines = new String[UtilitySignKind.MAX_LINES];
		for(int i = 0; i < lines.length; i++)
			//Lines past what this kind carries are kept rather than cleared: somebody who typed
			//three lines, looked at a one-line plate and went back would not expect the other two to
			//have been thrown away while they were not looking.
			lines[i] = fields[i].getText();
		ImmersiveEngineering.packetHandler.sendToServer(
				new MessageSignText(tile.getPos(), kind.ordinal(), lines));
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+WIDTH-8-PREVIEW, guiTop+PREVIEW_TOP, guiLeft+WIDTH-8,
				guiTop+PREVIEW_TOP+PREVIEW, COL_INSET);
		for(int i = 0; i < kind.getLines(); i++)
			drawRect(guiLeft+24, guiTop+FIRST_LINE+i*LINE_STEP, guiLeft+WIDTH-8,
					guiTop+FIRST_LINE+14+i*LINE_STEP, COL_INSET);

		drawPreview(guiLeft+WIDTH-8-PREVIEW, guiTop+PREVIEW_TOP);

		//Here rather than in the foreground layer: the fields were built with absolute coordinates
		//and the foreground layer draws inside a matrix already translated by guiLeft/guiTop.
		for(int i = 0; i < kind.getLines(); i++)
			fields[i].drawTextBox();
	}

	/**
	 * The plate as it will hang, at four times size, with the lettering laid out exactly as the
	 * world renderer will lay it out.
	 */
	private void drawPreview(int left, int top)
	{
		TextureAtlasSprite sprite = Minecraft.getMinecraft().getTextureMapBlocks()
				.getAtlasSprite("immersiveengineering:blocks/sign_"+kind.getName());
		Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.enableBlend();
		drawTexturedModalRect(left, top, sprite, PREVIEW, PREVIEW);
		GlStateManager.disableBlend();

		int centreX = left+PREVIEW/2;
		int centreY = top+PREVIEW/2;
		for(int i = 0; i < kind.getLines(); i++)
		{
			String text = fields[i].getText();
			if(text.isEmpty())
				continue;
			int width = fontRenderer.getStringWidth(text);
			float scale = SignLayout.scaleFor(kind, width)*ZOOM;
			GlStateManager.pushMatrix();
			GlStateManager.translate(centreX, centreY, 0);
			if(kind.isRotated())
				//The world renderer's rotation, mirrored: the screen's Y axis already points down
				//where the world's points up, so the two turn opposite ways to look the same.
				GlStateManager.rotate(-90, 0, 0, 1);
			GlStateManager.translate(0, SignLayout.lineCentre(kind, i)*ZOOM, 0);
			GlStateManager.scale(scale, scale, 1);
			fontRenderer.drawString(text, -width/2f, -SignLayout.FONT_HEIGHT/2f,
					0xFF000000|kind.getTextColour(), false);
			GlStateManager.popMatrix();
		}
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
	{
		fontRenderer.drawString(I18n.translateToLocal("gui.immersiveengineering.utility_sign.title"),
				8, 7, COL_TEXT);
		fontRenderer.drawString((kind.ordinal()+1)+" / "+UtilitySignKind.VALUES.length, 46, 24, COL_DIM);
		//Wrapped into the room left beside the preview panel. Some of these names are long, and a
		//name that ran under the preview looked like the window was broken rather than like a long
		//name. Two lines is all there is space for; a third would reach the text boxes.
		String name = I18n.translateToLocal("desc.immersiveengineering.info.sign."+kind.getName());
		java.util.List<String> wrapped = fontRenderer.listFormattedStringToWidth(name,
				WIDTH-8-PREVIEW-16);
		for(int i = 0; i < Math.min(2, wrapped.size()); i++)
			fontRenderer.drawString(wrapped.get(i), 8, 42+i*12, COL_TEXT);

		if(kind.getLines()==0)
			fontRenderer.drawString(
					I18n.translateToLocal("gui.immersiveengineering.utility_sign.noText"),
					8, FIRST_LINE+3, COL_DIM);
		for(int i = 0; i < kind.getLines(); i++)
			fontRenderer.drawString(Integer.toString(i+1), 12, FIRST_LINE+3+i*LINE_STEP, COL_DIM);
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		if(button.id==ID_PREV)
		{
			kind = kind.previous();
			sent = false;
		}
		else if(button.id==ID_NEXT)
		{
			kind = kind.next();
			sent = false;
		}
		else if(button.id==ID_DONE)
		{
			send();
			this.mc.player.closeScreen();
			return;
		}
		//The window follows the plate: a one-line kind should not show three boxes.
		for(int i = kind.getLines(); i < fields.length; i++)
			fields[i].setFocused(false);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException
	{
		if(keyCode==Keyboard.KEY_ESCAPE)
		{
			//Escape keeps what was typed, which is what every text box anybody has used does. It has
			//to send before super closes the window -- see send().
			send();
			super.keyTyped(typedChar, keyCode);
			return;
		}
		for(int i = 0; i < kind.getLines(); i++)
			if(fields[i].isFocused()&&keyCode!=Keyboard.KEY_ESCAPE)
			{
				if(keyCode==Keyboard.KEY_RETURN||keyCode==Keyboard.KEY_TAB)
				{
					//Enter and Tab step to the next line, which is what anybody filling in three
					//boxes will try before reaching for the mouse.
					fields[i].setFocused(false);
					fields[(i+1)%Math.max(1, kind.getLines())].setFocused(true);
					return;
				}
				fields[i].textboxKeyTyped(typedChar, keyCode);
				return;
			}
		super.keyTyped(typedChar, keyCode);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
	{
		for(int i = 0; i < kind.getLines(); i++)
			fields[i].mouseClicked(mouseX, mouseY, mouseButton);
		super.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public void updateScreen()
	{
		super.updateScreen();
		for(int i = 0; i < kind.getLines(); i++)
			fields[i].updateCursorCounter();
	}
}
