/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetAction.Op;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid network's action packet, guarded exactly as the grid's twin is.
 * <p>
 * {@code api/fluid/network} is a deliberate copy of {@code api/energy/grid} -- fix one, check the
 * other -- and that rule applies to the guards as much as to the code. The grid's {@code Op} had
 * its ordinals frozen by {@code MessageGridActionTest} from the day it was written; the fluid
 * mirror had nothing, so the one half of the pair could be reordered silently.
 * <p>
 * {@link Op} is a wire format: it goes into the packet buffer as a single byte ordinal. Reordering
 * or inserting a constant makes a client and server built from different commits perform the wrong
 * operation on each other's mains -- deleting one where they meant to rename it -- which is far
 * worse than a clean failure.
 */
class MessageFluidNetActionTest
{
	@Nested
	@DisplayName("wire format")
	class WireFormat
	{
		@Test
		@DisplayName("every operation's ordinal is frozen")
		void ordinalsAreFrozen()
		{
			assertEquals(0, Op.CREATE_MAIN.ordinal());
			assertEquals(1, Op.DELETE_MAIN.ordinal());
			assertEquals(2, Op.RENAME_MAIN.ordinal());
			assertEquals(3, Op.SET_COLOR.ordinal());
			assertEquals(4, Op.SET_ENABLED.ordinal());
			assertEquals(5, Op.SET_LOCKED.ordinal());
			assertEquals(6, Op.SET_POLICY.ordinal());
			assertEquals(7, Op.SET_FLUID.ordinal());
			assertEquals(8, Op.RESET_TRIP.ordinal());
			assertEquals(9, Op.RESET_METER.ordinal());
			assertEquals(10, Op.ASSIGN_DEVICE.ordinal());
			assertEquals(11, Op.SET_DEVICE.ordinal());
			assertEquals(12, Op.ADD_FAILOVER.ordinal());
			assertEquals(13, Op.REMOVE_FAILOVER.ordinal());
			assertEquals(14, Op.MOVE_FAILOVER.ordinal());
		}

		@Test
		@DisplayName("the operation count is frozen")
		void countIsFrozen()
		{
			assertEquals(15, Op.values().length,
					"a new operation must be appended, never inserted");
		}

		@Test
		@DisplayName("every ordinal fits in the single byte the packet writes")
		void ordinalsFitInAByte()
		{
			for(Op op : Op.values())
				assertTrue(op.ordinal() <= Byte.MAX_VALUE, op+" cannot be encoded in one byte");
		}

		@Test
		@DisplayName("ordinals are unique and contiguous")
		void ordinalsAreContiguous()
		{
			Set<Integer> seen = new HashSet<>();
			for(Op op : Op.values())
				assertTrue(seen.add(op.ordinal()), "duplicate ordinal for "+op);
			for(int i = 0; i < Op.values().length; i++)
				assertTrue(seen.contains(i), "missing ordinal "+i);
		}

		@Test
		@DisplayName("the fluid network carries one operation the grid does not: SET_FLUID")
		void mirrorsTheGridPlusItsOwnOperation()
		{
			//The two enums are deliberate mirrors, and the difference is deliberate too: a main
			//carries one named fluid where a segment carries undifferentiated flux. Asserting the
			//count relationship is what makes an accidental divergence in either visible.
			assertEquals(MessageGridAction.Op.values().length+1, Op.values().length,
					"the fluid mirror should differ from the grid by exactly SET_FLUID");
		}
	}

	@Nested
	@DisplayName("GUI ids")
	class GuiIds
	{
		@Test
		@DisplayName("every GUI id in the mod is unique")
		void allGuiIdsAreDistinct()
		{
			//	=================================
			//	This caught a real collision.
			//	=================================
			//
			// GUIID_NetworkTerminal was added at GUIID_Base_Item+3, which the Maintenance Kit already
			// held. Both windows answered to id 67 and only the instanceof guard beside each one in
			// CommonProxy.getServerGuiElement kept them apart -- so the ids were not doing the job
			// they exist to do, and the next item GUI written against "ids are unique" would have
			// opened somebody else's window.
			int[] ids = {
					Lib.GUIID_CokeOven, Lib.GUIID_AlloySmelter, Lib.GUIID_BlastFurnace,
					Lib.GUIID_WoodenCrate, Lib.GUIID_Workbench, Lib.GUIID_Assembler,
					Lib.GUIID_Sorter, Lib.GUIID_Squeezer, Lib.GUIID_Fermenter, Lib.GUIID_Refinery,
					Lib.GUIID_ArcFurnace, Lib.GUIID_AutoWorkbench, Lib.GUIID_Mixer,
					Lib.GUIID_Turret, Lib.GUIID_FluidSorter, Lib.GUIID_Belljar,
					Lib.GUIID_ToolboxBlock, Lib.GUIID_GridConsole, Lib.GUIID_GridDevice,
					Lib.GUIID_FluidConsole, Lib.GUIID_GasPump,
					Lib.GUIID_Manual, Lib.GUIID_Revolver, Lib.GUIID_Toolbox,
					Lib.GUIID_NetworkTerminal, Lib.GUIID_MaintenanceKit,
			};
			Set<Integer> seen = new HashSet<>();
			for(int id : ids)
				assertTrue(seen.add(id), "two GUIs share id "+id);
		}

		@Test
		@DisplayName("the fluid console's id is distinct from the grid console's")
		void fluidAndGridConsolesDiffer()
		{
			//The two windows look alike and are built from the same base; sharing an id would open
			//whichever the proxy happened to test first.
			assertNotEquals(Lib.GUIID_FluidConsole, Lib.GUIID_GridConsole);
			assertNotEquals(Lib.GUIID_FluidConsole, Lib.GUIID_GridDevice);
		}

		@Test
		@DisplayName("every tile GUI id stays below the item range")
		void tileIdsStayBelowTheItemRange()
		{
			//At or above GUIID_Base_Item the proxies reinterpret the id as an equipment slot times
			//100, so a tile id that crossed the line would be decoded as a slot and a different id.
			assertTrue(Lib.GUIID_FluidConsole < Lib.GUIID_Base_Item, "fluid console");
			assertTrue(Lib.GUIID_GasPump < Lib.GUIID_Base_Item, "gas pump");
			assertTrue(Lib.GUIID_GridConsole < Lib.GUIID_Base_Item, "grid console");
			assertTrue(Lib.GUIID_GridDevice < Lib.GUIID_Base_Item, "grid device");
		}

		@Test
		@DisplayName("every item GUI id survives the slot encoding it is packed into")
		void itemIdsSurviveSlotEncoding()
		{
			//openGui sends 100*slot + id, and the receiver recovers the slot with /100 and the id
			//with %100. An item id of 100 or more would carry into the slot and open the window
			//against the wrong equipment slot.
			int[] itemIds = {Lib.GUIID_Manual, Lib.GUIID_Revolver, Lib.GUIID_Toolbox,
					Lib.GUIID_NetworkTerminal, Lib.GUIID_MaintenanceKit};
			for(int id : itemIds)
			{
				assertTrue(id >= Lib.GUIID_Base_Item, "id "+id+" would be decoded as a tile GUI");
				assertTrue(id < 100, "id "+id+" would carry into the equipment slot");
			}
		}
	}
}
