/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.client.gui.elements.GuiButtonFlat;
import blusunrize.immersiveengineering.client.gui.elements.GuiReactiveList;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridDevice;
import blusunrize.immersiveengineering.common.gui.ContainerGridDevice;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.grid.ClientGridCache;
import blusunrize.immersiveengineering.common.util.network.MessageGridAction;
import blusunrize.immersiveengineering.common.util.network.MessageGridAction.Op;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single Feed or Service Unit's own panel.
 * <p>
 * Assigning a freshly placed box from the console meant walking back to it and remembering
 * which segment had been selected on another tab; doing it at the box is the natural
 * gesture. Everything here is also reachable from the console -- this is a shortcut, not a
 * separate source of truth, and it edits exactly the same records through exactly the same
 * packet.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiGridDevice extends GuiIEContainerBase
{
	private static final int WIDTH = 216;
	private static final int HEIGHT = 186;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;
	private static final int COL_GOOD = 0x4FBF5F;
	private static final int COL_WARN = 0xD9A227;
	private static final int COL_BAD = 0xD4462B;

	private static final int ID_LIST = 0, ID_ASSIGN = 1, ID_UNLINK = 2;
	private static final int ID_PRIO_DOWN = 3, ID_PRIO_UP = 4;
	private static final int ID_CAP_DOWN = 5, ID_CAP_UP = 6;
	private static final int ID_CRITICAL = 7, ID_CHUNKLOAD = 8, ID_ENABLED = 9;
	private static final int ID_SIGNAL_MODE = 10, ID_SIGNAL_INVERT = 11;

	private final TileEntityGridDevice tile;
	private final List<UUID> segmentOrder = new ArrayList<>();
	private int selectedIndex = -1;
	private int knownSegmentCount = -1;
	/**
	 * Snapshot of the state the current widgets were built from.
	 */
	private String lastSignature = "";

	public GuiGridDevice(InventoryPlayer inventoryPlayer, TileEntityGridDevice tile)
	{
		super(new ContainerGridDevice(inventoryPlayer, tile));
		this.tile = tile;
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	/**
	 * The authoritative record for this block, from the synced client copy of the grid.
	 * Null until the first sync arrives.
	 */
	@Nullable
	private GridDevice device()
	{
		if(tile==null||tile.getWorld()==null)
			return null;
		return ClientGridCache.get().getDevice(
				new DimensionBlockPos(tile.getPos(), tile.getWorld()));
	}

	@Nullable
	private GridSegment currentSegment()
	{
		GridDevice device = device();
		return device==null?null: ClientGridCache.get().getSegment(device.getSegment());
	}

	@Override
	public void initGui()
	{
		super.initGui();
		this.buttonList.clear();

		segmentOrder.clear();
		for(GridSegment segment : ClientGridCache.get().getSegments())
			segmentOrder.add(segment.getId());
		knownSegmentCount = segmentOrder.size();

		List<String> rows = new ArrayList<>();
		GridDevice device = device();
		UUID current = device==null?null: device.getSegment();
		for(UUID id : segmentOrder)
		{
			GridSegment segment = ClientGridCache.get().getSegment(id);
			String name = segment==null?"?": segment.getName();
			//Mark the one it is already on, so the list doubles as a status readout.
			rows.add(id.equals(current)?"> "+name: "  "+name);
		}
		if(rows.isEmpty())
			rows.add("(no segments exist)");
		GuiReactiveList list = new GuiReactiveList(this, ID_LIST, guiLeft+8, guiTop+38, 92, 90,
				rows.toArray(new String[0]));
		list.setPadding(1, 1, 2, 2).setScrollMode(1);
		this.buttonList.add(list);

		boolean canAssign = selectedIndex >= 0&&selectedIndex < segmentOrder.size();
		this.buttonList.add(button(ID_ASSIGN, 8, 132, 92, 14,
				canAssign?"Assign to "+trimTo(nameOf(segmentOrder.get(selectedIndex)), 44): "Pick a segment"));
		this.buttonList.get(this.buttonList.size()-1).enabled = canAssign;

		if(device!=null&&device.isLinked())
			this.buttonList.add(button(ID_UNLINK, 8, 150, 92, 14, "Unlink"));

		if(device==null)
		{
			lastSignature = structureSignature();
			return;
		}
		if(isSignal())
		{
			//A Signal Unit has no throughput, no priority and no critical flag -- it moves no
			//flux at all. Showing those controls greyed out would only invite the question of
			//why they do nothing, so it gets its own two.
			this.buttonList.add(button(ID_SIGNAL_MODE, 108, 66, 100, 14,
					device.isSignalOutput()?"Output: segment -> RS": "Input: RS -> segment")
					.setActive(device.isSignalOutput()));
			this.buttonList.add(button(ID_SIGNAL_INVERT, 108, 84, 100, 14,
					device.isSignalInverted()?"Inverted": "Normal").setActive(device.isSignalInverted()));
		}
		else
		{
			this.buttonList.add(button(ID_PRIO_DOWN, 108, 66, 20, 14, "-"));
			this.buttonList.add(button(ID_PRIO_UP, 130, 66, 20, 14, "+"));
			this.buttonList.add(button(ID_CAP_DOWN, 108, 96, 20, 14, "/2"));
			this.buttonList.add(button(ID_CAP_UP, 130, 96, 20, 14, "x2"));
			this.buttonList.add(button(ID_CRITICAL, 108, 116, 100, 14,
					device.isCritical()?"Critical load": "Normal load").setActive(device.isCritical()));
		}
		this.buttonList.add(button(ID_CHUNKLOAD, 108, 134, 100, 14,
				device.isChunkLoadRequested()?"Keeps chunk loaded": "Chunk loading off")
				.setActive(device.isChunkLoadRequested()));
		this.buttonList.add(button(ID_ENABLED, 108, 152, 100, 14,
				device.isEnabled()?"Enabled": "Disabled").setActive(device.isEnabled()));
		lastSignature = structureSignature();
	}

	private GuiButtonFlat button(int id, int x, int y, int w, int h, String label)
	{
		return new GuiButtonFlat(id, guiLeft+x, guiTop+y, w, h, label);
	}

	private boolean isSignal()
	{
		return tile!=null&&tile.getDeviceType()==GridDeviceType.SIGNAL;
	}

	private String nameOf(UUID id)
	{
		GridSegment segment = ClientGridCache.get().getSegment(id);
		return segment==null?"?": segment.getName();
	}

	private String trimTo(String text, int maxWidth)
	{
		return fontRenderer.getStringWidth(text) <= maxWidth?text
				: fontRenderer.trimStringToWidth(text, maxWidth);
	}

	@Override
	public void updateScreen()
	{
		super.updateScreen();
		//As in the console: rebuild only when the set of widgets must change, and otherwise
		//refresh the captions in place. Without the second half, a toggle keeps showing the
		//value it had when the button was created and reads as not working.
		String signature = structureSignature();
		if(!signature.equals(lastSignature))
		{
			initGui();
			return;
		}
		refreshWidgets();
	}

	private String structureSignature()
	{
		GridDevice device = device();
		return ClientGridCache.get().getSegmentCount()+"|"+selectedIndex+"|"
				+(device==null?"-": String.valueOf(device.isLinked()))+"|"
				+(device==null?"-": String.valueOf(device.getSegment()));
	}

	private void refreshWidgets()
	{
		GridDevice device = device();
		if(device==null)
			return;
		for(GuiButton button : this.buttonList)
			switch(button.id)
			{
				case ID_CRITICAL:
					setToggle(button, device.isCritical(), "Critical load", "Normal load");
					break;
				case ID_CHUNKLOAD:
					setToggle(button, device.isChunkLoadRequested(),
							"Keeps chunk loaded", "Chunk loading off");
					break;
				case ID_ENABLED:
					setToggle(button, device.isEnabled(), "Enabled", "Disabled");
					break;
				case ID_SIGNAL_MODE:
					setToggle(button, device.isSignalOutput(),
							"Output: segment -> RS", "Input: RS -> segment");
					break;
				case ID_SIGNAL_INVERT:
					setToggle(button, device.isSignalInverted(), "Inverted", "Normal");
					break;
				default:
					break;
			}
	}

	private static void setToggle(GuiButton button, boolean on, String onLabel, String offLabel)
	{
		button.displayString = on?onLabel: offLabel;
		if(button instanceof GuiButtonFlat)
			((GuiButtonFlat)button).active = on;
	}

	//	=================================
	//		INPUT
	//	=================================

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		GridDevice device = device();
		if(device==null&&button.id!=ID_LIST)
			return;
		switch(button.id)
		{
			case ID_LIST:
			{
				int index = ((GuiReactiveList)button).selectedOption;
				if(index >= 0&&index < segmentOrder.size())
				{
					selectedIndex = index;
					initGui();
				}
				break;
			}
			case ID_ASSIGN:
				if(selectedIndex >= 0&&selectedIndex < segmentOrder.size())
					send(Op.ASSIGN_DEVICE, segmentOrder.get(selectedIndex), pos(device));
				break;
			case ID_UNLINK:
			{
				NBTTagCompound args = pos(device);
				args.setBoolean("unlink", true);
				//A null segment is a legal target for an unlink; the handler treats the
				//device as the subject rather than requiring a selected segment.
				send(Op.ASSIGN_DEVICE, null, args);
				break;
			}
			case ID_PRIO_DOWN:
				sendInt(device, "priority", device.getPriority()-1);
				break;
			case ID_PRIO_UP:
				sendInt(device, "priority", device.getPriority()+1);
				break;
			case ID_CAP_DOWN:
				sendInt(device, "transferCap", Math.max(0, device.getTransferCap()/2));
				break;
			case ID_CAP_UP:
				sendInt(device, "transferCap", Math.max(1, device.getTransferCap()*2));
				break;
			case ID_CRITICAL:
				sendFlag(device, "critical", !device.isCritical());
				break;
			case ID_CHUNKLOAD:
				sendFlag(device, "chunkLoad", !device.isChunkLoadRequested());
				break;
			case ID_ENABLED:
				sendFlag(device, "enabled", !device.isEnabled());
				break;
			case ID_SIGNAL_MODE:
				sendFlag(device, "signalOutput", !device.isSignalOutput());
				break;
			case ID_SIGNAL_INVERT:
				sendFlag(device, "signalInverted", !device.isSignalInverted());
				break;
			default:
				break;
		}
	}

	private NBTTagCompound pos(GridDevice device)
	{
		NBTTagCompound args = new NBTTagCompound();
		args.setInteger("x", device.getPos().getX());
		args.setInteger("y", device.getPos().getY());
		args.setInteger("z", device.getPos().getZ());
		args.setInteger("dim", device.getPos().dimension);
		return args;
	}

	private void sendInt(GridDevice device, String key, int value)
	{
		NBTTagCompound args = pos(device);
		args.setInteger(key, value);
		send(Op.SET_DEVICE, device.getSegment(), args);
	}

	private void sendFlag(GridDevice device, String key, boolean value)
	{
		NBTTagCompound args = pos(device);
		args.setBoolean(key, value);
		send(Op.SET_DEVICE, device.getSegment(), args);
	}

	private void send(Op op, @Nullable UUID segment, NBTTagCompound args)
	{
		ImmersiveEngineering.packetHandler.sendToServer(new MessageGridAction(op, segment, args));
		initGui();
	}

	//	=================================
	//		RENDERING
	//	=================================

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+4, guiTop+4, guiLeft+WIDTH-4, guiTop+18, COL_INSET);

		boolean feed = tile!=null&&tile.getDeviceType()==GridDeviceType.FEED;
		fontRenderer.drawString(isSignal()?"GRID SIGNAL UNIT": feed?"GRID FEED UNIT": "GRID SERVICE UNIT",
				guiLeft+8, guiTop+8, Lib.COLOUR_I_ImmersiveOrange);

		GridDevice device = device();
		if(!ClientGridCache.isPopulated()||device==null)
		{
			fontRenderer.drawString("Linking to grid...", guiLeft+8, guiTop+30, COL_DIM);
			return;
		}

		//Left column: which segment to put it on.
		fontRenderer.drawString("Assign to segment", guiLeft+8, guiTop+26, COL_TEXT);
		drawRect(guiLeft+8, guiTop+36, guiLeft+100, guiTop+130, COL_INSET);

		//Right column: this device's own settings.
		GridSegment segment = currentSegment();
		drawRect(guiLeft+104, guiTop+24, guiLeft+WIDTH-8, guiTop+HEIGHT-8, COL_INSET);
		if(segment==null)
			fontRenderer.drawString("Unlinked", guiLeft+108, guiTop+28, COL_BAD);
		else
		{
			drawRect(guiLeft+108, guiTop+28, guiLeft+113, guiTop+36, 0xFF000000|segment.getColor());
			fontRenderer.drawString(trimTo(segment.getName(), 88), guiLeft+117, guiTop+28, COL_TEXT);
			fontRenderer.drawString(stateLabel(segment, device), guiLeft+108, guiTop+40,
					stateColour(segment, device));
		}

		int y = guiTop+HEIGHT-22;
		if(isSignal())
		{
			fontRenderer.drawString("Redstone", guiLeft+108, guiTop+56, COL_TEXT);
			//Spell out what the two toggles combine into, because "inverted input" is exactly
			//the sort of thing that is obvious while you set it and baffling a month later.
			fontRenderer.drawString(signalSummary(device), guiLeft+108, guiTop+102, COL_DIM);
			fontRenderer.drawString(device.isOnline()?"online": "offline",
					guiLeft+8, y, device.isOnline()?COL_GOOD: COL_DIM);
			if(segment!=null&&!device.isSignalOutput()&&segment.isForcedOff())
				fontRenderer.drawString("holding segment off", guiLeft+8, y+10, COL_WARN);
			return;
		}

		fontRenderer.drawString("Priority "+device.getPriority(), guiLeft+108, guiTop+56, COL_TEXT);
		fontRenderer.drawString("Cap "+device.getTransferCap()+" IF/t", guiLeft+108, guiTop+86, COL_TEXT);

		//Bottom strip: live readout, so the panel doubles as the status check.
		fontRenderer.drawString(device.isOnline()?"online": "offline",
				guiLeft+8, y, device.isOnline()?COL_GOOD: COL_DIM);
		fontRenderer.drawString(device.getLastThroughput()+" IF/t", guiLeft+48, y, COL_TEXT);
		fontRenderer.drawString(GuiGridConsole.formatEnergy(device.getLifetimeThroughput()),
				guiLeft+8, y+10, COL_DIM);

		//These boxes trade flux with the blocks they touch; they do not take wires directly,
		//any more than a machine does. Say so while nothing is moving, since a box that is
		//correctly assigned but wired up wrong looks identical to one that is working.
		if(device.isLinked()&&device.isEnabled()&&device.getLastThroughput()==0)
			fontRenderer.drawString(feed?"no power arriving": "nothing is taking power",
					guiLeft+100, y+10, COL_WARN);
	}

	/**
	 * Plain-language rendering of the two signal toggles together.
	 */
	private static String signalSummary(GridDevice device)
	{
		if(device.isSignalOutput())
			return device.isSignalInverted()?"emits while the segment is down"
					: "emits while the segment is up";
		return device.isSignalInverted()?"no redstone = segment off"
				: "redstone = segment off";
	}

	private static String stateLabel(GridSegment segment, GridDevice device)
	{
		if(!device.isEnabled())
			return "device disabled";
		if(segment.isTripped())
			return "segment tripped";
		if(!segment.isEnabled())
			return "segment off";
		if(CityMode.grid())
			return segment.isEnergized()?"energized": "no power";
		return "active";
	}

	private static int stateColour(GridSegment segment, GridDevice device)
	{
		if(!device.isEnabled()||segment.isTripped())
			return COL_BAD;
		if(!segment.isEnabled())
			return COL_DIM;
		if(CityMode.grid()&&!segment.isEnergized())
			return COL_WARN;
		return COL_GOOD;
	}
}
