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

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration2.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalDecoration2}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalDecoration2Test
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
				() -> assertEquals(0, STEEL_POST.getMeta(), "STEEL_POST moved"),
				() -> assertEquals(1, STEEL_WALLMOUNT.getMeta(), "STEEL_WALLMOUNT moved"),
				() -> assertEquals(2, ALUMINUM_POST.getMeta(), "ALUMINUM_POST moved"),
				() -> assertEquals(3, ALUMINUM_WALLMOUNT.getMeta(), "ALUMINUM_WALLMOUNT moved"),
				() -> assertEquals(4, LANTERN.getMeta(), "LANTERN moved"),
				() -> assertEquals(5, RAZOR_WIRE.getMeta(), "RAZOR_WIRE moved"),
				() -> assertEquals(6, TOOLBOX.getMeta(), "TOOLBOX moved"),
				() -> assertEquals(7, STEEL_SLOPE.getMeta(), "STEEL_SLOPE moved"),
				() -> assertEquals(8, ALU_SLOPE.getMeta(), "ALU_SLOPE moved")
		);
	}

	@Test
	@DisplayName("only the toolbox is hidden from the creative tab")
	void onlyToolboxIsHidden()
	{
		BlockEnumTestSupport.assertHiddenFromCreative(values(), TOOLBOX);
	}
}
