/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.ConduitTransfer.Moved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much energy moves down one hop of one channel.
 * <p>
 * Small arithmetic, and the two things it must never do are the two things energy code always ends
 * up doing: creating energy, or oscillating. {@link Convergence} is the test that would have caught
 * the obvious implementation -- hand over the whole gradient -- which makes two boxes swap their
 * contents back and forth forever and shows up as a run that flickers and never settles.
 */
class ConduitTransferTest
{
	private static final int CAP = 32768;
	private static final int RATE = 32768;

	@Nested
	@DisplayName("one hop")
	class Hop
	{
		@Test
		@DisplayName("an empty source moves nothing")
		void emptySourceMovesNothing()
		{
			assertTrue(ConduitTransfer.hop(0, 0, CAP, RATE, 0).isNothing());
			assertTrue(ConduitTransfer.hop(0, 500, CAP, RATE, 0).isNothing());
		}

		@Test
		@DisplayName("energy never flows uphill")
		void neverFlowsUphill()
		{
			//Without this a run would slosh: every box would push to every neighbour every tick and
			//the same energy would go round a loop forever, losing a percent each time.
			assertTrue(ConduitTransfer.hop(100, 200, CAP, RATE, 0).isNothing());
			assertTrue(ConduitTransfer.hop(100, 100, CAP, RATE, 0).isNothing(),
					"a level pair should be still");
		}

		@Test
		@DisplayName("half the difference moves")
		void halfTheGradient()
		{
			Moved moved = ConduitTransfer.hop(1000, 0, CAP, RATE, 0);
			assertEquals(500, moved.taken);
			assertEquals(500, moved.delivered);
		}

		@Test
		@DisplayName("the rate caps a hop")
		void rateCaps()
		{
			//The rate is this channel's own wire, not a share of a bundle-wide allowance.
			assertEquals(64, ConduitTransfer.hop(10000, 0, CAP, 64, 0).taken);
		}

		@Test
		@DisplayName("a full destination takes nothing more")
		void fullDestinationRefuses()
		{
			assertTrue(ConduitTransfer.hop(CAP, CAP, CAP, RATE, 0).isNothing());
			//Nearly full: it takes what fits and no more.
			assertEquals(50, ConduitTransfer.hop(CAP, CAP-100, CAP, RATE, 0).taken);
		}

		@Test
		@DisplayName("a difference of one is left alone")
		void gradientOfOneIsStill()
		{
			//Half of one is nothing, deliberately. Moving it would mean a single unit of flux
			//hopping back and forth between two boxes for as long as the world is loaded, which is
			//a worse outcome than one unit sitting a block further back than it might.
			assertTrue(ConduitTransfer.hop(1, 0, CAP, RATE, 0).isNothing());
			assertTrue(ConduitTransfer.hop(CAP, CAP-1, CAP, RATE, 0).isNothing());
		}

		@Test
		@DisplayName("a nonsensical capacity or rate moves nothing")
		void degenerateArgumentsMoveNothing()
		{
			assertTrue(ConduitTransfer.hop(1000, 0, 0, RATE, 0).isNothing());
			assertTrue(ConduitTransfer.hop(1000, 0, CAP, 0, 0).isNothing());
			assertTrue(ConduitTransfer.hop(1000, 0, CAP, -5, 0).isNothing());
		}
	}

	@Nested
	@DisplayName("loss")
	class Loss
	{
		@Test
		@DisplayName("loss is charged on what arrives, not on what leaves")
		void lossIsChargedOnArrival()
		{
			Moved moved = ConduitTransfer.hop(1000, 0, CAP, RATE, 0.10);
			assertEquals(500, moved.taken);
			assertEquals(450, moved.delivered);
		}

		@Test
		@DisplayName("a hop can never deliver more than it took")
		void neverCreatesEnergy()
		{
			//The one thing energy code must never do. Swept rather than sampled.
			for(int from = 0; from <= 4096; from += 37)
				for(int to = 0; to <= 4096; to += 53)
					for(double loss : new double[]{0, 0.01, 0.5, 1})
					{
						Moved moved = ConduitTransfer.hop(from, to, CAP, RATE, loss);
						assertTrue(moved.delivered <= moved.taken,
								"delivered more than was taken at "+from+"/"+to+"/"+loss);
						assertTrue(moved.taken >= 0&&moved.delivered >= 0);
						assertTrue(moved.taken <= from, "took more than the source held");
					}
		}

		@Test
		@DisplayName("total loss delivers nothing but still consumes")
		void totalLoss()
		{
			Moved moved = ConduitTransfer.hop(1000, 0, CAP, RATE, 1);
			assertEquals(500, moved.taken);
			assertEquals(0, moved.delivered);
		}

		@Test
		@DisplayName("a nonsense loss ratio is clamped rather than believed")
		void lossIsClamped()
		{
			//A bad config value should degrade the wire, not mint energy at the far end.
			assertEquals(500, ConduitTransfer.hop(1000, 0, CAP, RATE, -3).delivered);
			assertEquals(0, ConduitTransfer.hop(1000, 0, CAP, RATE, 9).delivered);
		}

		@Test
		@DisplayName("a trickle travels loss-free rather than being rounded away")
		void tinyPacketsSurvive()
		{
			//Rounding down is the right way round to be wrong. Rounding up would have a nearly-idle
			//run silently eating every small packet that crossed it.
			Moved moved = ConduitTransfer.hop(3, 0, CAP, RATE, 0.10);
			assertEquals(1, moved.taken);
			assertEquals(1, moved.delivered);
		}
	}

	@Nested
	@DisplayName("convergence")
	class Convergence
	{
		@Test
		@DisplayName("two boxes settle instead of swapping forever")
		void twoBoxesSettle()
		{
			//Handing over the whole gradient rather than half makes this alternate 1000/0, 0/1000
			//for as long as the world is loaded.
			int a = 1000;
			int b = 0;
			for(int tick = 0; tick < 200; tick++)
			{
				Moved moved = ConduitTransfer.hop(a, b, CAP, RATE, 0);
				a -= moved.taken;
				b += moved.delivered;
				Moved back = ConduitTransfer.hop(b, a, CAP, RATE, 0);
				b -= back.taken;
				a += back.delivered;
			}
			assertTrue(Math.abs(a-b) <= 1, "the pair never settled: "+a+" against "+b);
			assertEquals(1000, a+b, "energy was created or destroyed with no loss configured");
		}

		@Test
		@DisplayName("a run with a sink at the end drains toward it")
		void energyFlowsTowardTheSink()
		{
			//A bucket brigade: box 0 is fed, box 3 has a machine on it. Nobody walks the run, and
			//energy still ends up where it is wanted.
			int[] boxes = new int[4];
			int delivered = 0;
			for(int tick = 0; tick < 100; tick++)
			{
				boxes[0] = Math.min(CAP, boxes[0]+2000);
				for(int i = 0; i < boxes.length-1; i++)
				{
					Moved moved = ConduitTransfer.hop(boxes[i], boxes[i+1], CAP, RATE, 0);
					boxes[i] -= moved.taken;
					boxes[i+1] += moved.delivered;
				}
				int drained = ConduitTransfer.drain(boxes[3], RATE);
				boxes[3] -= drained;
				delivered += drained;
			}
			assertTrue(delivered > 0, "nothing ever reached the far end of the run");
			assertTrue(delivered > 100000, "the run barely trickled: "+delivered);
		}

		@Test
		@DisplayName("a dead end fills up and stops asking")
		void deadEndStalls()
		{
			//Nothing draws at the far end, so the run should fill and then be still rather than
			//continuing to churn energy away in line loss.
			int a = CAP;
			int b = 0;
			for(int tick = 0; tick < 500; tick++)
			{
				Moved moved = ConduitTransfer.hop(a, b, CAP, RATE, 0.05);
				a -= moved.taken;
				b += moved.delivered;
			}
			assertTrue(ConduitTransfer.hop(a, b, CAP, RATE, 0.05).isNothing(),
					"the pair is still moving energy after five hundred ticks");
		}
	}

	@Nested
	@DisplayName("draining to a connector")
	class Drain
	{
		@Test
		@DisplayName("everything it can, up to the rate")
		void drainsWhatItCan()
		{
			//No gradient: a connector is a way out of the bundle, not another bucket. Holding back
			//would be a machine running at half speed for no visible reason.
			assertEquals(500, ConduitTransfer.drain(500, RATE));
			assertEquals(64, ConduitTransfer.drain(500, 64));
		}

		@Test
		@DisplayName("nothing held drains nothing")
		void emptyDrainsNothing()
		{
			assertEquals(0, ConduitTransfer.drain(0, RATE));
			assertEquals(0, ConduitTransfer.drain(-5, RATE));
			assertEquals(0, ConduitTransfer.drain(500, 0));
		}
	}
}
