/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A named, individually switchable slice of the grid: a set of devices plus the rules
 * they operate under.
 * <p>
 * A segment is the unit of management. It can be switched off, capped, given a colour and
 * an owner, and linked to other segments as failover. Energy entering through its feed
 * units lands in a small smoothing buffer and leaves through its service units within the
 * same tick -- the buffer exists to make the collect-then-serve ordering work, not to
 * store power.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridSegment
{
	/**
	 * Colours offered by the console's picker, in menu order. Deliberately the same
	 * family of tones IE uses elsewhere rather than raw dye colours.
	 */
	public static final int[] PALETTE = {
			0xE9762B, 0xD4462B, 0xC8B72E, 0x6FA83A, 0x3A8FA8, 0x3F5FBF, 0x8A4FBF, 0xC44F9B,
			0xB07A3C, 0x7A7A7A, 0xBFBFBF, 0x4A4A4A, 0x2E8B6F, 0x8B2E4A, 0x55708A, 0x8A7E55
	};

	private final UUID id;
	private String name;
	private int color;
	private boolean enabled = true;
	@Nullable
	private UUID owner;
	private boolean locked;
	private GridPolicy policy = new GridPolicy();

	private int buffer;
	private final List<UUID> failover = new ArrayList<>();
	private final List<GridDevice> devices = new ArrayList<>();

	private final GridStats stats = new GridStats();

	/**
	 * Breaker state. A tripped segment behaves as if switched off and must be reset by
	 * hand -- consequence rather than a silent clamp.
	 */
	private boolean tripped;
	private transient int saturatedTicks;

	//	=================================
	//		TRANSIENT PER-TICK STATE
	//	=================================
	private transient int tickIn;
	private transient int tickOut;
	/**
	 * City mode: this segment's own feeds proved live. Distinct from {@link #energized},
	 * which also accounts for a live backup standing in.
	 */
	private transient boolean sourceLive;
	private transient boolean energized;
	/**
	 * A Signal Unit in input mode is holding this segment down. Recomputed from the world
	 * every tick, so it is never persisted -- an external kill switch that is itself gone
	 * must not keep a segment off across a restart.
	 */
	private transient boolean forcedOff;
	/**
	 * The clock is outside this segment's operating window. Also transient and recomputed
	 * every tick, for the same reason: the schedule is the truth, not a latched flag.
	 */
	private transient boolean scheduleSuppressed;
	/**
	 * Priority-ordered views, rebuilt only when membership or ordering actually changes.
	 */
	private transient final List<GridDevice> activeFeeds = new ArrayList<>();
	private transient final List<GridDevice> activeServices = new ArrayList<>();
	private transient final List<GridDevice> activeSignals = new ArrayList<>();
	private transient boolean viewsDirty = true;

	public GridSegment(UUID id, String name)
	{
		this.id = id;
		this.name = name==null?"": name;
		this.color = PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)];
	}

	public UUID getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name==null?"": name;
	}

	public int getColor()
	{
		return color;
	}

	public void setColor(int color)
	{
		this.color = color;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		if(enabled)
		{
			//Switching a segment back on is also the breaker reset -- one control, as on
			//a real panel, rather than a separate hidden "unlatch" action.
			tripped = false;
			saturatedTicks = 0;
		}
	}

	@Nullable
	public UUID getOwner()
	{
		return owner;
	}

	public void setOwner(@Nullable UUID owner)
	{
		this.owner = owner;
	}

	public boolean isLocked()
	{
		return locked;
	}

	public void setLocked(boolean locked)
	{
		this.locked = locked;
	}

	/**
	 * @return true if {@code player} may reconfigure this segment
	 */
	public boolean canEdit(@Nullable UUID player)
	{
		if(!locked||owner==null)
			return true;
		return owner.equals(player);
	}

	public GridPolicy getPolicy()
	{
		return policy;
	}

	public void setPolicy(GridPolicy policy)
	{
		this.policy = policy==null?new GridPolicy(): policy;
	}

	public GridStats getStats()
	{
		return stats;
	}

	public boolean isTripped()
	{
		return tripped;
	}

	public void setTripped(boolean tripped)
	{
		this.tripped = tripped;
		if(!tripped)
			saturatedTicks = 0;
	}

	/**
	 * @return true if the segment is switched on, its breaker has not latched, no Signal
	 * Unit is holding it down, and it is inside its schedule
	 */
	public boolean isOperational()
	{
		return enabled&&!tripped&&!forcedOff&&!scheduleSuppressed;
	}

	/**
	 * @return true if a Signal Unit in input mode is currently holding this segment off
	 */
	public boolean isForcedOff()
	{
		return forcedOff;
	}

	public void setForcedOff(boolean forcedOff)
	{
		this.forcedOff = forcedOff;
	}

	/**
	 * @return true if the segment is switched on but its schedule currently says otherwise
	 */
	public boolean isScheduleSuppressed()
	{
		return scheduleSuppressed;
	}

	/**
	 * Re-evaluates the schedule against the world clock.
	 * <p>
	 * The schedule is a <em>gate</em>, not a second switch: it can only hold a segment down,
	 * never turn one on that the player switched off. Otherwise the console's toggle and the
	 * clock would fight each other every dusk, and whichever ran last would win.
	 *
	 * @param dayTime the world time of day, 0-23999
	 */
	public void updateSchedule(long dayTime)
	{
		scheduleSuppressed = !policy.isWithinSchedule(dayTime);
	}

	//	=================================
	//		BUFFER
	//	=================================

	public int getBuffer()
	{
		return buffer;
	}

	public void setBuffer(int buffer)
	{
		this.buffer = buffer < 0?0: Math.min(buffer, policy.getBufferCap());
	}

	/**
	 * Adds to the buffer, discarding anything past the cap.
	 *
	 * @return the amount actually stored
	 */
	public int addToBuffer(int amount)
	{
		if(amount <= 0)
			return 0;
		int room = policy.getBufferCap()-buffer;
		int stored = Math.min(room, amount);
		if(stored > 0)
			buffer += stored;
		return stored;
	}

	/**
	 * @return the amount actually removed, never more than is present
	 */
	public int drawFromBuffer(int amount)
	{
		if(amount <= 0)
			return 0;
		int taken = Math.min(buffer, amount);
		buffer -= taken;
		return taken;
	}

	//	=================================
	//		FAILOVER LINKS
	//	=================================

	/**
	 * @return the ordered backup list; first entry is asked first
	 */
	public List<UUID> getFailover()
	{
		return Collections.unmodifiableList(failover);
	}

	/**
	 * Appends a backup. Self-links and duplicates are rejected; deeper cycles are allowed
	 * here and broken at traversal time by the visited set, because a cycle can also be
	 * created from the far end.
	 *
	 * @return true if the link was added
	 */
	public boolean addFailover(UUID target)
	{
		if(target==null||target.equals(id)||failover.contains(target))
			return false;
		failover.add(target);
		return true;
	}

	public boolean removeFailover(UUID target)
	{
		return failover.remove(target);
	}

	/**
	 * Moves a backup one place up or down the priority order.
	 *
	 * @return true if the list changed
	 */
	public boolean moveFailover(UUID target, boolean up)
	{
		int index = failover.indexOf(target);
		if(index < 0)
			return false;
		int dest = up?index-1: index+1;
		if(dest < 0||dest >= failover.size())
			return false;
		Collections.swap(failover, index, dest);
		return true;
	}

	public void clearFailover()
	{
		failover.clear();
	}

	//	=================================
	//		MEMBERSHIP
	//	=================================

	/**
	 * @return every device assigned to this segment, online or not
	 */
	public List<GridDevice> getDevices()
	{
		return Collections.unmodifiableList(devices);
	}

	public int getDeviceCount()
	{
		return devices.size();
	}

	public int getDeviceCount(GridDeviceType type)
	{
		int count = 0;
		for(int i = 0; i < devices.size(); i++)
			if(devices.get(i).getType()==type)
				count++;
		return count;
	}

	public int getOnlineDeviceCount()
	{
		int count = 0;
		for(int i = 0; i < devices.size(); i++)
			if(devices.get(i).isOnline())
				count++;
		return count;
	}

	void addDeviceInternal(GridDevice device)
	{
		if(!devices.contains(device))
		{
			devices.add(device);
			viewsDirty = true;
		}
	}

	boolean removeDeviceInternal(GridDevice device)
	{
		boolean removed = devices.remove(device);
		if(removed)
			viewsDirty = true;
		return removed;
	}

	/**
	 * Marks the priority-ordered views stale. Called whenever a device is added, removed,
	 * comes online, goes offline, or has its priority/critical flag changed.
	 */
	public void invalidateViews()
	{
		viewsDirty = true;
	}

	/**
	 * @return active feed units, highest priority first
	 */
	public List<GridDevice> getActiveFeeds()
	{
		rebuildViewsIfNeeded();
		return activeFeeds;
	}

	/**
	 * @return active service units, critical loads first then highest priority
	 */
	public List<GridDevice> getActiveServices()
	{
		rebuildViewsIfNeeded();
		return activeServices;
	}

	/**
	 * @return active Signal Units. Unordered relative to each other -- an input kills the
	 * segment regardless of who else voted, and every output sees the same state.
	 */
	public List<GridDevice> getActiveSignals()
	{
		rebuildViewsIfNeeded();
		return activeSignals;
	}

	private void rebuildViewsIfNeeded()
	{
		if(!viewsDirty)
			return;
		activeFeeds.clear();
		activeServices.clear();
		activeSignals.clear();
		for(int i = 0; i < devices.size(); i++)
		{
			GridDevice device = devices.get(i);
			if(!device.isActive())
				continue;
			if(device.getType()==GridDeviceType.FEED)
				activeFeeds.add(device);
			else if(device.getType()==GridDeviceType.SERVICE)
				activeServices.add(device);
			else if(device.getType()==GridDeviceType.SIGNAL)
				activeSignals.add(device);
		}
		activeFeeds.sort(FEED_ORDER);
		activeServices.sort(SERVICE_ORDER);
		viewsDirty = false;
	}

	/**
	 * Highest priority drains first. Position is the tie-break so ordering is stable
	 * across restarts rather than depending on hash iteration order.
	 */
	public static final Comparator<GridDevice> FEED_ORDER = (a, b) -> {
		int cmp = Integer.compare(b.getPriority(), a.getPriority());
		return cmp!=0?cmp: comparePos(a, b);
	};

	/**
	 * Critical loads are served before everything else -- that is the load-shedding rule.
	 * Within a class, highest priority first.
	 */
	public static final Comparator<GridDevice> SERVICE_ORDER = (a, b) -> {
		if(a.isCritical()!=b.isCritical())
			return a.isCritical()?-1: 1;
		int cmp = Integer.compare(b.getPriority(), a.getPriority());
		return cmp!=0?cmp: comparePos(a, b);
	};

	private static int comparePos(GridDevice a, GridDevice b)
	{
		int cmp = Integer.compare(a.getPos().dimension, b.getPos().dimension);
		if(cmp!=0)
			return cmp;
		cmp = Integer.compare(a.getPos().getX(), b.getPos().getX());
		if(cmp!=0)
			return cmp;
		cmp = Integer.compare(a.getPos().getY(), b.getPos().getY());
		if(cmp!=0)
			return cmp;
		return Integer.compare(a.getPos().getZ(), b.getPos().getZ());
	}

	//	=================================
	//		TICK STATE
	//	=================================

	public void beginTick()
	{
		tickIn = 0;
		tickOut = 0;
		stats.beginTick();
	}

	public void endTick()
	{
		stats.endTick();
	}

	public int getTickIn()
	{
		return tickIn;
	}

	public int getTickOut()
	{
		return tickOut;
	}

	/**
	 * @return how much more may still enter this segment this tick
	 */
	public int getInputBudget()
	{
		return Math.max(0, policy.getMaxInput()-tickIn);
	}

	/**
	 * @return how much more may still leave this segment this tick
	 */
	public int getOutputBudget()
	{
		return Math.max(0, policy.getMaxOutput()-tickOut);
	}

	public void recordIn(int amount)
	{
		if(amount <= 0)
			return;
		tickIn += amount;
		stats.recordIn(amount);
	}

	public void recordOut(int amount)
	{
		if(amount <= 0)
			return;
		tickOut += amount;
		stats.recordOut(amount);
	}

	/**
	 * @return city mode: whether power actually leaves this segment, counting failover
	 */
	public boolean isEnergized()
	{
		return energized;
	}

	public void setEnergized(boolean energized)
	{
		this.energized = energized;
	}

	/**
	 * The one-bit summary a Signal Unit reports and the console lamp draws: is this segment
	 * actually carrying power right now?
	 * <p>
	 * In city mode that is exactly {@link #isEnergized()}. In normal mode a segment is up
	 * when it is operational and flux either moved through it this tick or is sitting in its
	 * buffer -- so a segment whose generators died reads as down even though nobody switched
	 * it off, which is the case an alarm lamp exists for.
	 */
	public boolean isUp(boolean cityMode)
	{
		if(cityMode)
			return energized;
		return isOperational()&&(buffer > 0||tickIn > 0||tickOut > 0);
	}

	/**
	 * @return city mode: whether this segment's own feed units are live
	 */
	public boolean isSourceLive()
	{
		return sourceLive;
	}

	public void setSourceLive(boolean sourceLive)
	{
		this.sourceLive = sourceLive;
	}

	/**
	 * Advances the breaker. A segment that spent the whole tick at its output ceiling is
	 * saturated; {@link GridConfig#breakerTripSeconds} consecutive seconds of that latches
	 * it off.
	 *
	 * @return true if the breaker tripped on this call
	 */
	public boolean updateBreaker()
	{
		//A segment that is down for any reason -- switch, breaker, kill signal, schedule --
		//is not saturating, so nothing should be accumulating against its breaker.
		if(!GridConfig.breakersEnabled||!isOperational())
			return false;
		if(policy.getMaxOutput() > 0&&tickOut >= policy.getMaxOutput())
			saturatedTicks++;
		else
			saturatedTicks = 0;
		if(saturatedTicks >= GridConfig.breakerTripSeconds*20)
		{
			tripped = true;
			saturatedTicks = 0;
			return true;
		}
		return false;
	}

	public int getSaturatedTicks()
	{
		return saturatedTicks;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return writeToNBT(nbt, false);
	}

	/**
	 * @param live also include the state the engine recomputes every tick. Save data leaves
	 *             it out -- it is derived, and a stale copy read back at world load would be
	 *             wrong until the first tick corrected it. A GUI sync needs it, because it
	 *             is most of what the console actually displays.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setString("id", id.toString());
		nbt.setString("name", name);
		nbt.setInteger("color", color);
		nbt.setBoolean("enabled", enabled);
		if(owner!=null)
			nbt.setString("owner", owner.toString());
		nbt.setBoolean("locked", locked);
		nbt.setBoolean("tripped", tripped);
		nbt.setInteger("buffer", buffer);
		nbt.setTag("policy", policy.writeToNBT(new NBTTagCompound()));
		nbt.setTag("stats", stats.writeToNBT(new NBTTagCompound(), live));
		if(live)
		{
			nbt.setBoolean("energized", energized);
			nbt.setBoolean("sourceLive", sourceLive);
			nbt.setBoolean("forcedOff", forcedOff);
			nbt.setBoolean("scheduleSuppressed", scheduleSuppressed);
			nbt.setInteger("tickIn", tickIn);
			nbt.setInteger("tickOut", tickOut);
		}

		NBTTagList failoverList = new NBTTagList();
		for(UUID target : failover)
			failoverList.appendTag(new NBTTagString(target.toString()));
		nbt.setTag("failover", failoverList);
		return nbt;
	}

	/**
	 * Reads a segment. Devices are <em>not</em> read here -- {@link VirtualGrid} owns the
	 * device table and re-attaches them, so a device can never end up in two segments.
	 */
	@Nullable
	public static GridSegment readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null)
			return null;
		UUID id = GridDevice.parseUUID(nbt.getString("id"));
		if(id==null)
			return null;
		GridSegment segment = new GridSegment(id, nbt.getString("name"));
		if(nbt.hasKey("color"))
			segment.color = nbt.getInteger("color");
		segment.enabled = !nbt.hasKey("enabled")||nbt.getBoolean("enabled");
		if(nbt.hasKey("owner"))
			segment.owner = GridDevice.parseUUID(nbt.getString("owner"));
		segment.locked = nbt.getBoolean("locked");
		segment.tripped = nbt.getBoolean("tripped");
		segment.policy = GridPolicy.readFromNBT(nbt.getCompoundTag("policy"));
		segment.stats.readFromNBT(nbt.getCompoundTag("stats"));
		segment.setBuffer(nbt.getInteger("buffer"));
		//Absent in save data, present in a GUI sync. Reading them unconditionally is safe:
		//on the server the next tick recomputes all four before anything reads them.
		segment.energized = nbt.getBoolean("energized");
		segment.sourceLive = nbt.getBoolean("sourceLive");
		segment.forcedOff = nbt.getBoolean("forcedOff");
		segment.scheduleSuppressed = nbt.getBoolean("scheduleSuppressed");
		segment.tickIn = nbt.getInteger("tickIn");
		segment.tickOut = nbt.getInteger("tickOut");

		NBTTagList failoverList = nbt.getTagList("failover", 8);
		for(int i = 0; i < failoverList.tagCount(); i++)
		{
			UUID target = GridDevice.parseUUID(failoverList.getStringTagAt(i));
			if(target!=null)
				segment.addFailover(target);
		}
		return segment;
	}

	@Override
	public String toString()
	{
		return "GridSegment["+name+" ("+id+"), "+devices.size()+" devices, "
				+(enabled?tripped?"TRIPPED": "on": "off")+"]";
	}
}
