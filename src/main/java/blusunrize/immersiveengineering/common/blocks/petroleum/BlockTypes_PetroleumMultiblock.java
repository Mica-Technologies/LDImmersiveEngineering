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
 * Metas of the {@code petroleum_multiblock} block: assembled oilfield structures.
 * <p>
 * Its own block rather than more metas on {@code metal_multiblock}, which holds exactly 16
 * values and cannot take a seventeenth -- block metadata is 4 bits. Metas are persisted in
 * world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public enum BlockTypes_PetroleumMultiblock implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Sinks a bore and leaves a Wellhead behind, then comes apart to travel to the next site.
	 */
	DERRICK,
	/**
	 * Drives a Wellhead whose deposit has lost the pressure to flow on its own.
	 */
	PUMPJACK,
	/**
	 * Splits crude into its cuts, drawn off at heights matching the column order.
	 */
	DISTILLATION_TOWER,
	/**
	 * Burns the fuels nothing else wants, for process heat rather than flux.
	 */
	INDUSTRIAL_BURNER,
	/**
	 * Cleans raw wellhead gas, dropping sulfur out of it.
	 */
	GAS_SCRUBBER,
	/**
	 * Burns gas for flux, with a spool-up that makes it a peaker rather than a base load.
	 */
	GAS_TURBINE,
	/**
	 * Burns the heavy fuels for steam rather than flux -- the furnace half of a power station.
	 */
	FUEL_OIL_BOILER,
	/**
	 * Makes steam from a Gas Turbine's exhaust heat, consuming no fuel of its own.
	 */
	HRSG,
	/**
	 * Turns steam into flux at the largest scale the expansion offers.
	 */
	STEAM_TURBINE_HALL,
	/**
	 * Cylinder banks that scale by building another one alongside.
	 */
	ENGINE_BANK,
	/**
	 * One house's worth of fuel, under the garden.
	 */
	DOMESTIC_TANK,
	/**
	 * A forecourt's or a workshop's supply, under the yard.
	 */
	COMMERCIAL_TANK,
	/**
	 * A refinery's or a town's stock, under the ground it stands on.
	 */
	BULK_DEPOT,
	/**
	 * Fills portable containers in bulk: the refinery's truck bay.
	 */
	LOADING_GANTRY,
	/**
	 * Breaks the heavy cuts into the light ones, at whatever severity its firebox pays for.
	 */
	CRACKING_UNIT;

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
		//Assembled with a hammer, never taken from the creative list.
		return false;
	}
}
