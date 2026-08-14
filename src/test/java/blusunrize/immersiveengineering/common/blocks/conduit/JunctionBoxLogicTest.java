/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The junction box's three decisions.
 * <p>
 * The bucket brigade that moves energy along a run is {@code ConduitTransfer} and is tested there.
 * These are what was left inline on the tile entity, reachable only through a world: what a
 * comparator reads off a sixteen-channel bundle, how much a channel will accept, and how the
 * redstone conductors resolve when several boxes on one run drive the same colour.
 */
class JunctionBoxLogicTest
{
	private static final int CAP = 16000;
	private static final int CHANNELS = WireChannel.VALUES.length;

	private static int[] bundle(int... values)
	{
		int[] held = new int[CHANNELS];
		System.arraycopy(values, 0, held, 0, Math.min(values.length, CHANNELS));
		return held;
	}

	@Nested
	@DisplayName("what a comparator reads off a bundle")
	class Comparator
	{
		@Test
		@DisplayName("an idle bundle reads zero")
		void idleReadsZero()
		{
			assertEquals(0, JunctionBoxLogic.comparatorLevel(bundle(), CAP));
		}

		@Test
		@DisplayName("a bundle carrying anything at all reads at least one")
		void carryingAnythingReadsOne()
		{
			//"Carrying a trickle" and "carrying nothing" must be distinguishable, or a comparator
			//cannot be used to notice that a run has gone dead.
			assertEquals(1, JunctionBoxLogic.comparatorLevel(bundle(1), CAP));
		}

		@Test
		@DisplayName("it reads the busiest conductor, not the total")
		void busiestNotTotal()
		{
			//	=================================
			//	The decision this function exists for.
			//	=================================
			//
			// One saturated channel and fifteen idle ones is a bundle with a problem. Sixteen
			// channels each at a sixteenth of capacity is a bundle working normally. Their totals
			// are identical, so a summing comparator would report the two as the same thing.
			int[] oneSaturated = bundle(CAP);
			int[] sixteenTicking = new int[CHANNELS];
			java.util.Arrays.fill(sixteenTicking, CAP/CHANNELS);

			assertEquals(15, JunctionBoxLogic.comparatorLevel(oneSaturated, CAP),
					"a saturated conductor must read full");
			assertTrue(JunctionBoxLogic.comparatorLevel(sixteenTicking, CAP) < 15,
					"sixteen channels ticking over must not read as saturated");
		}

		@Test
		@DisplayName("and not the average either")
		void notTheAverage()
		{
			//An average over sixteen channels would put one saturated conductor at 15/16 of a level
			//-- which floors to 0 and then to 1, indistinguishable from a trickle.
			assertEquals(15, JunctionBoxLogic.comparatorLevel(bundle(CAP), CAP));
		}

		@Test
		@DisplayName("the level never leaves 0..15 at any fill")
		void alwaysInRange()
		{
			for(int held = 0; held <= CAP; held += 137)
			{
				int level = JunctionBoxLogic.comparatorLevel(bundle(held), CAP);
				assertTrue(level >= 0&&level <= 15, "level "+level+" at "+held);
			}
		}

		@Test
		@DisplayName("the level never falls as a channel fills")
		void monotonic()
		{
			int previous = 0;
			for(int held = 0; held <= CAP; held += 53)
			{
				int level = JunctionBoxLogic.comparatorLevel(bundle(held), CAP);
				assertTrue(level >= previous, "level dropped from "+previous+" to "+level);
				previous = level;
			}
		}

		@Test
		@DisplayName("degenerate inputs read zero rather than throwing")
		void degenerateIsZero()
		{
			assertEquals(0, JunctionBoxLogic.comparatorLevel(null, CAP));
			assertEquals(0, JunctionBoxLogic.comparatorLevel(bundle(500), 0));
			assertEquals(0, JunctionBoxLogic.comparatorLevel(new int[0], CAP));
		}

		@Test
		@DisplayName("a huge capacity does not overflow the level calculation")
		void hugeCapacityDoesNotOverflow()
		{
			//busiest*15 in int overflows above ~143 million, and a channel capacity is a config
			//value somebody may raise.
			assertTrue(JunctionBoxLogic.comparatorLevel(bundle(Integer.MAX_VALUE), Integer.MAX_VALUE) >= 0);
			assertEquals(15, JunctionBoxLogic.comparatorLevel(bundle(Integer.MAX_VALUE), Integer.MAX_VALUE));
		}
	}

	@Nested
	@DisplayName("how much a channel accepts")
	class Credit
	{
		@Test
		@DisplayName("an empty channel takes the whole offer")
		void emptyTakesEverything()
		{
			assertEquals(500, JunctionBoxLogic.credit(0, 500, CAP));
		}

		@Test
		@DisplayName("a nearly full one takes only what fits")
		void nearlyFullTakesTheRemainder()
		{
			assertEquals(100, JunctionBoxLogic.credit(CAP-100, 500, CAP));
		}

		@Test
		@DisplayName("a full one takes nothing")
		void fullTakesNothing()
		{
			assertEquals(0, JunctionBoxLogic.credit(CAP, 500, CAP));
		}

		@Test
		@DisplayName("an over-full channel takes nothing rather than reporting a negative")
		void overFullTakesNothing()
		{
			//A negative here would be subtracted from the sender and credited nowhere, quietly
			//destroying energy on every hop.
			assertEquals(0, JunctionBoxLogic.credit(CAP+500, 100, CAP));
		}

		@Test
		@DisplayName("an empty offer moves nothing")
		void emptyOfferMovesNothing()
		{
			assertEquals(0, JunctionBoxLogic.credit(0, 0, CAP));
			assertEquals(0, JunctionBoxLogic.credit(0, -50, CAP));
		}

		@Test
		@DisplayName("a channel never ends up holding more than its capacity")
		void neverExceedsCapacity()
		{
			for(int held = 0; held <= CAP; held += 311)
				for(int offer : new int[]{1, 100, CAP, CAP*2})
					assertTrue(held+JunctionBoxLogic.credit(held, offer, CAP) <= CAP,
							"channel overfilled from "+held+" by an offer of "+offer);
		}
	}

	@Nested
	@DisplayName("how the redstone conductors resolve across a run")
	class Signals
	{
		@Test
		@DisplayName("one input drives its own channel and no other")
		void oneInputOneChannel()
		{
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS, new int[][]{{3, 9}});
			assertEquals(9, out[3]);
			for(int i = 0; i < CHANNELS; i++)
				if(i!=3)
					assertEquals(0, out[i], "channel "+i+" should be untouched");
		}

		@Test
		@DisplayName("the strongest of several inputs on one channel wins")
		void strongestWins()
		{
			//A conductor reaches every box on the run, so a lever at one end and a comparator at the
			//other both drive the same colour. Taking the maximum makes that behave like a redstone
			//wire, which is the behaviour the player already has in their hands.
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{2, 4}, {2, 15}, {2, 7}});
			assertEquals(15, out[2]);
		}

		@Test
		@DisplayName("inputs are not summed -- two weak signals do not forge a strong one")
		void notSummed()
		{
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{0, 7}, {0, 8}});
			assertEquals(8, out[0], "7 and 8 must resolve to 8, not to 15");
		}

		@Test
		@DisplayName("the answer does not depend on the order the boxes were visited")
		void orderIndependent()
		{
			//Last-writer-wins would make a run's behaviour depend on iteration order, which is not
			//something a player can see, reason about or rely on.
			int[] ascending = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{5, 1}, {5, 6}, {5, 11}});
			int[] descending = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{5, 11}, {5, 6}, {5, 1}});
			assertArrayEquals(ascending, descending);
		}

		@Test
		@DisplayName("channels are independent of one another")
		void channelsAreIndependent()
		{
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{0, 15}, {1, 3}});
			assertEquals(15, out[0]);
			assertEquals(3, out[1]);
		}

		@Test
		@DisplayName("a signal from a neighbour is clamped to a real redstone level")
		void signalsAreClamped()
		{
			//This number comes off a neighbouring block, and another mod may answer with anything.
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{{0, 900}, {1, -50}});
			assertEquals(15, out[0]);
			assertEquals(0, out[1]);
		}

		@Test
		@DisplayName("out-of-range and malformed contributions are ignored rather than throwing")
		void malformedIgnored()
		{
			int[] out = JunctionBoxLogic.strongestPerChannel(CHANNELS,
					new int[][]{null, {}, {99, 15}, {-1, 15}, {4, 12}});
			assertEquals(12, out[4]);
		}

		@Test
		@DisplayName("no inputs leaves every conductor dark")
		void noInputsIsAllDark()
		{
			for(int value : JunctionBoxLogic.strongestPerChannel(CHANNELS, new int[0][]))
				assertEquals(0, value);
			assertEquals(CHANNELS, JunctionBoxLogic.strongestPerChannel(CHANNELS, null).length);
		}
	}

	@Nested
	@DisplayName("which conductor an unasked-for breakout takes")
	class AutoPatch
	{
		@Test
		@DisplayName("a bare box hands out the first conductor")
		void bareBoxGivesTheFirst()
		{
			assertEquals(0, JunctionBoxLogic.firstFreeChannel(0, CHANNELS));
		}

		@Test
		@DisplayName("it never hands out a conductor already broken out somewhere on the box")
		void skipsWhatIsAlreadyPatched()
		{
			//The same conductor arriving at two connectors is a short, not a feature.
			assertEquals(1, JunctionBoxLogic.firstFreeChannel(0b0001, CHANNELS));
			assertEquals(2, JunctionBoxLogic.firstFreeChannel(0b0011, CHANNELS));
			//A gap left by a hand-dyed face is filled before anything past it, so a box wired by
			//hand and then by hardware does not scatter its conductors.
			assertEquals(1, JunctionBoxLogic.firstFreeChannel(0b1101, CHANNELS));
		}

		@Test
		@DisplayName("a box with every conductor spoken for gives nothing rather than stealing one")
		void fullBoxRefuses()
		{
			assertEquals(-1, JunctionBoxLogic.firstFreeChannel(WireChannel.ALL_MASK, CHANNELS));
		}

		@Test
		@DisplayName("bits above the channel count cannot make the box look full")
		void ignoresBitsPastTheEnd()
		{
			//The mask is built from real channels, but the rule should not depend on that: a stray
			//high bit must not be read as "nothing left".
			assertEquals(0, JunctionBoxLogic.firstFreeChannel(1 << (CHANNELS+2), CHANNELS));
		}
	}
}
