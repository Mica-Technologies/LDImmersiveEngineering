/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.crafting;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link CrusherRecipe}'s config-driven time/energy scaling, its static recipe list
 * bookkeeping and the NBT hand-off used to restore an in-progress crusher process.
 * <p>
 * Ore-dictionary names are used for the inputs because they are the only ingredient
 * flavour that does not need a bootstrapped item registry; the outputs are always
 * {@code ItemStack.EMPTY} for the same reason.
 */
class CrusherRecipeTest
{
	private float savedEnergyModifier;
	private float savedTimeModifier;
	private ArrayList<CrusherRecipe> savedRecipeList;

	@BeforeEach
	void saveStatics()
	{
		savedEnergyModifier = CrusherRecipe.energyModifier;
		savedTimeModifier = CrusherRecipe.timeModifier;
		savedRecipeList = CrusherRecipe.recipeList;
		CrusherRecipe.recipeList = new ArrayList<>();
		CrusherRecipe.energyModifier = 1;
		CrusherRecipe.timeModifier = 1;
	}

	@AfterEach
	void restoreStatics()
	{
		CrusherRecipe.energyModifier = savedEnergyModifier;
		CrusherRecipe.timeModifier = savedTimeModifier;
		CrusherRecipe.recipeList = savedRecipeList;
	}

	private static CrusherRecipe make(String oreInput, int energy)
	{
		return new CrusherRecipe(ItemStack.EMPTY, oreInput, energy);
	}

	@Test
	@DisplayName("the base recipe costs the requested energy and a flat 50 ticks")
	void baseTimeAndEnergy()
	{
		CrusherRecipe r = make("oreIron", 3000);

		assertEquals(3000, r.getTotalProcessEnergy());
		assertEquals(50, r.getTotalProcessTime());
	}

	@Test
	@DisplayName("the energy modifier scales the energy cost and nothing else")
	void energyModifierScalesEnergy()
	{
		CrusherRecipe.energyModifier = 2.5f;
		CrusherRecipe r = make("oreIron", 3000);

		assertEquals(7500, r.getTotalProcessEnergy());
		assertEquals(50, r.getTotalProcessTime());
	}

	@Test
	@DisplayName("the time modifier scales the process time and nothing else")
	void timeModifierScalesTime()
	{
		CrusherRecipe.timeModifier = .5f;
		CrusherRecipe r = make("oreIron", 3000);

		assertEquals(3000, r.getTotalProcessEnergy());
		assertEquals(25, r.getTotalProcessTime());
	}

	@Test
	@DisplayName("fractional modifier results are floored, not rounded")
	void modifiersAreFloored()
	{
		CrusherRecipe.energyModifier = 1.5f;
		CrusherRecipe.timeModifier = .33f;
		CrusherRecipe r = make("oreIron", 101);

		assertEquals(151, r.getTotalProcessEnergy(), "floor(101*1.5) = floor(151.5) = 151");
		assertEquals(16, r.getTotalProcessTime(), "floor(50*0.33) = floor(16.5) = 16");
	}

	@Test
	@DisplayName("a zero time modifier produces an instant recipe rather than a negative one")
	void zeroTimeModifier()
	{
		CrusherRecipe.timeModifier = 0;

		assertEquals(0, make("oreIron", 100).getTotalProcessTime());
	}

	@Test
	@DisplayName("zero energy stays zero whatever the modifier is")
	void zeroEnergy()
	{
		CrusherRecipe.energyModifier = 12.5f;

		assertEquals(0, make("oreIron", 0).getTotalProcessEnergy());
	}

	@Test
	@DisplayName("a string input is remembered as the ore input name")
	void stringInputIsRemembered()
	{
		CrusherRecipe r = make("oreIron", 100);

		assertEquals("oreIron", r.oreInputString);
		assertEquals("oreIron", r.input.oreName);
	}

	@Test
	@DisplayName("a non-string input leaves the ore input name unset")
	void nonStringInputHasNoOreInputString()
	{
		CrusherRecipe r = new CrusherRecipe(ItemStack.EMPTY, new IngredientStack("oreIron", 4), 100);

		assertNull(r.oreInputString);
		assertEquals(4, r.input.inputSize, "an IngredientStack input is adopted as-is");
	}

	@Test
	@DisplayName("the input list holds exactly the one ingredient")
	void inputListHoldsTheIngredient()
	{
		CrusherRecipe r = make("oreIron", 100);

		assertEquals(1, r.getItemInputs().size());
		assertSame(r.input, r.getItemInputs().get(0));
	}

	@Test
	@DisplayName("the output list holds exactly the one output")
	void outputListHoldsTheOutput()
	{
		CrusherRecipe r = make("oreIron", 100);

		assertEquals(1, r.getItemOutputs().size());
		assertSame(ItemStack.EMPTY, r.getItemOutputs().get(0));
	}

	@Test
	@DisplayName("the crusher processes four items at a time")
	void multipleProcessTicks()
	{
		assertEquals(4, make("oreIron", 100).getMultipleProcessTicks());
	}

	@Test
	@DisplayName("addRecipe refuses a recipe with an empty output but still returns it")
	void addRecipeRejectsEmptyOutput()
	{
		CrusherRecipe r = CrusherRecipe.addRecipe(ItemStack.EMPTY, "oreIron", 100);

		assertNotNull(r, "the caller still gets the recipe so it can be configured");
		assertTrue(CrusherRecipe.recipeList.isEmpty(), "an empty output must not be registered");
	}

	@Test
	@DisplayName("addToSecondaryOutput ignores an odd-length argument list")
	void secondaryOutputIgnoresOddArgumentCount()
	{
		CrusherRecipe r = make("oreIron", 100);

		assertSame(r, r.addToSecondaryOutput(ItemStack.EMPTY));
		assertNull(r.secondaryOutput, "nothing may be recorded from a malformed call");
		assertNull(r.secondaryChance);
	}

	@Test
	@DisplayName("addToSecondaryOutput skips empty stacks but still rebuilds the output list")
	void secondaryOutputSkipsEmptyStacks()
	{
		CrusherRecipe r = make("oreIron", 100);
		r.addToSecondaryOutput(ItemStack.EMPTY, 0.5f);

		assertEquals(0, r.secondaryOutput.length);
		assertEquals(0, r.secondaryChance.length);
		assertEquals(1, r.getItemOutputs().size(), "the primary output is put back");
		assertSame(ItemStack.EMPTY, r.getItemOutputs().get(0));
	}

	@Test
	@DisplayName("addToSecondaryOutput is chainable")
	void secondaryOutputIsChainable()
	{
		CrusherRecipe r = make("oreIron", 100);

		assertSame(r, r.addToSecondaryOutput(ItemStack.EMPTY, 0.5f));
	}

	@Test
	@DisplayName("writeToNBT stores the input ingredient under \"input\"")
	void writeToNbtStoresInput()
	{
		NBTTagCompound nbt = make("oreIron", 100).writeToNBT(new NBTTagCompound());

		assertTrue(nbt.hasKey("input"));
		IngredientStack read = IngredientStack.readFromNBT(nbt.getCompoundTag("input"));
		assertNotNull(read);
		assertEquals("oreIron", read.oreName);
	}

	@Test
	@DisplayName("loadFromNBT finds the registered recipe again")
	void loadFromNbtFindsRecipe()
	{
		CrusherRecipe iron = make("oreIron", 100);
		CrusherRecipe gold = make("oreGold", 100);
		CrusherRecipe.recipeList.add(iron);
		CrusherRecipe.recipeList.add(gold);

		assertSame(iron, CrusherRecipe.loadFromNBT(iron.writeToNBT(new NBTTagCompound())));
		assertSame(gold, CrusherRecipe.loadFromNBT(gold.writeToNBT(new NBTTagCompound())));
	}

	@Test
	@DisplayName("loadFromNBT returns null when nothing matches")
	void loadFromNbtWithoutMatch()
	{
		CrusherRecipe.recipeList.add(make("oreIron", 100));

		assertNull(CrusherRecipe.loadFromNBT(make("oreDiamond", 100).writeToNBT(new NBTTagCompound())));
	}

	@Test
	@DisplayName("loadFromNBT returns null for an empty tag")
	void loadFromNbtWithEmptyTag()
	{
		CrusherRecipe.recipeList.add(make("oreIron", 100));

		assertNull(CrusherRecipe.loadFromNBT(new NBTTagCompound()));
	}
}
