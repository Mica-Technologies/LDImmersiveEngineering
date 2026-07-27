/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Throughput bookkeeping for one segment.
 * <p>
 * Steady-state cost is two int adds per tick; the per-second history is a pair of
 * fixed primitive ring buffers, so nothing is allocated while the server runs. Only
 * the lifetime totals are persisted -- the history is a live view, not save data.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridStats
{
	/**
	 * Seconds of history kept for the console's graph.
	 */
	public static final int HISTORY_SECONDS = 60;
	public static final int TICKS_PER_SAMPLE = 20;

	private int tickIn;
	private int tickOut;
	private int lastTickIn;
	private int lastTickOut;

	private final int[] historyIn = new int[HISTORY_SECONDS];
	private final int[] historyOut = new int[HISTORY_SECONDS];
	/**
	 * Index the next completed sample will be written to.
	 */
	private int historyHead;
	private int samplesRecorded;

	private long accumIn;
	private long accumOut;
	private int accumTicks;

	private long lifetimeIn;
	private long lifetimeOut;

	/**
	 * Starts a fresh tick. Rolls the previous tick's counters into {@code lastTick*} so a
	 * GUI reading mid-tick never sees a half-accumulated value.
	 */
	public void beginTick()
	{
		lastTickIn = tickIn;
		lastTickOut = tickOut;
		tickIn = 0;
		tickOut = 0;
	}

	public void recordIn(int amount)
	{
		if(amount <= 0)
			return;
		tickIn += amount;
		lifetimeIn += amount;
	}

	public void recordOut(int amount)
	{
		if(amount <= 0)
			return;
		tickOut += amount;
		lifetimeOut += amount;
	}

	/**
	 * Closes the tick, folding it into the current one-second sample and rolling the ring
	 * buffer when a full second has elapsed.
	 */
	public void endTick()
	{
		accumIn += tickIn;
		accumOut += tickOut;
		if(++accumTicks >= TICKS_PER_SAMPLE)
		{
			historyIn[historyHead] = (int)Math.min(accumIn, Integer.MAX_VALUE);
			historyOut[historyHead] = (int)Math.min(accumOut, Integer.MAX_VALUE);
			historyHead = (historyHead+1)%HISTORY_SECONDS;
			if(samplesRecorded < HISTORY_SECONDS)
				samplesRecorded++;
			accumIn = 0;
			accumOut = 0;
			accumTicks = 0;
		}
	}

	/**
	 * @return energy taken in during the tick currently being processed
	 */
	public int getTickIn()
	{
		return tickIn;
	}

	/**
	 * @return energy delivered during the tick currently being processed
	 */
	public int getTickOut()
	{
		return tickOut;
	}

	public int getLastTickIn()
	{
		return lastTickIn;
	}

	public int getLastTickOut()
	{
		return lastTickOut;
	}

	public long getLifetimeIn()
	{
		return lifetimeIn;
	}

	public long getLifetimeOut()
	{
		return lifetimeOut;
	}

	public int getSampleCount()
	{
		return samplesRecorded;
	}

	/**
	 * @return the per-second intake history, oldest sample first, length
	 * {@link #getSampleCount()}
	 */
	public int[] getHistoryIn()
	{
		return copyOrdered(historyIn);
	}

	/**
	 * @return the per-second delivery history, oldest sample first, length
	 * {@link #getSampleCount()}
	 */
	public int[] getHistoryOut()
	{
		return copyOrdered(historyOut);
	}

	private int[] copyOrdered(int[] ring)
	{
		int[] out = new int[samplesRecorded];
		//The ring is full once samplesRecorded saturates, at which point head is also the
		//oldest slot. Before that, samples start at 0 and head is one past the newest.
		int start = samplesRecorded < HISTORY_SECONDS?0: historyHead;
		for(int i = 0; i < samplesRecorded; i++)
			out[i] = ring[(start+i)%HISTORY_SECONDS];
		return out;
	}

	/**
	 * @return the highest one-second delivery seen in the retained history
	 */
	public int getPeakOut()
	{
		int peak = 0;
		for(int i = 0; i < samplesRecorded; i++)
			if(historyOut[i] > peak)
				peak = historyOut[i];
		return peak;
	}

	/**
	 * @return the highest one-second intake seen in the retained history
	 */
	public int getPeakIn()
	{
		int peak = 0;
		for(int i = 0; i < samplesRecorded; i++)
			if(historyIn[i] > peak)
				peak = historyIn[i];
		return peak;
	}

	/**
	 * Drops the live history and per-tick counters but keeps the lifetime totals, which
	 * are a meter reading rather than a measurement.
	 */
	public void resetHistory()
	{
		java.util.Arrays.fill(historyIn, 0);
		java.util.Arrays.fill(historyOut, 0);
		historyHead = 0;
		samplesRecorded = 0;
		accumIn = 0;
		accumOut = 0;
		accumTicks = 0;
		tickIn = 0;
		tickOut = 0;
		lastTickIn = 0;
		lastTickOut = 0;
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return writeToNBT(nbt, false);
	}

	/**
	 * @param live also include the running counters and the history ring. Set when writing
	 *             for a GUI sync and clear when writing save data -- the history is a live
	 *             view of the last minute, and persisting it would only reload a stale
	 *             graph that the next second overwrites anyway.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setLong("lifetimeIn", lifetimeIn);
		nbt.setLong("lifetimeOut", lifetimeOut);
		if(live)
		{
			nbt.setInteger("lastIn", lastTickIn);
			nbt.setInteger("lastOut", lastTickOut);
			nbt.setIntArray("histIn", historyIn);
			nbt.setIntArray("histOut", historyOut);
			nbt.setInteger("histHead", historyHead);
			nbt.setInteger("samples", samplesRecorded);
		}
		return nbt;
	}

	public void readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null)
			return;
		lifetimeIn = Math.max(0, nbt.getLong("lifetimeIn"));
		lifetimeOut = Math.max(0, nbt.getLong("lifetimeOut"));
		lastTickIn = nbt.getInteger("lastIn");
		lastTickOut = nbt.getInteger("lastOut");
		copyHistory(nbt.getIntArray("histIn"), historyIn);
		copyHistory(nbt.getIntArray("histOut"), historyOut);
		historyHead = Math.floorMod(nbt.getInteger("histHead"), HISTORY_SECONDS);
		samplesRecorded = Math.max(0, Math.min(HISTORY_SECONDS, nbt.getInteger("samples")));
	}

	/**
	 * Length-tolerant copy: a payload written by a build with a different
	 * {@link #HISTORY_SECONDS} must not throw, it must just show a shorter graph.
	 */
	private static void copyHistory(int[] from, int[] into)
	{
		if(from==null)
			return;
		int n = Math.min(from.length, into.length);
		System.arraycopy(from, 0, into, 0, n);
		for(int i = n; i < into.length; i++)
			into[i] = 0;
	}
}
