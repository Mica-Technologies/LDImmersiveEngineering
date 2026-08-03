/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.inventory;

import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * @author BluSunrize - 20.02.2017
 */
public class MultiFluidTank implements IFluidTank, IFluidHandler
{
	public ArrayList<FluidStack> fluids = new ArrayList<>();
	private final int capacity;

	public MultiFluidTank(int capacity)
	{
		this.capacity = capacity;
	}

	public MultiFluidTank readFromNBT(NBTTagCompound nbt)
	{
		if(nbt.hasKey("fluids"))
		{
			fluids.clear();
			NBTTagList tagList = nbt.getTagList("fluids", 10);
			for(int i = 0; i < tagList.tagCount(); i++)
			{
				FluidStack fs = FluidStack.loadFluidStackFromNBT(tagList.getCompoundTagAt(i));
				//Empties dropped on the way in, which clears the zero-amount entries older saves
				//accumulated from filling a full tank. Nothing writes them any more, but a world
				//that already has them should shed them rather than carry them forever.
				if(fs!=null&&fs.amount > 0)
					this.fluids.add(fs);
			}
		}
		return this;
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList tagList = new NBTTagList();
		for(FluidStack fs : this.fluids)
			if(fs!=null)
				tagList.appendTag(fs.writeToNBT(new NBTTagCompound()));
		nbt.setTag("fluids", tagList);
		return nbt;
	}

	public int getFluidTypes()
	{
		return fluids.size();
	}

	@Nullable
	@Override
	public FluidStack getFluid()
	{
		//grabbing the last fluid, for output reasons
		return fluids.size() > 0?fluids.get(fluids.size()-1): null;
	}

	@Override
	public int getFluidAmount()
	{
		int sum = 0;
		for(FluidStack fs : fluids)
			sum += fs.amount;
		return sum;
	}

	@Override
	public int getCapacity()
	{
		return this.capacity;
	}

	@Override
	public FluidTankInfo getInfo()
	{
		FluidStack fs = getFluid();
		int capacity = this.capacity-getFluidAmount();
		if(fs!=null)
			capacity += fs.amount;
		return new FluidTankInfo(fs, capacity);
	}

	@Override
	public IFluidTankProperties[] getTankProperties()
	{
		//	=================================
		//	An empty array means "I hold nothing you can see".
		//	=================================
		//
		// This is the only window external automation has into a tank. Returning nothing left every
		// pipe, storage bus and probe reading this block convinced it was empty however full it
		// was -- and, worse, unable to tell that it would accept a fill.
		//
		// One entry per fluid held, all sharing the one capacity, because that is what this tank is:
		// several fluids in one space rather than several tanks. An empty tank still reports one
		// entry so its capacity and its willingness to be filled are both visible.
		if(fluids.isEmpty())
			return new IFluidTankProperties[]{new FluidTankProperties(null, capacity, true, true)};
		IFluidTankProperties[] properties = new IFluidTankProperties[fluids.size()];
		for(int i = 0; i < fluids.size(); i++)
			properties[i] = new FluidTankProperties(fluids.get(i), capacity, true, true);
		return properties;
	}

	@Override
	public int fill(FluidStack resource, boolean doFill)
	{
		int space = this.capacity-getFluidAmount();
		int toFill = Math.min(resource.amount, space);
		if(!doFill)
			return toFill;
		//	=================================
		//	Nothing moved means nothing stored.
		//	=================================
		//
		// Without this, pushing a fluid at a full tank fell through to the add below and stored a
		// zero-amount stack -- which writeToNBT then serialised, permanently. Every distinct fluid
		// anybody tried against a full tank added another entry, and nothing ever removed them: a
		// save file growing without bound from an operation that visibly did nothing.
		if(toFill <= 0)
			return 0;
		for(FluidStack fs : this.fluids)
			if(fs.isFluidEqual(resource))
			{
				fs.amount += toFill;
				return toFill;
			}
		this.fluids.add(Utils.copyFluidStackWithAmount(resource, toFill, true));
		return toFill;

	}

	@Nullable
	@Override
	public FluidStack drain(FluidStack resource, boolean doDrain)
	{
		if(this.fluids.isEmpty())
			return null;
		Iterator<FluidStack> it = this.fluids.iterator();
		while(it.hasNext())
		{
			FluidStack fs = it.next();
			if(fs.isFluidEqual(resource))
			{
				int amount = Math.min(resource.amount, fs.amount);
				if(doDrain)
				{
					fs.amount -= amount;
					if(fs.amount <= 0)
						it.remove();
				}
				return Utils.copyFluidStackWithAmount(resource, amount, true);
			}
		}
		return null;
	}

	public static FluidStack drain(int remove, FluidStack removeFrom, Iterator<FluidStack> removeIt, boolean doDrain)
	{
		int amount = Math.min(remove, removeFrom.amount);
		if(doDrain)
		{
			removeFrom.amount -= amount;
			if(removeFrom.amount <= 0)
				removeIt.remove();
		}
		return Utils.copyFluidStackWithAmount(removeFrom, amount, true);
	}

	@Nullable
	@Override
	public FluidStack drain(int maxDrain, boolean doDrain)
	{
		if(this.fluids.isEmpty())
			return null;
		return drain(new FluidStack(getFluid(), maxDrain), doDrain);
	}
}
