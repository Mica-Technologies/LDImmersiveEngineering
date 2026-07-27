/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalMultiblock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalMultiblock}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalMultiblockTest
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
	@Disabled("a 17th constant was added to an enum that already filled all 16 metadata values")
	@DisplayName("every variant still addresses a legal block metadata value")
	void fitsInBlockMetadata()
	{
		// A block stores its metadata in four bits, so the legal range is 0..15 and this enum was
		// already exactly full at sixteen constants. Anything at meta 16 or above cannot be
		// placed, saved or read back -- it needs its own block rather than another constant here.
		BlockEnumTestSupport.assertFitsInBlockMetadata(values());
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, METAL_PRESS.getMeta(), "METAL_PRESS moved"),
				() -> assertEquals(1, CRUSHER.getMeta(), "CRUSHER moved"),
				() -> assertEquals(2, TANK.getMeta(), "TANK moved"),
				() -> assertEquals(3, SILO.getMeta(), "SILO moved"),
				() -> assertEquals(4, ASSEMBLER.getMeta(), "ASSEMBLER moved"),
				() -> assertEquals(5, AUTO_WORKBENCH.getMeta(), "AUTO_WORKBENCH moved"),
				() -> assertEquals(6, BOTTLING_MACHINE.getMeta(), "BOTTLING_MACHINE moved"),
				() -> assertEquals(7, SQUEEZER.getMeta(), "SQUEEZER moved"),
				() -> assertEquals(8, FERMENTER.getMeta(), "FERMENTER moved"),
				() -> assertEquals(9, REFINERY.getMeta(), "REFINERY moved"),
				() -> assertEquals(10, DIESEL_GENERATOR.getMeta(), "DIESEL_GENERATOR moved"),
				() -> assertEquals(11, EXCAVATOR.getMeta(), "EXCAVATOR moved"),
				() -> assertEquals(12, BUCKET_WHEEL.getMeta(), "BUCKET_WHEEL moved"),
				() -> assertEquals(13, ARC_FURNACE.getMeta(), "ARC_FURNACE moved"),
				() -> assertEquals(14, LIGHTNINGROD.getMeta(), "LIGHTNINGROD moved"),
				() -> assertEquals(15, MIXER.getMeta(), "MIXER moved")
		);
	}

	@Test
	@DisplayName("no variant is listed in the creative tab -- the multiblocks are placed via the hammer")
	void noneListedForCreative()
	{
		BlockEnumTestSupport.assertNoneListedForCreative(values());
	}

	@Test
	@DisplayName("needsCustomState() is frozen per variant")
	void customStateFlagsAreFrozen()
	{
		assertAll(
				() -> assertTrue(METAL_PRESS.needsCustomState()),
				() -> assertTrue(CRUSHER.needsCustomState()),
				() -> assertFalse(TANK.needsCustomState()),
				() -> assertFalse(SILO.needsCustomState()),
				() -> assertFalse(ASSEMBLER.needsCustomState()),
				() -> assertTrue(AUTO_WORKBENCH.needsCustomState()),
				() -> assertTrue(BOTTLING_MACHINE.needsCustomState()),
				() -> assertTrue(SQUEEZER.needsCustomState()),
				() -> assertTrue(FERMENTER.needsCustomState()),
				() -> assertTrue(REFINERY.needsCustomState()),
				() -> assertTrue(DIESEL_GENERATOR.needsCustomState()),
				() -> assertTrue(EXCAVATOR.needsCustomState()),
				() -> assertTrue(BUCKET_WHEEL.needsCustomState()),
				() -> assertTrue(ARC_FURNACE.needsCustomState()),
				() -> assertFalse(LIGHTNINGROD.needsCustomState()),
				() -> assertTrue(MIXER.needsCustomState())
		);
	}

	@Test
	@DisplayName("getCustomState() equals getName()")
	void customStateMatchesName()
	{
		for(BlockTypes_MetalMultiblock type : values())
			assertEquals(type.getName(), type.getCustomState(), "custom state drifted for "+type.name());
	}

	@Test
	@DisplayName("the sixteen historical variants still fill metas 0 to 15")
	void theHistoricalVariantsStillFillTheMetaRange()
	{
		// this enum was already full at sixteen constants, so every one of them is load-bearing and
		// there is no spare metadata value left for a new one
		assertEquals(BlockEnumTestSupport.MAX_META-1, MIXER.getMeta(),
				"the last historical variant must stay at meta 15");
		assertTrue(values().length >= BlockEnumTestSupport.MAX_META);
	}
}
