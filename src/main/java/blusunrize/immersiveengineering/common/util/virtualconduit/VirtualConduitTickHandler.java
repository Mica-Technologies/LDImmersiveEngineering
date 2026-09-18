/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.virtualconduit;

import blusunrize.immersiveengineering.api.energy.virtualconduit.IVirtualConduitWorld;
import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitEngine;
import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitLink;
import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduits;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import blusunrize.immersiveengineering.common.blocks.conduit.TileEntityJunctionBox;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.WireNetTransfer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Drives {@link VirtualConduitEngine} once per server tick, after every world has ticked -- so a box
 * that was loaded and did the job for real this tick has already done it before this pass considers
 * standing in for it.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
@Mod.EventBusSubscriber
public class VirtualConduitTickHandler
{
	private static final PortImpl PORT = new PortImpl();
	/**
	 * Ticks between orphan sweeps. See {@link VirtualConduitEngine#sweepOrphans}.
	 */
	private static final int SWEEP_INTERVAL = 200;
	/**
	 * How long a cached liveness flood may stand before it is thrown away regardless.
	 * <p>
	 * Everything that ought to invalidate it does -- a feed appearing or going, a run being made or
	 * broken -- so this is the backstop rather than the mechanism: a second is short enough that a
	 * missed notification cannot become a town lit by a circuit that no longer exists, and long
	 * enough that the flood is not the cost of the feature.
	 */
	private static final int LIVENESS_INTERVAL = 20;
	private static long tickCounter;

	/**
	 * The world behind the registry's liveness flood as well as the engine's push, handed over when
	 * the save data loads.
	 */
	public static IVirtualConduitWorld port()
	{
		return PORT;
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END)
			return;
		if(VirtualConduits.INSTANCE.size()==0&&VirtualConduits.INSTANCE.feedCount()==0)
			return;
		if(++tickCounter%LIVENESS_INTERVAL==0)
			VirtualConduits.INSTANCE.invalidateLiveness();
		if(tickCounter%SWEEP_INTERVAL==0)
			VirtualConduitEngine.sweepOrphans(VirtualConduits.INSTANCE, PORT);
		VirtualConduitEngine.tick(VirtualConduits.INSTANCE, PORT, CityMode.conduits());
	}

	/**
	 * The world behind {@link IVirtualConduitWorld}. Every query that touches blocks is a
	 * loaded-check first: nothing here may cause a chunk to load. The two that do not --
	 * {@link #bundleNeighbours} and {@link #push} -- read the wire graph, which is global.
	 */
	static final class PortImpl implements IVirtualConduitWorld
	{
		@Override
		public boolean isDimensionLoaded(int dimension)
		{
			return DimensionManager.getWorld(dimension)!=null;
		}

		@Override
		public boolean isLoaded(int dimension, BlockPos pos)
		{
			World world = DimensionManager.getWorld(dimension);
			return world!=null&&world.isBlockLoaded(pos);
		}

		@Override
		public boolean stillBreaksOut(int dimension, BlockPos boxPos, VirtualConduitLink link)
		{
			World world = DimensionManager.getWorld(dimension);
			if(world==null||!world.isBlockLoaded(boxPos))
				//Never called with an unloaded position, but answering "yes, still there" rather
				//than "gone" is the safe way round if it ever is: the sweep deletes on a false.
				return true;
			TileEntity te = world.getTileEntity(boxPos);
			if(!(te instanceof TileEntityJunctionBox))
				return false;
			return ((TileEntityJunctionBox)te).breaksOutOnto(link);
		}

		@Override
		public Collection<BlockPos> bundleNeighbours(int dimension, BlockPos boxPos, WireChannel channel)
		{
			Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(dimension, boxPos);
			if(conns==null)
				return Collections.emptyList();
			Set<BlockPos> peers = new HashSet<>();
			for(Connection con : conns)
				//A bundle carries what its ChannelSet says it carries. Every bundle this mod makes
				//carries all sixteen, but a set that has lost a conductor must not carry a feed's
				//word for it across -- channel separation is the point of the feature and it has to
				//survive the flood as well as the push.
				if(con.isBundle()&&con.channels!=null&&con.channels.getSpec(channel)!=null)
					peers.add(con.end);
			return peers;
		}

		@Override
		public int push(VirtualConduitLink link)
		{
			World world = DimensionManager.getWorld(link.getDimension());
			if(world==null||link.getRate() <= 0)
				return 0;
			BlockPos from = link.getOutletPos();
			//The run behind this push is the record's box, which is unloaded -- so it cannot be
			//worked out from the world the way a loaded node's is, and is handed over instead. See
			//ConduitRuns for what it is for.
			if(link.getKind()==VirtualConduitLink.Kind.NEIGHBOUR)
				//A connector has one terminal and therefore one circuit: there is nothing to filter,
				//and filtering on a wire it does not have would deliver nothing at all. This is the
				//path every breakout on the playtester's poles actually takes.
				return WireNetTransfer.cityFromRun(world, from, link.getRate(), null, link.getBoxPos());
			String wireTypeName = link.getWireTypeName();
			BlockPos wireEnd = link.getWireEnd();
			if(wireTypeName==null||wireEnd==null)
				return 0;
			//The same filter handToWire builds, rebuilt from what was recorded: this conductor's
			//energy leaves by its own face's wire and no other, which is what sixteen conductors
			//exist to do and what would otherwise quietly stop being true while unloaded.
			Predicate<AbstractConnection> onlyThisWire = route -> {
				Connection first = WireNetTransfer.firstHop(route);
				return first!=null&&first.cableType!=null
						&&wireTypeName.equals(first.cableType.getUniqueName())
						&&wireEnd.equals(first.end);
			};
			return WireNetTransfer.cityFromRun(world, from, link.getRate(), onlyThisWire, link.getBoxPos());
		}
	}
}
