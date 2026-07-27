/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import org.junit.jupiter.api.*;

import java.util.Random;

import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The reservoir maths: capacity rolls, the pressure decline curve, and what extraction costs.
 */
class ReservoirModelTest
{
	@BeforeEach
	void setUp()
	{
		PetroleumTestSupport.reset();
		registerTestType();
	}

	@AfterEach
	void tearDown()
	{
		PetroleumTestSupport.reset();
	}

	@Nested
	@DisplayName("capacity rolls")
	class Capacity
	{
		@Test
		@DisplayName("every roll lands inside the type's bounds")
		void staysInBounds()
		{
			ReservoirType type = new ReservoirType("t", "f", 1, 2000, 16000);
			Random random = new Random(1234);
			for(int i = 0; i < 500; i++)
			{
				int rolled = ReservoirModel.rollCapacity(random, type);
				assertTrue(rolled >= 2000, "rolled below the minimum: "+rolled);
				assertTrue(rolled <= 16000, "rolled above the maximum: "+rolled);
			}
		}

		@Test
		@DisplayName("equal bounds give exactly that size")
		void degenerateRange()
		{
			ReservoirType type = new ReservoirType("t", "f", 1, 5000, 5000);
			assertEquals(5000, ReservoirModel.rollCapacity(new Random(7), type));
		}

		@Test
		@DisplayName("bounds handed over backwards are corrected, not honoured")
		void invertedBoundsAreCorrected()
		{
			ReservoirType type = new ReservoirType("t", "f", 1, 9000, 1000);
			assertEquals(1000, type.getMinCapacity());
			assertEquals(9000, type.getMaxCapacity());
			int rolled = ReservoirModel.rollCapacity(new Random(3), type);
			assertTrue(rolled >= 1000&&rolled <= 9000);
		}

		@Test
		@DisplayName("the distribution favours small fields over large ones")
		void logDistributionFavoursSmallFields()
		{
			//The whole point of rolling log-distributed rather than uniform: over a 2M-16M
			//range a uniform roll would put the median near 9M and make every field "big
			//enough", which flattens the discovery curve. Weighting by magnitude should put
			//the median well below the arithmetic midpoint.
			ReservoirType type = new ReservoirType("t", "f", 1, 2_000_000, 16_000_000);
			Random random = new Random(99);
			int belowMidpoint = 0;
			int samples = 2000;
			for(int i = 0; i < samples; i++)
				if(ReservoirModel.rollCapacity(random, type) < 9_000_000)
					belowMidpoint++;
			assertTrue(belowMidpoint > samples*0.65,
					"expected most fields below the arithmetic midpoint, got "+belowMidpoint+"/"+samples);
		}

		@Test
		@DisplayName("the same seed always rolls the same size")
		void deterministic()
		{
			ReservoirType type = new ReservoirType("t", "f", 1, 1000, 100000);
			assertEquals(ReservoirModel.rollCapacity(new Random(42), type),
					ReservoirModel.rollCapacity(new Random(42), type));
		}
	}

	@Nested
	@DisplayName("free flow and decline")
	class Flow
	{
		@Test
		@DisplayName("a fresh field flows without a pump")
		void freshFieldFreeFlows()
		{
			Reservoir reservoir = full(100000);
			assertTrue(ReservoirModel.isFreeFlowing(reservoir));
			assertEquals(PetroleumConfig.peakFlowRate,
					ReservoirModel.flowRate(reservoir, false), 0.0001);
		}

		@Test
		@DisplayName("exactly at the threshold still counts as free-flowing")
		void thresholdIsInclusive()
		{
			Reservoir reservoir = at(100000, PetroleumConfig.freeFlowThreshold);
			assertTrue(ReservoirModel.isFreeFlowing(reservoir));
		}

		@Test
		@DisplayName("below the threshold an unpumped well produces nothing")
		void belowThresholdNeedsAPump()
		{
			Reservoir reservoir = at(100000, 0.3);
			assertFalse(ReservoirModel.isFreeFlowing(reservoir));
			assertEquals(0, ReservoirModel.flowRate(reservoir, false), 0.0001);
			assertTrue(ReservoirModel.flowRate(reservoir, true) > 0,
					"a pump should still get something out");
		}

		@Test
		@DisplayName("the pumped rate declines as the field empties")
		void rateDeclinesMonotonically()
		{
			double previous = Double.MAX_VALUE;
			for(double fraction = 0.55; fraction >= 0; fraction -= 0.05)
			{
				double rate = ReservoirModel.flowRate(at(100000, fraction), true);
				assertTrue(rate <= previous+0.0001,
						"rate rose as the field emptied at fraction "+fraction);
				previous = rate;
			}
		}

		@Test
		@DisplayName("an exhausted field still seeps rather than stopping dead")
		void exhaustedFieldStillSeeps()
		{
			//The design promise: a pumpjack never strands a base that was built around it.
			Reservoir reservoir = full(100000);
			reservoir.deplete(100000);
			assertEquals(0, reservoir.getRemaining());
			assertEquals(PetroleumConfig.residualFlowRate,
					ReservoirModel.flowRate(reservoir, true), 0.0001);
		}

		@Test
		@DisplayName("a cell that rolled nothing produces nothing, pumped or not")
		void emptyCellProducesNothing()
		{
			Reservoir nothing = new Reservoir("", 0);
			assertTrue(nothing.isEmpty());
			assertFalse(ReservoirModel.isFreeFlowing(nothing));
			assertEquals(0, ReservoirModel.flowRate(nothing, true), 0.0001);
			assertEquals(0, ReservoirModel.extract(nothing, 1000, 20, true, false, false));
		}

		@Test
		@DisplayName("a null deposit is handled rather than thrown on")
		void nullIsSafe()
		{
			assertFalse(ReservoirModel.isFreeFlowing(null));
			assertEquals(0, ReservoirModel.flowRate(null, true), 0.0001);
			assertEquals(0, ReservoirModel.extract(null, 100, 20, true, false, false));
		}
	}

	@Nested
	@DisplayName("extraction")
	class Extraction
	{
		@Test
		@DisplayName("a draw is bounded by the interval's worth of flow")
		void boundedByRate()
		{
			Reservoir reservoir = full(1_000_000);
			//10 ticks at the peak rate, however much the caller asks for.
			int taken = ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 10, true, false, false);
			assertEquals(PetroleumConfig.peakFlowRate*10, taken);
		}

		@Test
		@DisplayName("a draw is also bounded by what the caller asked for")
		void boundedByRequest()
		{
			Reservoir reservoir = full(1_000_000);
			assertEquals(5, ReservoirModel.extract(reservoir, 5, 100, true, false, false));
		}

		@Test
		@DisplayName("extraction depletes the pool")
		void depletes()
		{
			Reservoir reservoir = full(1_000_000);
			int taken = ReservoirModel.extract(reservoir, 1000, 100, true, false, false);
			assertTrue(taken > 0);
			assertEquals(1_000_000-taken, reservoir.getRemaining());
		}

		@Test
		@DisplayName("simulating moves nothing")
		void simulateIsPure()
		{
			Reservoir reservoir = full(1_000_000);
			int simulated = ReservoirModel.extract(reservoir, 1000, 100, true, false, true);
			assertEquals(1_000_000, reservoir.getRemaining(), "simulate must not deplete");
			int real = ReservoirModel.extract(reservoir, 1000, 100, true, false, false);
			assertEquals(simulated, real, "simulate must predict the real draw exactly");
		}

		@Test
		@DisplayName("zero or negative arguments draw nothing")
		void degenerateArguments()
		{
			Reservoir reservoir = full(1_000_000);
			assertEquals(0, ReservoirModel.extract(reservoir, 0, 20, true, false, false));
			assertEquals(0, ReservoirModel.extract(reservoir, -5, 20, true, false, false));
			assertEquals(0, ReservoirModel.extract(reservoir, 100, 0, true, false, false));
			assertEquals(0, ReservoirModel.extract(reservoir, 100, -20, true, false, false));
			assertEquals(1_000_000, reservoir.getRemaining());
		}

		@Test
		@DisplayName("a long unloaded interval is capped rather than paid out in full")
		void hugeIntervalIsCapped()
		{
			//A device unloaded for months reports a huge elapsed count. Paying that at today's
			//pressure would be wrong twice over: it overflows, and it ignores that pressure
			//would have declined as the fluid came out. The well simply was not producing,
			//because nothing was there to receive it.
			Reservoir reservoir = full(1_000_000);
			int taken = ReservoirModel.extract(reservoir, Integer.MAX_VALUE, Integer.MAX_VALUE,
					true, false, false);
			assertTrue(taken >= 0, "a huge interval produced a negative draw: "+taken);
			assertEquals(PetroleumConfig.peakFlowRate*ReservoirModel.MAX_CATCH_UP_TICKS, taken,
					"a huge interval should pay exactly the catch-up ceiling");
			assertEquals(1_000_000-taken, reservoir.getRemaining());
		}

		@Test
		@DisplayName("intervals under the cap are paid in full")
		void normalIntervalIsNotCapped()
		{
			Reservoir reservoir = full(1_000_000);
			int ticks = ReservoirModel.MAX_CATCH_UP_TICKS/2;
			assertEquals(PetroleumConfig.peakFlowRate*ticks,
					ReservoirModel.extract(reservoir, Integer.MAX_VALUE, ticks, true, false, false));
		}

		@Test
		@DisplayName("draining a field completely leaves it seeping, not dead")
		void drainToSeep()
		{
			Reservoir reservoir = full(2000);
			for(int i = 0; i < 2000; i++)
				ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 20, true, false, false);
			assertEquals(0, reservoir.getRemaining(), "the pool should be gone");

			//The seep is well under 1 mB per poll, so without carrying the remainder between
			//draws it would floor to nothing every time and the field really would be dead.
			int seeped = 0;
			for(int i = 0; i < 200; i++)
				seeped += ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 20, true, false, false);
			assertTrue(seeped > 0, "an exhausted field must still yield its seep");
			//200 polls x 20 ticks x 0.025 mB/t = 100 mB, give or take the carried remainder.
			assertEquals(100, seeped, 2,
					"the seep should track the configured residual rate, not round away");
		}

		@Test
		@DisplayName("a slow well is not rounded out of existence")
		void fractionalRateAccumulates()
		{
			//Directly the bug the carried remainder exists to fix: floor(0.025 * 20) is 0, so
			//every single poll would yield nothing at all.
			PetroleumConfig.peakFlowRate = 1;
            PetroleumConfig.residualFlowRate = 0.025;
			Reservoir reservoir = full(10000);
			reservoir.deplete(10000);
			int total = 0;
			for(int i = 0; i < 100; i++)
				total += ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 20, true, false, false);
			assertEquals(50, total, 2, "0.025 mB/t over 2000 ticks should be about 50 mB");
		}
	}

	@Nested
	@DisplayName("city mode")
	class CityMode
	{
		@Test
		@DisplayName("extraction never depletes the field")
		void neverDepletes()
		{
			Reservoir reservoir = full(1000);
			for(int i = 0; i < 100; i++)
				ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 20, true, true, false);
			assertEquals(1000, reservoir.getRemaining(),
					"a city-mode field must hold its level exactly");
		}

		@Test
		@DisplayName("the field still yields at its normal rate")
		void yieldIsUnchanged()
		{
			Reservoir reservoir = full(1_000_000);
			int city = ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 10, true, true, false);
			Reservoir other = full(1_000_000);
			int normal = ReservoirModel.extract(other, Integer.MAX_VALUE, 10, true, false, false);
			assertEquals(normal, city, "city mode changes the bookkeeping, not the rate");
		}

		@Test
		@DisplayName("a half-drained field keeps reading as half-drained")
		void pressureIsFrozenWhereItWas()
		{
			//Switching city mode on should not silently refill or reset a field that a normal
			//-mode server had already worked -- it freezes where it is.
			Reservoir reservoir = at(100000, 0.3);
			int before = reservoir.getRemaining();
			assertFalse(ReservoirModel.isFreeFlowing(reservoir));
			ReservoirModel.extract(reservoir, Integer.MAX_VALUE, 200, true, true, false);
			assertEquals(before, reservoir.getRemaining());
			assertFalse(ReservoirModel.isFreeFlowing(reservoir),
					"it should still need its pump, exactly as before");
		}

		@Test
		@DisplayName("an empty cell is still empty")
		void emptyStaysEmpty()
		{
			assertEquals(0, ReservoirModel.extract(new Reservoir("", 0), 1000, 20, true, true, false));
		}
	}

	@Nested
	@DisplayName("config sensitivity")
	class Config
	{
		@Test
		@DisplayName("raising the peak rate raises the draw")
		void peakRateApplies()
		{
			PetroleumConfig.peakFlowRate = 100;
			assertEquals(1000, ReservoirModel.extract(full(1_000_000), Integer.MAX_VALUE, 10,
					true, false, false));
		}

		@Test
		@DisplayName("a zero threshold means a field never needs a pump")
		void zeroThreshold()
		{
			PetroleumConfig.freeFlowThreshold = 0;
			assertTrue(ReservoirModel.isFreeFlowing(at(100000, 0.01)));
		}

		@Test
		@DisplayName("a threshold of one means a field needs a pump almost immediately")
		void fullThreshold()
		{
			PetroleumConfig.freeFlowThreshold = 1;
			assertTrue(ReservoirModel.isFreeFlowing(full(100000)), "a full field is still at 1.0");
			assertFalse(ReservoirModel.isFreeFlowing(at(100000, 0.99)));
		}

		@Test
		@DisplayName("out-of-range thresholds are clamped rather than trusted")
		void thresholdIsClamped()
		{
			PetroleumConfig.freeFlowThreshold = 5;
			assertFalse(ReservoirModel.isFreeFlowing(at(100000, 0.99)));
			PetroleumConfig.freeFlowThreshold = -5;
			assertTrue(ReservoirModel.isFreeFlowing(at(100000, 0.01)));
		}
	}
}
