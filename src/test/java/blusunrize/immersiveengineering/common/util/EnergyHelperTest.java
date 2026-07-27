/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEEnergyItem;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxConnector;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the parts of {@link EnergyHelper} that do not need a live tile entity.
 * <p>
 * Scope note: the {@code IIEInternalFluxHandler} defaults for receive/extract cast {@code this}
 * to a {@link net.minecraft.tileentity.TileEntity} to check {@code world.isRemote}, so those two
 * cannot be driven from a unit test. The read-only defaults, the side-config gate and the whole
 * of {@link IEForgeEnergyWrapper} can be, using a plain (non-tile) stand-in.
 */
class EnergyHelperTest
{
	/** A flux connector that is not a tile entity and not a handler. */
	private static class Connector implements IIEInternalFluxConnector
	{
		private final SideConfig config;

		Connector(SideConfig config)
		{
			this.config = config;
		}

		@Nonnull
		@Override
		public SideConfig getEnergySideConfig(@Nullable EnumFacing facing)
		{
			return config;
		}

		@Override
		public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
		{
			return new IEForgeEnergyWrapper(this, facing);
		}
	}

	/** A full flux handler that is not a tile entity, so only the read-only defaults are safe. */
	private static class Handler extends Connector implements IIEInternalFluxHandler
	{
		private final FluxStorage storage;

		Handler(SideConfig config, FluxStorage storage)
		{
			super(config);
			this.storage = storage;
		}

		@Nonnull
		@Override
		public FluxStorage getFluxStorage()
		{
			return storage;
		}
	}

	private static FluxStorage storage(int capacity, int stored)
	{
		FluxStorage f = new FluxStorage(capacity);
		f.receiveEnergy(stored, false);
		return f;
	}

	// ---------------------------------------------------------------- ItemStack entry points

	@Test
	@DisplayName("isFluxItem() is false for an empty stack")
	void isFluxItemEmptyStack()
	{
		assertFalse(EnergyHelper.isFluxItem(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("getEnergyStored() is 0 for an empty stack")
	void getEnergyStoredEmptyStack()
	{
		assertEquals(0, EnergyHelper.getEnergyStored(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("getMaxEnergyStored() is 0 for an empty stack")
	void getMaxEnergyStoredEmptyStack()
	{
		assertEquals(0, EnergyHelper.getMaxEnergyStored(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("insertFlux() into an empty stack moves nothing")
	void insertFluxEmptyStack()
	{
		assertEquals(0, EnergyHelper.insertFlux(ItemStack.EMPTY, 100, false));
		assertEquals(0, EnergyHelper.insertFlux(ItemStack.EMPTY, 100, true));
	}

	@Test
	@DisplayName("extractFlux() from an empty stack moves nothing")
	void extractFluxEmptyStack()
	{
		assertEquals(0, EnergyHelper.extractFlux(ItemStack.EMPTY, 100, false));
		assertEquals(0, EnergyHelper.extractFlux(ItemStack.EMPTY, 100, true));
	}

	@Test
	@DisplayName("forceExtractFlux() short-circuits on an empty stack before touching the item cache")
	void forceExtractFluxEmptyStack()
	{
		assertEquals(0, EnergyHelper.forceExtractFlux(ItemStack.EMPTY, 100, true));
	}

	@Test
	@DisplayName("an empty stack never gets an entry in the reverse-insertion cache")
	void emptyStackDoesNotPolluteTheCache()
	{
		int before = EnergyHelper.reverseInsertion.size();
		EnergyHelper.forceExtractFlux(ItemStack.EMPTY, 100, true);
		assertEquals(before, EnergyHelper.reverseInsertion.size());
	}

	// ---------------------------------------------------------------- TileEntity entry points

	@Test
	@DisplayName("isFluxReceiver() is false for a missing tile")
	void isFluxReceiverNullTile()
	{
		assertFalse(EnergyHelper.isFluxReceiver(null, EnumFacing.NORTH));
		assertFalse(EnergyHelper.isFluxReceiver(null, null));
	}

	@Test
	@DisplayName("insertFlux() into a missing tile moves nothing")
	void insertFluxNullTile()
	{
		assertEquals(0, EnergyHelper.insertFlux(null, EnumFacing.NORTH, 100, false));
		assertEquals(0, EnergyHelper.insertFlux(null, EnumFacing.NORTH, 100, true));
	}

	// ---------------------------------------------------------------- canConnectEnergy

	@Test
	@DisplayName("a side configured NONE does not connect")
	void noneDoesNotConnect()
	{
		assertFalse(new Connector(SideConfig.NONE).canConnectEnergy(EnumFacing.NORTH));
	}

	@Test
	@DisplayName("a side configured INPUT connects")
	void inputConnects()
	{
		assertTrue(new Connector(SideConfig.INPUT).canConnectEnergy(EnumFacing.NORTH));
	}

	@Test
	@DisplayName("a side configured OUTPUT connects")
	void outputConnects()
	{
		assertTrue(new Connector(SideConfig.OUTPUT).canConnectEnergy(EnumFacing.NORTH));
	}

	@Test
	@DisplayName("canConnectEnergy() accepts a null side, meaning \"unknown\"")
	void nullSideConnects()
	{
		assertTrue(new Connector(SideConfig.INPUT).canConnectEnergy(null));
		assertFalse(new Connector(SideConfig.NONE).canConnectEnergy(null));
	}

	@Test
	@DisplayName("every facing is answered from the same side config")
	void connectAnswersEveryFacing()
	{
		Connector c = new Connector(SideConfig.OUTPUT);
		for(EnumFacing facing : EnumFacing.VALUES)
			assertTrue(c.canConnectEnergy(facing), facing.toString());
	}

	// ---------------------------------------------------------------- handler read-only defaults

	@Test
	@DisplayName("getEnergyStored() reads straight through to the flux storage")
	void handlerReadsStoredEnergy()
	{
		Handler h = new Handler(SideConfig.INPUT, storage(1000, 250));
		assertEquals(250, h.getEnergyStored(EnumFacing.NORTH));
	}

	@Test
	@DisplayName("getMaxEnergyStored() reads straight through to the flux storage")
	void handlerReadsCapacity()
	{
		Handler h = new Handler(SideConfig.INPUT, storage(1000, 250));
		assertEquals(1000, h.getMaxEnergyStored(EnumFacing.NORTH));
	}

	@Test
	@DisplayName("the read-only defaults ignore which side is asked, including null")
	void handlerReadsAreSideIndependent()
	{
		Handler h = new Handler(SideConfig.NONE, storage(1000, 250));
		for(EnumFacing facing : EnumFacing.VALUES)
			assertEquals(250, h.getEnergyStored(facing), facing.toString());
		assertEquals(250, h.getEnergyStored(null));
		assertEquals(1000, h.getMaxEnergyStored(null));
	}

	@Test
	@DisplayName("postEnergyTransferUpdate() defaults to doing nothing")
	void postEnergyTransferUpdateIsANoOp()
	{
		Handler h = new Handler(SideConfig.INPUT, storage(1000, 250));
		assertDoesNotThrow(() -> h.postEnergyTransferUpdate(100, false));
		assertEquals(250, h.getEnergyStored(null), "the default hook must not move energy");
	}

	// ---------------------------------------------------------------- IEForgeEnergyWrapper

	@Nested
	@DisplayName("IEForgeEnergyWrapper")
	class Wrapper
	{
		@Test
		@DisplayName("remembers the side it was built for")
		void remembersItsSide()
		{
			assertEquals(EnumFacing.WEST, new IEForgeEnergyWrapper(new Connector(SideConfig.INPUT), EnumFacing.WEST).side);
		}

		@Test
		@DisplayName("getDefaultWrapperArray() covers all six sides")
		void defaultArrayCoversEverySide()
		{
			IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(new Connector(SideConfig.INPUT));
			assertEquals(6, wrappers.length);
			for(EnumFacing facing : EnumFacing.VALUES)
			{
				boolean found = false;
				for(IEForgeEnergyWrapper w : wrappers)
					found |= w.side==facing;
				assertTrue(found, "no wrapper for "+facing);
			}
		}

		@Test
		@DisplayName("getDefaultWrapperArray() is indexed by EnumFacing ordinal")
		void defaultArrayIsOrdinalIndexed()
		{
			IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(new Connector(SideConfig.INPUT));
			for(EnumFacing facing : EnumFacing.VALUES)
				assertEquals(facing, wrappers[facing.ordinal()].side,
						"callers index this array with facing.ordinal()/getIndex()");
		}

		@Test
		@DisplayName("getDefaultWrapperArray() builds a fresh array each call")
		void defaultArrayIsFresh()
		{
			Connector c = new Connector(SideConfig.INPUT);
			assertNotSame(IEForgeEnergyWrapper.getDefaultWrapperArray(c), IEForgeEnergyWrapper.getDefaultWrapperArray(c));
		}

		@Test
		@DisplayName("reports no energy when the connector is not a full handler")
		void nonHandlerReportsNothing()
		{
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Connector(SideConfig.INPUT), EnumFacing.UP);
			assertEquals(0, w.getEnergyStored());
			assertEquals(0, w.getMaxEnergyStored());
		}

		@Test
		@DisplayName("moves no energy when the connector is not a full handler")
		void nonHandlerMovesNothing()
		{
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Connector(SideConfig.INPUT), EnumFacing.UP);
			assertEquals(0, w.receiveEnergy(100, true));
			assertEquals(0, w.extractEnergy(100, true));
		}

		@Test
		@DisplayName("refuses both directions when the connector is not a full handler")
		void nonHandlerRefusesBothDirections()
		{
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Connector(SideConfig.INPUT), EnumFacing.UP);
			assertFalse(w.canReceive());
			assertFalse(w.canExtract());
		}

		@Test
		@DisplayName("reads energy from the handler's flux storage")
		void handlerReportsStoredEnergy()
		{
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Handler(SideConfig.INPUT, storage(1000, 400)), EnumFacing.UP);
			assertEquals(400, w.getEnergyStored());
			assertEquals(1000, w.getMaxEnergyStored());
		}

		@Test
		@DisplayName("allows both directions when the storage has transfer limits")
		void handlerAllowsBothDirections()
		{
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Handler(SideConfig.INPUT, storage(1000, 400)), EnumFacing.UP);
			assertTrue(w.canReceive());
			assertTrue(w.canExtract());
		}

		@Test
		@DisplayName("refuses to receive when the storage has a zero receive limit")
		void zeroReceiveLimitCannotReceive()
		{
			FluxStorage f = new FluxStorage(1000, 0, 100);
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Handler(SideConfig.INPUT, f), EnumFacing.UP);
			assertFalse(w.canReceive());
			assertTrue(w.canExtract());
		}

		@Test
		@DisplayName("refuses to extract when the storage has a zero extract limit")
		void zeroExtractLimitCannotExtract()
		{
			FluxStorage f = new FluxStorage(1000, 100, 0);
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Handler(SideConfig.INPUT, f), EnumFacing.UP);
			assertTrue(w.canReceive());
			assertFalse(w.canExtract());
		}

		@Test
		@DisplayName("the transfer permissions do not depend on the side config")
		void permissionsIgnoreSideConfig()
		{
			// canReceive/canExtract only look at the storage limits; the side config gates the
			// actual transfer instead
			IEForgeEnergyWrapper w = new IEForgeEnergyWrapper(new Handler(SideConfig.NONE, storage(1000, 400)), EnumFacing.UP);
			assertTrue(w.canReceive());
			assertTrue(w.canExtract());
		}
	}

	// ---------------------------------------------------------------- IIEEnergyItem

	@Nested
	@DisplayName("IIEEnergyItem defaults")
	class EnergyItem
	{
		/** An energy item that is not a real Item, exercising only the NBT-backed defaults. */
		class Item implements IIEEnergyItem
		{
			private final int capacity;

			Item(int capacity)
			{
				this.capacity = capacity;
			}

			@Override
			public int getMaxEnergyStored(ItemStack container)
			{
				return capacity;
			}
		}

		@Test
		@DisplayName("getEnergyStored() reads the item's NBT and defaults to 0")
		void readsStoredEnergy()
		{
			assertEquals(0, new Item(1000).getEnergyStored(ItemStack.EMPTY));
		}

		@Test
		@DisplayName("receiveEnergy() accepts up to the item's capacity")
		void receiveIsCappedByCapacity()
		{
			assertEquals(1000, new Item(1000).receiveEnergy(ItemStack.EMPTY, 5000, true));
		}

		@Test
		@DisplayName("receiveEnergy() accepts the whole request when it fits")
		void receiveAcceptsWhatFits()
		{
			assertEquals(250, new Item(1000).receiveEnergy(ItemStack.EMPTY, 250, true));
		}

		@Test
		@DisplayName("a zero-capacity item accepts nothing")
		void zeroCapacityAcceptsNothing()
		{
			assertEquals(0, new Item(0).receiveEnergy(ItemStack.EMPTY, 250, true));
		}

		@Test
		@DisplayName("extractEnergy() is limited by what is stored")
		void extractIsCappedByStoredEnergy()
		{
			assertEquals(0, new Item(1000).extractEnergy(ItemStack.EMPTY, 250, true));
		}
	}
}
