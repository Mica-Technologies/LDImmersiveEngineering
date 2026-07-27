/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-device lifetime metering -- the utility-bill reading shown on the Stats tab.
 * <p>
 * The distinction that matters here is between the per-tick figure, which is cleared every
 * tick, and the meter, which must survive ticks, chunk unloads and world saves.
 */
class GridMeteringTest
{
	private VirtualGrid grid;
	private GridSegment segment;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
		segment = segment(grid, "Main");
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("accumulation")
	class Accumulation
	{
		@Test
		@DisplayName("a new device reads zero")
		void startsAtZero()
		{
			assertEquals(0, feed(grid, segment, 0, 100).getLifetimeThroughput());
		}

		@Test
		@DisplayName("recording adds to both the tick figure and the meter")
		void recordingAddsToBoth()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(30);
			assertEquals(30, device.getLastThroughput());
			assertEquals(30, device.getLifetimeThroughput());
			device.recordThroughput(20);
			assertEquals(50, device.getLastThroughput(), "within a tick, contributions add up");
			assertEquals(50, device.getLifetimeThroughput());
		}

		@Test
		@DisplayName("non-positive amounts are ignored")
		void nonPositiveIgnored()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(0);
			device.recordThroughput(-500);
			assertEquals(0, device.getLifetimeThroughput());
			assertEquals(0, device.getLastThroughput());
		}

		@Test
		@DisplayName("clearing the tick figure leaves the meter alone")
		void tickResetKeepsMeter()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(75);
			device.setLastThroughput(0);
			assertEquals(0, device.getLastThroughput());
			assertEquals(75, device.getLifetimeThroughput(), "a meter reading is not a measurement");
		}

		@Test
		@DisplayName("the meter exceeds int range on a long-running grid")
		void meterExceedsIntRange()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			for(int i = 0; i < 5; i++)
				device.recordThroughput(Integer.MAX_VALUE);
			assertEquals(5L*Integer.MAX_VALUE, device.getLifetimeThroughput());
		}

		@Test
		@DisplayName("resetMeter zeroes it")
		void resetZeroes()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(500);
			device.resetMeter();
			assertEquals(0, device.getLifetimeThroughput());
		}
	}

	@Nested
	@DisplayName("driven by the engine")
	class DrivenByEngine
	{
		@Test
		@DisplayName("a feed's meter tracks what it supplied")
		void feedMeterTracksSupply()
		{
			GridDevice feed = feed(grid, segment, 1000, 100);
			service(grid, segment, 100000, 100000);
			for(int i = 0; i < 5; i++)
				GridEngine.tick(grid, i, false);
			assertEquals(500, feed.getLifetimeThroughput(), "5 ticks at a 100/t cap");
			assertEquals(endpointOf(feed).totalExtracted, feed.getLifetimeThroughput());
		}

		@Test
		@DisplayName("a service's meter tracks what it delivered")
		void serviceMeterTracksDelivery()
		{
			feed(grid, segment, 100000, 100000);
			GridDevice service = service(grid, segment, 100000, 60);
			for(int i = 0; i < 4; i++)
				GridEngine.tick(grid, i, false);
			assertEquals(240, service.getLifetimeThroughput());
			assertEquals(endpointOf(service).totalInserted, service.getLifetimeThroughput());
		}

		@Test
		@DisplayName("an idle device's meter does not creep")
		void idleDeviceDoesNotCreep()
		{
			GridDevice service = service(grid, segment, 0, 100);
			for(int i = 0; i < 20; i++)
				GridEngine.tick(grid, i, false);
			assertEquals(0, service.getLifetimeThroughput());
		}

		@Test
		@DisplayName("failover deliveries are metered on the device that received them")
		void failoverIsMetered()
		{
			GridSegment backup = segment(grid, "Backup");
			segment.addFailover(backup.getId());
			backup.addToBuffer(300);
			GridDevice service = service(grid, segment, 300, 1000);
			segment.setEnabled(false);

			GridEngine.tick(grid, 0, false);
			assertEquals(300, service.getLifetimeThroughput(),
					"the device moved the energy, whichever segment paid for it");
		}

		@Test
		@DisplayName("city-mode deliveries are metered too")
		void cityModeIsMetered()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 50);
			for(long t = 0; t < GridConfig.sipIntervalTicks; t++)
				GridEngine.tick(grid, t, true);
			assertTrue(service.getLifetimeThroughput() > 0);
			assertEquals(endpointOf(service).totalInserted, service.getLifetimeThroughput());
		}

		@Test
		@DisplayName("the segment total matches the sum of its service meters")
		void segmentTotalMatchesDevices()
		{
			feed(grid, segment, 100000, 100000);
			GridDevice a = service(grid, segment, 100000, 40);
			GridDevice b = service(grid, segment, 100000, 30);
			for(int i = 0; i < 10; i++)
				GridEngine.tick(grid, i, false);

			assertEquals(segment.getStats().getLifetimeOut(),
					a.getLifetimeThroughput()+b.getLifetimeThroughput(),
					"per-device meters must reconcile with the segment ledger");
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("the meter survives a save and load")
		void meterRoundTrips()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(123456);

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));

			GridDevice reloaded = loaded.getDevice(device.getPos());
			assertNotNull(reloaded);
			assertEquals(123456, reloaded.getLifetimeThroughput());
		}

		@Test
		@DisplayName("a huge meter reading survives intact")
		void largeMeterRoundTrips()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			for(int i = 0; i < 4; i++)
				device.recordThroughput(Integer.MAX_VALUE);
			long expected = device.getLifetimeThroughput();

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));
			assertEquals(expected, loaded.getDevice(device.getPos()).getLifetimeThroughput());
		}

		@Test
		@DisplayName("a device from before metering existed loads at zero")
		void missingMeterLoadsAtZero()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", 0);
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertEquals(0, loaded.getLifetimeThroughput());
		}

		@Test
		@DisplayName("a negative meter in a corrupt save loads as zero")
		void negativeMeterClamped()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", 0);
			nbt.setLong("meter", -9999);
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertEquals(0, loaded.getLifetimeThroughput());
		}

		@Test
		@DisplayName("the per-tick figure is not persisted")
		void tickFigureNotPersisted()
		{
			GridDevice device = feed(grid, segment, 0, 100);
			device.recordThroughput(500);

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));
			assertEquals(0, loaded.getDevice(device.getPos()).getLastThroughput());
		}
	}
}
