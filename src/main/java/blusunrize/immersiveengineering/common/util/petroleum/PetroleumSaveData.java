/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.petroleum;

import blusunrize.immersiveengineering.api.petroleum.ReservoirHandler;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;

/**
 * World-save persistence for oil reservoirs.
 * <p>
 * A separate {@link WorldSavedData} from {@code IESaveData} for the same reasons the virtual
 * grid keeps its own: the wire save path is hot and already large, and a self-contained file
 * means the whole feature can be removed -- or its data deleted after a mishap -- without
 * touching anything else.
 * <p>
 * Only cells that have actually been drawn from are written. Everything else re-rolls
 * identically from the world seed, so saving it would be pure noise; see
 * {@link ReservoirHandler#getDirtyCells()}. On a world nobody has drilled yet this file is
 * essentially empty.
 * <p>
 * Attached to the overworld's map storage: deposits are keyed by dimension internally, so one
 * file covers the server rather than one per dimension.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class PetroleumSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-PetroleumData";

	@Nullable
	private static PetroleumSaveData INSTANCE;

	public PetroleumSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		ReservoirHandler.readFromNBT(nbt);
		IELogger.info("Petroleum data loaded: "+ReservoirHandler.getCachedCellCount()
				+" worked reservoir(s)");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return ReservoirHandler.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the reservoir data for this server.
	 * <p>
	 * Clears the in-memory map first: the handler is a process-global singleton, so without
	 * this a second world in one session would inherit the previous world's depletion -- the
	 * same trap {@code serverStarted} avoids for wires and for the grid.
	 */
	public static void load(World world)
	{
		ReservoirHandler.clear();

		PetroleumSaveData data = (PetroleumSaveData)world.loadData(PetroleumSaveData.class, dataName);
		if(data==null)
		{
			data = new PetroleumSaveData(dataName);
			world.setData(dataName, data);
			IELogger.info("Petroleum data not found, starting fresh");
		}
		setInstance(data);
	}

	public static void setInstance(@Nullable PetroleumSaveData instance)
	{
		if(FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE = instance;
	}

	public static void setDirty()
	{
		if(INSTANCE!=null&&FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE.markDirty();
	}
}
