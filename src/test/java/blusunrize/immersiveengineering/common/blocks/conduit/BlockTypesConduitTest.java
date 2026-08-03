/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.conduit.BlockTypes_Conduit.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_Conduit}.
 * <p>
 * The ordinal of each constant is the block metadata written into every chunk holding one, so
 * inserting, removing or reordering a constant silently remaps every already-placed conduit in
 * every existing save. Any such edit must break a test rather than a player's world.
 */
class BlockTypesConduitTest
{
	@Test
	@DisplayName("getMeta() is the ordinal, unique and contiguous from zero")
	void metaIsTheOrdinal()
	{
		BlockEnumTestSupport.assertMetaIsOrdinal(values());
	}

	@Test
	@DisplayName("getName() is the lowercased constant name and unique")
	void namesAreSerializable()
	{
		BlockEnumTestSupport.assertNamesAreSerializable(values());
	}

	@Test
	@DisplayName("every variant still addresses a legal block metadata value")
	void fitsInBlockMetadata()
	{
		BlockEnumTestSupport.assertFitsInBlockMetadata(values());
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, CONDUIT_RUN.getMeta(), "CONDUIT_RUN moved"),
				() -> assertEquals(1, JUNCTION_BOX.getMeta(), "JUNCTION_BOX moved"),
				() -> assertEquals(2, GROUND_FEEDER.getMeta(), "GROUND_FEEDER moved")
		);
	}

	@Test
	@DisplayName("all three are craftable and shown in the creative list")
	void allListedForCreative()
	{
		//Unlike the multiblocks, every conduit piece is a block you place by hand.
		for(BlockTypes_Conduit type : values())
			assertTrue(type.listForCreative(), type.name()+" vanished from the creative list");
	}

	@Test
	@DisplayName("there is room for more conduit hardware")
	void hasHeadroom()
	{
		assertTrue(BlockEnumTestSupport.headroom(values()) > 0,
				"this enum is full; a new conduit piece needs its own block");
	}
}
