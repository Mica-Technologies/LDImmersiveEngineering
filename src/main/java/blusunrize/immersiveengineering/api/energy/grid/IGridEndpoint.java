/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

/**
 * The only thing the grid's tick engine is allowed to know about a world block.
 * <p>
 * Feed and Service tile entities implement this; the engine ({@code GridTickHandler})
 * talks to nothing else. That keeps every rule the engine enforces -- caps, buffers,
 * loss, priorities, load shedding, failover, city-mode presence -- free of
 * {@code World}/{@code TileEntity} and therefore unit-testable against fakes.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public interface IGridEndpoint
{
	/**
	 * Take energy <em>out of the world</em> and hand it to the grid. Called on FEED devices.
	 *
	 * @param max      upper bound the engine is willing to accept this tick, always &gt;= 0
	 * @param simulate if true, report what would move without moving it
	 * @return the amount actually available/removed, never more than {@code max}
	 */
	int extractForGrid(int max, boolean simulate);

	/**
	 * Push energy <em>into the world</em> on the grid's behalf. Called on SERVICE devices.
	 *
	 * @param max      upper bound the engine is willing to supply this tick, always &gt;= 0
	 * @param simulate if true, report what would be accepted without delivering it
	 * @return the amount the world actually accepted, never more than {@code max}
	 */
	int insertFromGrid(int max, boolean simulate);

	/**
	 * Called when the device's configuration changed (transfer cap, enablement, segment
	 * assignment, or the city-mode flag flipping) so the endpoint can resize buffers.
	 */
	default void onGridConfigChanged(GridDevice device)
	{
	}

	/**
	 * Read the world's redstone state at this device. Called on SIGNAL devices in input
	 * mode, once per tick, before anything else happens.
	 *
	 * @return true if the block is receiving a redstone signal
	 */
	default boolean isRedstoneHigh()
	{
		return false;
	}

	/**
	 * Publish a redstone level to the world. Called on SIGNAL devices in output mode at
	 * the end of every tick; implementations are expected to be change-gated, because this
	 * arrives whether or not anything moved.
	 *
	 * @param level 0-15
	 */
	default void setRedstoneOutput(int level)
	{
	}
}
