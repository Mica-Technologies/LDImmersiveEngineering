/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockIndustrialBurner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockIndustrialBurner.BRICK;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockIndustrialBurner.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockIndustrialBurner.SHELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Industrial Burner's shape and its fuel table.
 * <p>
 * Both fail quietly. A wrong cell in the shape means a machine that refuses to form when
 * hammered, with no message and nothing in the log. A wrong number in the fuel table means the
 * fuel hierarchy silently inverts -- if diesel out-burns heavy fuel oil here then the residue at
 * the bottom of the column has no consumer at all and the burner is just a worse generator,
 * which is the one thing the machine exists not to be.
 * <p>
 * Scope note: everything else the burner does needs a {@code World}, a {@code FluidStack} or a
 * neighbouring {@code TileEntity}, none of which can be constructed here, so the stoking pass,
 * the store and the furnace path are not reachable from a unit test.
 */
class IndustrialBurnerTest
{
	private static final int HEIGHT = PetroleumGeometry.BURNER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.BURNER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.BURNER_WIDTH;

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the burner is a three by three by three box with nothing left out")
		void size()
		{
			assertEquals(3, HEIGHT);
			assertEquals(3, DEPTH);
			assertEquals(3, WIDTH);
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockIndustrialBurner.blockCount(BRICK)
							+MultiblockIndustrialBurner.blockCount(SHELL)
							+MultiblockIndustrialBurner.blockCount(FRAME));
		}

		@Test
		@DisplayName("the hearth is solid refractory brick")
		void hearthIsBrick()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(BRICK, MultiblockIndustrialBurner.shapeAt(0, l, w),
							"hearth cell "+l+","+w);
		}

		@Test
		@DisplayName("the combustion chamber is lined all round bar the burner head at the front")
		void chamberIsLined()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					char expected = l==0&&w==WIDTH/2?FRAME: BRICK;
					assertEquals(expected, MultiblockIndustrialBurner.shapeAt(1, l, w),
							"chamber cell "+l+","+w);
				}
		}

		@Test
		@DisplayName("the crown is sheetmetal, with the flue in the middle of it")
		void crownIsShell()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					char expected = l==DEPTH/2&&w==WIDTH/2?FRAME: SHELL;
					assertEquals(expected, MultiblockIndustrialBurner.shapeAt(HEIGHT-1, l, w),
							"crown cell "+l+","+w);
				}
		}

		@Test
		@DisplayName("exactly the two frame cells can be hammered, since only frame is a trigger")
		void frameIsReachable()
		{
			assertEquals(2, MultiblockIndustrialBurner.blockCount(FRAME));
			assertEquals(FRAME, MultiblockIndustrialBurner.shapeAt(1, 0, 1));
			assertEquals(FRAME, MultiblockIndustrialBurner.shapeAt(2, 1, 1));
		}

		@Test
		@DisplayName("the master is the burner head and the flue is the middle of the crown")
		void namedPositions()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockIndustrialBurner.SIZE, 1, 0, 1),
					MultiblockIndustrialBurner.MASTER_POS);
			assertEquals(PetroleumGeometry.structureIndex(MultiblockIndustrialBurner.SIZE, 2, 1, 1),
					MultiblockIndustrialBurner.FLUE_POS);
			assertEquals(1, PetroleumGeometry.heightOf(MultiblockIndustrialBurner.SIZE,
					MultiblockIndustrialBurner.MASTER_POS));
		}

		@Test
		@DisplayName("anything outside the box is not part of the machine")
		void outsideTheBox()
		{
			assertEquals('.', MultiblockIndustrialBurner.shapeAt(-1, 0, 0));
			assertEquals('.', MultiblockIndustrialBurner.shapeAt(HEIGHT, 0, 0));
			assertEquals('.', MultiblockIndustrialBurner.shapeAt(0, DEPTH, 0));
			assertEquals('.', MultiblockIndustrialBurner.shapeAt(0, 0, WIDTH));
		}
	}

	@Nested
	@DisplayName("fuel table")
	class Fuels
	{
		@Test
		@DisplayName("heavy fuel oil is the best thing to put in, which is the whole machine")
		void heavyFuelOilWins()
		{
			int hfo = TileEntityIndustrialBurner.getHeatPerBucket("ie_heavy_fuel_oil");
			assertTrue(hfo > TileEntityIndustrialBurner.getHeatPerBucket("ie_diesel"));
			assertTrue(hfo > TileEntityIndustrialBurner.getHeatPerBucket("propane"));
			assertTrue(hfo > TileEntityIndustrialBurner.getHeatPerBucket("natural_gas"));
		}

		@Test
		@DisplayName("the table runs in order of energy density")
		void densityOrder()
		{
			assertTrue(TileEntityIndustrialBurner.getHeatPerBucket("natural_gas")
					< TileEntityIndustrialBurner.getHeatPerBucket("propane"));
			assertTrue(TileEntityIndustrialBurner.getHeatPerBucket("propane")
					< TileEntityIndustrialBurner.getHeatPerBucket("ie_diesel"));
			assertTrue(TileEntityIndustrialBurner.getHeatPerBucket("ie_diesel")
					< TileEntityIndustrialBurner.getHeatPerBucket("ie_heavy_fuel_oil"));
		}

		@Test
		@DisplayName("all four intended fuels are accepted")
		void theFourFuels()
		{
			assertTrue(TileEntityIndustrialBurner.isValidFuel("ie_heavy_fuel_oil"));
			assertTrue(TileEntityIndustrialBurner.isValidFuel("ie_diesel"));
			assertTrue(TileEntityIndustrialBurner.isValidFuel("propane"));
			assertTrue(TileEntityIndustrialBurner.isValidFuel("natural_gas"));
		}

		@Test
		@DisplayName("nothing else is, so a burner is not a way to dispose of crude or water")
		void everythingElseIsRejected()
		{
			assertFalse(TileEntityIndustrialBurner.isValidFuel("crude_oil"));
			assertFalse(TileEntityIndustrialBurner.isValidFuel("gasoline"));
			assertFalse(TileEntityIndustrialBurner.isValidFuel("bitumen"));
			assertFalse(TileEntityIndustrialBurner.isValidFuel("water"));
			assertFalse(TileEntityIndustrialBurner.isValidFuel((String)null));
			assertEquals(0, TileEntityIndustrialBurner.getHeatPerBucket("water"));
		}
	}

	@Nested
	@DisplayName("heat arithmetic")
	class Heat
	{
		@Test
		@DisplayName("the heat rate is the fuel's density at the burner's firing rate")
		void rateFollowsDensity()
		{
			for(String fuel : new String[]{"natural_gas", "propane", "ie_diesel", "ie_heavy_fuel_oil"})
				assertEquals(TileEntityIndustrialBurner.getHeatPerBucket(fuel)
								*TileEntityIndustrialBurner.FIRING_RATE/1000,
						TileEntityIndustrialBurner.getHeatRateFor(fuel), fuel);
		}

		@Test
		@DisplayName("a fuel the burner will not take sustains nothing")
		void unknownFuelSustainsNothing()
		{
			assertEquals(0, TileEntityIndustrialBurner.getHeatRateFor("water"));
			assertEquals(0, TileEntityIndustrialBurner.heatFromBurning(0, 1000));
			assertEquals(0, TileEntityIndustrialBurner.heatFromBurning(
					TileEntityIndustrialBurner.HEAT_PER_BUCKET_DIESEL, 0));
		}

		@Test
		@DisplayName("a bucket yields exactly its listed heat")
		void aBucketIsABucket()
		{
			assertEquals(TileEntityIndustrialBurner.HEAT_PER_BUCKET_HEAVY_FUEL_OIL,
					TileEntityIndustrialBurner.heatFromBurning(
							TileEntityIndustrialBurner.HEAT_PER_BUCKET_HEAVY_FUEL_OIL, 1000));
		}

		@Test
		@DisplayName("one stoking pass is one interval of firing, and no more")
		void passMatchesInterval()
		{
			assertEquals(TileEntityIndustrialBurner.FIRING_RATE*TileEntityIndustrialBurner.BURN_INTERVAL,
					TileEntityIndustrialBurner.CHARGE);
			assertEquals(TileEntityIndustrialBurner.getHeatRateFor("ie_heavy_fuel_oil")
							*TileEntityIndustrialBurner.BURN_INTERVAL,
					TileEntityIndustrialBurner.heatFromBurning(
							TileEntityIndustrialBurner.HEAT_PER_BUCKET_HEAVY_FUEL_OIL,
							TileEntityIndustrialBurner.CHARGE));
		}

		@Test
		@DisplayName("the store holds two passes of the best fuel, so an idle burner stops stoking")
		void storeHoldsTwoPasses()
		{
			//The store is the whole demand-gating mechanism: if a full pass could never fit twice
			//over, a burner nobody draws from would either never stop burning or never start.
			assertEquals(2*TileEntityIndustrialBurner.heatFromBurning(
							TileEntityIndustrialBurner.HEAT_PER_BUCKET_HEAVY_FUEL_OIL,
							TileEntityIndustrialBurner.CHARGE),
					TileEntityIndustrialBurner.HEAT_CAPACITY);
		}
	}

	@Nested
	@DisplayName("fuel ports")
	class Ports
	{
		@Test
		@DisplayName("the whole hearth course takes fuel")
		void hearthTakesFuel()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityIndustrialBurner.isFuelPort(
							PetroleumGeometry.structureIndex(MultiblockIndustrialBurner.SIZE, 0, l, w)),
							"hearth cell "+l+","+w);
		}

		@Test
		@DisplayName("so does the burner head, which is where a fuel line is meant to land")
		void headTakesFuel()
		{
			assertTrue(TileEntityIndustrialBurner.isFuelPort(MultiblockIndustrialBurner.MASTER_POS));
		}

		@Test
		@DisplayName("the crown does not, because that is the face things are heated on")
		void crownDoesNot()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertFalse(TileEntityIndustrialBurner.isFuelPort(
							PetroleumGeometry.structureIndex(MultiblockIndustrialBurner.SIZE, HEIGHT-1, l, w)),
							"crown cell "+l+","+w);
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and takes nothing")
		void unformedTakesNothing()
		{
			assertFalse(TileEntityIndustrialBurner.isFuelPort(-1));
		}
	}

	@Nested
	@DisplayName("registration")
	class Registration
	{
		@AfterEach
		void tearDown()
		{
			TileEntityIndustrialBurner.registerFuel("test_fuel", 0);
		}

		@Test
		@DisplayName("a fuel can be added and taken away again")
		void addAndRemove()
		{
			assertFalse(TileEntityIndustrialBurner.isValidFuel("test_fuel"));
			TileEntityIndustrialBurner.registerFuel("test_fuel", 9000);
			assertEquals(9000, TileEntityIndustrialBurner.getHeatPerBucket("test_fuel"));
			TileEntityIndustrialBurner.registerFuel("test_fuel", 0);
			assertFalse(TileEntityIndustrialBurner.isValidFuel("test_fuel"));
		}
	}
}
