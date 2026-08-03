/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a fluid fitting reports to redstone.
 * <p>
 * The comparator is the only readout a fitting gives the world, and it answers a different question
 * in each of the two modes the network runs in. That difference is city mode's whole bargain --
 * stop metering, keep the appearance -- so it is worth stating once and asserting rather than
 * leaving as two branches inside a tile entity nothing can construct.
 */
class FluidNetDeviceLogicTest
{
	private static final int CAP = 500;

	@Nested
	@DisplayName("normal mode reports throughput")
	class NormalMode
	{
		@Test
		@DisplayName("a fitting at its cap reads full")
		void atCapReadsFull()
		{
			assertEquals(15, FluidNetDeviceLogic.comparatorLevel(true, true, false, false, CAP, CAP));
		}

		@Test
		@DisplayName("a fitting moving nothing reads zero")
		void idleReadsZero()
		{
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(true, true, false, false, 0, CAP));
		}

		@Test
		@DisplayName("a fitting moving anything at all reads at least one")
		void tricklingReadsOne()
		{
			//Rounded up on purpose: a line that is working must never read the same as a line that
			//is dead, however slowly it happens to be moving.
			assertEquals(1, FluidNetDeviceLogic.comparatorLevel(true, true, false, false, 1, CAP));
		}

		@Test
		@DisplayName("the level never leaves 0..15 at any throughput")
		void alwaysInRange()
		{
			for(int flow = 0; flow <= CAP*2; flow += 7)
			{
				int level = FluidNetDeviceLogic.comparatorLevel(true, true, false, false, flow, CAP);
				assertTrue(level >= 0&&level <= 15, "level "+level+" at "+flow+" mB/t");
			}
		}

		@Test
		@DisplayName("throughput above the cap still reads full rather than overflowing")
		void aboveCapReadsFull()
		{
			assertEquals(15, FluidNetDeviceLogic.comparatorLevel(true, true, false, false, CAP*3, CAP));
		}

		@Test
		@DisplayName("a fitting with no cap reads zero rather than dividing by it")
		void noCapIsSafe()
		{
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(true, true, false, false, 100, 0));
		}
	}

	@Nested
	@DisplayName("city mode reports presence instead")
	class CityMode
	{
		@Test
		@DisplayName("a pressurised main reads full whatever the throughput says")
		void pressurisedReadsFull()
		{
			//City mode stops metering flow, so a proportional reading would sit at whatever the
			//last real tick happened to leave behind. Presence is the question that still has a
			//true answer there.
			assertEquals(15, FluidNetDeviceLogic.comparatorLevel(true, true, true, true, 0, CAP));
		}

		@Test
		@DisplayName("a main with no source reads zero whatever the throughput says")
		void noSourceReadsZero()
		{
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(true, true, true, false, CAP, CAP));
		}

		@Test
		@DisplayName("it is all or nothing -- there is no partial reading in city mode")
		void isBinary()
		{
			for(int flow = 0; flow <= CAP; flow += 31)
			{
				int level = FluidNetDeviceLogic.comparatorLevel(true, true, true, true, flow, CAP);
				assertEquals(15, level, "city mode should not vary with throughput");
			}
		}
	}

	@Nested
	@DisplayName("a fitting that is not carrying anything reads zero either way")
	class NotCarrying
	{
		@Test
		@DisplayName("an unlinked fitting reads zero")
		void unlinkedReadsZero()
		{
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(false, true, false, false, CAP, CAP));
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(false, true, true, true, CAP, CAP));
		}

		@Test
		@DisplayName("a fitting on a closed, tripped or sleeping main reads zero")
		void notOperationalReadsZero()
		{
			//One number, so there is no separate "broken" level. "Nothing is coming through" is the
			//honest answer to all three, and the console is where the reason lives.
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(true, false, false, false, CAP, CAP));
			assertEquals(0, FluidNetDeviceLogic.comparatorLevel(true, false, true, true, CAP, CAP));
		}
	}

	@Nested
	@DisplayName("the colour a fitting is tinted")
	class Colour
	{
		@Test
		@DisplayName("an unlinked fitting is white rather than any main's colour")
		void unlinkedIsWhite()
		{
			assertEquals(FluidNetDeviceLogic.UNLINKED_COLOUR,
					FluidNetDeviceLogic.mainColour(true, 0x00FF00));
		}

		@Test
		@DisplayName("a linked one wears its main's colour")
		void linkedWearsItsMain()
		{
			assertEquals(0x00FF00, FluidNetDeviceLogic.mainColour(false, 0x00FF00));
		}
	}
}
