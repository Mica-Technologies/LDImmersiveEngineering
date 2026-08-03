/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase.IBlockEnum;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared assertions for the {@code BlockTypes_*} enums.
 * <p>
 * Every one of those enums is used as the metadata of a block: {@code getMeta()} is written into
 * chunk data and {@code getName()} is written into blockstate/model files. Both are therefore part
 * of the world-save format and must never drift. These helpers pin down the invariants that hold
 * for <em>all</em> of them; the per-enum test classes additionally pin the exact ordinal of every
 * single constant.
 */
public final class BlockEnumTestSupport
{
	/**
	 * A block's metadata is four bits wide, so no meta-indexed enum may ever exceed 16 constants.
	 */
	public static final int MAX_META = 16;

	private BlockEnumTestSupport()
	{
	}

	/**
	 * Asserts that {@code getMeta()} is the ordinal, that metas are unique and that they are
	 * contiguous starting at 0.
	 */
	public static <E extends Enum<E> & IBlockEnum> void assertMetaIsOrdinal(E[] values)
	{
		assertTrue(values.length > 0, "enum has no constants at all");

		Set<Integer> seen = new HashSet<>();
		for(E value : values)
		{
			assertEquals(value.ordinal(), value.getMeta(),
					"getMeta() must be the ordinal for "+value.name());
			assertTrue(value.getMeta() >= 0, "negative meta for "+value.name());
			assertTrue(seen.add(value.getMeta()), "duplicate meta "+value.getMeta()+" at "+value.name());
		}
		for(int i = 0; i < values.length; i++)
			assertTrue(seen.contains(i), "meta values are not contiguous, "+i+" is missing");
	}

	/**
	 * Asserts that every constant still addresses a legal block metadata value. A block stores its
	 * metadata in four bits, so a seventeenth constant simply cannot be placed, saved or loaded.
	 */
	public static <E extends Enum<E> & IBlockEnum> void assertFitsInBlockMetadata(E[] values)
	{
		//The message says what to do instead, because the person who trips this is mid-way through
		//adding a block and the obvious next move -- deleting something to make room -- is the one
		//that corrupts every existing world.
		assertTrue(values.length <= MAX_META,
				"this enum has "+values.length+" constants but block metadata is four bits, so only "
						+MAX_META+" fit. The new one cannot be placed, saved or read back. Give it its "
						+"own block instead, the way the Grid Management Console became "
						+"grid_multiblock when metal_multiblock filled up -- and do NOT remove a "
						+"constant to make room unless it is the last one, because renumbering the "
						+"rest silently turns already-placed blocks into other blocks.");
		for(E value : values)
			assertTrue(value.getMeta() < MAX_META,
					value.name()+" has meta "+value.getMeta()+", which does not fit in four bits");
	}

	/**
	 * Reports how many metadata values an enum has left, so a nearly-full one is visible before
	 * somebody tries to add to it rather than after.
	 *
	 * @return spare slots, 0 when the enum is exactly full
	 */
	public static <E extends Enum<E> & IBlockEnum> int headroom(E[] values)
	{
		return Math.max(0, MAX_META-values.length);
	}

	/**
	 * As {@link #headroom(Enum[])}, for a caller holding the enums as plain {@link IBlockEnum}
	 * arrays -- which is how a test that walks <em>every</em> block enum at once has to hold them,
	 * since they share no common enum type.
	 */
	public static int headroom(IBlockEnum[] values)
	{
		return Math.max(0, MAX_META-values.length);
	}

	/**
	 * Asserts that {@code getName()} is the lowercased constant name, that it is unique within the
	 * enum and that it only uses characters that are legal in a blockstate variant key.
	 */
	public static <E extends Enum<E> & IBlockEnum> void assertNamesAreSerializable(E[] values)
	{
		Set<String> seen = new HashSet<>();
		for(E value : values)
		{
			String name = value.getName();
			assertNotNull(name, "null name for "+value.name());
			assertFalse(name.isEmpty(), "empty name for "+value.name());
			assertEquals(value.name().toLowerCase(Locale.ENGLISH), name,
					"getName() must be the lowercased constant name for "+value.name());
			assertEquals(name.toLowerCase(Locale.ENGLISH), name, "name must be lowercase: "+name);
			assertTrue(name.matches("[a-z0-9_]+"),
					"name is not safe for blockstate/model files: "+name);
			assertTrue(seen.add(name), "duplicate name "+name);
		}
	}

	/** Asserts that every constant shows up in the creative tab. */
	public static <E extends Enum<E> & IBlockEnum> void assertAllListedForCreative(E[] values)
	{
		for(E value : values)
			assertTrue(value.listForCreative(), value.name()+" should be listed for creative");
	}

	/** Asserts that no constant shows up in the creative tab. */
	public static <E extends Enum<E> & IBlockEnum> void assertNoneListedForCreative(E[] values)
	{
		for(E value : values)
			assertFalse(value.listForCreative(), value.name()+" should not be listed for creative");
	}

	/**
	 * Asserts that exactly the named constants are hidden from the creative tab and that every
	 * other constant is shown.
	 */
	@SafeVarargs
	public static <E extends Enum<E> & IBlockEnum> void assertHiddenFromCreative(E[] values, E... hidden)
	{
		Set<E> hiddenSet = new HashSet<>();
		for(E h : hidden)
			hiddenSet.add(h);
		for(E value : values)
			if(hiddenSet.contains(value))
				assertFalse(value.listForCreative(), value.name()+" should be hidden from creative");
			else
				assertTrue(value.listForCreative(), value.name()+" should be listed for creative");
	}
}
