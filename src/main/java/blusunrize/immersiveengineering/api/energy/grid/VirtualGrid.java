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
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The server-wide registry of grid segments and devices.
 * <p>
 * Deliberately independent of {@code ImmersiveNetHandler}: the virtual grid shares no
 * state with the physical wire network, so neither can regress the other. Unlike the wire
 * net there is no topology to search -- a segment is a flat list of devices -- so nothing
 * here caches routes and nothing needs invalidating beyond the priority-ordered views.
 * <p>
 * Persistence lives in {@code GridSaveData}, which registers itself through
 * {@link #setDirtyListener} rather than being imported from here.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class VirtualGrid
{
	public static final VirtualGrid INSTANCE = new VirtualGrid();

	/**
	 * Bumped whenever the persisted shape changes, so a future reader can migrate.
	 */
	public static final int DATA_VERSION = 1;

	private final Map<UUID, GridSegment> segments = new LinkedHashMap<>();
	private final Map<DimensionBlockPos, GridDevice> devices = new HashMap<>();

	@Nullable
	private Runnable dirtyListener;

	public VirtualGrid()
	{
	}

	/**
	 * Hooks the world-save "mark dirty" callback in without the API package having to
	 * know about it.
	 */
	public void setDirtyListener(@Nullable Runnable dirtyListener)
	{
		this.dirtyListener = dirtyListener;
	}

	public void markDirty()
	{
		if(dirtyListener!=null)
			dirtyListener.run();
	}

	//	=================================
	//		SEGMENTS
	//	=================================

	public Collection<GridSegment> getSegments()
	{
		return Collections.unmodifiableCollection(segments.values());
	}

	public int getSegmentCount()
	{
		return segments.size();
	}

	@Nullable
	public GridSegment getSegment(@Nullable UUID id)
	{
		return id==null?null: segments.get(id);
	}

	/**
	 * @return the first segment with this name, case-insensitively, or null
	 */
	@Nullable
	public GridSegment getSegmentByName(String name)
	{
		if(name==null)
			return null;
		for(GridSegment segment : segments.values())
			if(segment.getName().equalsIgnoreCase(name))
				return segment;
		return null;
	}

	public GridSegment createSegment(String name)
	{
		return createSegment(name, null);
	}

	public GridSegment createSegment(String name, @Nullable UUID owner)
	{
		UUID id = UUID.randomUUID();
		while(segments.containsKey(id))
			id = UUID.randomUUID();
		GridSegment segment = new GridSegment(id, name);
		segment.setOwner(owner);
		segments.put(id, segment);
		markDirty();
		return segment;
	}

	/**
	 * Adds a pre-built segment. Used by the loader; refuses to overwrite an existing id.
	 *
	 * @return true if it was added
	 */
	public boolean addSegment(GridSegment segment)
	{
		if(segment==null||segments.containsKey(segment.getId()))
			return false;
		segments.put(segment.getId(), segment);
		markDirty();
		return true;
	}

	/**
	 * Removes a segment, unlinking its devices and stripping it out of every other
	 * segment's failover list so no dangling reference survives.
	 *
	 * @return true if a segment was removed
	 */
	public boolean deleteSegment(UUID id)
	{
		GridSegment segment = segments.remove(id);
		if(segment==null)
			return false;
		//Copy first: unassign mutates the segment's own device list.
		for(GridDevice device : new ArrayList<>(segment.getDevices()))
		{
			device.setSegmentInternal(null);
			device.notifyEndpoint();
		}
		for(GridSegment other : segments.values())
			other.removeFailover(id);
		markDirty();
		return true;
	}

	//	=================================
	//		DEVICES
	//	=================================

	public Collection<GridDevice> getDevices()
	{
		return Collections.unmodifiableCollection(devices.values());
	}

	public int getDeviceCount()
	{
		return devices.size();
	}

	@Nullable
	public GridDevice getDevice(DimensionBlockPos pos)
	{
		return pos==null?null: devices.get(pos);
	}

	/**
	 * @return every registered device not currently assigned to a segment
	 */
	public List<GridDevice> getUnlinkedDevices()
	{
		List<GridDevice> out = new ArrayList<>();
		for(GridDevice device : devices.values())
			if(!device.isLinked())
				out.add(device);
		return out;
	}

	/**
	 * Registers a device, or returns the existing record if one is already known at that
	 * position and of that type. A record of a <em>different</em> type at the same
	 * position is replaced -- that means the block was broken and another put in its place.
	 */
	public GridDevice registerDevice(DimensionBlockPos pos, GridDeviceType type)
	{
		GridDevice existing = devices.get(pos);
		if(existing!=null)
		{
			if(existing.getType()==type)
				return existing;
			unregisterDevice(pos);
		}
		GridDevice device = new GridDevice(pos, type);
		devices.put(pos, device);
		markDirty();
		return device;
	}

	/**
	 * Re-attaches a device record loaded from save data.
	 *
	 * @return true if it was added
	 */
	public boolean addDevice(GridDevice device)
	{
		if(device==null||devices.containsKey(device.getPos()))
			return false;
		devices.put(device.getPos(), device);
		UUID segmentId = device.getSegment();
		if(segmentId!=null)
		{
			GridSegment segment = segments.get(segmentId);
			if(segment!=null)
				segment.addDeviceInternal(device);
			else
				//The segment went away while this device's chunk was unloaded. Better an
				//unlinked device the player can re-assign than a reference into nothing.
				device.setSegmentInternal(null);
		}
		return true;
	}

	/**
	 * @return the removed device, or null if nothing was registered there
	 */
	@Nullable
	public GridDevice unregisterDevice(DimensionBlockPos pos)
	{
		GridDevice device = devices.remove(pos);
		if(device==null)
			return null;
		GridSegment segment = getSegment(device.getSegment());
		if(segment!=null)
			segment.removeDeviceInternal(device);
		device.setEndpoint(null);
		device.setSegmentInternal(null);
		markDirty();
		return device;
	}

	/**
	 * Moves a device to a segment, or unlinks it when {@code segmentId} is null.
	 *
	 * @return true if the assignment took effect
	 */
	public boolean assignDevice(GridDevice device, @Nullable UUID segmentId)
	{
		if(device==null)
			return false;
		GridSegment target = segmentId==null?null: segments.get(segmentId);
		if(segmentId!=null&&target==null)
			return false;
		if(target!=null&&!GridConfig.crossDimension&&!acceptsDimension(target, device.getDimension()))
			return false;

		GridSegment current = getSegment(device.getSegment());
		if(current==target)
			return true;
		if(current!=null)
			current.removeDeviceInternal(device);
		device.setSegmentInternal(target==null?null: target.getId());
		if(target!=null)
			target.addDeviceInternal(device);
		device.notifyEndpoint();
		markDirty();
		return true;
	}

	/**
	 * With cross-dimension transfer disabled, a segment is pinned to the dimension of
	 * whichever device joined it first.
	 */
	private static boolean acceptsDimension(GridSegment segment, int dimension)
	{
		for(GridDevice existing : segment.getDevices())
			if(existing.getDimension()!=dimension)
				return false;
		return true;
	}

	//	=================================
	//		ONLINE / OFFLINE
	//	=================================

	/**
	 * Called when a device's tile entity loads. Devices that are not yet registered are
	 * created here, which is what makes the system self-healing if save data is lost.
	 */
	public GridDevice attach(DimensionBlockPos pos, GridDeviceType type, IGridEndpoint endpoint)
	{
		GridDevice device = registerDevice(pos, type);
		device.setEndpoint(endpoint);
		GridSegment segment = getSegment(device.getSegment());
		if(segment!=null)
			segment.invalidateViews();
		return device;
	}

	/**
	 * Called when a device's tile entity unloads or is broken. The record stays -- only
	 * the live attachment goes away.
	 */
	public void detach(DimensionBlockPos pos)
	{
		GridDevice device = devices.get(pos);
		if(device==null)
			return;
		device.setEndpoint(null);
		GridSegment segment = getSegment(device.getSegment());
		if(segment!=null)
			segment.invalidateViews();
	}

	/**
	 * Drops every live attachment, e.g. on world unload, without touching the records.
	 */
	public void detachAll()
	{
		for(GridDevice device : devices.values())
			device.setEndpoint(null);
		for(GridSegment segment : segments.values())
			segment.invalidateViews();
	}

	/**
	 * Wipes all grid state. Called before loading a world so data from a previous world
	 * in the same process can never leak across.
	 */
	public void clear()
	{
		segments.clear();
		devices.clear();
	}

	//	=================================
	//		AGGREGATES
	//	=================================

	/**
	 * @return total energy delivered by every segment during the last completed tick
	 */
	public int getTotalOut()
	{
		int total = 0;
		for(GridSegment segment : segments.values())
			total += segment.getStats().getLastTickOut();
		return total;
	}

	/**
	 * @return total energy taken in by every segment during the last completed tick
	 */
	public int getTotalIn()
	{
		int total = 0;
		for(GridSegment segment : segments.values())
			total += segment.getStats().getLastTickIn();
		return total;
	}

	public int getEnergizedSegmentCount()
	{
		int count = 0;
		for(GridSegment segment : segments.values())
			if(segment.isEnergized())
				count++;
		return count;
	}

	/**
	 * Re-clamps every segment's policy. Called after the config reloads so lowered
	 * ceilings take effect immediately.
	 */
	public void onConfigChanged()
	{
		for(GridSegment segment : segments.values())
		{
			segment.getPolicy().clamp();
			segment.setBuffer(segment.getBuffer());
		}
		for(GridDevice device : devices.values())
		{
			device.setTransferCap(device.getTransferCap());
			device.notifyEndpoint();
		}
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return writeToNBT(nbt, false);
	}

	/**
	 * Serialises the whole grid.
	 *
	 * @param live include the per-tick state the engine derives -- what is online, what
	 *             moved, what is energized, the stats history. The console GUI is almost
	 *             entirely a view of that state, so its sync sets this; the world save does
	 *             not, because none of it is worth persisting and a stale copy read back at
	 *             load would contradict the first tick.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setInteger("gridDataVersion", DATA_VERSION);

		NBTTagList segmentList = new NBTTagList();
		for(GridSegment segment : segments.values())
			segmentList.appendTag(segment.writeToNBT(new NBTTagCompound(), live));
		nbt.setTag("segments", segmentList);

		NBTTagList deviceList = new NBTTagList();
		for(GridDevice device : devices.values())
			deviceList.appendTag(device.writeToNBT(new NBTTagCompound(), live));
		nbt.setTag("devices", deviceList);
		return nbt;
	}

	/**
	 * Replaces all state with what is in {@code nbt}. Segments are read first so devices
	 * can bind to them.
	 */
	public void readFromNBT(NBTTagCompound nbt)
	{
		clear();
		if(nbt==null)
			return;

		NBTTagList segmentList = nbt.getTagList("segments", 10);
		for(int i = 0; i < segmentList.tagCount(); i++)
		{
			GridSegment segment = GridSegment.readFromNBT(segmentList.getCompoundTagAt(i));
			if(segment!=null)
				segments.put(segment.getId(), segment);
		}

		NBTTagList deviceList = nbt.getTagList("devices", 10);
		for(int i = 0; i < deviceList.tagCount(); i++)
		{
			GridDevice device = GridDevice.readFromNBT(deviceList.getCompoundTagAt(i));
			if(device!=null)
				addDevice(device);
		}

		//Drop failover links to segments that no longer exist, so the traversal never has
		//to defend against them at runtime.
		for(GridSegment segment : segments.values())
			for(UUID target : new ArrayList<>(segment.getFailover()))
				if(!segments.containsKey(target))
					segment.removeFailover(target);
	}
}
