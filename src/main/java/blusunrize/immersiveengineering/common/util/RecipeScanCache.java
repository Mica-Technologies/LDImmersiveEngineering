/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Caches the result of a linear recipe-list scan for the duration of a single tick.
 * <p>
 * The stone multiblocks (coke oven, blast furnace, alloy smelter) call their {@code getRecipe()}
 * two to four times per tick, and every call re-scans the whole recipe list from the top. That scan
 * is not cheap: recipe inputs are frequently ore dictionary entries, so each candidate walks
 * {@code OreDictionary.getOres()} to compare.
 * <p>
 * The cache key is the identity of the input stacks -- item, damage and count. Every matcher these
 * scans reach bottoms out in {@code OreDictionary.itemMatches(..., false)} (item and damage) and,
 * for {@link blusunrize.immersiveengineering.api.crafting.IngredientStack}, an
 * {@code inputSize <= input.getCount()} test. Nothing else about the stack participates, so two
 * inputs agreeing on those three values always produce the same result. Count is part of the key
 * even where it cannot matter, so this stays correct without depending on which matcher a given
 * recipe type happens to use.
 * <p>
 * Only the scan is cached. The output-space and tank-capacity checks that surround it in each
 * {@code getRecipe()} stay live, so machine behaviour is unchanged.
 * <p>
 * The entry is scoped to one tick so a recipe reload (CraftTweaker) can never leave a stale recipe
 * behind -- the same reasoning used for the multiblock insertion memo.
 *
 * @param <T> the recipe type being looked up
 */
public class RecipeScanCache<T>
{
	private Item itemA;
	private int metaA;
	private int countA;
	private Item itemB;
	private int metaB;
	private int countB;
	private long cachedTick = Long.MIN_VALUE;
	private T cachedRecipe;

	/**
	 * Single-input lookup.
	 *
	 * @param worldTime the current total world time, used to scope the entry to one tick
	 * @param input     the stack being looked up
	 * @param scan      the underlying recipe-list scan, run only on a miss
	 * @return the recipe for {@code input}, or null if there is none
	 */
	@Nullable
	public T get(long worldTime, ItemStack input, Function<ItemStack, T> scan)
	{
		return get(worldTime, input, ItemStack.EMPTY, (a, b) -> scan.apply(a));
	}

	/**
	 * Two-input lookup, for recipes matched against a pair of slots.
	 *
	 * @param worldTime the current total world time, used to scope the entry to one tick
	 * @param inputA    the first stack being looked up
	 * @param inputB    the second stack being looked up
	 * @param scan      the underlying recipe-list scan, run only on a miss
	 * @return the recipe for the pair, or null if there is none
	 */
	@Nullable
	public T get(long worldTime, ItemStack inputA, ItemStack inputB, BiFunction<ItemStack, ItemStack, T> scan)
	{
		Item newItemA = inputA.isEmpty()?null: inputA.getItem();
		int newMetaA = inputA.isEmpty()?0: inputA.getItemDamage();
		int newCountA = inputA.getCount();
		Item newItemB = inputB.isEmpty()?null: inputB.getItem();
		int newMetaB = inputB.isEmpty()?0: inputB.getItemDamage();
		int newCountB = inputB.getCount();

		if(cachedTick==worldTime
				&&itemA==newItemA&&metaA==newMetaA&&countA==newCountA
				&&itemB==newItemB&&metaB==newMetaB&&countB==newCountB)
			return cachedRecipe;

		cachedRecipe = scan.apply(inputA, inputB);
		itemA = newItemA;
		metaA = newMetaA;
		countA = newCountA;
		itemB = newItemB;
		metaB = newMetaB;
		countB = newCountB;
		cachedTick = worldTime;
		return cachedRecipe;
	}
}
