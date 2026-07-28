/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import net.minecraft.nbt.NBTTagCompound;

/**
 * The per-main transfer rules: how much may flow in and out per tick, how much leaks away on the
 * journey, how much line pack the main holds, and when backup mains step in.
 * <p>
 * Every setter clamps, so a value written by a GUI, a command, or a hand-edited save can never
 * put a main outside the bounds {@link FluidNetConfig} allows.
 * <p>
 * The deliberate mirror of {@code GridPolicy}, down to the schedule semantics -- a window that
 * wraps midnight, and equal endpoints meaning "never" rather than "always", because a schedule
 * that never runs is a visible mistake while one that always runs hides the typo.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidPolicy
{
	private int maxInput;
	private int maxOutput;
	private double leakPct;
	private int packCap;
	private boolean failoverTopUp;

	//	=================================
	//		SCHEDULE
	//	=================================
	/**
	 * Ticks of the Minecraft day, 0-23999. 0 is sunrise, 12000 dusk, 18000 midnight.
	 */
	public static final int DAY_LENGTH = 24000;
	/**
	 * Dusk, in day-time ticks.
	 */
	public static final int DEFAULT_ON = 12000;
	/**
	 * Dawn, in day-time ticks.
	 */
	public static final int DEFAULT_OFF = 23000;

	private boolean scheduleEnabled;
	private int scheduleOn = DEFAULT_ON;
	private int scheduleOff = DEFAULT_OFF;

	/**
	 * Creates a policy carrying the current configured defaults.
	 */
	public FluidPolicy()
	{
		this.maxInput = FluidNetConfig.maxMainIO;
		this.maxOutput = FluidNetConfig.maxMainIO;
		this.leakPct = FluidNetConfig.defaultLeakPct;
		this.packCap = defaultPackCap(this.maxOutput);
		this.failoverTopUp = FluidNetConfig.failoverTopUpDefault;
		clamp();
	}

	/**
	 * The line pack a main gets when nobody has chosen one: {@link FluidNetConfig#packTicks}
	 * ticks of its own output rate. Enough to smooth the collect/serve phase ordering, far too
	 * little to be used as a tank.
	 */
	public static int defaultPackCap(int maxOutput)
	{
		long cap = (long)Math.max(0, maxOutput)*Math.max(1, FluidNetConfig.packTicks);
		return (int)Math.min(cap, FluidNetConfig.packCapMax);
	}

	public int getMaxInput()
	{
		return maxInput;
	}

	public void setMaxInput(int maxInput)
	{
		this.maxInput = clampIO(maxInput);
	}

	public int getMaxOutput()
	{
		return maxOutput;
	}

	public void setMaxOutput(int maxOutput)
	{
		this.maxOutput = clampIO(maxOutput);
	}

	/**
	 * @return the fraction of everything entering the main that never arrives, 0..1
	 */
	public double getLeakPct()
	{
		return leakPct;
	}

	public void setLeakPct(double leakPct)
	{
		this.leakPct = leakPct < 0?0: leakPct > 1?1: leakPct;
	}

	public int getPackCap()
	{
		return packCap;
	}

	public void setPackCap(int packCap)
	{
		this.packCap = packCap < 0?0: Math.min(packCap, FluidNetConfig.packCapMax);
	}

	/**
	 * @return true if backup mains also cover ordinary shortfalls, not just outages
	 */
	public boolean isFailoverTopUp()
	{
		return failoverTopUp;
	}

	public void setFailoverTopUp(boolean failoverTopUp)
	{
		this.failoverTopUp = failoverTopUp;
	}

	//	=================================
	//		SCHEDULE
	//	=================================

	public boolean isScheduleEnabled()
	{
		return scheduleEnabled;
	}

	public void setScheduleEnabled(boolean scheduleEnabled)
	{
		this.scheduleEnabled = scheduleEnabled;
	}

	public int getScheduleOn()
	{
		return scheduleOn;
	}

	public void setScheduleOn(int scheduleOn)
	{
		this.scheduleOn = wrapDayTime(scheduleOn);
	}

	public int getScheduleOff()
	{
		return scheduleOff;
	}

	public void setScheduleOff(int scheduleOff)
	{
		this.scheduleOff = wrapDayTime(scheduleOff);
	}

	/**
	 * Is the clock inside this main's operating window?
	 * <p>
	 * The window runs from {@code scheduleOn} to {@code scheduleOff} and <em>wraps</em>, because
	 * the interesting windows cross midnight. Equal endpoints mean a zero-length window rather
	 * than a full day.
	 *
	 * @param dayTime world time of day; any value is accepted and wrapped
	 */
	public boolean isWithinSchedule(long dayTime)
	{
		if(!scheduleEnabled)
			return true;
		int now = wrapDayTime(dayTime);
		if(scheduleOn==scheduleOff)
			return false;
		if(scheduleOn < scheduleOff)
			return now >= scheduleOn&&now < scheduleOff;
		return now >= scheduleOn||now < scheduleOff;
	}

	private static int wrapDayTime(long value)
	{
		return (int)Math.floorMod(value, (long)DAY_LENGTH);
	}

	private static int clampIO(int value)
	{
		return value < 0?0: Math.min(value, FluidNetConfig.maxMainIO);
	}

	/**
	 * Re-applies every bound. Called after load and whenever the config changes, so lowering a
	 * config ceiling immediately pulls existing mains back inside it.
	 */
	public void clamp()
	{
		this.maxInput = clampIO(this.maxInput);
		this.maxOutput = clampIO(this.maxOutput);
		setLeakPct(this.leakPct);
		setPackCap(this.packCap);
	}

	public FluidPolicy copy()
	{
		FluidPolicy copy = new FluidPolicy();
		copy.maxInput = this.maxInput;
		copy.maxOutput = this.maxOutput;
		copy.leakPct = this.leakPct;
		copy.packCap = this.packCap;
		copy.failoverTopUp = this.failoverTopUp;
		copy.scheduleEnabled = this.scheduleEnabled;
		copy.scheduleOn = this.scheduleOn;
		copy.scheduleOff = this.scheduleOff;
		return copy;
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		nbt.setInteger("maxInput", maxInput);
		nbt.setInteger("maxOutput", maxOutput);
		nbt.setDouble("leakPct", leakPct);
		nbt.setInteger("packCap", packCap);
		nbt.setBoolean("failoverTopUp", failoverTopUp);
		nbt.setBoolean("scheduleEnabled", scheduleEnabled);
		nbt.setInteger("scheduleOn", scheduleOn);
		nbt.setInteger("scheduleOff", scheduleOff);
		return nbt;
	}

	public static FluidPolicy readFromNBT(NBTTagCompound nbt)
	{
		FluidPolicy policy = new FluidPolicy();
		if(nbt==null)
			return policy;
		if(nbt.hasKey("maxInput"))
			policy.setMaxInput(nbt.getInteger("maxInput"));
		if(nbt.hasKey("maxOutput"))
			policy.setMaxOutput(nbt.getInteger("maxOutput"));
		if(nbt.hasKey("leakPct"))
			policy.setLeakPct(nbt.getDouble("leakPct"));
		if(nbt.hasKey("packCap"))
			policy.setPackCap(nbt.getInteger("packCap"));
		if(nbt.hasKey("failoverTopUp"))
			policy.setFailoverTopUp(nbt.getBoolean("failoverTopUp"));
		policy.setScheduleEnabled(nbt.getBoolean("scheduleEnabled"));
		if(nbt.hasKey("scheduleOn"))
			policy.setScheduleOn(nbt.getInteger("scheduleOn"));
		if(nbt.hasKey("scheduleOff"))
			policy.setScheduleOff(nbt.getInteger("scheduleOff"));
		return policy;
	}
}
