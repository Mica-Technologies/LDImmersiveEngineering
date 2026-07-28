/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code petroleum_device} block: the placeable, non-assembled oilfield parts.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public enum BlockTypes_PetroleumDevice implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Caps a drilled bore and collects what comes up it.
	 */
	WELLHEAD,
	/**
	 * Structural block the Drilling Derrick and Pumpjack are built from.
	 */
	OILFIELD_FRAME,
	/**
	 * Burns off gas nobody has anywhere better to put.
	 */
	FLARE_STACK,
	/**
	 * Keeps an adjacent machine greased, so lubricant has somewhere to go.
	 */
	LUBRICATION_MANIFOLD,
	/**
	 * A bottle of propane you fill somewhere and carry to wherever it is needed.
	 */
	PROPANE_CYLINDER,
	/**
	 * The one fitting a buried tank shows above ground: what a pipe connects to, what a comparator
	 * reads, and the whole of the tank's gauge.
	 */
	TANK_FILL_CAP,
	/**
	 * The forecourt bowser: hands fuel to a person rather than to a machine.
	 */
	GAS_PUMP,
	/**
	 * The price board, read from the road.
	 */
	FORECOURT_SIGN,
	/**
	 * A generator you carry to the job and refuel at the pump.
	 */
	PORTABLE_GENERATOR,
	/**
	 * Puts water or gas back downhole to get a second tranche out of a tiring field.
	 */
	REINJECTION_WELL,
	/**
	 * Loose steel until a hollow box of it is hammered into a Storage Tank.
	 */
	STORAGE_TANK_WALL;

	@Override
	public String getName()
	{
		return this.toString().toLowerCase(Locale.ENGLISH);
	}

	@Override
	public int getMeta()
	{
		return ordinal();
	}

	@Override
	public boolean listForCreative()
	{
		//The wellhead is listed too: a creative build should not have to drill for one.
		return true;
	}
}
