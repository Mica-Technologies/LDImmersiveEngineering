/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.testutil;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the test harness itself works: JUnit 5 runs, and the deobfuscated Minecraft
 * classes on the compile classpath are usable from tests without an FML bootstrap.
 * <p>
 * If this class fails, no other test in the project is trustworthy -- fix the harness
 * before reading any other failure.
 */
class HarnessSmokeTest
{
	@Test
	@DisplayName("JUnit 5 is wired up")
	void junitRuns()
	{
		assertTrue(true, "if this fails, useJUnitPlatform() is not in effect");
	}

	@Test
	@DisplayName("NBTTagCompound is usable without a Minecraft bootstrap")
	void nbtWorksUnbootstrapped()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("i", 42);
		tag.setString("s", "hello");
		tag.setBoolean("b", true);
		tag.setDouble("d", 0.25);
		tag.setLong("l", 1234567890123L);

		assertEquals(42, tag.getInteger("i"));
		assertEquals("hello", tag.getString("s"));
		assertTrue(tag.getBoolean("b"));
		assertEquals(0.25, tag.getDouble("d"), 0.0);
		assertEquals(1234567890123L, tag.getLong("l"));
		assertFalse(tag.isEmpty());
	}

	@Test
	@DisplayName("BlockPos maths is usable without a Minecraft bootstrap")
	void blockPosWorksUnbootstrapped()
	{
		BlockPos pos = new BlockPos(3, 64, -7);
		assertEquals(3, pos.getX());
		assertEquals(64, pos.getY());
		assertEquals(-7, pos.getZ());
		assertEquals(new BlockPos(4, 64, -7), pos.offset(EnumFacing.EAST));
		assertEquals(new BlockPos(3, 65, -7), pos.up());
	}

	@Test
	@DisplayName("EnumFacing is usable without a Minecraft bootstrap")
	void enumFacingWorksUnbootstrapped()
	{
		assertEquals(EnumFacing.DOWN, EnumFacing.UP.getOpposite());
		assertEquals(EnumFacing.EAST, EnumFacing.NORTH.rotateY());
		assertEquals(6, EnumFacing.VALUES.length);
	}
}
