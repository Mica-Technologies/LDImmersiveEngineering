/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A named, individually switchable main: a set of endpoints, one fluid, and the rules they
 * operate under.
 * <p>
 * A main is the unit of management. It can be closed, capped, given a colour and an owner, and
 * linked to other mains as backup supply. Fluid entering through its Inlets lands in a small
 * quantity of line pack and leaves through its Outlets within the same tick -- the pack exists to
 * make the collect-then-serve ordering work, not to store anything.
 * <p>
 * <strong>A main carries one fluid at a time.</strong> That is the single thing this class has
 * that {@code GridSegment} does not, and it is a type check rather than an architecture. The type
 * is latched from the first Inlet that offers something and can only be changed by hand while the
 * pack is empty -- otherwise re-typing a live main would silently destroy whatever was in it and
 * start feeding every connected machine something else.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidMain
{
	/**
	 * Colours offered by the console's picker, in menu order. The same family the grid uses, so
	 * the two consoles read as one mod.
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
	private FluidPolicy policy = new FluidPolicy();

	/**
	 * The fluid's registry name, or null for a main that has not yet carried anything.
	 * <p>
	 * A name rather than a {@code Fluid}: it survives a fluid being registered later, it is what
	 * the save file holds anyway, and it keeps this whole class loadable without a fluid registry
	 * -- which is what makes the engine testable.
	 */
	@Nullable
	private String fluid;

	private int pack;
	private final List<UUID> failover = new ArrayList<>();
	private final List<FluidDevice> devices = new ArrayList<>();

	private final FluidStats stats = new FluidStats();

	/**
	 * Overpressure cut-out. A tripped main behaves as if closed and must be reset by hand --
	 * consequence rather than a silent clamp.
	 */
	private boolean tripped;
	private transient int saturatedTicks;

	//	=================================
	//		TRANSIENT PER-TICK STATE
	//	=================================
	private transient int tickIn;
	private transient int tickOut;
	/**
	 * City mode: this main's own Inlets proved live. Distinct from {@link #pressurised}, which
	 * also accounts for a live backup standing in.
	 */
	private transient boolean sourceLive;
	private transient boolean pressurised;
	/**
	 * A Valve in input mode is holding this main closed. Recomputed from the world every tick, so
	 * it is never persisted -- an external shut-off that is itself gone must not keep a main
	 * closed across a restart.
	 */
	private transient boolean forcedClosed;
	/**
	 * The clock is outside this main's operating window. Also transient and recomputed every tick,
	 * for the same reason: the schedule is the truth, not a latched flag.
	 */
	private transient boolean scheduleSuppressed;
	/**
	 * Priority-ordered views, rebuilt only when membership or ordering actually changes.
	 */
	private transient final List<FluidDevice> activeInlets = new ArrayList<>();
	private transient final List<FluidDevice> activeOutlets = new ArrayList<>();
	private transient final List<FluidDevice> activeValves = new ArrayList<>();
	private transient boolean viewsDirty = true;

	public FluidMain(UUID id, String name)
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
			//Opening a main is also the trip reset -- one control, as on a real valve station,
			//rather than a separate hidden "unlatch" action.
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
	 * @return true if {@code player} may reconfigure this main
	 */
	public boolean canEdit(@Nullable UUID player)
	{
		if(!locked||owner==null)
			return true;
		return owner.equals(player);
	}

	public FluidPolicy getPolicy()
	{
		return policy;
	}

	public void setPolicy(FluidPolicy policy)
	{
		this.policy = policy==null?new FluidPolicy(): policy;
	}

	public FluidStats getStats()
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

	//	=================================
	//		THE FLUID
	//	=================================

	/**
	 * @return the registry name of what this main carries, or null if it has never carried
	 * anything
	 */
	@Nullable
	public String getFluid()
	{
		return fluid;
	}

	public boolean isTyped()
	{
		return fluid!=null;
	}

	/**
	 * Types an untyped main. Called by the engine from the first Inlet that has something to
	 * offer, so a player who plumbs a main up and switches it on never has to name the fluid.
	 *
	 * @return true if the main took the type
	 */
	public boolean typeFrom(@Nullable String fluidName)
	{
		if(fluid!=null||fluidName==null||fluidName.isEmpty())
			return false;
		fluid = fluidName;
		notifyDevices();
		return true;
	}

	/**
	 * Changes what the main carries, by hand.
	 * <p>
	 * <strong>Refused while there is line pack.</strong> Re-typing a main that still holds
	 * something would either destroy it silently or start delivering the wrong fluid into every
	 * machine on the network, and neither is a thing a player can debug. Draining first is one
	 * extra step and it is the honest one.
	 *
	 * @param fluidName the new fluid's registry name, or null to un-type the main
	 * @return true if the change was applied
	 */
	public boolean setFluid(@Nullable String fluidName)
	{
		if(pack > 0)
			return false;
		String next = fluidName==null||fluidName.isEmpty()?null: fluidName;
		if(Objects.equals(next, fluid))
			return true;
		fluid = next;
		notifyDevices();
		return true;
	}

	/**
	 * Tells every attached endpoint that something it cares about changed. Used when the fluid
	 * changes, because an Inlet's own buffer has to be re-typed with it.
	 */
	public void notifyDevices()
	{
		for(int i = 0; i < devices.size(); i++)
			devices.get(i).notifyEndpoint();
	}

	/**
	 * @return true if the main is open, its cut-out has not latched, no Valve is holding it
	 * closed, and it is inside its schedule
	 */
	public boolean isOperational()
	{
		return enabled&&!tripped&&!forcedClosed&&!scheduleSuppressed;
	}

	/**
	 * @return true if a Valve in input mode is currently holding this main closed
	 */
	public boolean isForcedClosed()
	{
		return forcedClosed;
	}

	public void setForcedClosed(boolean forcedClosed)
	{
		this.forcedClosed = forcedClosed;
	}

	/**
	 * @return true if the main is open but its schedule currently says otherwise
	 */
	public boolean isScheduleSuppressed()
	{
		return scheduleSuppressed;
	}

	/**
	 * Re-evaluates the schedule against the world clock.
	 * <p>
	 * The schedule is a <em>gate</em>, not a second switch: it can only hold a main closed, never
	 * open one the player closed. Otherwise the console's toggle and the clock would fight each
	 * other every dusk, and whichever ran last would win.
	 *
	 * @param dayTime the world time of day, 0-23999
	 */
	public void updateSchedule(long dayTime)
	{
		scheduleSuppressed = !policy.isWithinSchedule(dayTime);
	}

	//	=================================
	//		LINE PACK
	//	=================================

	public int getPack()
	{
		return pack;
	}

	public void setPack(int pack)
	{
		this.pack = pack < 0?0: Math.min(pack, policy.getPackCap());
	}

	/**
	 * Adds to the line pack, discarding anything past the cap.
	 *
	 * @return the amount actually stored
	 */
	public int addToPack(int amount)
	{
		if(amount <= 0)
			return 0;
		int room = policy.getPackCap()-pack;
		int stored = Math.min(room, amount);
		if(stored > 0)
			pack += stored;
		return stored;
	}

	/**
	 * @return the amount actually removed, never more than is present
	 */
	public int drawFromPack(int amount)
	{
		if(amount <= 0)
			return 0;
		int taken = Math.min(pack, amount);
		pack -= taken;
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
	 * Appends a backup. Self-links and duplicates are rejected; deeper cycles are allowed here and
	 * broken at traversal time by the visited set, because a cycle can also be created from the
	 * far end.
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
	 * @return every device assigned to this main, online or not
	 */
	public List<FluidDevice> getDevices()
	{
		return Collections.unmodifiableList(devices);
	}

	public int getDeviceCount()
	{
		return devices.size();
	}

	public int getDeviceCount(FluidDeviceType type)
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

	void addDeviceInternal(FluidDevice device)
	{
		if(!devices.contains(device))
		{
			devices.add(device);
			viewsDirty = true;
		}
	}

	boolean removeDeviceInternal(FluidDevice device)
	{
		boolean removed = devices.remove(device);
		if(removed)
			viewsDirty = true;
		return removed;
	}

	/**
	 * Marks the priority-ordered views stale. Called whenever a device is added, removed, comes
	 * online, goes offline, or has its priority/critical flag changed.
	 */
	public void invalidateViews()
	{
		viewsDirty = true;
	}

	/**
	 * @return active Inlets, highest priority first
	 */
	public List<FluidDevice> getActiveInlets()
	{
		rebuildViewsIfNeeded();
		return activeInlets;
	}

	/**
	 * @return active Outlets, critical loads first then highest priority
	 */
	public List<FluidDevice> getActiveOutlets()
	{
		rebuildViewsIfNeeded();
		return activeOutlets;
	}

	/**
	 * @return active Valves. Unordered relative to each other -- one closed valve closes the main
	 * regardless of who else voted, and every indicator sees the same state.
	 */
	public List<FluidDevice> getActiveValves()
	{
		rebuildViewsIfNeeded();
		return activeValves;
	}

	private void rebuildViewsIfNeeded()
	{
		if(!viewsDirty)
			return;
		activeInlets.clear();
		activeOutlets.clear();
		activeValves.clear();
		for(int i = 0; i < devices.size(); i++)
		{
			FluidDevice device = devices.get(i);
			if(!device.isActive())
				continue;
			if(device.getType()==FluidDeviceType.INLET)
				activeInlets.add(device);
			else if(device.getType()==FluidDeviceType.OUTLET)
				activeOutlets.add(device);
			else if(device.getType()==FluidDeviceType.VALVE)
				activeValves.add(device);
		}
		activeInlets.sort(INLET_ORDER);
		activeOutlets.sort(OUTLET_ORDER);
		viewsDirty = false;
	}

	/**
	 * Highest priority drains first. Position is the tie-break so ordering is stable across
	 * restarts rather than depending on hash iteration order.
	 */
	public static final Comparator<FluidDevice> INLET_ORDER = (a, b) -> {
		int cmp = Integer.compare(b.getPriority(), a.getPriority());
		return cmp!=0?cmp: comparePos(a, b);
	};

	/**
	 * Critical loads are served before everything else -- that is the load-shedding rule. Within a
	 * class, highest priority first.
	 */
	public static final Comparator<FluidDevice> OUTLET_ORDER = (a, b) -> {
		if(a.isCritical()!=b.isCritical())
			return a.isCritical()?-1: 1;
		int cmp = Integer.compare(b.getPriority(), a.getPriority());
		return cmp!=0?cmp: comparePos(a, b);
	};

	private static int comparePos(FluidDevice a, FluidDevice b)
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
	 * @return how much more may still enter this main this tick
	 */
	public int getInputBudget()
	{
		return Math.max(0, policy.getMaxInput()-tickIn);
	}

	/**
	 * @return how much more may still leave this main this tick
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
	 * @return city mode: whether fluid actually leaves this main, counting failover
	 */
	public boolean isPressurised()
	{
		return pressurised;
	}

	public void setPressurised(boolean pressurised)
	{
		this.pressurised = pressurised;
	}

	/**
	 * The one-bit summary a Valve reports and the console lamp draws: is this main actually
	 * carrying anything right now?
	 * <p>
	 * In city mode that is exactly {@link #isPressurised()}. In normal mode a main is up when it
	 * is operational and fluid either moved through it this tick or is sitting in its pack -- so a
	 * main whose wells ran dry reads as down even though nobody closed it, which is the case an
	 * alarm lamp exists for.
	 */
	public boolean isUp(boolean cityMode)
	{
		if(cityMode)
			return pressurised;
		return isOperational()&&(pack > 0||tickIn > 0||tickOut > 0);
	}

	/**
	 * @return city mode: whether this main's own Inlets are live
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
	 * Advances the overpressure cut-out. A main that spent the whole tick at its output ceiling is
	 * saturated; {@link FluidNetConfig#tripSeconds} consecutive seconds of that latches it closed.
	 *
	 * @return true if the cut-out tripped on this call
	 */
	public boolean updateTrip()
	{
		//A main that is closed for any reason -- switch, cut-out, valve, schedule -- is not
		//saturating, so nothing should be accumulating against its cut-out.
		if(!FluidNetConfig.tripsEnabled||!isOperational())
			return false;
		if(policy.getMaxOutput() > 0&&tickOut >= policy.getMaxOutput())
			saturatedTicks++;
		else
			saturatedTicks = 0;
		if(saturatedTicks >= FluidNetConfig.tripSeconds*20)
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
	 * @param live also include the state the engine recomputes every tick. Save data leaves it out
	 *             -- it is derived, and a stale copy read back at world load would be wrong until
	 *             the first tick corrected it. A GUI sync needs it, because it is most of what the
	 *             console actually displays.
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
		nbt.setInteger("pack", pack);
		if(fluid!=null)
			nbt.setString("fluid", fluid);
		nbt.setTag("policy", policy.writeToNBT(new NBTTagCompound()));
		nbt.setTag("stats", stats.writeToNBT(new NBTTagCompound(), live));
		if(live)
		{
			nbt.setBoolean("pressurised", pressurised);
			nbt.setBoolean("sourceLive", sourceLive);
			nbt.setBoolean("forcedClosed", forcedClosed);
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
	 * Reads a main. Devices are <em>not</em> read here -- {@link VirtualFluidNet} owns the device
	 * table and re-attaches them, so a device can never end up in two mains.
	 */
	@Nullable
	public static FluidMain readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null)
			return null;
		UUID id = FluidDevice.parseUUID(nbt.getString("id"));
		if(id==null)
			return null;
		FluidMain main = new FluidMain(id, nbt.getString("name"));
		if(nbt.hasKey("color"))
			main.color = nbt.getInteger("color");
		main.enabled = !nbt.hasKey("enabled")||nbt.getBoolean("enabled");
		if(nbt.hasKey("owner"))
			main.owner = FluidDevice.parseUUID(nbt.getString("owner"));
		main.locked = nbt.getBoolean("locked");
		main.tripped = nbt.getBoolean("tripped");
		main.policy = FluidPolicy.readFromNBT(nbt.getCompoundTag("policy"));
		main.stats.readFromNBT(nbt.getCompoundTag("stats"));
		main.setPack(nbt.getInteger("pack"));
		//Assigned directly rather than through setFluid, which refuses while the pack is non-empty
		//-- and the pack has just been read back.
		if(nbt.hasKey("fluid"))
		{
			String stored = nbt.getString("fluid");
			main.fluid = stored.isEmpty()?null: stored;
		}
		//Absent in save data, present in a GUI sync. Reading them unconditionally is safe: on the
		//server the next tick recomputes all four before anything reads them.
		main.pressurised = nbt.getBoolean("pressurised");
		main.sourceLive = nbt.getBoolean("sourceLive");
		main.forcedClosed = nbt.getBoolean("forcedClosed");
		main.scheduleSuppressed = nbt.getBoolean("scheduleSuppressed");
		main.tickIn = nbt.getInteger("tickIn");
		main.tickOut = nbt.getInteger("tickOut");

		NBTTagList failoverList = nbt.getTagList("failover", 8);
		for(int i = 0; i < failoverList.tagCount(); i++)
		{
			UUID target = FluidDevice.parseUUID(failoverList.getStringTagAt(i));
			if(target!=null)
				main.addFailover(target);
		}
		return main;
	}

	@Override
	public String toString()
	{
		return "FluidMain["+name+" ("+id+"), "+(fluid==null?"untyped": fluid)+", "
				+devices.size()+" devices, "+(enabled?tripped?"TRIPPED": "open": "closed")+"]";
	}
}
