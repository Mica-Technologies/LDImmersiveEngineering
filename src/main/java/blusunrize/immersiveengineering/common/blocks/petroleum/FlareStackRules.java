/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import javax.annotation.Nullable;

/**
 * What a Flare Stack will burn, how much of it, and how long the flame lasts.
 * <p>
 * <strong>Keyed by fluid name, which is what makes it testable.</strong> The rest of this feature
 * already keys its tables that way for the same reason -- there is no {@code FluidRegistry} in the
 * test JVM -- and the flare's acceptance rule is the one worth pinning hardest: it decides whether
 * getting rid of an unwanted fluid is free. A flare that would take crude turns cleaning up a spill
 * into a no-op, and turns the Gas Scrubber and the Re-injection Well from the answer to the gas
 * problem into two machines nobody needs to build.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class FlareStackRules
{
	private FlareStackRules()
	{
	}

	/**
	 * Millibuckets destroyed per delivery. Sized so a wellhead's gas stream is consumed as fast as
	 * it arrives and the well never backs up behind the flare.
	 */
	public static final int BURN_RATE = 200;

	/** City mode burns a token amount: the spectacle without the accounting. */
	public static final int CITY_SIP = 1;

	/** Light level of a lit flare. */
	public static final int LIT_LIGHT = 14;

	/**
	 * The only things a flare will take.
	 * <p>
	 * Gases, and nothing else. Kept as an explicit list rather than a "is it a gas" predicate
	 * because gaseousness is a property a pack author can set on any fluid, and this is a
	 * gameplay rule about <em>these three</em> rather than a physical claim.
	 */
	public static boolean isFlarable(@Nullable String fluidName)
	{
		if(fluidName==null)
			return false;
		return "ie_sour_gas".equals(fluidName)
				||"natural_gas".equals(fluidName)
				||"propane".equals(fluidName);
	}

	/**
	 * How much a delivery destroys.
	 *
	 * @param held     what is in the flare's small buffer
	 * @param cityMode see {@code CityMode.petroleum()}
	 *
	 * @return millibuckets consumed, never more than is held and never negative
	 */
	public static int burnAmount(int held, boolean cityMode)
	{
		if(held <= 0)
			return 0;
		return Math.min(held, cityMode?CITY_SIP: BURN_RATE);
	}

	/**
	 * @return the light a flare gives off, which is its entire return
	 */
	public static int lightValue(int flameTicks)
	{
		return flameTicks > 0?LIT_LIGHT: 0;
	}

	/**
	 * @return true while there is still flame to draw
	 */
	public static boolean isLit(int flameTicks)
	{
		return flameTicks > 0;
	}
}
