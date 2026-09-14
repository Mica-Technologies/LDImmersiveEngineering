/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.util.ForcedChunkTickets;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.util.math.ChunkPos;

import java.util.*;

/**
 * Keeps the chunks of chunk-load-flagged grid devices loaded.
 * <p>
 * Each flagged device keeps <em>its own</em> chunk loaded -- one chunk, not an area around it.
 * Everything is rebuilt from {@link VirtualGrid} rather than tracked incrementally, so the forced
 * set cannot drift out of step with the device records -- the rebuild is O(devices) and only runs
 * when something actually changes. Which Forge ticket holds which chunk is
 * {@link ForcedChunkTickets}' business.
 * <p>
 * The budget is a hard server-wide cap on how many chunks the grid may pin. Anything past it is
 * dropped and logged rather than silently ignored, because a grid quietly not loading the chunks
 * you asked it to is worse than being told it ran out.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridChunkLoader
{
	private static final ForcedChunkTickets tickets = new ForcedChunkTickets("Virtual grid");
	/**
	 * What is currently forced, so a rebuild that changes nothing costs nothing.
	 */
	private static final Map<Integer, Set<ChunkPos>> forced = new HashMap<>();
	private static int lastDroppedCount;
	/**
	 * True between the grid's save data loading and the server stopping. A dimension that loads
	 * outside that window must not trigger a rebuild from whatever the grid happens to hold -- at
	 * startup that is nothing yet, and in a second singleplayer world it could be the last one's.
	 */
	private static boolean active;

	/**
	 * Registers the mod-wide ticket callback. The fluid network shares it -- see
	 * {@link ForcedChunkTickets#registerCallback()}.
	 */
	public static void init()
	{
		ForcedChunkTickets.registerCallback();
	}

	/**
	 * Recomputes the forced-chunk set. Safe to call often; does nothing when the result
	 * matches what is already forced.
	 */
	public static void refresh()
	{
		active = true;
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
		//Both sets: a dimension whose last apply fell short holds tickets without a record.
		Set<Integer> held = new HashSet<>(forced.keySet());
		held.addAll(tickets.getDimensions());
		for(Integer dim : held)
			if(!wanted.containsKey(dim))
			{
				tickets.release(dim);
				forced.remove(dim);
			}

		for(Map.Entry<Integer, Set<ChunkPos>> entry : wanted.entrySet())
			apply(entry.getKey(), entry.getValue());
	}

	private static void apply(int dimension, Set<ChunkPos> chunks)
	{
		if(chunks.equals(forced.get(dimension)))
			return;
		//Recorded only if it took. A dimension that is not loaded yet, or a ticket Forge refused,
		//leaves the record short, so the next refresh tries again rather than believing it is done.
		if(tickets.apply(dimension, chunks)==chunks.size())
			forced.put(dimension, new HashSet<>(chunks));
		else
			forced.remove(dimension);
	}

	/**
	 * A dimension finished loading. Devices there could not be chunk-loaded before now -- at server
	 * start only the dimensions Minecraft loads eagerly exist when the grid first refreshes -- so
	 * this is where a device in the Nether starts holding its chunk after a restart.
	 */
	public static void onWorldLoad(int dimension)
	{
		if(active)
			refresh();
	}

	/**
	 * A dimension unloaded. Its tickets died with it, so they are forgotten rather than released.
	 */
	public static void onWorldUnload(int dimension)
	{
		tickets.forget(dimension);
		forced.remove(dimension);
	}

	/**
	 * Drops every ticket. Called on server stop so a second world starts clean.
	 * <p>
	 * Must not throw. It runs inside {@code FMLServerStoppedEvent}, and anything escaping from
	 * there takes the Server thread down mid-shutdown and hangs the client.
	 */
	public static void releaseAll()
	{
		active = false;
		tickets.releaseAll();
		forced.clear();
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
