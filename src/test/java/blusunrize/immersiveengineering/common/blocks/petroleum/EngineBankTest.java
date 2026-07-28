/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.ALTERNATOR;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.CYLINDER;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.EMPTY;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.SHELL;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank.TRUSS;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.BANK_OUTPUT;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.ENERGY_PER_BANK_PASS;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.MAX_CHAIN;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.MAX_CHAIN_BONUS_PERMILLE;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.WORK_INTERVAL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.chainBonusPermille;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.chainOutput;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.fuelPerBankPass;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank.getFluxPerMillibucket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Reciprocating Engine Bank's arithmetic, its fuel table and its shape.
 * <p>
 * Four machines in this section share one scale of numbers, and this one's job in that scale is to
 * be the cheap, instant, inefficient option. Every one of those three words is a number here: six
 * thousand Flux for one bank, a full pass with no ramp state anywhere in the maths, and a fuel
 * table that is deliberately worse per millibucket than the combined-cycle plant. A drift in any of
 * them would still run, still form and still look right in a game -- it would simply stop being
 * the machine the section was balanced around, and nothing at runtime would say so.
 * <p>
 * The linking arithmetic is here for a sharper reason. The chain bonus multiplies the output of a
 * whole hall, so an off-by-one in the bank count is an off-by-six-thousand in Flux, and a bonus
 * that failed to cap would make a long enough row of engines free power. Only the maths is
 * reachable from here -- resolving an actual chain needs a {@code World}, neighbouring tile
 * entities and a {@code FluidStack}, none of which can be constructed without a Minecraft
 * bootstrap -- so the hazards the resolution itself guards against are argued in the class
 * javadoc rather than asserted here.
 * <p>
 * The shape fails quietly in the other direction: a wrong cell means a building that refuses to
 * form when hammered, with no message and nothing in the log.
 */
class EngineBankTest
{
	private static final int HEIGHT = PetroleumGeometry.ENGINE_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.ENGINE_DEPTH;
	private static final int WIDTH = PetroleumGeometry.ENGINE_WIDTH;

	@Nested
	@DisplayName("output")
	class Output
	{
		@Test
		@DisplayName("one bank is exactly six thousand Flux, with no bonus of any kind")
		void oneBank()
		{
			assertEquals(6000, BANK_OUTPUT);
			assertEquals(6000, chainOutput(1));
			assertEquals(0, chainBonusPermille(1));
		}

		/**
		 * A hall that could not be told apart from the same engines scattered around a base would
		 * make the linking pure bookkeeping, which is not worth the code it takes.
		 */
		@Test
		@DisplayName("each extra bank is worth two per cent to the whole installation")
		void bonusPerBank()
		{
			for(int banks = 1; banks <= MAX_CHAIN; banks++)
				assertEquals((banks-1)*20, chainBonusPermille(banks), banks+" banks");
			//Two banks make more than two separate ones, and by exactly the stated margin.
			assertEquals(2*6000*102/100, chainOutput(2));
			assertTrue(chainOutput(2) > 2*chainOutput(1));
		}

		@Test
		@DisplayName("the bonus caps at fourteen per cent, and the cap is reached at eight banks")
		void bonusCaps()
		{
			assertEquals(8, MAX_CHAIN);
			assertEquals(140, MAX_CHAIN_BONUS_PERMILLE);
			assertEquals(140, chainBonusPermille(MAX_CHAIN));
			assertTrue(chainBonusPermille(MAX_CHAIN-1) < MAX_CHAIN_BONUS_PERMILLE,
					"the cap is being reached before the eighth bank");
			//And it stays capped however long a row someone builds. A bonus that kept climbing
			//would eventually make a line of engines free power.
			for(int banks = MAX_CHAIN; banks < 200; banks++)
			{
				assertEquals(MAX_CHAIN_BONUS_PERMILLE, chainBonusPermille(banks), banks+" banks");
				assertEquals(chainOutput(MAX_CHAIN), chainOutput(banks), banks+" banks");
			}
			assertEquals(54720, chainOutput(MAX_CHAIN));
		}

		/**
		 * A chain resolved during a chunk load can legitimately come back with nothing in it, and
		 * a plant that answered "some" to "how many engines are running" would be making power out
		 * of a building that is not there.
		 */
		@Test
		@DisplayName("no banks, or a nonsensical number of them, produces nothing")
		void nothingFromNothing()
		{
			assertEquals(0, chainOutput(0));
			assertEquals(0, chainOutput(-1));
			assertEquals(0, chainOutput(Integer.MIN_VALUE));
			assertEquals(0, chainBonusPermille(0));
			assertEquals(0, chainBonusPermille(-5));
		}

		@Test
		@DisplayName("output never falls as banks are added")
		void monotonic()
		{
			int previous = 0;
			for(int banks = 0; banks <= MAX_CHAIN*2; banks++)
			{
				int output = chainOutput(banks);
				assertTrue(output >= previous, "output fell at "+banks+" banks");
				previous = output;
			}
		}

		/**
		 * The machine's entire selling point against the turbine. There is no spool counter, no
		 * warm-up and no partial state: one pass accounts for one whole interval at full output,
		 * which is what makes {@link TileEntityEngineBank#chainOutput} a function of a bank count
		 * and of nothing else.
		 */
		@Test
		@DisplayName("a pass is a whole interval at full output -- there is nothing to ramp")
		void instantResponse()
		{
			assertEquals(10, WORK_INTERVAL);
			assertEquals(BANK_OUTPUT*WORK_INTERVAL, ENERGY_PER_BANK_PASS);
			assertEquals(60000, ENERGY_PER_BANK_PASS);
		}
	}

	@Nested
	@DisplayName("fuel table")
	class Fuels
	{
		@Test
		@DisplayName("diesel, biodiesel and gas, and nothing else")
		void whatItBurns()
		{
			assertTrue(TileEntityEngineBank.isValidFuel("ie_diesel"));
			assertTrue(TileEntityEngineBank.isValidFuel("biodiesel"));
			assertTrue(TileEntityEngineBank.isValidFuel("natural_gas"));
			//The heavy end belongs in the boiler, which is the machine that exists to make heavy
			//fuel oil worth anything; a reciprocating engine that took it would take that away.
			assertFalse(TileEntityEngineBank.isValidFuel("ie_heavy_fuel_oil"));
			assertFalse(TileEntityEngineBank.isValidFuel("crude_oil"));
			assertFalse(TileEntityEngineBank.isValidFuel("gasoline"));
			assertFalse(TileEntityEngineBank.isValidFuel("naphtha"));
			assertFalse(TileEntityEngineBank.isValidFuel("ie_lubricant"));
			assertFalse(TileEntityEngineBank.isValidFuel("water"));
			assertFalse(TileEntityEngineBank.isValidFuel((String)null));
			assertEquals(0, getFluxPerMillibucket("water"));
			assertEquals(0, getFluxPerMillibucket(null));
		}

		@Test
		@DisplayName("refined beats grown beats gas, and by the margins the section was balanced on")
		void ordering()
		{
			assertEquals(300, getFluxPerMillibucket("ie_diesel"));
			assertEquals(240, getFluxPerMillibucket("biodiesel"));
			assertEquals(150, getFluxPerMillibucket("natural_gas"));
			assertTrue(getFluxPerMillibucket("ie_diesel") > getFluxPerMillibucket("biodiesel"));
			assertTrue(getFluxPerMillibucket("biodiesel") > getFluxPerMillibucket("natural_gas"));
		}

		/**
		 * The whole point of the machine. It is the instant one, so it has to be the wasteful one,
		 * or nothing else in the section has a reason to exist.
		 */
		@Test
		@DisplayName("even its best fuel is worse than the combined-cycle plant's five hundred")
		void deliberatelyInefficient()
		{
			int combinedCycle = 500;
			assertTrue(getFluxPerMillibucket("ie_diesel") < combinedCycle);
			assertTrue(getFluxPerMillibucket("biodiesel") < combinedCycle);
			assertTrue(getFluxPerMillibucket("natural_gas") < combinedCycle);
		}

		@Test
		@DisplayName("a pass costs what the fuel table says it does")
		void costPerPass()
		{
			assertEquals(200, fuelPerBankPass(getFluxPerMillibucket("ie_diesel")));
			assertEquals(250, fuelPerBankPass(getFluxPerMillibucket("biodiesel")));
			assertEquals(400, fuelPerBankPass(getFluxPerMillibucket("natural_gas")));
			//Which is twenty, twenty-five and forty millibuckets a tick.
			assertEquals(20, fuelPerBankPass(getFluxPerMillibucket("ie_diesel"))/WORK_INTERVAL);
		}

		/**
		 * A fluid the engines do not burn has no cost, and an affordability check written as
		 * {@code stored < cost} is false when both are zero. That is not hypothetical: the
		 * industrial burner heated for free forever on exactly this.
		 */
		@Test
		@DisplayName("a fuel the engines will not take has no price, so it can never be afforded")
		void noFreeRun()
		{
			assertEquals(0, fuelPerBankPass(0));
			assertEquals(0, fuelPerBankPass(-1));
			assertEquals(0, fuelPerBankPass(getFluxPerMillibucket("water")));
		}

		/**
		 * Rounded up rather than truncated: a fuel whose Flux value does not divide the pass
		 * exactly would otherwise be sold at a discount, and the discount would grow the worse the
		 * fuel was.
		 */
		@Test
		@DisplayName("a pass is never sold for less fuel than it is worth")
		void roundedUp()
		{
			for(int fluxPerMb = 1; fluxPerMb <= 1000; fluxPerMb++)
				assertTrue((long)fuelPerBankPass(fluxPerMb)*fluxPerMb >= ENERGY_PER_BANK_PASS,
						"a pass on "+fluxPerMb+" Flux per mB is being sold short");
			//7 does not divide 60000: 8572 millibuckets, not 8571.
			assertEquals(8572, fuelPerBankPass(7));
		}
	}

	@Nested
	@DisplayName("registration")
	class Registration
	{
		@AfterEach
		void tearDown()
		{
			TileEntityEngineBank.registerFuel("test_oil", 0);
		}

		@Test
		@DisplayName("a fuel can be added and taken away again")
		void addAndRemove()
		{
			assertFalse(TileEntityEngineBank.isValidFuel("test_oil"));
			TileEntityEngineBank.registerFuel("test_oil", 42);
			assertEquals(42, getFluxPerMillibucket("test_oil"));
			TileEntityEngineBank.registerFuel("test_oil", 0);
			assertFalse(TileEntityEngineBank.isValidFuel("test_oil"));
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("one bank is four high, five deep and five wide")
		void size()
		{
			assertEquals(4, HEIGHT);
			assertEquals(5, DEPTH);
			assertEquals(5, WIDTH);
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockEngineBank.blockCount(SHELL)
							+MultiblockEngineBank.blockCount(TRUSS)
							+MultiblockEngineBank.blockCount(CYLINDER)
							+MultiblockEngineBank.blockCount(ALTERNATOR)
							+MultiblockEngineBank.blockCount(FRAME)
							+MultiblockEngineBank.blockCount(EMPTY));
		}

		@Test
		@DisplayName("it is a building, not a solid block")
		void notASolidBox()
		{
			assertEquals(75, MultiblockEngineBank.blockCount());
			assertTrue(MultiblockEngineBank.blockCount() < HEIGHT*DEPTH*WIDTH,
					"a bank filling its own box would be a crate");
			//The walkway, the open front and the switchyard: the cells that make it walkable.
			assertEquals(25, MultiblockEngineBank.blockCount(EMPTY));
		}

		/**
		 * The linking design's whole justification. The cylinders, the fuel gallery, the walkway
		 * and the roof all run across the width, so butting a second bank against the width face
		 * continues each of them rather than starting a second one -- which is the difference
		 * between one long engine house and two sheds that happen to share a wall.
		 */
		@Test
		@DisplayName("every course runs across the width, so a linked bank continues it")
		void coursesRunAcrossTheWidth()
		{
			for(int w = 0; w < WIDTH; w++)
			{
				assertEquals(FRAME, MultiblockEngineBank.shapeAt(1, 0, w), "fuel gallery "+w);
				assertEquals(EMPTY, MultiblockEngineBank.shapeAt(1, 1, w), "walkway "+w);
				assertEquals(CYLINDER, MultiblockEngineBank.shapeAt(1,
						MultiblockEngineBank.CYLINDER_DEPTH, w), "cylinder "+w);
				assertEquals(TRUSS, MultiblockEngineBank.shapeAt(HEIGHT-1, 0, w), "roof "+w);
			}
			//And both ends of the cylinder row are cylinders, so a seam between two banks falls
			//between two of them and the row reads unbroken.
			assertEquals(CYLINDER, MultiblockEngineBank.shapeAt(1, MultiblockEngineBank.CYLINDER_DEPTH, 0));
			assertEquals(CYLINDER,
					MultiblockEngineBank.shapeAt(1, MultiblockEngineBank.CYLINDER_DEPTH, WIDTH-1));
		}

		@Test
		@DisplayName("the raft is one continuous foundation under the whole building")
		void raftIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(SHELL, MultiblockEngineBank.shapeAt(0, l, w), "raft cell "+l+","+w);
		}

		/**
		 * The two cells the linking check reads. They have to be on the raft, because that is the
		 * one course guaranteed to be solid all the way across whatever else is built around the
		 * bank, and they have to be a full width apart, because two flush banks put them exactly
		 * one block from each other across the seam.
		 */
		@Test
		@DisplayName("the mating cells are the two ends of the front raft row")
		void matingCells()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 0, 0, 0),
					MultiblockEngineBank.MATING_LOW);
			assertEquals(PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 0, 0, WIDTH-1),
					MultiblockEngineBank.MATING_HIGH);
			assertEquals(0, MultiblockEngineBank.MATING_LOW);
			assertEquals(WIDTH-1, MultiblockEngineBank.MATING_HIGH-MultiblockEngineBank.MATING_LOW);
			assertTrue(MultiblockEngineBank.isPart(0, 0, 0));
			assertTrue(MultiblockEngineBank.isPart(0, 0, WIDTH-1));
		}

		@Test
		@DisplayName("the switchyard is open above every alternator, which is where the flux leaves")
		void switchyardIsOpen()
		{
			assertEquals(3, MultiblockEngineBank.TERMINAL_COUNT);
			assertEquals(MultiblockEngineBank.TERMINAL_COUNT,
					MultiblockEngineBank.blockCount(ALTERNATOR));
			for(int t = 0; t < MultiblockEngineBank.TERMINAL_COUNT; t++)
			{
				int terminal = MultiblockEngineBank.terminalPos(t);
				assertEquals(PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 1,
						MultiblockEngineBank.TERMINAL_DEPTH, t*2), terminal);
				assertEquals(1, PetroleumGeometry.heightOf(MultiblockEngineBank.SIZE, terminal));
				assertEquals(ALTERNATOR, MultiblockEngineBank.shapeAt(1,
						MultiblockEngineBank.TERMINAL_DEPTH, t*2), "alternator "+t);
				//The cell the connector stands in has to be open, or there would be nowhere to put
				//one and the building could never deliver anything at all.
				assertEquals(EMPTY, MultiblockEngineBank.shapeAt(2,
						MultiblockEngineBank.TERMINAL_DEPTH, t*2), "switchyard "+t);
			}
			//And the whole bay is open, roof included, so the switchyard is reachable from outside.
			for(int w = 0; w < WIDTH; w++)
				for(int h = 2; h < HEIGHT; h++)
					assertEquals(EMPTY,
							MultiblockEngineBank.shapeAt(h, MultiblockEngineBank.TERMINAL_DEPTH, w),
							"switchyard bay "+h+","+w);
		}

		@Test
		@DisplayName("a stack comes through the roof above every second cylinder")
		void stacks()
		{
			assertEquals(3, MultiblockEngineBank.STACK_COUNT);
			for(int s = 0; s < MultiblockEngineBank.STACK_COUNT; s++)
			{
				int stack = MultiblockEngineBank.stackPos(s);
				assertEquals(HEIGHT-1, PetroleumGeometry.heightOf(MultiblockEngineBank.SIZE, stack));
				assertEquals(FRAME, MultiblockEngineBank.shapeAt(HEIGHT-1,
						MultiblockEngineBank.CYLINDER_DEPTH, s*2), "stack "+s);
			}
		}

		@Test
		@DisplayName("the master and the comparator face are two different blocks of the front")
		void namedPositions()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 1, 0, WIDTH/2),
					MultiblockEngineBank.MASTER_POS);
			assertEquals(PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 1, 0, 0),
					MultiblockEngineBank.REDSTONE_POS);
			assertEquals(MultiblockEngineBank.REDSTONE_POS, TileEntityEngineBank.REDSTONE_INDEX);
			assertTrue(MultiblockEngineBank.MASTER_POS!=MultiblockEngineBank.REDSTONE_POS);
			//Both are gallery blocks, so both are on the face a player walks up to.
			assertEquals(FRAME, MultiblockEngineBank.shapeAt(1, 0, WIDTH/2));
			assertEquals(FRAME, MultiblockEngineBank.shapeAt(1, 0, 0));
		}

		/**
		 * The property that decides whether the structure is buildable at all: every block has to
		 * be placeable against one that is already there. A floating cell cannot be reached with a
		 * right-click, so a structure containing one can never be completed by hand and the only
		 * symptom is that hammering it does nothing.
		 */
		@Test
		@DisplayName("every block can be placed against one already standing")
		void nothingFloats()
		{
			Set<Integer> placed = new HashSet<>();
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(MultiblockEngineBank.isPart(0, l, w))
						placed.add(key(0, l, w));

			boolean grew = true;
			while(grew)
			{
				grew = false;
				for(int h = 0; h < HEIGHT; h++)
					for(int l = 0; l < DEPTH; l++)
						for(int w = 0; w < WIDTH; w++)
						{
							if(!MultiblockEngineBank.isPart(h, l, w)||placed.contains(key(h, l, w)))
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
						if(MultiblockEngineBank.isPart(h, l, w))
							assertTrue(placed.contains(key(h, l, w)),
									"nothing to place against at "+h+","+l+","+w);
		}

		/**
		 * Symmetry matters more here than on any other structure in the expansion. Linking compares
		 * two banks' handedness, so a shape that could form mirrored would give a player two
		 * adjacent halls that look identical, refuse to link, and say nothing about why.
		 */
		@Test
		@DisplayName("every course is symmetric across the width, so there is no mirrored variant")
		void noMirroredVariant()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockEngineBank.shapeAt(h, l, w),
								MultiblockEngineBank.shapeAt(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("anything outside the box is not part of the building")
		void outsideTheBox()
		{
			assertEquals('?', MultiblockEngineBank.shapeAt(-1, 0, 0));
			assertEquals('?', MultiblockEngineBank.shapeAt(HEIGHT, 0, 0));
			assertEquals('?', MultiblockEngineBank.shapeAt(0, DEPTH, 0));
			assertEquals('?', MultiblockEngineBank.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockEngineBank.isPart(-1, 0, 0));
			assertFalse(MultiblockEngineBank.isPart(0, 0, WIDTH));
			assertFalse(MultiblockEngineBank.isPart(0, 0, -1));
		}

		private int key(int h, int l, int w)
		{
			return (h+1)*10000+(l+1)*100+(w+1);
		}
	}

	@Nested
	@DisplayName("fuel ports")
	class Ports
	{
		@Test
		@DisplayName("the whole raft and the whole fuel gallery take fuel")
		void raftAndGallery()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityEngineBank.isFuelPort(
							PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 0, l, w)),
							"raft cell "+l+","+w);
			for(int w = 0; w < WIDTH; w++)
				assertTrue(TileEntityEngineBank.isFuelPort(
						PetroleumGeometry.structureIndex(MultiblockEngineBank.SIZE, 1, 0, w)),
						"gallery cell "+w);
			assertTrue(TileEntityEngineBank.isFuelPort(MultiblockEngineBank.MASTER_POS));
		}

		@Test
		@DisplayName("the switchyard end does not, because that is the electrical end")
		void terminalsDoNot()
		{
			for(int t = 0; t < MultiblockEngineBank.TERMINAL_COUNT; t++)
				assertFalse(TileEntityEngineBank.isFuelPort(MultiblockEngineBank.terminalPos(t)),
						"terminal "+t);
			for(int s = 0; s < MultiblockEngineBank.STACK_COUNT; s++)
				assertFalse(TileEntityEngineBank.isFuelPort(MultiblockEngineBank.stackPos(s)),
						"stack "+s);
		}

		@Test
		@DisplayName("an unformed block belongs to no building and takes nothing")
		void unformedTakesNothing()
		{
			assertFalse(TileEntityEngineBank.isFuelPort(-1));
		}
	}
}
