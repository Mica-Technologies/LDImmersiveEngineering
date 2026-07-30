/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the Crawler has enough diesel to work.
 * <p>
 * <strong>A regression test for a bug that shipped.</strong> The fuel gate lived inline on the
 * entity, where the harness cannot reach it, and it was one comparison that treated an empty tank
 * and a nearly empty one as the same thing. A new Crawler arrives dry, so every machine anybody
 * built silently refused to break anything -- and said "running on reserve", describing a state its
 * operator was not in. It was reported as the Breaker not working on leaves; it was not working on
 * anything.
 * <p>
 * Every other decision in this feature was pulled into a pure function so it could be covered. This
 * one was not, and it is the one that broke. It is a pure function now.
 */
class CrawlerConfigTest
{
	@Test
	@DisplayName("a machine that has never been fuelled cannot work")
	void emptyCannotWork()
	{
		assertFalse(CrawlerConfig.canWork(0), "an empty tank should not run the attachment");
	}

	@Test
	@DisplayName("an empty tank is reported as empty, not as low")
	void emptyIsNotTheSameAsLow()
	{
		//The whole of the bug. Both refuse to work, and they need to say different things: one means
		//"refuel before working", the other means "you have never fuelled this".
		assertTrue(CrawlerConfig.isDry(0), "empty should read as dry");
		assertFalse(CrawlerConfig.isDry(CrawlerConfig.fuelReserve),
				"a tank down to its reserve is low, not empty, and telling somebody it is empty when "
						+"they can see fuel in it is worse than saying nothing");
	}

	@Test
	@DisplayName("the reserve is held back from the attachment")
	void reserveIsForDrivingHome()
	{
		//At the reserve exactly, and anywhere below it, the attachment stops. The tracks do not -- that
		//is what the reserve is for, and it is why the boundary is inclusive.
		assertFalse(CrawlerConfig.canWork(CrawlerConfig.fuelReserve),
				"the reserve itself should be held back, or there is nothing left to drive home on");
		assertFalse(CrawlerConfig.canWork(CrawlerConfig.fuelReserve-1));
	}

	@Test
	@DisplayName("a fuelled machine works")
	void fuelledCanWork()
	{
		assertTrue(CrawlerConfig.canWork(CrawlerConfig.fuelReserve+1));
		assertTrue(CrawlerConfig.canWork(CrawlerConfig.fuelCapacity));
	}

	@Test
	@DisplayName("the reserve is a usable fraction of the tank, not most of it")
	void reserveIsProportionate()
	{
		//A reserve approaching the capacity would be a machine that never works however much you put
		//in it -- the same symptom as the bug, arrived at by tuning instead of by logic.
		assertTrue(CrawlerConfig.fuelReserve < CrawlerConfig.fuelCapacity/4,
				"the reserve is "+CrawlerConfig.fuelReserve+" of a "+CrawlerConfig.fuelCapacity
						+" tank, which leaves little of it usable");
		assertTrue(CrawlerConfig.fuelReserve > 0, "a zero reserve strands people");
	}
}
