/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import java.util.Locale;

/**
 * The kind of world block a {@link GridDevice} stands for.
 * <p>
 * Ordinals are persisted in {@link GridDevice#writeToNBT} and in tile-entity NBT, so
 * <strong>constants must never be reordered or removed</strong> -- only appended.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public enum GridDeviceType
{
	/**
	 * Grid Feed Unit: takes energy out of the world and into its segment.
	 */
	FEED,
	/**
	 * Grid Service Unit: takes energy out of its segment and delivers it to the world.
	 */
	SERVICE,
	/**
	 * Grid Management Console: carries no energy, but is listed so a segment can report
	 * where it is managed from.
	 */
	CONSOLE,
	/**
	 * Grid Signal Unit: carries no energy either. It reads redstone into the segment (an
	 * external kill switch) or writes the segment's state back out as redstone.
	 */
	SIGNAL;

	private static final GridDeviceType[] VALUES = values();

	public String getName()
	{
		return this.toString().toLowerCase(Locale.ENGLISH);
	}

	/**
	 * @return true if this device participates in the per-tick energy pass at all
	 */
	public boolean movesEnergy()
	{
		return this==FEED||this==SERVICE;
	}

	/**
	 * Ordinal lookup that never throws -- unknown values (a save from a newer build, a
	 * corrupt tag) resolve to {@link #FEED} rather than crashing world load.
	 */
	public static GridDeviceType byIndex(int index)
	{
		return index >= 0&&index < VALUES.length?VALUES[index]: FEED;
	}
}
