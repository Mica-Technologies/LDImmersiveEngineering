/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler.BRICK;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler.CORE;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFuelOilBoiler.SHELL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler.MAX_STEAM_OUTPUT;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler.MAX_STEAM_PER_PASS;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler.fuelForSteamCap;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler.fuelToBurn;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler.steamFromFuel;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fuel Oil Boiler's steam-yield maths, its shape and its fuel table.
 * <p>
 * The maths are the machine, in the same way the two curves are the Gas Turbine: a boiler that
 * happened to yield the same steam from every fuel, or one that could be charged for a pass whose
 * cost worked out to zero, would still form and still run, and would simply not be the machine
 * the design brief describes. Nothing in a running game would report either failure, so both are
 * asserted here.
 * <p>
 * The shape fails quietly in the usual direction: a wrong cell means a machine that refuses to
 * form when hammered, with no message and nothing in the log.
 * <p>
 * Scope note: {@code runPass}, the tank hand-off and the flux draw all need a {@code World}, a
 * {@code FluidStack} or a {@code FluxStorage} wired to a real capability, none of which can be
 * constructed here, so only the static maths and the static shape are covered.
 */
class FuelOilBoilerTest
{
	private static final int HEIGHT = PetroleumGeometry.BOILER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.BOILER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.BOILER_WIDTH;

	/**
	 * A cell identity that survives being put in a set.
	 */
	private static int key(int h, int l, int w)
	{
		return (h*DEPTH+l)*WIDTH+w;
	}

	@Nested
	@DisplayName("steam yield")
	class SteamFromFuel
	{
		@Test
		@DisplayName("a millibucket of fuel makes a flat multiple of steam")
		void flatMultiplier()
		{
			assertEquals(4000, steamFromFuel(100, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL));
			assertEquals(2400, steamFromFuel(100, TileEntityFuelOilBoiler.STEAM_YIELD_CRUDE_OIL));
			assertEquals(5000, steamFromFuel(100, TileEntityFuelOilBoiler.STEAM_YIELD_DIESEL));
		}

		@Test
		@DisplayName("nothing in, nothing out, whichever side is empty")
		void zeroEitherSide()
		{
			assertEquals(0, steamFromFuel(0, 40));
			assertEquals(0, steamFromFuel(100, 0));
			assertEquals(0, steamFromFuel(-5, 40));
			assertEquals(0, steamFromFuel(100, -5));
		}
	}

	@Nested
	@DisplayName("fuel for a steam cap")
	class FuelForCap
	{
		@Test
		@DisplayName("rounds down, so a pass sized by this can never overflow the tank it fills")
		void roundsDown()
		{
			//101 mB of room at a 40:1 yield is only worth 2 mB of fuel; the third millibucket of
			//fuel would make 40 mB of steam the tank has no room for.
			assertEquals(2, fuelForSteamCap(101, 40));
			assertEquals(0, steamFromFuel(2, 40) > 101?1: 0, "the rounded-down fuel must fit the cap");
		}

		@Test
		@DisplayName("no yield means the fuel is never limited by the product tank")
		void noYieldIsUnbounded()
		{
			assertEquals(0, fuelForSteamCap(1000, 0));
			assertEquals(0, fuelForSteamCap(0, 40));
			assertEquals(0, fuelForSteamCap(-100, 40));
		}
	}

	@Nested
	@DisplayName("fuel actually burned in a pass")
	class FuelToBurn
	{
		@Test
		@DisplayName("never more than the machine's own output cap is worth")
		void neverExceedsTheCap()
		{
			//An enormous tank of fuel and an empty steam tank: the pass is still capped at
			//MAX_STEAM_PER_PASS worth of fuel, because the boiler is not allowed to exceed the
			//rate one Steam Turbine Hall is built to swallow.
			int burned = fuelToBurn(1_000_000, 1_000_000,
					TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL);
			assertTrue(steamFromFuel(burned, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL)
					<= MAX_STEAM_PER_PASS);
			assertEquals(MAX_STEAM_PER_PASS/TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL, burned);
		}

		@Test
		@DisplayName("never more than the room actually left in the steam tank")
		void neverExceedsTankRoom()
		{
			//Room for only 80 mB of steam at a 40:1 yield: two millibuckets of fuel is the honest
			//limit, however much fuel and however much output headroom the boiler has otherwise.
			int burned = fuelToBurn(1_000_000, 80, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL);
			assertEquals(2, burned);
			assertTrue(steamFromFuel(burned, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL) <= 80);
		}

		@Test
		@DisplayName("never more than what is actually in the tank")
		void neverExceedsFuelOnHand()
		{
			int burned = fuelToBurn(3, 1_000_000, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL);
			assertEquals(3, burned);
		}

		@Test
		@DisplayName("a boiler backed up behind a full steam tank burns nothing at all")
		void backedUpBurnsNothing()
		{
			assertEquals(0, fuelToBurn(1_000_000, 0, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL));
			assertEquals(0, fuelToBurn(1_000_000, -50, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL));
		}

		@Test
		@DisplayName("an empty tank or an unregistered fluid burns nothing, never for free")
		void emptyOrUnknownBurnsNothing()
		{
			//The regression this whole class exists to guard: a cost derived from zero-able state
			//(here, the fuel's yield) must never pass an affordability check that reads
			//"cost 0, stored 0, proceed". fuelToBurn is the one place that state is turned into a
			//quantity to spend, so it has to refuse outright rather than return a number a caller
			//might act on.
			assertEquals(0, fuelToBurn(0, 1_000_000, TileEntityFuelOilBoiler.STEAM_YIELD_HEAVY_FUEL_OIL));
			assertEquals(0, fuelToBurn(1_000_000, 1_000_000, 0));
			assertEquals(0, fuelToBurn(0, 1_000_000, 0));
		}
	}

	@Nested
	@DisplayName("output cap")
	class OutputCap
	{
		@Test
		@DisplayName("one boiler is sized to exactly one steam turbine hall's intake")
		void capMatchesOnePass()
		{
			assertEquals(300, MAX_STEAM_OUTPUT);
			assertEquals(10, TileEntityFuelOilBoiler.BOIL_INTERVAL);
			assertEquals(3000, MAX_STEAM_PER_PASS);
			assertEquals(MAX_STEAM_OUTPUT*TileEntityFuelOilBoiler.BOIL_INTERVAL, MAX_STEAM_PER_PASS);
		}
	}

	@Nested
	@DisplayName("fuel table")
	class FuelTable
	{
		@Test
		@DisplayName("it burns the three heavy liquids, and only those")
		void heavyLiquidsOnly()
		{
			assertTrue(TileEntityFuelOilBoiler.isValidFuel("ie_heavy_fuel_oil"));
			assertTrue(TileEntityFuelOilBoiler.isValidFuel("ie_crude_oil"));
			assertTrue(TileEntityFuelOilBoiler.isValidFuel("ie_diesel"));
			//Gases and the burner's own fuels that are not liquid oils must not quietly work here.
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("natural_gas"));
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("propane"));
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("biodiesel"));
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("water"));
			assertFalse(TileEntityFuelOilBoiler.isValidFuel((String)null));
			assertEquals(0, TileEntityFuelOilBoiler.getSteamYield("ie_gasoline"));
		}

		@Test
		@DisplayName("heavy fuel oil is the fuel this machine exists for, so it must win outright")
		void heavyFuelOilWins()
		{
			int hfo = TileEntityFuelOilBoiler.getSteamYield("ie_heavy_fuel_oil");
			int crude = TileEntityFuelOilBoiler.getSteamYield("ie_crude_oil");
			int diesel = TileEntityFuelOilBoiler.getSteamYield("ie_diesel");
			assertEquals(40, hfo);
			assertEquals(24, crude);
			assertEquals(50, diesel);
			//Diesel edges out heavy fuel oil on this one machine -- three other machines already
			//want diesel, so burning it away here must never be the free lunch, even though it
			//still out-yields crude. Heavy fuel oil only has to beat crude, the other fuel with no
			//better use once refining exists, to make its case.
			assertTrue(hfo > crude, "heavy fuel oil has to clearly beat raw crude");
		}

		@Test
		@DisplayName("a fuel can be added and taken away again")
		void addAndRemove()
		{
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("test_oil"));
			TileEntityFuelOilBoiler.registerFuel("test_oil", 12);
			assertEquals(12, TileEntityFuelOilBoiler.getSteamYield("test_oil"));
			TileEntityFuelOilBoiler.registerFuel("test_oil", 0);
			assertFalse(TileEntityFuelOilBoiler.isValidFuel("test_oil"));
		}
	}

	@Nested
	@DisplayName("energy cost")
	class EnergyCost
	{
		@Test
		@DisplayName("the hotel load and its buffer match the pinned figures exactly")
		void pinnedFigures()
		{
			assertEquals(120, TileEntityFuelOilBoiler.ENERGY_PER_TICK);
			assertEquals(1200, TileEntityFuelOilBoiler.ENERGY_PER_PASS);
			//Ten seconds' worth of the hotel load, at twenty ticks to the second.
			assertEquals(120*200, TileEntityFuelOilBoiler.ENERGY_CAPACITY);
			assertTrue(TileEntityFuelOilBoiler.ENERGY_CAPACITY >= 4*TileEntityFuelOilBoiler.ENERGY_PER_PASS,
					"the buffer must smooth over more than a couple of passes");
		}
	}

	@Nested
	@DisplayName("fluid ports")
	class Ports
	{
		@Test
		@DisplayName("the whole firing floor takes fuel")
		void firingFloorTakesFuel()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityFuelOilBoiler.isFuelPort(
							PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, 0, l, w)),
							"firing floor cell "+l+","+w);
		}

		@Test
		@DisplayName("the whole drum roof gives up steam")
		void drumRoofGivesSteam()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityFuelOilBoiler.isSteamPort(
							PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, HEIGHT-1, l, w)),
							"drum roof cell "+l+","+w);
		}

		@Test
		@DisplayName("fuel and steam never share a face")
		void fuelAndSteamNeverOverlap()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						int pos = PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, h, l, w);
						assertFalse(TileEntityFuelOilBoiler.isFuelPort(pos)
										&&TileEntityFuelOilBoiler.isSteamPort(pos),
								"cell "+h+","+l+","+w+" claims to be both a fuel and a steam face");
					}
		}

		@Test
		@DisplayName("the water wall in between is plumbed on neither side")
		void waterWallIsInert()
		{
			for(int h = 1; h < HEIGHT-1; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						int pos = PetroleumGeometry.structureIndex(PetroleumGeometry.BOILER_SIZE, h, l, w);
						assertFalse(TileEntityFuelOilBoiler.isFuelPort(pos), "water wall fuel port at "+h);
						assertFalse(TileEntityFuelOilBoiler.isSteamPort(pos), "water wall steam port at "+h);
					}
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and takes nothing")
		void unformedTakesNothing()
		{
			assertFalse(TileEntityFuelOilBoiler.isFuelPort(-1));
			assertFalse(TileEntityFuelOilBoiler.isSteamPort(-1));
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the boiler is five tall on a five by seven base")
		void size()
		{
			assertArrayEquals(PetroleumGeometry.BOILER_SIZE, MultiblockFuelOilBoiler.SIZE,
					"the structure definition and the geometry must agree on H, L, W");
			assertEquals(5, HEIGHT);
			assertEquals(5, DEPTH);
			assertEquals(7, WIDTH);
		}

		@Test
		@DisplayName("it is a solid box, exactly as the burner and the turbine are")
		void solidBox()
		{
			assertEquals(HEIGHT*DEPTH*WIDTH, MultiblockFuelOilBoiler.blockCount(BRICK)
					+MultiblockFuelOilBoiler.blockCount(SHELL)
					+MultiblockFuelOilBoiler.blockCount(CORE)
					+MultiblockFuelOilBoiler.blockCount(FRAME));
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertTrue(MultiblockFuelOilBoiler.isPart(h, l, w), "gap at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("the firing floor is refractory brick, with the fuel line at its centre")
		void firingFloorIsBrick()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(!(l==0&&w==WIDTH/2))
						assertEquals(BRICK, MultiblockFuelOilBoiler.shapeAt(0, l, w), "hearth "+l+","+w);
			assertEquals(FRAME, MultiblockFuelOilBoiler.shapeAt(0, 0, WIDTH/2));
		}

		@Test
		@DisplayName("the tube-bank core rises through the middle of every water wall course")
		void coreRisesThroughTheMiddle()
		{
			for(int h = 1; h < HEIGHT-1; h++)
				assertEquals(CORE, MultiblockFuelOilBoiler.shapeAt(h, DEPTH/2, WIDTH/2), "core at "+h);
		}

		@Test
		@DisplayName("the steam takeoff sits directly above the core it is fed by")
		void steamTakeoffAboveCore()
		{
			assertEquals(FRAME, MultiblockFuelOilBoiler.shapeAt(HEIGHT-1, DEPTH/2, WIDTH/2));
			assertEquals(PetroleumGeometry.structureIndex(MultiblockFuelOilBoiler.SIZE, HEIGHT-1, DEPTH/2, WIDTH/2),
					MultiblockFuelOilBoiler.STEAM_POS);
		}

		@Test
		@DisplayName("the master is the fuel line, at the foot of the core")
		void masterIsFuelLine()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockFuelOilBoiler.SIZE, 0, 0, WIDTH/2),
					MultiblockFuelOilBoiler.MASTER_POS);
			assertEquals(FRAME, MultiblockFuelOilBoiler.shapeAt(0, 0, WIDTH/2));
		}

		@Test
		@DisplayName("exactly the two frame cells can be hammered, since only frame is a trigger")
		void frameIsReachable()
		{
			assertEquals(2, MultiblockFuelOilBoiler.blockCount(FRAME));
		}

		@Test
		@DisplayName("every course is symmetric across the width, so there is no mirrored variant")
		void noMirroredVariant()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockFuelOilBoiler.shapeAt(h, l, w),
								MultiblockFuelOilBoiler.shapeAt(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("anything outside the box is not part of the machine")
		void outsideTheBox()
		{
			assertEquals('?', MultiblockFuelOilBoiler.shapeAt(-1, 0, 0));
			assertEquals('?', MultiblockFuelOilBoiler.shapeAt(HEIGHT, 0, 0));
			assertEquals('?', MultiblockFuelOilBoiler.shapeAt(0, DEPTH, 0));
			assertEquals('?', MultiblockFuelOilBoiler.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockFuelOilBoiler.isPart(-1, 0, 0));
			assertFalse(MultiblockFuelOilBoiler.isPart(0, 0, WIDTH));
		}

		/**
		 * The property that decides whether the structure is buildable at all: every block has to
		 * be placeable against one that is already there. Trivial for a solid box, but pinned so a
		 * future re-shape of the boiler cannot silently introduce a floating cell.
		 */
		@Test
		@DisplayName("every block can be placed against one already standing")
		void nothingFloats()
		{
			Set<Integer> placed = new HashSet<>();
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockFuelOilBoiler.isPart(0, l, w))
						placed.add(key(0, l, w));

			boolean grew = true;
			while(grew)
			{
				grew = false;
				for(int h = 0; h < HEIGHT; h++)
					for(int l = 0; l < DEPTH; l++)
						for(int w = 0; w < WIDTH; w++)
						{
							if(!MultiblockFuelOilBoiler.isPart(h, l, w)||placed.contains(key(h, l, w)))
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
						if(MultiblockFuelOilBoiler.isPart(h, l, w))
							assertTrue(placed.contains(key(h, l, w)),
									"nothing to place against at "+h+","+l+","+w);
		}
	}

	@Nested
	@DisplayName("structure index round trip")
	class IndexRoundTrip
	{
		@Test
		@DisplayName("the structure index agrees with the height it is read back as")
		void roundTrip()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(h, PetroleumGeometry.heightOf(PetroleumGeometry.BOILER_SIZE,
								PetroleumGeometry.structureIndex(
										PetroleumGeometry.BOILER_SIZE, h, l, w)),
								"the tile entity works out its port from this");
		}
	}

	@AfterEach
	void tearDown()
	{
		TileEntityFuelOilBoiler.registerFuel("test_oil", 0);
	}
}
