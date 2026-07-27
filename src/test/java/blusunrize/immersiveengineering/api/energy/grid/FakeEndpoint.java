/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

/**
 * A world-free stand-in for a Feed or Service Unit's tile entity.
 * <p>
 * The whole point of {@link IGridEndpoint} being a narrow port is that the tick engine can
 * be exercised against this instead of a live {@code World}. A feed's {@link #available}
 * is what its buffer plus its mounted block can supply; a service's {@link #capacity} is
 * what the machines it touches will still accept.
 */
public class FakeEndpoint implements IGridEndpoint
{
	/**
	 * Energy this endpoint can hand to the grid (FEED side).
	 */
	public int available;
	/**
	 * Energy the world will still accept through this endpoint (SERVICE side).
	 */
	public int capacity;

	public int totalExtracted;
	public int totalInserted;
	public int extractCalls;
	public int insertCalls;
	public int simulatedInsertCalls;
	public int configChangeCalls;

	/**
	 * What the world's redstone reads at this block (SIGNAL side, input mode).
	 */
	public boolean redstoneHigh;
	/**
	 * The last level the engine published here (SIGNAL side, output mode). Starts at -1 so a
	 * test can tell "never told anything" from "told zero" -- an output unit whose segment
	 * has gone away must actively be driven to 0, not merely left alone.
	 */
	public int publishedLevel = -1;
	public int publishCalls;

	public FakeEndpoint()
	{
	}

	public static FakeEndpoint feeding(int available)
	{
		FakeEndpoint endpoint = new FakeEndpoint();
		endpoint.available = available;
		return endpoint;
	}

	public static FakeEndpoint accepting(int capacity)
	{
		FakeEndpoint endpoint = new FakeEndpoint();
		endpoint.capacity = capacity;
		return endpoint;
	}

	@Override
	public int extractForGrid(int max, boolean simulate)
	{
		extractCalls++;
		int amount = Math.min(max, available);
		if(amount <= 0)
			return 0;
		if(!simulate)
		{
			available -= amount;
			totalExtracted += amount;
		}
		return amount;
	}

	@Override
	public int insertFromGrid(int max, boolean simulate)
	{
		if(simulate)
			simulatedInsertCalls++;
		else
			insertCalls++;
		int amount = Math.min(max, capacity);
		if(amount <= 0)
			return 0;
		if(!simulate)
		{
			capacity -= amount;
			totalInserted += amount;
		}
		return amount;
	}

	@Override
	public void onGridConfigChanged(GridDevice device)
	{
		configChangeCalls++;
	}

	public static FakeEndpoint signal(boolean redstoneHigh)
	{
		FakeEndpoint endpoint = new FakeEndpoint();
		endpoint.redstoneHigh = redstoneHigh;
		return endpoint;
	}

	@Override
	public boolean isRedstoneHigh()
	{
		return redstoneHigh;
	}

	@Override
	public void setRedstoneOutput(int level)
	{
		publishedLevel = level;
		publishCalls++;
	}

	/**
	 * An endpoint that lies about how much it moved. The engine has to defend against this
	 * because a real one delegates to arbitrary neighbouring mods.
	 */
	public static class Misbehaving implements IGridEndpoint
	{
		private final int overreport;

		public Misbehaving(int overreport)
		{
			this.overreport = overreport;
		}

		@Override
		public int extractForGrid(int max, boolean simulate)
		{
			return max+overreport;
		}

		@Override
		public int insertFromGrid(int max, boolean simulate)
		{
			return max+overreport;
		}
	}
}
