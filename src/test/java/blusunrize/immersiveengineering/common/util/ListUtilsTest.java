/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link NonNullList} builders.
 * <p>
 * Scope note: only {@link ItemStack#EMPTY} is available as a stack instance -- building a real
 * one needs the item registry -- so these tests exercise sizes, ordering, null handling and
 * aliasing rather than stack contents.
 */
class ListUtilsTest
{
	@Test
	@DisplayName("fromItem() wraps a single stack")
	void fromItemSingle()
	{
		NonNullList<ItemStack> list = ListUtils.fromItem(ItemStack.EMPTY);
		assertEquals(1, list.size());
		assertSame(ItemStack.EMPTY, list.get(0));
	}

	@Test
	@DisplayName("fromItem(null) yields an empty list rather than throwing")
	void fromItemNull()
	{
		NonNullList<ItemStack> list = ListUtils.fromItem(null);
		assertNotNull(list);
		assertTrue(list.isEmpty());
	}

	@Test
	@DisplayName("fromItem() returns a fresh list each call")
	void fromItemReturnsFreshList()
	{
		assertNotSame(ListUtils.fromItem(ItemStack.EMPTY), ListUtils.fromItem(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("fromItem() returns a mutable list")
	void fromItemIsMutable()
	{
		NonNullList<ItemStack> list = ListUtils.fromItem(ItemStack.EMPTY);
		list.add(ItemStack.EMPTY);
		assertEquals(2, list.size());
	}

	@Test
	@DisplayName("fromItems() with no arguments yields an empty list")
	void fromItemsVarargsEmpty()
	{
		NonNullList<ItemStack> list = ListUtils.fromItems();
		assertTrue(list.isEmpty());
	}

	@Test
	@DisplayName("fromItems() keeps one entry per argument")
	void fromItemsVarargsMany()
	{
		NonNullList<ItemStack> list = ListUtils.fromItems(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertEquals(3, list.size());
	}

	@Test
	@DisplayName("fromItems() substitutes ItemStack.EMPTY for null arguments")
	void fromItemsVarargsNullBecomesEmptyStack()
	{
		NonNullList<ItemStack> list = ListUtils.fromItems(null, ItemStack.EMPTY, null);
		assertEquals(3, list.size());
		assertSame(ItemStack.EMPTY, list.get(0));
		assertSame(ItemStack.EMPTY, list.get(1));
		assertSame(ItemStack.EMPTY, list.get(2));
	}

	@Test
	@DisplayName("fromItems() preserves argument order")
	void fromItemsVarargsPreservesOrder()
	{
		ItemStack[] input = {ItemStack.EMPTY, null, ItemStack.EMPTY};
		NonNullList<ItemStack> list = ListUtils.fromItems(input);
		for(int i = 0; i < input.length; i++)
			assertSame(ItemStack.EMPTY, list.get(i), "index "+i);
	}

	@Test
	@DisplayName("fromItems() does not alias the input array")
	void fromItemsVarargsCopies()
	{
		ItemStack[] input = {ItemStack.EMPTY};
		NonNullList<ItemStack> list = ListUtils.fromItems(input);
		list.add(ItemStack.EMPTY);
		assertEquals(1, input.length, "the source array must not grow with the list");
		assertEquals(2, list.size());
	}

	@Test
	@DisplayName("fromItems(List) copies an empty list")
	void fromItemsListEmpty()
	{
		NonNullList<ItemStack> list = ListUtils.fromItems(Collections.<ItemStack>emptyList());
		assertTrue(list.isEmpty());
	}

	@Test
	@DisplayName("fromItems(List) copies a single-element list")
	void fromItemsListSingle()
	{
		NonNullList<ItemStack> list = ListUtils.fromItems(Collections.singletonList(ItemStack.EMPTY));
		assertEquals(1, list.size());
		assertSame(ItemStack.EMPTY, list.get(0));
	}

	@Test
	@DisplayName("fromItems(List) copies a many-element list")
	void fromItemsListMany()
	{
		List<ItemStack> src = Arrays.asList(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertEquals(4, ListUtils.fromItems(src).size());
	}

	@Test
	@DisplayName("fromItems(List) makes an independent copy")
	void fromItemsListIsACopy()
	{
		List<ItemStack> src = new ArrayList<>();
		src.add(ItemStack.EMPTY);
		NonNullList<ItemStack> list = ListUtils.fromItems(src);
		src.add(ItemStack.EMPTY);
		assertEquals(1, list.size(), "later changes to the source must not show up in the result");
	}

	@Test
	@DisplayName("fromItems(List) rejects a null element, as a NonNullList must")
	void fromItemsListRejectsNulls()
	{
		// unlike the varargs overload, the List overload has no null substitution
		List<ItemStack> src = Collections.singletonList(null);
		assertThrows(NullPointerException.class, () -> ListUtils.fromItems(src));
	}

	@Test
	@DisplayName("the resulting list rejects nulls added later")
	void resultRejectsNulls()
	{
		NonNullList<ItemStack> list = ListUtils.fromItem(ItemStack.EMPTY);
		assertThrows(NullPointerException.class, () -> list.add(null));
	}

	@Test
	@DisplayName("ListUtils is a non-instantiable utility class")
	void utilityClassShape()
	{
		assertTrue(java.lang.reflect.Modifier.isFinal(ListUtils.class.getModifiers()));
		assertEquals(0, ListUtils.class.getConstructors().length);
	}
}
