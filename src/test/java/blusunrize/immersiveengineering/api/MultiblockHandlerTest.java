/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import blusunrize.immersiveengineering.api.MultiblockHandler.IMultiblock;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the registry bookkeeping in {@link MultiblockHandler}.
 * <p>
 * The registry is a plain static list that everything else iterates, so this fixture snapshots it
 * before each test and restores it afterwards -- registering test doubles must not leak into the
 * real registry or into any other test class.
 */
class MultiblockHandlerTest
{
	private List<IMultiblock> snapshot;

	/**
	 * A multiblock that implements only what the registry itself needs. Everything that would
	 * require a live world, a registry or an ItemStack is deliberately inert.
	 */
	private static class DummyMultiblock implements IMultiblock
	{
		private final String name;

		DummyMultiblock(String name)
		{
			this.name = name;
		}

		@Override
		public String getUniqueName()
		{
			return name;
		}

		@Override
		public boolean isBlockTrigger(IBlockState state)
		{
			return false;
		}

		@Override
		public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
		{
			return false;
		}

		@Override
		public ItemStack[][][] getStructureManual()
		{
			return null;
		}

		@Override
		public IngredientStack[] getTotalMaterials()
		{
			return null;
		}

		@Override
		public boolean overwriteBlockRender(ItemStack stack, int iterator)
		{
			return false;
		}

		@Override
		public float getManualScale()
		{
			return 1;
		}

		@Override
		public boolean canRenderFormedStructure()
		{
			return false;
		}

		@Override
		public void renderFormedStructure()
		{
		}
	}

	@BeforeEach
	void snapshotRegistry()
	{
		snapshot = new ArrayList<>(MultiblockHandler.getMultiblocks());
	}

	@AfterEach
	void restoreRegistry()
	{
		MultiblockHandler.getMultiblocks().clear();
		MultiblockHandler.getMultiblocks().addAll(snapshot);
	}

	@Test
	@DisplayName("getMultiblocks() hands out the live backing list")
	void getMultiblocksIsTheLiveList()
	{
		assertNotNull(MultiblockHandler.getMultiblocks());
		assertSame(MultiblockHandler.getMultiblocks(), MultiblockHandler.getMultiblocks());
	}

	@Test
	@DisplayName("registering appends to the registry")
	void registerAppends()
	{
		int before = MultiblockHandler.getMultiblocks().size();
		IMultiblock mb = new DummyMultiblock("test:alpha");
		MultiblockHandler.registerMultiblock(mb);

		assertEquals(before+1, MultiblockHandler.getMultiblocks().size());
		assertTrue(MultiblockHandler.getMultiblocks().contains(mb));
		assertSame(mb, MultiblockHandler.getMultiblocks().get(before));
	}

	@Test
	@DisplayName("registration order is preserved")
	void registrationOrderIsPreserved()
	{
		int before = MultiblockHandler.getMultiblocks().size();
		IMultiblock first = new DummyMultiblock("test:first");
		IMultiblock second = new DummyMultiblock("test:second");
		IMultiblock third = new DummyMultiblock("test:third");
		MultiblockHandler.registerMultiblock(first);
		MultiblockHandler.registerMultiblock(second);
		MultiblockHandler.registerMultiblock(third);

		assertSame(first, MultiblockHandler.getMultiblocks().get(before));
		assertSame(second, MultiblockHandler.getMultiblocks().get(before+1));
		assertSame(third, MultiblockHandler.getMultiblocks().get(before+2));
	}

	@Test
	@DisplayName("the registry is unfiltered -- the same instance can be registered twice")
	void registryDoesNotDeduplicate()
	{
		// documents current behaviour: nothing guards against a double registration, which is why
		// addons must not call registerMultiblock from both a preInit and an init hook
		int before = MultiblockHandler.getMultiblocks().size();
		IMultiblock mb = new DummyMultiblock("test:duplicate");
		MultiblockHandler.registerMultiblock(mb);
		MultiblockHandler.registerMultiblock(mb);

		assertEquals(before+2, MultiblockHandler.getMultiblocks().size());
	}

	@Test
	@DisplayName("a registered multiblock can be looked up by its unique name")
	void lookupByUniqueName()
	{
		MultiblockHandler.registerMultiblock(new DummyMultiblock("test:findme"));

		IMultiblock found = null;
		for(IMultiblock mb : MultiblockHandler.getMultiblocks())
			if("test:findme".equals(mb.getUniqueName()))
				found = mb;
		assertNotNull(found, "the registered multiblock was not reachable by name");
	}

	@Test
	@DisplayName("every registered multiblock has a non-blank, unique name")
	void realRegistryHasUniqueNames()
	{
		// the hammer's interdiction NBT keys off getUniqueName(), so a collision breaks the tool
		Set<String> seen = new HashSet<>();
		for(IMultiblock mb : MultiblockHandler.getMultiblocks())
		{
			String name = mb.getUniqueName();
			assertNotNull(name, "multiblock with a null unique name: "+mb.getClass());
			assertFalse(name.trim().isEmpty(), "multiblock with a blank unique name: "+mb.getClass());
			assertTrue(seen.add(name), "two multiblocks share the unique name "+name);
		}
	}

	@Test
	@DisplayName("the snapshot/restore fixture actually restores the registry")
	void fixtureRestoresTheRegistry()
	{
		// guards the fixture itself: if this drifts, the tests above start leaking state
		int before = MultiblockHandler.getMultiblocks().size();
		MultiblockHandler.registerMultiblock(new DummyMultiblock("test:leak-check"));
		assertEquals(before+1, MultiblockHandler.getMultiblocks().size());
		restoreRegistry();
		assertEquals(before, MultiblockHandler.getMultiblocks().size());
	}
}
