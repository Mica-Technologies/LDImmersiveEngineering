/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import java.util.Locale;

/**
 * One kind of deposit that can be rolled underground: what it holds, how likely it is, and how
 * big it gets.
 * <p>
 * The fluid is held <strong>by name</strong> rather than as a {@code Fluid} reference, for the
 * same reason {@code DieselHandler} keys by name: a reservoir type may be declared before the
 * fluid registry has settled, and a name never goes stale. It also keeps this class free of
 * anything that cannot be constructed in a test JVM.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class ReservoirType
{
	private final String name;
	private final String fluidName;
	private final int weight;
	private final int minCapacity;
	private final int maxCapacity;

	/**
	 * @param name        unique identifier, also the lang key suffix
	 * @param fluidName   registry name of the fluid this deposit yields
	 * @param weight      relative likelihood against every other registered type
	 * @param minCapacity smallest roll, in mB
	 * @param maxCapacity largest roll, in mB
	 */
	public ReservoirType(String name, String fluidName, int weight, int minCapacity, int maxCapacity)
	{
		this.name = name==null?"": name.toLowerCase(Locale.ENGLISH);
		this.fluidName = fluidName;
		this.weight = Math.max(0, weight);
		//Tolerate the bounds being handed over the wrong way round rather than rolling a
		//negative-width range later, where the cause would be far from the symptom.
		this.minCapacity = Math.max(0, Math.min(minCapacity, maxCapacity));
		this.maxCapacity = Math.max(0, Math.max(minCapacity, maxCapacity));
	}

	public String getName()
	{
		return name;
	}

	public String getFluidName()
	{
		return fluidName;
	}

	public int getWeight()
	{
		return weight;
	}

	public int getMinCapacity()
	{
		return minCapacity;
	}

	public int getMaxCapacity()
	{
		return maxCapacity;
	}

	@Override
	public String toString()
	{
		return "ReservoirType["+name+" -> "+fluidName+", weight "+weight
				+", "+minCapacity+"-"+maxCapacity+" mB]";
	}
}
