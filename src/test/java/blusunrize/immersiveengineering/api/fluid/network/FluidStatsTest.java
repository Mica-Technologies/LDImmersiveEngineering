/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FluidStats}: the per-main throughput ledger and the console's graph.
 * <p>
 * The mirror of {@code GridStatsTest}. The ring buffer is the part worth testing: it is written
 * once a second, read in a different order than it is stored, and its head wraps -- so "the graph
 * is drawn backwards" and "the graph starts full of zeroes" are both one arithmetic slip away, and
 * neither would ever crash.
 */
class FluidStatsTest
{
	/**
	 * Runs a whole second of ticks so one history sample lands.
	 */
	private static void second(FluidStats stats, int in, int out)
	{
		for(int tick = 0; tick < FluidStats.TICKS_PER_SAMPLE; tick++)
		{
			stats.beginTick();
			if(tick==0)
			{
				stats.recordIn(in);
				stats.recordOut(out);
			}
			stats.endTick();
		}
	}

	@Nested
	@DisplayName("per-tick counters")
	class PerTick
	{
		@Test
		@DisplayName("beginTick rolls the previous tick's figures into lastTick")
		void beginTickRolls()
		{
			//A GUI reading mid-tick must never see a half-accumulated value.
			FluidStats stats = new FluidStats();
			stats.beginTick();
			stats.recordIn(30);
			stats.recordOut(20);
			assertEquals(30, stats.getTickIn());
			stats.beginTick();
			assertEquals(30, stats.getLastTickIn());
			assertEquals(20, stats.getLastTickOut());
			assertEquals(0, stats.getTickIn(), "the running counter starts fresh");
		}

		@Test
		@DisplayName("non-positive amounts are ignored")
		void nonPositiveIgnored()
		{
			FluidStats stats = new FluidStats();
			stats.beginTick();
			stats.recordIn(0);
			stats.recordIn(-5);
			stats.recordOut(-5);
			assertEquals(0, stats.getTickIn());
			assertEquals(0, stats.getLifetimeIn());
		}

		@Test
		@DisplayName("lifetime totals only ever climb")
		void lifetimeAccumulates()
		{
			FluidStats stats = new FluidStats();
			for(int i = 0; i < 5; i++)
			{
				stats.beginTick();
				stats.recordIn(10);
				stats.recordOut(4);
			}
			assertEquals(50, stats.getLifetimeIn());
			assertEquals(20, stats.getLifetimeOut());
		}
	}

	@Nested
	@DisplayName("the history ring")
	class History
	{
		@Test
		@DisplayName("a sample lands once per second, not once per tick")
		void samplesArePerSecond()
		{
			FluidStats stats = new FluidStats();
			for(int tick = 0; tick < FluidStats.TICKS_PER_SAMPLE-1; tick++)
			{
				stats.beginTick();
				stats.recordOut(1);
				stats.endTick();
			}
			assertEquals(0, stats.getSampleCount(), "not a full second yet");
			stats.beginTick();
			stats.recordOut(1);
			stats.endTick();
			assertEquals(1, stats.getSampleCount());
		}

		@Test
		@DisplayName("a sample is the sum of its second")
		void sampleSumsTheSecond()
		{
			FluidStats stats = new FluidStats();
			for(int tick = 0; tick < FluidStats.TICKS_PER_SAMPLE; tick++)
			{
				stats.beginTick();
				stats.recordOut(5);
				stats.endTick();
			}
			assertArrayEquals(new int[]{5*FluidStats.TICKS_PER_SAMPLE}, stats.getHistoryOut());
		}

		@Test
		@DisplayName("history comes back oldest first while the ring is filling")
		void orderWhileFilling()
		{
			FluidStats stats = new FluidStats();
			second(stats, 1, 10);
			second(stats, 2, 20);
			second(stats, 3, 30);
			assertArrayEquals(new int[]{10, 20, 30}, stats.getHistoryOut());
			assertArrayEquals(new int[]{1, 2, 3}, stats.getHistoryIn());
		}

		@Test
		@DisplayName("history stays oldest first after the ring wraps")
		void orderAfterWrapping()
		{
			//The case that gets it wrong: once the buffer is full the head is also the oldest slot,
			//and reading from index zero would draw the graph with a discontinuity in the middle.
			FluidStats stats = new FluidStats();
			for(int i = 1; i <= FluidStats.HISTORY_SECONDS+3; i++)
				second(stats, 0, i);

			int[] history = stats.getHistoryOut();
			assertEquals(FluidStats.HISTORY_SECONDS, history.length);
			assertEquals(4, history[0], "the oldest surviving sample");
			assertEquals(FluidStats.HISTORY_SECONDS+3, history[history.length-1], "the newest");
			for(int i = 1; i < history.length; i++)
				assertEquals(history[i-1]+1, history[i], "the graph must not jump");
		}

		@Test
		@DisplayName("the ring never grows past its length")
		void ringIsBounded()
		{
			FluidStats stats = new FluidStats();
			for(int i = 0; i < FluidStats.HISTORY_SECONDS*3; i++)
				second(stats, 0, 1);
			assertEquals(FluidStats.HISTORY_SECONDS, stats.getSampleCount());
		}

		@Test
		@DisplayName("peaks are the largest sample retained")
		void peaks()
		{
			FluidStats stats = new FluidStats();
			second(stats, 5, 50);
			second(stats, 90, 9);
			second(stats, 5, 50);
			assertEquals(90, stats.getPeakIn());
			assertEquals(50, stats.getPeakOut());
		}
	}

	@Nested
	@DisplayName("resetting")
	class Reset
	{
		@Test
		@DisplayName("resetHistory clears the graph and keeps the meter")
		void resetKeepsLifetime()
		{
			//The lifetime totals are a meter reading rather than a measurement, and a meter that
			//zeroes itself when someone clears a graph is not a meter.
			FluidStats stats = new FluidStats();
			second(stats, 100, 200);
			stats.resetHistory();
			assertEquals(0, stats.getSampleCount());
			assertEquals(0, stats.getLastTickOut());
			assertEquals(100, stats.getLifetimeIn());
			assertEquals(200, stats.getLifetimeOut());
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("save data keeps the meter and drops the graph")
		void saveKeepsOnlyLifetime()
		{
			//The history is a live view of the last minute; persisting it would only reload a stale
			//graph that the next second overwrites anyway.
			FluidStats stats = new FluidStats();
			second(stats, 10, 20);
			NBTTagCompound saved = stats.writeToNBT(new NBTTagCompound());
			assertFalse(saved.hasKey("histOut"));

			FluidStats loaded = new FluidStats();
			loaded.readFromNBT(saved);
			assertEquals(10, loaded.getLifetimeIn());
			assertEquals(0, loaded.getSampleCount());
		}

		@Test
		@DisplayName("a GUI sync carries the graph as well")
		void syncCarriesHistory()
		{
			FluidStats stats = new FluidStats();
			second(stats, 10, 20);
			second(stats, 30, 40);

			FluidStats loaded = new FluidStats();
			loaded.readFromNBT(stats.writeToNBT(new NBTTagCompound(), true));
			assertEquals(2, loaded.getSampleCount());
			assertArrayEquals(new int[]{20, 40}, loaded.getHistoryOut());
		}

		@Test
		@DisplayName("a payload from a build with a different history length does not throw")
		void lengthTolerantRead()
		{
			//It shows a shorter graph rather than crashing, which is the only sane outcome for a
			//cosmetic buffer.
			NBTTagCompound odd = new NBTTagCompound();
			odd.setIntArray("histOut", new int[]{1, 2, 3});
			odd.setInteger("samples", 3);
			FluidStats loaded = new FluidStats();
			loaded.readFromNBT(odd);
			assertEquals(3, loaded.getSampleCount());
		}

		@Test
		@DisplayName("a corrupt head index is wrapped rather than trusted")
		void headIsWrapped()
		{
			NBTTagCompound bad = new NBTTagCompound();
			bad.setInteger("histHead", -12345);
			bad.setInteger("samples", 999999);
			FluidStats loaded = new FluidStats();
			loaded.readFromNBT(bad);
			assertTrue(loaded.getSampleCount() <= FluidStats.HISTORY_SECONDS);
			assertDoesNotThrow(loaded::getHistoryOut, "a bad head must not walk off the array");
		}

		@Test
		@DisplayName("negative lifetime totals in a hand-edited save are clamped")
		void negativeLifetimeClamped()
		{
			NBTTagCompound tampered = new NBTTagCompound();
			tampered.setLong("lifetimeIn", -50);
			FluidStats loaded = new FluidStats();
			loaded.readFromNBT(tampered);
			assertEquals(0, loaded.getLifetimeIn());
		}

		@Test
		@DisplayName("reading nothing is safe")
		void readingNullIsSafe()
		{
			FluidStats stats = new FluidStats();
			assertDoesNotThrow(() -> stats.readFromNBT(null));
		}
	}
}
