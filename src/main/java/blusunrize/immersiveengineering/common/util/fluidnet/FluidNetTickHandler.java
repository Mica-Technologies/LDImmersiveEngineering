/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetEngine;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives {@link FluidNetEngine} once per server tick.
 * <p>
 * A single {@link TickEvent.ServerTickEvent} rather than a per-world tick: the network spans
 * dimensions, and one pass per tick keeps the ledger trivially correct (a main's per-tick budgets
 * are opened and closed exactly once). This also means Inlets, Outlets and Valves are <em>not</em>
 * {@code ITickable} -- no tile entity in this feature ticks individually, which is the whole
 * performance argument for a virtual network over a pipe run.
 * <p>
 * The deliberate mirror of {@code GridTickHandler}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
@Mod.EventBusSubscriber
public class FluidNetTickHandler
{
	/**
	 * Monotonic tick counter. Not world time: world time can be frozen with a gamerule or jumped by
	 * commands, and the city-mode liveness stagger must keep advancing regardless.
	 */
	private static long tickCounter;

	/**
	 * Tracks city-mode transitions so device buffers get resized exactly when the mode flips,
	 * rather than being re-checked on every device every tick.
	 */
	private static boolean lastCityMode;
	private static boolean initialised;

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END||!FluidNetConfig.enabled)
			return;

		boolean cityMode = CityMode.petroleum();
		if(!initialised||cityMode!=lastCityMode)
		{
			//An Inlet's buffer is sized differently in city mode (it holds one sip, so a source
			//cannot stockpile between checks), so every endpoint has to be told.
			for(FluidDevice device : VirtualFluidNet.INSTANCE.getDevices())
				device.notifyEndpoint();
			lastCityMode = cityMode;
			initialised = true;
		}

		FluidNetEngine.applySchedules(VirtualFluidNet.INSTANCE, getScheduleDayTime());
		FluidNetEngine.tick(VirtualFluidNet.INSTANCE, tickCounter++, cityMode);
	}

	/**
	 * The clock every main's schedule runs on.
	 * <p>
	 * A main can span dimensions, so exactly one clock has to be authoritative or the same schedule
	 * would mean different things to different fittings in it. The overworld's is the one players
	 * actually mean by "dusk".
	 */
	private static long getScheduleDayTime()
	{
		MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
		if(server==null)
			return 0;
		World overworld = server.getWorld(0);
		return overworld==null?0: overworld.getWorldTime()%24000L;
	}

	/**
	 * @return the engine's tick counter, for staggering and liveness comparisons
	 */
	public static long getTick()
	{
		return tickCounter;
	}

	/**
	 * Resets per-process state. Called on server start so a second world in the same session does
	 * not inherit the previous one's counter.
	 */
	public static void reset()
	{
		tickCounter = 0;
		initialised = false;
	}
}
