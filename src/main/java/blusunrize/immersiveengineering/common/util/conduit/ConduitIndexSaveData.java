/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitIndex;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nullable;

/**
 * World-save persistence for the conduit index, in its own file for the reason its siblings give: the
 * table can be deleted to reset the feature without touching wire data or anything else.
 * <p>
 * <strong>What deleting it costs.</strong> Every run whose middle is unloaded stops being findable
 * until somebody has travelled it once, in pieces or all at once, which is the same state the map was
 * in before this phase. Nothing is lost permanently and nothing breaks -- the conduit is still on the
 * walls, and every block of it writes itself back down the next time its chunk loads.
 * <p>
 * All dimensions live in one file, exactly as the virtual conduit's and the virtual grid's records do,
 * because the index is keyed by dimension internally and a per-dimension file would be four files
 * saying the same thing.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitIndexSaveData extends WorldSavedData
{
	public static final String dataName = "ImmersiveEngineering-ConduitIndex";

	@Nullable
	private static ConduitIndexSaveData INSTANCE;

	public ConduitIndexSaveData(String name)
	{
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		ConduitIndex.INSTANCE.readFromNBT(nbt);
		IELogger.info("Conduit index loaded: "+ConduitIndex.INSTANCE.size()+" block(s) of hardware");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		return ConduitIndex.INSTANCE.writeToNBT(nbt);
	}

	/**
	 * Loads (or creates) the save data and binds the index's dirty listener. Clears the index first so
	 * a second world in one session cannot inherit the previous one's conduit.
	 */
	public static void load(World world)
	{
		//	=================================
		//	The overworld's spawn chunks get here late
		//	=================================
		//This runs at FMLServerStartedEvent, and the overworld's spawn chunks have already loaded by
		//then -- so any conduit in them has already written itself down, and this clear throws that
		//away. Left as it is deliberately: the alternative is merging a file into a half-filled table,
		//and correctness against a *second world in one session* is worth more than tidiness here.
		//
		//It is harmless because spawn chunks stay loaded, so a walk reads them off the world and never
		//needs the table for them. The only visible artefact is `/ie conduitindex` counting a little
		//low until something in that area reloads. A pack that turns keepSpawnInMemory off gets the
		//entries back the first time a player walks there.
		ConduitIndex.INSTANCE.clear();
		//Unbound while the file is being read: readFromNBT rebuilds the table entry by entry through
		//the ordinary remember() path, and every one of those would otherwise mark as dirty a file
		//that is by definition in step with what is being read out of it.
		ConduitIndex.INSTANCE.setDirtyListener(null);
		ConduitIndexSaveData data =
				(ConduitIndexSaveData)world.loadData(ConduitIndexSaveData.class, dataName);
		if(data==null)
		{
			data = new ConduitIndexSaveData(dataName);
			world.setData(dataName, data);
		}
		if(FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE = data;
		ConduitIndex.INSTANCE.setDirtyListener(ConduitIndexSaveData::setDirty);
	}

	public static void setDirty()
	{
		if(INSTANCE!=null&&FMLCommonHandler.instance().getEffectiveSide()==Side.SERVER)
			INSTANCE.markDirty();
	}
}
