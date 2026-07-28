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
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The server-wide registry of mains and their endpoints.
 * <p>
 * Deliberately independent of {@code TileEntityFluidPipe}'s network: the virtual fluid network
 * shares no state with physical pipe, so neither can regress the other. Unlike the pipe net there
 * is no topology to search -- a main is a flat list of devices -- so nothing here caches routes
 * and nothing needs invalidating beyond the priority-ordered views. That is the whole reason this
 * exists: a gas main under every road of a city is a handful of records, not thousands of block
 * entities and a BFS cache that one edit invalidates.
 * <p>
 * Persistence lives in {@code FluidNetSaveData}, which registers itself through
 * {@link #setDirtyListener} rather than being imported from here.
 * <p>
 * The deliberate mirror of {@code VirtualGrid}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class VirtualFluidNet
{
	public static final VirtualFluidNet INSTANCE = new VirtualFluidNet();

	/**
	 * Bumped whenever the persisted shape changes, so a future reader can migrate.
	 */
	public static final int DATA_VERSION = 1;

	private final Map<UUID, FluidMain> mains = new LinkedHashMap<>();
	private final Map<DimensionBlockPos, FluidDevice> devices = new HashMap<>();

	@Nullable
	private Runnable dirtyListener;

	public VirtualFluidNet()
	{
	}

	/**
	 * Hooks the world-save "mark dirty" callback in without the API package having to know about
	 * it.
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
	//		MAINS
	//	=================================

	public Collection<FluidMain> getMains()
	{
		return Collections.unmodifiableCollection(mains.values());
	}

	public int getMainCount()
	{
		return mains.size();
	}

	@Nullable
	public FluidMain getMain(@Nullable UUID id)
	{
		return id==null?null: mains.get(id);
	}

	/**
	 * @return the first main with this name, case-insensitively, or null
	 */
	@Nullable
	public FluidMain getMainByName(String name)
	{
		if(name==null)
			return null;
		for(FluidMain main : mains.values())
			if(main.getName().equalsIgnoreCase(name))
				return main;
		return null;
	}

	public FluidMain createMain(String name)
	{
		return createMain(name, null);
	}

	public FluidMain createMain(String name, @Nullable UUID owner)
	{
		UUID id = UUID.randomUUID();
		while(mains.containsKey(id))
			id = UUID.randomUUID();
		FluidMain main = new FluidMain(id, name);
		main.setOwner(owner);
		mains.put(id, main);
		markDirty();
		return main;
	}

	/**
	 * Adds a pre-built main. Used by the loader; refuses to overwrite an existing id.
	 *
	 * @return true if it was added
	 */
	public boolean addMain(FluidMain main)
	{
		if(main==null||mains.containsKey(main.getId()))
			return false;
		mains.put(main.getId(), main);
		markDirty();
		return true;
	}

	/**
	 * Removes a main, unlinking its devices and stripping it out of every other main's failover
	 * list so no dangling reference survives.
	 *
	 * @return true if a main was removed
	 */
	public boolean deleteMain(UUID id)
	{
		FluidMain main = mains.remove(id);
		if(main==null)
			return false;
		//Copy first: unassign mutates the main's own device list.
		for(FluidDevice device : new ArrayList<>(main.getDevices()))
		{
			device.setMainInternal(null);
			device.notifyEndpoint();
		}
		for(FluidMain other : mains.values())
			other.removeFailover(id);
		markDirty();
		return true;
	}

	//	=================================
	//		DEVICES
	//	=================================

	public Collection<FluidDevice> getDevices()
	{
		return Collections.unmodifiableCollection(devices.values());
	}

	public int getDeviceCount()
	{
		return devices.size();
	}

	@Nullable
	public FluidDevice getDevice(DimensionBlockPos pos)
	{
		return pos==null?null: devices.get(pos);
	}

	/**
	 * @return every registered device not currently assigned to a main
	 */
	public List<FluidDevice> getUnlinkedDevices()
	{
		List<FluidDevice> out = new ArrayList<>();
		for(FluidDevice device : devices.values())
			if(!device.isLinked())
				out.add(device);
		return out;
	}

	/**
	 * Registers a device, or returns the existing record if one is already known at that position
	 * and of that type. A record of a <em>different</em> type at the same position is replaced --
	 * that means the block was broken and another put in its place.
	 */
	public FluidDevice registerDevice(DimensionBlockPos pos, FluidDeviceType type)
	{
		FluidDevice existing = devices.get(pos);
		if(existing!=null)
		{
			if(existing.getType()==type)
				return existing;
			unregisterDevice(pos);
		}
		FluidDevice device = new FluidDevice(pos, type);
		devices.put(pos, device);
		markDirty();
		return device;
	}

	/**
	 * Re-attaches a device record loaded from save data.
	 *
	 * @return true if it was added
	 */
	public boolean addDevice(FluidDevice device)
	{
		if(device==null||devices.containsKey(device.getPos()))
			return false;
		devices.put(device.getPos(), device);
		UUID mainId = device.getMain();
		if(mainId!=null)
		{
			FluidMain main = mains.get(mainId);
			if(main!=null)
				main.addDeviceInternal(device);
			else
				//The main went away while this device's chunk was unloaded. Better an unlinked
				//device the player can re-assign than a reference into nothing.
				device.setMainInternal(null);
		}
		return true;
	}

	/**
	 * @return the removed device, or null if nothing was registered there
	 */
	@Nullable
	public FluidDevice unregisterDevice(DimensionBlockPos pos)
	{
		FluidDevice device = devices.remove(pos);
		if(device==null)
			return null;
		FluidMain main = getMain(device.getMain());
		if(main!=null)
			main.removeDeviceInternal(device);
		device.setEndpoint(null);
		device.setMainInternal(null);
		markDirty();
		return device;
	}

	/**
	 * Moves a device to a main, or unlinks it when {@code mainId} is null.
	 *
	 * @return true if the assignment took effect
	 */
	public boolean assignDevice(FluidDevice device, @Nullable UUID mainId)
	{
		if(device==null)
			return false;
		FluidMain target = mainId==null?null: mains.get(mainId);
		if(mainId!=null&&target==null)
			return false;
		if(target!=null&&!FluidNetConfig.crossDimension&&!acceptsDimension(target, device.getDimension()))
			return false;

		FluidMain current = getMain(device.getMain());
		if(current==target)
			return true;
		if(current!=null)
			current.removeDeviceInternal(device);
		device.setMainInternal(target==null?null: target.getId());
		if(target!=null)
			target.addDeviceInternal(device);
		device.notifyEndpoint();
		markDirty();
		return true;
	}

	/**
	 * With cross-dimension transfer disabled, a main is pinned to the dimension of whichever
	 * device joined it first.
	 */
	private static boolean acceptsDimension(FluidMain main, int dimension)
	{
		for(FluidDevice existing : main.getDevices())
			if(existing.getDimension()!=dimension)
				return false;
		return true;
	}

	//	=================================
	//		ONLINE / OFFLINE
	//	=================================

	/**
	 * Called when a device's tile entity loads. Devices that are not yet registered are created
	 * here, which is what makes the system self-healing if save data is lost.
	 */
	public FluidDevice attach(DimensionBlockPos pos, FluidDeviceType type, IFluidEndpoint endpoint)
	{
		FluidDevice device = registerDevice(pos, type);
		device.setEndpoint(endpoint);
		FluidMain main = getMain(device.getMain());
		if(main!=null)
			main.invalidateViews();
		return device;
	}

	/**
	 * Called when a device's tile entity unloads or is broken. The record stays -- only the live
	 * attachment goes away.
	 */
	public void detach(DimensionBlockPos pos)
	{
		FluidDevice device = devices.get(pos);
		if(device==null)
			return;
		device.setEndpoint(null);
		FluidMain main = getMain(device.getMain());
		if(main!=null)
			main.invalidateViews();
	}

	/**
	 * Drops every live attachment, e.g. on world unload, without touching the records.
	 */
	public void detachAll()
	{
		for(FluidDevice device : devices.values())
			device.setEndpoint(null);
		for(FluidMain main : mains.values())
			main.invalidateViews();
	}

	/**
	 * Wipes all network state. Called before loading a world so data from a previous world in the
	 * same process can never leak across.
	 */
	public void clear()
	{
		mains.clear();
		devices.clear();
	}

	//	=================================
	//		AGGREGATES
	//	=================================

	/**
	 * @return total fluid delivered by every main during the last completed tick
	 */
	public int getTotalOut()
	{
		int total = 0;
		for(FluidMain main : mains.values())
			total += main.getStats().getLastTickOut();
		return total;
	}

	/**
	 * @return total fluid taken in by every main during the last completed tick
	 */
	public int getTotalIn()
	{
		int total = 0;
		for(FluidMain main : mains.values())
			total += main.getStats().getLastTickIn();
		return total;
	}

	public int getPressurisedMainCount()
	{
		int count = 0;
		for(FluidMain main : mains.values())
			if(main.isPressurised())
				count++;
		return count;
	}

	/**
	 * Re-clamps every main's policy. Called after the config reloads so lowered ceilings take
	 * effect immediately.
	 */
	public void onConfigChanged()
	{
		for(FluidMain main : mains.values())
		{
			main.getPolicy().clamp();
			main.setPack(main.getPack());
		}
		for(FluidDevice device : devices.values())
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
	 * Serialises the whole network.
	 *
	 * @param live include the per-tick state the engine derives -- what is online, what moved,
	 *             what is pressurised, the stats history. The console GUI is almost entirely a
	 *             view of that state, so its sync sets this; the world save does not, because none
	 *             of it is worth persisting and a stale copy read back at load would contradict
	 *             the first tick.
	 */
	public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean live)
	{
		nbt.setInteger("fluidNetDataVersion", DATA_VERSION);

		NBTTagList mainList = new NBTTagList();
		for(FluidMain main : mains.values())
			mainList.appendTag(main.writeToNBT(new NBTTagCompound(), live));
		nbt.setTag("mains", mainList);

		NBTTagList deviceList = new NBTTagList();
		for(FluidDevice device : devices.values())
			deviceList.appendTag(device.writeToNBT(new NBTTagCompound(), live));
		nbt.setTag("devices", deviceList);
		return nbt;
	}

	/**
	 * Replaces all state with what is in {@code nbt}. Mains are read first so devices can bind to
	 * them.
	 */
	public void readFromNBT(NBTTagCompound nbt)
	{
		clear();
		if(nbt==null)
			return;

		NBTTagList mainList = nbt.getTagList("mains", 10);
		for(int i = 0; i < mainList.tagCount(); i++)
		{
			FluidMain main = FluidMain.readFromNBT(mainList.getCompoundTagAt(i));
			if(main!=null)
				mains.put(main.getId(), main);
		}

		NBTTagList deviceList = nbt.getTagList("devices", 10);
		for(int i = 0; i < deviceList.tagCount(); i++)
		{
			FluidDevice device = FluidDevice.readFromNBT(deviceList.getCompoundTagAt(i));
			if(device!=null)
				addDevice(device);
		}

		//Drop failover links to mains that no longer exist, so the traversal never has to defend
		//against them at runtime.
		for(FluidMain main : mains.values())
			for(UUID target : new ArrayList<>(main.getFailover()))
				if(!mains.containsKey(target))
					main.removeFailover(target);
	}
}
