/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One registered world block: an Inlet, an Outlet, a Valve, or a Console.
 * <p>
 * The record is authoritative and lives in {@link VirtualFluidNet}'s save data; the tile entity
 * is a transient attachment. A device whose chunk is unloaded stays listed (so it is still
 * visible and configurable in the console GUI) but has no {@link #getEndpoint() endpoint} and is
 * skipped by the tick engine.
 * <p>
 * The deliberate mirror of {@code GridDevice}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidDevice
{
	private final DimensionBlockPos pos;
	private final FluidDeviceType type;

	@Nullable
	private UUID main;
	private String customName = "";
	private int transferCap;
	private int priority;
	private boolean critical;
	private boolean chunkLoad;
	private boolean enabled = true;

	//	=================================
	//		VALVES ONLY
	//	=================================
	/**
	 * Which way a Valve faces: true writes the main's state out as redstone, false reads redstone
	 * in as an external shut-off. Ignored by every other type.
	 */
	private boolean valveOutput = true;
	/**
	 * Flips the sense of {@link #valveOutput}. On an output that turns a "flowing" lamp into a
	 * fault lamp; on an input it demands a keep-open signal instead of a close one.
	 */
	private boolean valveInverted;

	//	=================================
	//		TRANSIENT -- never persisted
	//	=================================
	@Nullable
	private transient IFluidEndpoint endpoint;
	/**
	 * Millibuckets this device moved during the last completed tick, for the GUI and the in-world
	 * readout.
	 */
	private transient int lastThroughput;
	/**
	 * City mode: the last tick on which this Inlet proved its source was live.
	 */
	private transient long lastLiveTick = Long.MIN_VALUE;
	/**
	 * The server said this device was online. Set only on the client, where there is no endpoint
	 * to ask -- the tile entity lives on the server side of the sync.
	 */
	private transient boolean remoteOnline;

	/**
	 * Lifetime meter reading, in millibuckets. Unlike the rest of the transient block above, this
	 * one is persisted -- see {@link #writeToNBT}.
	 */
	private long lifetimeThroughput;

	public FluidDevice(DimensionBlockPos pos, FluidDeviceType type)
	{
		this.pos = pos;
		this.type = type;
		this.transferCap = FluidNetConfig.defaultDeviceCap;
	}

	public DimensionBlockPos getPos()
	{
		return pos;
	}

	public FluidDeviceType getType()
	{
		return type;
	}

	public int getDimension()
	{
		return pos.dimension;
	}

	@Nullable
	public UUID getMain()
	{
		return main;
	}

	/**
	 * Package-private on purpose: assignment must go through {@link VirtualFluidNet#assignDevice}
	 * so both sides of the relation stay consistent.
	 */
	void setMainInternal(@Nullable UUID main)
	{
		this.main = main;
	}

	public boolean isLinked()
	{
		return main!=null;
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
	 * @return the custom name, or a generated "inlet 12, 64, -30" style fallback
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
		this.transferCap = transferCap < 0?0: Math.min(transferCap, FluidNetConfig.maxMainIO);
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
		return chunkLoad&&FluidNetConfig.allowChunkloading;
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
	//		VALVE SETTINGS
	//	=================================

	/**
	 * @return true if this Valve reports the main to the world, false if it takes orders from it
	 */
	public boolean isValveOutput()
	{
		return valveOutput;
	}

	public void setValveOutput(boolean valveOutput)
	{
		this.valveOutput = valveOutput;
		notifyEndpoint();
	}

	public boolean isValveInverted()
	{
		return valveInverted;
	}

	public void setValveInverted(boolean valveInverted)
	{
		this.valveInverted = valveInverted;
		notifyEndpoint();
	}

	/**
	 * Resolves a raw redstone reading into "this valve is calling for the main to close".
	 * <p>
	 * Plain: power means closed, which is the ordinary shut-off. Inverted: the absence of power
	 * means closed, so the main needs a keep-open signal to run -- a dead-man's switch for a
	 * branch that must not outlive its controller.
	 */
	public boolean isClosing(boolean redstoneHigh)
	{
		return redstoneHigh!=valveInverted;
	}

	//	=================================
	//		ONLINE STATE
	//	=================================

	@Nullable
	public IFluidEndpoint getEndpoint()
	{
		return endpoint;
	}

	public void setEndpoint(@Nullable IFluidEndpoint endpoint)
	{
		this.endpoint = endpoint;
		if(endpoint!=null)
			endpoint.onNetConfigChanged(this);
		else
			lastThroughput = 0;
	}

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
			endpoint.onNetConfigChanged(this);
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
	 * Total fluid this device has moved over its lifetime -- the meter reading. Persisted, and
	 * deliberately not reset by anything short of an explicit meter reset, so it can be used for
	 * utility-bill style accounting.
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
	 * Zeroes the meter. Separate from {@link #setLastThroughput} because the per-tick value is
	 * cleared every tick while the meter reading must survive.
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
	 * City mode: whether this Inlet's source counted as live recently enough. A grace of two sip
	 * intervals means one missed check does not depressurise a city.
	 */
	public boolean isLive(long now)
	{
		if(lastLiveTick==Long.MIN_VALUE)
			return false;
		return now-lastLiveTick <= (long)FluidNetConfig.sipIntervalTicks*2;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return writeToNBT(nbt, false);
	}

	/**
	 * @param live also include whether the block is loaded and what it moved last tick. Both are
	 *             properties of the running server, not of the save.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setInteger("x", pos.getX());
		nbt.setInteger("y", pos.getY());
		nbt.setInteger("z", pos.getZ());
		nbt.setInteger("dim", pos.dimension);
		nbt.setInteger("type", type.ordinal());
		if(main!=null)
			nbt.setString("main", main.toString());
		nbt.setString("customName", customName);
		nbt.setInteger("transferCap", transferCap);
		nbt.setInteger("priority", priority);
		nbt.setBoolean("critical", critical);
		nbt.setBoolean("chunkLoad", chunkLoad);
		nbt.setBoolean("enabled", enabled);
		nbt.setLong("meter", lifetimeThroughput);
		if(type==FluidDeviceType.VALVE)
		{
			nbt.setBoolean("valveOutput", valveOutput);
			nbt.setBoolean("valveInverted", valveInverted);
		}
		if(live)
		{
			nbt.setBoolean("online", isOnline());
			nbt.setInteger("throughput", lastThroughput);
		}
		return nbt;
	}

	@Nullable
	public static FluidDevice readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null||!nbt.hasKey("type"))
			return null;
		DimensionBlockPos pos = new DimensionBlockPos(nbt.getInteger("x"), nbt.getInteger("y"),
				nbt.getInteger("z"), nbt.getInteger("dim"));
		FluidDevice device = new FluidDevice(pos, FluidDeviceType.byIndex(nbt.getInteger("type")));
		if(nbt.hasKey("main"))
			device.main = parseUUID(nbt.getString("main"));
		device.setCustomName(nbt.getString("customName"));
		if(nbt.hasKey("transferCap"))
			device.setTransferCap(nbt.getInteger("transferCap"));
		device.priority = nbt.getInteger("priority");
		device.critical = nbt.getBoolean("critical");
		device.chunkLoad = nbt.getBoolean("chunkLoad");
		//Absent key means an older record from before the flag existed; default to enabled rather
		//than silently switching every device off on upgrade.
		device.enabled = !nbt.hasKey("enabled")||nbt.getBoolean("enabled");
		device.lifetimeThroughput = Math.max(0, nbt.getLong("meter"));
		//Output is the harmless default: an input would close a main.
		device.valveOutput = !nbt.hasKey("valveOutput")||nbt.getBoolean("valveOutput");
		device.valveInverted = nbt.getBoolean("valveInverted");
		//Only ever present in a GUI sync. On the server these stay false/0 and the real endpoint
		//attachment decides.
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
		return "FluidDevice["+type.getName()+" @ "+pos+(main!=null?", main="+main: ", unlinked")+"]";
	}
}
