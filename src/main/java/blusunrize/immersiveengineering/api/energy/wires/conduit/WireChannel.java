/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import javax.annotation.Nullable;

/**
 * One of the sixteen conductors a conduit bundle can carry, named after a dye colour.
 * <p>
 * Sixteen because that is how many dyes Minecraft has, and the colour is not decoration: a bundle
 * is read at its ends, where a coloured band at the mouth says which conductor leaves by which
 * stub. A player who can dye a breakout can identify a circuit by looking at it, which is the
 * whole ergonomic argument for the feature.
 * <p>
 * <strong>This enum deliberately does not reference {@code EnumDyeColor}.</strong> It carries its
 * own copy of the sixteen colour values so that the channel model stays loadable without a
 * Minecraft bootstrap -- the same discipline the virtual grid and fluid network follow, and the
 * reason any of this can be unit-tested at all. {@link #getWoolMeta()} is the bridge for code that
 * does have a game running.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public enum WireChannel
{
	WHITE("white", 0xFFFFFF),
	ORANGE("orange", 0xD87F33),
	MAGENTA("magenta", 0xB24CD8),
	LIGHT_BLUE("light_blue", 0x6699D8),
	YELLOW("yellow", 0xE5E533),
	LIME("lime", 0x7FCC19),
	PINK("pink", 0xF27FA5),
	GRAY("gray", 0x4C4C4C),
	SILVER("silver", 0x999999),
	CYAN("cyan", 0x4C7F99),
	PURPLE("purple", 0x7F3FB2),
	BLUE("blue", 0x334CB2),
	BROWN("brown", 0x664C33),
	GREEN("green", 0x667F33),
	RED("red", 0x993333),
	BLACK("black", 0x191919);

	/**
	 * Cached because {@code values()} allocates a fresh array on every call, and the transfer loop
	 * walks all sixteen per connection per tick.
	 */
	public static final WireChannel[] VALUES = values();

	/**
	 * Every channel at once, as a bit mask. The bundle is exactly sixteen wide, so a set of
	 * channels fits in an {@code int} with room to spare -- which is what lets a channel set be
	 * compared, intersected and saved without allocating.
	 */
	public static final int ALL_MASK = (1 << VALUES.length)-1;

	private final String name;
	private final int colour;

	WireChannel(String name, int colour)
	{
		this.name = name;
		this.colour = colour;
	}

	/**
	 * @return the stable name used in NBT and in commands. Never changes, whatever the enum
	 * constant is called.
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * @return packed 0xRRGGBB, for the band at the mouth of the conduit and for the console list.
	 */
	public int getColour()
	{
		return colour;
	}

	/**
	 * @return this channel as a one-bit mask
	 */
	public int getMask()
	{
		return 1 << ordinal();
	}

	/**
	 * The metadata a matching wool or dyed block would have.
	 * <p>
	 * This enum is ordered to match {@code EnumDyeColor}, so the ordinal <em>is</em> the wool
	 * metadata. The method exists anyway, because that correspondence is the sort of thing a
	 * refactor breaks silently, and a named accessor gives it somewhere to be tested.
	 */
	public int getWoolMeta()
	{
		return ordinal();
	}

	@Nullable
	public static WireChannel byName(@Nullable String name)
	{
		if(name==null)
			return null;
		for(WireChannel channel : VALUES)
			if(channel.name.equals(name))
				return channel;
		return null;
	}

	/**
	 * @return the channel at that index, or null if it is out of range. Used when reading save data
	 * and packets, where the number came from outside and cannot be trusted.
	 */
	@Nullable
	public static WireChannel byIndex(int index)
	{
		return index >= 0&&index < VALUES.length?VALUES[index]: null;
	}
}
