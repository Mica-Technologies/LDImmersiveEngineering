/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridDevice;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;
import java.util.ArrayList;

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
		reattachLoadedDevices();
	}

	/**
	 * Re-registers every Feed, Service and Signal Unit whose chunk was already loaded when the
	 * save file was read.
	 * <p>
	 * This runs from {@code FMLServerStartedEvent}, which on a dedicated server fires <em>after</em>
	 * "Preparing start region" -- so every box in the spawn region has already had {@code onLoad}
	 * called and has already attached itself. Reading the file then clears the registry and
	 * rebuilds each record from NBT with a null endpoint, throwing those attachments away, and
	 * {@code onLoad} does not fire again for a chunk that is already loaded. Without this the whole
	 * grid comes up offline after every restart and stays that way until each box is broken and
	 * replaced by hand, which is exactly what it looked like: devices listed on the segment,
	 * nothing ever energised, nothing ever lit.
	 * <p>
	 * Single player hid it, because there you tend to place the boxes during the session, after the
	 * file has been read.
	 */
	private static void reattachLoadedDevices()
	{
		MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
		if(server==null)
			return;
		int count = 0;
		for(WorldServer w : server.worlds)
		{
			if(w==null)
				continue;
			//Copied, because attaching touches the grid rather than the world's tile entity list,
			//but a mod that reacts to it must not be able to turn this into a comodification.
			for(TileEntity te : new ArrayList<>(w.loadedTileEntityList))
				if(te instanceof TileEntityGridDevice)
				{
					((TileEntityGridDevice)te).attachToGrid();
					count++;
				}
		}
		if(count > 0)
			IELogger.info("Virtual grid: re-attached "+count+" device(s) loaded before the save file");
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
