/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridEngine;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives {@link GridEngine} once per server tick.
 * <p>
 * A single {@link TickEvent.ServerTickEvent} rather than a per-world tick: the grid spans
 * dimensions, and one pass per tick keeps the energy ledger trivially correct (a segment's
 * per-tick budgets are opened and closed exactly once). This also means Feed and Service
 * Units are <em>not</em> {@code ITickable} -- no tile entity in this feature ticks
 * individually, which is a deliberate improvement on the wire connectors.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
@Mod.EventBusSubscriber
public class GridTickHandler
{
	/**
	 * Monotonic tick counter. Not world time: world time can be frozen with a gamerule or
	 * jumped by commands, and the city-mode liveness stagger must keep advancing regardless.
	 */
	private static long tickCounter;

	/**
	 * Tracks city-mode transitions so device buffers get resized exactly when the mode
	 * flips, rather than being re-checked on every device every tick.
	 */
	private static boolean lastCityMode;
	private static boolean initialised;

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END||!GridConfig.enabled)
			return;

		boolean cityMode = CityMode.grid();
		if(!initialised||cityMode!=lastCityMode)
		{
			//A Feed Unit's buffer is sized differently in city mode (it holds one sip, so a
			//source cannot stockpile between checks), so every endpoint has to be told.
			for(GridDevice device : VirtualGrid.INSTANCE.getDevices())
				device.notifyEndpoint();
			lastCityMode = cityMode;
			initialised = true;
		}

		GridEngine.applySchedules(VirtualGrid.INSTANCE, getScheduleDayTime());
		GridEngine.tick(VirtualGrid.INSTANCE, tickCounter++, cityMode);
	}

	/**
	 * The clock every segment schedule runs on.
	 * <p>
	 * A segment can span dimensions, so exactly one clock has to be authoritative or the
	 * same schedule would mean different things to different devices in it. The overworld's
	 * is the one players actually mean by "dusk"; dimensions with no day cycle of their own
	 * therefore inherit a sensible one instead of never advancing.
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
	 * Resets per-process state. Called on server start so a second world in the same
	 * session does not inherit the previous one's counter.
	 */
	public static void reset()
	{
		tickCounter = 0;
		initialised = false;
	}
}
