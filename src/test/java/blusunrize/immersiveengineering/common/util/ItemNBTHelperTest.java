/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the item-NBT helpers.
 * <p>
 * Scope note: a populated {@link ItemStack} cannot be built in a unit test -- its constructor
 * touches {@code net.minecraft.init.Items}, which throws without a Minecraft bootstrap. So the
 * stack-mutating setters are out of reach here; what is covered is
 * <ul>
 * <li>every getter's behaviour on a stack with no tag (the "missing key" defaults), using the
 * pre-built {@link ItemStack#EMPTY} singleton, and</li>
 * <li>the helpers that work on a bare {@link NBTTagCompound}, which is where all the
 * interesting logic lives ({@code combineTags}, {@code modifyInt}, {@code modifyFloat}).</li>
 * </ul>
 * Nothing here may write to {@code ItemStack.EMPTY} -- it is a shared global. {@link #emptyStackStaysPristine()}
 * enforces that.
 */
class ItemNBTHelperTest
{
	@AfterEach
	void emptyStackStaysPristine()
	{
		assertFalse(ItemStack.EMPTY.hasTagCompound(),
				"a test wrote NBT onto the shared ItemStack.EMPTY singleton -- that leaks into every other test");
	}

	// ---------------------------------------------------------------- defaults on a tagless stack

	@Test
	@DisplayName("hasTag() is false for a stack without a tag compound")
	void hasTagOnTaglessStack()
	{
		assertFalse(ItemNBTHelper.hasTag(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("hasKey() is false for a stack without a tag compound")
	void hasKeyOnTaglessStack()
	{
		assertFalse(ItemNBTHelper.hasKey(ItemStack.EMPTY, "anything"));
	}

	@Test
	@DisplayName("getInt() defaults to 0 on a missing key")
	void getIntDefault()
	{
		assertEquals(0, ItemNBTHelper.getInt(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("getLong() defaults to 0 on a missing key")
	void getLongDefault()
	{
		assertEquals(0L, ItemNBTHelper.getLong(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("getFloat() defaults to 0 on a missing key")
	void getFloatDefault()
	{
		assertEquals(0f, ItemNBTHelper.getFloat(ItemStack.EMPTY, "missing"), 0f);
	}

	@Test
	@DisplayName("getBoolean() defaults to false on a missing key")
	void getBooleanDefault()
	{
		assertFalse(ItemNBTHelper.getBoolean(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("getString() defaults to the empty string on a missing key")
	void getStringDefault()
	{
		assertEquals("", ItemNBTHelper.getString(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("getIntArray() defaults to a zero-length array on a missing key")
	void getIntArrayDefault()
	{
		int[] out = ItemNBTHelper.getIntArray(ItemStack.EMPTY, "missing");
		assertNotNull(out);
		assertEquals(0, out.length);
	}

	@Test
	@DisplayName("getTagCompound() defaults to a fresh empty compound on a missing key")
	void getTagCompoundDefault()
	{
		NBTTagCompound out = ItemNBTHelper.getTagCompound(ItemStack.EMPTY, "missing");
		assertNotNull(out);
		assertTrue(out.isEmpty());
	}

	@Test
	@DisplayName("getTagCompound() hands back a new compound each time, not a shared one")
	void getTagCompoundDefaultIsNotShared()
	{
		NBTTagCompound a = ItemNBTHelper.getTagCompound(ItemStack.EMPTY, "missing");
		NBTTagCompound b = ItemNBTHelper.getTagCompound(ItemStack.EMPTY, "missing");
		assertNotSame(a, b);
	}

	@Test
	@DisplayName("getItemStack() defaults to the empty stack on a missing key")
	void getItemStackDefault()
	{
		assertSame(ItemStack.EMPTY, ItemNBTHelper.getItemStack(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("getFluidStack() defaults to null on a tagless stack")
	void getFluidStackDefault()
	{
		assertNull(ItemNBTHelper.getFluidStack(ItemStack.EMPTY, "missing"));
	}

	@Test
	@DisplayName("remove() on a tagless stack is a no-op rather than an error")
	void removeOnTaglessStack()
	{
		assertDoesNotThrow(() -> ItemNBTHelper.remove(ItemStack.EMPTY, "missing"));
		assertFalse(ItemNBTHelper.hasTag(ItemStack.EMPTY));
	}

	// ---------------------------------------------------------------- flux helpers

	@Test
	@DisplayName("getFluxStoredInItem() reads the \"energy\" key and defaults to 0")
	void fluxStoredDefaultsToZero()
	{
		assertEquals(0, ItemNBTHelper.getFluxStoredInItem(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("insertFluxItem() accepts the requested amount when there is room")
	void insertFluxWithRoom()
	{
		assertEquals(100, ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, 100, 1000, true));
	}

	@Test
	@DisplayName("insertFluxItem() clamps to the remaining capacity")
	void insertFluxClampsToCapacity()
	{
		assertEquals(1000, ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, 5000, 1000, true));
	}

	@Test
	@DisplayName("insertFluxItem() accepts nothing into a zero-capacity item")
	void insertFluxZeroCapacity()
	{
		assertEquals(0, ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, 100, 0, true));
	}

	@Test
	@DisplayName("insertFluxItem() of zero accepts zero")
	void insertFluxZeroEnergy()
	{
		assertEquals(0, ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, 0, 1000, true));
	}

	@Test
	@DisplayName("insertFluxItem() passes negative amounts straight through -- this is the reverse-insertion drain EnergyHelper relies on")
	void insertFluxNegativeIsReverseExtraction()
	{
		// EnergyHelper.forceExtractFlux() drains third-party items by inserting negative energy
		// and diffing the stored amount, so a negative request must not be clamped to zero here.
		assertEquals(-100, ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, -100, 1000, true));
	}

	@Test
	@DisplayName("extractFluxFromItem() clamps to the amount actually stored")
	void extractFluxClampsToStored()
	{
		assertEquals(0, ItemNBTHelper.extractFluxFromItem(ItemStack.EMPTY, 100, true));
	}

	@Test
	@DisplayName("extractFluxFromItem() of zero extracts zero")
	void extractFluxZero()
	{
		assertEquals(0, ItemNBTHelper.extractFluxFromItem(ItemStack.EMPTY, 0, true));
	}

	@Test
	@DisplayName("simulated flux transfers leave the stack untouched")
	void simulatedFluxDoesNotWrite()
	{
		ItemNBTHelper.insertFluxItem(ItemStack.EMPTY, 100, 1000, true);
		ItemNBTHelper.extractFluxFromItem(ItemStack.EMPTY, 100, true);
		assertFalse(ItemNBTHelper.hasTag(ItemStack.EMPTY));
	}

	// ---------------------------------------------------------------- modifyInt / modifyFloat

	@Test
	@DisplayName("modifyInt() on a missing key starts from zero")
	void modifyIntFromMissingKey()
	{
		NBTTagCompound tag = new NBTTagCompound();
		ItemNBTHelper.modifyInt(tag, "n", 7);
		assertEquals(7, tag.getInteger("n"));
	}

	@Test
	@DisplayName("modifyInt() adds to an existing value")
	void modifyIntAdds()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("n", 10);
		ItemNBTHelper.modifyInt(tag, "n", 5);
		assertEquals(15, tag.getInteger("n"));
	}

	@Test
	@DisplayName("modifyInt() with a negative delta subtracts, and may go negative")
	void modifyIntSubtracts()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("n", 3);
		ItemNBTHelper.modifyInt(tag, "n", -10);
		assertEquals(-7, tag.getInteger("n"));
	}

	@Test
	@DisplayName("modifyInt() by zero leaves the value alone but creates the key")
	void modifyIntByZero()
	{
		NBTTagCompound tag = new NBTTagCompound();
		ItemNBTHelper.modifyInt(tag, "n", 0);
		assertTrue(tag.hasKey("n"));
		assertEquals(0, tag.getInteger("n"));
	}

	@Test
	@DisplayName("modifyInt() touches only its own key")
	void modifyIntIsScopedToOneKey()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("a", 1);
		tag.setInteger("b", 2);
		ItemNBTHelper.modifyInt(tag, "a", 10);
		assertEquals(11, tag.getInteger("a"));
		assertEquals(2, tag.getInteger("b"));
	}

	@Test
	@DisplayName("modifyFloat() on a missing key starts from zero")
	void modifyFloatFromMissingKey()
	{
		NBTTagCompound tag = new NBTTagCompound();
		ItemNBTHelper.modifyFloat(tag, "f", 1.5f);
		assertEquals(1.5f, tag.getFloat("f"), 1e-6f);
	}

	@Test
	@DisplayName("modifyFloat() adds to an existing value")
	void modifyFloatAdds()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setFloat("f", 2.25f);
		ItemNBTHelper.modifyFloat(tag, "f", 0.5f);
		assertEquals(2.75f, tag.getFloat("f"), 1e-6f);
	}

	@Test
	@DisplayName("modifyFloat() with a negative delta subtracts")
	void modifyFloatSubtracts()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setFloat("f", 1f);
		ItemNBTHelper.modifyFloat(tag, "f", -2.5f);
		assertEquals(-1.5f, tag.getFloat("f"), 1e-6f);
	}

	// ---------------------------------------------------------------- combineTags

	@Nested
	@DisplayName("combineTags")
	class CombineTags
	{
		private NBTTagCompound add(String key, int value)
		{
			NBTTagCompound t = new NBTTagCompound();
			t.setInteger(key, value);
			return t;
		}

		@Test
		@DisplayName("a null target yields a copy of the addition")
		void nullTarget()
		{
			NBTTagCompound addition = add("n", 5);
			NBTTagCompound result = ItemNBTHelper.combineTags(null, addition, null, false);
			assertEquals(5, result.getInteger("n"));
			assertNotSame(addition, result, "the addition must be copied, not aliased");
		}

		@Test
		@DisplayName("an empty target yields a copy of the addition")
		void emptyTarget()
		{
			NBTTagCompound addition = add("n", 5);
			NBTTagCompound result = ItemNBTHelper.combineTags(new NBTTagCompound(), addition, null, false);
			assertEquals(5, result.getInteger("n"));
			assertNotSame(addition, result);
		}

		@Test
		@DisplayName("a non-empty target is modified in place and returned")
		void returnsTheTargetInstance()
		{
			NBTTagCompound target = add("a", 1);
			assertSame(target, ItemNBTHelper.combineTags(target, add("b", 2), null, false));
		}

		@Test
		@DisplayName("keys the target does not have are copied over")
		void copiesMissingKeys()
		{
			NBTTagCompound target = add("a", 1);
			ItemNBTHelper.combineTags(target, add("b", 2), null, false);
			assertEquals(1, target.getInteger("a"));
			assertEquals(2, target.getInteger("b"));
		}

		@Test
		@DisplayName("the addition is left untouched")
		void additionIsNotModified()
		{
			NBTTagCompound target = add("n", 1);
			NBTTagCompound addition = add("n", 2);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals(2, addition.getInteger("n"), "combineTags must not write into its second argument");
		}

		@Test
		@DisplayName("bytes are added")
		void bytesAdd()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setByte("v", (byte)3);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setByte("v", (byte)4);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals((byte)7, target.getByte("v"));
		}

		@Test
		@DisplayName("shorts are added")
		void shortsAdd()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setShort("v", (short)300);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setShort("v", (short)45);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals((short)345, target.getShort("v"));
		}

		@Test
		@DisplayName("ints are added")
		void intsAdd()
		{
			NBTTagCompound target = add("v", 100);
			ItemNBTHelper.combineTags(target, add("v", 23), null, false);
			assertEquals(123, target.getInteger("v"));
		}

		@Test
		@DisplayName("longs are added")
		void longsAdd()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setLong("v", 5_000_000_000L);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setLong("v", 1L);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals(5_000_000_001L, target.getLong("v"));
		}

		@Test
		@DisplayName("floats are added when multiplyDecimals is false")
		void floatsAdd()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setFloat("v", 1.5f);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setFloat("v", 2f);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals(3.5f, target.getFloat("v"), 1e-6f);
		}

		@Test
		@DisplayName("floats are multiplied when multiplyDecimals is true")
		void floatsMultiply()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setFloat("v", 1.5f);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setFloat("v", 2f);
			ItemNBTHelper.combineTags(target, addition, null, true);
			assertEquals(3f, target.getFloat("v"), 1e-6f);
		}

		@Test
		@DisplayName("doubles are added when multiplyDecimals is false")
		void doublesAdd()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setDouble("v", 0.25);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setDouble("v", 0.5);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals(0.75, target.getDouble("v"), 1e-9);
		}

		@Test
		@DisplayName("doubles are multiplied when multiplyDecimals is true")
		void doublesMultiply()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setDouble("v", 0.25);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setDouble("v", 0.5);
			ItemNBTHelper.combineTags(target, addition, null, true);
			assertEquals(0.125, target.getDouble("v"), 1e-9);
		}

		@Test
		@DisplayName("multiplyDecimals does not affect integer types")
		void multiplyDecimalsLeavesIntegersAlone()
		{
			NBTTagCompound target = add("v", 3);
			ItemNBTHelper.combineTags(target, add("v", 4), null, true);
			assertEquals(7, target.getInteger("v"), "ints are always summed, even in multiply mode");
		}

		@Test
		@DisplayName("strings are concatenated, target first")
		void stringsConcatenate()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setString("v", "foo");
			NBTTagCompound addition = new NBTTagCompound();
			addition.setString("v", "bar");
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals("foobar", target.getString("v"));
		}

		@Test
		@DisplayName("byte arrays are concatenated, target first")
		void byteArraysConcatenate()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setByteArray("v", new byte[]{1, 2});
			NBTTagCompound addition = new NBTTagCompound();
			addition.setByteArray("v", new byte[]{3});
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertArrayEquals(new byte[]{1, 2, 3}, target.getByteArray("v"));
		}

		@Test
		@DisplayName("int arrays are concatenated, target first")
		void intArraysConcatenate()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setIntArray("v", new int[]{1, 2});
			NBTTagCompound addition = new NBTTagCompound();
			addition.setIntArray("v", new int[]{3, 4});
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertArrayEquals(new int[]{1, 2, 3, 4}, target.getIntArray("v"));
		}

		@Test
		@DisplayName("lists are appended")
		void listsAppend()
		{
			NBTTagCompound target = new NBTTagCompound();
			NBTTagList listA = new NBTTagList();
			listA.appendTag(new NBTTagString("a"));
			target.setTag("v", listA);

			NBTTagCompound addition = new NBTTagCompound();
			NBTTagList listB = new NBTTagList();
			listB.appendTag(new NBTTagString("b"));
			listB.appendTag(new NBTTagString("c"));
			addition.setTag("v", listB);

			ItemNBTHelper.combineTags(target, addition, null, false);

			NBTTagList result = target.getTagList("v", 8);
			assertEquals(3, result.tagCount());
			assertEquals("a", result.getStringTagAt(0));
			assertEquals("b", result.getStringTagAt(1));
			assertEquals("c", result.getStringTagAt(2));
		}

		@Test
		@DisplayName("nested compounds are combined recursively")
		void compoundsRecurse()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setTag("inner", add("n", 1));
			NBTTagCompound addition = new NBTTagCompound();
			addition.setTag("inner", add("n", 4));

			ItemNBTHelper.combineTags(target, addition, null, false);

			assertEquals(5, target.getCompoundTag("inner").getInteger("n"));
		}

		@Test
		@DisplayName("nested compounds pick up keys the target is missing")
		void compoundsRecurseAndCopyMissingKeys()
		{
			NBTTagCompound target = new NBTTagCompound();
			target.setTag("inner", add("a", 1));
			NBTTagCompound addition = new NBTTagCompound();
			addition.setTag("inner", add("b", 2));

			ItemNBTHelper.combineTags(target, addition, null, false);

			assertEquals(1, target.getCompoundTag("inner").getInteger("a"));
			assertEquals(2, target.getCompoundTag("inner").getInteger("b"));
		}

		@Test
		@DisplayName("a null pattern lets every key through")
		void nullPatternMatchesEverything()
		{
			NBTTagCompound target = add("keep", 1);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setInteger("keep", 1);
			addition.setInteger("other", 9);
			ItemNBTHelper.combineTags(target, addition, null, false);
			assertEquals(2, target.getInteger("keep"));
			assertTrue(target.hasKey("other"));
		}

		@Test
		@DisplayName("a pattern filters which keys are combined")
		void patternFiltersKeys()
		{
			NBTTagCompound target = add("keep", 1);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setInteger("keep", 1);
			addition.setInteger("skip", 9);
			ItemNBTHelper.combineTags(target, addition, Pattern.compile("keep"), false);
			assertEquals(2, target.getInteger("keep"));
			assertFalse(target.hasKey("skip"), "a key the pattern rejects must not be copied over");
		}

		@Test
		@DisplayName("the pattern must match the whole key, not just part of it")
		void patternMatchesWholeKey()
		{
			NBTTagCompound target = add("a", 1);
			NBTTagCompound addition = new NBTTagCompound();
			addition.setInteger("prefix_b", 5);
			ItemNBTHelper.combineTags(target, addition, Pattern.compile("b"), false);
			assertFalse(target.hasKey("prefix_b"), "matches(), not find(), so a partial match is rejected");
		}

		@Test
		@DisplayName("a pattern that matches nothing leaves the target alone")
		void patternMatchingNothing()
		{
			NBTTagCompound target = add("a", 1);
			ItemNBTHelper.combineTags(target, add("b", 2), Pattern.compile("zzz"), false);
			assertEquals(1, target.getInteger("a"));
			assertEquals(1, target.getKeySet().size());
		}

		@Test
		@DisplayName("combining an empty addition into a populated target changes nothing")
		void emptyAddition()
		{
			NBTTagCompound target = add("a", 1);
			ItemNBTHelper.combineTags(target, new NBTTagCompound(), null, false);
			assertEquals(1, target.getInteger("a"));
			assertEquals(1, target.getKeySet().size());
		}

		@Test
		@DisplayName("combining is repeatable -- applying the same addition twice doubles the delta")
		void repeatedCombination()
		{
			NBTTagCompound target = add("v", 0);
			ItemNBTHelper.combineTags(target, add("v", 5), null, false);
			ItemNBTHelper.combineTags(target, add("v", 5), null, false);
			assertEquals(10, target.getInteger("v"));
		}
	}
}
