/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.virtualgen;

import blusunrize.immersiveengineering.api.energy.virtualgen.IVirtualGenWorld;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGenConfig;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGenEngine;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGeneration;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.WireNetTransfer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Drives {@link VirtualGenEngine} once per server tick, after every world has ticked, so a plant that was
 * running for real this tick has already pushed through its own connector before this pass decides it was.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
@Mod.EventBusSubscriber
public class VirtualGenTickHandler
{
	private static final WorldPort PORT = new WorldPort();

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END||!VirtualGenConfig.enabled||VirtualGeneration.INSTANCE.size()==0)
			return;
		VirtualGenEngine.tick(VirtualGeneration.INSTANCE, PORT, CityMode.wires());
	}

	/**
	 * The world behind {@link IVirtualGenWorld}. Every query is a loaded-check first: nothing here may
	 * cause a chunk to load, or virtual generation would be a chunk loader by another name.
	 */
	static final class WorldPort implements IVirtualGenWorld
	{
		/**
		 * Scratch for the normal-mode push, as a connector owns one. Server thread only.
		 */
		private final Map<AbstractConnection, IImmersiveConnectable> endCache = new HashMap<>();

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
		public int push(int dimension, BlockPos source, int amount, int rate, boolean cityMode)
		{
			World world = DimensionManager.getWorld(dimension);
			if(world==null||amount <= 0)
				return 0;
			if(cityMode)
				return WireNetTransfer.city(world, source, amount);
			//The connector's own two-step: find what the network would take, then send exactly that.
			int wanted = WireNetTransfer.transfer(world, source, rate, rate, amount, true, 0, endCache);
			if(wanted <= 0)
				return 0;
			return WireNetTransfer.transfer(world, source, rate, rate, wanted, false, 0, endCache);
		}
	}
}
