/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.DimensionManager;

import java.util.*;

/**
 * Keeps the chunks of chunk-load-flagged grid devices loaded.
 * <p>
 * One ticket per dimension rather than one per device: Forge caps tickets per mod, and a
 * city-scale grid would exhaust that allowance immediately. Everything is rebuilt from
 * {@link VirtualGrid} rather than tracked incrementally, so the forced set cannot drift
 * out of step with the device records -- the rebuild is O(devices) and only runs when
 * something actually changes.
 * <p>
 * The budget is a hard server-wide cap on how many chunks the grid may pin. Anything past
 * it is dropped and logged rather than silently ignored, because a grid quietly not
 * loading the chunks you asked it to is worse than being told it ran out.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridChunkLoader
{
	private static final Map<Integer, Ticket> tickets = new HashMap<>();
	/**
	 * What is currently forced, so a rebuild that changes nothing costs nothing.
	 */
	private static final Map<Integer, Set<ChunkPos>> forced = new HashMap<>();
	private static int lastDroppedCount;

	/**
	 * Forge hands back any tickets it persisted from the last session. The grid rebuilds
	 * its own set from save data, so those are released and replaced rather than adopted --
	 * adopting them would resurrect chunk loads for devices that have since been removed.
	 */
	public static void init()
	{
		ForgeChunkManager.setForcedChunkLoadingCallback(ImmersiveEngineering.instance,
				(tickets, world) -> {
					//Returning an empty list tells Forge to drop every persisted ticket.
				});
	}

	/**
	 * Recomputes the forced-chunk set. Safe to call often; does nothing when the result
	 * matches what is already forced.
	 */
	public static void refresh()
	{
		Map<Integer, Set<ChunkPos>> wanted = new HashMap<>();
		int budget = Math.max(0, GridConfig.chunkloadBudget);
		int used = 0;
		int dropped = 0;

		if(GridConfig.enabled&&GridConfig.allowChunkloading)
			for(GridDevice device : VirtualGrid.INSTANCE.getDevices())
			{
				if(!device.isChunkLoad()||!device.isEnabled())
					continue;
				ChunkPos chunk = new ChunkPos(device.getPos().getX() >> 4, device.getPos().getZ() >> 4);
				Set<ChunkPos> perDim = wanted.get(device.getDimension());
				if(perDim!=null&&perDim.contains(chunk))
					continue;//already counted; several devices often share a chunk
				if(used >= budget)
				{
					dropped++;
					continue;
				}
				if(perDim==null)
					wanted.put(device.getDimension(), perDim = new HashSet<>());
				perDim.add(chunk);
				used++;
			}

		if(dropped!=lastDroppedCount)
		{
			if(dropped > 0)
				IELogger.warn("Virtual grid chunk-load budget of "+budget+" reached; "+dropped
						+" device chunk(s) are not being kept loaded. Raise gridChunkloadBudget "
						+"or switch chunk loading off on some devices.");
			lastDroppedCount = dropped;
		}

		//Release dimensions that no longer want anything.
		for(Integer dim : new ArrayList<>(tickets.keySet()))
			if(!wanted.containsKey(dim))
				release(dim);

		for(Map.Entry<Integer, Set<ChunkPos>> entry : wanted.entrySet())
			apply(entry.getKey(), entry.getValue());
	}

	private static void apply(int dimension, Set<ChunkPos> chunks)
	{
		Set<ChunkPos> current = forced.get(dimension);
		if(chunks.equals(current))
			return;

		World world = DimensionManager.getWorld(dimension);
		if(world==null)
			return;//dimension not loaded; nothing to force yet

		Ticket ticket = tickets.get(dimension);
		if(ticket==null)
		{
			ticket = ForgeChunkManager.requestTicket(ImmersiveEngineering.instance, world,
					ForgeChunkManager.Type.NORMAL);
			if(ticket==null)
			{
				IELogger.warn("Virtual grid could not obtain a chunk-loading ticket for dimension "
						+dimension+"; Forge's per-mod ticket allowance may be exhausted.");
				return;
			}
			tickets.put(dimension, ticket);
			current = null;
		}

		if(current!=null)
			for(ChunkPos chunk : current)
				if(!chunks.contains(chunk))
					ForgeChunkManager.unforceChunk(ticket, chunk);
		for(ChunkPos chunk : chunks)
			if(current==null||!current.contains(chunk))
				ForgeChunkManager.forceChunk(ticket, chunk);

		forced.put(dimension, new HashSet<>(chunks));
	}

	private static void release(int dimension)
	{
		Ticket ticket = tickets.remove(dimension);
		if(ticket!=null)
			ForgeChunkManager.releaseTicket(ticket);
		forced.remove(dimension);
	}

	/**
	 * Drops every ticket. Called on server stop so a second world starts clean.
	 */
	public static void releaseAll()
	{
		for(Integer dim : new ArrayList<>(tickets.keySet()))
			release(dim);
		lastDroppedCount = 0;
	}

	/**
	 * @return how many chunks the grid currently holds loaded, for the console readout
	 */
	public static int getForcedChunkCount()
	{
		int total = 0;
		for(Set<ChunkPos> chunks : forced.values())
			total += chunks.size();
		return total;
	}

	/**
	 * @return how many device chunks were refused by the budget on the last refresh
	 */
	public static int getDroppedCount()
	{
		return lastDroppedCount;
	}

	/**
	 * Counts what the grid <em>wants</em> to keep loaded, ignoring the budget and whether
	 * the dimensions are loaded. Pure bookkeeping over the device table, so the console can
	 * show "requested vs. allowed" honestly.
	 */
	public static int countRequestedChunks(VirtualGrid grid)
	{
		Map<Integer, Set<ChunkPos>> perDim = new HashMap<>();
		for(GridDevice device : grid.getDevices())
		{
			if(!device.isChunkLoadRequested()||!device.isEnabled())
				continue;
			Set<ChunkPos> chunks = perDim.get(device.getDimension());
			if(chunks==null)
				perDim.put(device.getDimension(), chunks = new HashSet<>());
			chunks.add(new ChunkPos(device.getPos().getX() >> 4, device.getPos().getZ() >> 4));
		}
		int total = 0;
		for(Set<ChunkPos> chunks : perDim.values())
			total += chunks.size();
		return total;
	}
}
