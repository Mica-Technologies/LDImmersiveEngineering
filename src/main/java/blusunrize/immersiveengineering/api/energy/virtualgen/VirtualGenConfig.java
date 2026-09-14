/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import java.util.HashSet;
import java.util.Set;

/**
 * Runtime mirror of the {@code VirtualGeneration} config group.
 * <p>
 * Same arrangement as {@code GridConfig}: {@code Config.onConfigUpdate()} pushes values in, so the model
 * in {@code api} never reaches back into {@code common} and tests can assign the fields directly. The
 * defaults here are the shipped defaults.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public final class VirtualGenConfig
{
	private VirtualGenConfig()
	{
	}

	/**
	 * Master switch. When false no plant is ever virtual: an unloaded plant simply stops, as in stock IE.
	 */
	public static boolean enabled = true;
	/**
	 * How many seconds of measurements the rolling peak looks back over.
	 */
	public static int measureWindowSeconds = 60;
	/**
	 * Ceiling on any one meter's virtual output, in IF/t, on top of the connector's own rate.
	 */
	public static int maxRate = 32768;
	/**
	 * Registry names of generators from other mods that burn no fuel, and so qualify for virtual output
	 * outside City Mode. IE's own free generators are classified in code.
	 */
	public static Set<String> freeSources = new HashSet<>();

	public static void resetToDefaults()
	{
		enabled = true;
		measureWindowSeconds = 60;
		maxRate = 32768;
		freeSources = new HashSet<>();
	}
}
