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
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
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
	private static long tickCounter;

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END||VirtualConduits.INSTANCE.size()==0)
			return;
		if(++tickCounter%SWEEP_INTERVAL==0)
			VirtualConduitEngine.sweepOrphans(VirtualConduits.INSTANCE, PORT);
		VirtualConduitEngine.tick(VirtualConduits.INSTANCE, PORT, CityMode.conduits());
	}

	/**
	 * The world behind {@link IVirtualConduitWorld}. Every query is a loaded-check first: nothing
	 * here may cause a chunk to load.
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
			return ((TileEntityJunctionBox)te).breaksOutOnto(link.getChannel(), link.getWireEnd());
		}

		@Override
		public int push(int dimension, BlockPos boxPos, int amount, String wireTypeName, BlockPos wireEnd)
		{
			World world = DimensionManager.getWorld(dimension);
			if(world==null||amount <= 0||wireTypeName==null||wireEnd==null)
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
			return WireNetTransfer.city(world, boxPos, amount, onlyThisWire);
		}
	}
}
