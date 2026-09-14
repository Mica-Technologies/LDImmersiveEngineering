/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.*;

/**
 * The Forge side of keeping a set of chunks loaded: which tickets hold which chunks, per dimension.
 * <p>
 * Both virtual networks pin chunks -- the power grid for its devices, the fluid network for its
 * fittings -- and both used to hold a single ticket per dimension. That was wrong in a way nothing
 * reported. <strong>Forge caps the chunks one ticket may hold</strong> ({@code
 * maximumChunksPerTicket}, 25 by default), and {@link ForgeChunkManager#forceChunk} does not refuse
 * the chunk past the cap: it quietly unforces the <em>oldest</em> one to make room. So a budget of
 * 25 happened to work, and raising it did nothing but shuffle which chunk was left out. Chunks are
 * spread across as many tickets as the cap requires instead.
 * <p>
 * The caller decides <em>what</em> should be loaded; this only makes Forge agree, and moves as
 * little as possible when the set changes, so a chunk that stays wanted is never unforced in
 * passing.
 *
 * @author LDImmersiveEngineering -- virtual networks
 */
public class ForcedChunkTickets
{
	private final String owner;
	private final Map<Integer, List<Ticket>> tickets = new HashMap<>();

	/**
	 * @param owner names the network in log lines
	 */
	public ForcedChunkTickets(String owner)
	{
		this.owner = owner;
	}

	/**
	 * Registers the mod-wide callback for tickets Forge persisted from the last session.
	 * <p>
	 * Forge stores ONE callback per mod, so both networks share this one. It releases every ticket
	 * it is handed, because both networks rebuild their forced sets from their own save data.
	 * <p>
	 * <strong>Releasing them is the callback's job, not an empty body's.</strong> The old callback
	 * did nothing, on the belief that returning nothing told Forge to drop the tickets. That is
	 * only true of {@code OrderedLoadingCallback}'s filtering method. For a plain callback Forge has
	 * already registered the tickets before calling it, and keeps them unless the mod releases them
	 * -- so every restart added one more dead ticket per network per dimension, each counting toward
	 * Forge's per-mod ticket limit, until requesting a live one failed and chunk loading stopped.
	 */
	public static void registerCallback()
	{
		ForgeChunkManager.setForcedChunkLoadingCallback(ImmersiveEngineering.instance,
				(loaded, world) -> {
					for(Ticket ticket : loaded)
						releaseQuietly(ticket, "Immersive Engineering", world.provider.getDimension());
					if(!loaded.isEmpty())
						IELogger.info("Released "+loaded.size()+" chunk-loading ticket(s) left over from the "
								+"last session in dimension "+world.provider.getDimension()
								+"; the virtual networks rebuild their own.");
				});
	}

	/**
	 * Makes the tickets for {@code dimension} hold exactly {@code wanted}.
	 *
	 * @return how many of the wanted chunks are actually forced -- fewer than asked only when Forge
	 * refused a ticket, or when the dimension is not loaded (then zero, and nothing is recorded, so
	 * the next call once it loads starts from scratch)
	 */
	public int apply(int dimension, Set<ChunkPos> wanted)
	{
		if(wanted.isEmpty())
		{
			release(dimension);
			return 0;
		}
		World world = DimensionManager.getWorld(dimension);
		if(world==null)
			return 0;

		List<Ticket> held = tickets.computeIfAbsent(dimension, d -> new ArrayList<>());
		List<Set<ChunkPos>> before = new ArrayList<>();
		for(Ticket ticket : held)
			before.add(new HashSet<>(ticket.getChunkList()));
		int depth = ForgeChunkManager.getMaxChunkDepthFor(ImmersiveEngineering.MODID);
		List<Set<ChunkPos>> after = assign(before, wanted, depth);

		//Unforce first, so a chunk that moves between tickets is never briefly over a ticket's cap.
		for(int i = 0; i < before.size(); i++)
			for(ChunkPos chunk : before.get(i))
				if(!after.get(i).contains(chunk))
					ForgeChunkManager.unforceChunk(held.get(i), chunk);

		int forced = 0;
		for(int i = 0; i < after.size(); i++)
		{
			Set<ChunkPos> chunks = after.get(i);
			if(chunks.isEmpty())
				continue;
			if(i >= held.size())
			{
				Ticket ticket = ForgeChunkManager.requestTicket(ImmersiveEngineering.instance, world,
						ForgeChunkManager.Type.NORMAL);
				if(ticket==null)
				{
					int missing = 0;
					for(int j = i; j < after.size(); j++)
						missing += after.get(j).size();
					IELogger.warn(owner+" could not obtain a chunk-loading ticket for dimension "+dimension
							+"; "+missing+" chunk(s) are not being kept loaded. Forge's per-mod ticket "
							+"allowance (maximumTicketCount in forgeChunkLoading.cfg) may be exhausted.");
					break;
				}
				held.add(ticket);
			}
			Ticket ticket = held.get(i);
			for(ChunkPos chunk : chunks)
				if(i >= before.size()||!before.get(i).contains(chunk))
					ForgeChunkManager.forceChunk(ticket, chunk);
			forced += chunks.size();
		}

		//Hand back tickets left holding nothing.
		for(int i = held.size()-1; i >= 0; i--)
			if(held.get(i).getChunkList().isEmpty())
				releaseQuietly(held.remove(i), owner, dimension);
		if(held.isEmpty())
			tickets.remove(dimension);
		return forced;
	}

	/**
	 * Decides which ticket holds which chunk. Pure, so it can be tested without Forge.
	 * <p>
	 * A chunk that is still wanted stays in the ticket that already holds it; chunks no longer
	 * wanted are dropped; new chunks fill the first ticket with room, then new tickets. Tickets are
	 * never renumbered, so the result lines up index-for-index with {@code current} and may contain
	 * empty sets -- the caller releases those.
	 *
	 * @param current what each existing ticket holds, in order
	 * @param depth   the most chunks one ticket may hold; zero or less means unlimited, as in Forge
	 * @return what each ticket should hold: {@code current.size()} entries, plus any new tickets
	 */
	public static List<Set<ChunkPos>> assign(List<Set<ChunkPos>> current, Set<ChunkPos> wanted, int depth)
	{
		int cap = depth <= 0?Integer.MAX_VALUE: depth;
		List<Set<ChunkPos>> result = new ArrayList<>();
		Set<ChunkPos> placed = new HashSet<>();
		for(Set<ChunkPos> held : current)
		{
			Set<ChunkPos> kept = new LinkedHashSet<>();
			for(ChunkPos chunk : held)
				//A ticket over the cap -- possible if the cap was lowered between sessions -- sheds
				//the excess here, and the shed chunks are placed again below like any new chunk.
				if(wanted.contains(chunk)&&kept.size() < cap&&placed.add(chunk))
					kept.add(chunk);
			result.add(kept);
		}

		//Sorted, so the same wanted set always lands the same way whatever order it arrived in.
		List<ChunkPos> fresh = new ArrayList<>();
		for(ChunkPos chunk : wanted)
			if(!placed.contains(chunk))
				fresh.add(chunk);
		fresh.sort(Comparator.<ChunkPos>comparingInt(c -> c.x).thenComparingInt(c -> c.z));

		int slot = 0;
		for(ChunkPos chunk : fresh)
		{
			while(slot < result.size()&&result.get(slot).size() >= cap)
				slot++;
			if(slot==result.size())
				result.add(new LinkedHashSet<>());
			result.get(slot).add(chunk);
		}
		return result;
	}

	/**
	 * Releases every ticket held for one dimension.
	 */
	public void release(int dimension)
	{
		List<Ticket> held = tickets.remove(dimension);
		if(held!=null)
			for(Ticket ticket : held)
				releaseQuietly(ticket, owner, dimension);
	}

	/**
	 * Forgets a dimension's tickets without handing them back, for when its world has unloaded.
	 * <p>
	 * Forge has already dropped that world's forced-chunk map, and a later force through one of the
	 * old tickets would dereference it. The dimension cannot normally unload while anything is
	 * forced in it, so this is a guard rather than a path expected to run.
	 */
	public void forget(int dimension)
	{
		tickets.remove(dimension);
	}

	public void releaseAll()
	{
		for(Integer dim : new ArrayList<>(tickets.keySet()))
			release(dim);
		tickets.clear();
	}

	/**
	 * @return the dimensions currently holding tickets
	 */
	public Set<Integer> getDimensions()
	{
		return Collections.unmodifiableSet(tickets.keySet());
	}

	private static void releaseQuietly(Ticket ticket, String owner, int dimension)
	{
		try
		{
			ForgeChunkManager.releaseTicket(ticket);
		} catch(RuntimeException e)
		{
			//	=================================
			//	Why this is caught rather than prevented
			//	=================================
			//
			// Forge's releaseTicket does `tickets.get(ticket.world).containsEntry(...)` with
			// no null check. Once a world has been unloaded its row is gone from that map, so
			// releasing a ticket against it throws NPE -- and there is no public way to ask whether a
			// ticket is still releasable.
			//
			// That mattered a great deal. releaseAll() runs from the FMLServerStoppedEvent handler, by
			// which point the worlds are already gone; the NPE propagated out of the mod's event
			// handler as a LoaderExceptionModCrash, killed the Server thread partway through shutdown,
			// and the integrated server never signalled that it had stopped. The symptom was the
			// client hanging forever on world exit.
			//
			// Dropping the ticket is all that matters here. Forge discards every ticket it holds when
			// the server stops, and the ones it saved are released by the callback next session.
			IELogger.warn(owner+" could not hand back a chunk ticket for dimension "+dimension
					+" (the dimension is already gone). Harmless at shutdown: "+e);
		}
	}
}
