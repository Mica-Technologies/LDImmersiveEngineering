/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import net.minecraft.util.math.BlockPos;

/**
 * Everything {@link VirtualGenEngine} needs from the world, and nothing more.
 * <p>
 * The same seam the grid's {@code IGridEndpoint} gives its engine: the decision of what to push, from
 * where and when is pure logic and is tested against a fake; the one implementation that touches a real
 * world lives in {@code common}.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public interface IVirtualGenWorld
{
	/**
	 * @return whether the dimension is loaded at all. An unloaded dimension has no loaded consumers, so
	 * nothing in it is pushed.
	 */
	boolean isDimensionLoaded(int dimension);

	/**
	 * @return whether the chunk holding {@code pos} is loaded. Must never load it.
	 */
	boolean isLoaded(int dimension, BlockPos pos);

	/**
	 * Pushes up to {@code amount} onto the wire network from {@code source}, exactly as that connector's
	 * own tick would, reaching only loaded consumers.
	 *
	 * @param rate the connector's rate, for the normal-mode loss curve
	 * @return how much was delivered
	 */
	int push(int dimension, BlockPos source, int amount, int rate, boolean cityMode);
}
