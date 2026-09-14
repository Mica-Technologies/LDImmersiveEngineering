/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGenConfig;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGeneration;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualSource;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Feed Units whose metered plant has lost its real path keep supplying their segment at the plant's measured
 * rate -- in normal mode as buffered energy under every cap a live feed obeys, in City Mode as liveness.
 */
class GridEngineVirtualFeedTest
{
	private VirtualGrid grid;
	private GridSegment segment;
	private FakeSupply supply;

	static final class FakeSupply implements IVirtualFeedSupply
	{
		final Map<GridDevice, Integer> rates = new HashMap<>();
		final Map<GridDevice, Integer> delivered = new HashMap<>();
		boolean fuelFree = true;

		@Override
		public int rate(GridDevice feed, boolean cityMode)
		{
			if(!cityMode&&!fuelFree)
				return 0;
			return rates.getOrDefault(feed, 0);
		}

		@Override
		public void delivered(GridDevice feed, int amount)
		{
			delivered.put(feed, amount);
		}
	}

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		VirtualGenConfig.resetToDefaults();
		grid = new VirtualGrid();
		segment = segment(grid, "Virtual");
		supply = new FakeSupply();
		GridEngine.virtualFeeds = supply;
	}

	@AfterEach
	void tearDown()
	{
		GridEngine.virtualFeeds = IVirtualFeedSupply.NONE;
		GridConfig.resetToDefaults();
		VirtualGenConfig.resetToDefaults();
	}

	private GridDevice offlineFeed(int cap)
	{
		GridDevice feed = feed(grid, segment, 0, cap);
		feed.setEndpoint(null);
		segment.invalidateViews();
		return feed;
	}

	@Nested
	@DisplayName("normal mode")
	class Normal
	{
		@Test
		@DisplayName("an unloaded metered feed supplies its measured rate to a loaded service")
		void offlineFeedSupplies()
		{
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			GridDevice service = service(grid, segment, 1000, 4096);
			GridEngine.tick(grid, 0, false);
			assertEquals(500, endpointOf(service).totalInserted);
			assertEquals(500, supply.delivered.get(feed));
		}

		@Test
		@DisplayName("without a supply the unloaded feed contributes nothing, as before")
		void noSupplyNoEnergy()
		{
			offlineFeed(4096);
			GridDevice service = service(grid, segment, 1000, 4096);
			GridEngine.tick(grid, 0, false);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("the feed's transfer cap still applies")
		void capApplies()
		{
			GridDevice feed = offlineFeed(200);
			supply.rates.put(feed, 500);
			GridDevice service = service(grid, segment, 1000, 4096);
			GridEngine.tick(grid, 0, false);
			assertEquals(200, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a loaded feed that already drew for real only has what is left of its cap")
		void loadedFeedRemainingCap()
		{
			GridDevice feed = feed(grid, segment, 300, 1000);
			supply.rates.put(feed, 900);
			GridDevice service = service(grid, segment, 5000, 5000);
			GridEngine.tick(grid, 0, false);
			assertEquals(1000, endpointOf(service).totalInserted, "300 real plus 700 virtual, capped at 1000");
		}

		@Test
		@DisplayName("a fuel-burning plant does not supply outside City Mode")
		void fuelNotInNormalMode()
		{
			supply.fuelFree = false;
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			GridDevice service = service(grid, segment, 1000, 4096);
			GridEngine.tick(grid, 0, false);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a disabled feed or a switched-off segment takes nothing")
		void disabledOrOff()
		{
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			GridDevice service = service(grid, segment, 1000, 4096);
			feed.setEnabled(false);
			GridEngine.tick(grid, 0, false);
			feed.setEnabled(true);
			segment.setEnabled(false);
			GridEngine.tick(grid, 1, false);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("an unloaded feed's per-tick figure does not accumulate across ticks")
		void throughputDoesNotAccumulate()
		{
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			service(grid, segment, 100000, 100000);
			for(int t = 0; t < 5; t++)
				GridEngine.tick(grid, t, false);
			assertEquals(500, feed.getLastThroughput());
		}

		@Test
		@DisplayName("the console flag is set on a tick the feed was supplied virtually, and cleared after")
		void virtualFlag()
		{
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			service(grid, segment, 100000, 100000);
			GridEngine.tick(grid, 0, false);
			assertTrue(feed.isVirtualSupplied());
			supply.rates.remove(feed);
			GridEngine.tick(grid, 1, false);
			assertFalse(feed.isVirtualSupplied());
		}

		@Test
		@DisplayName("the flag reaches the console through the live sync, and is never saved")
		void flagSynced()
		{
			GridDevice feed = offlineFeed(4096);
			feed.setVirtualSupplied(true);
			GridDevice synced = GridDevice.readFromNBT(feed.writeToNBT(new net.minecraft.nbt.NBTTagCompound(), true));
			assertNotNull(synced);
			assertTrue(synced.isVirtualSupplied());
			assertFalse(feed.writeToNBT(new net.minecraft.nbt.NBTTagCompound(), false).hasKey("virtual"));
		}
	}

	@Nested
	@DisplayName("city mode")
	class City
	{
		@Test
		@DisplayName("an unloaded metered feed keeps its segment energized and its services supplied")
		void energizes()
		{
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			GridDevice service = service(grid, segment, 1000, 4096);
			GridEngine.tick(grid, 0, true);
			assertTrue(segment.isEnergized());
			assertTrue(endpointOf(service).totalInserted > 0);
		}

		@Test
		@DisplayName("a fuel-burning plant qualifies in City Mode")
		void fuelInCity()
		{
			supply.fuelFree = false;
			GridDevice feed = offlineFeed(4096);
			supply.rates.put(feed, 500);
			GridEngine.tick(grid, 0, true);
			assertTrue(segment.isEnergized());
		}

		@Test
		@DisplayName("without a supply an unloaded feed leaves the segment dark")
		void darkWithout()
		{
			offlineFeed(4096);
			GridEngine.tick(grid, 0, true);
			assertFalse(segment.isEnergized());
		}
	}

	@Nested
	@DisplayName("the virtual generation registry as supply")
	class RegistrySupply
	{
		private VirtualGeneration registry;
		private GridDevice feed;
		private VirtualSource source;

		@BeforeEach
		void setUp()
		{
			registry = new VirtualGeneration();
			feed = offlineFeed(4096);
			BlockPos feedPos = new BlockPos(feed.getPos().getX(), feed.getPos().getY(), feed.getPos().getZ());
			source = registry.getOrCreate(feed.getDimension(), feedPos.west(), feedPos.west(2), feedPos);
			source.updateMeasurement(700, 0, true, "gen", 1);
			source.setFeedsGrid(true);
			source.setRealPath(false);
		}

		@Test
		@DisplayName("a metered plant in front of the unit supplies it while its real path is broken")
		void supplies()
		{
			assertEquals(700, registry.gridFeedSupply().rate(feed, false));
		}

		@Test
		@DisplayName("a plant running for real is not counted twice")
		void realPathNotCounted()
		{
			source.setRealPath(true);
			assertEquals(0, registry.gridFeedSupply().rate(feed, true));
		}

		@Test
		@DisplayName("a meter feeding a wire connector is not a grid supply")
		void wireMeterIgnored()
		{
			source.setFeedsGrid(false);
			assertEquals(0, registry.gridFeedSupply().rate(feed, true));
		}

		@Test
		@DisplayName("fuel plants supply only in City Mode; the master switch stops everything")
		void fuelAndSwitch()
		{
			source.updateMeasurement(700, 0, false, "gen", 2);
			assertEquals(0, registry.gridFeedSupply().rate(feed, false));
			assertEquals(700, registry.gridFeedSupply().rate(feed, true));
			VirtualGenConfig.enabled = false;
			assertEquals(0, registry.gridFeedSupply().rate(feed, true));
		}

		@Test
		@DisplayName("two meters on one unit add up, and the delivery lands on a readout")
		void twoMeters()
		{
			BlockPos feedPos = source.getSourcePos();
			VirtualSource second = registry.getOrCreate(feed.getDimension(), feedPos.east(), feedPos.east(2), feedPos);
			second.updateMeasurement(300, 0, true, "gen", 1);
			second.setFeedsGrid(true);
			second.setRealPath(false);
			assertEquals(1000, registry.gridFeedSupply().rate(feed, false));
			registry.gridFeedSupply().delivered(feed, 800);
			assertEquals(800, source.getLastVirtualDelivered()+second.getLastVirtualDelivered());
			assertTrue(source.isVirtualActive()&&second.isVirtualActive());
		}

		@Test
		@DisplayName("the grid flag round-trips through NBT")
		void flagSaved()
		{
			assertTrue(VirtualSource.readFromNBT(source.writeToNBT()).feedsGrid());
		}
	}
}
