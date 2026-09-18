/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitRuns;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Pushing energy out of a node and along the wire network it is attached to.
 * <p>
 * This is IE's own connector transfer, lifted verbatim out of {@code TileEntityConnectorLV} so that
 * a second kind of node can use it. That second node is the Grid Service Unit, which now takes a
 * wire directly rather than requiring a connector bolted beside it; it has to put energy onto a
 * catenary in exactly the way a connector does, or the two would disagree about loss, about the
 * proportional split when several outputs compete, and about what an Energy Meter in the middle
 * reads.
 * <p>
 * <strong>Copied, not re-derived.</strong> The proportional split, the double simulate-then-send
 * pass, the per-connection loss and the passthrough notifications are subtle and their behaviour is
 * load-bearing for every wire in the game. The connector calls this now rather than keeping its own
 * copy, so there is one implementation to be right and no second one to drift.
 * <p>
 * The caller owns the endpoint cache and its own storage: this method decides and moves, and reports
 * what was taken so the caller can debit itself.
 *
 * @author LDImmersiveEngineering -- direct wire seams
 */
public final class WireNetTransfer
{
	private WireNetTransfer()
	{
	}

	/**
	 * The last leg of a route: the connection whose {@code end} is the node about to be handed the
	 * energy, and therefore the one that node knows as "the wire on such-and-such a terminal".
	 * <p>
	 * The route is built outward from the pushing node -- see
	 * {@code ImmersiveNetHandler.getIndirectEnergyConnections} -- so the first sub-connection
	 * leaves the pusher and the last arrives at the receiver.
	 */
	@Nullable
	private static Connection lastHop(AbstractConnection con)
	{
		if(con.subConnections==null||con.subConnections.length==0)
			return null;
		return con.subConnections[con.subConnections.length-1];
	}

	/**
	 * The first leg of a route: the wire it leaves this node by.
	 * <p>
	 * Public because the filter a caller passes to {@link #transfer} is written in terms of it --
	 * the node knows which of its own wires it means, and this is where the route says which one it
	 * took.
	 */
	@Nullable
	public static Connection firstHop(AbstractConnection con)
	{
		if(con.subConnections==null||con.subConnections.length==0)
			return null;
		return con.subConnections[0];
	}

	/**
	 * Sends up to {@code energy} out of the node at {@code pos} along its network.
	 *
	 * @param maxInput  the node's input rate, used only for the loss curve's shoulder -- the same
	 *                  figure a connector passes, so a run behaves identically whichever kind of
	 *                  node is feeding it
	 * @param maxOutput the node's output rate
	 * @param endCache  a scratch map owned by the caller, cleared here. Reused rather than allocated
	 *                  because this runs twice a tick for every powered node on a server.
	 * @return how much was taken from the caller
	 */
	public static int transfer(World world, BlockPos pos, int maxInput, int maxOutput, int energy,
							   boolean simulate, int energyType,
							   Map<AbstractConnection, IImmersiveConnectable> endCache)
	{
		return transfer(world, pos, maxInput, maxOutput, energy, simulate, energyType, endCache, null);
	}

	/**
	 * The same push, restricted to the routes that leave by one particular wire.
	 * <p>
	 * A connector has one terminal and wants all of them, which is what the overload above is. A
	 * conduit junction box has six, each carrying its own conductor, and a channel's energy must
	 * leave by <em>its</em> face's wire and no other -- so it filters on the first hop of each
	 * route. The alternative is six terminals sharing one budget, which is not six circuits.
	 *
	 * @param only keeps a route if it returns true, or null to take every route as before
	 */
	public static int transfer(World world, BlockPos pos, int maxInput, int maxOutput, int energy,
							   boolean simulate, int energyType,
							   Map<AbstractConnection, IImmersiveConnectable> endCache,
							   @Nullable Predicate<AbstractConnection> only)
	{
		int received = 0;
		if(world.isRemote)
			return 0;
		Set<AbstractConnection> outputs = ImmersiveNetHandler.INSTANCE.getIndirectEnergyConnections(pos,
				world, true);
		int powerLeft = Math.min(Math.min(maxOutput, maxInput), energy);
		final int powerForSort = powerLeft;

		if(outputs.isEmpty())
			return 0;

		endCache.clear();
		Map<Connection, Integer> transferedRates = ImmersiveNetHandler.INSTANCE.getTransferedRates(
				world.provider.getDimension());
		int sum = 0;
		//TreeMap to prioritize outputs close to this node if more energy is requested than available
		//(energy will be provided to the nearby outputs rather than some random ones)
		Map<AbstractConnection, Integer> powerSorting = new TreeMap<>();
		for(AbstractConnection con : outputs)
			if(con.isEnergyOutput&&(only==null||only.test(con)))
			{
				IImmersiveConnectable end = ApiUtils.toIIC(con.end, world);
				if(con.cableType!=null&&end!=null)
				{
					int atmOut = Math.min(powerForSort, con.cableType.getTransferRate());
					int tempR = end.outputEnergy(atmOut, true, energyType, lastHop(con));
					if(tempR > 0)
					{
						powerSorting.put(con, tempR);
						endCache.put(con, end);
						sum += tempR;
					}
				}
			}

		if(sum > 0)
			for(AbstractConnection con : powerSorting.keySet())
			{
				IImmersiveConnectable end = endCache.get(con);
				if(con.cableType!=null&&end!=null)
				{
					float prio = powerSorting.get(con)/(float)sum;
					int output = Math.min(MathHelper.ceil(powerForSort*prio), powerLeft);

					int tempR = end.outputEnergy(Math.min(output, con.cableType.getTransferRate()), true,
							energyType, lastHop(con));
					int r = tempR;
					tempR -= (int)Math.max(0, Math.floor(tempR*con.getPreciseLossRate(tempR, maxInput)));
					end.outputEnergy(tempR, simulate, energyType, lastHop(con));
					HashSet<IImmersiveConnectable> passedConnectors = new HashSet<>();
					float intermediaryLoss = 0;
					//<editor-fold desc="Transfer rate and passed energy">
					for(Connection sub : con.subConnections)
					{
						float length = sub.length/(float)sub.cableType.getMaxLength();
						float baseLoss = (float)sub.cableType.getLossRatio();
						float mod = (((maxInput-tempR)/(float)maxInput)/.25f)*.1f;
						intermediaryLoss = MathHelper.clamp(intermediaryLoss+length*(baseLoss+baseLoss*mod), 0, 1);

						int transferredPerCon = transferedRates.getOrDefault(sub, 0);
						transferredPerCon += r;
						if(!simulate)
						{
							transferedRates.put(sub, transferredPerCon);
							IImmersiveConnectable subStart = ApiUtils.toIIC(sub.start, world);
							IImmersiveConnectable subEnd = ApiUtils.toIIC(sub.end, world);
							if(subStart!=null&&passedConnectors.add(subStart))
								subStart.onEnergyPassthrough(r-r*intermediaryLoss);
							if(subEnd!=null&&passedConnectors.add(subEnd))
								subEnd.onEnergyPassthrough(r-r*intermediaryLoss);
						}
					}
					//</editor-fold>
					received += r;
					powerLeft -= r;
					if(powerLeft <= 0)
						break;
				}
			}
		return received;
	}

	/**
	 * "City mode" push (see {@link CityMode#wires()}): a single lossless pass that hands this node's
	 * energy straight to the devices reachable on its network, skipping the realistic grid's per-wire
	 * loss, distance weighting, proportional split and double simulate/transfer pass.
	 * <p>
	 * Energy is still conserved -- only what a device actually accepts counts against the return.
	 *
	 * @return how much was consumed, for the caller to debit
	 */
	public static int city(World world, BlockPos pos, int available)
	{
		return city(world, pos, available, null);
	}

	/**
	 * City mode's push from a node whose conduit run is already known, for the one caller that cannot
	 * work it out: the virtual push, whose origin box is unloaded by definition.
	 *
	 * @param runBox the junction box this energy came out of, so the push does not hand it back to
	 *               the run it came from -- see {@link ConduitRuns}
	 */
	public static int cityFromRun(World world, BlockPos pos, int available,
								  @Nullable Predicate<AbstractConnection> only, BlockPos runBox)
	{
		return city(world, pos, available, only, ConduitRuns.shadowOfRunAt(world, runBox));
	}

	/**
	 * City mode's push, restricted to one of this node's wires. See the filtered
	 * {@link #transfer} for why a node with six terminals needs that.
	 * <p>
	 * <strong>Each output is offered what is left, and the line sheds what it cannot carry.</strong>
	 * A version of this briefly split the node's energy evenly between every output before anyone
	 * was offered the rest, meaning to end the coin toss over who gets served first. It made things
	 * very much worse, and the reason is worth keeping written down.
	 * <p>
	 * An equal share is only equal while there is a share to go round. A city district runs on far
	 * more consumers than its source has flux -- a metered plant on a playtester's server supplies
	 * 40 a tick to a line with hundreds of things on it -- so the split floors at one apiece, forty
	 * consumers are handed a single flux each, the budget is gone, and the second pass never runs.
	 * Nothing on the line can do anything with one flux. Before the split, the first consumers off
	 * the line got the whole forty and actually ran; after it, a whole district went dark. Spreading
	 * an insufficient supply thinly is not fairness, it is an outage.
	 * <p>
	 * So the push serves each output in turn with everything still in hand, and when it runs out the
	 * rest go without. That is what a real line does when it cannot carry its load, and it is the
	 * only policy here that leaves anything running at all.
	 * <p>
	 * <strong>What the coin toss actually was.</strong> The report that prompted the split was a
	 * conduit box reading zero on a shared line, and the box was not losing a race -- it was asking
	 * for a twentieth of a channel every tick, 1,638, which is more than any LV or MV connector
	 * supplies. It ate the line or was starved by it depending on set order. That is fixed where it
	 * belonged, in {@code JunctionBoxLogic.debit}: under city mode's presence rule a lit conductor
	 * costs its sender a single token. A box is a cheap thing to serve now, so serving in order
	 * reaches it.
	 * <p>
	 * Nothing is simulated and nothing is sorted -- the point of city mode is to skip normal mode's
	 * simulate-sort-split. Energy is conserved: only what was accepted is returned.
	 * <p>
	 * <strong>A run may not feed itself.</strong> Presence is generous by design -- any credit at all
	 * fills a conductor -- and on a network with two breakouts of one run on it that generosity
	 * closes a loop and never opens again. So a push whose origin has a conduit run behind it skips
	 * the destinations that lead back into the same run. {@link ConduitRuns} carries the whole story,
	 * including why almost every push pays nothing for the rule. Normal mode has no latch to break:
	 * it conserves energy, so a circle of hand-offs runs down instead of locking on.
	 */
	public static int city(World world, BlockPos pos, int available,
						   @Nullable Predicate<AbstractConnection> only)
	{
		if(world.isRemote||available <= 0)
			return 0;
		return city(world, pos, available, only, ConduitRuns.shadowFor(world, pos));
	}

	/**
	 * The same push with the run behind it already worked out, for the two callers that know it
	 * better than {@link ConduitRuns#runBehind} could: a junction box, which is its own run and
	 * caches the answer rather than flooding it once per conductor per tick, and the virtual push,
	 * whose origin box is unloaded.
	 *
	 * @param sameRun destinations that lead back into the pushing node's own conduit run, or null
	 *                when there is no run behind it -- which is the case for virtually every node on
	 *                a server, and then this behaves exactly as it always did
	 */
	public static int city(World world, BlockPos pos, int available,
						   @Nullable Predicate<AbstractConnection> only, @Nullable Set<BlockPos> sameRun)
	{
		if(world.isRemote||available <= 0)
			return 0;
		Set<AbstractConnection> outputs = ImmersiveNetHandler.INSTANCE.getIndirectEnergyConnections(pos,
				world, true);
		if(outputs.isEmpty())
			return 0;
		int powerLeft = available;
		for(AbstractConnection con : outputs)
		{
			if(powerLeft <= 0)
				break;
			powerLeft = cityOffer(world, con, only, sameRun, powerLeft);
		}
		return available-powerLeft;
	}

	/**
	 * A route the city push may send along: to a consumer, over conductive wire, one the caller's
	 * filter keeps, and one that does not arrive back at the run the energy came out of.
	 */
	private static boolean conducts(AbstractConnection con, @Nullable Predicate<AbstractConnection> only,
									@Nullable Set<BlockPos> sameRun)
	{
		if(!con.isEnergyOutput||con.cableType==null||con.cableType.getTransferRate() <= 0)
			return false;
		if(ConduitRuns.blocks(sameRun, con.end))
			return false;
		return only==null||only.test(con);
	}

	/**
	 * Offer one route everything still in hand.
	 *
	 * @return what is left afterwards
	 */
	private static int cityOffer(World world, AbstractConnection con,
								 @Nullable Predicate<AbstractConnection> only,
								 @Nullable Set<BlockPos> sameRun, int powerLeft)
	{
		if(!conducts(con, only, sameRun))
			return powerLeft;
		IImmersiveConnectable end = ApiUtils.toIIC(con.end, world);
		if(end==null||!end.allowEnergyToPass(null))
			return powerLeft;
		int sent = end.outputEnergy(powerLeft, false, 0, lastHop(con));
		powerLeft -= sent;
		//Notify in-line connectables (e.g. the Energy Meter) of throughput so they still measure
		//power in city mode. City mode is lossless, so the full amount passes through every
		//sub-connection.
		if(sent > 0)
		{
			HashSet<IImmersiveConnectable> passed = new HashSet<>();
			for(Connection sub : con.subConnections)
			{
				IImmersiveConnectable subStart = ApiUtils.toIIC(sub.start, world);
				if(subStart!=null&&passed.add(subStart))
					subStart.onEnergyPassthrough((double)sent);
				IImmersiveConnectable subEnd = ApiUtils.toIIC(sub.end, world);
				if(subEnd!=null&&passed.add(subEnd))
					subEnd.onEnergyPassthrough((double)sent);
			}
		}
		return powerLeft;
	}
}
