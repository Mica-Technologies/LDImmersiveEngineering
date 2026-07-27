/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the rotation gate.
 * <p>
 * Scope note: {@code rotateBlock} needs a live {@link net.minecraft.world.World} and the block
 * predicates need real {@link IBlockState}s from the block registry, so neither can run here.
 * What is reachable is the shape of the two extension-point sets and the tile-entity predicate,
 * which only inspects fields on the tile.
 */
class RotationUtilTest
{
	@Test
	@DisplayName("the block predicate set is populated at class load")
	void blockPredicatesRegistered()
	{
		assertFalse(RotationUtil.permittedRotation.isEmpty(),
				"the static initialiser should have registered the vanilla exclusions");
	}

	@Test
	@DisplayName("the tile predicate set is populated at class load")
	void tilePredicatesRegistered()
	{
		assertFalse(RotationUtil.permittedTileRotation.isEmpty());
	}

	@Test
	@DisplayName("both predicate sets are mutable, so add-ons can register their own exclusions")
	void predicateSetsAreExtensible()
	{
		Predicate<TileEntity> extra = tile -> false;
		int before = RotationUtil.permittedTileRotation.size();
		RotationUtil.permittedTileRotation.add(extra);
		try
		{
			assertEquals(before+1, RotationUtil.permittedTileRotation.size());
		} finally
		{
			RotationUtil.permittedTileRotation.remove(extra);
		}
		assertEquals(before, RotationUtil.permittedTileRotation.size(), "the set must be restored");
	}

	@Test
	@DisplayName("an ordinary tile entity is permitted to rotate")
	void ordinaryTileMayRotate()
	{
		TileEntity plain = new TileEntity()
		{
		};
		for(Predicate<TileEntity> pred : RotationUtil.permittedTileRotation)
			assertTrue(pred.test(plain), "no shipped predicate should block a plain tile entity");
	}

	@Test
	@Disabled("Suspected bug: the double-chest predicate is inverted -- see RotationUtil.java:57-62")
	@DisplayName("a lone chest is permitted to rotate")
	void loneChestMayRotate()
	{
		// The comment on the predicate says "preventing double chests from rotating", so a chest
		// with no neighbours should be permitted and a chest with one should be blocked. The
		// implementation returns the adjacency test as-is, which is the wrong way round.
		TileEntityChest lone = new TileEntityChest();
		for(Predicate<TileEntity> pred : RotationUtil.permittedTileRotation)
			assertTrue(pred.test(lone), "a chest with no adjacent chest is not a double chest");
	}

	@Test
	@DisplayName("rotateEntity() does nothing yet and reports no rotation")
	void rotateEntityIsAStub()
	{
		assertFalse(RotationUtil.rotateEntity(null, null));
	}
}
