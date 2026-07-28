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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasPump;
import blusunrize.immersiveengineering.common.gui.ContainerGasPump;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.network.MessagePumpSettings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * The Gas Station Pump's panel: what is in the tank, what it costs, and how much this pump has
 * ever sold.
 * <p>
 * Small on purpose. There are exactly two things a forecourt owner sets -- the price and whether
 * to zero the meter -- and a window with two controls in it should look like a window with two
 * controls in it rather than a machine GUI with the slots taken out.
 * <p>
 * The odometer is not decoration. A lifetime-dispensed counter is the single thing roleplay
 * servers ask a fuel pump for, it costs one long in NBT, and it is the reason the price setting is
 * worth having at all.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class GuiGasPump extends GuiIEContainerBase
{
	private static final int WIDTH = 176;
	private static final int HEIGHT = 108;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;
	private static final int COL_GOOD = 0x4FBF5F;
	private static final int COL_WARN = 0xD9A227;

	private static final int ID_APPLY = 0, ID_RESET = 1;

	private final TileEntityGasPump tile;
	private GuiTextField priceField;

	public GuiGasPump(InventoryPlayer inventoryPlayer, TileEntityGasPump tile)
	{
		super(new ContainerGasPump(inventoryPlayer, tile));
		this.tile = tile;
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	@Override
	public void initGui()
	{
		super.initGui();
		Keyboard.enableRepeatEvents(true);
		this.buttonList.clear();
		priceField = new GuiTextField(0, this.fontRenderer, guiLeft+64, guiTop+62, 60, 9);
		priceField.setMaxStringLength(7);
		priceField.setEnableBackgroundDrawing(false);
		priceField.setTextColor(0xE0E0E0);
		priceField.setText(Integer.toString(tile.getPrice()));
		this.buttonList.add(new GuiButtonFlat(ID_APPLY, guiLeft+128, guiTop+58, 40, 14, "Set"));
		this.buttonList.add(new GuiButtonFlat(ID_RESET, guiLeft+8, guiTop+86, 80, 14, "Reset meter"));
	}

	@Override
	public void onGuiClosed()
	{
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+6, guiTop+18, guiLeft+WIDTH-6, guiTop+52, COL_INSET);
		drawRect(guiLeft+60, guiTop+58, guiLeft+126, guiTop+72, COL_INSET);

		//The level bar. Drawn in the fluid's own colour so a forecourt stocking two grades is
		//legible at a glance rather than by reading the name.
		FluidStack held = tile.tank.getFluid();
		if(held!=null&&held.amount > 0)
		{
			int span = WIDTH-16;
			int filled = Math.max(1, span*held.amount/TileEntityGasPump.CAPACITY);
			int colour = 0xFF000000|(held.getFluid()==null?0x808080: held.getFluid().getColor(held)&0xFFFFFF);
			drawRect(guiLeft+8, guiTop+40, guiLeft+8+filled, guiTop+48, colour);
		}
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
	{
		fontRenderer.drawString("GAS STATION PUMP", 8, 7, COL_TEXT);
		if(CityMode.petroleum())
			fontRenderer.drawString("CITY", WIDTH-fontRenderer.getStringWidth("CITY")-8, 7, COL_WARN);

		FluidStack held = tile.tank.getFluid();
		if(held==null||held.amount <= 0)
			fontRenderer.drawString("Empty -- pipe this to a tank", 10, 24, COL_DIM);
		else
		{
			fontRenderer.drawString(held.getLocalizedName(), 10, 24, COL_TEXT);
			String amount = held.amount+" / "+TileEntityGasPump.CAPACITY+" mB";
			fontRenderer.drawString(amount, WIDTH-10-fontRenderer.getStringWidth(amount), 24, COL_DIM);
		}

		fontRenderer.drawString("Price / bucket", 8, 62, COL_TEXT);
		priceField.drawTextBox();

		String meter = tile.getLifetimeDispensed()+" mB dispensed";
		fontRenderer.drawString(meter, WIDTH-10-fontRenderer.getStringWidth(meter), 90,
				tile.getLifetimeDispensed() > 0?COL_GOOD: COL_DIM);
		if(tile.getPrice()<=0)
			fontRenderer.drawString("free", 130, 76, COL_DIM);
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		if(button.id==ID_APPLY)
			send(false);
		else if(button.id==ID_RESET)
			send(true);
	}

	private void send(boolean resetOdometer)
	{
		int price;
		try
		{
			price = Integer.parseInt(priceField.getText().trim());
		} catch(NumberFormatException e)
		{
			//A typo puts the old value back rather than zeroing the price, which would quietly
			//give away a forecourt's stock.
			price = tile.getPrice();
			priceField.setText(Integer.toString(price));
		}
		ImmersiveEngineering.packetHandler.sendToServer(
				new MessagePumpSettings(tile.getPos(), price, resetOdometer));
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException
	{
		if(priceField.isFocused()&&keyCode!=Keyboard.KEY_ESCAPE)
		{
			if(keyCode==Keyboard.KEY_RETURN)
			{
				send(false);
				return;
			}
			priceField.textboxKeyTyped(typedChar, keyCode);
			return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
	{
		priceField.mouseClicked(mouseX, mouseY, mouseButton);
		super.mouseClicked(mouseX, mouseY, mouseButton);
	}
}
