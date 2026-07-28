/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The client's copy of the fluid network, kept in step by {@code MessageFluidNetSync} while a
 * Fluid Control Console GUI is open.
 * <p>
 * It is a real {@link VirtualFluidNet} rather than a bespoke set of view objects, so the GUI reads
 * exactly the same model the server does and there is no second representation to keep honest.
 * Nothing on the client ever mutates it: every button round-trips through
 * {@code MessageFluidNetAction} and the next sync is the truth.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public final class ClientFluidNetCache
{
	private ClientFluidNetCache()
	{
	}

	private static final VirtualFluidNet CACHE = new VirtualFluidNet();
	private static boolean populated;

	public static VirtualFluidNet get()
	{
		return CACHE;
	}

	/**
	 * @return true once at least one sync has arrived; the GUI shows a "waiting" state until then
	 * rather than an empty network, which would look like data loss
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
