/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.tool.ExternalHeaterHandler.HeatableAdapter;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the adapter registry of {@link ExternalHeaterHandler}: a plain map plus a walk up
 * the tile entity class hierarchy. The furnace adapter itself needs a live world and is
 * not covered.
 */
class ExternalHeaterHandlerTest
{
	private HashMap<Class<? extends TileEntity>, HeatableAdapter> savedAdapters;
	private int savedEnergyCost;
	private int savedSpeedupCost;

	@BeforeEach
	void isolateStatics()
	{
		savedAdapters = ExternalHeaterHandler.adapterMap;
		savedEnergyCost = ExternalHeaterHandler.defaultFurnaceEnergyCost;
		savedSpeedupCost = ExternalHeaterHandler.defaultFurnaceSpeedupCost;
		ExternalHeaterHandler.adapterMap = new HashMap<>();
	}

	@AfterEach
	void restoreStatics()
	{
		ExternalHeaterHandler.adapterMap = savedAdapters;
		ExternalHeaterHandler.defaultFurnaceEnergyCost = savedEnergyCost;
		ExternalHeaterHandler.defaultFurnaceSpeedupCost = savedSpeedupCost;
	}

	// Only the Class objects matter, so these never need to be instantiated.
	private abstract static class BaseTile extends TileEntity
	{
	}

	private abstract static class MiddleTile extends BaseTile
	{
	}

	private abstract static class LeafTile extends MiddleTile
	{
	}

	private abstract static class UnrelatedTile extends TileEntity
	{
	}

	private static HeatableAdapter<TileEntity> adapter()
	{
		return new HeatableAdapter<TileEntity>()
		{
			@Override
			public int doHeatTick(TileEntity tileEntity, int energyAvailable, boolean canHeat)
			{
				return energyAvailable;
			}
		};
	}

	@Test
	@DisplayName("a directly registered adapter is found")
	void directLookup()
	{
		HeatableAdapter a = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, a);

		assertSame(a, ExternalHeaterHandler.getHeatableAdapter(MiddleTile.class));
	}

	@Test
	@DisplayName("a subclass inherits the adapter registered on its parent")
	void subclassInheritsAdapter()
	{
		HeatableAdapter a = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, a);

		assertSame(a, ExternalHeaterHandler.getHeatableAdapter(LeafTile.class));
	}

	@Test
	@DisplayName("the walk up the hierarchy is not limited to one level")
	void lookupWalksSeveralLevels()
	{
		HeatableAdapter a = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(BaseTile.class, a);

		assertSame(a, ExternalHeaterHandler.getHeatableAdapter(LeafTile.class));
	}

	@Test
	@DisplayName("the nearest registered ancestor wins")
	void nearestAncestorWins()
	{
		HeatableAdapter outer = adapter();
		HeatableAdapter inner = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(BaseTile.class, outer);
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, inner);

		assertSame(inner, ExternalHeaterHandler.getHeatableAdapter(LeafTile.class));
	}

	@Test
	@DisplayName("a resolved adapter is cached against the subclass")
	void resolvedAdapterIsCached()
	{
		HeatableAdapter a = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(BaseTile.class, a);

		assertFalse(ExternalHeaterHandler.adapterMap.containsKey(LeafTile.class));
		ExternalHeaterHandler.getHeatableAdapter(LeafTile.class);
		assertSame(a, ExternalHeaterHandler.adapterMap.get(LeafTile.class));
	}

	@Test
	@DisplayName("an unrelated tile entity gets no adapter")
	void unrelatedTileHasNoAdapter()
	{
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, adapter());

		assertNull(ExternalHeaterHandler.getHeatableAdapter(UnrelatedTile.class));
	}

	@Test
	@DisplayName("looking up TileEntity itself never recurses")
	void tileEntityRootIsNotWalked()
	{
		assertNull(ExternalHeaterHandler.getHeatableAdapter(TileEntity.class));
	}

	@Test
	@DisplayName("re-registering replaces the previous adapter")
	void reRegistrationReplaces()
	{
		HeatableAdapter first = adapter();
		HeatableAdapter second = adapter();
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, first);
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, second);

		assertSame(second, ExternalHeaterHandler.getHeatableAdapter(MiddleTile.class));
	}

	@Test
	@DisplayName("the registered adapter is the one that actually runs")
	void adapterIsInvokable()
	{
		ExternalHeaterHandler.registerHeatableAdapter(MiddleTile.class, adapter());

		HeatableAdapter found = ExternalHeaterHandler.getHeatableAdapter(MiddleTile.class);
		assertEquals(128, found.doHeatTick(null, 128, true));
	}
}
