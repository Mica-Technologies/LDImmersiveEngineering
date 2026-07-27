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

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_Connector.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_Connector}.
 * <p>
 * The ordinal of each constant is the block metadata that gets written into every chunk that
 * contains one of these blocks. Inserting, removing or reordering a constant silently remaps
 * every already-placed block in every existing world save, so the ordinals are asserted one by
 * one here: any such edit must break a test rather than a player's world.
 */
class BlockTypesConnectorTest
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
		assertEquals(15, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every ordinal is frozen -- reordering corrupts existing world saves")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, CONNECTOR_LV.getMeta(), "CONNECTOR_LV moved"),
				() -> assertEquals(1, RELAY_LV.getMeta(), "RELAY_LV moved"),
				() -> assertEquals(2, CONNECTOR_MV.getMeta(), "CONNECTOR_MV moved"),
				() -> assertEquals(3, RELAY_MV.getMeta(), "RELAY_MV moved"),
				() -> assertEquals(4, CONNECTOR_HV.getMeta(), "CONNECTOR_HV moved"),
				() -> assertEquals(5, RELAY_HV.getMeta(), "RELAY_HV moved"),
				() -> assertEquals(6, CONNECTOR_STRUCTURAL.getMeta(), "CONNECTOR_STRUCTURAL moved"),
				() -> assertEquals(7, TRANSFORMER.getMeta(), "TRANSFORMER moved"),
				() -> assertEquals(8, TRANSFORMER_HV.getMeta(), "TRANSFORMER_HV moved"),
				() -> assertEquals(9, BREAKERSWITCH.getMeta(), "BREAKERSWITCH moved"),
				() -> assertEquals(10, REDSTONE_BREAKER.getMeta(), "REDSTONE_BREAKER moved"),
				() -> assertEquals(11, ENERGY_METER.getMeta(), "ENERGY_METER moved"),
				() -> assertEquals(12, CONNECTOR_REDSTONE.getMeta(), "CONNECTOR_REDSTONE moved"),
				() -> assertEquals(13, CONNECTOR_PROBE.getMeta(), "CONNECTOR_PROBE moved"),
				() -> assertEquals(14, FEEDTHROUGH.getMeta(), "FEEDTHROUGH moved")
		);
	}

	@Test
	@DisplayName("only the feedthrough is hidden from the creative tab")
	void onlyFeedthroughIsHidden()
	{
		BlockEnumTestSupport.assertHiddenFromCreative(values(), FEEDTHROUGH);
	}
}
