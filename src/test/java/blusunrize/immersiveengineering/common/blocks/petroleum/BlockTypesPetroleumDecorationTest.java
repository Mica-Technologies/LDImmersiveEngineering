/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockEnumTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDecoration.values;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link BlockTypes_PetroleumDecoration}.
 * <p>
 * The ordinal of each constant is the block metadata written into every chunk that contains one of
 * these blocks. Inserting, removing or reordering a constant silently remaps every already-placed
 * block in every existing world save, so the ordinals are asserted one by one: any such edit must
 * break a test rather than a player's world.
 * <p>
 * Plain asphalt also sets from the wet fluid, but it is listed as well so a creative build does not have to pour it.
 */
class BlockTypesPetroleumDecorationTest
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
		assertEquals(4, values().length,
				"a constant was added or removed -- confirm the metadata remap is intentional");
	}

	@Test
	@DisplayName("every road surface is listed")
	void creativeListing()
	{
		BlockEnumTestSupport.assertAllListedForCreative(values());
	}

	@Test
	@DisplayName("the persisted order is exactly this")
	void orderIsFrozen()
	{
		//Named one by one on purpose. A count check alone passes when two constants are swapped,
		//which is the edit most likely to be made by accident and the one that does the most damage.
		String[] expected = {"asphalt", "asphalt_tile", "asphalt_marked", "forecourt_canopy"};
		for(int meta = 0; meta < expected.length; meta++)
			assertEquals(expected[meta], values()[meta].getName(),
					"metadata "+meta+" no longer means what it used to");
	}
}
