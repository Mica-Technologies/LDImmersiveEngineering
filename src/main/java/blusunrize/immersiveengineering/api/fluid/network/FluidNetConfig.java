/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

/**
 * Runtime mirror of the {@code FluidNetwork} config group.
 * <p>
 * Same arrangement as {@code GridConfig}, for the same reason: the model lives in {@code api}
 * and must not reach into {@code common.Config}, so {@code Config.onConfigUpdate()} pushes the
 * values in here. The practical benefit is that the whole model stays configurable at runtime
 * <em>and</em> trivially set up in unit tests, which just assign these fields directly.
 * <p>
 * <strong>This file is one half of a deliberate pair.</strong> Every field here has a
 * counterpart in {@code GridConfig}, and a fix to one is an obvious candidate for the other.
 * That duplication was chosen over generalising the shipped grid into a resource-agnostic
 * engine, because the grid has world save data behind it and a migration bug there costs
 * somebody their power network.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public final class FluidNetConfig
{
	private FluidNetConfig()
	{
	}

	/**
	 * Master switch. When false the tick engine does no work and devices stay inert.
	 */
	public static boolean enabled = true;
	/**
	 * Whether one main may span more than one dimension.
	 */
	public static boolean crossDimension = true;
	/**
	 * Default per-device throughput in mB/t.
	 * <p>
	 * Sized against the pipe it replaces rather than against a machine: IE's Fluid Pipe moves
	 * 50 mB/t unpressurised and 1000 pressurised, so a default endpoint is comfortably a
	 * pressurised pipe's worth. A Steam Turbine Hall drinks 300 mB/t, so one Outlet at the
	 * default feeds three of them.
	 */
	public static int defaultDeviceCap = 1000;
	/**
	 * Upper bound a main's in/out cap may be raised to from the GUI.
	 * <p>
	 * Paired with {@link #packCapMax} exactly as the grid's is: a main's default line pack is
	 * {@link #packTicks} ticks of its own output rate, so this value times {@code packTicks}
	 * lands on the pack ceiling. Raising one without the other silently clamps new mains to
	 * less smoothing than the collect-then-serve ordering assumes.
	 */
	public static int maxMainIO = 32768;
	/**
	 * Default leakage, 0..1. Ships at 0 -- the network is a convenience feature by default and
	 * physical pipe keeps no efficiency advantage unless a pack asks for it.
	 */
	public static double defaultLeakPct = 0.0;
	/**
	 * Default for a new main's "backups also cover shortfalls" toggle.
	 */
	public static boolean failoverTopUpDefault = true;
	/**
	 * Hard ceiling on a main's line pack. This is the anti-tank clamp: a main is a conduit, and
	 * the buried tanks are what storage is for.
	 */
	public static int packCapMax = 65536;
	/**
	 * How many ticks of throughput a main packs by default.
	 */
	public static int packTicks = 2;
	/**
	 * Whether the Fluid Control Console needs standby power to display.
	 */
	public static boolean consoleRequiresPower = true;
	/**
	 * Console standby draw in IF/t.
	 */
	public static int consoleStandbyDraw = 8;
	/**
	 * Master switch for per-device chunk loading.
	 */
	public static boolean allowChunkloading = true;
	/**
	 * Server-wide ceiling on chunks held loaded by fluid network devices.
	 */
	public static int chunkloadBudget = 25;
	/**
	 * City mode: how often an Inlet proves its source is live.
	 */
	public static int sipIntervalTicks = 100;
	/**
	 * City mode: how much an Inlet consumes per liveness check, in millibuckets.
	 */
	public static int sipAmount = 1;
	/**
	 * How many failover hops a shortfall may traverse before giving up.
	 */
	public static int maxFailoverDepth = 4;
	/**
	 * Whether sustained saturation trips a main's overpressure cut-out.
	 */
	public static boolean tripsEnabled = false;
	/**
	 * Seconds of continuous saturation before an overpressure trip latches.
	 */
	public static int tripSeconds = 5;

	/**
	 * Restores every field to its shipped default. Used by tests; also a safe reset if a config
	 * reload ever fails half-way.
	 */
	public static void resetToDefaults()
	{
		enabled = true;
		crossDimension = true;
		defaultDeviceCap = 1000;
		maxMainIO = 32768;
		defaultLeakPct = 0.0;
		failoverTopUpDefault = true;
		packCapMax = 65536;
		packTicks = 2;
		consoleRequiresPower = true;
		consoleStandbyDraw = 8;
		allowChunkloading = true;
		chunkloadBudget = 25;
		sipIntervalTicks = 100;
		sipAmount = 1;
		maxFailoverDepth = 4;
		tripsEnabled = false;
		tripSeconds = 5;
	}
}
