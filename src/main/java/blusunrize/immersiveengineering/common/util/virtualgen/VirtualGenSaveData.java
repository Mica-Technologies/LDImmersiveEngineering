/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.virtualgen;

import blusunrize.immersiveengineering.api.energy.grid.GridEngine;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGeneration;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;

/**
 * World-save persistence for metered plants, in its own file for the reasons {@code GridSaveData} gives:
 * the feature can be removed, or its data deleted, without touching wire data.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class VirtualGenSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-VirtualGenData";

	@Nullable
	private static VirtualGenSaveData INSTANCE;

	public VirtualGenSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		VirtualGeneration.INSTANCE.readFromNBT(nbt);
		IELogger.info("Virtual generation loaded: "+VirtualGeneration.INSTANCE.size()+" metered plant(s)");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return VirtualGeneration.INSTANCE.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the save data and binds the registry's dirty listener. Clears the registry first so
	 * a second world in one session cannot inherit the previous one's plants.
	 */
	public static void load(World world)
	{
		VirtualGeneration.INSTANCE.clear();
		VirtualGeneration.INSTANCE.setDirtyListener(null);
		VirtualGenSaveData data = (VirtualGenSaveData)world.loadData(VirtualGenSaveData.class, dataName);
		if(data==null)
		{
			data = new VirtualGenSaveData(dataName);
			world.setData(dataName, data);
		}
		if(FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE = data;
		VirtualGeneration.INSTANCE.setDirtyListener(VirtualGenSaveData::setDirty);
		//Let unloaded, metered Feed Units keep supplying their segments.
		GridEngine.virtualFeeds = VirtualGeneration.INSTANCE.gridFeedSupply();
	}

	public static void setDirty()
	{
		if(INSTANCE!=null&&FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE.markDirty();
	}
}
