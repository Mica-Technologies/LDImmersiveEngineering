/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasTurbine;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG.BANK;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG.DRUM;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG.EMPTY;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockHRSG.SHELL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.EXHAUST_STEAM_AT_FULL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.MAX_OUTPUT;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.exhaustSteamFor;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine.isExhaustFace;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.FLUX_PER_MILLIBUCKET_DOWNSTREAM;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.WORK_INTERVAL;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.isEnergyPos;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.isIntakeFace;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.isSteamPort;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.recoverablePerPass;
import static blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG.steamPerPass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combined cycle: the recovery curve, the dock, and the arithmetic that makes the whole thing
 * worth building.
 * <p>
 * Two classes of failure are asserted here and neither of them would ever be reported by a running
 * game. The recovery curve is the one that matters: it is the only thing standing between "the
 * waste heat now does work" and "the waste heat now prints power". If it ever stopped tracking the
 * turbine's actual output -- if a coasting or cold machine yielded anything, or a clamp went
 * missing and a bad number multiplied through -- the plant would still form, still run and still
 * look right, while quietly making several times what it should.
 * <p>
 * The dock is the second: an HRSG that could not identify a turbine's exhaust end, or a shape
 * whose intake face stopped matching that end, is a machine that silently never couples. The
 * player hammers it, it forms, and it does nothing forever with no message and nothing in the log.
 * <p>
 * Scope note: the pass itself needs a {@code World}, a {@code FluidStack} and a neighbouring
 * {@code TileEntity}, none of which can be constructed here, so host resolution, the exhaust claim
 * and the drum are not reachable from a unit test. Everything they are built out of is.
 */
class HRSGTest
{
	private static final int HEIGHT = PetroleumGeometry.HRSG_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.HRSG_DEPTH;
	private static final int WIDTH = PetroleumGeometry.HRSG_WIDTH;

	private static int index(int h, int l, int w)
	{
		return PetroleumGeometry.structureIndex(PetroleumGeometry.HRSG_SIZE, h, l, w);
	}

	private static int turbineIndex(int h, int l, int w)
	{
		return PetroleumGeometry.structureIndex(PetroleumGeometry.TURBINE_SIZE, h, l, w);
	}

	@Nested
	@DisplayName("recovery curve")
	class Recovery
	{
		@Test
		@DisplayName("a turbine making nothing yields nothing at all")
		void nothingFromNothing()
		{
			assertEquals(0, exhaustSteamFor(0));
			assertEquals(0, exhaustSteamFor(-1));
			assertEquals(0, exhaustSteamFor(Integer.MIN_VALUE));
			//And therefore no pass can bank anything from it. This is the assertion that keeps a
			//switched-off plant from being a steam source.
			assertEquals(0, steamPerPass(exhaustSteamFor(0)));
		}

		@Test
		@DisplayName("a turbine at full output is worth exactly 180 mB/t")
		void fullOutput()
		{
			assertEquals(180, EXHAUST_STEAM_AT_FULL);
			assertEquals(EXHAUST_STEAM_AT_FULL, exhaustSteamFor(MAX_OUTPUT));
		}

		@Test
		@DisplayName("nothing above full output buys any more heat")
		void clampedAbove()
		{
			assertEquals(EXHAUST_STEAM_AT_FULL, exhaustSteamFor(MAX_OUTPUT+1));
			assertEquals(EXHAUST_STEAM_AT_FULL, exhaustSteamFor(MAX_OUTPUT*10));
			assertEquals(EXHAUST_STEAM_AT_FULL, exhaustSteamFor(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("half a turbine's load is half its waste heat")
		void proportional()
		{
			assertEquals(EXHAUST_STEAM_AT_FULL/2, exhaustSteamFor(MAX_OUTPUT/2));
			assertEquals(EXHAUST_STEAM_AT_FULL/4, exhaustSteamFor(MAX_OUTPUT/4));
			assertEquals(EXHAUST_STEAM_AT_FULL*3/4, exhaustSteamFor(MAX_OUTPUT*3/4));
		}

		@Test
		@DisplayName("it only ever climbs, and never past full")
		void monotone()
		{
			int previous = 0;
			for(int output = 0; output <= MAX_OUTPUT; output += 16)
			{
				int steam = exhaustSteamFor(output);
				assertTrue(steam >= previous, "fell back at output "+output);
				assertTrue(steam <= EXHAUST_STEAM_AT_FULL, "over full recovery at output "+output);
				previous = steam;
			}
		}

		@Test
		@DisplayName("it follows the turbine's own output curve wherever that curve goes")
		void tracksTheSpool()
		{
			//Not a second curve with its own shape. Whatever the turbine's output does with its
			//spool, the recovery does the same thing, so the two can never drift apart.
			assertEquals(0, exhaustSteamFor(TileEntityGasTurbine.outputAt(0)));
			assertEquals(EXHAUST_STEAM_AT_FULL,
					exhaustSteamFor(TileEntityGasTurbine.outputAt(TileEntityGasTurbine.SPOOL_FULL)));
			assertEquals(EXHAUST_STEAM_AT_FULL/2,
					exhaustSteamFor(TileEntityGasTurbine.outputAt(TileEntityGasTurbine.SPOOL_FULL/2)));
		}
	}

	@Nested
	@DisplayName("the pass")
	class Pass
	{
		@Test
		@DisplayName("a pass is one interval of recovery")
		void interval()
		{
			assertEquals(10, WORK_INTERVAL);
			assertEquals(EXHAUST_STEAM_AT_FULL*WORK_INTERVAL, steamPerPass(EXHAUST_STEAM_AT_FULL));
			assertEquals(0, steamPerPass(0));
			assertEquals(0, steamPerPass(-100));
		}

		@Test
		@DisplayName("a rate above what a turbine can offer is clamped, not multiplied through")
		void clampsAWildRate()
		{
			//The rate arrives from another tile entity. A boiler that trusted it would turn one
			//bad number into unbounded steam.
			assertEquals(EXHAUST_STEAM_AT_FULL*WORK_INTERVAL, steamPerPass(EXHAUST_STEAM_AT_FULL*10));
			assertEquals(EXHAUST_STEAM_AT_FULL*WORK_INTERVAL, steamPerPass(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("a full drum takes nothing, so a backed-up machine spends nothing")
		void backedUp()
		{
			assertEquals(0, recoverablePerPass(EXHAUST_STEAM_AT_FULL, 0));
			assertEquals(0, recoverablePerPass(EXHAUST_STEAM_AT_FULL, -1));
			assertEquals(0, recoverablePerPass(0, TileEntityHRSG.TANK_CAPACITY));
		}

		@Test
		@DisplayName("a nearly full drum takes what is left and no more")
		void partialRoom()
		{
			assertEquals(7, recoverablePerPass(EXHAUST_STEAM_AT_FULL, 7));
			assertEquals(EXHAUST_STEAM_AT_FULL*WORK_INTERVAL,
					recoverablePerPass(EXHAUST_STEAM_AT_FULL, TileEntityHRSG.TANK_CAPACITY));
		}

		@Test
		@DisplayName("a pass always costs something, so nothing can run for free")
		void costIsNeverZero()
		{
			//The comparison in drawPower is "stored < cost". If the cost were ever zero that test
			//would pass on an empty buffer and the machine would recover forever with no supply,
			//which is exactly how the industrial burner once heated a tower for nothing.
			assertEquals(40, TileEntityHRSG.ENERGY_PER_TICK);
			assertEquals(TileEntityHRSG.ENERGY_PER_TICK*WORK_INTERVAL, TileEntityHRSG.ENERGY_PER_PASS);
			assertTrue(TileEntityHRSG.ENERGY_PER_PASS > 0);
			assertTrue(TileEntityHRSG.ENERGY_CAPACITY >= TileEntityHRSG.ENERGY_PER_PASS);
			//And a buffer that could not hold one pass would be a machine that never runs.
			assertTrue(TileEntityHRSG.ENERGY_MAX_RECEIVE*WORK_INTERVAL > TileEntityHRSG.ENERGY_PER_PASS);
		}

		@Test
		@DisplayName("the exhaust claim outlives a missed pass but not a broken machine")
		void claimLease()
		{
			assertTrue(TileEntityHRSG.CLAIM_LEASE > WORK_INTERVAL,
					"a lease no longer than the interval would drop on any skipped pass");
			assertTrue(TileEntityHRSG.CLAIM_LEASE <= 20*3,
					"a dead HRSG must let go of a turbine in seconds, not minutes");
		}

		@Test
		@DisplayName("the drum is a buffer against a stuttering steam line, not a way to store a plant")
		void drumDepth()
		{
			int ticks = TileEntityHRSG.TANK_CAPACITY/EXHAUST_STEAM_AT_FULL;
			assertTrue(ticks >= 20*10, "a drum this shallow would back up on any hiccup");
			assertTrue(ticks <= 20*60, "a drum that deep would make the steam line pointless");
			//The same volume the turbine's gas tank holds: the pair are one plant.
			assertEquals(TileEntityGasTurbine.TANK_CAPACITY, TileEntityHRSG.TANK_CAPACITY);
		}
	}

	@Nested
	@DisplayName("the combined cycle")
	class CombinedCycle
	{
		@Test
		@DisplayName("recovered heat is worth half again as much as the turbine makes itself")
		void theWholePoint()
		{
			//The number the feature exists for. A gas turbine alone is 12,288 IF/t; its exhaust,
			//once a hall has had the steam, is another 18,000. The same gas, one plant, 30,288 --
			//and the second half of it costs no fuel whatsoever.
			int turbine = MAX_OUTPUT;
			int recovered = EXHAUST_STEAM_AT_FULL*FLUX_PER_MILLIBUCKET_DOWNSTREAM;
			assertEquals(12288, turbine);
			assertEquals(18000, recovered);
			assertTrue(recovered > turbine, "the recovery has to be the larger half, or nobody builds it");
			assertTrue(turbine+recovered >= 30000, "the plan's diagram says ~30,000 FE/t");
			assertTrue(turbine+recovered < 31000, "and it says ~30,000, not 40,000");
		}

		@Test
		@DisplayName("a millibucket of steam is worth a hundred flux downstream")
		void steamIsPinned()
		{
			//Pinned across four machines. If this ever changes, every steam producer and consumer
			//in the expansion changes with it.
			assertEquals(100, FLUX_PER_MILLIBUCKET_DOWNSTREAM);
		}
	}

	@Nested
	@DisplayName("the dock")
	class Dock
	{
		@Test
		@DisplayName("the HRSG's intake face is exactly the size of the turbine's exhaust end")
		void facesMatch()
		{
			//The reason both machines are three by three. If either ever changed width or height
			//the two would no longer seal against each other and nothing would ever couple.
			assertEquals(PetroleumGeometry.TURBINE_HEIGHT, HEIGHT);
			assertEquals(PetroleumGeometry.TURBINE_WIDTH, WIDTH);
		}

		@Test
		@DisplayName("the intake face is solid, so it seals against the whole exhaust")
		void intakeIsSolid()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int w = 0; w < WIDTH; w++)
				{
					assertTrue(MultiblockHRSG.isPart(h, MultiblockHRSG.INTAKE_DEPTH, w),
							"intake cell "+h+","+w+" is open");
					assertTrue(isIntakeFace(index(h, MultiblockHRSG.INTAKE_DEPTH, w)),
							"intake cell "+h+","+w+" is not recognised as intake");
				}
			//And so is the turbine's exhaust end, or there would be nothing to seal against.
			for(int h = 0; h < PetroleumGeometry.TURBINE_HEIGHT; h++)
				for(int w = 0; w < PetroleumGeometry.TURBINE_WIDTH; w++)
					assertTrue(MultiblockGasTurbine.isPart(h, PetroleumGeometry.TURBINE_DEPTH-1, w),
							"turbine exhaust cell "+h+","+w+" is open");
		}

		@Test
		@DisplayName("the exhaust end is the last course of the turbine and nothing else")
		void exhaustFaceIsTheEndCourse()
		{
			for(int h = 0; h < PetroleumGeometry.TURBINE_HEIGHT; h++)
				for(int l = 0; l < PetroleumGeometry.TURBINE_DEPTH; l++)
					for(int w = 0; w < PetroleumGeometry.TURBINE_WIDTH; w++)
						assertEquals(l==PetroleumGeometry.TURBINE_DEPTH-1,
								isExhaustFace(turbineIndex(h, l, w)),
								"turbine cell "+h+","+l+","+w);
		}

		@Test
		@DisplayName("the intake house is emphatically not the exhaust")
		void intakeHouseIsNotTheExhaust()
		{
			//The failure this test exists for: an HRSG parked against the front of a turbine is
			//catching filtered air, and must never couple.
			assertFalse(isExhaustFace(MultiblockGasTurbine.MASTER_POS));
			for(int h = 0; h < PetroleumGeometry.TURBINE_HEIGHT; h++)
				for(int w = 0; w < PetroleumGeometry.TURBINE_WIDTH; w++)
					assertFalse(isExhaustFace(turbineIndex(h, 0, w)), "front cell "+h+","+w);
			//The stack mouth is on the exhaust end, and so are the eight cells around it.
			assertTrue(isExhaustFace(MultiblockGasTurbine.STACK_POS));
			assertEquals(PetroleumGeometry.TURBINE_DEPTH-1, TileEntityGasTurbine.EXHAUST_DEPTH);
		}

		@Test
		@DisplayName("an unformed or out-of-range block belongs to no face")
		void nothingOutsideCounts()
		{
			assertFalse(isExhaustFace(-1));
			assertFalse(isExhaustFace(Integer.MIN_VALUE));
			assertFalse(isExhaustFace(PetroleumGeometry.TURBINE_HEIGHT*PetroleumGeometry.TURBINE_DEPTH
					*PetroleumGeometry.TURBINE_WIDTH));
			assertFalse(isIntakeFace(-1));
			assertFalse(isIntakeFace(HEIGHT*DEPTH*WIDTH));
		}
	}

	@Nested
	@DisplayName("ports")
	class Ports
	{
		@Test
		@DisplayName("the whole skid course takes a wire")
		void skidTakesPower()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(isEnergyPos(index(0, l, w)), "skid cell "+l+","+w);
		}

		@Test
		@DisplayName("no cell is both a wire landing and a pipe landing")
		void portsAreDisjoint()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						int pos = index(h, l, w);
						assertFalse(isEnergyPos(pos)&&isSteamPort(pos),
								"cell "+h+","+l+","+w+" is both");
					}
		}

		@Test
		@DisplayName("no steam leaves the face that is buried against the turbine")
		void nothingLeavesTheIntake()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int w = 0; w < WIDTH; w++)
					assertFalse(isSteamPort(index(h, MultiblockHRSG.INTAKE_DEPTH, w)),
							"intake cell "+h+","+w+" offers steam");
		}

		@Test
		@DisplayName("the cold end and the drum are where a steam line lands")
		void steamLeavesTheColdEndAndTheDrum()
		{
			for(int h = 1; h < HEIGHT; h++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(isSteamPort(index(h, DEPTH-1, w)), "cold end cell "+h+","+w);
			for(int l = 1; l < DEPTH; l++)
				assertTrue(isSteamPort(index(HEIGHT-1, l, WIDTH/2)), "drum cell "+l);
			//The outlet head is a steam port, which is the whole reason it is the master.
			assertTrue(isSteamPort(MultiblockHRSG.MASTER_POS));
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and offers nothing")
		void unformedOffersNothing()
		{
			assertFalse(isEnergyPos(-1));
			assertFalse(isSteamPort(-1));
			assertFalse(isEnergyPos(HEIGHT*DEPTH*WIDTH));
			assertFalse(isSteamPort(HEIGHT*DEPTH*WIDTH));
		}

		@Test
		@DisplayName("the comparator answers from one block, and a reachable one")
		void oneComparatorFace()
		{
			assertEquals(index(0, DEPTH-1, 0), TileEntityHRSG.REDSTONE_INDEX);
			assertFalse(isIntakeFace(TileEntityHRSG.REDSTONE_INDEX),
					"a comparator on the intake face would be inside the turbine");
		}
	}

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the unit is three high, five long and three wide")
		void size()
		{
			assertEquals(3, HEIGHT);
			assertEquals(5, DEPTH);
			assertEquals(3, WIDTH);
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockHRSG.blockCount(SHELL)
							+MultiblockHRSG.blockCount(BANK)
							+MultiblockHRSG.blockCount(DRUM)
							+MultiblockHRSG.blockCount(FRAME)
							+MultiblockHRSG.blockCount(EMPTY));
		}

		@Test
		@DisplayName("bonnet, tube banks and cold end sit in a line from the turbine outwards")
		void theGasPath()
		{
			assertEquals(FRAME, MultiblockHRSG.shapeAt(1, 0, WIDTH/2));
			for(int l = 1; l <= DEPTH-2; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(BANK, MultiblockHRSG.shapeAt(1, l, w), "tube bank "+l+","+w);
			assertEquals(FRAME, MultiblockHRSG.shapeAt(1, DEPTH-1, WIDTH/2));
			assertEquals(3, MultiblockHRSG.blockCount(BANK)/WIDTH,
					"three courses of exchanger: superheater, evaporator, economiser");
		}

		@Test
		@DisplayName("the skid is one continuous raft under the whole unit")
		void skidIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(SHELL, MultiblockHRSG.shapeAt(0, l, w), "skid cell "+l+","+w);
		}

		@Test
		@DisplayName("the drum stands clear of the casing, which is the machine's silhouette")
		void drumSpine()
		{
			for(int l = 1; l <= DEPTH-2; l++)
			{
				assertEquals(DRUM, MultiblockHRSG.shapeAt(HEIGHT-1, l, WIDTH/2), "drum "+l);
				assertEquals(EMPTY, MultiblockHRSG.shapeAt(HEIGHT-1, l, 0), "drum flank "+l);
				assertEquals(EMPTY, MultiblockHRSG.shapeAt(HEIGHT-1, l, WIDTH-1), "drum flank "+l);
				assertFalse(MultiblockHRSG.isPart(HEIGHT-1, l, 0));
			}
			//But never at the two ends: one seals against the turbine, the other closes the duct.
			for(int w = 0; w < WIDTH; w++)
			{
				assertEquals(SHELL, MultiblockHRSG.shapeAt(HEIGHT-1, 0, w));
				assertEquals(SHELL, MultiblockHRSG.shapeAt(HEIGHT-1, DEPTH-1, w));
			}
		}

		@Test
		@DisplayName("the master is the outlet head, at the end a docked machine can still be reached from")
		void namedPositions()
		{
			assertEquals(index(1, DEPTH-1, WIDTH/2), MultiblockHRSG.MASTER_POS);
			assertEquals(index(1, 0, WIDTH/2), MultiblockHRSG.BONNET_POS);
			assertEquals(FRAME, MultiblockHRSG.shapeAt(1, DEPTH-1, WIDTH/2));
			assertEquals(FRAME, MultiblockHRSG.shapeAt(1, 0, WIDTH/2));
			assertFalse(isIntakeFace(MultiblockHRSG.MASTER_POS),
					"the master must not be the face buried against the turbine");
			assertTrue(isIntakeFace(MultiblockHRSG.BONNET_POS));
			for(int h = 0; h < HEIGHT; h++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(index(h, 0, w), MultiblockHRSG.intakePos(h, w));
		}

		@Test
		@DisplayName("exactly the two frame cells can be hammered, since only frame is a trigger")
		void frameIsReachable()
		{
			assertEquals(2, MultiblockHRSG.blockCount(FRAME));
		}

		@Test
		@DisplayName("every course is symmetric across the width, so there is no mirrored variant")
		void noMirroredVariant()
		{
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						assertEquals(MultiblockHRSG.shapeAt(h, l, w),
								MultiblockHRSG.shapeAt(h, l, WIDTH-1-w),
								"asymmetric at "+h+","+l+","+w);
		}

		@Test
		@DisplayName("anything outside the box is not part of the machine")
		void outsideTheBox()
		{
			assertEquals('?', MultiblockHRSG.shapeAt(-1, 0, 0));
			assertEquals('?', MultiblockHRSG.shapeAt(HEIGHT, 0, 0));
			assertEquals('?', MultiblockHRSG.shapeAt(0, DEPTH, 0));
			assertEquals('?', MultiblockHRSG.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockHRSG.isPart(-1, 0, 0));
			assertFalse(MultiblockHRSG.isPart(0, 0, WIDTH));
		}

		@Test
		@DisplayName("it has its own name, so it is not confused with any other structure")
		void uniqueName()
		{
			assertEquals("IE:HRSG", MultiblockHRSG.instance.getUniqueName());
			assertFalse(MultiblockHRSG.instance.getUniqueName()
					.equals(MultiblockGasTurbine.instance.getUniqueName()));
		}
	}
}
