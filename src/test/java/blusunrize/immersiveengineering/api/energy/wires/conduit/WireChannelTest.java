/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sixteen conductors a bundle can carry.
 * <p>
 * Small class, but three of the things it asserts are load-bearing: the names go into save data,
 * the ordinals are assumed to match wool metadata, and the mask arithmetic is what lets a channel
 * set be intersected without allocating.
 */
class WireChannelTest
{
	@Test
	@DisplayName("there are exactly sixteen, because there are exactly sixteen dyes")
	void sixteenChannels()
	{
		assertEquals(16, WireChannel.VALUES.length);
	}

	@Test
	@DisplayName("the cached values array matches values()")
	void cachedValuesMatch()
	{
		//VALUES exists so the per-tick loop does not allocate. If it ever drifted from values(),
		//half the code would see a different set of channels than the other half.
		assertArrayEquals(WireChannel.values(), WireChannel.VALUES);
	}

	@Test
	@DisplayName("names are unique and stable")
	void namesAreUnique()
	{
		//These strings are NBT keys. A duplicate would mean two channels sharing a slot in save
		//data, and the second would silently overwrite the first.
		Set<String> seen = new HashSet<>();
		for(WireChannel channel : WireChannel.VALUES)
			assertTrue(seen.add(channel.getName()), channel+" shares a name with another channel");
	}

	@Test
	@DisplayName("names round-trip through byName")
	void namesRoundTrip()
	{
		for(WireChannel channel : WireChannel.VALUES)
			assertSame(channel, WireChannel.byName(channel.getName()));
	}

	@Test
	@DisplayName("an unknown or null name gives null rather than a default")
	void unknownNameIsNull()
	{
		//Defaulting to WHITE here would mean a typo in a command, or a channel removed by a future
		//version, quietly patching the wrong conductor.
		assertNull(WireChannel.byName("chartreuse"));
		assertNull(WireChannel.byName(""));
		assertNull(WireChannel.byName(null));
	}

	@Test
	@DisplayName("byIndex refuses anything out of range")
	void indexIsBoundsChecked()
	{
		assertSame(WireChannel.WHITE, WireChannel.byIndex(0));
		assertSame(WireChannel.BLACK, WireChannel.byIndex(15));
		assertNull(WireChannel.byIndex(-1));
		assertNull(WireChannel.byIndex(16));
		assertNull(WireChannel.byIndex(Integer.MAX_VALUE));
	}

	@Test
	@DisplayName("the ordinal is the wool metadata")
	void ordinalIsWoolMeta()
	{
		//The enum is ordered to match EnumDyeColor deliberately, so a dyed breakout stub can be
		//matched to a channel by metadata alone. Nothing enforces that ordering but this test.
		assertEquals(0, WireChannel.WHITE.getWoolMeta());
		assertEquals(1, WireChannel.ORANGE.getWoolMeta());
		assertEquals(4, WireChannel.YELLOW.getWoolMeta());
		assertEquals(11, WireChannel.BLUE.getWoolMeta());
		assertEquals(14, WireChannel.RED.getWoolMeta());
		assertEquals(15, WireChannel.BLACK.getWoolMeta());
		for(WireChannel channel : WireChannel.VALUES)
			assertEquals(channel.ordinal(), channel.getWoolMeta());
	}

	@Test
	@DisplayName("every channel has its own bit")
	void masksAreDistinctBits()
	{
		int seen = 0;
		for(WireChannel channel : WireChannel.VALUES)
		{
			int mask = channel.getMask();
			assertEquals(1, Integer.bitCount(mask), channel+" is not a single bit");
			assertEquals(0, seen&mask, channel+" shares a bit with an earlier channel");
			seen |= mask;
		}
		assertEquals(WireChannel.ALL_MASK, seen);
	}

	@Test
	@DisplayName("all sixteen fit in an int with room left over")
	void maskFitsInAnInt()
	{
		//The mask is an int on purpose. If the channel count ever grew past 31 this stops being
		//true, and everything that intersects two sets silently starts losing the top channels.
		assertTrue(WireChannel.VALUES.length < 31);
		assertEquals(16, Integer.bitCount(WireChannel.ALL_MASK));
		assertTrue(WireChannel.ALL_MASK > 0, "the mask must not have run into the sign bit");
	}

	@Test
	@DisplayName("colours are distinct and opaque-safe")
	void coloursAreDistinct()
	{
		//Two channels the same colour would defeat the entire point of colouring the ends.
		Set<Integer> seen = new HashSet<>();
		for(WireChannel channel : WireChannel.VALUES)
		{
			int colour = channel.getColour();
			assertTrue(seen.add(colour), channel+" shares a colour with another channel");
			assertEquals(0, colour&0xFF000000,
					channel+" has alpha set; getColour is 0xRRGGBB and callers add their own");
		}
	}
}
