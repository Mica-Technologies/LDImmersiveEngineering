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

import static blusunrize.immersiveengineering.common.blocks.stone.BlockTypes_StoneDecoration.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_StoneDecoration}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesStoneDecorationTest
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
		assertEquals(11, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, COKEBRICK.getMeta(), "COKEBRICK moved"),
				() -> assertEquals(1, BLASTBRICK.getMeta(), "BLASTBRICK moved"),
				() -> assertEquals(2, BLASTBRICK_REINFORCED.getMeta(), "BLASTBRICK_REINFORCED moved"),
				() -> assertEquals(3, COKE.getMeta(), "COKE moved"),
				() -> assertEquals(4, HEMPCRETE.getMeta(), "HEMPCRETE moved"),
				() -> assertEquals(5, CONCRETE.getMeta(), "CONCRETE moved"),
				() -> assertEquals(6, CONCRETE_TILE.getMeta(), "CONCRETE_TILE moved"),
				() -> assertEquals(7, CONCRETE_LEADED.getMeta(), "CONCRETE_LEADED moved"),
				() -> assertEquals(8, INSULATING_GLASS.getMeta(), "INSULATING_GLASS moved"),
				() -> assertEquals(9, CONCRETE_SPRAYED.getMeta(), "CONCRETE_SPRAYED moved"),
				() -> assertEquals(10, ALLOYBRICK.getMeta(), "ALLOYBRICK moved")
		);
	}

	@Test
	@DisplayName("every variant is listed in the creative tab")
	void allListedForCreative()
	{
		BlockEnumTestSupport.assertAllListedForCreative(values());
	}
}
