/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.util.network.MessageGridAction.Op;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Op} is a wire format: it is written to the packet buffer as a single byte ordinal.
 * Reordering or inserting a constant makes a client and server built from different commits
 * silently perform the wrong operation on each other's grids, which is far worse than a
 * clean failure -- so the ordinals are frozen here by name.
 */
class MessageGridActionTest
{
	@Nested
	@DisplayName("wire format")
	class WireFormat
	{
		@Test
		@DisplayName("every operation's ordinal is frozen")
		void ordinalsAreFrozen()
		{
			assertEquals(0, Op.CREATE_SEGMENT.ordinal());
			assertEquals(1, Op.DELETE_SEGMENT.ordinal());
			assertEquals(2, Op.RENAME_SEGMENT.ordinal());
			assertEquals(3, Op.SET_COLOR.ordinal());
			assertEquals(4, Op.SET_ENABLED.ordinal());
			assertEquals(5, Op.SET_LOCKED.ordinal());
			assertEquals(6, Op.SET_POLICY.ordinal());
			assertEquals(7, Op.RESET_BREAKER.ordinal());
			assertEquals(8, Op.RESET_METER.ordinal());
			assertEquals(9, Op.ASSIGN_DEVICE.ordinal());
			assertEquals(10, Op.SET_DEVICE.ordinal());
			assertEquals(11, Op.ADD_FAILOVER.ordinal());
			assertEquals(12, Op.REMOVE_FAILOVER.ordinal());
			assertEquals(13, Op.MOVE_FAILOVER.ordinal());
		}

		@Test
		@DisplayName("the operation count is frozen")
		void countIsFrozen()
		{
			assertEquals(14, Op.values().length,
					"a new operation must be appended, never inserted");
		}

		@Test
		@DisplayName("every ordinal fits in the single byte the packet writes")
		void ordinalsFitInAByte()
		{
			for(Op op : Op.values())
				assertTrue(op.ordinal() <= Byte.MAX_VALUE,
						op+" cannot be encoded in one byte");
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
	}

	@Nested
	@DisplayName("GUI ids")
	class GuiIds
	{
		@Test
		@DisplayName("the grid windows have distinct tile GUI ids")
		void gridGuiIdsAreDistinct()
		{
			assertNotEquals(Lib.GUIID_GridConsole, Lib.GUIID_GridDevice);
		}

		@Test
		@DisplayName("both stay inside the tile-GUI range")
		void gridGuiIdsAreTileIds()
		{
			//At or above GUIID_Base_Item the proxies reinterpret the id as an equipment
			//slot times 100, so a tile id must stay below it.
			assertTrue(Lib.GUIID_GridConsole < Lib.GUIID_Base_Item);
			assertTrue(Lib.GUIID_GridDevice < Lib.GUIID_Base_Item);
			assertTrue(Lib.GUIID_GridConsole >= Lib.GUIID_Base_Tile);
			assertTrue(Lib.GUIID_GridDevice >= Lib.GUIID_Base_Tile);
		}
	}
}
