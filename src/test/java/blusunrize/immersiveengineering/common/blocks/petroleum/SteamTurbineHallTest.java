/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.ALTERNATOR;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.CORE;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.EMPTY;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.MESH;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSteamTurbineHall.SHELL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.FULL_EFFICIENCY;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.IDLE_EFFICIENCY;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.MAX_OUTPUT;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.MAX_STEAM_PER_TICK;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.MIN_STEAM_PER_PASS;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.SPOOL_FULL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.WORK_INTERVAL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.efficiencyAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.outputAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.spoolAfter;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall.steamPerPassAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Steam Turbine Hall's three curves and its shape.
 * <p>
 * The curves are the machine, more so here than for any other generator in the mod: this is the
 * one whose whole design brief is that partial spool is not merely slow, it is <em>wasteful</em>,
 * and that claim is either true of {@link TileEntitySteamTurbineHall#steamPerPassAt(int)} or it
 * is not true of anything a player will ever see. A hall that produced the pinned peak numbers
 * but was exactly as efficient at half spool as at full would still form, still light up and
 * still make 30,000 FE/t -- it would just be a Diesel Generator with three extra zeroes on it,
 * which is precisely what this machine is not supposed to be.
 * <p>
 * The shape fails quietly in the usual direction: a wrong band boundary means a hall that refuses
 * to form when hammered, with no message and nothing in the log, so it is worth pinning here too.
 * <p>
 * Scope note: the pass itself, the tank and the flux hand-off all need a {@code World}, a
 * {@code FluidStack} or a neighbouring {@code TileEntity}, none of which can be constructed in
 * this test JVM.
 */
class SteamTurbineHallTest
{
	private static final int HEIGHT = MultiblockSteamTurbineHall.HEIGHT;
	private static final int DEPTH = MultiblockSteamTurbineHall.DEPTH;
	private static final int WIDTH = MultiblockSteamTurbineHall.WIDTH;

	@Nested
	@DisplayName("spool")
	class Spool
	{
		@Test
		@DisplayName("cold to full is twenty seconds, and coasting back down is forty")
		void rampTimes()
		{
			assertEquals(400, TileEntitySteamTurbineHall.SPOOL_UP_TICKS);
			assertEquals(800, TileEntitySteamTurbineHall.SPOOL_DOWN_TICKS);

			int spool = 0;
			for(int t = 0; t < TileEntitySteamTurbineHall.SPOOL_UP_TICKS; t++)
			{
				assertTrue(spool < SPOOL_FULL, "reached full early, at tick "+t);
				spool = spoolAfter(spool, true);
			}
			assertEquals(SPOOL_FULL, spool);

			for(int t = 0; t < TileEntitySteamTurbineHall.SPOOL_DOWN_TICKS; t++)
			{
				assertTrue(spool > 0, "stopped early, at tick "+t);
				spool = spoolAfter(spool, false);
			}
			assertEquals(0, spool);
		}

		@Test
		@DisplayName("decay is exactly half the build rate, as pinned")
		void decayIsHalfTheBuildRate()
		{
			assertEquals(TileEntitySteamTurbineHall.SPOOL_UP_STEP/2, TileEntitySteamTurbineHall.SPOOL_DOWN_STEP);
			//And therefore twice as long to coast down as it took to spin up.
			assertEquals(TileEntitySteamTurbineHall.SPOOL_UP_TICKS*2, TileEntitySteamTurbineHall.SPOOL_DOWN_TICKS);
		}

		@Test
		@DisplayName("it winds down rather than snapping to zero when steam stops")
		void coastRatherThanCut()
		{
			int spool = SPOOL_FULL;
			spool = spoolAfter(spool, false);
			assertTrue(spool > 0);
			assertTrue(spool < SPOOL_FULL);
		}

		@Test
		@DisplayName("the count never leaves its range, whatever it is handed")
		void clamped()
		{
			assertEquals(0, spoolAfter(0, false));
			assertEquals(SPOOL_FULL, spoolAfter(SPOOL_FULL, true));
			assertEquals(0, spoolAfter(-500, false));
			assertEquals(SPOOL_FULL, spoolAfter(SPOOL_FULL*10, true));
			assertTrue(spoolAfter(SPOOL_FULL*10, false) <= SPOOL_FULL);
			assertTrue(spoolAfter(-500, true) >= 0);
		}

		@Test
		@DisplayName("a warm rotor is a head start, so a brief steam gap is cheaper than a cold start")
		void warmRestartIsCheaper()
		{
			int spool = SPOOL_FULL;
			for(int t = 0; t < 10; t++)
				spool = spoolAfter(spool, false);
			assertTrue(spool > SPOOL_FULL*9/10);
			assertTrue(efficiencyAt(spool) > efficiencyAt(0));
		}
	}

	@Nested
	@DisplayName("output curve")
	class Output
	{
		@Test
		@DisplayName("full output is the pinned peak, split evenly across three terminals")
		void peakOutput()
		{
			assertEquals(30000, MAX_OUTPUT);
			assertEquals(0, MAX_OUTPUT%MultiblockSteamTurbineHall.TERMINAL_COUNT);
			assertEquals(10000, MAX_OUTPUT/MultiblockSteamTurbineHall.TERMINAL_COUNT);
		}

		@Test
		@DisplayName("no output at zero spool, and a spooled hall makes everything")
		void endpoints()
		{
			assertEquals(0, outputAt(0));
			assertEquals(MAX_OUTPUT, outputAt(SPOOL_FULL));
			assertEquals(0, outputAt(-1));
			assertEquals(MAX_OUTPUT, outputAt(SPOOL_FULL*10));
		}

		@Test
		@DisplayName("it only ever climbs")
		void monotone()
		{
			for(int spool = 1; spool <= SPOOL_FULL; spool++)
				assertTrue(outputAt(spool) >= outputAt(spool-1), "fell back at spool "+spool);
		}

		@Test
		@DisplayName("half spooled is half power, so the readout is the machine and not a curve")
		void linear()
		{
			assertEquals(MAX_OUTPUT/2, outputAt(SPOOL_FULL/2));
			assertEquals(MAX_OUTPUT/4, outputAt(SPOOL_FULL/4));
		}
	}

	@Nested
	@DisplayName("efficiency curve")
	class Efficiency
	{
		@Test
		@DisplayName("exactly a hundred Flux per millibucket at full spool, as pinned")
		void fullSpoolIsExactlyPinned()
		{
			assertEquals(100, FULL_EFFICIENCY);
			assertEquals(100, efficiencyAt(SPOOL_FULL));
			assertEquals(100, efficiencyAt(SPOOL_FULL*10), "clamped, not extrapolated past full");
		}

		@Test
		@DisplayName("about forty Flux per millibucket cold, never fewer")
		void idleFloor()
		{
			assertEquals(40, IDLE_EFFICIENCY);
			assertEquals(40, efficiencyAt(0));
			assertEquals(40, efficiencyAt(-500), "clamped, not extrapolated below cold");
		}

		@Test
		@DisplayName("it only ever climbs")
		void monotone()
		{
			for(int spool = 1; spool <= SPOOL_FULL; spool++)
				assertTrue(efficiencyAt(spool) >= efficiencyAt(spool-1), "fell back at spool "+spool);
			assertTrue(efficiencyAt(SPOOL_FULL) > efficiencyAt(SPOOL_FULL*3/4));
			assertTrue(efficiencyAt(SPOOL_FULL*3/4) > efficiencyAt(SPOOL_FULL/2));
			assertTrue(efficiencyAt(SPOOL_FULL/2) > efficiencyAt(SPOOL_FULL/4));
		}

		@Test
		@DisplayName("it never leaves the pinned forty-to-hundred band")
		void bounded()
		{
			for(int spool = 0; spool <= SPOOL_FULL; spool++)
			{
				assertTrue(efficiencyAt(spool) >= IDLE_EFFICIENCY, "below the idle floor at "+spool);
				assertTrue(efficiencyAt(spool) <= FULL_EFFICIENCY, "above the pinned peak at "+spool);
			}
		}
	}

	@Nested
	@DisplayName("steam cost")
	class SteamCost
	{
		@Test
		@DisplayName("a full-spool pass costs exactly the pinned peak draw")
		void fullSpoolIsPinned()
		{
			assertEquals(MAX_STEAM_PER_TICK*WORK_INTERVAL, steamPerPassAt(SPOOL_FULL));
			assertEquals(1500, steamPerPassAt(SPOOL_FULL));
		}

		@Test
		@DisplayName("a cold hall never computes a free pass")
		void neverFreeAtIgnition()
		{
			//The whole point of MIN_STEAM_PER_PASS: outputAt(0) is zero, so a naive output/efficiency
			//division would charge nothing at all, and "tank amount >= 0" is true of an empty tank.
			assertEquals(0, outputAt(0));
			assertTrue(steamPerPassAt(0) > 0, "a cold pass must still owe real steam");
			assertEquals(MIN_STEAM_PER_PASS, steamPerPassAt(0));
		}

		@Test
		@DisplayName("the floor never lets a pass cost less than it, even where the curve would")
		void floorHolds()
		{
			for(int spool = 0; spool <= SPOOL_FULL; spool++)
				assertTrue(steamPerPassAt(spool) >= MIN_STEAM_PER_PASS,
						"pass at spool "+spool+" undercut the floor");
		}

		@Test
		@DisplayName("it only ever climbs, and never exceeds the pinned peak draw")
		void monotoneAndCapped()
		{
			int cap = MAX_STEAM_PER_TICK*WORK_INTERVAL;
			for(int spool = 1; spool <= SPOOL_FULL; spool++)
			{
				assertTrue(steamPerPassAt(spool) >= steamPerPassAt(spool-1),
						"fell back at spool "+spool);
				assertTrue(steamPerPassAt(spool) <= cap, "exceeded peak draw at spool "+spool);
			}
		}

		@Test
		@DisplayName("rounding never lets a pass buy more Flux than the steam it paid for was worth")
		void neverUndercharges()
		{
			//Checked against the exact (unrounded) output-per-Flux ratio the cost is actually derived
			//from, not against outputAt/efficiencyAt: those are each already floored for display, and
			//comparing two independently-floored curves against each other is exactly what let a
			//spool value round its own cost below what it should have been in the first place. The
			//real guarantee steamPerPassAt makes is against the exact ratio, which is what its own
			//ceiling division is computed from.
			for(int spool = 0; spool <= SPOOL_FULL; spool += 7)
			{
				long fluxPromised = (long)MAX_OUTPUT*WORK_INTERVAL*spool;
				long fluxPaidFor = (long)steamPerPassAt(spool)
						*(IDLE_EFFICIENCY*(long)SPOOL_FULL+(FULL_EFFICIENCY-IDLE_EFFICIENCY)*(long)spool);
				assertTrue(fluxPaidFor >= fluxPromised,
						"spool "+spool+" would have delivered more Flux than its steam was worth");
			}
		}

		@Test
		@DisplayName("a full cold start burns disproportionately more steam than the Flux it makes is worth")
		void cyclingIsWasteful()
		{
			//The whole design brief in one assertion, mirroring the Gas Turbine's own version of it:
			//run one cold start, pass by pass, and compare it against the same span of passes spent
			//at full output. The start has to cost disproportionately more steam for disproportionately
			//less Flux, or there is no reason to leave the hall running rather than cycling it.
			assertEquals(0, TileEntitySteamTurbineHall.SPOOL_UP_TICKS%WORK_INTERVAL,
					"the ramp has to divide evenly into whole passes for this comparison to be fair");
			int passes = TileEntitySteamTurbineHall.SPOOL_UP_TICKS/WORK_INTERVAL;

			long steamSpent = 0;
			long fluxMade = 0;
			int spool = 0;
			for(int pass = 0; pass < passes; pass++)
			{
				//The pass charges steam against the spool it starts the interval at, exactly as
				//runPass() does against the master's own field before spoolAfter ticks it forward.
				steamSpent += steamPerPassAt(spool);
				for(int t = 0; t < WORK_INTERVAL; t++)
				{
					spool = spoolAfter(spool, true);
					fluxMade += outputAt(spool);
				}
			}
			assertEquals(SPOOL_FULL, spool);

			long steamAtFullOutput = (long)passes*steamPerPassAt(SPOOL_FULL);
			long fluxAtFullOutput = (long)passes*WORK_INTERVAL*MAX_OUTPUT;

			assertTrue(fluxMade < fluxAtFullOutput);
			assertTrue(steamSpent < steamAtFullOutput);
			//steamSpent/steamAtFullOutput > fluxMade/fluxAtFullOutput, without leaving integers.
			assertTrue(steamSpent*fluxAtFullOutput > fluxMade*steamAtFullOutput,
					"a cold start is not costing enough steam per Flux to be worth avoiding");
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the hall is five tall, nine long and five wide")
		void size()
		{
			assertEquals(5, HEIGHT);
			assertEquals(9, DEPTH);
			assertEquals(5, WIDTH);
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockSteamTurbineHall.blockCount(SHELL)
							+MultiblockSteamTurbineHall.blockCount(MESH)
							+MultiblockSteamTurbineHall.blockCount(CORE)
							+MultiblockSteamTurbineHall.blockCount(ALTERNATOR)
							+MultiblockSteamTurbineHall.blockCount(FRAME)
							+MultiblockSteamTurbineHall.blockCount(EMPTY));
		}

		@Test
		@DisplayName("the raft is one continuous slab under the whole building")
		void raftIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(SHELL, MultiblockSteamTurbineHall.shapeAt(0, l, w), "raft cell "+l+","+w);
		}

		@Test
		@DisplayName("a 5x9x5 box that was solid all through would be a block, not a building")
		void hallIsMostlyAir()
		{
			int solid = 0;
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(MultiblockSteamTurbineHall.isPart(h, l, w))
							solid++;
			//Cross-multiplied rather than divided, so integer truncation cannot quietly loosen the
			//bound: a floor-and-roof building this squat is never going to be mostly air by volume,
			//but it must still be measurably short of solid.
			assertTrue(solid*4 < HEIGHT*DEPTH*WIDTH*3,
					"a hall filling its own box is a monolith, not a building with a hall inside");
		}

		@Test
		@DisplayName("the condenser wall is louvred at its middle course, and solid everywhere else on it")
		void condenserWallIsLouvred()
		{
			int condenser = MultiblockSteamTurbineHall.CONDENSER_WALL;
			for(int w = 0; w < WIDTH; w++)
				for(int h = 1; h <= 3; h++)
				{
					char expected = (h==2&&w > 0&&w < WIDTH-1)?MESH: SHELL;
					assertEquals(expected, MultiblockSteamTurbineHall.shapeAt(h, condenser, w),
							"condenser wall cell "+h+","+w);
				}
		}

		@Test
		@DisplayName("the core sits two layers tall on the centre line, between the inlet and the alternator")
		void coreIsBetweenInletAndAlternator()
		{
			int centre = WIDTH/2;
			assertEquals(FRAME, MultiblockSteamTurbineHall.shapeAt(1, MultiblockSteamTurbineHall.INLET_DEPTH, centre));
			for(int l : new int[]{MultiblockSteamTurbineHall.CORE_DEPTH_1, MultiblockSteamTurbineHall.CORE_DEPTH_2})
				for(int h : new int[]{1, 2})
					assertEquals(CORE, MultiblockSteamTurbineHall.shapeAt(h, l, centre), "core cell "+h+","+l);
			assertEquals(ALTERNATOR,
					MultiblockSteamTurbineHall.shapeAt(1, MultiblockSteamTurbineHall.ALTERNATOR_DEPTH, centre));
			//Ordered along the axis exactly as the flavour text promises: condenser, then the
			//machinery, then (checked below) the switchyard.
			assertTrue(MultiblockSteamTurbineHall.CONDENSER_WALL < MultiblockSteamTurbineHall.INLET_DEPTH);
			assertTrue(MultiblockSteamTurbineHall.INLET_DEPTH < MultiblockSteamTurbineHall.CORE_DEPTH_1);
			assertTrue(MultiblockSteamTurbineHall.CORE_DEPTH_2 < MultiblockSteamTurbineHall.ALTERNATOR_DEPTH);
			assertTrue(MultiblockSteamTurbineHall.ALTERNATOR_DEPTH < MultiblockSteamTurbineHall.ENERGY_DEPTH);
			assertTrue(MultiblockSteamTurbineHall.ENERGY_DEPTH < DEPTH-1);
		}

		@Test
		@DisplayName("the switchyard bus deck is open above and solid below, like the turbine's own terminals")
		void switchyardDeckIsOpenOnTop()
		{
			int energy = MultiblockSteamTurbineHall.ENERGY_DEPTH;
			for(int w = 1; w < WIDTH-1; w++)
			{
				assertEquals(EMPTY, MultiblockSteamTurbineHall.shapeAt(HEIGHT-1, energy, w),
						"deck cell "+w+" should be open to the sky");
				assertFalse(MultiblockSteamTurbineHall.isPart(HEIGHT-1, energy, w));
				assertEquals(SHELL, MultiblockSteamTurbineHall.shapeAt(HEIGHT-2, energy, w),
						"deck cell "+w+" needs something solid underneath the opening");
			}
			//And nowhere else does the roof open up.
			for(int l = 0; l < DEPTH; l++)
				if(l!=energy)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(SHELL, MultiblockSteamTurbineHall.shapeAt(HEIGHT-1, l, w),
								"unexpected roof opening at "+l+","+w);
		}

		@Test
		@DisplayName("three terminals sit under the deck, and the peak output divides evenly across them")
		void terminalsMatchTheDeck()
		{
			assertEquals(3, MultiblockSteamTurbineHall.TERMINAL_COUNT);
			for(int w = 0; w < MultiblockSteamTurbineHall.TERMINAL_COUNT; w++)
			{
				int pos = MultiblockSteamTurbineHall.terminalPos(w);
				assertEquals(HEIGHT-2, PetroleumGeometry.heightOf(PetroleumGeometry.HALL_SIZE, pos));
				assertTrue(MultiblockSteamTurbineHall.isPart(HEIGHT-2, MultiblockSteamTurbineHall.ENERGY_DEPTH, w+1));
			}
		}

		@Test
		@DisplayName("exactly one frame cell exists, and it is the only hammer trigger")
		void exactlyOneFrame()
		{
			assertEquals(1, MultiblockSteamTurbineHall.blockCount(FRAME));
			assertEquals(MultiblockSteamTurbineHall.MASTER_POS,
					PetroleumGeometry.structureIndex(PetroleumGeometry.HALL_SIZE, 1,
							MultiblockSteamTurbineHall.INLET_DEPTH, WIDTH/2));
		}

		@Test
		@DisplayName("every course is symmetric across the width, so there is no mirrored variant")
		void noMirroredVariant()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockSteamTurbineHall.shapeAt(h, l, w),
								MultiblockSteamTurbineHall.shapeAt(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("anything outside the box is not part of the hall")
		void outsideTheBox()
		{
			assertEquals('?', MultiblockSteamTurbineHall.shapeAt(-1, 0, 0));
			assertEquals('?', MultiblockSteamTurbineHall.shapeAt(HEIGHT, 0, 0));
			assertEquals('?', MultiblockSteamTurbineHall.shapeAt(0, DEPTH, 0));
			assertEquals('?', MultiblockSteamTurbineHall.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockSteamTurbineHall.isPart(-1, 0, 0));
			assertFalse(MultiblockSteamTurbineHall.isPart(0, 0, WIDTH));
		}

		/**
		 * The property that decides whether the structure is buildable at all: every block has to be
		 * placeable against one that is already there. See {@code DistillationTowerTest} for why this
		 * matters -- a floating cell can never be reached with a right-click, and the only symptom is
		 * that hammering the finished shell does nothing.
		 */
		@Test
		@DisplayName("every block can be placed against one already standing")
		void nothingFloats()
		{
			Set<Integer> placed = new HashSet<>();
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockSteamTurbineHall.isPart(0, l, w))
						placed.add(key(0, l, w));

			boolean grew = true;
			while(grew)
			{
				grew = false;
				for(int h = 0; h < HEIGHT; h++)
					for(int l = 0; l < DEPTH; l++)
						for(int w = 0; w < WIDTH; w++)
						{
							if(!MultiblockSteamTurbineHall.isPart(h, l, w)||placed.contains(key(h, l, w)))
								continue;
							if(placed.contains(key(h-1, l, w))||placed.contains(key(h+1, l, w))
									||placed.contains(key(h, l-1, w))||placed.contains(key(h, l+1, w))
									||placed.contains(key(h, l, w-1))||placed.contains(key(h, l, w+1)))
							{
								placed.add(key(h, l, w));
								grew = true;
							}
						}
			}
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(MultiblockSteamTurbineHall.isPart(h, l, w))
							assertTrue(placed.contains(key(h, l, w)),
									"nothing to place against at "+h+","+l+","+w);
		}

		private int key(int h, int l, int w)
		{
			return (h*DEPTH+l)*WIDTH+w;
		}
	}

	@Nested
	@DisplayName("steam ports")
	class Ports
	{
		@Test
		@DisplayName("the whole raft takes steam")
		void raftTakesSteam()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntitySteamTurbineHall.isSteamPort(
							PetroleumGeometry.structureIndex(MultiblockSteamTurbineHall.SIZE, 0, l, w)),
							"raft cell "+l+","+w);
		}

		@Test
		@DisplayName("so does the inlet skid, which is where a steam main is meant to land")
		void inletTakesSteam()
		{
			assertTrue(TileEntitySteamTurbineHall.isSteamPort(MultiblockSteamTurbineHall.MASTER_POS));
		}

		@Test
		@DisplayName("the switchyard deck does not, because that is the electrical end")
		void terminalsDoNot()
		{
			for(int w = 0; w < MultiblockSteamTurbineHall.TERMINAL_COUNT; w++)
				assertFalse(TileEntitySteamTurbineHall.isSteamPort(MultiblockSteamTurbineHall.terminalPos(w)),
						"terminal "+w);
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and takes nothing")
		void unformedTakesNothing()
		{
			assertFalse(TileEntitySteamTurbineHall.isSteamPort(-1));
		}
	}
}
