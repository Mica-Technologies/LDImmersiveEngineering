/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;

/**
 * World-save persistence for the virtual grid.
 * <p>
 * Deliberately a separate {@link WorldSavedData} from {@code IESaveData} rather than
 * another tag inside it. Two reasons: the wire network's save path is hot and already
 * large, and keeping the grid in its own file means the whole feature can be removed
 * (or its data deleted after a mishap) without touching wire data.
 * <p>
 * Like {@code IESaveData} this is attached to the overworld's map storage, because the
 * grid is server-wide rather than per-dimension.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class GridSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-GridData";

	@Nullable
	private static GridSaveData INSTANCE;

	public GridSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		VirtualGrid.INSTANCE.readFromNBT(nbt);
		IELogger.info("Virtual grid loaded: "+VirtualGrid.INSTANCE.getSegmentCount()+" segment(s), "
				+VirtualGrid.INSTANCE.getDeviceCount()+" device(s)");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return VirtualGrid.INSTANCE.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the grid save data for this server and binds
	 * {@link VirtualGrid#setDirtyListener} to it.
	 * <p>
	 * Clears the in-memory grid first: the handler is a process-global singleton, so
	 * without this, switching worlds in a single-player session would carry the previous
	 * world's segments over -- the same trap {@code serverStarted} avoids for wires.
	 */
	public static void load(World world)
	{
		VirtualGrid.INSTANCE.clear();
		VirtualGrid.INSTANCE.setDirtyListener(null);

		GridSaveData data = (GridSaveData)world.loadData(GridSaveData.class, dataName);
		if(data==null)
		{
			data = new GridSaveData(dataName);
			world.setData(dataName, data);
			IELogger.info("Virtual grid data not found, starting empty");
		}
		setInstance(data);
		VirtualGrid.INSTANCE.setDirtyListener(GridSaveData::setDirty);
	}

	public static void setInstance(@Nullable GridSaveData instance)
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
