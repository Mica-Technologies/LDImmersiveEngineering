/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import blusunrize.immersiveengineering.api.petroleum.ReservoirHandler.CellPos;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The deposit registry and the virtual map: weighted selection, deterministic cell rolls, and
 * what does and does not need saving.
 */
class ReservoirHandlerTest
{
	private static final long SEED = 8675309L;

	@BeforeEach
	void setUp()
	{
		PetroleumTestSupport.reset();
	}

	@AfterEach
	void tearDown()
	{
		PetroleumTestSupport.reset();
	}

	@Nested
	@DisplayName("the type registry")
	class Types
	{
		@Test
		@DisplayName("a registered type is retrievable by name, case-insensitively")
		void registerAndFetch()
		{
			ReservoirType type = registerTestType();
			assertSame(type, ReservoirHandler.getType(TEST_TYPE));
			assertSame(type, ReservoirHandler.getType(TEST_TYPE.toUpperCase()));
		}

		@Test
		@DisplayName("an unknown or null name resolves to nothing rather than throwing")
		void unknownIsNull()
		{
			assertNull(ReservoirHandler.getType("nope"));
			assertNull(ReservoirHandler.getType(null));
		}

		@Test
		@DisplayName("re-registering a name replaces the earlier type")
		void reregisterReplaces()
		{
			registerTestType();
			ReservoirType replacement = ReservoirHandler.registerType(
					new ReservoirType(TEST_TYPE, "other", 5, 1, 2));
			assertSame(replacement, ReservoirHandler.getType(TEST_TYPE));
			assertEquals(1, ReservoirHandler.getTypes().size());
		}

		@Test
		@DisplayName("weights total across every type")
		void totalWeight()
		{
			ReservoirHandler.registerType(new ReservoirType("a", "f", 3, 1, 2));
			ReservoirHandler.registerType(new ReservoirType("b", "f", 7, 1, 2));
			assertEquals(10, ReservoirHandler.getTotalWeight());
		}

		@Test
		@DisplayName("selection honours the weights")
		void weightedSelection()
		{
			ReservoirHandler.registerType(new ReservoirType("common", "f", 9, 1, 2));
			ReservoirHandler.registerType(new ReservoirType("rare", "f", 1, 1, 2));
			Random random = new Random(11);
			int common = 0;
			for(int i = 0; i < 2000; i++)
				if("common".equals(ReservoirHandler.selectType(random).getName()))
					common++;
			//Expect about 1800; allow a wide band so this tests the weighting, not the RNG.
			assertTrue(common > 1650&&common < 1950, "weighting looks wrong: "+common+"/2000");
		}

		@Test
		@DisplayName("selecting from an empty registry yields nothing")
		void selectionWithNoTypes()
		{
			assertEquals(0, ReservoirHandler.getTotalWeight());
			assertNull(ReservoirHandler.selectType(new Random(1)));
		}

		@Test
		@DisplayName("a zero-weight type is never selected")
		void zeroWeightNeverSelected()
		{
			ReservoirHandler.registerType(new ReservoirType("never", "f", 0, 1, 2));
			ReservoirHandler.registerType(new ReservoirType("always", "f", 1, 1, 2));
			Random random = new Random(5);
			for(int i = 0; i < 200; i++)
				assertEquals("always", ReservoirHandler.selectType(random).getName());
		}
	}

	@Nested
	@DisplayName("cell coordinates")
	class Cells
	{
		@Test
		@DisplayName("chunks inside one cell map to the same cell")
		void chunksShareACell()
		{
			PetroleumConfig.cellChunkSize = 8;
			assertEquals(0, ReservoirHandler.toCell(0));
			assertEquals(0, ReservoirHandler.toCell(7));
			assertEquals(1, ReservoirHandler.toCell(8));
		}

		@Test
		@DisplayName("negative chunk coordinates floor rather than truncate toward zero")
		void negativeCoordinatesFloor()
		{
			//Truncation would make cell 0 twice as wide as every other cell, straddling the
			//origin, and would put a visible seam through spawn.
			PetroleumConfig.cellChunkSize = 8;
			assertEquals(-1, ReservoirHandler.toCell(-1));
			assertEquals(-1, ReservoirHandler.toCell(-8));
			assertEquals(-2, ReservoirHandler.toCell(-9));
		}

		@Test
		@DisplayName("a nonsensical cell size does not divide by zero")
		void zeroCellSizeIsSafe()
		{
			PetroleumConfig.cellChunkSize = 0;
			assertDoesNotThrow(() -> ReservoirHandler.toCell(5));
		}
	}

	@Nested
	@DisplayName("rolling the world")
	class Rolling
	{
		@Test
		@DisplayName("the same seed and cell always give the same deposit")
		void deterministic()
		{
			registerTestType();
			Reservoir first = ReservoirHandler.getReservoir(SEED, 0, 40, 40);
			ReservoirHandler.clear();
			Reservoir second = ReservoirHandler.getReservoir(SEED, 0, 40, 40);
			assertEquals(first.getType(), second.getType());
			assertEquals(first.getOriginalCapacity(), second.getOriginalCapacity());
		}

		@Test
		@DisplayName("every chunk in a cell reports the same deposit")
		void wholeCellAgrees()
		{
			registerTestType();
			PetroleumConfig.cellChunkSize = 8;
			PetroleumConfig.cellChance = 1;
			Reservoir corner = ReservoirHandler.getReservoir(SEED, 0, 0, 0);
			for(int x = 0; x < 8; x++)
				for(int z = 0; z < 8; z++)
					assertSame(corner, ReservoirHandler.getReservoir(SEED, 0, x, z),
							"chunk "+x+","+z+" disagreed with its own cell");
		}

		@Test
		@DisplayName("different seeds give different worlds")
		void seedMatters()
		{
			registerTestType();
			PetroleumConfig.cellChance = 0.5;
			Set<String> shapes = new HashSet<>();
			for(long seed = 0; seed < 12; seed++)
			{
				ReservoirHandler.clear();
				Reservoir reservoir = ReservoirHandler.getReservoir(seed, 0, 0, 0);
				shapes.add(reservoir.isEmpty()?"empty": String.valueOf(reservoir.getOriginalCapacity()));
			}
			assertTrue(shapes.size() > 1, "every seed produced an identical cell");
		}

		@Test
		@DisplayName("neighbouring cells are not correlated")
		void neighboursDiffer()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			Set<Integer> capacities = new HashSet<>();
			for(int x = 0; x < 6; x++)
				for(int z = 0; z < 6; z++)
					capacities.add(ReservoirHandler.getReservoir(SEED, 0, x*8, z*8).getOriginalCapacity());
			assertTrue(capacities.size() > 20,
					"36 adjacent cells produced only "+capacities.size()+" distinct sizes");
		}

		@Test
		@DisplayName("the presence chance is honoured")
		void presenceChance()
		{
			registerTestType();
			PetroleumConfig.cellChance = 0;
			for(int i = 0; i < 40; i++)
				assertTrue(ReservoirHandler.getReservoir(SEED, 0, i*8, 0).isEmpty(),
						"a zero chance produced a deposit");

			ReservoirHandler.clear();
			PetroleumConfig.cellChance = 1;
			for(int i = 0; i < 40; i++)
				assertFalse(ReservoirHandler.getReservoir(SEED, 0, i*8, 0).isEmpty(),
						"a certain chance produced an empty cell");
		}

		@Test
		@DisplayName("with no types registered every cell is empty")
		void noTypesMeansNoDeposits()
		{
			PetroleumConfig.cellChance = 1;
			assertTrue(ReservoirHandler.getReservoir(SEED, 0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("the master switch stops generation entirely")
		void masterSwitch()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			PetroleumConfig.enabled = false;
			assertTrue(ReservoirHandler.getReservoir(SEED, 0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("blacklisted dimensions never generate")
		void dimensionBlacklist()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			PetroleumConfig.dimensionBlacklist = new int[]{1, -1};
			assertTrue(ReservoirHandler.getReservoir(SEED, 1, 0, 0).isEmpty());
			assertTrue(ReservoirHandler.getReservoir(SEED, -1, 0, 0).isEmpty());
			assertFalse(ReservoirHandler.getReservoir(SEED, 0, 0, 0).isEmpty());
		}

		@Test
		@DisplayName("the same cell in different dimensions is a different deposit")
		void dimensionsAreIndependent()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			PetroleumConfig.dimensionBlacklist = new int[0];
			assertNotSame(ReservoirHandler.getReservoir(SEED, 0, 0, 0),
					ReservoirHandler.getReservoir(SEED, 2, 0, 0));
		}

		@Test
		@DisplayName("a rolled cell is cached rather than re-rolled")
		void cellsAreCached()
		{
			registerTestType();
			Reservoir first = ReservoirHandler.getReservoir(SEED, 0, 0, 0);
			assertSame(first, ReservoirHandler.getReservoir(SEED, 0, 0, 0));
			assertEquals(1, ReservoirHandler.getCachedCellCount());
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("an untouched cell is not worth saving")
		void untouchedCellsAreNotSaved()
		{
			//It re-rolls identically from the seed, so persisting it would be pure noise --
			//that is the whole reason nothing is placed and worldgen costs nothing.
			registerTestType();
			PetroleumConfig.cellChance = 1;
			ReservoirHandler.getReservoir(SEED, 0, 0, 0);
			assertEquals(1, ReservoirHandler.getCachedCellCount());
			assertTrue(ReservoirHandler.getDirtyCells().isEmpty());
		}

		@Test
		@DisplayName("a drawn-from cell is saved and restored")
		void drawnCellsRoundTrip()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			Reservoir original = ReservoirHandler.getReservoir(SEED, 0, 16, 16);
			original.deplete(500);
			int remaining = original.getRemaining();
			assertEquals(1, ReservoirHandler.getDirtyCells().size());

			NBTTagCompound nbt = ReservoirHandler.writeToNBT(new NBTTagCompound());
			ReservoirHandler.readFromNBT(nbt);

			Reservoir loaded = ReservoirHandler.getReservoir(SEED, 0, 16, 16);
			assertEquals(remaining, loaded.getRemaining());
			assertEquals(original.getOriginalCapacity(), loaded.getOriginalCapacity());
			assertEquals(original.getType(), loaded.getType());
		}

		@Test
		@DisplayName("an exhausted cell survives, so it is not silently refilled")
		void exhaustedCellRoundTrips()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			Reservoir original = ReservoirHandler.getReservoir(SEED, 0, 0, 0);
			original.deplete(original.getOriginalCapacity());

			ReservoirHandler.readFromNBT(ReservoirHandler.writeToNBT(new NBTTagCompound()));

			assertEquals(0, ReservoirHandler.getReservoir(SEED, 0, 0, 0).getRemaining(),
					"a drained field must not come back full after a reload");
		}

		@Test
		@DisplayName("reading replaces rather than merges")
		void readReplaces()
		{
			registerTestType();
			PetroleumConfig.cellChance = 1;
			ReservoirHandler.getReservoir(SEED, 0, 0, 0).deplete(100);
			ReservoirHandler.getReservoir(SEED, 0, 80, 80).deplete(100);
			assertEquals(2, ReservoirHandler.getDirtyCells().size());

			ReservoirHandler.readFromNBT(new NBTTagCompound());
			assertEquals(0, ReservoirHandler.getCachedCellCount(),
					"loading a world must not inherit the previous one's deposits");
		}

		@Test
		@DisplayName("the payload carries a version")
		void versioned()
		{
			NBTTagCompound nbt = ReservoirHandler.writeToNBT(new NBTTagCompound());
			assertEquals(ReservoirHandler.DATA_VERSION, nbt.getInteger("petroleumDataVersion"));
		}

		@Test
		@DisplayName("a malformed payload does not fail world load")
		void malformedPayloadIsSurvivable()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setString("reservoirs", "not a list");
			assertDoesNotThrow(() -> ReservoirHandler.readFromNBT(nbt));
			assertDoesNotThrow(() -> ReservoirHandler.readFromNBT(null));
		}
	}

	@Nested
	@DisplayName("cell identity")
	class Identity
	{
		@Test
		@DisplayName("equal coordinates are equal keys")
		void equality()
		{
			assertEquals(new CellPos(0, 3, 4), new CellPos(0, 3, 4));
			assertEquals(new CellPos(0, 3, 4).hashCode(), new CellPos(0, 3, 4).hashCode());
		}

		@Test
		@DisplayName("dimension is part of the identity")
		void dimensionDistinguishes()
		{
			assertNotEquals(new CellPos(0, 3, 4), new CellPos(1, 3, 4));
		}

		@Test
		@DisplayName("x and z are not interchangeable")
		void axesAreDistinct()
		{
			assertNotEquals(new CellPos(0, 3, 4), new CellPos(0, 4, 3));
		}
	}
}
