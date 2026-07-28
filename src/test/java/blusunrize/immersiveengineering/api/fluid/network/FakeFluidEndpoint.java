/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

/**
 * A world-free stand-in for an Inlet's or Outlet's tile entity.
 * <p>
 * The whole point of {@link IFluidEndpoint} being a narrow port is that the tick engine can be
 * exercised against this instead of a live {@code World} and a real fluid registry. An inlet's
 * {@link #available} is what its buffer plus its mounted block can supply; an outlet's
 * {@link #capacity} is what the machines it touches will still accept.
 * <p>
 * The fluid is a plain string here for the same reason it is one in the model: {@code Fluid}
 * cannot be constructed without a running game, and the engine has no business knowing about it.
 */
public class FakeFluidEndpoint implements IFluidEndpoint
{
	/**
	 * The fluid this endpoint deals in. An inlet holding something else supplies nothing; an
	 * outlet offered something else accepts nothing.
	 */
	public String fluid;
	/**
	 * Millibuckets this endpoint can hand to the main (INLET side).
	 */
	public int available;
	/**
	 * Millibuckets the world will still accept through this endpoint (OUTLET side).
	 */
	public int capacity;

	public int totalExtracted;
	public int totalInserted;
	public int extractCalls;
	public int insertCalls;
	public int simulatedInsertCalls;
	public int configChangeCalls;
	/**
	 * The fluid name the last insert was offered, so a test can prove the engine passes the
	 * main's type through rather than the endpoint's own.
	 */
	public String lastOffered;

	/**
	 * What the world's redstone reads at this block (VALVE side, input mode).
	 */
	public boolean redstoneHigh;
	/**
	 * The last level the engine published here (VALVE side, output mode). Starts at -1 so a test
	 * can tell "never told anything" from "told zero" -- an output valve whose main has gone away
	 * must actively be driven to 0, not merely left alone.
	 */
	public int publishedLevel = -1;
	public int publishCalls;

	public FakeFluidEndpoint()
	{
	}

	public static FakeFluidEndpoint supplying(String fluid, int available)
	{
		FakeFluidEndpoint endpoint = new FakeFluidEndpoint();
		endpoint.fluid = fluid;
		endpoint.available = available;
		return endpoint;
	}

	public static FakeFluidEndpoint accepting(String fluid, int capacity)
	{
		FakeFluidEndpoint endpoint = new FakeFluidEndpoint();
		endpoint.fluid = fluid;
		endpoint.capacity = capacity;
		return endpoint;
	}

	public static FakeFluidEndpoint valve(boolean redstoneHigh)
	{
		FakeFluidEndpoint endpoint = new FakeFluidEndpoint();
		endpoint.redstoneHigh = redstoneHigh;
		return endpoint;
	}

	@Override
	public String getOfferedFluid()
	{
		return available > 0?fluid: null;
	}

	@Override
	public int extractForMain(String wanted, int max, boolean simulate)
	{
		extractCalls++;
		lastOffered = wanted;
		if(wanted==null||!wanted.equals(fluid))
			return 0;
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
	public int insertFromMain(String offered, int max, boolean simulate)
	{
		if(simulate)
			simulatedInsertCalls++;
		else
			insertCalls++;
		lastOffered = offered;
		if(offered==null||!offered.equals(fluid))
			return 0;
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
	public void onNetConfigChanged(FluidDevice device)
	{
		configChangeCalls++;
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
	 * An endpoint that lies about how much it moved. The engine has to defend against this because
	 * a real one delegates to arbitrary neighbouring mods.
	 */
	public static class Misbehaving implements IFluidEndpoint
	{
		private final String fluid;
		private final int overreport;

		public Misbehaving(String fluid, int overreport)
		{
			this.fluid = fluid;
			this.overreport = overreport;
		}

		@Override
		public String getOfferedFluid()
		{
			return fluid;
		}

		@Override
		public int extractForMain(String wanted, int max, boolean simulate)
		{
			return max+overreport;
		}

		@Override
		public int insertFromMain(String offered, int max, boolean simulate)
		{
			return max+overreport;
		}
	}
}
