/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import java.util.Locale;

/**
 * The kind of world block a {@link FluidDevice} stands for.
 * <p>
 * Ordinals are persisted in {@link FluidDevice#writeToNBT} and in tile-entity NBT, so
 * <strong>constants must never be reordered or removed</strong> -- only appended.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public enum FluidDeviceType
{
	/**
	 * Fluid Inlet: takes fluid out of the world and into its main.
	 */
	INLET,
	/**
	 * Fluid Outlet: takes fluid out of its main and delivers it to the world.
	 */
	OUTLET,
	/**
	 * Fluid Control Console: carries no fluid, but is listed so a main can report where it is
	 * managed from.
	 */
	CONSOLE,
	/**
	 * Main Valve: carries no fluid either. It reads redstone into the main -- an external shut-off
	 * for a branch -- or writes the main's state back out as redstone.
	 * <p>
	 * The grid's Signal Unit under a name that means something on a pipe. Making one block do
	 * both jobs is deliberate: on a real network the thing that closes a branch and the thing that
	 * tells you whether the branch is live are the same fitting.
	 */
	VALVE;

	private static final FluidDeviceType[] VALUES = values();

	public String getName()
	{
		return this.toString().toLowerCase(Locale.ENGLISH);
	}

	/**
	 * @return true if this device participates in the per-tick fluid pass at all
	 */
	public boolean movesFluid()
	{
		return this==INLET||this==OUTLET;
	}

	/**
	 * Ordinal lookup that never throws -- unknown values (a save from a newer build, a corrupt
	 * tag) resolve to {@link #INLET} rather than crashing world load.
	 */
	public static FluidDeviceType byIndex(int index)
	{
		return index >= 0&&index < VALUES.length?VALUES[index]: INLET;
	}
}
