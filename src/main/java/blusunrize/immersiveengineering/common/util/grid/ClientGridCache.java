/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The client's copy of the grid, kept in step by {@code MessageGridSync} while a Grid
 * Management Console GUI is open.
 * <p>
 * It is a real {@link VirtualGrid} rather than a bespoke set of view objects, so the GUI
 * reads exactly the same model the server does and there is no second representation to
 * keep honest. Nothing on the client ever mutates it: every button round-trips through
 * {@code MessageGridAction} and the next sync is the truth.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public final class ClientGridCache
{
	private ClientGridCache()
	{
	}

	private static final VirtualGrid CACHE = new VirtualGrid();
	private static boolean populated;

	public static VirtualGrid get()
	{
		return CACHE;
	}

	/**
	 * @return true once at least one sync has arrived; the GUI shows a "waiting" state
	 * until then rather than an empty grid, which would look like data loss
	 */
	public static boolean isPopulated()
	{
		return populated;
	}

	public static void accept(NBTTagCompound nbt)
	{
		CACHE.readFromNBT(nbt);
		populated = true;
	}

	public static void clear()
	{
		CACHE.clear();
		populated = false;
	}
}
