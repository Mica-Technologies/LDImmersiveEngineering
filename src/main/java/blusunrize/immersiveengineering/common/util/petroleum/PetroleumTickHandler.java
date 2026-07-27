/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.petroleum;

import blusunrize.immersiveengineering.api.petroleum.PetroleumConfig;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityWellhead;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Drives oil production from a single server tick handler.
 * <p>
 * Wellheads are deliberately <strong>not</strong> {@code ITickable}. A well produces a
 * fraction of a bucket per second at best, so ticking each one twenty times a second to move
 * nothing is pure waste -- and a mature field is dozens of wells. Instead every wellhead is
 * driven once per {@link #PRODUCTION_INTERVAL}, staggered by position so a whole field never
 * fires on the same tick, and the reservoir maths computes the interval's output analytically.
 * <p>
 * Same arrangement as {@code GridTickHandler}, and for the same reasons.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
@Mod.EventBusSubscriber
public class PetroleumTickHandler
{
	/**
	 * Ticks between production passes for any one wellhead.
	 */
	public static final int PRODUCTION_INTERVAL = 20;

	private static final Set<TileEntityWellhead> WELLHEADS = new LinkedHashSet<>();
	/**
	 * Iteration snapshot, rebuilt only when membership changes. Iterating the set directly
	 * would be fine today, but pushing fluid into a neighbour runs third-party code and a
	 * concurrent modification there would be a miserable crash to diagnose.
	 */
	private static TileEntityWellhead[] snapshot = new TileEntityWellhead[0];
	private static boolean snapshotDirty = true;

	private static long tickCounter;

	public static void register(TileEntityWellhead wellhead)
	{
		if(WELLHEADS.add(wellhead))
			snapshotDirty = true;
	}

	public static void unregister(TileEntityWellhead wellhead)
	{
		if(WELLHEADS.remove(wellhead))
			snapshotDirty = true;
	}

	/**
	 * @return how many wellheads are currently loaded and producing
	 */
	public static int getActiveCount()
	{
		return WELLHEADS.size();
	}

	/**
	 * Drops every registration. Called on server start so a second world in one session does
	 * not inherit the previous one's wells.
	 */
	public static void reset()
	{
		WELLHEADS.clear();
		snapshot = new TileEntityWellhead[0];
		snapshotDirty = false;
		tickCounter = 0;
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(event.phase!=TickEvent.Phase.END||!PetroleumConfig.enabled)
			return;
		long tick = tickCounter++;
		if(WELLHEADS.isEmpty())
			return;
		if(snapshotDirty)
		{
			snapshot = WELLHEADS.toArray(new TileEntityWellhead[0]);
			snapshotDirty = false;
		}
		for(int i = 0; i < snapshot.length; i++)
		{
			TileEntityWellhead wellhead = snapshot[i];
			if((tick+wellhead.getStagger())%PRODUCTION_INTERVAL==0)
				wellhead.produce(PRODUCTION_INTERVAL);
		}
	}
}
