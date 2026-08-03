/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.fluid.network.*;
import blusunrize.immersiveengineering.client.gui.elements.*;
import blusunrize.immersiveengineering.common.blocks.fluidnet.TileEntityFluidConsole;
import blusunrize.immersiveengineering.common.gui.ContainerFluidConsole;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.fluidnet.ClientFluidNetCache;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetAction;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetAction.Op;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import org.lwjgl.input.Keyboard;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Fluid Control Console screen.
 * <p>
 * Drawn entirely from primitives -- no texture sheet -- so the layout can be reworked in-game
 * without round-tripping a PNG. Every control round-trips through {@link MessageFluidNetAction} and
 * the panel then redraws from the next server sync; nothing is applied optimistically, so the
 * screen can never disagree with the world.
 * <p>
 * Four tabs where the grid's console has six. That is not a shortfall: failover chains and the
 * long-form stats page are managed here through the same commands the grid exposes, and a fluid
 * main has one thing the grid does not -- what it carries -- which earns a place on the main
 * editor rather than a tab of its own.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class GuiFluidConsole extends GuiIEContainerBase
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
	private static final int ID_MAIN_LIST = 20;
	private static final int ID_NEW = 21, ID_DELETE = 22, ID_TOGGLE = 23;
	private static final int ID_APPLY = 24, ID_LOCK = 25, ID_TOPUP = 26, ID_TRIP = 27;
	private static final int ID_SCHEDULE = 28, ID_CLEAR_FLUID = 29;
	private static final int ID_SWATCH_BASE = 40;

	private static final int ID_DEVICE_LIST = 60, ID_FILTER = 61;
	private static final int ID_PRIO_DOWN = 62, ID_PRIO_UP = 63;
	private static final int ID_CAP_DOWN = 64, ID_CAP_UP = 65;
	private static final int ID_CRITICAL = 66, ID_CHUNKLOAD = 67, ID_DEV_ENABLED = 68;
	private static final int ID_DEV_LINK = 69;
	private static final int ID_VALVE_MODE = 70, ID_VALVE_INVERT = 71;

	private static final int ID_RESET_METERS = 100;

	private enum Tab
	{
		OVERVIEW("Overview"),
		MAINS("Mains"),
		FITTINGS("Fittings"),
		STATS("Stats");

		final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private final TileEntityFluidConsole tile;
	private Tab tab = Tab.OVERVIEW;

	@Nullable
	private UUID selected;
	private int overviewScroll;

	private GuiTextField nameField, inputField, outputField, leakField, packField;
	private GuiTextField fluidField;
	/**
	 * The schedule window's two ends, in day-time ticks.
	 * <p>
	 * These were missing, and their absence was not cosmetic: the tab has always carried a
	 * "Sched: on/off" toggle, so a main's schedule could be switched <em>on</em> and its window
	 * could never be set. It ran on {@link FluidPolicy#DEFAULT_ON} to {@link FluidPolicy#DEFAULT_OFF}
	 * for good. The grid console has had the same pair since it shipped.
	 */
	private GuiTextField schedOnField, schedOffField;

	/** Every field on the Mains tab, in tab order -- one list, so none can be missed by one of them. */
	private GuiTextField[] mainsFields()
	{
		return new GuiTextField[]{nameField, fluidField, inputField, outputField, leakField,
				packField, schedOnField, schedOffField};
	}

	private final List<UUID> listOrder = new ArrayList<>();
	private UUID fieldsLoadedFor;

	/**
	 * Fittings tab: false shows the selected main's fittings, true shows unlinked ones.
	 */
	private boolean showUnlinked;
	private final List<FluidDevice> deviceView = new ArrayList<>();
	private int selectedDevice = -1;

	private GuiLineGraph graph;
	/**
	 * Snapshot of the state the current widgets were built from, so a rebuild happens only when
	 * the set of widgets actually has to change.
	 */
	private String lastSignature = "";

	public GuiFluidConsole(InventoryPlayer inventoryPlayer, TileEntityFluidConsole tile)
	{
		super(new ContainerFluidConsole(inventoryPlayer, tile));
		this.tile = tile;
		this.xSize = WIDTH;
		this.ySize = HEIGHT;
	}

	private static VirtualFluidNet net()
	{
		return ClientFluidNetCache.get();
	}

	@Nullable
	private FluidMain selectedMain()
	{
		return net().getMain(selected);
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
			case MAINS:
				initMainsTab();
				break;
			case FITTINGS:
				initFittingsTab();
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

	@Override
	public void onGuiClosed()
	{
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
	}

	/**
	 * Rebuilds the panel when the <em>set</em> of things it is showing changes.
	 * <p>
	 * Without this the window is built once and never again: a console opened before the first sync
	 * arrives shows an empty main list for as long as it stays open, and creating a main, linking a
	 * fitting or deleting anything appears to do nothing until the player switches tabs. The server
	 * had the right answer the whole time -- the screen simply never asked again.
	 * <p>
	 * Gated on a signature rather than rebuilt unconditionally, because {@link #initGui} recreates
	 * the text fields and would eat the player's caret twice a second while they typed a name.
	 */
	@Override
	public void updateScreen()
	{
		super.updateScreen();
		if(tab==Tab.FITTINGS)
			rebuildDeviceView();
		String signature = structureSignature();
		if(!signature.equals(lastSignature))
			initGui();
		refreshWidgets();
	}

	/**
	 * Re-reads the model into the captions and toggle states of the widgets that already exist, so a
	 * click is reflected as soon as the server's answer arrives.
	 * <p>
	 * <strong>This screen had none, and the grid console has had one since it shipped.</strong>
	 * Every toggle here was baked at {@link #initGui} time and none of their states are in
	 * {@link #structureSignature}, so nothing ever rebuilt them: clicking "Sched: off" sent the
	 * packet, the server flipped the flag and sent it back, and the caption went on saying "off"
	 * until the player changed tabs. Lock, Backup, Chunkload, Critical, Enabled and both Valve
	 * controls were all stuck the same way -- a screen full of switches that appeared not to work.
	 * <p>
	 * Putting the states into the signature instead would have worked and been wrong: a rebuild
	 * recreates the text fields, so the panel would have eaten the player's caret every time any
	 * toggle anywhere changed. The signature is for the <em>set</em> of widgets; this is for their
	 * contents.
	 */
	private void refreshWidgets()
	{
		FluidMain main = selectedMain();
		FluidDevice device = selectedDeviceRecord();
		for(GuiButton button : this.buttonList)
		{
			if(button.id >= ID_SWATCH_BASE&&button.id < ID_SWATCH_BASE+FluidMain.PALETTE.length)
			{
				if(main!=null&&button instanceof GuiColourSwatch)
					((GuiColourSwatch)button).active =
							FluidMain.PALETTE[button.id-ID_SWATCH_BASE]==main.getColor();
				continue;
			}
			switch(button.id)
			{
				case ID_TOGGLE:
					if(main!=null&&button instanceof GuiToggleSwitch)
					{
						((GuiToggleSwitch)button).state = main.isEnabled();
						((GuiToggleSwitch)button).tripped = main.isTripped();
					}
					break;
				case ID_LOCK:
					if(main!=null)
						setToggle(button, main.isLocked(), "Locked", "Unlocked");
					break;
				case ID_SCHEDULE:
					if(main!=null)
						setToggle(button, main.getPolicy().isScheduleEnabled(),
								"Sched: on", "Sched: off");
					break;
				case ID_TOPUP:
					if(main!=null)
						setToggle(button, main.getPolicy().isFailoverTopUp(),
								"Backup: any", "Backup: outage");
					break;
				case ID_FILTER:
					setToggle(button, showUnlinked, "Show: unlinked", "Show: this main");
					break;
				case ID_VALVE_MODE:
					if(device!=null)
						setToggle(button, device.isValveOutput(),
								"Indicator: main -> RS", "Shut-off: RS -> main");
					break;
				case ID_VALVE_INVERT:
					if(device!=null)
						setToggle(button, device.isValveInverted(), "Inverted", "Normal");
					break;
				case ID_CRITICAL:
					if(device!=null)
						setToggle(button, device.isCritical(), "Critical", "Normal");
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
		FluidMain main = selectedMain();
		FluidDevice device = selectedDeviceRecord();
		StringBuilder sig = new StringBuilder(tab.name());
		sig.append('|').append(net().getMainCount());
		//The fluid and the pack are in here because both decide whether the "clear fluid" button
		//exists at all -- and that button appearing the moment a main finishes draining is the one
		//piece of feedback that tells a player why it was missing before.
		sig.append('|').append(main==null?"-": main.getId()+":"+main.isTripped()
				+":"+main.getFluid()+":"+(main.getPack() > 0));
		sig.append('|').append(showUnlinked).append(':').append(deviceView.size());
		sig.append('|').append(device==null?"-": device.getPos()+":"+device.isLinked()
				+":"+device.getType());
		return sig.toString();
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

	/**
	 * Draw text fields from inside the foreground layer.
	 * <p>
	 * <strong>Undoing the container's translate, which is not decoration.</strong> Everything else
	 * on this screen is drawn from {@code drawGuiContainerForegroundLayer}, and vanilla's
	 * {@code GuiContainer} translates by {@code (guiLeft, guiTop)} before calling it -- so the
	 * relative coordinates every {@code drawString} here uses are correct. A {@link GuiTextField}
	 * is not drawn that way: it stores its own position and {@code drawTextBox()} renders at it,
	 * and these were built with absolute coordinates so that {@code mouseClicked} -- which is
	 * handed absolute mouse coordinates -- would find them.
	 * <p>
	 * Absolute coordinates drawn inside a translated matrix land at {@code guiLeft*2}, half a screen
	 * down and to the right of the panel, while the field stays clickable where it always was. That
	 * is precisely the Gas Station Pump's price field, reported as "the box shows nothing but Set
	 * works". Cancelling the translate for exactly these widgets keeps both halves right, and keeps
	 * the fix to the one place it belongs rather than converting every string on the screen.
	 */
	private void drawFields(GuiTextField... fields)
	{
		GlStateManager.pushMatrix();
		GlStateManager.translate(-guiLeft, -guiTop, 0);
		for(GuiTextField field : fields)
			if(field!=null)
				field.drawTextBox();
		GlStateManager.popMatrix();
	}

	private GuiTextField makeField(int x, int y, int w, int maxLength)
	{
		GuiTextField field = new GuiTextField(0, this.fontRenderer, x, y, w, 9);
		field.setMaxStringLength(maxLength);
		field.setEnableBackgroundDrawing(false);
		field.setTextColor(0xE0E0E0);
		return field;
	}

	private void rebuildListOrder()
	{
		listOrder.clear();
		for(FluidMain main : net().getMains())
			listOrder.add(main.getId());
		if(selected==null&&!listOrder.isEmpty())
			selected = listOrder.get(0);
	}

	//	=================================
	//		MAINS TAB
	//	=================================

	private void initMainsTab()
	{
		List<String> names = new ArrayList<>();
		for(UUID id : listOrder)
		{
			FluidMain main = net().getMain(id);
			names.add(main==null?"?": main.getName());
		}
		this.buttonList.add(list(ID_MAIN_LIST, BODY_X+2, BODY_Y+2, 70, 130, names));
		this.buttonList.add(button(ID_NEW, BODY_X+2, 158, 34, 14, "New"));
		this.buttonList.add(button(ID_DELETE, BODY_X+38, 158, 34, 14, "Del"));

		FluidMain main = selectedMain();
		nameField = makeField(guiLeft+EDITOR_X+2, guiTop+BODY_Y+2, 113, 32);
		fluidField = makeField(guiLeft+EDITOR_X+48, guiTop+96, 67, 40);
		inputField = makeField(guiLeft+EDITOR_X+48, guiTop+110, 67, 9);
		outputField = makeField(guiLeft+EDITOR_X+48, guiTop+124, 67, 9);
		leakField = makeField(guiLeft+EDITOR_X+48, guiTop+138, 67, 9);
		packField = makeField(guiLeft+EDITOR_X+48, guiTop+152, 67, 9);
		//Day-time ticks rather than a clock widget, exactly as the grid console does it: it is the
		//unit /time set speaks and every redstone contraption already agrees with, so the two need
		//no conversion between them.
		//
		//Side by side on one row where the grid console stacks them, because this editor column is
		//four rows taller than the grid's -- a main carries a fluid, which a segment does not -- and
		//stacking would have pushed the buttons below off the bottom of the panel.
		schedOnField = makeField(guiLeft+EDITOR_X+48, guiTop+166, 30, 5);
		schedOffField = makeField(guiLeft+EDITOR_X+85, guiTop+166, 30, 5);
		fieldsLoadedFor = null;
		loadFieldsFrom(main);

		if(main==null)
			return;
		this.buttonList.add(new GuiToggleSwitch(ID_TOGGLE, guiLeft+EDITOR_X+2, guiTop+40, 16, 24,
				main.isEnabled()).setTripped(main.isTripped()));
		for(int i = 0; i < FluidMain.PALETTE.length; i++)
			this.buttonList.add(new GuiColourSwatch(ID_SWATCH_BASE+i,
					guiLeft+EDITOR_X+2+(i%8)*12, guiTop+70+(i/8)*12,
					FluidMain.PALETTE[i], FluidMain.PALETTE[i]==main.getColor()));
		this.buttonList.add(toggle(ID_SCHEDULE, BODY_X+2, 176, 70, 14,
				main.getPolicy().isScheduleEnabled()?"Sched: on": "Sched: off",
				main.getPolicy().isScheduleEnabled()));
		this.buttonList.add(toggle(ID_TOPUP, BODY_X+2, 194, 70, 14,
				main.getPolicy().isFailoverTopUp()?"Backup: any": "Backup: outage",
				main.getPolicy().isFailoverTopUp()));
		this.buttonList.add(button(ID_APPLY, EDITOR_X+2, 182, 52, 14, "Apply"));
		this.buttonList.add(toggle(ID_LOCK, EDITOR_X+58, 182, 57, 14,
				main.isLocked()?"Locked": "Unlocked", main.isLocked()));
		//	=================================
		//	The last row, shared when it has to be.
		//	=================================
		//
		// Only offered when they can actually work. A greyed-out control the player cannot explain
		// is worse than one that is simply absent while the main still holds something.
		//
		// Both can apply at once -- a tripped main with a fluid type and an empty pack is an
		// ordinary thing to be looking at -- and the schedule row above took the space they used to
		// have one under the other. So they share a row when both are wanted, with shorter labels,
		// and each takes the full width when it is alone.
		boolean canClear = main.isTyped()&&main.getPack() <= 0;
		boolean canReset = main.isTripped();
		if(canClear&&canReset)
		{
			this.buttonList.add(button(ID_CLEAR_FLUID, EDITOR_X+2, 198, 55, 14, "Clear fluid"));
			this.buttonList.add(button(ID_TRIP, EDITOR_X+60, 198, 55, 14, "Reset trip"));
		}
		else if(canClear)
			this.buttonList.add(button(ID_CLEAR_FLUID, EDITOR_X+2, 198, 113, 14, "Clear fluid"));
		else if(canReset)
			this.buttonList.add(button(ID_TRIP, EDITOR_X+2, 198, 113, 14, "Reset overpressure"));
	}

	private void loadFieldsFrom(@Nullable FluidMain main)
	{
		if(main==null)
		{
			nameField.setText("");
			fluidField.setText("");
			inputField.setText("");
			outputField.setText("");
			leakField.setText("");
			packField.setText("");
			schedOnField.setText("");
			schedOffField.setText("");
			return;
		}
		//Only reload when the selection actually changed, so a half-typed value is not wiped by
		//the twice-a-second sync.
		if(main.getId().equals(fieldsLoadedFor))
			return;
		fieldsLoadedFor = main.getId();
		nameField.setText(main.getName());
		fluidField.setText(main.getFluid()==null?"": main.getFluid());
		inputField.setText(Integer.toString(main.getPolicy().getMaxInput()));
		outputField.setText(Integer.toString(main.getPolicy().getMaxOutput()));
		leakField.setText(Integer.toString((int)Math.round(main.getPolicy().getLeakPct()*100)));
		packField.setText(Integer.toString(main.getPolicy().getPackCap()));
		schedOnField.setText(Integer.toString(main.getPolicy().getScheduleOn()));
		schedOffField.setText(Integer.toString(main.getPolicy().getScheduleOff()));
	}

	private void applyEditor()
	{
		FluidMain main = selectedMain();
		if(main==null)
			return;
		NBTTagCompound rename = new NBTTagCompound();
		rename.setString("name", nameField.getText());
		send(Op.RENAME_MAIN, selected, rename);

		String fluid = fluidField.getText().trim();
		if(!fluid.equals(main.getFluid()==null?"": main.getFluid()))
		{
			NBTTagCompound args = new NBTTagCompound();
			args.setString("fluid", fluid);
			send(Op.SET_FLUID, selected, args);
		}

		NBTTagCompound policy = new NBTTagCompound();
		policy.setInteger("maxInput", parseIntOr(inputField.getText(), main.getPolicy().getMaxInput()));
		policy.setInteger("maxOutput", parseIntOr(outputField.getText(), main.getPolicy().getMaxOutput()));
		policy.setDouble("leakPct", parseIntOr(leakField.getText(),
				(int)Math.round(main.getPolicy().getLeakPct()*100))/100.0);
		policy.setInteger("packCap", parseIntOr(packField.getText(), main.getPolicy().getPackCap()));
		//	=================================
		//	The schedule window.
		//	=================================
		//
		// Sent only when the two ends differ. Equal endpoints mean a window that never opens --
		// deliberate in FluidPolicy, because an always-open window is indistinguishable from no
		// schedule and would hide the typo -- so writing them through from a GUI would let somebody
		// close their own main by pressing Apply on two boxes that happened to match. The command
		// refuses the same pair outright; here the old window is simply kept.
		int on = parseIntOr(schedOnField.getText(), main.getPolicy().getScheduleOn());
		int off = parseIntOr(schedOffField.getText(), main.getPolicy().getScheduleOff());
		if(on!=off)
		{
			policy.setInteger("scheduleOn", on);
			policy.setInteger("scheduleOff", off);
		}
		send(Op.SET_POLICY, selected, policy);
		fieldsLoadedFor = null;
	}

	private static int parseIntOr(String text, int fallback)
	{
		return ConsoleFormat.parseIntOr(text, fallback);
	}

	//	=================================
	//		FITTINGS TAB
	//	=================================

	private void initFittingsTab()
	{
		rebuildDeviceView();
		this.buttonList.add(toggle(ID_FILTER, BODY_X, 24, 90, 14,
				showUnlinked?"Show: unlinked": "Show: this main", showUnlinked));

		List<String> rows = new ArrayList<>();
		for(FluidDevice device : deviceView)
			rows.add(describeDevice(device));
		if(rows.isEmpty())
			rows.add(showUnlinked?"(no unlinked fittings)": "(no fittings on this main)");
		this.buttonList.add(list(ID_DEVICE_LIST, BODY_X, 42, BODY_W, 81, rows));

		FluidDevice device = selectedDeviceRecord();
		if(device==null)
			return;
		if(device.getType()==FluidDeviceType.VALVE)
		{
			//Priority, cap and the critical flag all describe how fluid is apportioned, and a Valve
			//carries none. Give it its own two controls instead of dead ones.
			this.buttonList.add(toggle(ID_VALVE_MODE, BODY_X, 142, 118, 14,
					device.isValveOutput()?"Indicator: main -> RS": "Shut-off: RS -> main",
					device.isValveOutput()));
			this.buttonList.add(toggle(ID_VALVE_INVERT, BODY_X+122, 142, 68, 14,
					device.isValveInverted()?"Inverted": "Normal", device.isValveInverted()));
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

		FluidMain target = selectedMain();
		if(device.isLinked())
			this.buttonList.add(button(ID_DEV_LINK, BODY_X, 178, 90, 14, "Unlink"));
		else if(target!=null)
			//Name the destination on the button itself. Without it the only way to know where a
			//fitting is about to go is to remember what was picked on another tab.
			this.buttonList.add(button(ID_DEV_LINK, BODY_X, 178, 150, 14,
					"Link to "+fontRenderer.trimStringToWidth(target.getName(), 84)));
	}

	private void rebuildDeviceView()
	{
		deviceView.clear();
		if(showUnlinked)
			deviceView.addAll(net().getUnlinkedDevices());
		else
		{
			FluidMain main = selectedMain();
			if(main!=null)
				deviceView.addAll(main.getDevices());
		}
		//A stable order, so a fitting does not jump under the cursor between syncs.
		deviceView.sort(Comparator
				.comparingInt((FluidDevice d) -> d.getType().ordinal())
				.thenComparingInt(d -> d.getPos().getX())
				.thenComparingInt(d -> d.getPos().getY())
				.thenComparingInt(d -> d.getPos().getZ()));
	}

	@Nullable
	private FluidDevice selectedDeviceRecord()
	{
		return selectedDevice >= 0&&selectedDevice < deviceView.size()
				?deviceView.get(selectedDevice): null;
	}

	private String describeDevice(FluidDevice device)
	{
		StringBuilder out = new StringBuilder();
		out.append(device.isOnline()?"": "§8");
		out.append(device.getType().getName()).append("  ");
		out.append(device.getPos().getX()).append(",").append(device.getPos().getY())
				.append(",").append(device.getPos().getZ());
		if(device.getType().movesFluid())
			out.append("  ").append(device.getLastThroughput()).append(" mB/t");
		if(!device.isEnabled())
			out.append("  [off]");
		return out.toString();
	}

	//	=================================
	//		DRAWING
	//	=================================

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
	{
		drawRect(guiLeft, guiTop, guiLeft+WIDTH, guiTop+HEIGHT, COL_FRAME);
		drawRect(guiLeft+2, guiTop+2, guiLeft+WIDTH-2, guiTop+HEIGHT-2, COL_PANEL);
		drawRect(guiLeft+BODY_X-2, guiTop+BODY_Y-2, guiLeft+BODY_R, guiTop+BODY_B, COL_INSET);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
	{
		fontRenderer.drawString("FLUID CONTROL", 6, 8, 0xE0E0E0);
		if(!tile.isPowered())
			fontRenderer.drawString("NO POWER", WIDTH-fontRenderer.getStringWidth("NO POWER")-6, 8, COL_BAD);
		else if(CityMode.petroleum())
			fontRenderer.drawString("CITY MODE", WIDTH-fontRenderer.getStringWidth("CITY MODE")-6, 8, COL_WARN);

		if(!ClientFluidNetCache.isPopulated())
		{
			fontRenderer.drawString("waiting for the server...", BODY_X+4, BODY_Y+4, COL_DIM);
			return;
		}

		switch(tab)
		{
			case OVERVIEW:
				drawOverview();
				break;
			case MAINS:
				drawMains();
				break;
			case FITTINGS:
				drawFittings();
				break;
			case STATS:
				drawStats();
				break;
		}
	}

	private void drawOverview()
	{
		int y = BODY_Y+2;
		fontRenderer.drawString(net().getMainCount()+" main(s), "+net().getDeviceCount()+" fitting(s)",
				BODY_X+2, y, COL_TEXT);
		y += 12;
		fontRenderer.drawString("in "+net().getTotalIn()+" mB/t   out "+net().getTotalOut()+" mB/t",
				BODY_X+2, y, COL_DIM);
		y += 14;
		drawRect(guiLeft+BODY_X, guiTop+y-2, guiLeft+BODY_R-2, guiTop+y-1, COL_LINE);

		int shown = 0;
		for(UUID id : listOrder)
		{
			if(shown++ < overviewScroll)
				continue;
			if(y > BODY_B-14)
				break;
			FluidMain main = net().getMain(id);
			if(main==null)
				continue;
			drawRect(guiLeft+BODY_X+1, guiTop+y+1, guiLeft+BODY_X+4, guiTop+y+8,
					0xFF000000|main.getColor());
			fontRenderer.drawString(fontRenderer.trimStringToWidth(main.getName(), 70),
					BODY_X+8, y+1, COL_TEXT);
			fontRenderer.drawString(main.isTyped()?main.getFluid(): "untyped",
					BODY_X+82, y+1, main.isTyped()?COL_DIM: COL_WARN);
			String state = stateWord(main);
			fontRenderer.drawString(state, BODY_R-4-fontRenderer.getStringWidth(state), y+1,
					stateColour(main));
			y += 11;
		}
	}

	private static String stateWord(FluidMain main)
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

	private static int stateColour(FluidMain main)
	{
		if(main.isTripped())
			return COL_BAD;
		if(!main.isOperational())
			return COL_WARN;
		return COL_GOOD;
	}

	private void drawMains()
	{
		FluidMain main = selectedMain();
		loadFieldsFrom(main);
		if(main==null)
		{
			drawFields(nameField);
			fontRenderer.drawString("No main selected.", EDITOR_X+2, 40, COL_DIM);
			return;
		}

		fontRenderer.drawString("carries", EDITOR_X+2, 96, COL_DIM);
		fontRenderer.drawString("max in", EDITOR_X+2, 110, COL_DIM);
		fontRenderer.drawString("max out", EDITOR_X+2, 124, COL_DIM);
		fontRenderer.drawString("leak %", EDITOR_X+2, 138, COL_DIM);
		fontRenderer.drawString("pack", EDITOR_X+2, 152, COL_DIM);
		//Dimmed while the schedule is switched off, so the two boxes read as "not in use" rather
		//than as settings that are being ignored for no stated reason.
		boolean scheduled = main.getPolicy().isScheduleEnabled();
		fontRenderer.drawString("open/shut", EDITOR_X+2, 166, scheduled?COL_TEXT: COL_DIM);
		fontRenderer.drawString("-", EDITOR_X+79, 166, COL_DIM);

		//The five full-width rows, then the schedule row's two narrow boxes.
		for(int row = 0; row < 5; row++)
			drawRect(EDITOR_X+46, 95+row*14, BODY_R-2, 107+row*14, COL_INSET);
		drawRect(EDITOR_X+46, 165, EDITOR_X+78, 177, COL_INSET);
		drawRect(EDITOR_X+83, 165, BODY_R-2, 177, COL_INSET);

		drawFields(mainsFields());

		fontRenderer.drawString(stateWord(main), EDITOR_X+22, 44, stateColour(main));
		fontRenderer.drawString(main.getPack()+" / "+main.getPolicy().getPackCap()+" mB",
				EDITOR_X+22, 54, COL_DIM);
		//The one rule players trip over. Say it where the field is, not in a manual page.
		if(main.getPack() > 0)
			fontRenderer.drawString("drain to re-type", EDITOR_X+2, 106, COL_WARN);
	}

	private void drawFittings()
	{
		FluidDevice device = selectedDeviceRecord();
		if(device==null)
		{
			fontRenderer.drawString("Pick a fitting.", BODY_X, 142, COL_DIM);
			return;
		}
		String line = device.getDisplayName();
		fontRenderer.drawString(fontRenderer.trimStringToWidth(line, BODY_W), BODY_X, 128, COL_TEXT);
		if(device.getType().movesFluid())
			fontRenderer.drawString("cap "+device.getTransferCap()+" mB/t   priority "
					+device.getPriority()+"   meter "+ConsoleFormat.volume(device.getLifetimeThroughput()),
					BODY_X, 196, COL_DIM);
	}

	private void drawStats()
	{
		FluidMain main = selectedMain();
		if(main==null)
		{
			fontRenderer.drawString("No main selected.", BODY_X, BODY_Y+4, COL_DIM);
			return;
		}
		fontRenderer.drawString(main.getName(), BODY_X, BODY_Y+4, COL_TEXT);
		graph.draw(main.getStats().getHistoryIn(), main.getStats().getHistoryOut(), COL_IN, COL_OUT);
		fontRenderer.drawString("peak in  "+main.getStats().getPeakIn()+" mB/s", BODY_X, 118, COL_DIM);
		fontRenderer.drawString("peak out "+main.getStats().getPeakOut()+" mB/s", BODY_X, 130, COL_DIM);
		//Compacted, as the grid console's have always been. A main that has moved a few million
		//millibuckets wrote its total straight through the edge of the window before this.
		fontRenderer.drawString("lifetime in  "+ConsoleFormat.volume(main.getStats().getLifetimeIn()),
				BODY_X, 148, COL_DIM);
		fontRenderer.drawString("lifetime out "+ConsoleFormat.volume(main.getStats().getLifetimeOut()),
				BODY_X, 160, COL_DIM);
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
				initGui();
			}
			return;
		}
		if(button.id >= ID_SWATCH_BASE&&button.id < ID_SWATCH_BASE+FluidMain.PALETTE.length)
		{
			NBTTagCompound args = new NBTTagCompound();
			args.setInteger("color", FluidMain.PALETTE[button.id-ID_SWATCH_BASE]);
			send(Op.SET_COLOR, selected, args);
			initGui();
			return;
		}

		switch(button.id)
		{
			case ID_MAIN_LIST:
			{
				int index = ((GuiReactiveList)button).selectedOption;
				if(index >= 0&&index < listOrder.size())
				{
					selected = listOrder.get(index);
					fieldsLoadedFor = null;
					initGui();
				}
				break;
			}
			case ID_NEW:
			{
				NBTTagCompound args = new NBTTagCompound();
				args.setString("name", uniqueDefaultName());
				send(Op.CREATE_MAIN, null, args);
				break;
			}
			case ID_DELETE:
				if(selected!=null)
				{
					send(Op.DELETE_MAIN, selected, new NBTTagCompound());
					selected = null;
					fieldsLoadedFor = null;
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
				withMain(main -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("value", !main.isLocked());
					send(Op.SET_LOCKED, selected, args);
				});
				break;
			case ID_TOPUP:
				withMain(main -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("failoverTopUp", !main.getPolicy().isFailoverTopUp());
					send(Op.SET_POLICY, selected, args);
				});
				break;
			case ID_SCHEDULE:
				withMain(main -> {
					NBTTagCompound args = new NBTTagCompound();
					args.setBoolean("scheduleEnabled", !main.getPolicy().isScheduleEnabled());
					send(Op.SET_POLICY, selected, args);
				});
				break;
			case ID_CLEAR_FLUID:
			{
				NBTTagCompound args = new NBTTagCompound();
				args.setString("fluid", "");
				send(Op.SET_FLUID, selected, args);
				fieldsLoadedFor = null;
				break;
			}
			case ID_TRIP:
				send(Op.RESET_TRIP, selected, new NBTTagCompound());
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
				withDevice(device -> sendDeviceInt(device, "priority", device.getPriority()-1));
				break;
			case ID_PRIO_UP:
				withDevice(device -> sendDeviceInt(device, "priority", device.getPriority()+1));
				break;
			case ID_CAP_DOWN:
				withDevice(device -> sendDeviceInt(device, "transferCap",
						Math.max(0, device.getTransferCap()/2)));
				break;
			case ID_CAP_UP:
				withDevice(device -> sendDeviceInt(device, "transferCap",
						Math.max(1, device.getTransferCap()*2)));
				break;
			case ID_CRITICAL:
				withDevice(device -> sendDeviceFlag(device, "critical", !device.isCritical()));
				break;
			case ID_VALVE_MODE:
				withDevice(device -> sendDeviceFlag(device, "valveOutput", !device.isValveOutput()));
				break;
			case ID_VALVE_INVERT:
				withDevice(device -> sendDeviceFlag(device, "valveInverted", !device.isValveInverted()));
				break;
			case ID_CHUNKLOAD:
				withDevice(device -> sendDeviceFlag(device, "chunkLoad", !device.isChunkLoadRequested()));
				break;
			case ID_DEV_ENABLED:
				withDevice(device -> sendDeviceFlag(device, "enabled", !device.isEnabled()));
				break;
			case ID_DEV_LINK:
				withDevice(device -> {
					NBTTagCompound args = deviceArgs(device);
					boolean unlink = device.isLinked();
					args.setBoolean("unlink", unlink);
					send(Op.ASSIGN_DEVICE, unlink?null: selected, args);
				});
				initGui();
				break;
			case ID_RESET_METERS:
				send(Op.RESET_METER, selected, new NBTTagCompound());
				break;
			default:
				break;
		}
	}

	private void withMain(Consumer<FluidMain> action)
	{
		FluidMain main = selectedMain();
		if(main!=null)
			action.accept(main);
		initGui();
	}

	private void withDevice(Consumer<FluidDevice> action)
	{
		FluidDevice device = selectedDeviceRecord();
		if(device!=null)
			action.accept(device);
		initGui();
	}

	private NBTTagCompound deviceArgs(FluidDevice device)
	{
		NBTTagCompound args = new NBTTagCompound();
		args.setInteger("x", device.getPos().getX());
		args.setInteger("y", device.getPos().getY());
		args.setInteger("z", device.getPos().getZ());
		args.setInteger("dim", device.getPos().dimension);
		return args;
	}

	private void sendDeviceInt(FluidDevice device, String key, int value)
	{
		NBTTagCompound args = deviceArgs(device);
		args.setInteger(key, value);
		send(Op.SET_DEVICE, selected, args);
	}

	private void sendDeviceFlag(FluidDevice device, String key, boolean value)
	{
		NBTTagCompound args = deviceArgs(device);
		args.setBoolean(key, value);
		send(Op.SET_DEVICE, selected, args);
	}

	private String uniqueDefaultName()
	{
		for(int i = 1; i < 1000; i++)
		{
			String candidate = "Main "+i;
			if(net().getMainByName(candidate)==null)
				return candidate;
		}
		return "Main";
	}

	private void send(Op op, @Nullable UUID main, NBTTagCompound args)
	{
		ImmersiveEngineering.packetHandler.sendToServer(new MessageFluidNetAction(op, main, args));
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException
	{
		if(tab==Tab.MAINS)
			for(GuiTextField field : mainsFields())
				if(field!=null&&field.isFocused())
				{
					//Escape still has to close the window, or a focused field would trap the player.
					if(keyCode==Keyboard.KEY_ESCAPE)
						break;
					if(keyCode==Keyboard.KEY_RETURN)
					{
						applyEditor();
						return;
					}
					field.textboxKeyTyped(typedChar, keyCode);
					return;
				}
		super.keyTyped(typedChar, keyCode);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
	{
		if(tab==Tab.MAINS)
			for(GuiTextField field : mainsFields())
				if(field!=null)
					field.mouseClicked(mouseX, mouseY, mouseButton);
		super.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public void handleMouseInput() throws IOException
	{
		super.handleMouseInput();
		if(tab!=Tab.OVERVIEW)
			return;
		int scroll = org.lwjgl.input.Mouse.getEventDWheel();
		if(scroll!=0)
			overviewScroll = Math.max(0, Math.min(Math.max(0, listOrder.size()-14),
					overviewScroll+(scroll > 0?-1: 1)));
	}

	/**
	 * A flat colour chip used for the main palette.
	 * <p>
	 * A private copy of the grid console's rather than a shared widget: it is fifteen lines, and
	 * hoisting it into {@code client.gui.elements} would be the first step towards the two consoles
	 * sharing a base class -- which is exactly the coupling the parallel-network decision was taken
	 * to avoid.
	 */
	private static class GuiColourSwatch extends GuiButton
	{
		private final int colour;
		/** Not final: the periodic refresh re-reads which swatch is the selected main's colour. */
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
