/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GridDeviceType} ordinals are written into world saves, so this class exists
 * mainly to make reordering the enum a test failure rather than a silent data migration.
 */
class GridDeviceTypeTest
{
	@Test
	@DisplayName("ordinals are frozen -- reordering would rewrite every saved device")
	void ordinalsAreFrozen()
	{
		assertEquals(0, GridDeviceType.FEED.ordinal());
		assertEquals(1, GridDeviceType.SERVICE.ordinal());
		assertEquals(2, GridDeviceType.CONSOLE.ordinal());
	}

	@Test
	@DisplayName("constant count is frozen")
	void constantCountIsFrozen()
	{
		//Appending is fine and this number moves with it; the point of the guard is the
		//ordinal *order* below, which is what world saves are written against.
		assertEquals(4, GridDeviceType.values().length);
	}

	@Test
	@DisplayName("names are lowercase and unique")
	void namesAreLowercaseAndUnique()
	{
		assertEquals("feed", GridDeviceType.FEED.getName());
		assertEquals("service", GridDeviceType.SERVICE.getName());
		assertEquals("console", GridDeviceType.CONSOLE.getName());
		assertNotEquals(GridDeviceType.FEED.getName(), GridDeviceType.SERVICE.getName());
	}

	@Test
	@DisplayName("only the console carries no energy")
	void consoleDoesNotMoveEnergy()
	{
		assertTrue(GridDeviceType.FEED.movesEnergy());
		assertTrue(GridDeviceType.SERVICE.movesEnergy());
		assertFalse(GridDeviceType.CONSOLE.movesEnergy());
	}

	@Test
	@DisplayName("byIndex resolves every valid ordinal")
	void byIndexResolvesValidOrdinals()
	{
		for(GridDeviceType type : GridDeviceType.values())
			assertSame(type, GridDeviceType.byIndex(type.ordinal()));
	}

	@Test
	@DisplayName("byIndex falls back rather than throwing on a corrupt tag")
	void byIndexIsLenient()
	{
		assertSame(GridDeviceType.FEED, GridDeviceType.byIndex(-1));
		assertSame(GridDeviceType.FEED, GridDeviceType.byIndex(GridDeviceType.values().length));
		assertSame(GridDeviceType.FEED, GridDeviceType.byIndex(Integer.MAX_VALUE));
		assertSame(GridDeviceType.FEED, GridDeviceType.byIndex(Integer.MIN_VALUE));
	}
}
