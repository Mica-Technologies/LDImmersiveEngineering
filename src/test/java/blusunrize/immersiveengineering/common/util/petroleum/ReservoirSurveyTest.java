/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.petroleum;

import blusunrize.immersiveengineering.api.petroleum.PetroleumConfig;
import blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport;
import blusunrize.immersiveengineering.api.petroleum.Reservoir;
import blusunrize.immersiveengineering.api.petroleum.ReservoirHandler;
import blusunrize.immersiveengineering.api.petroleum.ReservoirModel;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static blusunrize.immersiveengineering.api.petroleum.PetroleumTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a core sample records about the oil under it: the size banding, the pressure rounding,
 * and the round trip through the sample's NBT -- including what a sample cut before any of this
 * existed reads as.
 */
class ReservoirSurveyTest
{
	@BeforeEach
	void setUp()
	{
		PetroleumTestSupport.reset();
		registerTestType();
	}

	@AfterEach
	void tearDown()
	{
		PetroleumTestSupport.reset();
	}

	@Nested
	@DisplayName("size banding")
	class Sizes
	{
		@Test
		@DisplayName("the extremes of the range land in the extreme bands")
		void extremes()
		{
			assertEquals("marginal", ReservoirSurvey.sizeBand(1000, 1000, 1_000_000));
			assertEquals("giant", ReservoirSurvey.sizeBand(1_000_000, 1000, 1_000_000));
		}

		@Test
		@DisplayName("every band is reachable somewhere in the range")
		void everyBandReachable()
		{
			Set<String> seen = new HashSet<>();
			for(int capacity = 1000; capacity <= 1_000_000; capacity += 1000)
				seen.add(ReservoirSurvey.sizeBand(capacity, 1000, 1_000_000));
			for(String band : ReservoirSurvey.SIZE_BANDS)
				assertTrue(seen.contains(band), "no capacity ever reported as "+band);
		}

		@Test
		@DisplayName("bands never step backwards as capacity grows")
		void monotonic()
		{
			int previous = -1;
			for(int capacity = 1000; capacity <= 1_000_000; capacity += 250)
			{
				int index = indexOf(ReservoirSurvey.sizeBand(capacity, 1000, 1_000_000));
				assertTrue(index >= previous, "band went backwards at "+capacity);
				previous = index;
			}
		}

		@Test
		@DisplayName("a capacity outside the bounds is clamped rather than falling off the ladder")
		void outOfRangeClamps()
		{
			assertEquals("marginal", ReservoirSurvey.sizeBand(1, 1000, 1_000_000));
			assertEquals("giant", ReservoirSurvey.sizeBand(Integer.MAX_VALUE, 1000, 1_000_000));
		}

		@Test
		@DisplayName("nothing to describe gives no band")
		void emptyGivesNoBand()
		{
			assertEquals("", ReservoirSurvey.sizeBand(0, 1000, 1_000_000));
			assertEquals("", ReservoirSurvey.sizeBand(new Reservoir("", 0)));
			assertEquals("", ReservoirSurvey.sizeBand(null));
		}

		@Test
		@DisplayName("a world where every field is the same size reports the middle band")
		void degenerateRange()
		{
			assertEquals("moderate", ReservoirSurvey.sizeBand(5000, 5000, 5000));
		}

		@Test
		@DisplayName("a deposit whose type has been unregistered still gets a band")
		void unknownTypeFallsBackToConfigBounds()
		{
			PetroleumConfig.minCapacity = 1000;
			PetroleumConfig.maxCapacity = 1_000_000;
			assertEquals("giant", ReservoirSurvey.sizeBand(new Reservoir("gone_away", 1_000_000)));
		}

		@Test
		@DisplayName("bands spread out across a log-distributed roll rather than piling up low")
		void bandsSpreadOverRealRolls()
		{
			//The banding only earns its keep if real rolls actually reach the top of it; a
			//linear split would put nearly everything in the bottom band.
			Set<String> seen = new HashSet<>();
			Random random = new Random(4242);
			for(int i = 0; i < 2000; i++)
			{
				int capacity = ReservoirModel.rollCapacity(random, ReservoirHandler.getType(TEST_TYPE));
				seen.add(ReservoirSurvey.sizeBand(capacity, 1000, 1_000_000));
			}
			assertEquals(ReservoirSurvey.SIZE_BANDS.length, seen.size(),
					"log-distributed rolls did not reach every band: "+seen);
		}

		private int indexOf(String band)
		{
			for(int i = 0; i < ReservoirSurvey.SIZE_BANDS.length; i++)
				if(ReservoirSurvey.SIZE_BANDS[i].equals(band))
					return i;
			return -1;
		}
	}

	@Nested
	@DisplayName("pressure readings")
	class Pressure
	{
		@Test
		@DisplayName("a full field reads 100%, a half-drawn one reads 50%")
		void straightforward()
		{
			assertEquals(100, ReservoirSurvey.pressurePercent(full(100_000)));
			assertEquals(50, ReservoirSurvey.pressurePercent(at(100_000, 0.5)));
		}

		@Test
		@DisplayName("a field with anything left never reads as a flat zero")
		void nearlyEmptyStillReadsAboveZero()
		{
			Reservoir reservoir = full(1_000_000);
			reservoir.deplete(999_999);
			assertEquals(1, ReservoirSurvey.pressurePercent(reservoir));
		}

		@Test
		@DisplayName("a genuinely exhausted field reads zero")
		void exhaustedReadsZero()
		{
			Reservoir reservoir = full(100_000);
			reservoir.deplete(100_000);
			assertEquals(0, ReservoirSurvey.pressurePercent(reservoir));
		}

		@Test
		@DisplayName("an absent deposit reads zero rather than throwing")
		void emptyAndNull()
		{
			assertEquals(0, ReservoirSurvey.pressurePercent(new Reservoir("", 0)));
			assertEquals(0, ReservoirSurvey.pressurePercent(null));
		}
	}

	@Nested
	@DisplayName("what gets written onto a sample")
	class Written
	{
		@Test
		@DisplayName("a fresh field records its type, size, pressure and that it flows unaided")
		void fullField()
		{
			NBTTagCompound nbt = ReservoirSurvey.write(new NBTTagCompound(), full(1_000_000));
			assertTrue(ReservoirSurvey.isSurveyed(nbt));
			assertTrue(ReservoirSurvey.hasReservoir(nbt));
			assertEquals(TEST_TYPE, ReservoirSurvey.getType(nbt));
			assertEquals("giant", ReservoirSurvey.getSizeBand(nbt));
			assertEquals(100, ReservoirSurvey.getPressure(nbt));
			assertTrue(ReservoirSurvey.isFreeFlowing(nbt));
		}

		@Test
		@DisplayName("a field below the free-flow threshold records that it needs a pump")
		void drawnDownField()
		{
			NBTTagCompound nbt = ReservoirSurvey.write(new NBTTagCompound(), at(1_000_000, 0.2));
			assertTrue(ReservoirSurvey.hasReservoir(nbt));
			assertEquals(20, ReservoirSurvey.getPressure(nbt));
			assertFalse(ReservoirSurvey.isFreeFlowing(nbt));
		}

		@Test
		@DisplayName("an empty cell is recorded as surveyed but barren, not as unsurveyed")
		void barrenCell()
		{
			NBTTagCompound nbt = ReservoirSurvey.write(new NBTTagCompound(), new Reservoir("", 0));
			assertTrue(ReservoirSurvey.isSurveyed(nbt), "a barren survey is still a survey");
			assertFalse(ReservoirSurvey.hasReservoir(nbt));
		}

		@Test
		@DisplayName("a deposit whose type was removed is still reported as being there")
		void unregisteredTypeStillCounts()
		{
			PetroleumConfig.minCapacity = 1000;
			PetroleumConfig.maxCapacity = 1_000_000;
			NBTTagCompound nbt = ReservoirSurvey.write(new NBTTagCompound(),
					new Reservoir("gone_away", 500_000));
			assertTrue(ReservoirSurvey.hasReservoir(nbt));
			assertEquals("gone_away", ReservoirSurvey.getType(nbt));
		}

		@Test
		@DisplayName("writing nothing at all still marks the sample as surveyed")
		void nullReservoir()
		{
			NBTTagCompound nbt = ReservoirSurvey.write(new NBTTagCompound(), null);
			assertTrue(ReservoirSurvey.isSurveyed(nbt));
			assertFalse(ReservoirSurvey.hasReservoir(nbt));
		}
	}

	@Nested
	@DisplayName("samples from before oil existed")
	class Legacy
	{
		@Test
		@DisplayName("an old sample reads as unsurveyed, not as barren")
		void oldSampleIsSilent()
		{
			//The distinction the display hangs off: an old sample must say nothing about oil
			//rather than claiming the ground under it is dry.
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setString("mineral", "Iron");
			nbt.setInteger("depletion", 12);
			assertFalse(ReservoirSurvey.isSurveyed(nbt));
			assertFalse(ReservoirSurvey.hasReservoir(nbt));
		}

		@Test
		@DisplayName("reading oil fields off a sample that has none yields blanks, not exceptions")
		void accessorsTolerateMissingData()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			assertEquals("", ReservoirSurvey.getType(nbt));
			assertEquals("", ReservoirSurvey.getSizeBand(nbt));
			assertEquals(0, ReservoirSurvey.getPressure(nbt));
			assertFalse(ReservoirSurvey.isFreeFlowing(nbt));
		}

		@Test
		@DisplayName("a null tag is tolerated everywhere")
		void nullTag()
		{
			assertFalse(ReservoirSurvey.isSurveyed(null));
			assertFalse(ReservoirSurvey.hasReservoir(null));
			assertEquals("", ReservoirSurvey.getType(null));
			assertEquals("", ReservoirSurvey.getSizeBand(null));
			assertEquals(0, ReservoirSurvey.getPressure(null));
			assertFalse(ReservoirSurvey.isFreeFlowing(null));
			assertNull(ReservoirSurvey.write(null, full(1000)));
		}
	}

	@Nested
	@DisplayName("city mode")
	class CityModeBehaviour
	{
		@Test
		@DisplayName("a field that never depletes keeps reporting itself as full and free-flowing")
		void neverDepletingFieldReadsFull()
		{
			//City mode leaves the pool alone rather than teaching the survey a special case, so
			//the reading a sample takes is the same one it would take on a fresh field forever.
			Reservoir reservoir = full(1_000_000);
			NBTTagCompound before = ReservoirSurvey.write(new NBTTagCompound(), reservoir);
			ReservoirModel.extract(reservoir, 10_000, 100, true, true, false);
			NBTTagCompound after = ReservoirSurvey.write(new NBTTagCompound(), reservoir);
			assertEquals(ReservoirSurvey.getPressure(before), ReservoirSurvey.getPressure(after));
			assertEquals(ReservoirSurvey.getSizeBand(before), ReservoirSurvey.getSizeBand(after));
			assertTrue(ReservoirSurvey.isFreeFlowing(after));
		}
	}
}
