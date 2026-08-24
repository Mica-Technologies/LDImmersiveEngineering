/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.blocks.fluidnet.TileEntityFluidNetDevice;
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
 * World-save persistence for the virtual fluid network.
 * <p>
 * Its own {@link WorldSavedData} file, for the same two reasons the grid has one: the pipe
 * network's save path is unrelated and already large, and keeping this in its own file means the
 * whole feature can be removed -- or its data deleted after a mishap -- without touching anything
 * else.
 * <p>
 * Attached to the overworld's map storage, because the network is server-wide rather than
 * per-dimension.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class FluidNetSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-FluidNetData";

	@Nullable
	private static FluidNetSaveData INSTANCE;

	public FluidNetSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		VirtualFluidNet.INSTANCE.readFromNBT(nbt);
		IELogger.info("Virtual fluid network loaded: "+VirtualFluidNet.INSTANCE.getMainCount()
				+" main(s), "+VirtualFluidNet.INSTANCE.getDeviceCount()+" device(s)");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return VirtualFluidNet.INSTANCE.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the network save data for this server and binds
	 * {@link VirtualFluidNet#setDirtyListener} to it.
	 * <p>
	 * Clears the in-memory network first: the handler is a process-global singleton, so without
	 * this, switching worlds in a single-player session would carry the previous world's mains
	 * over.
	 */
	public static void load(World world)
	{
		VirtualFluidNet.INSTANCE.clear();
		VirtualFluidNet.INSTANCE.setDirtyListener(null);

		FluidNetSaveData data = (FluidNetSaveData)world.loadData(FluidNetSaveData.class, dataName);
		if(data==null)
		{
			data = new FluidNetSaveData(dataName);
			world.setData(dataName, data);
			IELogger.info("Virtual fluid network data not found, starting empty");
		}
		setInstance(data);
		VirtualFluidNet.INSTANCE.setDirtyListener(FluidNetSaveData::setDirty);
		reattachLoadedDevices();
	}

	/**
	 * Re-registers every fitting whose chunk was already loaded when the save file was read.
	 * <p>
	 * The fluid network is the grid's sibling and had the grid's bug: see
	 * {@code GridSaveData.reattachLoadedDevices} for why this is needed and what it looked like
	 * without it.
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
			for(TileEntity te : new ArrayList<>(w.loadedTileEntityList))
				if(te instanceof TileEntityFluidNetDevice)
				{
					((TileEntityFluidNetDevice)te).attachToNet();
					count++;
				}
		}
		if(count > 0)
			IELogger.info("Virtual fluid network: re-attached "+count+" fitting(s) loaded before the save file");
	}

	public static void setInstance(@Nullable FluidNetSaveData instance)
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
