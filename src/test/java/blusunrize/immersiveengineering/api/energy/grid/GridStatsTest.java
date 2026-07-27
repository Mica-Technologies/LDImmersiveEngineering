/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GridStats} runs on every segment every tick, so its behaviour under long runs
 * (ring wrap, accumulator reset, lifetime totals) matters more than its first few samples.
 */
class GridStatsTest
{
	/**
	 * Runs {@code ticks} ticks, feeding the same in/out each tick.
	 */
	private static void run(GridStats stats, int ticks, int in, int out)
	{
		for(int i = 0; i < ticks; i++)
		{
			stats.beginTick();
			stats.recordIn(in);
			stats.recordOut(out);
			stats.endTick();
		}
	}

	@Nested
	@DisplayName("per-tick counters")
	class PerTick
	{
		@Test
		@DisplayName("a fresh instance reads zero everywhere")
		void freshIsZero()
		{
			GridStats stats = new GridStats();
			assertEquals(0, stats.getTickIn());
			assertEquals(0, stats.getTickOut());
			assertEquals(0, stats.getLastTickIn());
			assertEquals(0, stats.getLastTickOut());
			assertEquals(0, stats.getLifetimeIn());
			assertEquals(0, stats.getLifetimeOut());
			assertEquals(0, stats.getSampleCount());
		}

		@Test
		@DisplayName("records accumulate within a tick")
		void recordsAccumulateWithinTick()
		{
			GridStats stats = new GridStats();
			stats.beginTick();
			stats.recordIn(10);
			stats.recordIn(15);
			stats.recordOut(4);
			assertEquals(25, stats.getTickIn());
			assertEquals(4, stats.getTickOut());
		}

		@Test
		@DisplayName("beginTick rolls the previous tick into lastTick")
		void beginTickRollsPrevious()
		{
			GridStats stats = new GridStats();
			stats.beginTick();
			stats.recordIn(30);
			stats.recordOut(20);
			stats.endTick();
			stats.beginTick();
			assertEquals(30, stats.getLastTickIn());
			assertEquals(20, stats.getLastTickOut());
			assertEquals(0, stats.getTickIn(), "current tick must start clean");
			assertEquals(0, stats.getTickOut());
		}

		@Test
		@DisplayName("non-positive records are ignored")
		void nonPositiveIgnored()
		{
			GridStats stats = new GridStats();
			stats.beginTick();
			stats.recordIn(0);
			stats.recordIn(-50);
			stats.recordOut(0);
			stats.recordOut(-50);
			assertEquals(0, stats.getTickIn());
			assertEquals(0, stats.getTickOut());
			assertEquals(0, stats.getLifetimeIn());
			assertEquals(0, stats.getLifetimeOut());
		}
	}

	@Nested
	@DisplayName("lifetime totals")
	class Lifetime
	{
		@Test
		@DisplayName("accumulate across ticks")
		void accumulateAcrossTicks()
		{
			GridStats stats = new GridStats();
			run(stats, 10, 5, 3);
			assertEquals(50, stats.getLifetimeIn());
			assertEquals(30, stats.getLifetimeOut());
		}

		@Test
		@DisplayName("survive far past what an int would hold")
		void exceedIntRange()
		{
			GridStats stats = new GridStats();
			for(int i = 0; i < 5; i++)
			{
				stats.beginTick();
				stats.recordIn(Integer.MAX_VALUE);
				stats.endTick();
			}
			assertEquals(5L*Integer.MAX_VALUE, stats.getLifetimeIn());
			assertTrue(stats.getLifetimeIn() > Integer.MAX_VALUE);
		}

		@Test
		@DisplayName("are kept by resetHistory -- a meter reading is not a measurement")
		void survivesHistoryReset()
		{
			GridStats stats = new GridStats();
			run(stats, 40, 5, 3);
			stats.resetHistory();
			assertEquals(200, stats.getLifetimeIn());
			assertEquals(120, stats.getLifetimeOut());
			assertEquals(0, stats.getSampleCount());
		}
	}

	@Nested
	@DisplayName("one-second history")
	class History
	{
		@Test
		@DisplayName("no sample is recorded before a full second elapses")
		void noSampleBeforeASecond()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE-1, 10, 10);
			assertEquals(0, stats.getSampleCount());
		}

		@Test
		@DisplayName("a sample lands exactly on the twentieth tick")
		void sampleOnTwentiethTick()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 10, 4);
			assertEquals(1, stats.getSampleCount());
			assertArrayEquals(new int[]{200}, stats.getHistoryIn());
			assertArrayEquals(new int[]{80}, stats.getHistoryOut());
		}

		@Test
		@DisplayName("the accumulator resets between samples")
		void accumulatorResets()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 10, 0);
			run(stats, GridStats.TICKS_PER_SAMPLE, 1, 0);
			assertArrayEquals(new int[]{200, 20}, stats.getHistoryIn());
		}

		@Test
		@DisplayName("history is ordered oldest first")
		void historyIsOldestFirst()
		{
			GridStats stats = new GridStats();
			for(int second = 1; second <= 3; second++)
				run(stats, GridStats.TICKS_PER_SAMPLE, second, 0);
			assertArrayEquals(new int[]{20, 40, 60}, stats.getHistoryIn());
		}

		@Test
		@DisplayName("sample count saturates at the retained window")
		void sampleCountSaturates()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE*(GridStats.HISTORY_SECONDS+30), 1, 1);
			assertEquals(GridStats.HISTORY_SECONDS, stats.getSampleCount());
			assertEquals(GridStats.HISTORY_SECONDS, stats.getHistoryIn().length);
		}

		@Test
		@DisplayName("the ring keeps oldest-first ordering after it wraps")
		void orderingSurvivesWrap()
		{
			GridStats stats = new GridStats();
			//Second n contributes n IF/tick, so sample n is 20n. Run one full window plus
			//five more seconds, so the ring has wrapped.
			int seconds = GridStats.HISTORY_SECONDS+5;
			for(int second = 1; second <= seconds; second++)
				run(stats, GridStats.TICKS_PER_SAMPLE, second, 0);

			int[] history = stats.getHistoryIn();
			assertEquals(GridStats.HISTORY_SECONDS, history.length);
			//The oldest retained second is (seconds - HISTORY_SECONDS + 1).
			int oldestSecond = seconds-GridStats.HISTORY_SECONDS+1;
			assertEquals(20*oldestSecond, history[0], "oldest sample first");
			assertEquals(20*seconds, history[history.length-1], "newest sample last");
			for(int i = 1; i < history.length; i++)
				assertTrue(history[i] > history[i-1], "history must stay monotonic after wrap");
		}

		@Test
		@DisplayName("in and out histories are tracked independently")
		void inAndOutAreIndependent()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 7, 3);
			assertArrayEquals(new int[]{140}, stats.getHistoryIn());
			assertArrayEquals(new int[]{60}, stats.getHistoryOut());
		}

		@Test
		@DisplayName("a returned history array is a copy, not the live ring")
		void historyIsACopy()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 5, 5);
			int[] first = stats.getHistoryIn();
			first[0] = 999999;
			assertEquals(100, stats.getHistoryIn()[0]);
		}

		@Test
		@DisplayName("a second's worth of huge throughput saturates instead of overflowing")
		void sampleSaturatesInsteadOfOverflowing()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, Integer.MAX_VALUE, 0);
			assertEquals(Integer.MAX_VALUE, stats.getHistoryIn()[0]);
		}
	}

	@Nested
	@DisplayName("peaks")
	class Peaks
	{
		@Test
		@DisplayName("peaks are zero with no history")
		void peaksStartAtZero()
		{
			GridStats stats = new GridStats();
			assertEquals(0, stats.getPeakIn());
			assertEquals(0, stats.getPeakOut());
		}

		@Test
		@DisplayName("peak reports the highest retained second")
		void peakReportsHighestSecond()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 5, 1);
			run(stats, GridStats.TICKS_PER_SAMPLE, 50, 9);
			run(stats, GridStats.TICKS_PER_SAMPLE, 2, 4);
			assertEquals(1000, stats.getPeakIn());
			assertEquals(180, stats.getPeakOut());
		}

		@Test
		@DisplayName("peaks are cleared by resetHistory")
		void peaksClearedByReset()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE, 50, 50);
			stats.resetHistory();
			assertEquals(0, stats.getPeakIn());
			assertEquals(0, stats.getPeakOut());
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("lifetime totals round-trip")
		void lifetimeRoundTrips()
		{
			GridStats stats = new GridStats();
			run(stats, 100, 7, 3);
			GridStats loaded = new GridStats();
			loaded.readFromNBT(stats.writeToNBT(new NBTTagCompound()));
			assertEquals(700, loaded.getLifetimeIn());
			assertEquals(300, loaded.getLifetimeOut());
		}

		@Test
		@DisplayName("live history is deliberately not persisted")
		void historyIsNotPersisted()
		{
			GridStats stats = new GridStats();
			run(stats, GridStats.TICKS_PER_SAMPLE*3, 10, 10);
			GridStats loaded = new GridStats();
			loaded.readFromNBT(stats.writeToNBT(new NBTTagCompound()));
			assertEquals(0, loaded.getSampleCount(), "history is a live view, not save data");
		}

		@Test
		@DisplayName("null NBT is tolerated")
		void nullNbtTolerated()
		{
			GridStats stats = new GridStats();
			assertDoesNotThrow(() -> stats.readFromNBT(null));
			assertEquals(0, stats.getLifetimeIn());
		}

		@Test
		@DisplayName("a negative lifetime in a corrupt save loads as zero")
		void negativeLifetimeClamped()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setLong("lifetimeIn", -500);
			nbt.setLong("lifetimeOut", -1);
			GridStats stats = new GridStats();
			stats.readFromNBT(nbt);
			assertEquals(0, stats.getLifetimeIn());
			assertEquals(0, stats.getLifetimeOut());
		}
	}
}
