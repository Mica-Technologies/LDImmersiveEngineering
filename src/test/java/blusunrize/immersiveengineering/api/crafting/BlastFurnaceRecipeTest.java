/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.crafting;

import blusunrize.immersiveengineering.api.crafting.BlastFurnaceRecipe.BlastFurnaceFuel;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link BlastFurnaceRecipe}'s recipe and fuel list bookkeeping. Matching a recipe
 * or a fuel against a real ItemStack goes through the ore dictionary, which needs a
 * bootstrapped registry, so only the empty-stack paths are exercised.
 */
class BlastFurnaceRecipeTest
{
	private ArrayList<BlastFurnaceRecipe> savedRecipes;
	private ArrayList<BlastFurnaceFuel> savedFuels;

	@BeforeEach
	void isolateLists()
	{
		savedRecipes = BlastFurnaceRecipe.recipeList;
		savedFuels = BlastFurnaceRecipe.blastFuels;
		BlastFurnaceRecipe.recipeList = new ArrayList<>();
		BlastFurnaceRecipe.blastFuels = new ArrayList<>();
	}

	@AfterEach
	void restoreLists()
	{
		BlastFurnaceRecipe.recipeList = savedRecipes;
		BlastFurnaceRecipe.blastFuels = savedFuels;
	}

	@Test
	@DisplayName("the constructor stores output, time and slag verbatim")
	void constructorStoresFields()
	{
		BlastFurnaceRecipe r = new BlastFurnaceRecipe(ItemStack.EMPTY, ItemStack.EMPTY, 1200, ItemStack.EMPTY);

		assertSame(ItemStack.EMPTY, r.output);
		assertSame(ItemStack.EMPTY, r.slag);
		assertEquals(1200, r.time);
		assertNotNull(r.input);
	}

	@Test
	@DisplayName("odd smelting times are not clamped")
	void timeIsNotClamped()
	{
		assertEquals(0, new BlastFurnaceRecipe(ItemStack.EMPTY, ItemStack.EMPTY, 0, ItemStack.EMPTY).time);
		assertEquals(-1, new BlastFurnaceRecipe(ItemStack.EMPTY, ItemStack.EMPTY, -1, ItemStack.EMPTY).time);
		assertEquals(Integer.MAX_VALUE,
				new BlastFurnaceRecipe(ItemStack.EMPTY, ItemStack.EMPTY, Integer.MAX_VALUE, ItemStack.EMPTY).time);
	}

	@Test
	@DisplayName("addRecipe registers a recipe with a usable input")
	void addRecipeRegisters()
	{
		BlastFurnaceRecipe.addRecipe(ItemStack.EMPTY, ItemStack.EMPTY, 100, ItemStack.EMPTY);
		BlastFurnaceRecipe.addRecipe(ItemStack.EMPTY, ItemStack.EMPTY, 200, ItemStack.EMPTY);

		assertEquals(2, BlastFurnaceRecipe.recipeList.size());
		assertEquals(100, BlastFurnaceRecipe.recipeList.get(0).time);
		assertEquals(200, BlastFurnaceRecipe.recipeList.get(1).time);
	}

	@Test
	@DisplayName("an input that is not a stack, item, block or ore name is rejected outright")
	void unusableInputTypeIsRejected()
	{
		assertThrows(RuntimeException.class,
				() -> new BlastFurnaceRecipe(ItemStack.EMPTY, new Object(), 100, ItemStack.EMPTY));
		assertThrows(RuntimeException.class,
				() -> BlastFurnaceRecipe.addRecipe(ItemStack.EMPTY, null, 100, ItemStack.EMPTY));
		assertTrue(BlastFurnaceRecipe.recipeList.isEmpty());
	}

	@Test
	@DisplayName("addBlastFuel returns an entry carrying the ingredient and burn time")
	void addBlastFuelReturnsEntry()
	{
		IngredientStack coke = new IngredientStack("fuelCoke", 1);
		BlastFurnaceFuel fuel = BlastFurnaceRecipe.addBlastFuel(coke, 1200);

		assertNotNull(fuel);
		assertSame(coke, fuel.input);
		assertEquals(1200, fuel.burnTime);
	}

	@Test
	@DisplayName("fuels accumulate in registration order")
	void fuelsAccumulateInOrder()
	{
		BlastFurnaceRecipe.addBlastFuel(new IngredientStack("fuelCoke", 1), 1200);
		BlastFurnaceRecipe.addBlastFuel(new IngredientStack("blockFuelCoke", 1), 12000);

		assertEquals(2, BlastFurnaceRecipe.blastFuels.size());
		assertEquals(1200, BlastFurnaceRecipe.blastFuels.get(0).burnTime);
		assertEquals(12000, BlastFurnaceRecipe.blastFuels.get(1).burnTime);
	}

	@Test
	@DisplayName("an empty stack never burns")
	void emptyStackIsNotFuel()
	{
		BlastFurnaceRecipe.addBlastFuel(new IngredientStack("fuelCoke", 1), 1200);

		assertEquals(0, BlastFurnaceRecipe.getBlastFuelTime(ItemStack.EMPTY));
		assertFalse(BlastFurnaceRecipe.isValidBlastFuel(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("with no fuels registered nothing is valid fuel")
	void noFuelsRegistered()
	{
		assertEquals(0, BlastFurnaceRecipe.getBlastFuelTime(ItemStack.EMPTY));
		assertFalse(BlastFurnaceRecipe.isValidBlastFuel(ItemStack.EMPTY));
	}
}
