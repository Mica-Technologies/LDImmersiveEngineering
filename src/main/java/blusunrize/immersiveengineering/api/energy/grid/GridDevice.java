/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One registered world block: a Feed Unit, a Service Unit, or a Console.
 * <p>
 * The record is authoritative and lives in {@link VirtualGrid}'s save data; the tile
 * entity is a transient attachment. A device whose chunk is unloaded stays listed (so it
 * is still visible and configurable in the console GUI) but has no {@link #getEndpoint()
 * endpoint} and is skipped by the tick engine.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridDevice
{
	private final DimensionBlockPos pos;
	private final GridDeviceType type;

	@Nullable
	private UUID segment;
	private String customName = "";
	private int transferCap;
	private int priority;
	private boolean critical;
	private boolean chunkLoad;
	private boolean enabled = true;

	//	=================================
	//		SIGNAL UNITS ONLY
	//	=================================
	/**
	 * Which way a Signal Unit faces: true writes the segment's state out as redstone,
	 * false reads redstone in as an external kill switch. Ignored by every other type.
	 */
	private boolean signalOutput = true;
	/**
	 * Flips the sense of {@link #signalOutput}. On an output that turns a "running" lamp
	 * into a fault lamp; on an input it demands a keep-alive signal instead of a kill one.
	 */
	private boolean signalInverted;

	//	=================================
	//		TRANSIENT -- never persisted
	//	=================================
	@Nullable
	private transient IGridEndpoint endpoint;
	/**
	 * Energy this device moved during the last completed tick, for the GUI and the
	 * in-world readout.
	 */
	private transient int lastThroughput;
	/**
	 * City mode: the last tick on which this feed unit proved its source was live.
	 */
	private transient long lastLiveTick = Long.MIN_VALUE;

	/**
	 * Lifetime meter reading. Unlike the rest of the transient block above, this one is
	 * persisted -- see {@link #writeToNBT}.
	 */
	private long lifetimeThroughput;

	public GridDevice(DimensionBlockPos pos, GridDeviceType type)
	{
		this.pos = pos;
		this.type = type;
		this.transferCap = GridConfig.defaultDeviceCap;
	}

	public DimensionBlockPos getPos()
	{
		return pos;
	}

	public GridDeviceType getType()
	{
		return type;
	}

	public int getDimension()
	{
		return pos.dimension;
	}

	@Nullable
	public UUID getSegment()
	{
		return segment;
	}

	/**
	 * Package-private on purpose: assignment must go through
	 * {@link VirtualGrid#assignDevice} so both sides of the relation stay consistent.
	 */
	void setSegmentInternal(@Nullable UUID segment)
	{
		this.segment = segment;
	}

	public boolean isLinked()
	{
		return segment!=null;
	}

	public String getCustomName()
	{
		return customName;
	}

	public void setCustomName(String customName)
	{
		this.customName = customName==null?"": customName;
	}

	/**
	 * @return the custom name, or a generated "Feed Unit (x, y, z)" style fallback
	 */
	public String getDisplayName()
	{
		if(!customName.isEmpty())
			return customName;
		return type.getName()+" "+pos.getX()+", "+pos.getY()+", "+pos.getZ();
	}

	public int getTransferCap()
	{
		return transferCap;
	}

	public void setTransferCap(int transferCap)
	{
		this.transferCap = transferCap < 0?0: Math.min(transferCap, GridConfig.maxSegmentIO);
		notifyEndpoint();
	}

	public int getPriority()
	{
		return priority;
	}

	public void setPriority(int priority)
	{
		this.priority = priority;
	}

	/**
	 * @return true if this device is served before non-critical ones during a shortfall
	 */
	public boolean isCritical()
	{
		return critical;
	}

	public void setCritical(boolean critical)
	{
		this.critical = critical;
	}

	public boolean isChunkLoad()
	{
		return chunkLoad&&GridConfig.allowChunkloading;
	}

	/**
	 * @return the stored flag, ignoring whether the config currently permits chunk loading
	 */
	public boolean isChunkLoadRequested()
	{
		return chunkLoad;
	}

	public void setChunkLoad(boolean chunkLoad)
	{
		this.chunkLoad = chunkLoad;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		notifyEndpoint();
	}

	//	=================================
	//		SIGNAL SETTINGS
	//	=================================

	/**
	 * @return true if this Signal Unit reports the segment to the world, false if it takes
	 * orders from it
	 */
	public boolean isSignalOutput()
	{
		return signalOutput;
	}

	public void setSignalOutput(boolean signalOutput)
	{
		this.signalOutput = signalOutput;
		notifyEndpoint();
	}

	public boolean isSignalInverted()
	{
		return signalInverted;
	}

	public void setSignalInverted(boolean signalInverted)
	{
		this.signalInverted = signalInverted;
		notifyEndpoint();
	}

	/**
	 * Resolves a raw redstone reading into "this unit is calling for the segment to stop".
	 * <p>
	 * Plain: power means stop, which is the ordinary kill switch. Inverted: the absence of
	 * power means stop, so the segment needs a keep-alive signal to run -- a dead-man's
	 * switch for anything that must not outlive its controller.
	 */
	public boolean isKilling(boolean redstoneHigh)
	{
		return redstoneHigh!=signalInverted;
	}

	//	=================================
	//		ONLINE STATE
	//	=================================

	@Nullable
	public IGridEndpoint getEndpoint()
	{
		return endpoint;
	}

	public void setEndpoint(@Nullable IGridEndpoint endpoint)
	{
		this.endpoint = endpoint;
		if(endpoint!=null)
			endpoint.onGridConfigChanged(this);
		else
			lastThroughput = 0;
	}

	/**
	 * The server said this device was online. Set only on the client, where there is no
	 * endpoint to ask -- the tile entity lives on the server side of the sync.
	 */
	private transient boolean remoteOnline;

	/**
	 * @return true if the block is loaded and attached
	 */
	public boolean isOnline()
	{
		return endpoint!=null||remoteOnline;
	}

	/**
	 * @return true if this device should take part in the current tick
	 */
	public boolean isActive()
	{
		return enabled&&endpoint!=null;
	}

	public void notifyEndpoint()
	{
		if(endpoint!=null)
			endpoint.onGridConfigChanged(this);
	}

	public int getLastThroughput()
	{
		return lastThroughput;
	}

	public void setLastThroughput(int lastThroughput)
	{
		this.lastThroughput = lastThroughput;
	}

	/**
	 * Total flux this device has moved over its lifetime -- the meter reading. Persisted,
	 * and deliberately not reset by anything short of an explicit meter reset, so it can be
	 * used for utility-bill style accounting.
	 */
	public long getLifetimeThroughput()
	{
		return lifetimeThroughput;
	}

	public void recordThroughput(int amount)
	{
		if(amount <= 0)
			return;
		lastThroughput += amount;
		lifetimeThroughput += amount;
	}

	/**
	 * Zeroes the meter. Separate from {@link #setLastThroughput} because the per-tick value
	 * is cleared every tick while the meter reading must survive.
	 */
	public void resetMeter()
	{
		lifetimeThroughput = 0;
	}

	public long getLastLiveTick()
	{
		return lastLiveTick;
	}

	public void setLastLiveTick(long lastLiveTick)
	{
		this.lastLiveTick = lastLiveTick;
	}

	/**
	 * City mode: whether this feed unit's source counted as live recently enough.
	 * A grace of two sip intervals means one missed check does not brown out a city.
	 */
	public boolean isLive(long now)
	{
		if(lastLiveTick==Long.MIN_VALUE)
			return false;
		return now-lastLiveTick <= (long)GridConfig.sipIntervalTicks*2;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return writeToNBT(nbt, false);
	}

	/**
	 * @param live also include whether the block is loaded and what it moved last tick.
	 *             Both are properties of the running server, not of the save.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setInteger("x", pos.getX());
		nbt.setInteger("y", pos.getY());
		nbt.setInteger("z", pos.getZ());
		nbt.setInteger("dim", pos.dimension);
		nbt.setInteger("type", type.ordinal());
		if(segment!=null)
			nbt.setString("segment", segment.toString());
		nbt.setString("customName", customName);
		nbt.setInteger("transferCap", transferCap);
		nbt.setInteger("priority", priority);
		nbt.setBoolean("critical", critical);
		nbt.setBoolean("chunkLoad", chunkLoad);
		nbt.setBoolean("enabled", enabled);
		nbt.setLong("meter", lifetimeThroughput);
		if(type==GridDeviceType.SIGNAL)
		{
			nbt.setBoolean("signalOutput", signalOutput);
			nbt.setBoolean("signalInverted", signalInverted);
		}
		if(live)
		{
			nbt.setBoolean("online", isOnline());
			nbt.setInteger("throughput", lastThroughput);
		}
		return nbt;
	}

	@Nullable
	public static GridDevice readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null||!nbt.hasKey("type"))
			return null;
		DimensionBlockPos pos = new DimensionBlockPos(nbt.getInteger("x"), nbt.getInteger("y"),
				nbt.getInteger("z"), nbt.getInteger("dim"));
		GridDevice device = new GridDevice(pos, GridDeviceType.byIndex(nbt.getInteger("type")));
		if(nbt.hasKey("segment"))
			device.segment = parseUUID(nbt.getString("segment"));
		device.setCustomName(nbt.getString("customName"));
		if(nbt.hasKey("transferCap"))
			device.setTransferCap(nbt.getInteger("transferCap"));
		device.priority = nbt.getInteger("priority");
		device.critical = nbt.getBoolean("critical");
		device.chunkLoad = nbt.getBoolean("chunkLoad");
		//Absent key means an older record from before the flag existed; default to enabled
		//rather than silently switching every device off on upgrade.
		device.enabled = !nbt.hasKey("enabled")||nbt.getBoolean("enabled");
		device.lifetimeThroughput = Math.max(0, nbt.getLong("meter"));
		//Absent means a record written before Signal Units existed, or a non-signal device;
		//output is the harmless default either way, since an input would switch a segment off.
		device.signalOutput = !nbt.hasKey("signalOutput")||nbt.getBoolean("signalOutput");
		device.signalInverted = nbt.getBoolean("signalInverted");
		//Only ever present in a GUI sync. On the server these stay false/0 and the real
		//endpoint attachment decides, exactly as before.
		device.remoteOnline = nbt.getBoolean("online");
		device.lastThroughput = nbt.getInteger("throughput");
		return device;
	}

	/**
	 * Lenient UUID parse -- a malformed id unlinks the device rather than failing world load.
	 */
	@Nullable
	public static UUID parseUUID(String s)
	{
		if(s==null||s.isEmpty())
			return null;
		try
		{
			return UUID.fromString(s);
		} catch(IllegalArgumentException e)
		{
			return null;
		}
	}

	@Override
	public String toString()
	{
		return "GridDevice["+type.getName()+" @ "+pos+(segment!=null?", segment="+segment: ", unlinked")+"]";
	}
}
