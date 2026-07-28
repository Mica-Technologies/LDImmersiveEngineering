/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import blusunrize.immersiveengineering.api.ApiUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The per-tick fluid pass, expressed purely in terms of {@link VirtualFluidNet},
 * {@link FluidMain} and {@link IFluidEndpoint}.
 * <p>
 * Nothing here touches {@code World}, {@code TileEntity} or the fluid registry -- that is what
 * makes every rule below (caps, line pack, leakage, priorities, load shedding, failover walks,
 * city-mode presence, fluid typing) directly unit-testable. {@code FluidNetTickHandler} is the
 * thin Forge-facing wrapper that calls {@link #tick}.
 * <p>
 * Cost is O(active devices) with small constants and no steady-state allocation: the only object
 * created per tick is the failover visited-set, and only on the ticks where a shortfall actually
 * occurs.
 * <p>
 * <strong>The deliberate mirror of {@code GridEngine}.</strong> Phase for phase, guard for guard.
 * The differences are exactly three, and all three come from millibuckets not being fungible:
 * an untyped main takes its fluid from the first Inlet with something to offer, every transfer
 * names what is moving, and a backup main can only cover a shortfall if it carries the same
 * fluid. A fix to one engine is an obvious candidate for the other.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public final class FluidNetEngine
{
	private FluidNetEngine()
	{
	}

	/**
	 * Runs one server tick of the whole network.
	 *
	 * @param net      the registry to tick
	 * @param tick     a monotonically increasing tick counter, used for city-mode stagger
	 * @param cityMode whether city-mode presence semantics apply (see {@code CityMode.petroleum()})
	 */
	public static void tick(VirtualFluidNet net, long tick, boolean cityMode)
	{
		if(!FluidNetConfig.enabled)
			return;
		for(FluidMain main : net.getMains())
			main.beginTick();

		//Shut-offs are read before anything moves, so a main that is being held closed never
		//collects a tick of fluid it is not allowed to deliver.
		readValveInputs(net);

		if(cityMode)
			tickCityMode(net, tick);
		else
			tickNormal(net);

		for(FluidMain main : net.getMains())
		{
			main.updateTrip();
			main.endTick();
		}

		//Last, so the lamps report the tick that just happened rather than the one before.
		writeValveOutputs(net, cityMode);
	}

	/**
	 * Re-evaluates every main's time-of-day window.
	 * <p>
	 * Deliberately separate from {@link #tick}: the schedule is a gate on whether a main runs, not
	 * part of the fluid pass, and keeping it apart means the caller owns the question of which
	 * world's clock is authoritative for a cross-dimension network.
	 *
	 * @param dayTime the world time of day driving every schedule
	 */
	public static void applySchedules(VirtualFluidNet net, long dayTime)
	{
		for(FluidMain main : net.getMains())
			main.updateSchedule(dayTime);
	}

	//	=================================
	//		VALVES
	//	=================================

	/**
	 * Phase 0 -- let Valves in input mode hold their main closed.
	 * <p>
	 * Any one valve calling for a stop is enough; this is a run of shut-offs in series, not a vote.
	 */
	private static void readValveInputs(VirtualFluidNet net)
	{
		for(FluidMain main : net.getMains())
		{
			List<FluidDevice> valves = main.getActiveValves();
			boolean close = false;
			for(int i = 0; i < valves.size()&&!close; i++)
			{
				FluidDevice valve = valves.get(i);
				if(valve.isValveOutput())
					continue;
				IFluidEndpoint endpoint = valve.getEndpoint();
				if(endpoint!=null&&valve.isClosing(endpoint.isRedstoneHigh()))
					close = true;
			}
			main.setForcedClosed(close);
		}
	}

	/**
	 * Final phase -- publish each main's state to its Valves in output mode.
	 * <p>
	 * An inverted output emits while the main is <em>down</em>, which is what makes it an alarm
	 * rather than a running light. The endpoints are change-gated, so a steady network sends no
	 * block updates at all.
	 */
	private static void writeValveOutputs(VirtualFluidNet net, boolean cityMode)
	{
		for(FluidMain main : net.getMains())
		{
			List<FluidDevice> valves = main.getActiveValves();
			if(valves.isEmpty())
				continue;
			boolean up = main.isUp(cityMode);
			for(int i = 0; i < valves.size(); i++)
			{
				FluidDevice valve = valves.get(i);
				if(!valve.isValveOutput())
					continue;
				IFluidEndpoint endpoint = valve.getEndpoint();
				if(endpoint!=null)
					endpoint.setRedstoneOutput(up!=valve.isValveInverted()?15: 0);
			}
		}
	}

	//	=================================
	//		NORMAL MODE -- real accounting
	//	=================================

	private static void tickNormal(VirtualFluidNet net)
	{
		//The three phases run as three passes over all mains rather than being nested inside one
		//per-main loop. That matters for failover: a backup must have already collected before
		//anything draws on it, and with a single loop whether it had would depend on the order
		//mains happened to be created in.
		for(FluidMain main : net.getMains())
		{
			List<FluidDevice> inlets = main.getActiveInlets();
			for(int i = 0; i < inlets.size(); i++)
				inlets.get(i).setLastThroughput(0);
			List<FluidDevice> outlets = main.getActiveOutlets();
			for(int i = 0; i < outlets.size(); i++)
				outlets.get(i).setLastThroughput(0);
			if(main.isOperational())
				collect(main, inlets);
		}

		for(FluidMain main : net.getMains())
			if(main.isOperational())
				serve(main, main.getActiveOutlets());

		for(FluidMain main : net.getMains())
			if(!main.getFailover().isEmpty())
				runFailover(net, main, main.getActiveOutlets(), main.isOperational());
	}

	/**
	 * Phase A -- drain Inlets into the line pack, highest priority first.
	 */
	private static void collect(FluidMain main, List<FluidDevice> inlets)
	{
		String fluid = resolveFluid(main, inlets);
		//An untyped main with nothing on offer has nothing to do. It is not an error state: a main
		//built before its wells exist sits here quietly until something arrives.
		if(fluid==null)
			return;

		FluidPolicy policy = main.getPolicy();
		double keep = 1.0-policy.getLeakPct();
		//Total leakage means nothing could ever arrive; draining sources into nothing would be a
		//bug, not a feature.
		if(keep <= 0)
			return;

		for(int i = 0; i < inlets.size(); i++)
		{
			int room = policy.getPackCap()-main.getPack();
			if(room <= 0)
				break;
			int inputBudget = main.getInputBudget();
			if(inputBudget <= 0)
				break;

			FluidDevice inlet = inlets.get(i);
			//How much gross intake it takes to fill the remaining room, given leakage.
			int grossForRoom = keep >= 1.0?room
					: (int)Math.min(Integer.MAX_VALUE, Math.ceil(room/keep));
			int budget = Math.min(Math.min(inputBudget, inlet.getTransferCap()), grossForRoom);
			if(budget <= 0)
				continue;

			IFluidEndpoint endpoint = inlet.getEndpoint();
			if(endpoint==null)
				continue;
			int pulled = clampResult(endpoint.extractForMain(fluid, budget, false), budget);
			if(pulled <= 0)
				continue;

			main.addToPack((int)Math.floor(pulled*keep));
			main.recordIn(pulled);
			inlet.recordThroughput(pulled);
		}
	}

	/**
	 * Decides what the main carries, typing it from its Inlets if it has never carried anything.
	 * <p>
	 * Only ever consulted while the main is untyped: once it has a fluid, an Inlet holding
	 * something else simply reports nothing and is skipped, rather than re-typing a live main out
	 * from under everything connected to it.
	 *
	 * @return the fluid the main carries, or null if it has none and none was offered
	 */
	@Nullable
	private static String resolveFluid(FluidMain main, List<FluidDevice> inlets)
	{
		if(main.isTyped())
			return main.getFluid();
		for(int i = 0; i < inlets.size(); i++)
		{
			IFluidEndpoint endpoint = inlets.get(i).getEndpoint();
			if(endpoint==null)
				continue;
			String offered = endpoint.getOfferedFluid();
			if(offered!=null&&!offered.isEmpty())
			{
				main.typeFrom(offered);
				return main.getFluid();
			}
		}
		return null;
	}

	/**
	 * Phase B -- deliver from the line pack, critical loads first.
	 */
	private static void serve(FluidMain main, List<FluidDevice> outlets)
	{
		String fluid = main.getFluid();
		if(fluid==null)
			return;
		for(int i = 0; i < outlets.size(); i++)
		{
			FluidDevice outlet = outlets.get(i);
			IFluidEndpoint endpoint = outlet.getEndpoint();
			if(endpoint==null)
				continue;
			int budget = Math.min(Math.min(main.getPack(), main.getOutputBudget()),
					outlet.getTransferCap());
			if(budget <= 0)
				continue;
			int accepted = clampResult(endpoint.insertFromMain(fluid, budget, false), budget);
			if(accepted <= 0)
				continue;
			main.drawFromPack(accepted);
			main.recordOut(accepted);
			outlet.recordThroughput(accepted);
		}
	}

	/**
	 * Phase C -- ask linked backup mains to cover what this main could not.
	 * <p>
	 * Backups always engage when the main is closed or tripped. They additionally cover ordinary
	 * shortfalls only when the main's {@code failoverTopUp} is set.
	 */
	private static void runFailover(VirtualFluidNet net, FluidMain main,
									List<FluidDevice> outlets, boolean operational)
	{
		if(operational&&!main.getPolicy().isFailoverTopUp())
			return;
		if(outlets.isEmpty())
			return;

		//	=================================
		//	The fluid a failover delivers is THIS main's, never the backup's.
		//	=================================
		//
		// This was wrong once, and it was wrong in the worst way: the fluid was taken from whichever
		// backup happened to be able to supply, so a diesel main backed by a water main offered its
		// outlets water -- and a real Outlet does not check what it is handed, it simply fills its
		// neighbours. The engine is the only place that check can live.
		//
		// It took a while to find because the test suite's fake endpoint refuses a fluid it does not
		// hold, so the assertion passed while the bug sat underneath it. That is bug class 11 in the
		// plan: a test that documents behaviour instead of catching it.
		String fluid = main.getFluid();
		//An untyped main has never carried anything, so there is nothing to cover. Letting a backup
		//supply one would mean a main whose console says "untyped" quietly delivering diesel.
		if(fluid==null)
			return;

		for(int i = 0; i < outlets.size(); i++)
		{
			FluidDevice outlet = outlets.get(i);
			IFluidEndpoint endpoint = outlet.getEndpoint();
			if(endpoint==null)
				continue;
			int want = outlet.getTransferCap()-outlet.getLastThroughput();
			if(want <= 0)
				continue;

			//What the world would still take. If it takes nothing, the load is satisfied and there
			//is no shortfall to cover.
			int demand = clampResult(endpoint.insertFromMain(fluid, want, true), want);
			if(demand <= 0)
				continue;

			Set<UUID> visited = new HashSet<>();
			visited.add(main.getId());
			int available = walkFailover(net, main, fluid, demand, visited, 0, false);
			if(available <= 0)
				continue;

			int accepted = clampResult(endpoint.insertFromMain(fluid, available, false), available);
			if(accepted <= 0)
				continue;
			//Debit exactly what was delivered, walking the same order so the same backups are
			//charged as were surveyed.
			visited.clear();
			visited.add(main.getId());
			walkFailover(net, main, fluid, accepted, visited, 0, true);
			outlet.recordThroughput(accepted);
		}
	}

	/**
	 * Depth-first walk of the failover chain in declared order.
	 * <p>
	 * {@code visited} makes cycles harmless: a main can be reached at most once per walk, so
	 * A-&gt;B-&gt;A terminates. {@link FluidNetConfig#maxFailoverDepth} bounds how far a shortfall
	 * may propagate even in an acyclic chain.
	 * <p>
	 * A backup carrying a different fluid is skipped rather than drawn on. Covering a diesel
	 * shortfall out of a water main would be worse than not covering it at all.
	 *
	 * @param commit false to survey availability without moving anything
	 * @return how much the chain supplied (or could supply)
	 */
	private static int walkFailover(VirtualFluidNet net, FluidMain origin, String fluid, int amount,
									Set<UUID> visited, int depth, boolean commit)
	{
		if(amount <= 0||depth >= FluidNetConfig.maxFailoverDepth)
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
			FluidMain backup = net.getMain(backupId);
			if(backup==null||!backup.isOperational())
				continue;

			if(fluid.equals(backup.getFluid()))
			{
				int give = Math.min(amount, Math.min(backup.getPack(), backup.getOutputBudget()));
				if(give > 0)
				{
					if(commit)
					{
						backup.drawFromPack(give);
						backup.recordOut(give);
					}
					supplied += give;
					amount -= give;
				}
			}
			if(amount > 0)
			{
				int deeper = walkFailover(net, backup, fluid, amount, visited, depth+1, commit);
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
	 * City mode does to a main what it already does to a wire: stop simulating the physics and ask
	 * only "is there anything in it?".
	 * <p>
	 * A main is pressurised when it is open and at least one of its Inlets has recently proved its
	 * source is live. Proving it costs {@link FluidNetConfig#sipAmount} every
	 * {@link FluidNetConfig#sipIntervalTicks} -- one millibucket per five seconds by default.
	 * Outlets on a pressurised main then deliver freely.
	 */
	private static void tickCityMode(VirtualFluidNet net, long tick)
	{
		int interval = Math.max(1, FluidNetConfig.sipIntervalTicks);

		for(FluidMain main : net.getMains())
		{
			List<FluidDevice> inlets = main.getActiveInlets();
			boolean live = false;
			for(int i = 0; i < inlets.size(); i++)
			{
				FluidDevice inlet = inlets.get(i);
				//Staggered by position so a city's worth of Inlets never sip on the same tick.
				if((tick+stagger(inlet))%interval==0)
				{
					IFluidEndpoint endpoint = inlet.getEndpoint();
					if(endpoint!=null)
					{
						//The sip is also how an untyped main gets typed in city mode: there is no
						//collect phase to do it, so it has to happen here or a city-mode network
						//would never decide what it carries.
						String fluid = main.isTyped()?main.getFluid(): endpoint.getOfferedFluid();
						if(fluid!=null&&endpoint.extractForMain(fluid, FluidNetConfig.sipAmount, false) > 0)
						{
							main.typeFrom(fluid);
							inlet.setLastLiveTick(tick);
						}
					}
				}
				if(inlet.isLive(tick))
					live = true;
			}
			main.setSourceLive(live);
		}

		//Resolve effective pressurisation, so a closed main backed by a live one still delivers.
		//Boolean cascade instead of volume maths -- cheaper than normal mode, which is the point.
		for(FluidMain main : net.getMains())
			main.setPressurised(resolvePressurised(net, main));

		for(FluidMain main : net.getMains())
		{
			List<FluidDevice> outlets = main.getActiveOutlets();
			if(outlets.isEmpty())
				continue;
			String fluid = main.getFluid();
			if(!main.isPressurised()||fluid==null)
			{
				for(int i = 0; i < outlets.size(); i++)
					outlets.get(i).setLastThroughput(0);
				continue;
			}
			for(int i = 0; i < outlets.size(); i++)
			{
				FluidDevice outlet = outlets.get(i);
				IFluidEndpoint endpoint = outlet.getEndpoint();
				if(endpoint==null)
					continue;
				//The main's own output cap is still honoured: it is a setting the player chose, not
				//a physics term. Only the pool accounting goes away.
				int budget = Math.min(outlet.getTransferCap(), main.getOutputBudget());
				if(budget <= 0)
				{
					outlet.setLastThroughput(0);
					continue;
				}
				int given = clampResult(endpoint.insertFromMain(fluid, budget, false), budget);
				outlet.recordThroughput(given);
				main.recordOut(given);
			}
		}
	}

	/**
	 * @return whether {@code main} has supply available, following failover links
	 */
	private static boolean resolvePressurised(VirtualFluidNet net, FluidMain main)
	{
		if(main.isOperational()&&main.isSourceLive())
			return true;
		if(main.getFailover().isEmpty())
			return false;
		Set<UUID> visited = new HashSet<>();
		visited.add(main.getId());
		//The same fluid rule as normal mode's failover. A live water main does not mean a diesel
		//main has supply, and city mode being cheap is not a reason for it to be wrong -- the whole
		//point of presence semantics is that the one bit it reports is trustworthy.
		return walkPressurised(net, main, main.getFluid(), visited, 0);
	}

	private static boolean walkPressurised(VirtualFluidNet net, FluidMain origin,
										   @Nullable String fluid, Set<UUID> visited, int depth)
	{
		//An untyped main has no supply to stand in for. Its outlets are skipped by the delivery pass
		//anyway, so reporting it as pressurised would light a lamp for something that will never
		//move a millibucket.
		if(fluid==null||depth >= FluidNetConfig.maxFailoverDepth)
			return false;
		List<UUID> links = origin.getFailover();
		for(int i = 0; i < links.size(); i++)
		{
			UUID backupId = links.get(i);
			if(!visited.add(backupId))
				continue;
			FluidMain backup = net.getMain(backupId);
			if(backup==null||!backup.isOperational())
				continue;
			if(backup.isSourceLive()&&fluid.equals(backup.getFluid()))
				return true;
			if(walkPressurised(net, backup, fluid, visited, depth+1))
				return true;
		}
		return false;
	}

	//	=================================
	//		INTROSPECTION
	//	=================================

	/**
	 * The backup mains that would be consulted for {@code main}, in the order the engine would ask
	 * them.
	 * <p>
	 * Shares the cycle guard and depth bound with {@link #walkFailover}, so the console's "who
	 * would cover this" preview cannot drift from what actually happens at runtime. Read-only:
	 * nothing here mutates a pack or a budget.
	 */
	public static List<FluidMain> failoverChain(VirtualFluidNet net, FluidMain main)
	{
		List<FluidMain> chain = new ArrayList<>();
		if(main==null)
			return chain;
		Set<UUID> visited = new HashSet<>();
		visited.add(main.getId());
		collectChain(net, main, visited, 0, chain);
		return chain;
	}

	private static void collectChain(VirtualFluidNet net, FluidMain origin, Set<UUID> visited,
									 int depth, List<FluidMain> out)
	{
		if(depth >= FluidNetConfig.maxFailoverDepth)
			return;
		for(UUID backupId : origin.getFailover())
		{
			if(!visited.add(backupId))
				continue;
			FluidMain backup = net.getMain(backupId);
			if(backup==null)
				continue;
			out.add(backup);
			collectChain(net, backup, visited, depth+1, out);
		}
	}

	/**
	 * @return the first main in the chain that could actually supply right now, or null. A backup
	 * carrying a different fluid is not an available backup, however full it is.
	 */
	@Nullable
	public static FluidMain firstAvailableBackup(VirtualFluidNet net, FluidMain main, boolean cityMode)
	{
		String fluid = main==null?null: main.getFluid();
		for(FluidMain backup : failoverChain(net, main))
		{
			if(!backup.isOperational())
				continue;
			if(fluid!=null&&!fluid.equals(backup.getFluid()))
				continue;
			if(cityMode?backup.isSourceLive(): backup.getPack() > 0)
				return backup;
		}
		return null;
	}

	/**
	 * Spreads periodic work across ticks by position, the same trick the throttled multiblocks use.
	 */
	private static int stagger(FluidDevice device)
	{
		return ApiUtils.positionStagger(device.getPos().getX(),
				device.getPos().getZ()^(device.getDimension()*31), FluidNetConfig.sipIntervalTicks);
	}

	/**
	 * Endpoints are third-party code (a tile entity talking to arbitrary neighbouring mods). Clamp
	 * whatever they report into the range the engine offered, so a misbehaving one cannot mint or
	 * destroy fluid in the ledger.
	 */
	private static int clampResult(int reported, int offered)
	{
		if(reported <= 0)
			return 0;
		return Math.min(reported, offered);
	}
}
