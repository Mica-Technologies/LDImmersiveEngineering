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
import net.minecraft.util.NonNullList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ArcFurnaceRecipe}'s time/energy scaling (where the energy total is derived
 * from the already-scaled tick count), its additive handling, the special-recipe-type
 * registry and the NBT hand-off.
 */
class ArcFurnaceRecipeTest
{
	private float savedEnergyModifier;
	private float savedTimeModifier;
	private ArrayList<ArcFurnaceRecipe> savedRecipeList;
	private ArrayList<String> savedSpecialTypes;

	@BeforeEach
	void saveStatics()
	{
		savedEnergyModifier = ArcFurnaceRecipe.energyModifier;
		savedTimeModifier = ArcFurnaceRecipe.timeModifier;
		savedRecipeList = ArcFurnaceRecipe.recipeList;
		savedSpecialTypes = ArcFurnaceRecipe.specialRecipeTypes;
		ArcFurnaceRecipe.recipeList = new ArrayList<>();
		ArcFurnaceRecipe.specialRecipeTypes = new ArrayList<>();
		ArcFurnaceRecipe.energyModifier = 1;
		ArcFurnaceRecipe.timeModifier = 1;
	}

	@AfterEach
	void restoreStatics()
	{
		ArcFurnaceRecipe.energyModifier = savedEnergyModifier;
		ArcFurnaceRecipe.timeModifier = savedTimeModifier;
		ArcFurnaceRecipe.recipeList = savedRecipeList;
		ArcFurnaceRecipe.specialRecipeTypes = savedSpecialTypes;
	}

	private static ArcFurnaceRecipe make(String input, int time, int energyPerTick, Object... additives)
	{
		return new ArcFurnaceRecipe(ItemStack.EMPTY, input, ItemStack.EMPTY, time, energyPerTick, additives);
	}

	@Test
	@DisplayName("the total energy is the per-tick cost times the tick count")
	void baseTimeAndEnergy()
	{
		ArcFurnaceRecipe r = make("oreIron", 100, 512);

		assertEquals(100, r.getTotalProcessTime());
		assertEquals(51200, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("the energy modifier scales the per-tick cost before it is multiplied out")
	void energyModifierScalesPerTickCost()
	{
		ArcFurnaceRecipe.energyModifier = 2;
		ArcFurnaceRecipe r = make("oreIron", 100, 512);

		assertEquals(100, r.getTotalProcessTime());
		assertEquals(102400, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("the time modifier changes the total energy too, because energy is derived from ticks")
	void timeModifierAlsoChangesEnergy()
	{
		ArcFurnaceRecipe.timeModifier = .5f;
		ArcFurnaceRecipe r = make("oreIron", 100, 512);

		assertEquals(50, r.getTotalProcessTime());
		assertEquals(25600, r.getTotalProcessEnergy(), "512 * 50, not 512 * 100");
	}

	@Test
	@DisplayName("a zero time modifier makes the recipe free as well as instant")
	void zeroTimeModifierMakesRecipeFree()
	{
		ArcFurnaceRecipe.timeModifier = 0;
		ArcFurnaceRecipe r = make("oreIron", 100, 512);

		assertEquals(0, r.getTotalProcessTime());
		assertEquals(0, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("the per-tick cost is floored before the multiplication")
	void perTickCostIsFlooredFirst()
	{
		ArcFurnaceRecipe.energyModifier = 1.5f;
		ArcFurnaceRecipe r = make("oreIron", 10, 101);

		assertEquals(1510, r.getTotalProcessEnergy(), "floor(101*1.5) = 151, then 151*10");
	}

	@Test
	@DisplayName("both modifiers apply together")
	void bothModifiersApply()
	{
		ArcFurnaceRecipe.energyModifier = 2;
		ArcFurnaceRecipe.timeModifier = 3;
		ArcFurnaceRecipe r = make("oreIron", 10, 100);

		assertEquals(30, r.getTotalProcessTime());
		assertEquals(6000, r.getTotalProcessEnergy());
	}

	@Test
	@DisplayName("no additives yields an empty additive array, never null")
	void noAdditivesYieldsEmptyArray()
	{
		ArcFurnaceRecipe r = make("oreIron", 10, 10);

		assertNotNull(r.additives);
		assertEquals(0, r.additives.length);
		assertEquals(1, r.getItemInputs().size());
	}

	@Test
	@DisplayName("an explicitly null additive array is treated as no additives")
	void nullAdditiveArrayIsTolerated()
	{
		ArcFurnaceRecipe r = new ArcFurnaceRecipe(ItemStack.EMPTY, "oreIron", ItemStack.EMPTY, 10, 10, (Object[])null);

		assertNotNull(r.additives);
		assertEquals(0, r.additives.length);
	}

	@Test
	@DisplayName("additives are converted to ingredients and appended to the input list")
	void additivesAreAppendedToInputList()
	{
		ArcFurnaceRecipe r = make("oreIron", 10, 10, "dustCoal", "sand");

		assertEquals(2, r.additives.length);
		assertEquals("dustCoal", r.additives[0].oreName);
		assertEquals("sand", r.additives[1].oreName);
		assertEquals(3, r.getItemInputs().size(), "the main input plus both additives");
		assertSame(r.input, r.getItemInputs().get(0));
	}

	@Test
	@DisplayName("the arc furnace never batches items")
	void multipleProcessTicksIsZero()
	{
		assertEquals(0, make("oreIron", 10, 10).getMultipleProcessTicks());
	}

	@Test
	@DisplayName("addRecipe registers the recipe and hands it back")
	void addRecipeRegisters()
	{
		ArcFurnaceRecipe r = ArcFurnaceRecipe.addRecipe(ItemStack.EMPTY, "oreIron", ItemStack.EMPTY, 10, 10);

		assertNotNull(r);
		assertEquals(1, ArcFurnaceRecipe.recipeList.size());
		assertSame(r, ArcFurnaceRecipe.recipeList.get(0));
	}

	@Test
	@DisplayName("setSpecialRecipeType records the type once and is chainable")
	void specialRecipeTypeIsRecordedOnce()
	{
		ArcFurnaceRecipe a = make("oreIron", 10, 10);
		ArcFurnaceRecipe b = make("oreGold", 10, 10);

		assertSame(a, a.setSpecialRecipeType("Recycling"));
		b.setSpecialRecipeType("Recycling");

		assertEquals("Recycling", a.specialRecipeType);
		assertEquals("Recycling", b.specialRecipeType);
		assertEquals(1, ArcFurnaceRecipe.specialRecipeTypes.size(), "the shared type list must not gain duplicates");
	}

	@Test
	@DisplayName("distinct special recipe types all get recorded")
	void distinctSpecialRecipeTypesAreAllRecorded()
	{
		make("oreIron", 10, 10).setSpecialRecipeType("Recycling");
		make("oreGold", 10, 10).setSpecialRecipeType("Ores");

		assertEquals(2, ArcFurnaceRecipe.specialRecipeTypes.size());
		assertTrue(ArcFurnaceRecipe.specialRecipeTypes.contains("Recycling"));
		assertTrue(ArcFurnaceRecipe.specialRecipeTypes.contains("Ores"));
	}

	@Test
	@DisplayName("an empty stack is neither a valid input nor a valid additive")
	void emptyStackIsNeverValid()
	{
		ArcFurnaceRecipe r = make("oreIron", 10, 10, "dustCoal");

		assertFalse(r.isValidInput(ItemStack.EMPTY));
		assertFalse(r.isValidAdditive(ItemStack.EMPTY));
		assertFalse(r.matches(ItemStack.EMPTY, NonNullList.create()));
	}

	@Test
	@DisplayName("the static input/additive scans report false when nothing matches")
	void staticScansReportFalse()
	{
		ArcFurnaceRecipe.recipeList.add(make("oreIron", 10, 10, "dustCoal"));

		assertFalse(ArcFurnaceRecipe.isValidRecipeInput(ItemStack.EMPTY));
		assertFalse(ArcFurnaceRecipe.isValidRecipeAdditive(ItemStack.EMPTY));
		assertNull(ArcFurnaceRecipe.findRecipe(ItemStack.EMPTY, NonNullList.create()));
	}

	@Test
	@DisplayName("writeToNBT leaves out the additive list when there are none")
	void writeToNbtOmitsEmptyAdditives()
	{
		NBTTagCompound nbt = make("oreIron", 10, 10).writeToNBT(new NBTTagCompound());

		assertTrue(nbt.hasKey("input"));
		assertFalse(nbt.hasKey("additives"));
	}

	@Test
	@DisplayName("writeToNBT records every additive")
	void writeToNbtRecordsAdditives()
	{
		NBTTagCompound nbt = make("oreIron", 10, 10, "dustCoal", "sand").writeToNBT(new NBTTagCompound());

		assertEquals(2, nbt.getTagList("additives", 10).tagCount());
	}

	@Test
	@DisplayName("loadFromNBT finds the recipe again, additives and all")
	void loadFromNbtRoundTrip()
	{
		ArcFurnaceRecipe plain = make("oreIron", 10, 10);
		ArcFurnaceRecipe withAdditive = make("oreGold", 10, 10, "dustCoal");
		ArcFurnaceRecipe.recipeList.add(plain);
		ArcFurnaceRecipe.recipeList.add(withAdditive);

		assertSame(plain, ArcFurnaceRecipe.loadFromNBT(plain.writeToNBT(new NBTTagCompound())));
		assertSame(withAdditive, ArcFurnaceRecipe.loadFromNBT(withAdditive.writeToNBT(new NBTTagCompound())));
	}

	@Test
	@DisplayName("loadFromNBT tells apart two recipes that share an input but differ in additives")
	void loadFromNbtDistinguishesByAdditives()
	{
		ArcFurnaceRecipe withCoal = make("oreIron", 10, 10, "dustCoal");
		ArcFurnaceRecipe withSand = make("oreIron", 10, 10, "sand");
		ArcFurnaceRecipe.recipeList.add(withCoal);
		ArcFurnaceRecipe.recipeList.add(withSand);

		assertSame(withCoal, ArcFurnaceRecipe.loadFromNBT(withCoal.writeToNBT(new NBTTagCompound())));
		assertSame(withSand, ArcFurnaceRecipe.loadFromNBT(withSand.writeToNBT(new NBTTagCompound())));
	}

	@Test
	@DisplayName("loadFromNBT returns null when no registered recipe matches")
	void loadFromNbtWithoutMatch()
	{
		ArcFurnaceRecipe.recipeList.add(make("oreIron", 10, 10));

		assertNull(ArcFurnaceRecipe.loadFromNBT(make("oreDiamond", 10, 10).writeToNBT(new NBTTagCompound())));
	}
}
