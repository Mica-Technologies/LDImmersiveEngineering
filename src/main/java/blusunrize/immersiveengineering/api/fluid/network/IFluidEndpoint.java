/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import javax.annotation.Nullable;

/**
 * The only thing the network's tick engine is allowed to know about a world block.
 * <p>
 * Inlet and Outlet tile entities implement this; the engine ({@link FluidNetEngine}) talks to
 * nothing else. That keeps every rule the engine enforces -- caps, line pack, leakage,
 * priorities, load shedding, failover walks, city-mode presence -- free of {@code World} and
 * {@code TileEntity}, and therefore unit-testable against fakes.
 * <p>
 * The one thing this interface has that {@code IGridEndpoint} does not is the fluid's name.
 * Electricity is fungible and millibuckets are not, so every transfer names what is moving and
 * an endpoint holding the wrong thing simply reports nothing. That is a type check rather than
 * an architecture: everything else about the two interfaces is the same shape on purpose.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public interface IFluidEndpoint
{
	/**
	 * What this endpoint could put into an <em>untyped</em> main, so the engine knows what to
	 * type it as.
	 * <p>
	 * Only consulted for a main that has not yet decided what it carries. Once a main is typed
	 * this is never called again, which is what stops an inlet holding the wrong fluid from
	 * quietly re-typing a live main out from under everything connected to it.
	 *
	 * @return the fluid's registry name, or null if there is nothing to offer
	 */
	@Nullable
	default String getOfferedFluid()
	{
		return null;
	}

	/**
	 * Take fluid <em>out of the world</em> and hand it to the main. Called on INLET devices.
	 *
	 * @param fluid    the registry name of the fluid the main carries; never null
	 * @param max      upper bound in millibuckets the engine is willing to accept this tick,
	 *                 always &gt;= 0
	 * @param simulate if true, report what would move without moving it
	 * @return the amount actually available/removed, never more than {@code max}
	 */
	int extractForMain(String fluid, int max, boolean simulate);

	/**
	 * Push fluid <em>into the world</em> on the main's behalf. Called on OUTLET devices.
	 *
	 * @param fluid    the registry name of the fluid the main carries; never null
	 * @param max      upper bound in millibuckets the engine is willing to supply this tick,
	 *                 always &gt;= 0
	 * @param simulate if true, report what would be accepted without delivering it
	 * @return the amount the world actually accepted, never more than {@code max}
	 */
	int insertFromMain(String fluid, int max, boolean simulate);

	/**
	 * Called when the device's configuration changed (transfer cap, enablement, main assignment,
	 * the main's fluid, or the city-mode flag flipping) so the endpoint can resize buffers.
	 */
	default void onNetConfigChanged(FluidDevice device)
	{
	}

	/**
	 * Read the world's redstone state at this device. Called on VALVE devices in input mode,
	 * once per tick, before anything else happens.
	 *
	 * @return true if the block is receiving a redstone signal
	 */
	default boolean isRedstoneHigh()
	{
		return false;
	}

	/**
	 * Publish a redstone level to the world. Called on VALVE devices in output mode at the end of
	 * every tick; implementations are expected to be change-gated, because this arrives whether
	 * or not anything moved.
	 *
	 * @param level 0-15
	 */
	default void setRedstoneOutput(int level)
	{
	}
}
