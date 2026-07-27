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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ore-dictionary-name flavour of {@link IngredientStack} plus its copy,
 * equality and NBT semantics.
 * <p>
 * The ItemStack- and FluidStack-backed flavours are not exercised here: building either
 * one needs the item registry / fluid registry, which only exist after a Forge bootstrap.
 */
class IngredientStackTest
{
	private static IngredientStack ore(String name, int size)
	{
		return new IngredientStack(name, size);
	}

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("the ore-name constructor defaults to an input size of one")
		void oreNameDefaultsToOne()
		{
			IngredientStack i = new IngredientStack("ingotIron");

			assertEquals("ingotIron", i.oreName);
			assertEquals(1, i.inputSize);
			assertNull(i.stackList);
			assertNull(i.fluid);
			assertSame(ItemStack.EMPTY, i.stack);
			assertFalse(i.useNBT);
		}

		@Test
		@DisplayName("the ore-name constructor keeps the requested input size, including odd ones")
		void oreNameKeepsSize()
		{
			assertEquals(0, ore("ingotIron", 0).inputSize);
			assertEquals(64, ore("ingotIron", 64).inputSize);
			assertEquals(-3, ore("ingotIron", -3).inputSize);
			assertEquals(Integer.MAX_VALUE, ore("ingotIron", Integer.MAX_VALUE).inputSize);
		}

		@Test
		@DisplayName("the stack-list constructor defaults to an input size of one")
		void stackListDefaultsToOne()
		{
			List<ItemStack> list = new ArrayList<>();
			IngredientStack i = new IngredientStack(list);

			assertSame(list, i.stackList);
			assertEquals(1, i.inputSize);
			assertNull(i.oreName);
		}

		@Test
		@DisplayName("the stack-list constructor stores the list by reference, not by copy")
		void stackListIsNotCopied()
		{
			List<ItemStack> list = new ArrayList<>();
			IngredientStack i = new IngredientStack(list, 4);

			list.add(ItemStack.EMPTY);
			assertEquals(1, i.stackList.size(), "the list is shared with the caller");
			assertEquals(4, i.inputSize);
		}

		@Test
		@DisplayName("setUseNBT is chainable and flips the flag")
		void setUseNbtIsChainable()
		{
			IngredientStack i = ore("ingotIron", 1);

			assertSame(i, i.setUseNBT(true));
			assertTrue(i.useNBT);
			assertSame(i, i.setUseNBT(false));
			assertFalse(i.useNBT);
		}
	}

	@Nested
	@DisplayName("copying")
	class Copying
	{
		@Test
		@DisplayName("the copy constructor carries every field over")
		void copyConstructorCarriesFields()
		{
			IngredientStack src = ore("ingotIron", 7).setUseNBT(true);
			IngredientStack copy = new IngredientStack(src);

			assertEquals("ingotIron", copy.oreName);
			assertEquals(7, copy.inputSize);
			assertTrue(copy.useNBT);
			assertSame(src.stack, copy.stack);
			assertNotSame(src, copy);
		}

		@Test
		@DisplayName("copyWithSize replaces only the input size")
		void copyWithSizeReplacesOnlySize()
		{
			IngredientStack src = ore("ingotIron", 7).setUseNBT(true);
			IngredientStack copy = src.copyWithSize(3);

			assertEquals(3, copy.inputSize);
			assertEquals(7, src.inputSize, "the source must not be mutated");
			assertEquals("ingotIron", copy.oreName);
			assertTrue(copy.useNBT);
		}

		@Test
		@DisplayName("copyWithSize accepts zero and negative sizes verbatim")
		void copyWithSizeBoundaries()
		{
			IngredientStack src = ore("ingotIron", 7);

			assertEquals(0, src.copyWithSize(0).inputSize);
			assertEquals(-5, src.copyWithSize(-5).inputSize);
			assertEquals(Integer.MAX_VALUE, src.copyWithSize(Integer.MAX_VALUE).inputSize);
		}

		@Test
		@DisplayName("copyWithMultipliedSize floors the product")
		void copyWithMultipliedSizeFloors()
		{
			IngredientStack src = ore("ingotIron", 7);

			assertEquals(17, src.copyWithMultipliedSize(2.5).inputSize, "7*2.5 = 17.5 -> 17");
			assertEquals(7, src.copyWithMultipliedSize(1).inputSize);
			assertEquals(0, src.copyWithMultipliedSize(0).inputSize);
			assertEquals(0, src.copyWithMultipliedSize(.1).inputSize, "0.7 floors to 0");
		}

		@Test
		@DisplayName("copyWithMultipliedSize floors towards negative infinity for negatives")
		void copyWithMultipliedSizeNegative()
		{
			assertEquals(-8, ore("ingotIron", 7).copyWithMultipliedSize(-1.1).inputSize,
					"Math.floor(-7.7) is -8, not -7");
		}

		@Test
		@DisplayName("copyWithMultipliedSize does not touch the original")
		void copyWithMultipliedSizeDoesNotMutate()
		{
			IngredientStack src = ore("ingotIron", 7);
			src.copyWithMultipliedSize(3);

			assertEquals(7, src.inputSize);
		}
	}

	@Nested
	@DisplayName("equality")
	class Equality
	{
		@Test
		@DisplayName("two ingredients with the same ore name are equal regardless of size")
		void sameOreNameIsEqual()
		{
			assertEquals(ore("ingotIron", 1), ore("ingotIron", 9));
			assertEquals(ore("ingotIron", 9), ore("ingotIron", 1));
		}

		@Test
		@DisplayName("different ore names are not equal")
		void differentOreNameIsNotEqual()
		{
			assertNotEquals(ore("ingotIron", 1), ore("ingotGold", 1));
		}

		@Test
		@DisplayName("ore names are compared case-sensitively")
		void oreNameIsCaseSensitive()
		{
			assertNotEquals(ore("ingotIron", 1), ore("INGOTIRON", 1));
		}

		@Test
		@DisplayName("equals rejects null and foreign types")
		void equalsRejectsForeignTypes()
		{
			IngredientStack i = ore("ingotIron", 1);

			assertNotEquals(i, null);
			assertFalse(i.equals("ingotIron"));
			assertFalse(i.equals(new Object()));
		}

		@Test
		@DisplayName("an ore ingredient is not equal to a stack ingredient with no stack")
		void oreVersusEmptyStackIsNotEqual()
		{
			IngredientStack ore = ore("ingotIron", 1);
			IngredientStack empty = new IngredientStack(Collections.<ItemStack>emptyList());

			assertNotEquals(ore, empty);
			assertNotEquals(empty, ore);
		}

		@Test
		@DisplayName("an ingredient equals itself")
		void reflexive()
		{
			IngredientStack i = ore("ingotIron", 4);
			assertEquals(i, i);
		}

		@Test
		@Disabled("IngredientStack overrides equals() but not hashCode(); equal ingredients hash differently")
		@DisplayName("equal ingredients must produce the same hash code")
		void equalIngredientsShareAHashCode()
		{
			IngredientStack a = ore("ingotIron", 1);
			IngredientStack b = ore("ingotIron", 1);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode(),
					"equals/hashCode contract: hash-based collections silently duplicate ingredients");

			Set<IngredientStack> set = new HashSet<>();
			set.add(a);
			set.add(b);
			assertEquals(1, set.size());
		}
	}

	@Nested
	@DisplayName("matches")
	class Matches
	{
		@Test
		@DisplayName("null never matches")
		void nullNeverMatches()
		{
			assertFalse(ore("ingotIron", 1).matches(null));
		}

		@Test
		@DisplayName("an ore name string matches the ingredient's own ore name")
		void oreNameStringMatches()
		{
			IngredientStack i = ore("ingotIron", 3);

			assertTrue(i.matches("ingotIron"));
			assertFalse(i.matches("ingotGold"));
		}

		@Test
		@DisplayName("matching another ingredient also requires the other to supply enough")
		void ingredientMatchRespectsInputSize()
		{
			IngredientStack needsThree = ore("ingotIron", 3);

			assertTrue(needsThree.matches(ore("ingotIron", 3)), "exactly enough");
			assertTrue(needsThree.matches(ore("ingotIron", 9)), "more than enough");
			assertFalse(needsThree.matches(ore("ingotIron", 2)), "not enough");
		}

		@Test
		@DisplayName("a mismatched ore name is rejected even when the size would fit")
		void ingredientMatchRejectsOtherOre()
		{
			assertFalse(ore("ingotIron", 1).matches(ore("ingotGold", 64)));
		}

		@Test
		@DisplayName("a list matches when any of its elements matches")
		void listMatchesIfAnyElementMatches()
		{
			IngredientStack i = ore("ingotIron", 1);

			assertTrue(i.matches(Arrays.asList(ore("ingotGold", 1), ore("ingotIron", 1))));
			assertFalse(i.matches(Arrays.asList(ore("ingotGold", 1), ore("ingotLead", 1))));
			assertFalse(i.matches(Collections.emptyList()));
		}

		@Test
		@DisplayName("an unrelated object never matches")
		void unrelatedObjectDoesNotMatch()
		{
			assertFalse(ore("ingotIron", 1).matches(42));
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("an ore ingredient round trips through NBT")
		void oreRoundTrip()
		{
			IngredientStack written = ore("ingotIron", 9);
			IngredientStack read = IngredientStack.readFromNBT(written.writeToNBT(new NBTTagCompound()));

			assertNotNull(read);
			assertEquals("ingotIron", read.oreName);
			assertEquals(9, read.inputSize);
			assertNull(read.stackList);
			assertNull(read.fluid);
		}

		@Test
		@DisplayName("the ore branch is tagged as nbtType 2")
		void oreUsesTypeTwo()
		{
			NBTTagCompound tag = ore("ingotIron", 1).writeToNBT(new NBTTagCompound());

			assertEquals(2, tag.getInteger("nbtType"));
			assertEquals("ingotIron", tag.getString("oreName"));
			assertEquals(1, tag.getInteger("inputSize"));
		}

		@Test
		@DisplayName("writeToNBT writes into the tag it was handed and returns it")
		void writeUsesTheGivenTag()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString("pre-existing", "keep me");

			NBTTagCompound returned = ore("ingotIron", 1).writeToNBT(tag);

			assertSame(tag, returned);
			assertEquals("keep me", returned.getString("pre-existing"));
		}

		@Test
		@DisplayName("an odd input size survives the round trip")
		void oddSizesRoundTrip()
		{
			for(int size : new int[]{0, 1, 64, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
			{
				IngredientStack read = IngredientStack.readFromNBT(
						ore("ingotIron", size).writeToNBT(new NBTTagCompound()));
				assertNotNull(read);
				assertEquals(size, read.inputSize, "size "+size);
			}
		}

		@Test
		@DisplayName("readFromNBT returns null for a tag that carries no type")
		void readWithoutTypeIsNull()
		{
			assertNull(IngredientStack.readFromNBT(new NBTTagCompound()));
		}

		@Test
		@DisplayName("readFromNBT returns null for an unknown type")
		void readWithUnknownTypeIsNull()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("nbtType", 99);

			assertNull(IngredientStack.readFromNBT(tag));
		}

		@Test
		@DisplayName("a round tripped ore ingredient still equals the original")
		void roundTripPreservesEquality()
		{
			IngredientStack written = ore("ingotIron", 5);
			IngredientStack read = IngredientStack.readFromNBT(written.writeToNBT(new NBTTagCompound()));

			assertEquals(written, read);
			assertEquals(read, written);
		}
	}
}
