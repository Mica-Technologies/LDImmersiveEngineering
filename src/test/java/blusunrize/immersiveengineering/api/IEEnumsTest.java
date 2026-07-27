/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link IEEnums.SideConfig}.
 * <p>
 * SideConfig is stored by ordinal in tile entity NBT (sideConfig arrays), so its order is part of
 * the save format. The cycling behaviour is what the player sees when clicking a machine's side
 * with a screwdriver.
 */
class IEEnumsTest
{
	@Test
	@DisplayName("the ordinals are frozen -- they are persisted in tile entity NBT")
	void ordinalsAreFrozen()
	{
		assertAll(
				() -> assertEquals(0, SideConfig.NONE.ordinal()),
				() -> assertEquals(1, SideConfig.INPUT.ordinal()),
				() -> assertEquals(2, SideConfig.OUTPUT.ordinal())
		);
		assertEquals(3, SideConfig.values().length, "a SideConfig was added or removed");
	}

	@Test
	@DisplayName("NONE is the default, i.e. ordinal zero")
	void noneIsTheDefault()
	{
		assertEquals(SideConfig.NONE, SideConfig.values()[0],
				"an unset/zeroed side must deserialise to NONE, not to a live input or output");
	}

	@Test
	@DisplayName("getName() is the lowercased constant name")
	void getNameIsLowercase()
	{
		assertEquals("none", SideConfig.NONE.getName());
		assertEquals("input", SideConfig.INPUT.getName());
		assertEquals("output", SideConfig.OUTPUT.getName());
	}

	@Test
	@DisplayName("getTextureName() uses the short forms and is unique")
	void textureNamesAreShortAndUnique()
	{
		assertEquals("none", SideConfig.NONE.getTextureName());
		assertEquals("in", SideConfig.INPUT.getTextureName());
		assertEquals("out", SideConfig.OUTPUT.getTextureName());

		Set<String> seen = new HashSet<>();
		for(SideConfig config : SideConfig.values())
			assertTrue(seen.add(config.getTextureName()), "duplicate texture name for "+config);
	}

	@Test
	@DisplayName("next() walks NONE -> INPUT -> OUTPUT -> NONE")
	void nextWalksTheExpectedCycle()
	{
		assertEquals(SideConfig.INPUT, SideConfig.next(SideConfig.NONE));
		assertEquals(SideConfig.OUTPUT, SideConfig.next(SideConfig.INPUT));
		assertEquals(SideConfig.NONE, SideConfig.next(SideConfig.OUTPUT));
	}

	@Test
	@DisplayName("next() returns to the start after one full lap from every state")
	void nextIsACompleteCycle()
	{
		for(SideConfig start : SideConfig.values())
		{
			SideConfig current = start;
			Set<SideConfig> visited = new HashSet<>();
			for(int i = 0; i < SideConfig.values().length; i++)
			{
				assertTrue(visited.add(current), "next() revisited "+current+" before completing a lap");
				current = SideConfig.next(current);
			}
			assertEquals(start, current, "next() did not come back to "+start+" after a full lap");
			assertEquals(SideConfig.values().length, visited.size(), "next() skipped a state");
		}
	}

	@Test
	@DisplayName("next() never returns null")
	void nextIsTotal()
	{
		for(SideConfig config : SideConfig.values())
			assertNotNull(SideConfig.next(config), "next() returned null for "+config);
	}

	@Test
	@DisplayName("valueOf round-trips the constant name")
	void valueOfRoundTrips()
	{
		for(SideConfig config : SideConfig.values())
			assertEquals(config, SideConfig.valueOf(config.name()));
	}
}
