/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;

/**
 * The world, in the shape {@link VirtualConduitEngine} and {@link VirtualConduits} ask for.
 * <p>
 * Behind an interface for the reason {@code IVirtualGenWorld} is: the decisions worth getting right
 * -- who is skipped, what is offered, what counts as live, what happens when a box comes back -- are
 * then testable with no Minecraft under them, and the awkward half is one small class that does
 * nothing but translate.
 * <p>
 * <strong>Nothing here may load a chunk.</strong> Every query is a loaded-check first, and the two
 * that have no loaded-check -- {@link #bundleNeighbours} and the push -- read the wire graph, which
 * is global, saved with the world and entirely independent of what is in memory. A feature built to
 * work while the world is unloaded, which quietly loaded it, would be a chunk loader with extra
 * steps -- and this mod's whole performance argument is about not being one.
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
	 * @return true if there is still a junction box at that position with that outlet on it. Only
	 * asked about a loaded position -- see {@link #isLoaded} -- because an unloaded one cannot be
	 * checked, and is exactly the case the record exists to cover.
	 */
	boolean stillBreaksOut(int dimension, BlockPos boxPos, VirtualConduitLink link);

	/**
	 * The boxes one box shares a run with, one hop away.
	 * <p>
	 * Read from the global wire graph rather than from the world: a bundle is a connection between
	 * two boxes, saved with the world's wire data, and the hundred conduit blocks in between are not
	 * nodes at all. So this answers with every block of the run unloaded, which is what makes the
	 * liveness flood possible in the first place.
	 *
	 * @param channel only bundles carrying this conductor count. Channel separation has to survive
	 *                the flood or a lighting circuit's feed would vouch for a workshop circuit.
	 */
	Collection<BlockPos> bundleNeighbours(int dimension, BlockPos boxPos, WireChannel channel);

	/**
	 * Push into the wire network hanging off one outlet, as the loaded box would.
	 * <p>
	 * Where the push starts from and whether it is filtered are the outlet's own business -- see
	 * {@link VirtualConduitLink.Kind} -- which is why the whole record is handed over rather than
	 * four loose arguments.
	 *
	 * @return how much the network took
	 */
	int push(VirtualConduitLink link);
}
