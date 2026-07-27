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

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDevice0.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalDevice0}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalDevice0Test
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
		assertEquals(7, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, CAPACITOR_LV.getMeta(), "CAPACITOR_LV moved"),
				() -> assertEquals(1, CAPACITOR_MV.getMeta(), "CAPACITOR_MV moved"),
				() -> assertEquals(2, CAPACITOR_HV.getMeta(), "CAPACITOR_HV moved"),
				() -> assertEquals(3, CAPACITOR_CREATIVE.getMeta(), "CAPACITOR_CREATIVE moved"),
				() -> assertEquals(4, BARREL.getMeta(), "BARREL moved"),
				() -> assertEquals(5, FLUID_PUMP.getMeta(), "FLUID_PUMP moved"),
				() -> assertEquals(6, FLUID_PLACER.getMeta(), "FLUID_PLACER moved")
		);
	}

	@Test
	@DisplayName("every variant is listed in the creative tab")
	void allListedForCreative()
	{
		BlockEnumTestSupport.assertAllListedForCreative(values());
	}
}
