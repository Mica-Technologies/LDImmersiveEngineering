/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

/**
 * Runtime mirror of the {@code Petroleum} config group.
 * <p>
 * The reservoir model lives in {@code api} and must not reach into {@code common.Config}, so
 * {@code Config.onConfigUpdate()} pushes the values in here instead -- the same arrangement
 * {@code GridConfig} and {@code WireType} use. The practical benefit is that the whole model
 * stays configurable at runtime <em>and</em> trivially set up in unit tests, which just assign
 * these fields directly.
 * <p>
 * The defaults below are the shipped defaults and are what applies if the config never loads
 * (dedicated-server tooling, tests).
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class PetroleumConfig
{
	private PetroleumConfig()
	{
	}

	/**
	 * Master switch. When false no reservoir is ever generated and extraction blocks stay inert.
	 */
	public static boolean enabled = true;
	/**
	 * Edge length, in chunks, of the square cell a single reservoir occupies. Deposits are rolled
	 * per cell rather than per chunk so that a field spans a believable area and neighbouring
	 * chunks agree about what is underneath them.
	 */
	public static int cellChunkSize = 8;
	/**
	 * Chance that any given cell contains anything at all, 0..1.
	 */
	public static double cellChance = 0.25;
	/**
	 * Smallest and largest reservoir capacity in mB. Rolls are log-distributed between them, so
	 * modest fields are common and a giant is a genuine event.
	 */
	public static int minCapacity = 2_000_000;
	public static int maxCapacity = 16_000_000;
	/**
	 * Fraction of original capacity remaining above which a well flows without a pump.
	 */
	public static double freeFlowThreshold = 0.6;
	/**
	 * Peak extraction rate in mB/tick at full pressure, before any equipment multiplier.
	 */
	public static int peakFlowRate = 30;
	/**
	 * Floor the flow rate decays to to rather than reaching zero, in mB/tick. A depleted field
	 * becomes slow, never dead -- a pumpjack must not strand a base that was built around it.
	 */
	public static double residualFlowRate = 0.025;
	/**
	 * Dimensions in which reservoirs are never generated.
	 */
	public static int[] dimensionBlacklist = {1};

	/**
	 * Restores every field to its shipped default. Used by tests; also a safe reset if a config
	 * reload ever fails half-way.
	 */
	public static void resetToDefaults()
	{
		enabled = true;
		cellChunkSize = 8;
		cellChance = 0.25;
		minCapacity = 2_000_000;
		maxCapacity = 16_000_000;
		freeFlowThreshold = 0.6;
		peakFlowRate = 30;
		residualFlowRate = 0.025;
		dimensionBlacklist = new int[]{1};
	}
}
