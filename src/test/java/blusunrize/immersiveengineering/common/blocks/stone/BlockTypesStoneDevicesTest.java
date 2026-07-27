/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.stone;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.stone.BlockTypes_StoneDevices.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_StoneDevices}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesStoneDevicesTest
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
	@DisplayName("the constant count is frozen")
	void constantCountIsFrozen()
	{
		assertEquals(8, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, COKE_OVEN.getMeta(), "COKE_OVEN moved"),
				() -> assertEquals(1, BLAST_FURNACE.getMeta(), "BLAST_FURNACE moved"),
				() -> assertEquals(2, BLAST_FURNACE_ADVANCED.getMeta(), "BLAST_FURNACE_ADVANCED moved"),
				() -> assertEquals(3, CONCRETE_SHEET.getMeta(), "CONCRETE_SHEET moved"),
				() -> assertEquals(4, CONCRETE_QUARTER.getMeta(), "CONCRETE_QUARTER moved"),
				() -> assertEquals(5, CONCRETE_THREEQUARTER.getMeta(), "CONCRETE_THREEQUARTER moved"),
				() -> assertEquals(6, CORESAMPLE.getMeta(), "CORESAMPLE moved"),
				() -> assertEquals(7, ALLOY_SMELTER.getMeta(), "ALLOY_SMELTER moved")
		);
	}

	@Test
	@DisplayName("only the three concrete slab variants are listed in the creative tab")
	void onlyConcreteSlabsAreListed()
	{
		BlockEnumTestSupport.assertHiddenFromCreative(values(), COKE_OVEN, BLAST_FURNACE,
				BLAST_FURNACE_ADVANCED, CORESAMPLE, ALLOY_SMELTER);
	}

	@Test
	@DisplayName("listForCreative() is keyed to the hard-coded ordinal window 3..5")
	void hardCodedCreativeWindowStillCoversTheConcreteSlabs()
	{
		// BlockTypes_StoneDevices#listForCreative() is "ordinal() > 2 && ordinal() < 6" rather than
		// a comparison against the constants, so this pins the window to what it is meant to cover.
		assertEquals(3, CONCRETE_SHEET.ordinal());
		assertEquals(4, CONCRETE_QUARTER.ordinal());
		assertEquals(5, CONCRETE_THREEQUARTER.ordinal());
	}
}
