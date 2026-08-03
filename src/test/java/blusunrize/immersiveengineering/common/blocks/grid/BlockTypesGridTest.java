/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guards for the virtual grid's two block enums.
 * <p>
 * These had none, which is how the Substation's removal came to need checking by hand: the only
 * thing standing between "drop a constant" and "every placed Signal Unit becomes something else" is
 * whether the constant being dropped happens to be the last one. That is a fact about the enum, so
 * it belongs in a test rather than in somebody's head.
 */
class BlockTypesGridTest
{
	@Nested
	@DisplayName("grid_device")
	class Devices
	{
		@Test
		@DisplayName("getMeta() is the ordinal, unique and contiguous from zero")
		void metaIsTheOrdinal()
		{
			BlockEnumTestSupport.assertMetaIsOrdinal(BlockTypes_GridDevice.values());
		}

		@Test
		@DisplayName("getName() is the lowercased constant name and unique")
		void namesAreSerializable()
		{
			BlockEnumTestSupport.assertNamesAreSerializable(BlockTypes_GridDevice.values());
		}

		@Test
		@DisplayName("every variant still addresses a legal block metadata value")
		void fitsInBlockMetadata()
		{
			BlockEnumTestSupport.assertFitsInBlockMetadata(BlockTypes_GridDevice.values());
		}

		@Test
		@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
		void ordinalsAreFrozen()
		{
			//SUBSTATION_FRAME sat at 4 and was removed on 2026-08-03. It was the last constant, so
			//nothing below it moved -- which is the only reason that removal was safe to make.
			assertAll(
					() -> assertEquals(0, BlockTypes_GridDevice.FEED_UNIT.getMeta(), "FEED_UNIT moved"),
					() -> assertEquals(1, BlockTypes_GridDevice.SERVICE_UNIT.getMeta(), "SERVICE_UNIT moved"),
					() -> assertEquals(2, BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta(), "CONSOLE_HOUSING moved"),
					() -> assertEquals(3, BlockTypes_GridDevice.SIGNAL_UNIT.getMeta(), "SIGNAL_UNIT moved")
			);
		}
	}

	@Nested
	@DisplayName("grid_multiblock")
	class Multiblocks
	{
		@Test
		@DisplayName("getMeta() is the ordinal, unique and contiguous from zero")
		void metaIsTheOrdinal()
		{
			BlockEnumTestSupport.assertMetaIsOrdinal(BlockTypes_GridMultiblock.values());
		}

		@Test
		@DisplayName("getName() is the lowercased constant name and unique")
		void namesAreSerializable()
		{
			BlockEnumTestSupport.assertNamesAreSerializable(BlockTypes_GridMultiblock.values());
		}

		@Test
		@DisplayName("every variant still addresses a legal block metadata value")
		void fitsInBlockMetadata()
		{
			BlockEnumTestSupport.assertFitsInBlockMetadata(BlockTypes_GridMultiblock.values());
		}

		@Test
		@DisplayName("the console stays at meta zero")
		void consoleOrdinalIsFrozen()
		{
			//This block exists at all because metal_multiblock was exactly full at sixteen. It is
			//the worked example the ceiling message points at, so its own numbering is worth pinning.
			assertEquals(0, BlockTypes_GridMultiblock.GRID_CONSOLE.getMeta(), "GRID_CONSOLE moved");
		}

		@Test
		@DisplayName("no grid multiblock is listed for creative -- they are hammered together")
		void noneListedForCreative()
		{
			BlockEnumTestSupport.assertNoneListedForCreative(BlockTypes_GridMultiblock.values());
		}
	}
}
