/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

/**
 * Runtime mirror of the {@code VirtualGrid} config group.
 * <p>
 * The grid model lives in {@code api} and must not reach into {@code common.Config},
 * so {@code Config.onConfigUpdate()} pushes the values in here instead -- the same
 * arrangement {@code WireType} uses for the wire arrays. The practical benefit is that
 * the whole model stays configurable at runtime <em>and</em> trivially set up in unit
 * tests, which just assign these fields directly.
 * <p>
 * The defaults below are the shipped defaults and are what applies if the config never
 * loads (dedicated-server tooling, tests).
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public final class GridConfig
{
	private GridConfig()
	{
	}

	/**
	 * Master switch. When false the tick engine does no work and devices stay inert.
	 */
	public static boolean enabled = true;
	/**
	 * Whether one segment may span more than one dimension.
	 */
	public static boolean crossDimension = true;
	/**
	 * Default per-device throughput in IF/t, HV-connector equivalent.
	 */
	public static int defaultDeviceCap = 4096;
	/**
	 * Upper bound a segment's in/out cap may be raised to from the GUI.
	 */
	public static int maxSegmentIO = 32768;
	/**
	 * Default transmission loss, 0..1. Ships at 0 -- the grid is a convenience feature by
	 * default and physical wire keeps no efficiency advantage unless a pack asks for it.
	 */
	public static double defaultLossPct = 0.0;
	/**
	 * Default for a new segment's "backups also cover shortfalls" toggle.
	 */
	public static boolean failoverTopUpDefault = true;
	/**
	 * Hard ceiling on a segment's smoothing buffer. This is the anti-battery clamp: the
	 * grid is a conduit, not free storage.
	 */
	public static int bufferCapMax = 262144;
	/**
	 * How many ticks of throughput a segment buffers by default.
	 */
	public static int bufferTicks = 2;
	/**
	 * Whether the Grid Management Console needs standby power to display.
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
	 * Server-wide ceiling on chunks held loaded by grid devices.
	 */
	public static int chunkloadBudget = 25;
	/**
	 * City mode: how often a feed unit proves its source is live.
	 */
	public static int sipIntervalTicks = 100;
	/**
	 * City mode: how much a feed unit consumes per liveness check.
	 */
	public static int sipAmount = 1;
	/**
	 * How many failover hops a shortfall may traverse before giving up.
	 */
	public static int maxFailoverDepth = 4;
	/**
	 * Whether sustained saturation trips a segment's breaker.
	 */
	public static boolean breakersEnabled = false;
	/**
	 * Seconds of continuous saturation before a breaker trips.
	 */
	public static int breakerTripSeconds = 5;

	/**
	 * Restores every field to its shipped default. Used by tests; also a safe reset if a
	 * config reload ever fails half-way.
	 */
	public static void resetToDefaults()
	{
		enabled = true;
		crossDimension = true;
		defaultDeviceCap = 4096;
		maxSegmentIO = 32768;
		defaultLossPct = 0.0;
		failoverTopUpDefault = true;
		bufferCapMax = 262144;
		bufferTicks = 2;
		consoleRequiresPower = true;
		consoleStandbyDraw = 8;
		allowChunkloading = true;
		chunkloadBudget = 25;
		sipIntervalTicks = 100;
		sipAmount = 1;
		maxFailoverDepth = 4;
		breakersEnabled = false;
		breakerTripSeconds = 5;
	}
}
