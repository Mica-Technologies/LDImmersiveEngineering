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

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDevice1.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_MetalDevice1}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesMetalDevice1Test
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
		assertEquals(14, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, BLAST_FURNACE_PREHEATER.getMeta(), "BLAST_FURNACE_PREHEATER moved"),
				() -> assertEquals(1, FURNACE_HEATER.getMeta(), "FURNACE_HEATER moved"),
				() -> assertEquals(2, DYNAMO.getMeta(), "DYNAMO moved"),
				() -> assertEquals(3, THERMOELECTRIC_GEN.getMeta(), "THERMOELECTRIC_GEN moved"),
				() -> assertEquals(4, ELECTRIC_LANTERN.getMeta(), "ELECTRIC_LANTERN moved"),
				() -> assertEquals(5, CHARGING_STATION.getMeta(), "CHARGING_STATION moved"),
				() -> assertEquals(6, FLUID_PIPE.getMeta(), "FLUID_PIPE moved"),
				() -> assertEquals(7, SAMPLE_DRILL.getMeta(), "SAMPLE_DRILL moved"),
				() -> assertEquals(8, TESLA_COIL.getMeta(), "TESLA_COIL moved"),
				() -> assertEquals(9, FLOODLIGHT.getMeta(), "FLOODLIGHT moved"),
				() -> assertEquals(10, TURRET_CHEM.getMeta(), "TURRET_CHEM moved"),
				() -> assertEquals(11, TURRET_GUN.getMeta(), "TURRET_GUN moved"),
				() -> assertEquals(12, TURRET_RAIL.getMeta(), "TURRET_RAIL moved"),
				() -> assertEquals(13, BELLJAR.getMeta(), "BELLJAR moved")
		);
	}

	@Test
	@DisplayName("only the railgun turret is hidden from the creative tab")
	void onlyRailgunTurretIsHidden()
	{
		BlockEnumTestSupport.assertHiddenFromCreative(values(), TURRET_RAIL);
	}

	@Test
	@DisplayName("listForCreative() is keyed to the hard-coded ordinal 12")
	void hardCodedCreativeOrdinalStillPointsAtTheRailgunTurret()
	{
		// BlockTypes_MetalDevice1#listForCreative() compares against the literal 12 rather than
		// against the constant, so this pins the two together.
		assertEquals(12, TURRET_RAIL.ordinal(),
				"listForCreative() hides ordinal 12; it is no longer the railgun turret");
	}
}
