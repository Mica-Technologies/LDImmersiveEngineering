/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraft.world.World;

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
 * One ticket per dimension rather than one per fitting: Forge caps tickets per mod, and a
 * city-scale network would exhaust that allowance immediately. Everything is rebuilt from
 * {@link VirtualFluidNet} rather than tracked incrementally, so the forced set cannot drift out of
 * step with the records -- the rebuild is O(devices) and only runs when something actually changes.
 * <p>
 * The budget is a hard server-wide cap on how many chunks the network may pin. Anything past it is
 * dropped and logged rather than silently ignored, because a network quietly not loading the chunks
 * you asked it to is worse than being told it ran out.
 * <p>
 * The deliberate mirror of {@code GridChunkLoader} -- including its shutdown discipline, which was
 * bought the hard way: see {@link #release(int)}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidNetChunkLoader
{
	private static final Map<Integer, Ticket> tickets = new HashMap<>();
	/**
	 * What is currently forced, so a rebuild that changes nothing costs nothing.
	 */
	private static final Map<Integer, Set<ChunkPos>> forced = new HashMap<>();
	private static int lastDroppedCount;

	//	=================================
	//	No init() here, deliberately.
	//	=================================
	//
	// ForgeChunkManager.setForcedChunkLoadingCallback stores ONE callback per mod container, so a
	// second call silently replaces the first. GridChunkLoader.init() already registers the
	// mod-wide callback, and it does the only thing either loader wants -- returns nothing, telling
	// Forge to drop every ticket it persisted from the last session, because both networks rebuild
	// their forced sets from their own save data.
	//
	// Registering a second identical callback would work by luck rather than by design, and would
	// break the moment either one needed to do something different.

	/**
	 * Recomputes the forced-chunk set. Safe to call often; does nothing when the result matches what
	 * is already forced.
	 */
	public static void refresh()
	{
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
				IELogger.warn("Virtual fluid network could not obtain a chunk-loading ticket for "
						+"dimension "+dimension+"; Forge's per-mod ticket allowance may be exhausted.");
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
		forced.remove(dimension);
		if(ticket==null)
			return;
		try
		{
			ForgeChunkManager.releaseTicket(ticket);
		} catch(RuntimeException e)
		{
			//ForgeChunkManager.releaseTicket dereferences the ticket's world with no null check, and
			//once a world is unloaded its row is gone from that map. At server stop the worlds are
			//already gone, and an exception escaping the FMLServerStoppedEvent handler kills the
			//Server thread mid-shutdown -- which does not present as a crash, it presents as the
			//client hanging forever on world exit. The grid's loader shipped exactly that.
			//
			//Dropping the ticket is all that matters: Forge discards every ticket it holds when the
			//server stops.
			IELogger.warn("Virtual fluid network could not hand back its chunk ticket for dimension "
					+dimension+" (the dimension is already gone). Harmless at shutdown: "+e);
		}
	}

	/**
	 * Drops every ticket. Called on server stop so a second world starts clean.
	 * <p>
	 * Must not throw -- see {@link #release(int)}.
	 */
	public static void releaseAll()
	{
		for(Integer dim : new ArrayList<>(tickets.keySet()))
			release(dim);
		tickets.clear();
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
