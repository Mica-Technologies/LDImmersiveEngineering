/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link TargetingInfo}, the sub-block hit position that travels over the
 * network and through NBT when a player interacts with a multiblock face.
 */
class TargetingInfoTest
{
	@Test
	@DisplayName("the constructor stores the hit exactly as given")
	void constructorStoresTheHit()
	{
		TargetingInfo info = new TargetingInfo(EnumFacing.SOUTH, 0.25f, 0.5f, 0.75f);
		assertEquals(EnumFacing.SOUTH, info.side);
		assertEquals(0.25f, info.hitX, 0f);
		assertEquals(0.5f, info.hitY, 0f);
		assertEquals(0.75f, info.hitZ, 0f);
	}

	@Test
	@DisplayName("writeToNBT uses the documented keys")
	void writeUsesTheDocumentedKeys()
	{
		NBTTagCompound tag = new NBTTagCompound();
		new TargetingInfo(EnumFacing.WEST, 0.1f, 0.2f, 0.3f).writeToNBT(tag);

		assertTrue(tag.hasKey("side"));
		assertTrue(tag.hasKey("hitX"));
		assertTrue(tag.hasKey("hitY"));
		assertTrue(tag.hasKey("hitZ"));
		assertEquals(EnumFacing.WEST.ordinal(), tag.getInteger("side"));
		assertEquals(0.1f, tag.getFloat("hitX"), 0f);
		assertEquals(0.2f, tag.getFloat("hitY"), 0f);
		assertEquals(0.3f, tag.getFloat("hitZ"), 0f);
	}

	@Test
	@DisplayName("a write/read round trip preserves every side")
	void roundTripPreservesEverySide()
	{
		for(EnumFacing side : EnumFacing.VALUES)
		{
			NBTTagCompound tag = new NBTTagCompound();
			new TargetingInfo(side, 0.125f, 0.375f, 0.625f).writeToNBT(tag);
			TargetingInfo read = TargetingInfo.readFromNBT(tag);

			assertEquals(side, read.side, "side changed for "+side);
			assertEquals(0.125f, read.hitX, 0f);
			assertEquals(0.375f, read.hitY, 0f);
			assertEquals(0.625f, read.hitZ, 0f);
		}
	}

	@Test
	@DisplayName("a round trip preserves awkward float values bit for bit")
	void roundTripPreservesAwkwardFloats()
	{
		float[] samples = {0f, 1f, -1f, 0.1f, 1f/3f, Float.MIN_VALUE, Float.MAX_VALUE};
		for(float sample : samples)
		{
			NBTTagCompound tag = new NBTTagCompound();
			new TargetingInfo(EnumFacing.UP, sample, sample, sample).writeToNBT(tag);
			TargetingInfo read = TargetingInfo.readFromNBT(tag);

			assertEquals(Float.floatToIntBits(sample), Float.floatToIntBits(read.hitX));
			assertEquals(Float.floatToIntBits(sample), Float.floatToIntBits(read.hitY));
			assertEquals(Float.floatToIntBits(sample), Float.floatToIntBits(read.hitZ));
		}
	}

	@Test
	@DisplayName("reading an empty tag yields the zeroed default rather than throwing")
	void readingAnEmptyTagIsSafe()
	{
		TargetingInfo read = TargetingInfo.readFromNBT(new NBTTagCompound());
		assertEquals(EnumFacing.DOWN, read.side, "side 0 must be DOWN");
		assertEquals(0f, read.hitX, 0f);
		assertEquals(0f, read.hitY, 0f);
		assertEquals(0f, read.hitZ, 0f);
	}

	@Test
	@DisplayName("an out-of-range side index wraps instead of throwing")
	void outOfRangeSideIndexWraps()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("side", EnumFacing.VALUES.length);
		assertEquals(EnumFacing.DOWN, TargetingInfo.readFromNBT(tag).side);

		tag.setInteger("side", EnumFacing.VALUES.length+2);
		assertEquals(EnumFacing.NORTH, TargetingInfo.readFromNBT(tag).side);
	}

	@Test
	@DisplayName("writing into a shared tag does not disturb unrelated keys")
	void writeDoesNotClobberUnrelatedKeys()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setString("unrelated", "keep me");
		new TargetingInfo(EnumFacing.EAST, 1f, 1f, 1f).writeToNBT(tag);

		assertEquals("keep me", tag.getString("unrelated"));
		assertEquals(5, tag.getSize());
	}
}
