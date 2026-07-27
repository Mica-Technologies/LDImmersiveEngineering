/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.ALTERNATOR;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.CORE;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.EMPTY;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.MESH;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine.SHELL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.MAX_OUTPUT;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.SPOOL_FULL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.efficiencyPermilleAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.fuelPerPassAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.fuelRateAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.outputAt;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.spoolAfter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gas Turbine's two curves, its shape and its fuel table.
 * <p>
 * The curves are the machine. A turbine that reached full output instantly, or one whose fuel
 * curve happened to be cheaper at part load than at full, would still run, still form and still
 * look right -- it would simply be a Diesel Generator with a bigger number on it, which is the one
 * thing this machine exists not to be. Nothing in a running game would report that, so it is
 * asserted here instead.
 * <p>
 * The shape fails quietly in the other direction: a wrong cell means a machine that refuses to
 * form when hammered, with no message and nothing in the log.
 * <p>
 * Scope note: everything else the turbine does needs a {@code World}, a {@code FluidStack} or a
 * neighbouring {@code TileEntity}, none of which can be constructed here, so the pass, the tank
 * and the flux hand-off are not reachable from a unit test.
 */
class GasTurbineTest
{
	private static final int HEIGHT = PetroleumGeometry.TURBINE_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.TURBINE_DEPTH;
	private static final int WIDTH = PetroleumGeometry.TURBINE_WIDTH;

	@Nested
	@DisplayName("spool")
	class Spool
	{
		@Test
		@DisplayName("cold to full is ten seconds, and coasting back down is twenty")
		void rampTimes()
		{
			assertEquals(200, TileEntityGasTurbine.SPOOL_UP_TICKS);
			assertEquals(400, TileEntityGasTurbine.SPOOL_DOWN_TICKS);

			int spool = 0;
			for(int t = 0; t < TileEntityGasTurbine.SPOOL_UP_TICKS; t++)
			{
				assertTrue(spool < SPOOL_FULL, "reached full early, at tick "+t);
				spool = spoolAfter(spool, true);
			}
			assertEquals(SPOOL_FULL, spool);

			for(int t = 0; t < TileEntityGasTurbine.SPOOL_DOWN_TICKS; t++)
			{
				assertTrue(spool > 0, "stopped early, at tick "+t);
				spool = spoolAfter(spool, false);
			}
			assertEquals(0, spool);
		}

		@Test
		@DisplayName("it winds down rather than snapping to zero when the gas stops")
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
		@DisplayName("a warm rotor is a head start, so a brief gas gap is cheaper than a cold start")
		void warmRestartIsCheaper()
		{
			//Half a second off line, then gas again: the machine must not be back at zero.
			int spool = SPOOL_FULL;
			for(int t = 0; t < 10; t++)
				spool = spoolAfter(spool, false);
			assertTrue(spool > SPOOL_FULL*9/10);
			assertTrue(efficiencyPermilleAt(spool) > efficiencyPermilleAt(0));
		}
	}

	@Nested
	@DisplayName("output curve")
	class Output
	{
		@Test
		@DisplayName("full output is three diesel generators, split evenly across three terminals")
		void threeGenerators()
		{
			assertEquals(3*4096, MAX_OUTPUT);
			assertEquals(0, MAX_OUTPUT%WIDTH);
			assertEquals(4096, MAX_OUTPUT/WIDTH);
		}

		@Test
		@DisplayName("a cold machine makes nothing and a spooled one makes everything")
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
	@DisplayName("fuel curve")
	class Fuel
	{
		@Test
		@DisplayName("a machine making nothing is already burning most of its gas")
		void ignitionCostsRealFuel()
		{
			assertEquals(TileEntityGasTurbine.IDLE_FLOW_PERMILLE, fuelRateAt(0));
			assertTrue(fuelRateAt(0) > 500, "ignition flow has to be a real fraction of full flow");
			assertEquals(0, outputAt(0));
		}

		@Test
		@DisplayName("full spool is full flow, and nothing ever exceeds it")
		void fullFlow()
		{
			assertEquals(1000, fuelRateAt(SPOOL_FULL));
			assertEquals(1000, fuelRateAt(SPOOL_FULL*10));
			for(int spool = 0; spool <= SPOOL_FULL; spool++)
				assertTrue(fuelRateAt(spool) <= 1000, "over full flow at spool "+spool);
		}

		@Test
		@DisplayName("it only ever climbs")
		void monotone()
		{
			for(int spool = 1; spool <= SPOOL_FULL; spool++)
				assertTrue(fuelRateAt(spool) >= fuelRateAt(spool-1), "fell back at spool "+spool);
		}

		@Test
		@DisplayName("a pass is one interval of flow, and a full-load pass is the fuel's own rate")
		void perPass()
		{
			assertEquals(TileEntityGasTurbine.FULL_LOAD_FLOW_NATURAL_GAS*TileEntityGasTurbine.BURN_INTERVAL,
					fuelPerPassAt(SPOOL_FULL, TileEntityGasTurbine.FULL_LOAD_FLOW_NATURAL_GAS));
			assertEquals(TileEntityGasTurbine.FULL_LOAD_FLOW_PROPANE*TileEntityGasTurbine.BURN_INTERVAL,
					fuelPerPassAt(SPOOL_FULL, TileEntityGasTurbine.FULL_LOAD_FLOW_PROPANE));
			//An igniting turbine still draws a real charge: a pass it cannot pay for is a pass it
			//does not light for, and a zero here would make cold starts free.
			assertTrue(fuelPerPassAt(0, TileEntityGasTurbine.FULL_LOAD_FLOW_NATURAL_GAS) > 0);
			assertEquals(0, fuelPerPassAt(SPOOL_FULL, 0));
		}
	}

	@Nested
	@DisplayName("part load penalty")
	class PartLoad
	{
		@Test
		@DisplayName("the machine is at its best only at full output")
		void bestAtFull()
		{
			assertEquals(1000, efficiencyPermilleAt(SPOOL_FULL));
			for(int spool = 0; spool < SPOOL_FULL; spool++)
				assertTrue(efficiencyPermilleAt(spool) < 1000, "no penalty at spool "+spool);
		}

		@Test
		@DisplayName("half spooled is markedly worse than half as good")
		void halfIsWorseThanHalf()
		{
			//Half the power on eight tenths of the gas. If this ever reads 500 or better the
			//penalty has gone and the spool is a cosmetic delay rather than a cost.
			assertEquals(800, fuelRateAt(SPOOL_FULL/2));
			assertEquals(625, efficiencyPermilleAt(SPOOL_FULL/2));
			assertTrue(efficiencyPermilleAt(SPOOL_FULL/2) < 700);
		}

		@Test
		@DisplayName("an igniting turbine is burning gas for no return at all")
		void worstAtIgnition()
		{
			assertEquals(0, efficiencyPermilleAt(0));
			assertTrue(fuelRateAt(0) > 0);
		}

		@Test
		@DisplayName("it gets better the whole way up, never worse")
		void monotone()
		{
			for(int spool = 1; spool <= SPOOL_FULL; spool++)
				assertTrue(efficiencyPermilleAt(spool) >= efficiencyPermilleAt(spool-1),
						"fell back at spool "+spool);
			assertTrue(efficiencyPermilleAt(SPOOL_FULL) > efficiencyPermilleAt(SPOOL_FULL*3/4));
			assertTrue(efficiencyPermilleAt(SPOOL_FULL*3/4) > efficiencyPermilleAt(SPOOL_FULL/2));
			assertTrue(efficiencyPermilleAt(SPOOL_FULL/2) > efficiencyPermilleAt(SPOOL_FULL/4));
			assertTrue(efficiencyPermilleAt(SPOOL_FULL/4) > efficiencyPermilleAt(1));
		}

		@Test
		@DisplayName("a cold start spends far more gas than the power it delivers is worth")
		void cyclingIsWasteful()
		{
			//The whole design brief in one assertion. Run one cold start and compare it against the
			//same number of ticks spent at full output: the start has to cost disproportionately
			//more gas for disproportionately less flux, or there is no reason to leave the machine
			//running and the turbine is just a large generator.
			long gasSpent = 0;
			long fluxMade = 0;
			int spool = 0;
			int ticks = 0;
			while(spool < SPOOL_FULL)
			{
				spool = spoolAfter(spool, true);
				gasSpent += fuelRateAt(spool);
				fluxMade += outputAt(spool);
				ticks++;
			}
			long gasAtFullOutput = (long)ticks*1000;
			long fluxAtFullOutput = (long)ticks*MAX_OUTPUT;

			assertTrue(fluxMade < fluxAtFullOutput);
			assertTrue(gasSpent < gasAtFullOutput);
			//gasSpent/gasAtFullOutput > 1.5 * fluxMade/fluxAtFullOutput, without leaving integers.
			assertTrue(2L*gasSpent*fluxAtFullOutput > 3L*fluxMade*gasAtFullOutput,
					"a cold start is not costing enough to be worth avoiding");
		}
	}

	@Nested
	@DisplayName("fuel table")
	class Fuels
	{
		@Test
		@DisplayName("it burns gas, and only gas")
		void gasOnly()
		{
			assertTrue(TileEntityGasTurbine.isValidFuel("natural_gas"));
			assertTrue(TileEntityGasTurbine.isValidFuel("propane"));
			//The engine-type split: a turbine is not a compression engine and must not quietly
			//become the best thing to pour diesel into.
			assertFalse(TileEntityGasTurbine.isValidFuel("ie_diesel"));
			assertFalse(TileEntityGasTurbine.isValidFuel("ie_heavy_fuel_oil"));
			assertFalse(TileEntityGasTurbine.isValidFuel("gasoline"));
			assertFalse(TileEntityGasTurbine.isValidFuel("naphtha"));
			assertFalse(TileEntityGasTurbine.isValidFuel("crude_oil"));
			assertFalse(TileEntityGasTurbine.isValidFuel("biodiesel"));
			assertFalse(TileEntityGasTurbine.isValidFuel("water"));
			assertFalse(TileEntityGasTurbine.isValidFuel((String)null));
			assertEquals(0, TileEntityGasTurbine.getFullLoadFlow("ie_diesel"));
		}

		@Test
		@DisplayName("propane stays the better gas, as it is everywhere else")
		void propaneIsBetter()
		{
			int gas = TileEntityGasTurbine.getFullLoadFlow("natural_gas");
			int propane = TileEntityGasTurbine.getFullLoadFlow("propane");
			assertEquals(TileEntityGasTurbine.FULL_LOAD_FLOW_NATURAL_GAS, gas);
			assertEquals(TileEntityGasTurbine.FULL_LOAD_FLOW_PROPANE, propane);
			//Less gas for the same power is more flux per millibucket.
			assertTrue(propane < gas);
		}

		@Test
		@DisplayName("at full output a millibucket goes further here than in a diesel generator")
		void betterThanAGenerator()
		{
			//A Diesel Generator makes 4096 on 1000/burnTime millibuckets a tick: five of natural
			//gas, four of propane, at the burn times IEContent registers. Beating that at full
			//output is the payoff for accepting the spool -- and it is only collected by a machine
			//that is left running, which is the entire trade.
			assertTrue(MAX_OUTPUT/TileEntityGasTurbine.getFullLoadFlow("natural_gas") > 4096/5);
			assertTrue(MAX_OUTPUT/TileEntityGasTurbine.getFullLoadFlow("propane") > 4096/4);
		}

		@Test
		@DisplayName("the tank is a buffer against a stuttering line, not a way to run it from buckets")
		void tankDepth()
		{
			int ticks = TileEntityGasTurbine.TANK_CAPACITY/TileEntityGasTurbine.FULL_LOAD_FLOW_NATURAL_GAS;
			assertTrue(ticks >= 20*60, "a full tank should be worth minutes, not seconds");
			assertTrue(ticks <= 20*60*10, "a tank that deep would make the gas line pointless");
		}
	}

	@Nested
	@DisplayName("registration")
	class Registration
	{
		@AfterEach
		void tearDown()
		{
			TileEntityGasTurbine.registerFuel("test_gas", 0);
		}

		@Test
		@DisplayName("a gas can be added and taken away again")
		void addAndRemove()
		{
			assertFalse(TileEntityGasTurbine.isValidFuel("test_gas"));
			TileEntityGasTurbine.registerFuel("test_gas", 7);
			assertEquals(7, TileEntityGasTurbine.getFullLoadFlow("test_gas"));
			TileEntityGasTurbine.registerFuel("test_gas", 0);
			assertFalse(TileEntityGasTurbine.isValidFuel("test_gas"));
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the package is three high, six long and three wide")
		void size()
		{
			assertEquals(3, HEIGHT);
			assertEquals(6, DEPTH);
			assertEquals(3, WIDTH);
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockGasTurbine.blockCount(SHELL)
							+MultiblockGasTurbine.blockCount(MESH)
							+MultiblockGasTurbine.blockCount(CORE)
							+MultiblockGasTurbine.blockCount(ALTERNATOR)
							+MultiblockGasTurbine.blockCount(FRAME)
							+MultiblockGasTurbine.blockCount(EMPTY));
		}

		@Test
		@DisplayName("intake house, nacelle and exhaust stack sit in a line front to back")
		void theThreeParts()
		{
			//The filter house: two courses of mesh across the front, bar the fuel skid.
			for(int w = 0; w < WIDTH; w++)
			{
				assertEquals(MESH, MultiblockGasTurbine.shapeAt(HEIGHT-1, 0, w), "filter house top "+w);
				assertEquals(w==WIDTH/2?FRAME: MESH, MultiblockGasTurbine.shapeAt(1, 0, w),
						"filter house "+w);
			}
			//The nacelle: rotating machinery down the middle, cased either side.
			for(int l = 1; l <= 3; l++)
			{
				assertEquals(CORE, MultiblockGasTurbine.shapeAt(1, l, WIDTH/2), "nacelle core "+l);
				assertEquals(SHELL, MultiblockGasTurbine.shapeAt(1, l, 0), "nacelle casing "+l);
			}
			//The alternator at the cold end of the shaft.
			assertEquals(ALTERNATOR,
					MultiblockGasTurbine.shapeAt(1, MultiblockGasTurbine.TERMINAL_DEPTH, WIDTH/2));
			//The stack, standing back up to full height behind it.
			assertEquals(FRAME, MultiblockGasTurbine.shapeAt(HEIGHT-1, DEPTH-1, WIDTH/2));
			assertEquals(SHELL, MultiblockGasTurbine.shapeAt(HEIGHT-1, DEPTH-1, 0));
		}

		@Test
		@DisplayName("the skid is one continuous raft under the whole package")
		void skidIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(SHELL, MultiblockGasTurbine.shapeAt(0, l, w), "skid cell "+l+","+w);
		}

		@Test
		@DisplayName("the course over the nacelle is open, which is where the flux leaves")
		void terminalDeckIsOpen()
		{
			for(int l = 1; l < DEPTH-1; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					assertEquals(EMPTY, MultiblockGasTurbine.shapeAt(HEIGHT-1, l, w),
							"deck cell "+l+","+w);
					assertFalse(MultiblockGasTurbine.isPart(HEIGHT-1, l, w));
				}
			//And the three terminals are exactly the cells under the open part of that deck.
			for(int w = 0; w < WIDTH; w++)
			{
				assertEquals(PetroleumGeometry.structureIndex(MultiblockGasTurbine.SIZE, 1,
						MultiblockGasTurbine.TERMINAL_DEPTH, w), MultiblockGasTurbine.terminalPos(w));
				assertEquals(1, PetroleumGeometry.heightOf(MultiblockGasTurbine.SIZE,
						MultiblockGasTurbine.terminalPos(w)));
				assertEquals(EMPTY, MultiblockGasTurbine.shapeAt(HEIGHT-1,
						MultiblockGasTurbine.TERMINAL_DEPTH, w));
			}
		}

		@Test
		@DisplayName("the master is the fuel skid and the plume leaves the stack")
		void namedPositions()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockGasTurbine.SIZE, 1, 0, 1),
					MultiblockGasTurbine.MASTER_POS);
			assertEquals(PetroleumGeometry.structureIndex(MultiblockGasTurbine.SIZE, HEIGHT-1, DEPTH-1, 1),
					MultiblockGasTurbine.STACK_POS);
			assertEquals(FRAME, MultiblockGasTurbine.shapeAt(1, 0, 1));
			assertEquals(FRAME, MultiblockGasTurbine.shapeAt(HEIGHT-1, DEPTH-1, 1));
		}

		@Test
		@DisplayName("exactly the two frame cells can be hammered, since only frame is a trigger")
		void frameIsReachable()
		{
			assertEquals(2, MultiblockGasTurbine.blockCount(FRAME));
		}

		@Test
		@DisplayName("every course is symmetric across the width, so there is no mirrored variant")
		void noMirroredVariant()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockGasTurbine.shapeAt(h, l, w),
								MultiblockGasTurbine.shapeAt(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("anything outside the box is not part of the machine")
		void outsideTheBox()
		{
			assertEquals('?', MultiblockGasTurbine.shapeAt(-1, 0, 0));
			assertEquals('?', MultiblockGasTurbine.shapeAt(HEIGHT, 0, 0));
			assertEquals('?', MultiblockGasTurbine.shapeAt(0, DEPTH, 0));
			assertEquals('?', MultiblockGasTurbine.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockGasTurbine.isPart(-1, 0, 0));
			assertFalse(MultiblockGasTurbine.isPart(0, 0, WIDTH));
		}
	}

	@Nested
	@DisplayName("fuel ports")
	class Ports
	{
		@Test
		@DisplayName("the whole skid course takes gas")
		void skidTakesGas()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityGasTurbine.isFuelPort(
							PetroleumGeometry.structureIndex(MultiblockGasTurbine.SIZE, 0, l, w)),
							"skid cell "+l+","+w);
		}

		@Test
		@DisplayName("so does the fuel skid, which is where a gas line is meant to land")
		void skidHeadTakesGas()
		{
			assertTrue(TileEntityGasTurbine.isFuelPort(MultiblockGasTurbine.MASTER_POS));
		}

		@Test
		@DisplayName("the alternator end does not, because that is the electrical end")
		void terminalsDoNot()
		{
			for(int w = 0; w < WIDTH; w++)
				assertFalse(TileEntityGasTurbine.isFuelPort(MultiblockGasTurbine.terminalPos(w)),
						"terminal "+w);
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and takes nothing")
		void unformedTakesNothing()
		{
			assertFalse(TileEntityGasTurbine.isFuelPort(-1));
		}
	}
}
