/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import java.util.ArrayList;
import java.util.List;

/**
 * One tick of virtual conduit: every outlet whose box is unloaded, and whose conductor the registry
 * says is being fed, hands its offer to the network on its face, as the box itself would if it were
 * ticking.
 * <p>
 * <strong>Only the last step is reproduced.</strong> A run is a chain of boxes passing energy along
 * one hop per tick, and none of that needs simulating while it is unloaded, because nothing loaded
 * can observe it. What has to keep happening is the end of the chain: the far box handing its
 * conductor to whatever is strung or bolted to it. So this is not a transport model, it is a record
 * of one hand-off being replayed.
 * <p>
 * <strong>A loaded box is always skipped.</strong> It is doing the real thing, and delivering both
 * ways would double a town's supply -- which is the obvious way for this feature to be wrong, and
 * the failure that would be hardest to notice, because more power looks like everything working. A
 * loaded box on a run that is fed from somewhere unloaded holds itself lit instead; see
 * {@code TileEntityJunctionBox.holdVirtuallyLit}.
 * <p>
 * <strong>And a dark one is skipped too.</strong> An outlet exists while its breakout exists, which
 * says nothing at all about whether the circuit behind it is switched on. {@link VirtualConduits#isLive}
 * is the other half: a conductor delivers while a feed reaches it over the bundles, and stops when
 * the last feed goes. Without that a run would go on supplying a town from a generator somebody
 * dismantled last week.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public final class VirtualConduitEngine
{
	private VirtualConduitEngine()
	{
	}

	/**
	 * @param cityMode whether conduits are running on presence rather than accounting. Outside it a
	 *                 conduit is a thing that works while loaded, and nothing here applies -- see
	 *                 the decisions table in the plan for why metered flux through unloaded blocks
	 *                 is not on offer.
	 *
	 * @return total energy delivered virtually this tick
	 */
	public static int tick(VirtualConduits registry, IVirtualConduitWorld world, boolean cityMode)
	{
		int total = 0;
		for(VirtualConduitLink link : registry.getLinks())
		{
			link.setLastDelivered(0);
			if(!VirtualConduitConfig.enabled||!cityMode||!link.isUsable())
				continue;
			int dim = link.getDimension();
			//An unloaded dimension has nothing loaded on it to receive, so there is nobody to
			//deliver to and no reason to walk its wire graph.
			if(!world.isDimensionLoaded(dim))
				continue;
			//The box is here and ticking: it is doing this itself.
			if(world.isLoaded(dim, link.getBoxPos()))
				continue;
			//Nothing is feeding this conductor anywhere on its run, so there is nothing to deliver.
			if(!registry.isLive(dim, link.getBoxPos(), link.getChannel()))
				continue;
			int delivered = world.push(link);
			if(delivered > 0)
			{
				link.setLastDelivered(delivered);
				total += delivered;
			}
		}
		return total;
	}

	/**
	 * Drop records whose breakout is gone.
	 * <p>
	 * A box refreshes its own outlets whenever its patch or wire tables can be trusted, which covers
	 * every change it is told about. This covers the ones nothing tells it about: a world edit, a
	 * {@code /setblock}, or the block simply being something else the next time anybody looks.
	 * <p>
	 * Checked only where the box's chunk is loaded. An unloaded one cannot be inspected without
	 * loading it, and is precisely the case the record is there to cover -- so "cannot see it" must
	 * never be read as "it is gone". That is the same mistake that was deleting whole runs from the
	 * wire graph before {@code ConduitRoute.Walk} learned to report a truncated walk.
	 *
	 * @return how many records were dropped
	 */
	public static int sweepOrphans(VirtualConduits registry, IVirtualConduitWorld world)
	{
		List<VirtualConduitLink> orphans = new ArrayList<>();
		for(VirtualConduitLink link : registry.getLinks())
		{
			int dim = link.getDimension();
			if(!world.isDimensionLoaded(dim)||!world.isLoaded(dim, link.getBoxPos()))
				continue;
			if(!world.stillBreaksOut(dim, link.getBoxPos(), link))
				orphans.add(link);
		}
		for(VirtualConduitLink orphan : orphans)
			registry.remove(orphan.getDimension(), orphan.getBoxPos(), orphan.getChannel(), orphan.getKind());
		return orphans.size();
	}
}
