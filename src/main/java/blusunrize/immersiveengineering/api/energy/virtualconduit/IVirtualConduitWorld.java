/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import net.minecraft.util.math.BlockPos;

/**
 * The world, in the shape {@link VirtualConduitEngine} asks for.
 * <p>
 * Behind an interface for the reason {@code IVirtualGenWorld} is: the decisions worth getting right
 * -- who is skipped, what is offered, what happens when a box comes back -- are then testable with
 * no Minecraft under them, and the awkward half is one small class that does nothing but translate.
 * <p>
 * <strong>Nothing here may load a chunk.</strong> Every query is a loaded-check first. A feature
 * built to work while the world is unloaded, which quietly loaded it, would be a chunk loader with
 * extra steps -- and this mod's whole performance argument is about not being one.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public interface IVirtualConduitWorld
{
	boolean isDimensionLoaded(int dimension);

	/**
	 * @return true if that position can be read without loading anything
	 */
	boolean isLoaded(int dimension, BlockPos pos);

	/**
	 * @return true if there is still a junction box at that position with that conductor broken out
	 * onto the wire named. Only asked about a loaded position -- see {@link #isLoaded} -- because an
	 * unloaded one cannot be checked, and is exactly the case the record exists to cover.
	 */
	boolean stillBreaksOut(int dimension, BlockPos boxPos, VirtualConduitLink link);

	/**
	 * Push into the wire network hanging off one breakout face, as the loaded box would.
	 *
	 * @param boxPos       where the junction box is -- the push's origin, which needs no tile entity
	 *                     because the wire graph is global and proxies cover the poles
	 * @param amount       what that conductor offers
	 * @param wireTypeName the wire on the face, so the push stays on this conductor's own circuit
	 * @param wireEnd      the node at that wire's far end, the other half of the same filter
	 *
	 * @return how much the network took
	 */
	int push(int dimension, BlockPos boxPos, int amount, String wireTypeName, BlockPos wireEnd);
}
