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
 * The per-segment transfer rules: how much may flow in and out per tick, how much is
 * lost on the way, how much may be buffered, and when backup segments step in.
 * <p>
 * Every setter clamps, so a value written by a GUI, a command, or a hand-edited save can
 * never put a segment outside the bounds {@link GridConfig} allows.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridPolicy
{
	private int maxInput;
	private int maxOutput;
	private double lossPct;
	private int bufferCap;
	private boolean failoverTopUp;

	//	=================================
	//		SCHEDULE
	//	=================================
	/**
	 * Ticks of the Minecraft day, 0-23999. 0 is sunrise, 12000 dusk, 18000 midnight.
	 */
	public static final int DAY_LENGTH = 24000;
	/**
	 * Dusk, in day-time ticks -- the default "on" for street lighting.
	 */
	public static final int DEFAULT_ON = 12000;
	/**
	 * Dawn, in day-time ticks -- the default "off".
	 */
	public static final int DEFAULT_OFF = 23000;

	private boolean scheduleEnabled;
	private int scheduleOn = DEFAULT_ON;
	private int scheduleOff = DEFAULT_OFF;

	/**
	 * Creates a policy carrying the current configured defaults.
	 */
	public GridPolicy()
	{
		this.maxInput = GridConfig.maxSegmentIO;
		this.maxOutput = GridConfig.maxSegmentIO;
		this.lossPct = GridConfig.defaultLossPct;
		this.bufferCap = defaultBufferCap(this.maxOutput);
		this.failoverTopUp = GridConfig.failoverTopUpDefault;
		clamp();
	}

	/**
	 * The buffer a segment gets when nobody has chosen one: {@link GridConfig#bufferTicks}
	 * ticks of its own output rate. Enough to smooth the collect/serve phase ordering,
	 * far too little to be used as a battery.
	 */
	public static int defaultBufferCap(int maxOutput)
	{
		long cap = (long)Math.max(0, maxOutput)*Math.max(1, GridConfig.bufferTicks);
		return (int)Math.min(cap, GridConfig.bufferCapMax);
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

	public double getLossPct()
	{
		return lossPct;
	}

	public void setLossPct(double lossPct)
	{
		this.lossPct = lossPct < 0?0: lossPct > 1?1: lossPct;
	}

	public int getBufferCap()
	{
		return bufferCap;
	}

	public void setBufferCap(int bufferCap)
	{
		this.bufferCap = bufferCap < 0?0: Math.min(bufferCap, GridConfig.bufferCapMax);
	}

	/**
	 * @return true if backup segments also cover ordinary shortfalls, not just outages
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
	 * Is the clock inside this segment's operating window?
	 * <p>
	 * The window runs from {@code scheduleOn} to {@code scheduleOff} and <em>wraps</em>: the
	 * interesting case, street lighting, is on at dusk and off at dawn, which crosses
	 * midnight. Equal endpoints mean a zero-length window rather than a full day -- a
	 * schedule that never runs is a visible mistake, while one that always runs is
	 * indistinguishable from having no schedule at all and would hide the typo.
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
		return value < 0?0: Math.min(value, GridConfig.maxSegmentIO);
	}

	/**
	 * Re-applies every bound. Called after load and whenever the config changes, so
	 * lowering a config ceiling immediately pulls existing segments back inside it.
	 */
	public void clamp()
	{
		this.maxInput = clampIO(this.maxInput);
		this.maxOutput = clampIO(this.maxOutput);
		setLossPct(this.lossPct);
		setBufferCap(this.bufferCap);
	}

	public GridPolicy copy()
	{
		GridPolicy copy = new GridPolicy();
		copy.maxInput = this.maxInput;
		copy.maxOutput = this.maxOutput;
		copy.lossPct = this.lossPct;
		copy.bufferCap = this.bufferCap;
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
		nbt.setDouble("lossPct", lossPct);
		nbt.setInteger("bufferCap", bufferCap);
		nbt.setBoolean("failoverTopUp", failoverTopUp);
		nbt.setBoolean("scheduleEnabled", scheduleEnabled);
		nbt.setInteger("scheduleOn", scheduleOn);
		nbt.setInteger("scheduleOff", scheduleOff);
		return nbt;
	}

	public static GridPolicy readFromNBT(NBTTagCompound nbt)
	{
		GridPolicy policy = new GridPolicy();
		if(nbt==null)
			return policy;
		if(nbt.hasKey("maxInput"))
			policy.setMaxInput(nbt.getInteger("maxInput"));
		if(nbt.hasKey("maxOutput"))
			policy.setMaxOutput(nbt.getInteger("maxOutput"));
		if(nbt.hasKey("lossPct"))
			policy.setLossPct(nbt.getDouble("lossPct"));
		if(nbt.hasKey("bufferCap"))
			policy.setBufferCap(nbt.getInteger("bufferCap"));
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
