/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.client.gui.elements.GuiButtonFlat;
import blusunrize.immersiveengineering.common.gui.ContainerNetworkLinker;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.fluidnet.ClientFluidNetCache;
import blusunrize.immersiveengineering.common.util.grid.ClientGridCache;
import blusunrize.immersiveengineering.common.util.link.NetworkLinker;
import blusunrize.immersiveengineering.common.util.network.MessageLinkerSelect;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A linker's chooser: the short list of networks, and one click to pick one.
 * <p>
 * Deliberately not a console. There is no editor here, no statistics and no per-device settings --
 * everything this window can do is choose which segment or main the tool is loaded with, which is
 * the whole of what a linking tool needs and the whole of what a pocket item is allowed. One click
 * per row rather than select-then-confirm: the window exists to be answered and dismissed, and a
 * second click to confirm a list of six names is a second click for nothing.
 * <p>
 * A row shows the network's colour, its name and its state, because the question a player asks
 * while holding a box is "which of these is the one that is running", and a list of names alone
 * does not answer it.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public class GuiNetworkLinker extends GuiIEContainerBase
{
	private static final int WIDTH = 190;
	private static final int HEIGHT = 168;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;
	private static final int COL_GOOD = 0x4FBF5F;
	private static final int COL_WARN = 0xD9A227;
	private static final int COL_BAD = 0xD4462B;

	private static final int ID_ROW_BASE = 100;
	private static final int ID_CLEAR = 1;

	/**
	 * Rows on screen at once. Anything past this scrolls; the overflow is reported rather than left
	 * for the wheel to discover.
	 */
	private static final int ROWS = 8;
	private static final int ROW_HEIGHT = 13;

	private final ContainerNetworkLinker container;
	private final ItemStack tool;
	private final List<UUID> order = new ArrayList<>();
	private int scroll;

	public GuiNetworkLinker(InventoryPlayer inventoryPlayer, EntityEquipmentSlot slot, boolean fluid,
							ItemStack tool)
	{
		super(new ContainerNetworkLinker(inventoryPlayer.player, slot, fluid));
		this.container = (ContainerNetworkLinker)this.inventorySlots;
		this.tool = tool;
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	private boolean fluid()
	{
		return container.fluid;
	}

	@Override
	public void initGui()
	{
		super.initGui();
		this.buttonList.clear();

		order.clear();
		if(fluid())
		{
			for(FluidMain main : ClientFluidNetCache.get().getMains())
				order.add(main.getId());
		}
		else
		{
			for(GridSegment segment : ClientGridCache.get().getSegments())
				order.add(segment.getId());
		}
		scroll = Math.max(0, Math.min(Math.max(0, order.size()-ROWS), scroll));

		for(int row = 0; row < ROWS&&row+scroll < order.size(); row++)
		{
			//The row is the button. Its caption is blank because the foreground layer draws the
			//colour swatch, the name and the state into the same rectangle -- a caption would either
			//duplicate that or fight it for the width.
			this.buttonList.add(new GuiButtonFlat(ID_ROW_BASE+row, guiLeft+7, guiTop+34+row*ROW_HEIGHT,
					WIDTH-14, ROW_HEIGHT-1, ""));
		}
		this.buttonList.add(new GuiButtonFlat(ID_CLEAR, guiLeft+7, guiTop+HEIGHT-21, WIDTH-14, 14,
				NetworkLinker.hasSelection(tool)?"Clear the tool": "Nothing loaded"));
		this.buttonList.get(this.buttonList.size()-1).enabled = NetworkLinker.hasSelection(tool);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+5, guiTop+31, guiLeft+WIDTH-5, guiTop+HEIGHT-24, COL_INSET);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
	{
		fontRenderer.drawString(fluid()?"FLUID LINKER": "GRID LINKER", 7, 7, COL_TEXT);
		String held = NetworkLinker.hasSelection(tool)
				?"holding "+fontRenderer.trimStringToWidth(NetworkLinker.describeSelection(tool), 90)
				: "empty";
		fontRenderer.drawString(held, 7, 20, NetworkLinker.hasSelection(tool)?COL_GOOD: COL_DIM);

		boolean populated = fluid()?ClientFluidNetCache.isPopulated(): ClientGridCache.isPopulated();
		if(!populated)
		{
			fontRenderer.drawString("waiting for the server...", 9, 36, COL_DIM);
			return;
		}
		if(order.isEmpty())
		{
			//Said plainly: an empty list and a broken window look the same, and the fix is at a
			//console rather than here.
			fontRenderer.drawString(fluid()?"No mains exist yet.": "No segments exist yet.", 9, 36, COL_DIM);
			fontRenderer.drawString("Make one at a console.", 9, 47, COL_DIM);
			return;
		}

		for(int row = 0; row < ROWS&&row+scroll < order.size(); row++)
		{
			UUID id = order.get(row+scroll);
			int y = 34+row*ROW_HEIGHT;
			drawRect(guiLeft+11, guiTop+y+3, guiLeft+15, guiTop+y+9, 0xFF000000|colourOf(id));
			fontRenderer.drawString(fontRenderer.trimStringToWidth(nameOf(id), 92), 19, y+2, COL_TEXT);
			String state = stateOf(id);
			fontRenderer.drawString(state, WIDTH-11-fontRenderer.getStringWidth(state), y+2, stateColour(id));
		}
		int hidden = order.size()-scroll-ROWS;
		if(hidden > 0)
		{
			String more = "+"+hidden+" more -- scroll";
			fontRenderer.drawString(more, WIDTH-9-fontRenderer.getStringWidth(more), HEIGHT-33, COL_DIM);
		}
	}

	private String nameOf(UUID id)
	{
		if(fluid())
		{
			FluidMain main = ClientFluidNetCache.get().getMain(id);
			return main==null?"?": main.getName();
		}
		GridSegment segment = ClientGridCache.get().getSegment(id);
		return segment==null?"?": segment.getName();
	}

	private int colourOf(UUID id)
	{
		if(fluid())
		{
			FluidMain main = ClientFluidNetCache.get().getMain(id);
			return main==null?0xFFFFFF: main.getColor();
		}
		GridSegment segment = ClientGridCache.get().getSegment(id);
		return segment==null?0xFFFFFF: segment.getColor();
	}

	private String stateOf(UUID id)
	{
		if(fluid())
		{
			FluidMain main = ClientFluidNetCache.get().getMain(id);
			if(main==null)
				return "?";
			if(main.isTripped())
				return "TRIPPED";
			if(!main.isEnabled())
				return "closed";
			if(CityMode.petroleum())
				return main.isPressurised()?"pressurised": "no source";
			return main.getStats().getLastTickOut()+" mB/t";
		}
		GridSegment segment = ClientGridCache.get().getSegment(id);
		if(segment==null)
			return "?";
		if(segment.isTripped())
			return "TRIPPED";
		if(!segment.isEnabled())
			return "off";
		if(CityMode.grid())
			return segment.isEnergized()?"energized": "no source";
		return segment.getStats().getLastTickOut()+" IF/t";
	}

	private int stateColour(UUID id)
	{
		if(fluid())
		{
			FluidMain main = ClientFluidNetCache.get().getMain(id);
			return main==null?COL_DIM: main.isTripped()?COL_BAD: main.isOperational()?COL_GOOD: COL_WARN;
		}
		GridSegment segment = ClientGridCache.get().getSegment(id);
		return segment==null?COL_DIM: segment.isTripped()?COL_BAD
				: segment.isOperational()?COL_GOOD: COL_WARN;
	}

	@Override
	public void updateScreen()
	{
		super.updateScreen();
		//The list arrives a fraction of a second after the window opens, and grows or shrinks while
		//it is open. Rebuilt only when the row count actually changes, so a steady list costs
		//nothing.
		int count = fluid()?ClientFluidNetCache.get().getMainCount(): ClientGridCache.get().getSegmentCount();
		if(count!=order.size())
			initGui();
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		if(button.id==ID_CLEAR)
		{
			ImmersiveEngineering.packetHandler.sendToServer(new MessageLinkerSelect(null));
			return;
		}
		int row = button.id-ID_ROW_BASE;
		if(row < 0||row+scroll >= order.size())
			return;
		//The server closes the window once it has applied the pick, and it is also what links the
		//box that opened it. Nothing is applied client-side: the tool's NBT is the server's.
		ImmersiveEngineering.packetHandler.sendToServer(new MessageLinkerSelect(order.get(row+scroll)));
	}

	@Override
	public void handleMouseInput() throws IOException
	{
		super.handleMouseInput();
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if(wheel==0)
			return;
		int next = Math.max(0, Math.min(Math.max(0, order.size()-ROWS), scroll+(wheel > 0?-1: 1)));
		if(next!=scroll)
		{
			scroll = next;
			initGui();
		}
	}
}
