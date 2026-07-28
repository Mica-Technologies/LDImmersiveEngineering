/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

/**
 * The petroleum item's sub-names, in meta order, kept free of any dependency on a running game.
 * <p>
 * {@link ItemPetroleum} cannot hold this on its own: constructing it touches the creative tab and
 * the item registry, so nothing outside a running game can ask it what metas it defines -- and
 * "which metas exist" is exactly what the asset test needs to know to catch a recipe that produces
 * an item no enum defines. The propane cylinder shipped that bug once already, in an otherwise
 * green build.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class PetroleumItemNames
{
	private PetroleumItemNames()
	{
	}

	public static final String[] SUB_NAMES = {
			"nozzle", "drill_pipe", "blowout_preventer", "absorbent_pad", "petcoke", "survey_kit"
	};
}
