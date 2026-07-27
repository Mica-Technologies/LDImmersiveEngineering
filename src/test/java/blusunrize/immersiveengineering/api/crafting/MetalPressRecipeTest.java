/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.crafting;

import blusunrize.immersiveengineering.api.ComparableItemStack;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link MetalPressRecipe}'s config-driven time/energy figures and its input-size
 * handling. The mould-keyed multimap is keyed by {@link ComparableItemStack}, whose hash
 * dereferences the item, so registration and lookup need a bootstrapped registry and are
 * not covered.
 */
class MetalPressRecipeTest
{
	private float savedEnergyModifier;
	private float savedTimeModifier;

	@BeforeEach
	void saveModifiers()
	{
		savedEnergyModifier = MetalPressRecipe.energyModifier;
		savedTimeModifier = MetalPressRecipe.timeModifier;
		MetalPressRecipe.energyModifier = 1;
		MetalPressRecipe.timeModifier = 1;
	}

	@AfterEach
	void restoreModifiers()
	{
		MetalPressRecipe.energyModifier = savedEnergyModifier;
		MetalPressRecipe.timeModifier = savedTimeModifier;
	}

	private static ComparableItemStack mould()
	{
		// matchOre=false and copy=false keep the ore dictionary and ItemStack.copy() out of it
		return new ComparableItemStack(ItemStack.EMPTY, false, false);
	}

	private static MetalPressRecipe make(String input, int energy)
	{
		return new MetalPressRecipe(ItemStack.EMPTY, input, mould(), energy);
	}

	@Test
	@DisplayName("the press takes a flat 120 ticks and the requested energy")
	void baseTimeAndEnergy()
	{
		MetalPressRecipe r = make("ingotIron", 2400);

		assertEquals(120, r.getTotalProcessTime());
		assertEquals(2400, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("the modifiers scale time and energy independently")
	void modifiersScaleIndependently()
	{
		MetalPressRecipe.energyModifier = 2;
		MetalPressRecipe.timeModifier = .5f;
		MetalPressRecipe r = make("ingotIron", 2400);

		assertEquals(60, r.getTotalProcessTime());
		assertEquals(4800, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("fractional modifier results are floored")
	void modifiersAreFloored()
	{
		MetalPressRecipe.timeModifier = .33f;
		MetalPressRecipe.energyModifier = 1.5f;
		MetalPressRecipe r = make("ingotIron", 101);

		assertEquals(39, r.getTotalProcessTime(), "floor(120*0.33) = floor(39.6) = 39");
		assertEquals(151, r.getTotalProcessEnergy(), "floor(101*1.5) = floor(151.5) = 151");
	}

	@Test
	@DisplayName("the mould is stored on the recipe")
	void mouldIsStored()
	{
		ComparableItemStack mould = mould();
		MetalPressRecipe r = new MetalPressRecipe(ItemStack.EMPTY, "ingotIron", mould, 100);

		assertSame(mould, r.mold);
	}

	@Test
	@DisplayName("the input starts at a size of one and setInputSize is chainable")
	void setInputSizeIsChainable()
	{
		MetalPressRecipe r = make("ingotIron", 100);
		assertEquals(1, r.input.inputSize);

		assertSame(r, r.setInputSize(4));
		assertEquals(4, r.input.inputSize);
	}

	@Test
	@DisplayName("setInputSize also shows up through the shared input list")
	void setInputSizeIsVisibleThroughTheInputList()
	{
		MetalPressRecipe r = make("ingotIron", 100);
		r.setInputSize(9);

		assertEquals(1, r.getItemInputs().size());
		assertEquals(9, r.getItemInputs().get(0).inputSize);
	}

	@Test
	@DisplayName("matches ignores the mould and defers to the input ingredient")
	void matchesDefersToTheIngredient()
	{
		MetalPressRecipe r = make("ingotIron", 100);

		assertFalse(r.matches(ItemStack.EMPTY, ItemStack.EMPTY), "an empty input can never match");
	}

	@Test
	@DisplayName("getActualRecipe hands back the recipe itself by default")
	void getActualRecipeIsIdentity()
	{
		MetalPressRecipe r = make("ingotIron", 100);

		assertSame(r, r.getActualRecipe(ItemStack.EMPTY, ItemStack.EMPTY));
	}

	@Test
	@DisplayName("the press never batches items")
	void multipleProcessTicksIsZero()
	{
		assertEquals(0, make("ingotIron", 100).getMultipleProcessTicks());
	}

	@Test
	@DisplayName("an empty mould or an empty input finds nothing")
	void findRecipeShortCircuitsOnEmptyStacks()
	{
		assertNull(MetalPressRecipe.findRecipe(ItemStack.EMPTY, ItemStack.EMPTY));
	}

	@Test
	@DisplayName("an empty stack is not a valid mould")
	void emptyStackIsNotAMould()
	{
		assertFalse(MetalPressRecipe.isValidMold(ItemStack.EMPTY));
	}
}
