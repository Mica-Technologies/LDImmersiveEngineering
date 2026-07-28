/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.client.gui.elements.GuiTabButton;
import blusunrize.immersiveengineering.common.gui.ContainerNetworkTerminal;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.fluidnet.ClientFluidNetCache;
import blusunrize.immersiveengineering.common.util.grid.ClientGridCache;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.InventoryPlayer;

import java.io.IOException;

/**
 * The Engineer's Network Terminal screen: both networks, read-only.
 * <p>
 * Deliberately a <em>list</em> and nothing else. Every widget the consoles have exists to change
 * something, and this window cannot change anything, so it has no widgets beyond the two tabs.
 * What it does have is the one line per segment or main that answers "is it up, and is it moving
 * anything" -- which is the whole reason to carry it.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiNetworkTerminal extends GuiIEContainerBase
{
	private static final int WIDTH = 220;
	private static final int HEIGHT = 180;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;
	private static final int COL_GOOD = 0x4FBF5F;
	private static final int COL_WARN = 0xD9A227;
	private static final int COL_BAD = 0xD4462B;

	private static final int ID_TAB_BASE = 0;
	/**
	 * Rows that fit in the list area. Anything past this is not shown -- and is counted and
	 * reported, because a terminal that silently truncated would be worse than one that admits it.
	 */
	private static final int ROWS = 11;

	private enum Tab
	{
		POWER("Power"),
		FLUID("Fluid");

		final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private Tab tab = Tab.POWER;
	private int scroll;

	public GuiNetworkTerminal(InventoryPlayer inventoryPlayer)
	{
		super(new ContainerNetworkTerminal(inventoryPlayer.player));
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	@Override
	public void initGui()
	{
		super.initGui();
		this.buttonList.clear();
		for(Tab t : Tab.values())
			this.buttonList.add(new GuiTabButton(ID_TAB_BASE+t.ordinal(),
					guiLeft+6+t.ordinal()*54, guiTop+18, 52, 16, t.label).setSelected(t==tab));
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		if(button.id >= ID_TAB_BASE&&button.id < ID_TAB_BASE+Tab.values().length)
		{
			Tab target = Tab.values()[button.id-ID_TAB_BASE];
			if(target!=tab)
			{
				tab = target;
				scroll = 0;
				initGui();
			}
		}
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+5, guiTop+36, guiLeft+WIDTH-5, guiTop+HEIGHT-6, COL_INSET);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
	{
		fontRenderer.drawString("NETWORK TERMINAL", 7, 7, COL_TEXT);
		String mode = tab==Tab.POWER
				?CityMode.grid()?"CITY": ""
				:CityMode.petroleum()?"CITY": "";
		if(!mode.isEmpty())
			fontRenderer.drawString(mode, WIDTH-fontRenderer.getStringWidth(mode)-7, 7, COL_WARN);

		if(tab==Tab.POWER)
			drawPower();
		else
			drawFluid();
	}

	private void drawPower()
	{
		if(!ClientGridCache.isPopulated())
		{
			fontRenderer.drawString("waiting for the server...", 9, 40, COL_DIM);
			return;
		}
		VirtualGrid grid = ClientGridCache.get();
		if(grid.getSegmentCount()==0)
		{
			fontRenderer.drawString("No grid segments.", 9, 40, COL_DIM);
			return;
		}
		fontRenderer.drawString(grid.getTotalOut()+" IF/t across "+grid.getSegmentCount()+" segment(s)",
				9, 26, COL_DIM);

		int y = 40;
		int shown = 0;
		int hidden = 0;
		for(GridSegment segment : grid.getSegments())
		{
			if(shown++ < scroll)
				continue;
			if(y > HEIGHT-20)
			{
				hidden++;
				continue;
			}
			drawRect(guiLeft+7, guiTop+y+1, guiLeft+10, guiTop+y+8, 0xFF000000|segment.getColor());
			fontRenderer.drawString(fontRenderer.trimStringToWidth(segment.getName(), 108), 14, y+1, COL_TEXT);
			String state = segmentState(segment);
			fontRenderer.drawString(state, WIDTH-9-fontRenderer.getStringWidth(state), y+1,
					segment.isTripped()?COL_BAD: segment.isOperational()?COL_GOOD: COL_WARN);
			y += 11;
		}
		drawOverflow(hidden);
	}

	private static String segmentState(GridSegment segment)
	{
		if(segment.isTripped())
			return "TRIPPED";
		if(!segment.isEnabled())
			return "off";
		if(segment.isForcedOff())
			return "held off";
		if(segment.isScheduleSuppressed())
			return "off-schedule";
		if(CityMode.grid())
			return segment.isEnergized()?"energized": "no source";
		return segment.getStats().getLastTickOut()+" IF/t";
	}

	private void drawFluid()
	{
		if(!ClientFluidNetCache.isPopulated())
		{
			fontRenderer.drawString("waiting for the server...", 9, 40, COL_DIM);
			return;
		}
		VirtualFluidNet net = ClientFluidNetCache.get();
		if(net.getMainCount()==0)
		{
			fontRenderer.drawString("No fluid mains.", 9, 40, COL_DIM);
			return;
		}
		fontRenderer.drawString(net.getTotalOut()+" mB/t across "+net.getMainCount()+" main(s)",
				9, 26, COL_DIM);

		int y = 40;
		int shown = 0;
		int hidden = 0;
		for(FluidMain main : net.getMains())
		{
			if(shown++ < scroll)
				continue;
			if(y > HEIGHT-20)
			{
				hidden++;
				continue;
			}
			drawRect(guiLeft+7, guiTop+y+1, guiLeft+10, guiTop+y+8, 0xFF000000|main.getColor());
			fontRenderer.drawString(fontRenderer.trimStringToWidth(main.getName(), 70), 14, y+1, COL_TEXT);
			//What it carries earns its place even on a list this small: a main that has stopped and
			//a main that never decided what it was carrying look identical without it.
			fontRenderer.drawString(main.isTyped()?fontRenderer.trimStringToWidth(main.getFluid(), 52)
					: "untyped", 88, y+1, main.isTyped()?COL_DIM: COL_WARN);
			String state = mainState(main);
			fontRenderer.drawString(state, WIDTH-9-fontRenderer.getStringWidth(state), y+1,
					main.isTripped()?COL_BAD: main.isOperational()?COL_GOOD: COL_WARN);
			y += 11;
		}
		drawOverflow(hidden);
	}

	private static String mainState(FluidMain main)
	{
		if(main.isTripped())
			return "TRIPPED";
		if(!main.isEnabled())
			return "closed";
		if(main.isForcedClosed())
			return "valve shut";
		if(main.isScheduleSuppressed())
			return "off-schedule";
		if(CityMode.petroleum())
			return main.isPressurised()?"pressurised": "no source";
		return main.getStats().getLastTickOut()+" mB/t";
	}

	private void drawOverflow(int hidden)
	{
		if(hidden <= 0)
			return;
		//Said out loud rather than left to the scroll wheel to discover. A list that quietly ends
		//is indistinguishable from a network that quietly ends.
		String more = "+"+hidden+" more -- scroll";
		fontRenderer.drawString(more, WIDTH-9-fontRenderer.getStringWidth(more), HEIGHT-15, COL_DIM);
	}

	@Override
	public void handleMouseInput() throws IOException
	{
		super.handleMouseInput();
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if(wheel==0)
			return;
		int count = tab==Tab.POWER?ClientGridCache.get().getSegmentCount()
				: ClientFluidNetCache.get().getMainCount();
		scroll = Math.max(0, Math.min(Math.max(0, count-ROWS), scroll+(wheel > 0?-1: 1)));
	}
}
