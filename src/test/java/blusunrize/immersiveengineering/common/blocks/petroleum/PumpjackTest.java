/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.petroleum.PetroleumConfig;
import blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport;
import blusunrize.immersiveengineering.api.petroleum.Reservoir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.at;
import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.full;
import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.registerTestType;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pumpjack's one piece of pure decision-making: whether a given deposit is worth spending
 * power on.
 * <p>
 * Scope note: everything else the machine does needs a {@code World}, a {@code TileEntity} or a
 * {@code FluxStorage} bound to one, so the interval, the wellhead lookup and the energy draw are
 * not reachable from a unit test. This is the part that decides whether the machine runs at all,
 * which is also the part whose failure would be silent -- a pumpjack that idles forever, or one
 * that burns power on a well that never needed it.
 */
class PumpjackTest
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
	@DisplayName("normal play")
	class Normal
	{
		@Test
		@DisplayName("no deposit at all is never worth pumping")
		void noReservoir()
		{
			assertFalse(TileEntityPumpjack.shouldPump(null, false));
		}

		@Test
		@DisplayName("a cell rolled as containing nothing is never worth pumping")
		void emptyCell()
		{
			assertFalse(TileEntityPumpjack.shouldPump(new Reservoir("nothing", 0), false));
		}

		@Test
		@DisplayName("a fresh deposit reaches the surface unaided, so the pump stays off")
		void freeFlowingIsLeftAlone()
		{
			assertFalse(TileEntityPumpjack.shouldPump(full(100_000), false));
		}

		@Test
		@DisplayName("exactly at the free-flow threshold still flows on its own")
		void thresholdItselfIsFreeFlowing()
		{
			assertFalse(TileEntityPumpjack.shouldPump(
					at(100_000, PetroleumConfig.freeFlowThreshold), false));
		}

		@Test
		@DisplayName("a deposit past the threshold is what the machine exists for")
		void declinedFieldIsPumped()
		{
			assertTrue(TileEntityPumpjack.shouldPump(
					at(100_000, PetroleumConfig.freeFlowThreshold/2), false));
		}

		@Test
		@DisplayName("an exhausted deposit is still pumped -- the residual seep needs the lift")
		void exhaustedFieldIsStillPumped()
		{
			assertTrue(TileEntityPumpjack.shouldPump(at(100_000, 0), false));
		}

		@Test
		@DisplayName("raising the threshold brings a field into scope without touching the field")
		void followsTheConfiguredThreshold()
		{
			Reservoir reservoir = at(100_000, 0.8);
			assertFalse(TileEntityPumpjack.shouldPump(reservoir, false));
			PetroleumConfig.freeFlowThreshold = 0.9;
			assertTrue(TileEntityPumpjack.shouldPump(reservoir, false));
		}
	}

	@Nested
	@DisplayName("city mode")
	class City
	{
		@Test
		@DisplayName("a full deposit is pumped anyway, or the machine would never run at all")
		void freeFlowingIsPumpedAnyway()
		{
			assertTrue(TileEntityPumpjack.shouldPump(full(100_000), true));
		}

		@Test
		@DisplayName("a declined deposit is pumped just the same")
		void declinedFieldIsPumped()
		{
			assertTrue(TileEntityPumpjack.shouldPump(at(100_000, 0.1), true));
		}

		@Test
		@DisplayName("a cell with nothing in it is still nothing to pump")
		void emptyCellIsStillEmpty()
		{
			assertFalse(TileEntityPumpjack.shouldPump(new Reservoir("nothing", 0), true));
			assertFalse(TileEntityPumpjack.shouldPump(null, true));
		}
	}
}
