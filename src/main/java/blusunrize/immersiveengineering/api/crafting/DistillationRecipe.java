/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.crafting;

import com.google.common.collect.Lists;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One fractionation: a single fluid in, several fluids out at once.
 * <p>
 * This exists because nothing else in the mod does that. Every other recipe class here yields
 * at most one fluid, and a distillation column that could only emit one cut at a time would
 * either need running once per fraction -- turning one machine into six sequential ones -- or
 * would have to throw the rest away.
 * <p>
 * Cuts are declared in column order, lightest first, so the recipe reads the way the tower
 * looks: gas off the top, residue out of the bottom. The tower is expected to pull them out at
 * heights matching that order.
 * <p>
 * Recipes are deliberately batched small (a few dozen millibuckets per run) rather than
 * modelled as continuous flow. The existing multiblock process queue is discrete, well tested
 * and understood by JEI; the granularity is invisible in play, and a bespoke continuous
 * processor would be new infrastructure with none of those properties.
 * <p>
 * <strong>Not unit-tested, and cannot be.</strong> Every method here takes or returns a
 * {@code FluidStack}, whose constructor touches {@code FluidRegistry}, whose static
 * initialiser throws without a Forge bootstrap. The registration is covered by the server
 * smoke run instead, which loads the real registry.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class DistillationRecipe extends MultiblockRecipe
{
	public static float energyModifier = 1;
	public static float timeModifier = 1;

	public final FluidStack input;
	/**
	 * The cuts, lightest first. Never null and never empty.
	 */
	public final FluidStack[] outputs;

	public DistillationRecipe(FluidStack input, FluidStack[] outputs, int energy, int time)
	{
		this.input = input;
		this.outputs = outputs==null?new FluidStack[0]: outputs;
		this.totalProcessEnergy = (int)Math.floor(energy*energyModifier);
		this.totalProcessTime = (int)Math.floor(time*timeModifier);

		this.fluidInputList = Lists.newArrayList(this.input);
		this.fluidOutputList = Lists.newArrayList(this.outputs);
	}

	public static final ArrayList<DistillationRecipe> recipeList = new ArrayList<>();

	public static DistillationRecipe addRecipe(FluidStack input, FluidStack[] outputs, int energy,
											   int time)
	{
		DistillationRecipe recipe = new DistillationRecipe(input, outputs, energy, time);
		recipeList.add(recipe);
		return recipe;
	}

	/**
	 * @return the recipe this fluid feeds, or null. Matched by
	 * {@link FluidStack#containsFluid} so a partial tank still identifies the recipe it is
	 * working toward, exactly as the refinery does.
	 */
	public static DistillationRecipe findRecipe(FluidStack input)
	{
		if(input==null)
			return null;
		for(int i = 0; i < recipeList.size(); i++)
		{
			DistillationRecipe recipe = recipeList.get(i);
			if(recipe.input!=null&&input.containsFluid(recipe.input))
				return recipe;
		}
		return null;
	}

	/**
	 * @return every recipe this fluid could feed given enough of it, ignoring the amount
	 * present. Used by the GUI to show what a tank is working toward before it has enough.
	 */
	public static List<DistillationRecipe> findIncompleteRecipes(FluidStack input)
	{
		if(input==null)
			return Collections.emptyList();
		List<DistillationRecipe> out = Lists.newArrayList();
		for(int i = 0; i < recipeList.size(); i++)
		{
			DistillationRecipe recipe = recipeList.get(i);
			if(recipe.input!=null&&input.isFluidEqual(recipe.input))
				out.add(recipe);
		}
		return out;
	}

	/**
	 * @return the total millibuckets this recipe yields across every cut
	 */
	public int getTotalOutput()
	{
		int total = 0;
		for(int i = 0; i < outputs.length; i++)
			if(outputs[i]!=null)
				total += outputs[i].amount;
		return total;
	}

	@Override
	public int getMultipleProcessTicks()
	{
		return 0;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		if(input!=null)
			nbt.setTag("input", input.writeToNBT(new NBTTagCompound()));
		return nbt;
	}

	public static DistillationRecipe loadFromNBT(NBTTagCompound nbt)
	{
		return findRecipe(FluidStack.loadFluidStackFromNBT(nbt.getCompoundTag("input")));
	}
}
