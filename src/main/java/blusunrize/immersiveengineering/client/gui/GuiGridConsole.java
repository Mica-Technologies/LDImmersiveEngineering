/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.client.gui.elements.*;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridConsole;
import blusunrize.immersiveengineering.common.gui.ContainerGridConsole;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.grid.ClientGridCache;
import blusunrize.immersiveengineering.common.util.network.MessageGridAction;
import blusunrize.immersiveengineering.common.util.network.MessageGridAction.Op;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import org.lwjgl.input.Keyboard;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

/**
 * The Grid Management Console screen.
 * <p>
 * Drawn entirely from primitives -- no texture sheet -- so the layout can be reworked
 * in-game without round-tripping a PNG. Every control round-trips through
 * {@link MessageGridAction} and the panel then redraws from the next server sync; nothing
 * is applied optimistically, so the screen can never disagree with the world.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GuiGridConsole extends GuiIEContainerBase
{
	//	=================================
	//		LAYOUT
	//	=================================
	private static final int WIDTH = 276;
	private static final int HEIGHT = 220;
	private static final int TAB_X = 5, TAB_Y = 22, TAB_W = 64, TAB_H = 16, TAB_GAP = 18;
	private static final int BODY_X = 74, BODY_Y = 22, BODY_R = 271, BODY_B = 214;
	private static final int BODY_W = BODY_R-BODY_X;
	private static final int EDITOR_X = 154;

	private static final int COL_FRAME = 0xFF101010;
	private static final int COL_PANEL = 0xFF232323;
	private static final int COL_INSET = 0xFF161616;
	private static final int COL_LINE = 0xFF3A3A3A;
	private static final int COL_TEXT = 0xC8C8C8;
	private static final int COL_DIM = 0x808080;
	private static final int COL_GOOD = 0x4FBF5F;
	private static final int COL_WARN = 0xD9A227;
	private static final int COL_BAD = 0xD4462B;
	private static final int COL_IN = 0xFF3F8FBF;
	private static final int COL_OUT = 0xFFD9A227;

	//	=================================
	//		BUTTON IDS
	//	=================================
	private static final int ID_TAB_BASE = 0;
	private static final int ID_SEGMENT_LIST = 20;
	private static final int ID_NEW = 21, ID_DELETE = 22, ID_TOGGLE = 23;
	private static final int ID_APPLY = 24, ID_LOCK = 25, ID_TOPUP = 26, ID_BREAKER = 27;
	private static final int ID_SCHEDULE = 28;
	private static final int ID_SWATCH_BASE = 40;

	private static final int ID_DEVICE_LIST = 60, ID_FILTER = 61;
	private static final int ID_PRIO_DOWN = 62, ID_PRIO_UP = 63;
	private static final int ID_CAP_DOWN = 64, ID_CAP_UP = 65;
	private static final int ID_CRITICAL = 66, ID_CHUNKLOAD = 67, ID_DEV_ENABLED = 68;
	private static final int ID_DEV_LINK = 69;
	private static final int ID_SIG_MODE = 70, ID_SIG_INVERT = 71;

	private static final int ID_CHAIN_LIST = 80, ID_CANDIDATE_LIST = 81;
	private static final int ID_CHAIN_UP = 82, ID_CHAIN_DOWN = 83;
	private static final int ID_CHAIN_REMOVE = 84, ID_CHAIN_ADD = 85;

	private static final int ID_RESET_METERS = 100;

	private enum Tab
	{
		OVERVIEW("Overview"),
		SEGMENTS("Segments"),
		DEVICES("Devices"),
		FAILOVER("Failover"),
		STATS("Stats"),
		SETTINGS("Settings");

		final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private final TileEntityGridConsole tile;
	private Tab tab = Tab.OVERVIEW;

	@Nullable
	private UUID selected;
	private int overviewScroll;

	private GuiTextField nameField, inputField, outputField, lossField, bufferField;
	private GuiTextField schedOnField, schedOffField;

	private final List<UUID> listOrder = new ArrayList<>();
	private UUID fieldsLoadedFor;

	/**
	 * Devices tab: false shows the selected segment's devices, true shows unlinked ones.
	 */
	private boolean showUnlinked;
	private final List<GridDevice> deviceView = new ArrayList<>();
	private int selectedDevice = -1;

	/**
	 * Failover tab selections, as indices into the rendered lists.
	 */
	private final List<UUID> chainView = new ArrayList<>();
	private final List<UUID> candidateView = new ArrayList<>();
	private int selectedChain = -1, selectedCandidate = -1;

	private GuiLineGraph graph;
	/**
	 * Snapshot of the state the current widgets were built from, so a rebuild happens only
	 * when the set of widgets actually has to change.
	 */
	private String lastSignature = "";

	public GuiGridConsole(InventoryPlayer inventoryPlayer, TileEntityGridConsole tile)
	{
		super(new ContainerGridConsole(inventoryPlayer, tile));
		this.tile = tile;
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	private static VirtualGrid grid()
	{
		return ClientGridCache.get();
	}

	@Nullable
	private GridSegment selectedSegment()
	{
		return grid().getSegment(selected);
	}

	//	=================================
	//		SETUP
	//	=================================

	@Override
	public void initGui()
	{
		super.initGui();
		Keyboard.enableRepeatEvents(true);
		this.buttonList.clear();
		rebuildListOrder();

		for(Tab t : Tab.values())
			this.buttonList.add(new GuiTabButton(ID_TAB_BASE+t.ordinal(),
					guiLeft+TAB_X, guiTop+TAB_Y+t.ordinal()*TAB_GAP, TAB_W, TAB_H, t.label)
					.setSelected(t==tab));

		switch(tab)
		{
			case SEGMENTS:
				initSegmentsTab();
				break;
			case DEVICES:
				initDevicesTab();
				break;
			case FAILOVER:
				initFailoverTab();
				break;
			case STATS:
				graph = new GuiLineGraph(guiLeft+BODY_X, guiTop+40, BODY_W, 70);
				this.buttonList.add(button(ID_RESET_METERS, BODY_X, 196, 90, 14, "Reset meters"));
				break;
			default:
				break;
		}
		lastSignature = structureSignature();
	}

	private GuiButtonFlat button(int id, int x, int y, int w, int h, String label)
	{
		return new GuiButtonFlat(id, guiLeft+x, guiTop+y, w, h, label);
	}

	/**
	 * A toggle-style button, drawn lit when {@code active}.
	 */
	private GuiButtonFlat toggle(int id, int x, int y, int w, int h, String label, boolean active)
	{
		return (GuiButtonFlat)button(id, x, y, w, h, label).setActive(active);
	}

	private GuiReactiveList list(int id, int x, int y, int w, int h, List<String> entries)
	{
		GuiReactiveList widget = new GuiReactiveList(this, id, guiLeft+x, guiTop+y, w, h,
				entries.toArray(new String[0]));
		widget.setPadding(1, 1, 2, 2).setScrollMode(1);
		return widget;
	}

	//	=================================
	//		SEGMENTS TAB
	//	=================================

	private void initSegmentsTab()
	{
		List<String> names = new ArrayList<>();
		for(UUID id : listOrder)
		{
			GridSegment segment = grid().getSegment(id);
			names.add(segment==null?"?": segment.getName());
		}
		this.buttonList.add(list(ID_SEGMENT_LIST, BODY_X+2, BODY_Y+2, 70, 130, names));
		this.buttonList.add(button(ID_NEW, BODY_X+2, 158, 34, 14, "New"));
		this.buttonList.add(button(ID_DELETE, BODY_X+38, 158, 34, 14, "Del"));

		GridSegment segment = selectedSegment();
		nameField = makeField(guiLeft+EDITOR_X+2, guiTop+BODY_Y+2, 113, 32);
		inputField = makeField(guiLeft+EDITOR_X+48, guiTop+96, 67, 9);
		outputField = makeField(guiLeft+EDITOR_X+48, guiTop+110, 67, 9);
		lossField = makeField(guiLeft+EDITOR_X+48, guiTop+124, 67, 9);
		bufferField = makeField(guiLeft+EDITOR_X+48, guiTop+138, 67, 9);
		schedOnField = makeField(guiLeft+EDITOR_X+48, guiTop+152, 67, 5);
		schedOffField = makeField(guiLeft+EDITOR_X+48, guiTop+166, 67, 5);
		fieldsLoadedFor = null;
		loadFieldsFrom(segment);

		if(segment==null)
			return;
		this.buttonList.add(new GuiToggleSwitch(ID_TOGGLE, guiLeft+EDITOR_X+2, guiTop+40, 16, 24,
				segment.isEnabled()).setTripped(segment.isTripped()));
		for(int i = 0; i < GridSegment.PALETTE.length; i++)
			this.buttonList.add(new GuiColourSwatch(ID_SWATCH_BASE+i,
					guiLeft+EDITOR_X+2+(i%8)*12, guiTop+70+(i/8)*12,
					GridSegment.PALETTE[i], GridSegment.PALETTE[i]==segment.getColor()));
		this.buttonList.add(toggle(ID_SCHEDULE, BODY_X+2, 176, 70, 14,
				segment.getPolicy().isScheduleEnabled()?"Sched: on": "Sched: off",
				segment.getPolicy().isScheduleEnabled()));
		this.buttonList.add(button(ID_APPLY, EDITOR_X+2, 180, 52, 14, "Apply"));
		this.buttonList.add(toggle(ID_LOCK, EDITOR_X+58, 180, 57, 14,
				segment.isLocked()?"Locked": "Unlocked", segment.isLocked()));
		if(segment.isTripped())
			this.buttonList.add(button(ID_BREAKER, EDITOR_X+2, 196, 113, 14, "Reset breaker"));
	}

	//	=================================
	//		DEVICES TAB
	//	=================================

	private void initDevicesTab()
	{
		rebuildDeviceView();
		this.buttonList.add(toggle(ID_FILTER, BODY_X, 24, 90, 14,
				showUnlinked?"Show: unlinked": "Show: this segment", showUnlinked));

		List<String> rows = new ArrayList<>();
		for(GridDevice device : deviceView)
			rows.add(describeDevice(device));
		if(rows.isEmpty())
			rows.add(showUnlinked?"(no unlinked devices)": "(no devices on this segment)");
		this.buttonList.add(list(ID_DEVICE_LIST, BODY_X, 42, BODY_W, 81, rows));

		GridDevice device = selectedDevice();
		if(device==null)
			return;
		if(device.getType()==GridDeviceType.SIGNAL)
		{
			//Priority, cap and the critical flag all describe how flux is apportioned, and a
			//Signal Unit carries none. Give it its own two controls instead of dead ones.
			this.buttonList.add(toggle(ID_SIG_MODE, BODY_X, 142, 118, 14,
					device.isSignalOutput()?"Output: segment -> RS": "Input: RS -> segment",
					device.isSignalOutput()));
			this.buttonList.add(toggle(ID_SIG_INVERT, BODY_X+122, 142, 68, 14,
					device.isSignalInverted()?"Inverted": "Normal", device.isSignalInverted()));
		}
		else
		{
			this.buttonList.add(button(ID_PRIO_DOWN, BODY_X, 142, 22, 14, "P-"));
			this.buttonList.add(button(ID_PRIO_UP, BODY_X+24, 142, 22, 14, "P+"));
			this.buttonList.add(button(ID_CAP_DOWN, BODY_X+52, 142, 30, 14, "Cap/2"));
			this.buttonList.add(button(ID_CAP_UP, BODY_X+84, 142, 30, 14, "Cap*2"));
			this.buttonList.add(toggle(ID_CRITICAL, BODY_X, 160, 62, 14,
					device.isCritical()?"Critical": "Normal", device.isCritical()));
		}
		this.buttonList.add(toggle(ID_CHUNKLOAD, BODY_X+64, 160, 62, 14,
				device.isChunkLoadRequested()?"Chunkload on": "Chunkload off",
				device.isChunkLoadRequested()));
		this.buttonList.add(toggle(ID_DEV_ENABLED, BODY_X+128, 160, 62, 14,
				device.isEnabled()?"Enabled": "Disabled", device.isEnabled()));

		GridSegment target = selectedSegment();
		if(device.isLinked())
			this.buttonList.add(button(ID_DEV_LINK, BODY_X, 178, 90, 14, "Unlink"));
		else if(target!=null)
			//Name the destination on the button itself. Without it the only way to know
			//where a device is about to go is to remember what you picked on another tab.
			this.buttonList.add(button(ID_DEV_LINK, BODY_X, 178, 150, 14,
					"Link to "+fontRenderer.trimStringToWidth(target.getName(), 84)));
	}

	private void rebuildDeviceView()
	{
		deviceView.clear();
		if(showUnlinked)
			deviceView.addAll(grid().getUnlinkedDevices());
		else
		{
			GridSegment segment = selectedSegment();
			if(segment!=null)
				deviceView.addAll(segment.getDevices());
		}
		//A stable order, so a device does not jump under the cursor between syncs.
		deviceView.sort(Comparator
				.comparingInt((GridDevice d) -> d.getType().ordinal())
				.thenComparingInt(d -> d.getPos().dimension)
				.thenComparingInt(d -> d.getPos().getX())
				.thenComparingInt(d -> d.getPos().getY())
				.thenComparingInt(d -> d.getPos().getZ()));
		if(selectedDevice >= deviceView.size())
			selectedDevice = deviceView.isEmpty()?-1: deviceView.size()-1;
	}

	@Nullable
	private GridDevice selectedDevice()
	{
		return selectedDevice >= 0&&selectedDevice < deviceView.size()
				?deviceView.get(selectedDevice): null;
	}

	private String describeDevice(GridDevice device)
	{
		String type = device.getType()==GridDeviceType.FEED?"IN "
				: device.getType()==GridDeviceType.SERVICE?"OUT"
				: device.getType()==GridDeviceType.SIGNAL?"SIG": "CON";
		String name = device.getCustomName().isEmpty()
				?device.getPos().getX()+","+device.getPos().getY()+","+device.getPos().getZ()
				: device.getCustomName();
		StringBuilder flags = new StringBuilder();
		if(!device.isOnline())
			flags.append(" offline");
		if(!device.isEnabled())
			flags.append(" off");
		if(device.isCritical())
			flags.append(" crit");
		if(device.isChunkLoadRequested())
			flags.append(" chunk");
		if(device.getType()==GridDeviceType.SIGNAL)
			return type+" "+name+"  "+(device.isSignalOutput()?"out": "in")
					+(device.isSignalInverted()?" inv": "")+flags;
		return type+" "+name+"  p"+device.getPriority()+"  "+device.getTransferCap()+flags;
	}

	//	=================================
	//		FAILOVER TAB
	//	=================================

	private void initFailoverTab()
	{
		GridSegment segment = selectedSegment();
		chainView.clear();
		candidateView.clear();
		if(segment!=null)
		{
			chainView.addAll(segment.getFailover());
			for(GridSegment other : grid().getSegments())
				if(!other.getId().equals(segment.getId())&&!chainView.contains(other.getId()))
					candidateView.add(other.getId());
		}

		List<String> chainRows = new ArrayList<>();
		for(int i = 0; i < chainView.size(); i++)
			chainRows.add((i+1)+". "+nameOf(chainView.get(i)));
		if(chainRows.isEmpty())
			chainRows.add("(no backups)");
		this.buttonList.add(list(ID_CHAIN_LIST, BODY_X, 42, 118, 63, chainRows));

		List<String> candidateRows = new ArrayList<>();
		for(UUID id : candidateView)
			candidateRows.add(nameOf(id));
		if(candidateRows.isEmpty())
			candidateRows.add("(none available)");
		this.buttonList.add(list(ID_CANDIDATE_LIST, BODY_X, 126, 118, 45, candidateRows));

		if(segment==null)
			return;
		this.buttonList.add(button(ID_CHAIN_UP, BODY_X+122, 42, 74, 14, "Move up"));
		this.buttonList.add(button(ID_CHAIN_DOWN, BODY_X+122, 60, 74, 14, "Move down"));
		this.buttonList.add(button(ID_CHAIN_REMOVE, BODY_X+122, 78, 74, 14, "Remove"));
		this.buttonList.add(button(ID_CHAIN_ADD, BODY_X+122, 126, 74, 14, "Add backup"));
		this.buttonList.add(toggle(ID_TOPUP, BODY_X, 176, BODY_W, 14,
				segment.getPolicy().isFailoverTopUp()
						?"Backups cover outage + shortfall": "Backups cover outage only",
				segment.getPolicy().isFailoverTopUp()));
	}

	private String nameOf(UUID id)
	{
		GridSegment segment = grid().getSegment(id);
		return segment==null?"(missing)": segment.getName();
	}

	//	=================================
	//		SHARED
	//	=================================

	private GuiTextField makeField(int x, int y, int w, int maxLength)
	{
		GuiTextField field = new GuiTextField(0, this.fontRenderer, x, y, w, 11);
		field.setTextColor(0xFFFFFF);
		field.setDisabledTextColour(COL_DIM);
		field.setEnableBackgroundDrawing(false);
		field.setMaxStringLength(maxLength);
		return field;
	}

	private void rebuildListOrder()
	{
		listOrder.clear();
		for(GridSegment segment : grid().getSegments())
			listOrder.add(segment.getId());
		if(selected!=null&&!listOrder.contains(selected))
			selected = null;
		if(selected==null&&!listOrder.isEmpty())
			selected = listOrder.get(0);
	}

	private void loadFieldsFrom(@Nullable GridSegment segment)
	{
		if(segment==null)
		{
			fieldsLoadedFor = null;
			return;
		}
		if(segment.getId().equals(fieldsLoadedFor))
			return;
		fieldsLoadedFor = segment.getId();
		GridPolicy policy = segment.getPolicy();
		nameField.setText(segment.getName());
		inputField.setText(Integer.toString(policy.getMaxInput()));
		outputField.setText(Integer.toString(policy.getMaxOutput()));
		lossField.setText(String.format(Locale.ENGLISH, "%.2f", policy.getLossPct()*100));
		bufferField.setText(Integer.toString(policy.getBufferCap()));
		schedOnField.setText(Integer.toString(policy.getScheduleOn()));
		schedOffField.setText(Integer.toString(policy.getScheduleOff()));
	}

	@Override
	public void updateScreen()
	{
		super.updateScreen();
		if(tab==Tab.DEVICES)
			rebuildDeviceView();

		//Two different kinds of staleness. If the *set* of widgets changed -- a segment
		//appeared, a device became linked -- the panel has to be rebuilt. Otherwise the
		//widgets are right but their captions are baked from whatever the cache held when
		//they were created, so refresh those in place. Rebuilding unconditionally would
		//destroy the text fields (and the caret) twice a second.
		String signature = structureSignature();
		if(!signature.equals(lastSignature))
		{
			initGui();
			return;
		}
		refreshWidgets();
		if(tab==Tab.SEGMENTS)
			loadFieldsFrom(selectedSegment());
	}

	private static void setToggle(GuiButton button, boolean on, String onLabel, String offLabel)
	{
		button.displayString = on?onLabel: offLabel;
		if(button instanceof GuiButtonFlat)
			((GuiButtonFlat)button).active = on;
	}

	/**
	 * Everything that decides which widgets exist. When this changes, only a rebuild will do.
	 */
	private String structureSignature()
	{
		GridSegment segment = selectedSegment();
		GridDevice device = selectedDevice();
		StringBuilder sig = new StringBuilder(tab.name());
		sig.append('|').append(grid().getSegmentCount());
		sig.append('|').append(segment==null?"-": segment.getId()+":"+segment.isTripped());
		sig.append('|').append(showUnlinked).append(':').append(deviceView.size());
		sig.append('|').append(device==null?"-": device.getPos()+":"+device.isLinked());
		if(segment!=null)
			sig.append('|').append(segment.getFailover().size());
		return sig.toString();
	}

	/**
	 * Re-reads the model into the captions and toggle states of the widgets that already
	 * exist, so a click is reflected as soon as the server's answer arrives.
	 */
	private void refreshWidgets()
	{
		GridSegment segment = selectedSegment();
		GridDevice device = selectedDevice();
		for(GuiButton button : this.buttonList)
		{
			if(button.id >= ID_SWATCH_BASE&&button.id < ID_SWATCH_BASE+GridSegment.PALETTE.length)
			{
				if(segment!=null&&button instanceof GuiColourSwatch)
					((GuiColourSwatch)button).active =
							GridSegment.PALETTE[button.id-ID_SWATCH_BASE]==segment.getColor();
				continue;
			}
			switch(button.id)
			{
				case ID_TOGGLE:
					if(segment!=null&&button instanceof GuiToggleSwitch)
					{
						((GuiToggleSwitch)button).state = segment.isEnabled();
						((GuiToggleSwitch)button).tripped = segment.isTripped();
					}
					break;
				case ID_LOCK:
					if(segment!=null)
						setToggle(button, segment.isLocked(), "Locked", "Unlocked");
					break;
				case ID_TOPUP:
					if(segment!=null)
						setToggle(button, segment.getPolicy().isFailoverTopUp(),
								"Backups cover outage + shortfall", "Backups cover outage only");
					break;
				case ID_SCHEDULE:
					if(segment!=null)
						setToggle(button, segment.getPolicy().isScheduleEnabled(),
								"Sched: on", "Sched: off");
					break;
				case ID_FILTER:
					setToggle(button, showUnlinked, "Show: unlinked", "Show: this segment");
					break;
				case ID_CRITICAL:
					if(device!=null)
						setToggle(button, device.isCritical(), "Critical", "Normal");
					break;
				case ID_SIG_MODE:
					if(device!=null)
						setToggle(button, device.isSignalOutput(),
								"Output: segment -> RS", "Input: RS -> segment");
					break;
				case ID_SIG_INVERT:
					if(device!=null)
						setToggle(button, device.isSignalInverted(), "Inverted", "Normal");
					break;
				case ID_CHUNKLOAD:
					if(device!=null)
						setToggle(button, device.isChunkLoadRequested(),
								"Chunkload on", "Chunkload off");
					break;
				case ID_DEV_ENABLED:
					if(device!=null)
						setToggle(button, device.isEnabled(), "Enabled", "Disabled");
					break;
				default:
					break;
			}
		}
	}

	//	=================================
	//		INPUT
	//	=================================

	@Override
	protected void actionPerformed(GuiButton button) throws IOException
	{
		if(button.id >= ID_TAB_BASE&&button.id < ID_TAB_BASE+Tab.values().length)
		{
			Tab target = Tab.values()[button.id-ID_TAB_BASE];
			if(target!=tab)
			{
				tab = target;
				selectedDevice = -1;
				selectedChain = selectedCandidate = -1;
				initGui();
			}
			return;
		}
		if(button.id >= ID_SWATCH_BASE&&button.id < ID_SWATCH_BASE+GridSegment.PALETTE.length)
		{
			NBTTagCompound args = new NBTTagCompound();
			args.setInteger("color", GridSegment.PALETTE[button.id-ID_SWATCH_BASE]);
			send(Op.SET_COLOR, selected, args);
			initGui();
			return;
		}

		switch(button.id)
		{
			case ID_SEGMENT_LIST:
			{
				int index = ((GuiReactiveList)button).selectedOption;
				if(index >= 0&&index < listOrder.size())
				{
					selected = listOrder.get(index);
					initGui();
				}
				break;
			}
			case ID_NEW:
			{
				NBTTagCompound args = new NBTTagCompound();
				args.setString("name", uniqueDefaultName());
				send(Op.CREATE_SEGMENT, null, args);
				break;
			}
			case ID_DELETE:
				if(selected!=null)
				{
					send(Op.DELETE_SEGMENT, selected, new NBTTagCompound());
					selected = null;
				}
				break;
			case ID_TOGGLE:
			{
				NBTTagCompound args = new NBTTagCompound();
				args.setBoolean("value", ((GuiToggleSwitch)button).state);
				send(Op.SET_ENABLED, selected, args);
				break;
			}
			case ID_LOCK:
				withSegment(segment -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("value", !segment.isLocked());
					send(Op.SET_LOCKED, selected, args);
				});
				break;
			case ID_TOPUP:
				withSegment(segment -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("failoverTopUp", !segment.getPolicy().isFailoverTopUp());
					send(Op.SET_POLICY, selected, args);
				});
				break;
			case ID_SCHEDULE:
				withSegment(segment -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("scheduleEnabled", !segment.getPolicy().isScheduleEnabled());
					send(Op.SET_POLICY, selected, args);
				});
				break;
			case ID_BREAKER:
				send(Op.RESET_BREAKER, selected, new NBTTagCompound());
				initGui();
				break;
			case ID_APPLY:
				applyEditor();
				break;

			case ID_FILTER:
				showUnlinked = !showUnlinked;
				selectedDevice = -1;
				initGui();
				break;
			case ID_DEVICE_LIST:
			{
				int index = ((GuiReactiveList)button).selectedOption;
				if(index >= 0&&index < deviceView.size())
				{
					selectedDevice = index;
					initGui();
				}
				break;
			}
			case ID_PRIO_DOWN:
				withDevice(device -> sendDevice(device, "priority", device.getPriority()-1));
				break;
			case ID_PRIO_UP:
				withDevice(device -> sendDevice(device, "priority", device.getPriority()+1));
				break;
			case ID_CAP_DOWN:
				withDevice(device -> sendDevice(device, "transferCap",
						Math.max(0, device.getTransferCap()/2)));
				break;
			case ID_CAP_UP:
				withDevice(device -> sendDevice(device, "transferCap",
						Math.max(1, device.getTransferCap()*2)));
				break;
			case ID_CRITICAL:
				withDevice(device -> sendDeviceFlag(device, "critical", !device.isCritical()));
				break;
			case ID_SIG_MODE:
				withDevice(device -> sendDeviceFlag(device, "signalOutput", !device.isSignalOutput()));
				break;
			case ID_SIG_INVERT:
				withDevice(device -> sendDeviceFlag(device, "signalInverted",
						!device.isSignalInverted()));
				break;
			case ID_CHUNKLOAD:
				withDevice(device -> sendDeviceFlag(device, "chunkLoad",
						!device.isChunkLoadRequested()));
				break;
			case ID_DEV_ENABLED:
				withDevice(device -> sendDeviceFlag(device, "enabled", !device.isEnabled()));
				break;
			case ID_DEV_LINK:
				withDevice(device -> {
					NBTTagCompound args = devicePos(device);
					args.setBoolean("unlink", device.isLinked());
					send(Op.ASSIGN_DEVICE, selected, args);
					selectedDevice = -1;
				});
				break;

			case ID_CHAIN_LIST:
				selectedChain = ((GuiReactiveList)button).selectedOption;
				break;
			case ID_CANDIDATE_LIST:
				selectedCandidate = ((GuiReactiveList)button).selectedOption;
				break;
			case ID_CHAIN_UP:
			case ID_CHAIN_DOWN:
				if(selectedChain >= 0&&selectedChain < chainView.size())
				{
					NBTTagCompound args = new NBTTagCompound();
					args.setString("target", chainView.get(selectedChain).toString());
					args.setBoolean("up", button.id==ID_CHAIN_UP);
					send(Op.MOVE_FAILOVER, selected, args);
					selectedChain += button.id==ID_CHAIN_UP?-1: 1;
					initGui();
				}
				break;
			case ID_CHAIN_REMOVE:
				if(selectedChain >= 0&&selectedChain < chainView.size())
				{
					NBTTagCompound args = new NBTTagCompound();
					args.setString("target", chainView.get(selectedChain).toString());
					send(Op.REMOVE_FAILOVER, selected, args);
					selectedChain = -1;
					initGui();
				}
				break;
			case ID_CHAIN_ADD:
				if(selectedCandidate >= 0&&selectedCandidate < candidateView.size())
				{
					NBTTagCompound args = new NBTTagCompound();
					args.setString("target", candidateView.get(selectedCandidate).toString());
					send(Op.ADD_FAILOVER, selected, args);
					selectedCandidate = -1;
					initGui();
				}
				break;

			case ID_RESET_METERS:
				send(Op.RESET_METER, selected, new NBTTagCompound());
				break;
			default:
				break;
		}
	}

	private void withSegment(java.util.function.Consumer<GridSegment> action)
	{
		GridSegment segment = selectedSegment();
		if(segment!=null)
		{
			action.accept(segment);
			initGui();
		}
	}

	private void withDevice(java.util.function.Consumer<GridDevice> action)
	{
		GridDevice device = selectedDevice();
		if(device!=null)
		{
			action.accept(device);
			initGui();
		}
	}

	private NBTTagCompound devicePos(GridDevice device)
	{
		NBTTagCompound args = new NBTTagCompound();
		args.setInteger("x", device.getPos().getX());
		args.setInteger("y", device.getPos().getY());
		args.setInteger("z", device.getPos().getZ());
		args.setInteger("dim", device.getPos().dimension);
		return args;
	}

	private void sendDevice(GridDevice device, String key, int value)
	{
		NBTTagCompound args = devicePos(device);
		args.setInteger(key, value);
		send(Op.SET_DEVICE, selected, args);
	}

	private void sendDeviceFlag(GridDevice device, String key, boolean value)
	{
		NBTTagCompound args = devicePos(device);
		args.setBoolean(key, value);
		send(Op.SET_DEVICE, selected, args);
	}

	private void applyEditor()
	{
		GridSegment segment = selectedSegment();
		if(segment==null)
			return;
		String name = nameField.getText().trim();
		if(!name.isEmpty()&&!name.equals(segment.getName()))
		{
			NBTTagCompound args = new NBTTagCompound();
			args.setString("name", name);
			send(Op.RENAME_SEGMENT, selected, args);
		}
		NBTTagCompound policy = new NBTTagCompound();
		policy.setInteger("maxInput", parseInt(inputField.getText(), segment.getPolicy().getMaxInput()));
		policy.setInteger("maxOutput", parseInt(outputField.getText(), segment.getPolicy().getMaxOutput()));
		policy.setDouble("lossPct", parseDouble(lossField.getText(), segment.getPolicy().getLossPct()*100)/100.0);
		policy.setInteger("bufferCap", parseInt(bufferField.getText(), segment.getPolicy().getBufferCap()));
		policy.setInteger("scheduleOn", parseInt(schedOnField.getText(), segment.getPolicy().getScheduleOn()));
		policy.setInteger("scheduleOff", parseInt(schedOffField.getText(), segment.getPolicy().getScheduleOff()));
		send(Op.SET_POLICY, selected, policy);
		fieldsLoadedFor = null;
	}

	private String uniqueDefaultName()
	{
		for(int i = 1; i < 500; i++)
		{
			String candidate = "Segment "+i;
			if(grid().getSegmentByName(candidate)==null)
				return candidate;
		}
		return "Segment";
	}

	private static int parseInt(String text, int fallback)
	{
		try
		{
			return Integer.parseInt(text.trim());
		} catch(NumberFormatException e)
		{
			return fallback;
		}
	}

	private static double parseDouble(String text, double fallback)
	{
		try
		{
			return Double.parseDouble(text.trim());
		} catch(NumberFormatException e)
		{
			return fallback;
		}
	}

	private void send(Op op, @Nullable UUID segment, NBTTagCompound args)
	{
		ImmersiveEngineering.packetHandler.sendToServer(new MessageGridAction(op, segment, args));
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
	{
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if(tab==Tab.SEGMENTS)
		{
			nameField.mouseClicked(mouseX, mouseY, mouseButton);
			inputField.mouseClicked(mouseX, mouseY, mouseButton);
			outputField.mouseClicked(mouseX, mouseY, mouseButton);
			lossField.mouseClicked(mouseX, mouseY, mouseButton);
			bufferField.mouseClicked(mouseX, mouseY, mouseButton);
			schedOnField.mouseClicked(mouseX, mouseY, mouseButton);
			schedOffField.mouseClicked(mouseX, mouseY, mouseButton);
		}
		else if(tab==Tab.OVERVIEW)
			handleOverviewClick(mouseX, mouseY);
	}

	private void handleOverviewClick(int mouseX, int mouseY)
	{
		int rowTop = guiTop+52;
		int rows = Math.min(listOrder.size()-overviewScroll, overviewRowCapacity());
		for(int i = 0; i < rows; i++)
		{
			int y = rowTop+i*15;
			if(mouseX >= guiLeft+BODY_X&&mouseX <= guiLeft+BODY_R&&mouseY >= y&&mouseY < y+14)
			{
				selected = listOrder.get(overviewScroll+i);
				tab = Tab.SEGMENTS;
				initGui();
				return;
			}
		}
	}

	private int overviewRowCapacity()
	{
		return (BODY_B-52)/15;
	}

	@Override
	public void handleMouseInput() throws IOException
	{
		super.handleMouseInput();
		if(tab!=Tab.OVERVIEW)
			return;
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if(wheel==0)
			return;
		int max = Math.max(0, grid().getSegmentCount()-overviewRowCapacity());
		overviewScroll = Math.max(0, Math.min(max, overviewScroll+(wheel < 0?1: -1)));
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException
	{
		if(tab==Tab.SEGMENTS)
		{
			if(keyCode==Keyboard.KEY_RETURN&&anyFieldFocused())
			{
				applyEditor();
				return;
			}
			if(nameField.textboxKeyTyped(typedChar, keyCode)
					||inputField.textboxKeyTyped(typedChar, keyCode)
					||outputField.textboxKeyTyped(typedChar, keyCode)
					||lossField.textboxKeyTyped(typedChar, keyCode)
					||bufferField.textboxKeyTyped(typedChar, keyCode)
					||schedOnField.textboxKeyTyped(typedChar, keyCode)
					||schedOffField.textboxKeyTyped(typedChar, keyCode))
				return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	private boolean anyFieldFocused()
	{
		return nameField.isFocused()||inputField.isFocused()||outputField.isFocused()
				||lossField.isFocused()||bufferField.isFocused()
				||schedOnField.isFocused()||schedOffField.isFocused();
	}

	@Override
	public void onGuiClosed()
	{
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
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
		boolean powered = tile==null||tile.isPowered();
		fontRenderer.drawString("GRID MANAGEMENT CONSOLE", guiLeft+8, guiTop+8,
				powered?Lib.COLOUR_I_ImmersiveOrange: COL_DIM);
		if(!powered)
			drawRightAligned("NO POWER", guiLeft+WIDTH-8, guiTop+8, COL_BAD);
		else if(CityMode.grid())
			drawRightAligned("CITY MODE", guiLeft+WIDTH-8, guiTop+8, 0x59A8C4);

		drawRect(guiLeft+BODY_X-2, guiTop+BODY_Y-2, guiLeft+BODY_R+2, guiTop+BODY_B+2, COL_INSET);

		if(!ClientGridCache.isPopulated())
		{
			fontRenderer.drawString("Linking to grid...", guiLeft+BODY_X+6, guiTop+BODY_Y+6, COL_DIM);
			return;
		}

		switch(tab)
		{
			case OVERVIEW:
				drawOverview();
				break;
			case SEGMENTS:
				drawSegments();
				break;
			case DEVICES:
				drawDevices();
				break;
			case FAILOVER:
				drawFailover();
				break;
			case STATS:
				drawStats();
				break;
			case SETTINGS:
				drawSettings();
				break;
		}
	}

	private void drawOverview()
	{
		VirtualGrid grid = grid();
		fontRenderer.drawString("Grid throughput", guiLeft+BODY_X+4, guiTop+BODY_Y+4, COL_TEXT);
		fontRenderer.drawString(grid.getTotalIn()+" IF/t in", guiLeft+BODY_X+4, guiTop+BODY_Y+16, COL_GOOD);
		fontRenderer.drawString(grid.getTotalOut()+" IF/t out", guiLeft+BODY_X+80, guiTop+BODY_Y+16, COL_WARN);
		drawRightAligned(grid.getSegmentCount()+" segments", guiLeft+BODY_R-4, guiTop+BODY_Y+16, COL_DIM);
		drawRect(guiLeft+BODY_X+2, guiTop+48, guiLeft+BODY_R-2, guiTop+49, COL_LINE);

		if(listOrder.isEmpty())
		{
			fontRenderer.drawString("No segments yet.", guiLeft+BODY_X+4, guiTop+58, COL_DIM);
			fontRenderer.drawString("Open the Segments tab to create one.",
					guiLeft+BODY_X+4, guiTop+70, COL_DIM);
			return;
		}

		int rows = Math.min(listOrder.size()-overviewScroll, overviewRowCapacity());
		for(int i = 0; i < rows; i++)
		{
			GridSegment segment = grid.getSegment(listOrder.get(overviewScroll+i));
			if(segment==null)
				continue;
			int y = guiTop+52+i*15;
			drawRect(guiLeft+BODY_X, y, guiLeft+BODY_R, y+14, COL_PANEL);
			drawRect(guiLeft+BODY_X+2, y+2, guiLeft+BODY_X+7, y+12, 0xFF000000|segment.getColor());
			fontRenderer.drawString(trim(segment.getName(), 78), guiLeft+BODY_X+11, y+4, COL_TEXT);
			fontRenderer.drawString(stateLabel(segment), guiLeft+BODY_X+94, y+4, stateColour(segment));
			drawRightAligned(segment.getStats().getLastTickOut()+" IF/t",
					guiLeft+BODY_R-4, y+4, COL_DIM);
		}
		if(listOrder.size() > overviewRowCapacity())
			drawRightAligned("scroll for more", guiLeft+BODY_R-4, guiTop+BODY_B-8, COL_DIM);
	}

	private void drawSegments()
	{
		drawRect(guiLeft+BODY_X, guiTop+BODY_Y, guiLeft+BODY_X+74, guiTop+BODY_Y+134, COL_PANEL);
		drawRect(guiLeft+EDITOR_X-2, guiTop+BODY_Y, guiLeft+BODY_R, guiTop+BODY_B, COL_PANEL);

		GridSegment segment = selectedSegment();
		if(segment==null)
		{
			fontRenderer.drawString("No segment selected.", guiLeft+EDITOR_X+4, guiTop+BODY_Y+6, COL_DIM);
			fontRenderer.drawString("Press New to create one.", guiLeft+EDITOR_X+4, guiTop+BODY_Y+18, COL_DIM);
			return;
		}

		drawRect(guiLeft+EDITOR_X, guiTop+BODY_Y, guiLeft+BODY_R-2, guiTop+BODY_Y+14, COL_INSET);
		nameField.drawTextBox();

		fontRenderer.drawString(stateLabel(segment), guiLeft+EDITOR_X+24, guiTop+42, stateColour(segment));
		fontRenderer.drawString(segment.getDeviceCount(GridDeviceType.FEED)+" feed  "
						+segment.getDeviceCount(GridDeviceType.SERVICE)+" service",
				guiLeft+EDITOR_X+24, guiTop+53, COL_DIM);
		if(!CityMode.grid())
			fontRenderer.drawString("buf "+segment.getBuffer()+"/"+segment.getPolicy().getBufferCap(),
					guiLeft+EDITOR_X+24, guiTop+62, COL_DIM);

		fontRenderer.drawString("Max in/t", guiLeft+EDITOR_X+2, guiTop+97, COL_TEXT);
		fontRenderer.drawString("Max out/t", guiLeft+EDITOR_X+2, guiTop+111, COL_TEXT);
		fontRenderer.drawString("Loss %", guiLeft+EDITOR_X+2, guiTop+125, COL_TEXT);
		fontRenderer.drawString("Buffer", guiLeft+EDITOR_X+2, guiTop+139, COL_TEXT);
		//Day-time ticks rather than a clock widget: it is the unit /time set and every other
		//redstone contraption in the game already speaks, so the two agree by construction.
		boolean scheduled = segment.getPolicy().isScheduleEnabled();
		fontRenderer.drawString("On at", guiLeft+EDITOR_X+2, guiTop+153, scheduled?COL_TEXT: COL_DIM);
		fontRenderer.drawString("Off at", guiLeft+EDITOR_X+2, guiTop+167, scheduled?COL_TEXT: COL_DIM);
		for(int row = 0; row < 6; row++)
			drawRect(guiLeft+EDITOR_X+46, guiTop+95+row*14, guiLeft+BODY_R-2, guiTop+107+row*14, COL_INSET);
		inputField.drawTextBox();
		outputField.drawTextBox();
		lossField.drawTextBox();
		bufferField.drawTextBox();
		schedOnField.drawTextBox();
		schedOffField.drawTextBox();

		//Say why a segment that is switched on is nonetheless not running. Without this, a
		//schedule that has just closed looks exactly like a fault.
		if(segment.isScheduleSuppressed())
			fontRenderer.drawString("asleep until "+segment.getPolicy().getScheduleOn(),
					guiLeft+BODY_X+2, guiTop+194, COL_DIM);
		else if(segment.isForcedOff())
			fontRenderer.drawString("held off by signal", guiLeft+BODY_X+2, guiTop+194, COL_WARN);
	}

	private void drawDevices()
	{
		GridSegment segment = selectedSegment();
		//In "unlinked" mode the selected segment is the link destination, so say so up
		//front rather than making the player remember it from the Segments tab.
		String header = showUnlinked
				?(segment==null?"Unlinked devices": "Unlinked  >  "+trim(segment.getName(), 80))
				:(segment==null?"No segment selected": "On "+trim(segment.getName(), 100));
		fontRenderer.drawString(header, guiLeft+BODY_X+96, guiTop+28,
				showUnlinked&&segment!=null?COL_GOOD: COL_TEXT);
		drawRect(guiLeft+BODY_X, guiTop+40, guiLeft+BODY_R, guiTop+124, COL_PANEL);

		//Highlight the selected row inside the list widget.
		if(selectedDevice >= 0&&selectedDevice < deviceView.size())
		{
			int rowY = guiTop+43+(selectedDevice)*fontRenderer.FONT_HEIGHT;
			if(rowY < guiTop+122)
				drawRect(guiLeft+BODY_X+1, rowY-1, guiLeft+BODY_R-1,
						rowY+fontRenderer.FONT_HEIGHT-1, 0xFF313131);
		}

		drawRect(guiLeft+BODY_X, guiTop+128, guiLeft+BODY_R, guiTop+BODY_B, COL_PANEL);
		GridDevice device = selectedDevice();
		if(device==null)
		{
			if(deviceView.isEmpty()&&showUnlinked)
				fontRenderer.drawString("Every device is already assigned.",
						guiLeft+BODY_X+4, guiTop+134, COL_DIM);
			else
				fontRenderer.drawString("Select a device to configure it.",
						guiLeft+BODY_X+4, guiTop+134, COL_DIM);
			return;
		}
		//The commonest dead end: an unlinked device is selected but there is nowhere to put
		//it, so the link button is absent with no explanation.
		if(!device.isLinked()&&selectedSegment()==null)
			fontRenderer.drawString("Pick a segment on the Segments tab to link this to.",
					guiLeft+BODY_X+4, guiTop+180, COL_WARN);
		String where = "dim "+device.getPos().dimension+"  "+device.getPos().getX()
				+", "+device.getPos().getY()+", "+device.getPos().getZ();
		fontRenderer.drawString(where, guiLeft+BODY_X+4, guiTop+132, COL_TEXT);
		fontRenderer.drawString(device.isOnline()?"online": "offline",
				guiLeft+BODY_X+150, guiTop+132, device.isOnline()?COL_GOOD: COL_DIM);
		fontRenderer.drawString("meter "+formatEnergy(device.getLifetimeThroughput())
						+"   now "+device.getLastThroughput()+" IF/t",
				guiLeft+BODY_X+4, guiTop+198, COL_DIM);
	}

	private void drawFailover()
	{
		GridSegment segment = selectedSegment();
		fontRenderer.drawString("Backup chain", guiLeft+BODY_X, guiTop+28, COL_TEXT);
		drawRect(guiLeft+BODY_X, guiTop+40, guiLeft+BODY_X+118, guiTop+107, COL_PANEL);
		fontRenderer.drawString("Available segments", guiLeft+BODY_X, guiTop+114, COL_TEXT);
		drawRect(guiLeft+BODY_X, guiTop+124, guiLeft+BODY_X+118, guiTop+173, COL_PANEL);

		if(segment==null)
		{
			fontRenderer.drawString("No segment selected.", guiLeft+BODY_X+124, guiTop+44, COL_DIM);
			return;
		}

		//Live resolution preview: exactly the walk the engine performs, so what is shown
		//here cannot drift from what happens at runtime.
		List<GridSegment> chain = GridEngine.failoverChain(grid(), segment);
		GridSegment first = GridEngine.firstAvailableBackup(grid(), segment, CityMode.grid());
		fontRenderer.drawString("Resolution preview", guiLeft+BODY_X, guiTop+194, COL_TEXT);
		if(chain.isEmpty())
			fontRenderer.drawString("no backups configured", guiLeft+BODY_X, guiTop+204, COL_DIM);
		else if(first==null)
			fontRenderer.drawString("chain of "+chain.size()+", none can supply now",
					guiLeft+BODY_X, guiTop+204, COL_WARN);
		else
			fontRenderer.drawString(trim("would draw from "+first.getName(), BODY_W),
					guiLeft+BODY_X, guiTop+204, COL_GOOD);
	}

	private void drawStats()
	{
		GridSegment segment = selectedSegment();
		if(segment==null)
		{
			fontRenderer.drawString("No segment selected.", guiLeft+BODY_X+4, guiTop+30, COL_DIM);
			return;
		}
		fontRenderer.drawString(trim(segment.getName(), 120), guiLeft+BODY_X, guiTop+28, COL_TEXT);
		if(CityMode.grid())
			drawRightAligned("presence accounting", guiLeft+BODY_R, guiTop+28, 0x59A8C4);

		GridStats stats = segment.getStats();
		int peak = graph.draw(stats.getHistoryIn(), stats.getHistoryOut(), COL_IN, COL_OUT);

		fontRenderer.drawString("in", guiLeft+BODY_X+10, guiTop+116, COL_IN);
		drawRect(guiLeft+BODY_X, guiTop+117, guiLeft+BODY_X+8, guiTop+123, COL_IN);
		fontRenderer.drawString("out", guiLeft+BODY_X+42, guiTop+116, COL_OUT);
		drawRect(guiLeft+BODY_X+32, guiTop+117, guiLeft+BODY_X+40, guiTop+123, COL_OUT);
		drawRightAligned(peak > 0?"peak "+peak+" IF/s": "no traffic yet",
				guiLeft+BODY_R, guiTop+116, COL_DIM);

		fontRenderer.drawString("last tick: "+stats.getLastTickIn()+" in / "
				+stats.getLastTickOut()+" out", guiLeft+BODY_X, guiTop+132, COL_TEXT);
		fontRenderer.drawString("lifetime: "+formatEnergy(stats.getLifetimeIn())+" in / "
				+formatEnergy(stats.getLifetimeOut())+" out", guiLeft+BODY_X, guiTop+142, COL_TEXT);
		fontRenderer.drawString(stats.getSampleCount()+"s of history", guiLeft+BODY_X, guiTop+152, COL_DIM);

		//Busiest devices by lifetime meter.
		List<GridDevice> top = new ArrayList<>(segment.getDevices());
		top.sort((a, b) -> Long.compare(b.getLifetimeThroughput(), a.getLifetimeThroughput()));
		fontRenderer.drawString("Busiest devices", guiLeft+BODY_X, guiTop+166, COL_TEXT);
		int shown = Math.min(3, top.size());
		for(int i = 0; i < shown; i++)
		{
			GridDevice device = top.get(i);
			fontRenderer.drawString(trim(device.getDisplayName(), 120),
					guiLeft+BODY_X, guiTop+176+i*10, COL_DIM);
			drawRightAligned(formatEnergy(device.getLifetimeThroughput()),
					guiLeft+BODY_R, guiTop+176+i*10, COL_DIM);
		}
		if(shown==0)
			fontRenderer.drawString("none", guiLeft+BODY_X, guiTop+176, COL_DIM);
	}

	private void drawSettings()
	{
		int y = guiTop+28;
		fontRenderer.drawString("Grid configuration", guiLeft+BODY_X, y, COL_TEXT);
		y += 14;
		y = settingLine(y, "Power mode", CityMode.grid()?"city (presence)": "normal (metered)");
		y = settingLine(y, "Cross-dimension", GridConfig.crossDimension?"allowed": "off");
		y = settingLine(y, "Max segment I/O", GridConfig.maxSegmentIO+" IF/t");
		y = settingLine(y, "Max buffer", GridConfig.bufferCapMax+" IF");
		y = settingLine(y, "Default device cap", GridConfig.defaultDeviceCap+" IF/t");
		y = settingLine(y, "Breakers", GridConfig.breakersEnabled
				?"trip after "+GridConfig.breakerTripSeconds+"s": "off");
		y = settingLine(y, "Console power", GridConfig.consoleRequiresPower
				?GridConfig.consoleStandbyDraw+" IF/t": "not required");
		if(CityMode.grid())
			y = settingLine(y, "Liveness sip", GridConfig.sipAmount+" IF every "
					+GridConfig.sipIntervalTicks+"t");
		y += 6;
		fontRenderer.drawString("Chunk loading", guiLeft+BODY_X, y, COL_TEXT);
		y += 12;
		if(!GridConfig.allowChunkloading)
			settingLine(y, "Status", "disabled in config");
		else
			settingLine(y, "Budget", GridConfig.chunkloadBudget+" chunks");
	}

	private int settingLine(int y, String label, String value)
	{
		fontRenderer.drawString(label, guiLeft+BODY_X, y, COL_DIM);
		drawRightAligned(value, guiLeft+BODY_R, y, COL_TEXT);
		return y+11;
	}

	/**
	 * Compact energy figure, so a lifetime meter does not run off the panel.
	 */
	static String formatEnergy(long value)
	{
		if(value < 1000)
			return value+" IF";
		if(value < 1000000)
			return String.format(Locale.ENGLISH, "%.1fk IF", value/1000.0);
		if(value < 1000000000L)
			return String.format(Locale.ENGLISH, "%.1fM IF", value/1000000.0);
		return String.format(Locale.ENGLISH, "%.1fG IF", value/1000000000.0);
	}

	private static String stateLabel(GridSegment segment)
	{
		if(segment.isTripped())
			return "TRIPPED";
		if(!segment.isEnabled())
			return "off";
		//These two look identical to "off" from the outside, and the whole point of an
		//external kill switch or a schedule is that nobody touched the console switch.
		if(segment.isForcedOff())
			return "held off";
		if(segment.isScheduleSuppressed())
			return "scheduled off";
		if(CityMode.grid())
			return segment.isEnergized()?"energized": "no source";
		return "on";
	}

	private static int stateColour(GridSegment segment)
	{
		if(segment.isTripped())
			return COL_BAD;
		if(!segment.isEnabled()||segment.isScheduleSuppressed())
			return COL_DIM;
		if(segment.isForcedOff())
			return COL_WARN;
		if(CityMode.grid()&&!segment.isEnergized())
			return COL_WARN;
		return COL_GOOD;
	}

	private void drawRightAligned(String text, int right, int y, int colour)
	{
		fontRenderer.drawString(text, right-fontRenderer.getStringWidth(text), y, colour);
	}

	private String trim(String text, int maxWidth)
	{
		return fontRenderer.getStringWidth(text) <= maxWidth?text
				: fontRenderer.trimStringToWidth(text, maxWidth-6)+"...";
	}

	/**
	 * A flat colour chip used for the segment palette.
	 */
	private static class GuiColourSwatch extends GuiButton
	{
		private final int colour;
		/**
		 * Not final: refreshWidgets keeps the highlight in step with the synced colour.
		 */
		private boolean active;

		GuiColourSwatch(int id, int x, int y, int colour, boolean active)
		{
			super(id, x, y, 10, 10, "");
			this.colour = colour;
			this.active = active;
		}

		@Override
		public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partialTicks)
		{
			if(!visible)
				return;
			this.hovered = mouseX >= x&&mouseY >= y&&mouseX < x+width&&mouseY < y+height;
			if(active||hovered)
				drawRect(x-1, y-1, x+width+1, y+height+1, active?0xFFFFFFFF: 0xFF909090);
			drawRect(x, y, x+width, y+height, 0xFF000000|colour);
		}
	}
}
