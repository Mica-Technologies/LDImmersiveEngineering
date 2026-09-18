/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.virtualconduit;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduits;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;

/**
 * World-save persistence for conduit breakouts that were delivering into a wire, in its own file for
 * the reason {@code VirtualGenSaveData} gives: the feature can be removed, or its data deleted,
 * without touching wire data. Deleting it costs nothing more than a run rediscovering what it
 * carries the next time somebody stands near it.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduitSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-VirtualConduitData";

	@Nullable
	private static VirtualConduitSaveData INSTANCE;

	public VirtualConduitSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		VirtualConduits.INSTANCE.readFromNBT(nbt);
		IELogger.info("Virtual conduit loaded: "+VirtualConduits.INSTANCE.size()+" outlet(s), "
				+VirtualConduits.INSTANCE.feedCount()+" feed(s)");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return VirtualConduits.INSTANCE.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the save data and binds the registry's dirty listener. Clears the registry
	 * first so a second world in one session cannot inherit the previous one's runs.
	 */
	public static void load(World world)
	{
		VirtualConduits.INSTANCE.clear();
		VirtualConduits.INSTANCE.setDirtyListener(null);
		//The registry answers "is this conductor live" from the wire graph, so it needs the same
		//view of the world the engine pushes through. Handed over here rather than in a static
		//initialiser so that it is set once per world load, next to the table it applies to.
		VirtualConduits.INSTANCE.setWorld(VirtualConduitTickHandler.port());
		VirtualConduitSaveData data =
				(VirtualConduitSaveData)world.loadData(VirtualConduitSaveData.class, dataName);
		if(data==null)
		{
			data = new VirtualConduitSaveData(dataName);
			world.setData(dataName, data);
		}
		if(FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE = data;
		VirtualConduits.INSTANCE.setDirtyListener(VirtualConduitSaveData::setDirty);
	}

	public static void setDirty()
	{
		if(INSTANCE!=null&&FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE.markDirty();
	}
}
