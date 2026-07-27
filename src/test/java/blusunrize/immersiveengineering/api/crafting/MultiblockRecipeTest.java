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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the shared plumbing in {@link MultiblockRecipe} and the default methods of
 * {@link IMultiblockRecipe} / {@link IJEIRecipe} through a minimal stub recipe.
 * <p>
 * Only ore-dictionary ingredients and empty stacks are used, so nothing here needs the
 * item registry.
 */
class MultiblockRecipeTest
{
	/** The smallest possible concrete MultiblockRecipe. */
	private static class StubRecipe extends MultiblockRecipe
	{
		int multipleProcessTicks = 0;

		@Override
		public int getMultipleProcessTicks()
		{
			return multipleProcessTicks;
		}

		@Override
		public NBTTagCompound writeToNBT(NBTTagCompound nbt)
		{
			nbt.setString("stub", "yes");
			return nbt;
		}
	}

	private static StubRecipe recipe(int time, int energy)
	{
		StubRecipe r = new StubRecipe();
		r.totalProcessTime = time;
		r.totalProcessEnergy = energy;
		return r;
	}

	@Nested
	@DisplayName("time and energy")
	class TimeAndEnergy
	{
		@Test
		@DisplayName("a fresh recipe reports zero time and zero energy")
		void defaultsAreZero()
		{
			StubRecipe r = new StubRecipe();

			assertEquals(0, r.getTotalProcessTime());
			assertEquals(0, r.getTotalProcessEnergy());
		}

		@Test
		@DisplayName("the reported totals are exactly what was stored")
		void totalsAreReportedVerbatim()
		{
			StubRecipe r = recipe(120, 6400);

			assertEquals(120, r.getTotalProcessTime());
			assertEquals(6400, r.getTotalProcessEnergy());
		}

		@Test
		@DisplayName("extreme totals are not clamped")
		void extremeTotalsAreNotClamped()
		{
			StubRecipe r = recipe(Integer.MAX_VALUE, Integer.MIN_VALUE);

			assertEquals(Integer.MAX_VALUE, r.getTotalProcessTime());
			assertEquals(Integer.MIN_VALUE, r.getTotalProcessEnergy());
		}

		@Test
		@DisplayName("energy per tick derives from the two totals")
		void energyPerTickDerivation()
		{
			StubRecipe r = recipe(100, 25600);

			assertEquals(256, r.getTotalProcessEnergy()/r.getTotalProcessTime());
		}

		@Test
		@DisplayName("getMultipleProcessTicks comes from the concrete recipe")
		void multipleProcessTicksIsOverridable()
		{
			StubRecipe r = new StubRecipe();
			assertEquals(0, r.getMultipleProcessTicks());

			r.multipleProcessTicks = 4;
			assertEquals(4, r.getMultipleProcessTicks());
		}
	}

	@Nested
	@DisplayName("input/output accessors")
	class Accessors
	{
		@Test
		@DisplayName("all four lists start out null")
		void listsStartNull()
		{
			StubRecipe r = new StubRecipe();

			assertNull(r.getItemInputs());
			assertNull(r.getItemOutputs());
			assertNull(r.getFluidInputs());
			assertNull(r.getFluidOutputs());
		}

		@Test
		@DisplayName("the accessors hand back the very same list instances")
		void accessorsReturnTheSameInstances()
		{
			StubRecipe r = new StubRecipe();
			List<IngredientStack> in = new ArrayList<>();
			NonNullList<ItemStack> out = NonNullList.create();
			r.inputList = in;
			r.outputList = out;

			assertSame(in, r.getItemInputs());
			assertSame(out, r.getItemOutputs());
		}

		@Test
		@DisplayName("getActualItemOutputs defaults to the plain outputs")
		void actualOutputsDefaultToPlainOutputs()
		{
			StubRecipe r = new StubRecipe();
			r.outputList = NonNullList.create();

			assertSame(r.getItemOutputs(), r.getActualItemOutputs(null));
		}

		@Test
		@DisplayName("getActualFluidOutputs defaults to the plain fluid outputs")
		void actualFluidOutputsDefaultToPlainOutputs()
		{
			StubRecipe r = new StubRecipe();
			r.fluidOutputList = new ArrayList<>();

			assertSame(r.getFluidOutputs(), r.getActualFluidOutputs(null));
		}

		@Test
		@DisplayName("shouldCheckItemAvailability defaults to true")
		void shouldCheckItemAvailabilityDefaultsTrue()
		{
			assertTrue(new StubRecipe().shouldCheckItemAvailability());
		}

		@Test
		@DisplayName("listInJEI defaults to true")
		void listInJeiDefaultsTrue()
		{
			assertTrue(new StubRecipe().listInJEI());
		}

		@Test
		@DisplayName("getDisplayStack returns EMPTY when no ingredient matches")
		void displayStackFallsBackToEmpty()
		{
			StubRecipe r = new StubRecipe();
			r.inputList = Arrays.asList(new IngredientStack("ingotIron", 3));

			assertSame(ItemStack.EMPTY, r.getDisplayStack(ItemStack.EMPTY));
		}

		@Test
		@DisplayName("getDisplayStack returns EMPTY for a recipe with no inputs")
		void displayStackWithNoInputs()
		{
			StubRecipe r = new StubRecipe();
			r.inputList = Collections.emptyList();

			assertSame(ItemStack.EMPTY, r.getDisplayStack(ItemStack.EMPTY));
		}
	}

	@Nested
	@DisplayName("setupJEI")
	class SetupJei
	{
		@Test
		@DisplayName("null source lists become empty JEI lists, never null")
		void nullListsBecomeEmptyLists()
		{
			StubRecipe r = new StubRecipe();
			r.setupJEI();

			assertNotNull(r.getJEITotalItemInputs());
			assertTrue(r.getJEITotalItemInputs().isEmpty());
			assertNotNull(r.getJEITotalItemOutputs());
			assertTrue(r.getJEITotalItemOutputs().isEmpty());
			assertNotNull(r.getJEITotalFluidInputs());
			assertTrue(r.getJEITotalFluidInputs().isEmpty());
			assertNotNull(r.getJEITotalFluidOutputs());
			assertTrue(r.getJEITotalFluidOutputs().isEmpty());
		}

		@Test
		@DisplayName("an empty input list yields an empty per-slot array, not a null one")
		void emptyInputListYieldsEmptyArray()
		{
			StubRecipe r = new StubRecipe();
			r.inputList = new ArrayList<>();
			r.setupJEI();

			assertNotNull(r.jeiItemInputList);
			assertEquals(0, r.jeiItemInputList.length);
			assertTrue(r.getJEITotalItemInputs().isEmpty());
		}

		@Test
		@DisplayName("one JEI slot is produced per ingredient")
		void oneSlotPerIngredient()
		{
			StubRecipe r = new StubRecipe();
			List<ItemStack> a = new ArrayList<>();
			a.add(ItemStack.EMPTY);
			List<ItemStack> b = new ArrayList<>();
			b.add(ItemStack.EMPTY);
			b.add(ItemStack.EMPTY);
			r.inputList = Arrays.asList(new IngredientStack(a, 1), new IngredientStack(b, 1));
			r.setupJEI();

			assertEquals(2, r.jeiItemInputList.length);
			assertEquals(1, r.jeiItemInputList[0].size());
			assertEquals(2, r.jeiItemInputList[1].size());
			assertEquals(3, r.getJEITotalItemInputs().size(), "the total list flattens every slot");
		}

		@Test
		@DisplayName("one JEI slot is produced per output stack")
		void oneSlotPerOutput()
		{
			StubRecipe r = new StubRecipe();
			NonNullList<ItemStack> out = NonNullList.create();
			out.add(ItemStack.EMPTY);
			out.add(ItemStack.EMPTY);
			r.outputList = out;
			r.setupJEI();

			assertEquals(2, r.jeiItemOutputList.length);
			assertEquals(2, r.getJEITotalItemOutputs().size());
		}

		@Test
		@DisplayName("setupJEI is idempotent -- running it twice does not double up the totals")
		void setupIsIdempotent()
		{
			StubRecipe r = new StubRecipe();
			List<ItemStack> a = new ArrayList<>();
			a.add(ItemStack.EMPTY);
			r.inputList = Arrays.asList(new IngredientStack(a, 1));

			r.setupJEI();
			int first = r.getJEITotalItemInputs().size();
			r.setupJEI();

			assertEquals(first, r.getJEITotalItemInputs().size());
		}
	}
}
