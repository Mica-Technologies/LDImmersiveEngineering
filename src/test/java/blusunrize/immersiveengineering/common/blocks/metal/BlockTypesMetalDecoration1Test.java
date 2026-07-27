/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalDecoration1}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalDecoration1Test
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
				() -> assertEquals(0, STEEL_FENCE.getMeta(), "STEEL_FENCE moved"),
				() -> assertEquals(1, STEEL_SCAFFOLDING_0.getMeta(), "STEEL_SCAFFOLDING_0 moved"),
				() -> assertEquals(2, STEEL_SCAFFOLDING_1.getMeta(), "STEEL_SCAFFOLDING_1 moved"),
				() -> assertEquals(3, STEEL_SCAFFOLDING_2.getMeta(), "STEEL_SCAFFOLDING_2 moved"),
				() -> assertEquals(4, ALUMINUM_FENCE.getMeta(), "ALUMINUM_FENCE moved"),
				() -> assertEquals(5, ALUMINUM_SCAFFOLDING_0.getMeta(), "ALUMINUM_SCAFFOLDING_0 moved"),
				() -> assertEquals(6, ALUMINUM_SCAFFOLDING_1.getMeta(), "ALUMINUM_SCAFFOLDING_1 moved"),
				() -> assertEquals(7, ALUMINUM_SCAFFOLDING_2.getMeta(), "ALUMINUM_SCAFFOLDING_2 moved")
		);
	}

	@Test
	@DisplayName("every variant is listed in the creative tab")
	void allListedForCreative()
	{
		BlockEnumTestSupport.assertAllListedForCreative(values());
	}

	@Test
	@DisplayName("exactly the scaffolding variants report isScaffold()")
	void scaffoldFlagMatchesTheName()
	{
		for(BlockTypes_MetalDecoration1 type : values())
			assertEquals(type.getName().contains("scaffolding"), type.isScaffold(),
					"isScaffold() disagrees with the name of "+type.name());
	}

	@Test
	@DisplayName("both metals expose a fence and three scaffolding variants")
	void bothMetalsHaveTheSameLayout()
	{
		assertFalse(STEEL_FENCE.isScaffold());
		assertFalse(ALUMINUM_FENCE.isScaffold());
		assertTrue(STEEL_SCAFFOLDING_0.isScaffold());
		assertTrue(STEEL_SCAFFOLDING_1.isScaffold());
		assertTrue(STEEL_SCAFFOLDING_2.isScaffold());
		assertTrue(ALUMINUM_SCAFFOLDING_0.isScaffold());
		assertTrue(ALUMINUM_SCAFFOLDING_1.isScaffold());
		assertTrue(ALUMINUM_SCAFFOLDING_2.isScaffold());
		// the aluminum half is exactly four metas after the steel half
		assertEquals(STEEL_FENCE.getMeta()+4, ALUMINUM_FENCE.getMeta());
		assertEquals(STEEL_SCAFFOLDING_0.getMeta()+4, ALUMINUM_SCAFFOLDING_0.getMeta());
	}
}
