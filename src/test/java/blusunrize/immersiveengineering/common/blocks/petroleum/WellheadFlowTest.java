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
 * The wellhead's back-pressure arithmetic.
 * <p>
 * The wellhead was the only extraction machine in this feature with no test class, and this is the
 * arithmetic that was hiding in it: the rule that a well with nowhere to put its associated gas
 * backs up and stops. If a draw is ever authorised whose gas does not fit, the overflow is
 * destroyed on the way in and the flare stack, the scrubber and the re-injection well all become
 * optional scenery.
 * <p>
 * The headline test is therefore named after that property and asserts <em>it</em>, swept across a
 * wide range of tank states and ratios rather than spot-checked -- the failure only shows up at
 * particular combinations of remainder and ratio, which is exactly what endpoint tests miss.
 */
class WellheadFlowTest
{
	/** What the feature actually ships: a quarter of a bucket of gas per bucket of crude. */
	private static final double SHIPPED_RATIO = 0.25;

	@Nested
	@DisplayName("the gas a draw brings up always fits in the gas tank")
	class BackPressureHolds
	{
		@Test
		@DisplayName("across every combination of tank room and ratio")
		void gasNeverOverfills()
		{
			double[] ratios = {0.05, 0.1, 0.2, SHIPPED_RATIO, 1.0/3, 0.5, 0.7, 1.0, 1.5, 2.0};
			for(double ratio : ratios)
				for(int oilRoom = 0; oilRoom <= 2000; oilRoom += 7)
					for(int gasRoom = 0; gasRoom <= 2000; gasRoom += 13)
					{
						int draw = WellheadFlow.drawRoom(oilRoom, gasRoom, ratio);
						assertTrue(draw >= 0,
								"negative draw at ratio "+ratio+", oil "+oilRoom+", gas "+gasRoom);
						assertTrue(draw <= oilRoom,
								"draw "+draw+" exceeds oil room "+oilRoom+" at ratio "+ratio);
						int gas = WellheadFlow.associatedGas(draw, ratio);
						assertTrue(gas <= gasRoom,
								"draw of "+draw+" brings up "+gas+" mB of gas but only "+gasRoom
										+" mB of room exists, at ratio "+ratio);
					}
		}
	}

	@Nested
	@DisplayName("a full gas tank stops the well")
	class FullGasStopsTheWell
	{
		@Test
		@DisplayName("no gas room means no draw at all, however empty the oil tank is")
		void noGasRoomMeansNoDraw()
		{
			assertEquals(0, WellheadFlow.drawRoom(16000, 0, SHIPPED_RATIO));
		}

		@Test
		@DisplayName("a ratio of zero takes the gas side out of the decision entirely")
		void zeroRatioIgnoresGas()
		{
			//The gas tank is full, but nothing is coming up with the oil, so the well runs.
			assertEquals(16000, WellheadFlow.drawRoom(16000, 0, 0));
		}

		@Test
		@DisplayName("the oil tank still limits the draw when gas is plentiful")
		void oilRoomStillBinds()
		{
			assertEquals(500, WellheadFlow.drawRoom(500, 1000000, SHIPPED_RATIO));
		}
	}

	@Nested
	@DisplayName("the gas side converts room correctly")
	class Conversion
	{
		@Test
		@DisplayName("room for a bucket of gas is room for four buckets of crude at the shipped ratio")
		void gasRoomConvertsToCrude()
		{
			assertEquals(4000, WellheadFlow.drawRoom(16000, 1000, SHIPPED_RATIO));
		}

		@Test
		@DisplayName("and that draw uses the gas room exactly, with nothing left and nothing lost")
		void conversionIsExact()
		{
			int draw = WellheadFlow.drawRoom(16000, 1000, SHIPPED_RATIO);
			assertEquals(1000, WellheadFlow.associatedGas(draw, SHIPPED_RATIO));
		}

		@Test
		@DisplayName("a part-millibucket of gas room is floored rather than rounded up")
		void partialRoomIsFloored()
		{
			//Room for 3 mB of gas at 0.3 is room for 10 mB of crude, which brings up exactly 3.
			assertEquals(10, WellheadFlow.drawRoom(16000, 3, 0.3));
			assertEquals(3, WellheadFlow.associatedGas(10, 0.3));
		}
	}

	@Nested
	@DisplayName("negative and degenerate inputs are absorbed rather than propagated")
	class Degenerate
	{
		@Test
		@DisplayName("negative room reads as no room")
		void negativeRoomIsZero()
		{
			assertEquals(0, WellheadFlow.drawRoom(-5, 1000, SHIPPED_RATIO));
			assertEquals(0, WellheadFlow.drawRoom(1000, -5, SHIPPED_RATIO));
		}

		@Test
		@DisplayName("no crude means no gas")
		void noCrudeNoGas()
		{
			assertEquals(0, WellheadFlow.associatedGas(0, SHIPPED_RATIO));
			assertEquals(0, WellheadFlow.associatedGas(-100, SHIPPED_RATIO));
		}
	}

	@Nested
	@DisplayName("the buffer is sized against the configured peak rate")
	class Capacity
	{
		@Test
		@DisplayName("it holds two production passes at the peak rate")
		void holdsTwoPasses()
		{
			int rate = 40;
			assertEquals(2*blusunrize.immersiveengineering.common.util.petroleum
							.PetroleumTickHandler.PRODUCTION_INTERVAL*rate,
					WellheadFlow.capacityFor(rate));
		}

		@Test
		@DisplayName("never smaller than a bucket, however low the rate is set")
		void neverBelowABucket()
		{
			assertEquals(1000, WellheadFlow.capacityFor(0));
			assertEquals(1000, WellheadFlow.capacityFor(-10));
			assertTrue(WellheadFlow.capacityFor(1) >= 1000);
		}

		@Test
		@DisplayName("a rate that grows grows the buffer with it")
		void scalesWithRate()
		{
			assertTrue(WellheadFlow.capacityFor(200) > WellheadFlow.capacityFor(100),
					"raising the peak rate must not leave the buffer behind");
		}
	}
}
