/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.util.ForcedChunkTickets;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.util.math.ChunkPos;

import java.util.*;

/**
 * Keeps the chunks of chunk-load-flagged fluid network fittings loaded.
 * <p>
 * <strong>This did not exist for a while, and the toggle that drives it did.</strong> The console
 * showed a "Chunkload on/off" button, the action packet applied it, {@code FluidDevice} stored it,
 * and the config offered {@code fluidNetAllowChunkloading} and {@code fluidNetChunkloadBudget} --
 * with nothing anywhere reading {@code isChunkLoad()}. A control that does nothing is worse than no
 * control: a player who switches it on and watches their Outlet stop working when they walk away
 * has been actively misled.
 * <p>
 * Each flagged fitting keeps its own chunk loaded. Everything is rebuilt from
 * {@link VirtualFluidNet} rather than tracked incrementally, so the forced set cannot drift out of
 * step with the records -- the rebuild is O(devices) and only runs when something actually changes.
 * Which Forge ticket holds which chunk is {@link ForcedChunkTickets}' business.
 * <p>
 * The budget is a hard server-wide cap on how many chunks the network may pin. Anything past it is
 * dropped and logged rather than silently ignored, because a network quietly not loading the chunks
 * you asked it to is worse than being told it ran out.
 * <p>
 * The deliberate mirror of {@code GridChunkLoader}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidNetChunkLoader
{
	private static final ForcedChunkTickets tickets = new ForcedChunkTickets("Virtual fluid network");
	/**
	 * What is currently forced, so a rebuild that changes nothing costs nothing.
	 */
	private static final Map<Integer, Set<ChunkPos>> forced = new HashMap<>();
	private static int lastDroppedCount;
	/**
	 * See {@code GridChunkLoader.active}.
	 */
	private static boolean active;

	//	=================================
	//	No init() here, deliberately.
	//	=================================
	//
	// ForgeChunkManager.setForcedChunkLoadingCallback stores ONE callback per mod container, so a
	// second call silently replaces the first. GridChunkLoader.init() registers the mod-wide one,
	// ForcedChunkTickets.registerCallback, which releases every ticket persisted from the last
	// session for both networks.

	/**
	 * Recomputes the forced-chunk set. Safe to call often; does nothing when the result matches what
	 * is already forced.
	 */
	public static void refresh()
	{
		active = true;
		Map<Integer, Set<ChunkPos>> wanted = new HashMap<>();
		int budget = Math.max(0, FluidNetConfig.chunkloadBudget);
		int used = 0;
		int dropped = 0;

		if(FluidNetConfig.enabled&&FluidNetConfig.allowChunkloading)
			for(FluidDevice device : VirtualFluidNet.INSTANCE.getDevices())
			{
				if(!device.isChunkLoad()||!device.isEnabled())
					continue;
				ChunkPos chunk = new ChunkPos(device.getPos().getX() >> 4, device.getPos().getZ() >> 4);
				Set<ChunkPos> perDim = wanted.get(device.getDimension());
				if(perDim!=null&&perDim.contains(chunk))
					continue;//already counted; several fittings often share a chunk
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
				IELogger.warn("Virtual fluid network chunk-load budget of "+budget+" reached; "+dropped
						+" fitting chunk(s) are not being kept loaded. Raise fluidNetChunkloadBudget "
						+"or switch chunk loading off on some fittings.");
			lastDroppedCount = dropped;
		}

		//Release dimensions that no longer want anything. Both sets: a dimension whose last apply
		//fell short holds tickets without a record.
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
		//Recorded only if it took, so a dimension not loaded yet is retried by the next refresh.
		if(tickets.apply(dimension, chunks)==chunks.size())
			forced.put(dimension, new HashSet<>(chunks));
		else
			forced.remove(dimension);
	}

	/**
	 * A dimension finished loading; see {@code GridChunkLoader.onWorldLoad}.
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
	 * @return how many chunks the network currently holds loaded, for the console readout
	 */
	public static int getForcedChunkCount()
	{
		int total = 0;
		for(Set<ChunkPos> chunks : forced.values())
			total += chunks.size();
		return total;
	}
}
