/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Portable Generator's fuel table and its numbers.
 * <p>
 * The whole reason this machine exists is the engine-type split: gasoline runs handheld tools and a
 * Diesel Generator refuses it, which left the cut with no consumer that scales. If this ever starts
 * accepting diesel the split collapses and both generators become the same machine with different
 * models.
 */
class PortableGeneratorTest
{
	@Nested
	@DisplayName("the fuel table")
	class Fuels
	{
		@Test
		@DisplayName("it burns the spark-ignition fuels")
		void burnsSparkFuels()
		{
			assertTrue(TileEntityPortableGenerator.isFuel("ie_gasoline"));
			assertTrue(TileEntityPortableGenerator.isFuel("ethanol"));
		}

		@Test
		@DisplayName("it refuses diesel, exactly as the Diesel Generator refuses gasoline")
		void refusesCompressionFuels()
		{
			//This is the design, not an oversight. A spark engine cannot burn diesel.
			assertFalse(TileEntityPortableGenerator.isFuel("ie_diesel"));
			assertFalse(TileEntityPortableGenerator.isFuel("biodiesel"));
			assertFalse(TileEntityPortableGenerator.isFuel("ie_heavy_fuel_oil"));
			assertFalse(TileEntityPortableGenerator.isFuel("ie_crude_oil"));
		}

		@Test
		@DisplayName("it refuses things that are not fuel at all")
		void refusesNonFuels()
		{
			assertFalse(TileEntityPortableGenerator.isFuel("water"));
			assertFalse(TileEntityPortableGenerator.isFuel("ie_lubricant"));
			assertFalse(TileEntityPortableGenerator.isFuel((String)null));
		}
	}

	@Nested
	@DisplayName("the numbers")
	class Numbers
	{
		@Test
		@DisplayName("it is deliberately the small one")
		void outputIsSmall()
		{
			//A twentieth of a Gas Turbine and a sixteenth of a Diesel Generator: this powers a work
			//light and a small machine, and must never be the answer to a factory.
			assertEquals(256, TileEntityPortableGenerator.OUTPUT);
			assertTrue(TileEntityPortableGenerator.OUTPUT < 4096,
					"a portable generator that rivals a Diesel Generator has no reason to be portable");
		}

		@Test
		@DisplayName("a tank lasts minutes, not seconds or hours")
		void runtimeIsAnErrand()
		{
			//Long enough that a trip to the forecourt is an errand rather than a chore, short enough
			//that it stays portable rather than becoming a base's power supply.
			int ticks = TileEntityPortableGenerator.TANK_CAPACITY
					*TileEntityPortableGenerator.ticksPerBucket()/1000;
			int seconds = ticks/20;
			assertTrue(seconds > 120, "a tank that lasts under two minutes is a chore, got "+seconds+"s");
			assertTrue(seconds < 1200, "a tank that lasts twenty minutes is a base generator, got "+seconds+"s");
		}

		@Test
		@DisplayName("portability costs about half the value per millibucket")
		void portabilityHasAPrice()
		{
			//The Diesel Generator makes 4096 Flux/t and burns a bucket of diesel over 175 ticks:
			//about 717,000 Flux per bucket. Portability should cost roughly half of that, and this
			//is the assertion that noticed it was costing thirteen fourteenths of it.
			int dieselGeneratorPerBucket = 4096*175;
			int fluxPerBucket = TileEntityPortableGenerator.fluxPerBucket();
			assertEquals(384000, fluxPerBucket);
			assertTrue(fluxPerBucket < dieselGeneratorPerBucket,
					"a portable generator should not beat a stationary one per millibucket");
			double share = fluxPerBucket/(double)dieselGeneratorPerBucket;
			assertTrue(share > 0.4&&share < 0.7,
					"portability should cost about half the value per millibucket, not "
							+Math.round(share*100)+"%");
		}
	}
}
