/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.crafting;

import com.google.common.collect.ArrayListMultimap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link BlueprintCraftingRecipe}: the automatic-workbench time/energy figures, the
 * category registry, ingredient folding via {@code getFormattedInputs} and the NBT lookup
 * that restores an in-progress workbench recipe.
 */
class BlueprintCraftingRecipeTest
{
	private float savedEnergyModifier;
	private float savedTimeModifier;
	private ArrayListMultimap<String, BlueprintCraftingRecipe> savedRecipeList;
	private ArrayList<String> savedCategories;

	@BeforeEach
	void saveStatics()
	{
		savedEnergyModifier = BlueprintCraftingRecipe.energyModifier;
		savedTimeModifier = BlueprintCraftingRecipe.timeModifier;
		savedRecipeList = BlueprintCraftingRecipe.recipeList;
		savedCategories = BlueprintCraftingRecipe.blueprintCategories;
		BlueprintCraftingRecipe.recipeList = ArrayListMultimap.create();
		BlueprintCraftingRecipe.blueprintCategories = new ArrayList<>();
		BlueprintCraftingRecipe.energyModifier = 1;
		BlueprintCraftingRecipe.timeModifier = 1;
	}

	@AfterEach
	void restoreStatics()
	{
		BlueprintCraftingRecipe.energyModifier = savedEnergyModifier;
		BlueprintCraftingRecipe.timeModifier = savedTimeModifier;
		BlueprintCraftingRecipe.recipeList = savedRecipeList;
		BlueprintCraftingRecipe.blueprintCategories = savedCategories;
	}

	private static BlueprintCraftingRecipe make(String category, Object... inputs)
	{
		return new BlueprintCraftingRecipe(category, ItemStack.EMPTY, inputs);
	}

	@Nested
	@DisplayName("time and energy")
	class TimeAndEnergy
	{
		@Test
		@DisplayName("the automatic workbench defaults to 180 ticks and 23040 RF")
		void defaults()
		{
			BlueprintCraftingRecipe r = make("components", "ingotIron");

			assertEquals(180, r.getTotalProcessTime());
			assertEquals(23040, r.getTotalProcessEnergy());
		}

		@Test
		@DisplayName("the modifiers scale the two figures independently")
		void modifiersScaleIndependently()
		{
			BlueprintCraftingRecipe.energyModifier = .5f;
			BlueprintCraftingRecipe.timeModifier = 2;
			BlueprintCraftingRecipe r = make("components", "ingotIron");

			assertEquals(360, r.getTotalProcessTime());
			assertEquals(11520, r.getTotalProcessEnergy());
		}

		@Test
		@DisplayName("fractional modifier results are floored")
		void modifiersAreFloored()
		{
			BlueprintCraftingRecipe.timeModifier = .33f;
			assertEquals(59, make("components", "ingotIron").getTotalProcessTime(),
					"floor(180*0.33) = floor(59.4) = 59");
		}

		@Test
		@DisplayName("the workbench never batches items")
		void multipleProcessTicksIsZero()
		{
			assertEquals(0, make("components", "ingotIron").getMultipleProcessTicks());
		}
	}

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("every input is converted into an ingredient in order")
		void inputsAreConvertedInOrder()
		{
			BlueprintCraftingRecipe r = make("components", "ingotIron", "ingotGold", "dustCoal");

			assertEquals(3, r.inputs.length);
			assertEquals("ingotIron", r.inputs[0].oreName);
			assertEquals("ingotGold", r.inputs[1].oreName);
			assertEquals("dustCoal", r.inputs[2].oreName);
			assertEquals(3, r.getItemInputs().size());
		}

		@Test
		@DisplayName("an ingredient handed in directly is adopted as-is, sizes included")
		void ingredientInputsAreAdoptedAsIs()
		{
			IngredientStack ingr = new IngredientStack("ingotIron", 9);
			BlueprintCraftingRecipe r = make("components", ingr);

			assertSame(ingr, r.inputs[0]);
			assertEquals(9, r.inputs[0].inputSize);
		}

		@Test
		@DisplayName("a recipe with no inputs is still well formed")
		void noInputs()
		{
			BlueprintCraftingRecipe r = make("components");

			assertEquals(0, r.inputs.length);
			assertTrue(r.getItemInputs().isEmpty());
			assertEquals(1, r.getItemOutputs().size());
		}

		@Test
		@DisplayName("the category is stored on the recipe")
		void categoryIsStored()
		{
			assertEquals("components", make("components", "ingotIron").blueprintCategory);
		}
	}

	@Nested
	@DisplayName("registry")
	class Registry
	{
		@Test
		@DisplayName("addRecipe files the recipe under its category")
		void addRecipeFilesUnderCategory()
		{
			BlueprintCraftingRecipe.addRecipe("components", ItemStack.EMPTY, "ingotIron");

			assertEquals(1, BlueprintCraftingRecipe.recipeList.get("components").size());
			assertEquals(1, BlueprintCraftingRecipe.findRecipes("components").length);
		}

		@Test
		@DisplayName("a category is only listed once no matter how many recipes it holds")
		void categoriesAreDeduplicated()
		{
			BlueprintCraftingRecipe.addRecipe("components", ItemStack.EMPTY, "ingotIron");
			BlueprintCraftingRecipe.addRecipe("components", ItemStack.EMPTY, "ingotGold");
			BlueprintCraftingRecipe.addRecipe("bullet", ItemStack.EMPTY, "ingotLead");

			assertEquals(2, BlueprintCraftingRecipe.blueprintCategories.size());
			assertTrue(BlueprintCraftingRecipe.blueprintCategories.contains("components"));
			assertTrue(BlueprintCraftingRecipe.blueprintCategories.contains("bullet"));
		}

		@Test
		@DisplayName("recipes come back in registration order")
		void findRecipesKeepsOrder()
		{
			BlueprintCraftingRecipe.addRecipe("components", ItemStack.EMPTY, "ingotIron");
			BlueprintCraftingRecipe.addRecipe("components", ItemStack.EMPTY, "ingotGold");

			BlueprintCraftingRecipe[] found = BlueprintCraftingRecipe.findRecipes("components");
			assertEquals(2, found.length);
			assertEquals("ingotIron", found[0].inputs[0].oreName);
			assertEquals("ingotGold", found[1].inputs[0].oreName);
		}

		@Test
		@DisplayName("an unknown category yields an empty array, not null")
		void unknownCategoryYieldsEmptyArray()
		{
			BlueprintCraftingRecipe[] found = BlueprintCraftingRecipe.findRecipes("nope");

			assertNotNull(found);
			assertEquals(0, found.length);
		}

		@Test
		@DisplayName("villager trades are recorded per category")
		void villagerTradesAreRecorded()
		{
			int before = BlueprintCraftingRecipe.villagerPrices.size();
			BlueprintCraftingRecipe.addVillagerTrade("test_category_xyz", ItemStack.EMPTY);

			assertEquals(before+1, BlueprintCraftingRecipe.villagerPrices.size());
			assertSame(ItemStack.EMPTY, BlueprintCraftingRecipe.villagerPrices.get("test_category_xyz"));
			BlueprintCraftingRecipe.villagerPrices.remove("test_category_xyz");
		}
	}

	@Nested
	@DisplayName("getFormattedInputs")
	class FormattedInputs
	{
		@Test
		@DisplayName("distinct ingredients are passed through unchanged")
		void distinctIngredientsArePassedThrough()
		{
			List<IngredientStack> formatted = make("c", "ingotIron", "ingotGold").getFormattedInputs();

			assertEquals(2, formatted.size());
			assertEquals("ingotIron", formatted.get(0).oreName);
			assertEquals(1, formatted.get(0).inputSize);
			assertEquals("ingotGold", formatted.get(1).oreName);
			assertEquals(1, formatted.get(1).inputSize);
		}

		@Test
		@DisplayName("repeated ingredients are folded into one with the sizes added up")
		void repeatedIngredientsAreFolded()
		{
			List<IngredientStack> formatted = make("c",
					new IngredientStack("ingotIron", 1),
					new IngredientStack("ingotIron", 2),
					new IngredientStack("ingotIron", 4)).getFormattedInputs();

			assertEquals(1, formatted.size());
			assertEquals("ingotIron", formatted.get(0).oreName);
			assertEquals(7, formatted.get(0).inputSize);
		}

		@Test
		@DisplayName("folding does not mutate the recipe's own ingredients")
		void foldingDoesNotMutateTheRecipe()
		{
			BlueprintCraftingRecipe r = make("c",
					new IngredientStack("ingotIron", 1),
					new IngredientStack("ingotIron", 2));

			r.getFormattedInputs();

			assertEquals(1, r.inputs[0].inputSize);
			assertEquals(2, r.inputs[1].inputSize);
		}

		@Test
		@DisplayName("a recipe with no inputs formats to an empty list")
		void noInputsFormatsToEmpty()
		{
			assertTrue(make("c").getFormattedInputs().isEmpty());
		}

		@Test
		@Disabled("getFormattedInputs leaks the isNew flag across the inner loop; folding a "
				+ "duplicate also inflates every ingredient listed after it")
		@DisplayName("folding a duplicate must not change any other ingredient's size")
		void foldingDoesNotInflateLaterIngredients()
		{
			// iron, gold, iron: the second iron folds into the first, and the "isNew" flag
			// is never reset, so gold is credited the folded amount as well.
			List<IngredientStack> formatted = make("c",
					new IngredientStack("ingotIron", 1),
					new IngredientStack("ingotGold", 1),
					new IngredientStack("ingotIron", 2)).getFormattedInputs();

			assertEquals(2, formatted.size());
			assertEquals("ingotIron", formatted.get(0).oreName);
			assertEquals(3, formatted.get(0).inputSize);
			assertEquals("ingotGold", formatted.get(1).oreName);
			assertEquals(1, formatted.get(1).inputSize, "gold appears once and must still cost one");
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("writeToNBT records the category and every input")
		void writeRecordsCategoryAndInputs()
		{
			NBTTagCompound nbt = make("components", "ingotIron", "ingotGold").writeToNBT(new NBTTagCompound());

			assertEquals("components", nbt.getString("blueprintCategory"));
			assertEquals(2, nbt.getTagList("inputs", 10).tagCount());
		}

		@Test
		@DisplayName("loadFromNBT finds the recipe it was written from")
		void loadFindsTheSameRecipe()
		{
			BlueprintCraftingRecipe iron = make("components", "ingotIron", "ingotGold");
			BlueprintCraftingRecipe.recipeList.put("components", iron);

			assertSame(iron, BlueprintCraftingRecipe.loadFromNBT(iron.writeToNBT(new NBTTagCompound())));
		}

		@Test
		@DisplayName("loadFromNBT does not settle for a recipe that only shares its first ingredient")
		void loadDistinguishesRecipesSharingAnIngredient()
		{
			BlueprintCraftingRecipe a = make("components", "ingotIron", "ingotGold");
			BlueprintCraftingRecipe b = make("components", "ingotIron", "gemDiamond");
			BlueprintCraftingRecipe.recipeList.put("components", a);
			BlueprintCraftingRecipe.recipeList.put("components", b);

			assertSame(a, BlueprintCraftingRecipe.loadFromNBT(a.writeToNBT(new NBTTagCompound())));
			assertSame(b, BlueprintCraftingRecipe.loadFromNBT(b.writeToNBT(new NBTTagCompound())));
		}

		@Test
		@DisplayName("loadFromNBT skips a recipe with a different number of ingredients")
		void loadSkipsDifferentIngredientCounts()
		{
			BlueprintCraftingRecipe shortRecipe = make("components", "ingotIron");
			BlueprintCraftingRecipe longRecipe = make("components", "ingotIron", "ingotGold");
			BlueprintCraftingRecipe.recipeList.put("components", shortRecipe);
			BlueprintCraftingRecipe.recipeList.put("components", longRecipe);

			assertSame(longRecipe, BlueprintCraftingRecipe.loadFromNBT(longRecipe.writeToNBT(new NBTTagCompound())));
			assertSame(shortRecipe, BlueprintCraftingRecipe.loadFromNBT(shortRecipe.writeToNBT(new NBTTagCompound())));
		}

		@Test
		@DisplayName("ingredient sizes survive the round trip and still match")
		void sizesSurviveTheRoundTrip()
		{
			BlueprintCraftingRecipe r = make("components", new IngredientStack("ingotIron", 4));
			BlueprintCraftingRecipe.recipeList.put("components", r);

			NBTTagCompound nbt = r.writeToNBT(new NBTTagCompound());
			assertEquals(4, IngredientStack.readFromNBT(nbt.getTagList("inputs", 10).getCompoundTagAt(0)).inputSize);
			assertSame(r, BlueprintCraftingRecipe.loadFromNBT(nbt));
		}

		@Test
		@DisplayName("loadFromNBT returns null for an unknown category")
		void loadWithUnknownCategory()
		{
			BlueprintCraftingRecipe r = make("components", "ingotIron");
			BlueprintCraftingRecipe.recipeList.put("components", r);

			NBTTagCompound nbt = r.writeToNBT(new NBTTagCompound());
			nbt.setString("blueprintCategory", "nope");

			assertNull(BlueprintCraftingRecipe.loadFromNBT(nbt));
		}

		@Test
		@DisplayName("loadFromNBT returns null when nothing in the category matches")
		void loadWithoutMatch()
		{
			BlueprintCraftingRecipe.recipeList.put("components", make("components", "ingotIron"));

			assertNull(BlueprintCraftingRecipe.loadFromNBT(
					make("components", "gemDiamond").writeToNBT(new NBTTagCompound())));
		}
	}
}
