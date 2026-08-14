/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three city-mode machine rules, exercised without a world.
 * <p>
 * These cover the three symptoms a playtester reported against city mode: machines that stopped
 * producing server side, animations and sounds that stuttered every few seconds, and a redstone
 * switch that turned the machine off without touching its energy buffer.
 */
class CityModeMachinesTest
{
	// ---------------------------------------------------------------- idleScanExempt

	@Nested
	@DisplayName("idleScanExempt -- an idle machine's right to scan every tick")
	class IdleScanExempt
	{
		@Test
		@DisplayName("outside city mode an idle machine always scans, whatever it last did")
		void stockAlwaysExempt()
		{
			assertTrue(CityModeMachines.idleScanExempt(false, 0, CityModeMachines.NEVER));
			assertTrue(CityModeMachines.idleScanExempt(false, 1_000_000, CityModeMachines.NEVER));
			assertTrue(CityModeMachines.idleScanExempt(false, 1_000_000, 5));
		}

		@Test
		@DisplayName("in city mode a machine that has never produced falls back on the throttle")
		void cityModeNeverProducedIsThrottled()
		{
			assertFalse(CityModeMachines.idleScanExempt(true, 0, CityModeMachines.NEVER));
			assertFalse(CityModeMachines.idleScanExempt(true, 12_345, CityModeMachines.NEVER));
		}

		@Test
		@DisplayName("in city mode a machine that just produced keeps scanning every tick")
		void cityModeJustProducedIsExempt()
		{
			assertTrue(CityModeMachines.idleScanExempt(true, 100, 100), "same tick");
			assertTrue(CityModeMachines.idleScanExempt(true, 101, 100), "one tick later");
		}

		@Test
		@DisplayName("the exemption lasts exactly the grace window, then lapses")
		void graceWindowBoundary()
		{
			long start = 1000;
			long last = start+CityModeMachines.IDLE_SCAN_GRACE_TICKS;
			assertTrue(CityModeMachines.idleScanExempt(true, last, start), "the last tick of the window is still inside it");
			assertFalse(CityModeMachines.idleScanExempt(true, last+1, start), "one tick past the window is outside it");
		}

		@Test
		@DisplayName("the window is long enough to bridge any gap between batches, and short enough to lapse")
		void graceWindowIsSaneLength()
		{
			//Long enough that a working machine never trips over it, short enough that a machine which
			//has genuinely stopped settles onto the throttle within a few seconds.
			assertTrue(CityModeMachines.IDLE_SCAN_GRACE_TICKS >= 100, "under five seconds is too twitchy");
			assertTrue(CityModeMachines.IDLE_SCAN_GRACE_TICKS <= 1200, "over a minute never lapses in practice");
		}

		@Test
		@DisplayName("a world clock that ran backwards does not grant a permanent exemption")
		void clockWentBackwards()
		{
			//A restored backup or a /time set can leave lastProcessStart in the future. Treating that
			//as "recent" would exempt the machine for the next several million ticks.
			assertFalse(CityModeMachines.idleScanExempt(true, 50, 5000));
		}

		@Test
		@DisplayName("recentlyProductive agrees with idleScanExempt under city mode")
		void recentlyProductiveMirrorsExemption()
		{
			for(long age = 0; age <= CityModeMachines.IDLE_SCAN_GRACE_TICKS+2; age++)
			{
				long now = 10_000+age;
				assertEquals(CityModeMachines.recentlyProductive(now, 10_000),
						CityModeMachines.idleScanExempt(true, now, 10_000), "age "+age);
			}
		}

		@Test
		@DisplayName("NEVER is never recent")
		void neverIsNotRecent()
		{
			assertFalse(CityModeMachines.recentlyProductive(Long.MIN_VALUE, CityModeMachines.NEVER));
			assertFalse(CityModeMachines.recentlyProductive(0, CityModeMachines.NEVER));
			assertFalse(CityModeMachines.recentlyProductive(Long.MAX_VALUE, CityModeMachines.NEVER));
		}
	}

	// ---------------------------------------------------------------- renderAsActive

	@Nested
	@DisplayName("renderAsActive -- what the client draws and sounds")
	class RenderAsActive
	{
		@Test
		@DisplayName("outside city mode: active exactly when powered, enabled and processing")
		void stockTruthTable()
		{
			for(int mask = 0; mask < 8; mask++)
			{
				boolean enabled = (mask&1)!=0;
				boolean powered = (mask&2)!=0;
				boolean processing = (mask&4)!=0;
				assertEquals(enabled&&powered&&processing,
						CityModeMachines.renderAsActive(false, enabled, powered, processing),
						"enabled="+enabled+" powered="+powered+" processing="+processing);
			}
		}

		@Test
		@DisplayName("in city mode a powered, enabled machine is active with nothing queued")
		void cityModeIdleStillLooksBusy()
		{
			assertTrue(CityModeMachines.renderAsActive(true, true, true, false));
			assertTrue(CityModeMachines.renderAsActive(true, true, true, true));
		}

		@Test
		@DisplayName("redstone still stops the animation in city mode")
		void redstoneStillWins()
		{
			assertFalse(CityModeMachines.renderAsActive(true, false, true, true));
			assertFalse(CityModeMachines.renderAsActive(true, false, true, false));
		}

		@Test
		@DisplayName("an unpowered machine is still still, in either mode")
		void unpoweredIsInactive()
		{
			assertFalse(CityModeMachines.renderAsActive(true, true, false, true));
			assertFalse(CityModeMachines.renderAsActive(true, true, false, false));
			assertFalse(CityModeMachines.renderAsActive(false, true, false, true));
		}

		@Test
		@DisplayName("city mode never turns an active machine off -- it only ever adds")
		void cityModeIsAWidening()
		{
			//The property that makes this safe to switch on live: no configuration of the inputs is
			//active in normal mode and inactive in city mode.
			for(int mask = 0; mask < 8; mask++)
			{
				boolean enabled = (mask&1)!=0;
				boolean powered = (mask&2)!=0;
				boolean processing = (mask&4)!=0;
				if(CityModeMachines.renderAsActive(false, enabled, powered, processing))
					assertTrue(CityModeMachines.renderAsActive(true, enabled, powered, processing),
							"enabled="+enabled+" powered="+powered+" processing="+processing);
			}
		}
	}

	// ---------------------------------------------------------------- redstoneEdgeBufferLevel

	@Nested
	@DisplayName("redstoneEdgeBufferLevel -- the buffer following the switch")
	class RedstoneEdgeBufferLevel
	{
		private static final int CAPACITY = 32000;

		@Test
		@DisplayName("outside city mode the buffer is never touched")
		void stockNeverTouchesTheBuffer()
		{
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(false, null, true, CAPACITY));
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(false, false, true, CAPACITY));
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(false, true, false, CAPACITY));
		}

		@Test
		@DisplayName("switching on fills the buffer")
		void onFills()
		{
			assertEquals(CAPACITY, CityModeMachines.redstoneEdgeBufferLevel(true, false, true, CAPACITY));
		}

		@Test
		@DisplayName("switching off empties the buffer")
		void offDrains()
		{
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, true, false, CAPACITY));
		}

		@Test
		@DisplayName("holding a state does nothing -- this is an edge, not a level")
		void steadyStateIsLeftAlone()
		{
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(true, true, true, CAPACITY));
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(true, false, false, CAPACITY));
		}

		@Test
		@DisplayName("a machine seen for the first time is brought into line immediately")
		void firstObservationCounts()
		{
			//Otherwise a machine loaded from disk with its lever already thrown sits at whatever level
			//it was saved with until somebody flips the lever twice.
			assertEquals(CAPACITY, CityModeMachines.redstoneEdgeBufferLevel(true, null, true, CAPACITY));
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, null, false, CAPACITY));
		}

		@Test
		@DisplayName("a zero-capacity machine is asked for zero, not for a negative level")
		void zeroCapacityIsSafe()
		{
			//The diesel generator is a TileEntityMultiblockMetal with a capacity of 0.
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, null, true, 0));
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, false, true, 0));
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, true, false, -5),
					"a nonsensical capacity still never asks for a negative level");
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, false, true, -5));
		}

		@Test
		@DisplayName("a full on/off/on cycle fills, drains and fills again")
		void fullCycle()
		{
			Boolean last = null;
			boolean enabled = true;
			assertEquals(CAPACITY, CityModeMachines.redstoneEdgeBufferLevel(true, last, enabled, CAPACITY));
			last = enabled;
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(true, last, enabled, CAPACITY));
			enabled = false;
			assertEquals(0, CityModeMachines.redstoneEdgeBufferLevel(true, last, enabled, CAPACITY));
			last = enabled;
			assertEquals(-1, CityModeMachines.redstoneEdgeBufferLevel(true, last, enabled, CAPACITY));
			enabled = true;
			assertEquals(CAPACITY, CityModeMachines.redstoneEdgeBufferLevel(true, last, enabled, CAPACITY));
		}
	}

	// ---------------------------------------------------------------- shape

	@Test
	@DisplayName("CityModeMachines is a non-instantiable utility class")
	void utilityClassShape()
	{
		assertTrue(java.lang.reflect.Modifier.isFinal(CityModeMachines.class.getModifiers()));
		assertEquals(0, CityModeMachines.class.getConstructors().length);
	}
}
