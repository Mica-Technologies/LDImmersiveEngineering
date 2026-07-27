/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The per-tick energy pass, expressed purely in terms of {@link VirtualGrid},
 * {@link GridSegment} and {@link IGridEndpoint}.
 * <p>
 * Nothing here touches {@code World} or {@code TileEntity} -- that is what makes every
 * rule below (caps, buffers, loss, priorities, load shedding, failover walks, city-mode
 * presence) directly unit-testable. {@code GridTickHandler} is the thin Forge-facing
 * wrapper that calls {@link #tick}.
 * <p>
 * Cost is O(active devices) with small constants and no steady-state allocation: the
 * only object created per tick is the failover visited-set, and only on the ticks where
 * a shortfall actually occurs.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public final class GridEngine
{
	private GridEngine()
	{
	}

	/**
	 * Runs one server tick of the whole grid.
	 *
	 * @param grid     the registry to tick
	 * @param tick     a monotonically increasing tick counter, used for city-mode stagger
	 * @param cityMode whether city-mode presence semantics apply (see {@code CityMode.grid()})
	 */
	public static void tick(VirtualGrid grid, long tick, boolean cityMode)
	{
		if(!GridConfig.enabled)
			return;
		for(GridSegment segment : grid.getSegments())
			segment.beginTick();

		//Kill switches are read before anything moves, so a segment that is being held down
		//never collects a tick of energy it is not allowed to deliver.
		readSignalInputs(grid);

		if(cityMode)
			tickCityMode(grid, tick);
		else
			tickNormal(grid);

		for(GridSegment segment : grid.getSegments())
		{
			segment.updateBreaker();
			segment.endTick();
		}

		//Last, so the lamps report the tick that just happened rather than the one before.
		writeSignalOutputs(grid, cityMode);
	}

	/**
	 * Re-evaluates every segment's time-of-day window.
	 * <p>
	 * Deliberately separate from {@link #tick}: the schedule is a gate on whether a segment
	 * runs, not part of the energy pass, and keeping it apart means the caller owns the
	 * question of which world's clock is authoritative for a cross-dimension grid.
	 *
	 * @param dayTime the world time of day driving every schedule
	 */
	public static void applySchedules(VirtualGrid grid, long dayTime)
	{
		for(GridSegment segment : grid.getSegments())
			segment.updateSchedule(dayTime);
	}

	//	=================================
	//		SIGNAL UNITS
	//	=================================

	/**
	 * Phase 0 -- let Signal Units in input mode hold their segment down.
	 * <p>
	 * Any one unit calling for a stop is enough; this is a chain of emergency stops in
	 * series, not a vote.
	 */
	private static void readSignalInputs(VirtualGrid grid)
	{
		for(GridSegment segment : grid.getSegments())
		{
			List<GridDevice> signals = segment.getActiveSignals();
			boolean kill = false;
			for(int i = 0; i < signals.size()&&!kill; i++)
			{
				GridDevice signal = signals.get(i);
				if(signal.isSignalOutput())
					continue;
				IGridEndpoint endpoint = signal.getEndpoint();
				if(endpoint!=null&&signal.isKilling(endpoint.isRedstoneHigh()))
					kill = true;
			}
			segment.setForcedOff(kill);
		}
	}

	/**
	 * Final phase -- publish each segment's state to its Signal Units in output mode.
	 * <p>
	 * An inverted output emits while the segment is <em>down</em>, which is what makes it an
	 * alarm rather than a running light. The endpoints are change-gated, so a steady grid
	 * sends no block updates at all.
	 */
	private static void writeSignalOutputs(VirtualGrid grid, boolean cityMode)
	{
		for(GridSegment segment : grid.getSegments())
		{
			List<GridDevice> signals = segment.getActiveSignals();
			if(signals.isEmpty())
				continue;
			boolean up = segment.isUp(cityMode);
			for(int i = 0; i < signals.size(); i++)
			{
				GridDevice signal = signals.get(i);
				if(!signal.isSignalOutput())
					continue;
				IGridEndpoint endpoint = signal.getEndpoint();
				if(endpoint!=null)
					endpoint.setRedstoneOutput(up!=signal.isSignalInverted()?15: 0);
			}
		}
	}

	//	=================================
	//		NORMAL MODE -- real accounting
	//	=================================

	private static void tickNormal(VirtualGrid grid)
	{
		//The three phases run as three passes over all segments rather than being nested
		//inside one per-segment loop. That matters for failover: a backup must have already
		//collected before anything draws on it, and with a single loop whether it had would
		//depend on the order segments happened to be created in.
		for(GridSegment segment : grid.getSegments())
		{
			List<GridDevice> feeds = segment.getActiveFeeds();
			for(int i = 0; i < feeds.size(); i++)
				feeds.get(i).setLastThroughput(0);
			List<GridDevice> services = segment.getActiveServices();
			for(int i = 0; i < services.size(); i++)
				services.get(i).setLastThroughput(0);
			if(segment.isOperational())
				collect(segment, feeds);
		}

		for(GridSegment segment : grid.getSegments())
			if(segment.isOperational())
				serve(segment, segment.getActiveServices());

		for(GridSegment segment : grid.getSegments())
			if(!segment.getFailover().isEmpty())
				runFailover(grid, segment, segment.getActiveServices(), segment.isOperational());
	}

	/**
	 * Phase A -- drain feed units into the segment buffer, highest priority first.
	 */
	private static void collect(GridSegment segment, List<GridDevice> feeds)
	{
		GridPolicy policy = segment.getPolicy();
		double keep = 1.0-policy.getLossPct();
		//Total loss means nothing could ever arrive; draining sources into nothing would
		//be a bug, not a feature.
		if(keep <= 0)
			return;

		for(int i = 0; i < feeds.size(); i++)
		{
			int room = policy.getBufferCap()-segment.getBuffer();
			if(room <= 0)
				break;
			int inputBudget = segment.getInputBudget();
			if(inputBudget <= 0)
				break;

			GridDevice feed = feeds.get(i);
			//How much gross intake it takes to fill the remaining room, given loss.
			int grossForRoom = keep >= 1.0?room
					: (int)Math.min(Integer.MAX_VALUE, Math.ceil(room/keep));
			int budget = Math.min(Math.min(inputBudget, feed.getTransferCap()), grossForRoom);
			if(budget <= 0)
				continue;

			IGridEndpoint endpoint = feed.getEndpoint();
			if(endpoint==null)
				continue;
			int pulled = clampResult(endpoint.extractForGrid(budget, false), budget);
			if(pulled <= 0)
				continue;

			segment.addToBuffer((int)Math.floor(pulled*keep));
			segment.recordIn(pulled);
			feed.recordThroughput(pulled);
		}
	}

	/**
	 * Phase B -- deliver from the segment buffer, critical loads first.
	 */
	private static void serve(GridSegment segment, List<GridDevice> services)
	{
		for(int i = 0; i < services.size(); i++)
		{
			GridDevice service = services.get(i);
			IGridEndpoint endpoint = service.getEndpoint();
			if(endpoint==null)
				continue;
			int budget = Math.min(Math.min(segment.getBuffer(), segment.getOutputBudget()),
					service.getTransferCap());
			if(budget <= 0)
				continue;
			int accepted = clampResult(endpoint.insertFromGrid(budget, false), budget);
			if(accepted <= 0)
				continue;
			segment.drawFromBuffer(accepted);
			segment.recordOut(accepted);
			service.recordThroughput(accepted);
		}
	}

	/**
	 * Phase C -- ask linked backup segments to cover what this segment could not.
	 * <p>
	 * Backups always engage when the segment is switched off or tripped. They additionally
	 * cover ordinary shortfalls only when the segment's {@code failoverTopUp} is set.
	 */
	private static void runFailover(VirtualGrid grid, GridSegment segment,
									List<GridDevice> services, boolean operational)
	{
		if(operational&&!segment.getPolicy().isFailoverTopUp())
			return;
		if(services.isEmpty())
			return;

		for(int i = 0; i < services.size(); i++)
		{
			GridDevice service = services.get(i);
			IGridEndpoint endpoint = service.getEndpoint();
			if(endpoint==null)
				continue;
			int want = service.getTransferCap()-service.getLastThroughput();
			if(want <= 0)
				continue;
			//What the world would still take. If it takes nothing, the load is satisfied
			//and there is no shortfall to cover.
			int demand = clampResult(endpoint.insertFromGrid(want, true), want);
			if(demand <= 0)
				continue;

			Set<UUID> visited = new HashSet<>();
			visited.add(segment.getId());
			int available = walkFailover(grid, segment, demand, visited, 0, false);
			if(available <= 0)
				continue;

			int accepted = clampResult(endpoint.insertFromGrid(available, false), available);
			if(accepted <= 0)
				continue;
			//Debit exactly what was delivered, walking the same order so the same backups
			//are charged as were surveyed.
			visited.clear();
			visited.add(segment.getId());
			walkFailover(grid, segment, accepted, visited, 0, true);
			service.recordThroughput(accepted);
		}
	}

	/**
	 * Depth-first walk of the failover chain in declared order.
	 * <p>
	 * {@code visited} makes cycles harmless: a segment can be reached at most once per
	 * walk, so A→B→A terminates. {@link GridConfig#maxFailoverDepth} bounds how far a
	 * shortfall may propagate even in an acyclic chain.
	 *
	 * @param commit false to survey availability without moving anything
	 * @return how much the chain supplied (or could supply)
	 */
	private static int walkFailover(VirtualGrid grid, GridSegment origin, int amount,
									Set<UUID> visited, int depth, boolean commit)
	{
		if(amount <= 0||depth >= GridConfig.maxFailoverDepth)
			return 0;
		int supplied = 0;
		List<UUID> links = origin.getFailover();
		for(int i = 0; i < links.size(); i++)
		{
			if(amount <= 0)
				break;
			UUID backupId = links.get(i);
			if(!visited.add(backupId))
				continue;
			GridSegment backup = grid.getSegment(backupId);
			if(backup==null||!backup.isOperational())
				continue;

			int give = Math.min(amount, Math.min(backup.getBuffer(), backup.getOutputBudget()));
			if(give > 0)
			{
				if(commit)
				{
					backup.drawFromBuffer(give);
					backup.recordOut(give);
				}
				supplied += give;
				amount -= give;
			}
			if(amount > 0)
			{
				int deeper = walkFailover(grid, backup, amount, visited, depth+1, commit);
				supplied += deeper;
				amount -= deeper;
			}
		}
		return supplied;
	}

	//	=================================
	//		CITY MODE -- presence semantics
	//	=================================

	/**
	 * City mode mirrors what {@code CityMode.wires()} does to the wire network: stop
	 * simulating the physics and ask only "is there power?".
	 * <p>
	 * A segment is energized when it is switched on and at least one of its feed units has
	 * recently proved its source is live. Proving it costs {@link GridConfig#sipAmount}
	 * every {@link GridConfig#sipIntervalTicks} -- one flux per five seconds by default.
	 * Service units on an energized segment then deliver freely.
	 */
	private static void tickCityMode(VirtualGrid grid, long tick)
	{
		int interval = Math.max(1, GridConfig.sipIntervalTicks);

		for(GridSegment segment : grid.getSegments())
		{
			List<GridDevice> feeds = segment.getActiveFeeds();
			boolean live = false;
			for(int i = 0; i < feeds.size(); i++)
			{
				GridDevice feed = feeds.get(i);
				//Staggered by position so a city's worth of feed units never sip on the
				//same tick.
				if((tick+stagger(feed))%interval==0)
				{
					IGridEndpoint endpoint = feed.getEndpoint();
					if(endpoint!=null&&endpoint.extractForGrid(GridConfig.sipAmount, false) > 0)
						feed.setLastLiveTick(tick);
				}
				if(feed.isLive(tick))
					live = true;
			}
			segment.setSourceLive(live);
		}

		//Resolve effective energization, so a switched-off segment backed by a live one
		//still delivers. Boolean cascade instead of energy maths -- cheaper than normal
		//mode, which is the whole point.
		for(GridSegment segment : grid.getSegments())
			segment.setEnergized(resolveEnergized(grid, segment));

		for(GridSegment segment : grid.getSegments())
		{
			List<GridDevice> services = segment.getActiveServices();
			if(services.isEmpty())
				continue;
			if(!segment.isEnergized())
			{
				for(int i = 0; i < services.size(); i++)
					services.get(i).setLastThroughput(0);
				continue;
			}
			for(int i = 0; i < services.size(); i++)
			{
				GridDevice service = services.get(i);
				IGridEndpoint endpoint = service.getEndpoint();
				if(endpoint==null)
					continue;
				//The segment's own output cap is still honoured: it is a setting the player
				//chose, not a physics term. Only the pool accounting goes away.
				int budget = Math.min(service.getTransferCap(), segment.getOutputBudget());
				if(budget <= 0)
				{
					service.setLastThroughput(0);
					continue;
				}
				int given = clampResult(endpoint.insertFromGrid(budget, false), budget);
				service.recordThroughput(given);
				segment.recordOut(given);
			}
		}
	}

	/**
	 * @return whether {@code segment} has power available, following failover links
	 */
	private static boolean resolveEnergized(VirtualGrid grid, GridSegment segment)
	{
		if(segment.isOperational()&&segment.isSourceLive())
			return true;
		if(segment.getFailover().isEmpty())
			return false;
		Set<UUID> visited = new HashSet<>();
		visited.add(segment.getId());
		return walkEnergized(grid, segment, visited, 0);
	}

	private static boolean walkEnergized(VirtualGrid grid, GridSegment origin,
										 Set<UUID> visited, int depth)
	{
		if(depth >= GridConfig.maxFailoverDepth)
			return false;
		List<UUID> links = origin.getFailover();
		for(int i = 0; i < links.size(); i++)
		{
			UUID backupId = links.get(i);
			if(!visited.add(backupId))
				continue;
			GridSegment backup = grid.getSegment(backupId);
			if(backup==null||!backup.isOperational())
				continue;
			if(backup.isSourceLive())
				return true;
			if(walkEnergized(grid, backup, visited, depth+1))
				return true;
		}
		return false;
	}

	//	=================================
	//		INTROSPECTION
	//	=================================

	/**
	 * The backup segments that would be consulted for {@code segment}, in the order the
	 * engine would ask them.
	 * <p>
	 * Shares the cycle guard and depth bound with {@link #walkFailover}, so the console's
	 * "who would cover this" preview cannot drift from what actually happens at runtime.
	 * Read-only: nothing here mutates a buffer or a budget.
	 */
	public static List<GridSegment> failoverChain(VirtualGrid grid, GridSegment segment)
	{
		List<GridSegment> chain = new java.util.ArrayList<>();
		if(segment==null)
			return chain;
		Set<UUID> visited = new HashSet<>();
		visited.add(segment.getId());
		collectChain(grid, segment, visited, 0, chain);
		return chain;
	}

	private static void collectChain(VirtualGrid grid, GridSegment origin, Set<UUID> visited,
									 int depth, List<GridSegment> out)
	{
		if(depth >= GridConfig.maxFailoverDepth)
			return;
		for(UUID backupId : origin.getFailover())
		{
			if(!visited.add(backupId))
				continue;
			GridSegment backup = grid.getSegment(backupId);
			if(backup==null)
				continue;
			out.add(backup);
			collectChain(grid, backup, visited, depth+1, out);
		}
	}

	/**
	 * @return the first segment in the chain that could actually supply right now, or null
	 */
	public static GridSegment firstAvailableBackup(VirtualGrid grid, GridSegment segment, boolean cityMode)
	{
		for(GridSegment backup : failoverChain(grid, segment))
			if(backup.isOperational()&&(cityMode?backup.isSourceLive(): backup.getBuffer() > 0))
				return backup;
		return null;
	}

	/**
	 * Spreads periodic work across ticks by position, the same trick the throttled
	 * multiblocks use.
	 */
	private static int stagger(GridDevice device)
	{
		int hash = device.getPos().getX()^device.getPos().getZ()^(device.getDimension()*31);
		return Math.floorMod(hash, Math.max(1, GridConfig.sipIntervalTicks));
	}

	/**
	 * Endpoints are third-party code (a tile entity talking to arbitrary neighbouring
	 * mods). Clamp whatever they report into the range the engine offered, so a
	 * misbehaving one cannot mint or destroy energy in the ledger.
	 */
	private static int clampResult(int reported, int offered)
	{
		if(reported <= 0)
			return 0;
		return Math.min(reported, offered);
	}
}
