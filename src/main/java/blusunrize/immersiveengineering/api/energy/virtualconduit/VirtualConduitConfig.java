/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

/**
 * Virtual conduit's settings, pushed here from {@code IEConfig} so the engine can be tested without
 * a config or a game -- the same arrangement {@code VirtualGenConfig} has, for the same reason.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public final class VirtualConduitConfig
{
	private VirtualConduitConfig()
	{
	}

	/**
	 * Whether a breakout keeps delivering while its box's chunk is unloaded. Off restores exactly
	 * what conduit did before the feature: fine while loaded, dark otherwise.
	 */
	public static boolean enabled = true;

	/**
	 * Back to the shipped default, for tests that change it.
	 */
	public static void reset()
	{
		enabled = true;
	}
}
