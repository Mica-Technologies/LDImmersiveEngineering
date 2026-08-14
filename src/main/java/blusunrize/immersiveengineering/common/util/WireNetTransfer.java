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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
			if(con.isEnergyOutput)
			{
				IImmersiveConnectable end = ApiUtils.toIIC(con.end, world);
				if(con.cableType!=null&&end!=null)
				{
					int atmOut = Math.min(powerForSort, con.cableType.getTransferRate());
					int tempR = end.outputEnergy(atmOut, true, energyType);
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

					int tempR = end.outputEnergy(Math.min(output, con.cableType.getTransferRate()), true, energyType);
					int r = tempR;
					tempR -= (int)Math.max(0, Math.floor(tempR*con.getPreciseLossRate(tempR, maxInput)));
					end.outputEnergy(tempR, simulate, energyType);
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
			if(!con.isEnergyOutput||con.cableType==null||con.cableType.getTransferRate() <= 0)
				continue;
			IImmersiveConnectable end = ApiUtils.toIIC(con.end, world);
			if(end==null||!end.allowEnergyToPass(null))
				continue;
			int sent = end.outputEnergy(powerLeft, false, 0);
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
		}
		return available-powerLeft;
	}
}
