/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasScrubber.Scrubbing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber.DECK;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber.EMPTY;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber.FRAME;
import static blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGasScrubber.VESSEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gas Scrubber's shape, its conversion table and its pass arithmetic.
 * <p>
 * All three fail quietly. A wrong cell in the shape means a machine that refuses to form when
 * hammered, with no message and nothing in the log. A wrong number in the conversion table means
 * the machine either prints money or is not worth the forty blocks it costs, and neither shows up
 * as an error. And the pass arithmetic is where gas gets destroyed: every cap on the pass volume
 * exists to stop the machine drawing feed it cannot turn into something, so an off-by-one here is
 * a silent duplication or a silent loss rather than a crash.
 * <p>
 * Scope note: everything else the scrubber does needs a {@code World}, a {@code FluidStack}, an
 * {@code ItemStack} or a neighbouring {@code TileEntity}, none of which can be constructed here,
 * so the tanks, the sulfur ejection and the heat draw are not reachable from a unit test.
 */
class GasScrubberTest
{
	private static final int HEIGHT = PetroleumGeometry.SCRUBBER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.SCRUBBER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.SCRUBBER_WIDTH;

	@Nested
	@DisplayName("shape")
	class Shape
	{
		@Test
		@DisplayName("the scrubber is six high on a three by three footprint")
		void size()
		{
			assertEquals(6, HEIGHT);
			assertEquals(3, DEPTH);
			assertEquals(3, WIDTH);
		}

		@Test
		@DisplayName("every cell is one of the three materials or the alley, and nothing else")
		void everyCellAccountedFor()
		{
			assertEquals(HEIGHT*DEPTH*WIDTH,
					MultiblockGasScrubber.blockCount(DECK)
							+MultiblockGasScrubber.blockCount(VESSEL)
							+MultiblockGasScrubber.blockCount(FRAME)
							+MultiblockGasScrubber.blockCount(EMPTY));
		}

		@Test
		@DisplayName("the skid is a solid course of deck, and it is the only one")
		void skidIsSolid()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertEquals(DECK, MultiblockGasScrubber.shapeAt(0, l, w), "skid cell "+l+","+w);
			assertEquals(DEPTH*WIDTH, MultiblockGasScrubber.blockCount(DECK));
		}

		@Test
		@DisplayName("the two vessels run the full height at either side")
		void vesselsRunTheFullHeight()
		{
			for(int h = 1; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
				{
					char left = MultiblockGasScrubber.shapeAt(h, l, 0);
					char right = MultiblockGasScrubber.shapeAt(h, l, WIDTH-1);
					assertTrue(left==VESSEL||left==FRAME, "left vessel at "+h+","+l);
					//The machine has to be symmetric across its width, or a mirrored build would
					//refuse to form and there would be nothing on screen to say why.
					assertEquals(left, right, "width symmetry at "+h+","+l);
				}
		}

		@Test
		@DisplayName("the alley between them is open all the way up, bar the crossover")
		void alleyIsOpen()
		{
			for(int h = 1; h < HEIGHT-1; h++)
				for(int l = 0; l < DEPTH; l++)
					assertEquals(EMPTY, MultiblockGasScrubber.shapeAt(h, l, WIDTH/2),
							"alley cell "+h+","+l);
			assertEquals(FRAME, MultiblockGasScrubber.shapeAt(HEIGHT-1, DEPTH/2, WIDTH/2));
		}

		@Test
		@DisplayName("frame is reachable from the ground, since only frame is a trigger")
		void frameIsReachable()
		{
			//A trigger that can only be hit six blocks up is a machine the player cannot assemble
			//without scaffolding they have not built yet.
			assertEquals(FRAME, MultiblockGasScrubber.shapeAt(1, 0, 0));
			assertEquals(FRAME, MultiblockGasScrubber.shapeAt(1, 0, WIDTH-1));
			assertEquals(5, MultiblockGasScrubber.blockCount(FRAME));
		}

		@Test
		@DisplayName("the frames sit on the front face only, which is what gives the machine a front")
		void frontIsAsymmetric()
		{
			//Formation reads the orientation off the blocks rather than off the clicked face, so a
			//shape that looked the same from front and back would form pointing any which way.
			boolean differs = false;
			for(int h = 0; h < HEIGHT&&!differs; h++)
				for(int w = 0; w < WIDTH&&!differs; w++)
					differs = MultiblockGasScrubber.shapeAt(h, 0, w)
							!=MultiblockGasScrubber.shapeAt(h, DEPTH-1, w);
			assertTrue(differs, "the shape must not be symmetric front to back");
		}

		@Test
		@DisplayName("anything outside the box is not part of the machine")
		void outsideTheBox()
		{
			assertEquals(EMPTY, MultiblockGasScrubber.shapeAt(-1, 0, 0));
			assertEquals(EMPTY, MultiblockGasScrubber.shapeAt(HEIGHT, 0, 0));
			assertEquals(EMPTY, MultiblockGasScrubber.shapeAt(0, DEPTH, 0));
			assertEquals(EMPTY, MultiblockGasScrubber.shapeAt(0, 0, WIDTH));
			assertFalse(MultiblockGasScrubber.isPart(HEIGHT, 0, 0));
			assertTrue(MultiblockGasScrubber.isPart(0, 0, 0));
		}

		@Test
		@DisplayName("the master is the origin, so the offsets are the cells' own coordinates")
		void masterIsTheOrigin()
		{
			assertEquals(0, MultiblockGasScrubber.MASTER_POS);
			assertEquals(DECK, MultiblockGasScrubber.shapeAt(0, 0, 0));
		}

		@Test
		@DisplayName("the sulfur outlet and the crossover are where the shape says they are")
		void namedPositions()
		{
			assertEquals(PetroleumGeometry.structureIndex(MultiblockGasScrubber.SIZE, 0, 0, WIDTH/2),
					MultiblockGasScrubber.SULFUR_OUTLET_POS);
			assertEquals(DECK, MultiblockGasScrubber.shapeAt(0, 0, WIDTH/2));
			assertEquals(PetroleumGeometry.structureIndex(MultiblockGasScrubber.SIZE,
					HEIGHT-1, DEPTH/2, WIDTH/2), MultiblockGasScrubber.CROSSOVER_POS);
		}
	}

	@Nested
	@DisplayName("ports")
	class Ports
	{
		@Test
		@DisplayName("the whole skid takes sour gas")
		void skidTakesFeed()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityGasScrubber.isFeedPort(
							PetroleumGeometry.structureIndex(MultiblockGasScrubber.SIZE, 0, l, w)),
							"skid cell "+l+","+w);
		}

		@Test
		@DisplayName("the head gives sweet gas back")
		void headGivesProduct()
		{
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					assertTrue(TileEntityGasScrubber.isProductPort(
							PetroleumGeometry.structureIndex(MultiblockGasScrubber.SIZE, HEIGHT-1, l, w)),
							"head cell "+l+","+w);
		}

		@Test
		@DisplayName("the two never overlap, so no face both takes and gives")
		void portsAreDisjoint()
		{
			//A face that did both would let a pipe run feed the machine its own product, which is a
			//loop that looks like it works and quietly does nothing.
			for(int pos = 0; pos < HEIGHT*DEPTH*WIDTH; pos++)
				assertFalse(TileEntityGasScrubber.isFeedPort(pos)
								&&TileEntityGasScrubber.isProductPort(pos),
						"cell "+pos);
		}

		@Test
		@DisplayName("the vessels themselves are inert all the way up")
		void vesselsAreInert()
		{
			for(int h = 1; h < HEIGHT-1; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						int pos = PetroleumGeometry.structureIndex(MultiblockGasScrubber.SIZE, h, l, w);
						assertFalse(TileEntityGasScrubber.isFeedPort(pos), "feed at "+pos);
						assertFalse(TileEntityGasScrubber.isProductPort(pos), "product at "+pos);
					}
		}

		@Test
		@DisplayName("an unformed block belongs to no machine and connects to nothing")
		void unformedConnectsToNothing()
		{
			assertFalse(TileEntityGasScrubber.isFeedPort(-1));
			assertFalse(TileEntityGasScrubber.isProductPort(-1));
		}
	}

	@Nested
	@DisplayName("conversion table")
	class Table
	{
		@Test
		@DisplayName("sour gas is what goes in and natural gas is what comes out")
		void theShippedConversion()
		{
			Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");
			assertNotNull(sour);
			assertEquals("natural_gas", sour.output);
			assertEquals(TileEntityGasScrubber.SWEET_PERCENT, sour.outputPercent);
			assertEquals(TileEntityGasScrubber.SOUR_PER_SULFUR, sour.inputPerSulfur);
			assertEquals(TileEntityGasScrubber.HEAT_PER_BUCKET, sour.heatPerBucket);
		}

		@Test
		@DisplayName("nothing else is, so a scrubber is not a way to dispose of crude or water")
		void everythingElseIsRejected()
		{
			assertNull(TileEntityGasScrubber.getScrubbing("ie_crude_oil"));
			assertNull(TileEntityGasScrubber.getScrubbing("natural_gas"));
			assertNull(TileEntityGasScrubber.getScrubbing("water"));
			assertNull(TileEntityGasScrubber.getScrubbing((String)null));
		}

		@Test
		@DisplayName("the machine cannot be plumbed into a loop that makes gas out of gas")
		void notACycle()
		{
			//If the product were ever also an input the plant would be a perpetual motion machine
			//with an extra step, and it would take a long time for anyone to notice.
			Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");
			assertNotNull(sour);
			assertNull(TileEntityGasScrubber.getScrubbing(sour.output));
		}

		@Test
		@DisplayName("sweetening loses volume, because the loss is what the sulfur is made of")
		void theRatioIsAConversionNotAGift()
		{
			Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");
			assertNotNull(sour);
			assertTrue(sour.outputPercent < 100, "a lossless scrubber has no acid gas to make sulfur from");
			assertTrue(sour.outputPercent >= 50, "a stingy ratio is not worth forty blocks");
		}

		@Test
		@DisplayName("a pass costs heat, or the machine would be free money")
		void heatIsTheCost()
		{
			Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");
			assertNotNull(sour);
			assertTrue(sour.heatPerBucket > 0);
		}

		@Test
		@DisplayName("the machine can be run off its own product, and it costs a quarter of it")
		void selfSustaining()
		{
			//The whole design turns on this number. If a burner on natural gas could not keep one
			//scrubber lit, the machine would be gated behind heavy fuel oil and therefore behind the
			//distillation tower; if it cost nothing to do so, there would be no reason ever to burn
			//anything else.
			Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");
			assertNotNull(sour);
			int heatPerPass = sour.heatFor(TileEntityGasScrubber.CHARGE);
			int burnerYield = TileEntityIndustrialBurner.getHeatRateFor("natural_gas")
					*TileEntityGasScrubber.SCRUB_INTERVAL;
			assertTrue(burnerYield >= heatPerPass,
					"a firebox on natural gas must sustain one scrubber: "+burnerYield+" < "+heatPerPass);

			//What that costs, as a fraction of the gas the pass makes.
			int made = sour.sweetFrom(TileEntityGasScrubber.CHARGE);
			int burnt = TileEntityIndustrialBurner.FIRING_RATE*TileEntityGasScrubber.SCRUB_INTERVAL;
			assertTrue(burnt*4 <= made*3, "keeping the reboiler lit must not eat most of the product");
			assertTrue(burnt*8 >= made, "gas that keeps its own reboiler lit for nothing is free money");
		}

		@Test
		@DisplayName("heavy fuel oil is still the better thing to burn under it")
		void residueBeatsProduct()
		{
			//Otherwise the bottom of the barrel loses its only consumer the moment a scrubber exists.
			assertTrue(TileEntityIndustrialBurner.getHeatRateFor("ie_heavy_fuel_oil")
					> TileEntityIndustrialBurner.getHeatRateFor("natural_gas"));
		}
	}

	@Nested
	@DisplayName("pass arithmetic")
	class Pass
	{
		private final Scrubbing sour = TileEntityGasScrubber.getScrubbing("ie_sour_gas");

		@Test
		@DisplayName("a pass is one interval at the scrubbing rate, and no more")
		void passMatchesInterval()
		{
			assertEquals(TileEntityGasScrubber.SCRUB_RATE*TileEntityGasScrubber.SCRUB_INTERVAL,
					TileEntityGasScrubber.CHARGE);
		}

		@Test
		@DisplayName("a bucket in yields exactly its listed share out")
		void aBucketIsABucket()
		{
			assertEquals(TileEntityGasScrubber.SWEET_PERCENT*10, sour.sweetFrom(1000));
			assertEquals(0, sour.sweetFrom(0));
			assertEquals(0, sour.sweetFrom(-1));
		}

		@Test
		@DisplayName("a pass sized to the room left never overflows the product tank")
		void sizedToRoomNeverOverflows()
		{
			//This is the check that stops the machine destroying gas: whatever room is left, the
			//volume it permits must produce no more than that room.
			for(int room = 0; room <= 2000; room++)
			{
				int volume = Math.min(TileEntityGasScrubber.CHARGE, sour.volumeForSweet(room));
				assertTrue(sour.sweetFrom(volume) <= room, "room "+room);
			}
		}

		@Test
		@DisplayName("a full product tank stops the machine dead rather than losing the feed")
		void fullTankStops()
		{
			assertEquals(0, sour.volumeForSweet(0));
			assertEquals(0, sour.sweetFrom(sour.volumeForSweet(0)));
		}

		@Test
		@DisplayName("heat bought and heat spent agree, so a pass is never had for free")
		void heatRoundTrips()
		{
			for(int volume = 0; volume <= TileEntityGasScrubber.CHARGE; volume += 7)
			{
				int cost = sour.heatFor(volume);
				//What that heat buys back must never exceed what was asked for, or a machine handed
				//exactly its quoted price would put through more than it paid for.
				assertTrue(sour.volumeForHeat(cost) <= volume+999*1000/sour.heatPerBucket,
						"volume "+volume);
				assertTrue(cost <= volume*sour.heatPerBucket/1000, "volume "+volume);
			}
		}

		@Test
		@DisplayName("partial heat buys a proportional part of a pass")
		void partialHeatRunsSlowly()
		{
			int full = sour.heatFor(TileEntityGasScrubber.CHARGE);
			assertEquals(TileEntityGasScrubber.CHARGE, sour.volumeForHeat(full));
			assertEquals(TileEntityGasScrubber.CHARGE/2, sour.volumeForHeat(full/2));
			assertEquals(0, sour.volumeForHeat(0));
		}
	}

	@Nested
	@DisplayName("sulfur accounting")
	class Sulfur
	{
		private static final int PER_SULFUR = 2000;

		/**
		 * Runs the accumulator exactly as a pass does, and returns the dust it produced.
		 */
		private int[] accumulate(int passes, int perPass, int bufferCap)
		{
			int progress = 0;
			int buffer = 0;
			int consumed = 0;
			for(int i = 0; i < passes; i++)
			{
				int volume = Math.min(perPass, TileEntityGasScrubber.volumeForSulfurRoom(
						progress, PER_SULFUR, bufferCap-buffer));
				consumed += volume;
				progress += volume;
				int dust = progress/PER_SULFUR;
				progress -= dust*PER_SULFUR;
				buffer += dust;
			}
			return new int[]{buffer, consumed, progress};
		}

		@Test
		@DisplayName("dust comes out at exactly the listed rate, whatever the pass size")
		void rateIsExact()
		{
			for(int perPass : new int[]{1, 7, 400, PER_SULFUR, 3*PER_SULFUR})
			{
				int passes = 10*PER_SULFUR/perPass;
				int[] run = accumulate(passes, perPass, Integer.MAX_VALUE);
				assertEquals(run[1]/PER_SULFUR, run[0], "pass size "+perPass);
			}
		}

		@Test
		@DisplayName("the buffer is never overfilled, so no dust is ever made with nowhere to go")
		void bufferIsNeverOverfilled()
		{
			for(int cap = 0; cap <= 4; cap++)
			{
				int[] run = accumulate(200, 400, cap);
				assertTrue(run[0] <= cap, "cap "+cap+" held "+run[0]);
			}
		}

		@Test
		@DisplayName("a full buffer stops the feed, rather than the machine eating gas for nothing")
		void fullBufferStopsTheFeed()
		{
			//Trickling to just short of the next dust is deliberate -- it is the difference between
			//stopping where the buffer fills and stopping a whole dust early -- but it must stop.
			assertEquals(PER_SULFUR-1, TileEntityGasScrubber.volumeForSulfurRoom(0, PER_SULFUR, 0));
			assertEquals(0, TileEntityGasScrubber.volumeForSulfurRoom(PER_SULFUR-1, PER_SULFUR, 0));
			int[] run = accumulate(200, 400, 0);
			assertEquals(0, run[0]);
			assertEquals(PER_SULFUR-1, run[1]);
		}

		@Test
		@DisplayName("a conversion that makes no sulfur is never held up by the buffer")
		void noSulfurNoLimit()
		{
			assertEquals(Integer.MAX_VALUE,
					TileEntityGasScrubber.volumeForSulfurRoom(0, 0, 0));
		}

		@Test
		@DisplayName("saltpeter still gates gunpowder, not sulfur")
		void sulfurIsNotTheBottleneck()
		{
			//Four saltpeter to one sulfur in the gunpowder recipe. A scrubber that could not keep a
			//player in sulfur would make the bonus meaningless; one that flooded them would make the
			//excavator's sulfur minerals pointless. A dust every few seconds is the middle of that.
			int perDust = TileEntityGasScrubber.SOUR_PER_SULFUR/TileEntityGasScrubber.SCRUB_RATE;
			assertTrue(perDust >= 20, "a dust more often than once a second is a flood");
			assertTrue(perDust <= 600, "a dust less often than twice a minute is not worth plumbing");
		}
	}

	@Nested
	@DisplayName("registration")
	class Registration
	{
		@AfterEach
		void tearDown()
		{
			TileEntityGasScrubber.removeScrubbing("test_sour");
		}

		@Test
		@DisplayName("a conversion can be added and taken away again")
		void addAndRemove()
		{
			assertNull(TileEntityGasScrubber.getScrubbing("test_sour"));
			TileEntityGasScrubber.registerScrubbing("test_sour", "test_sweet", 50, 1000, 500);
			Scrubbing added = TileEntityGasScrubber.getScrubbing("test_sour");
			assertNotNull(added);
			assertEquals(500, added.sweetFrom(1000));
			TileEntityGasScrubber.removeScrubbing("test_sour");
			assertNull(TileEntityGasScrubber.getScrubbing("test_sour"));
		}

		@Test
		@DisplayName("a nonsensical ratio is clamped rather than allowed to make gas out of nothing")
		void ratioIsClamped()
		{
			TileEntityGasScrubber.registerScrubbing("test_sour", "test_sweet", 400, -1, -1);
			Scrubbing added = TileEntityGasScrubber.getScrubbing("test_sour");
			assertNotNull(added);
			assertEquals(100, added.outputPercent);
			assertEquals(0, added.inputPerSulfur);
			assertEquals(0, added.heatPerBucket);
		}
	}
}
