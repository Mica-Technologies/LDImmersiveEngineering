/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsIE.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalsIE}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalsIETest
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
		assertEquals(9, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, COPPER.getMeta(), "COPPER moved"),
				() -> assertEquals(1, ALUMINUM.getMeta(), "ALUMINUM moved"),
				() -> assertEquals(2, LEAD.getMeta(), "LEAD moved"),
				() -> assertEquals(3, SILVER.getMeta(), "SILVER moved"),
				() -> assertEquals(4, NICKEL.getMeta(), "NICKEL moved"),
				() -> assertEquals(5, URANIUM.getMeta(), "URANIUM moved"),
				() -> assertEquals(6, CONSTANTAN.getMeta(), "CONSTANTAN moved"),
				() -> assertEquals(7, ELECTRUM.getMeta(), "ELECTRUM moved"),
				() -> assertEquals(8, STEEL.getMeta(), "STEEL moved")
		);
	}

	@Test
	@DisplayName("every variant is listed in the creative tab")
	void allListedForCreative()
	{
		BlockEnumTestSupport.assertAllListedForCreative(values());
	}
}
