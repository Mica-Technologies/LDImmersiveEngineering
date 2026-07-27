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
 * The two shapes {@link VirtualGrid#writeToNBT(NBTTagCompound, boolean)} produces.
 * <p>
 * The console GUI reads a client-side copy of the grid rebuilt from this payload, so
 * anything the window displays has to survive the round trip -- and anything the engine
 * recomputes every tick has to stay <em>out</em> of the world save, where a stale copy
 * would contradict the first tick after load.
 */
class GridSyncPayloadTest
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

	/**
	 * Rebuilds the grid the way the client does, from a payload of the given kind.
	 */
	private VirtualGrid roundTrip(boolean live)
	{
		VirtualGrid copy = new VirtualGrid();
		copy.readFromNBT(grid.writeToNBT(new NBTTagCompound(), live));
		return copy;
	}

	private GridSegment onlySegment(VirtualGrid of)
	{
		return of.getSegments().iterator().next();
	}

	@Nested
	@DisplayName("a live sync carries what the console draws")
	class LiveSync
	{
		@Test
		@DisplayName("device online state and throughput survive")
		void deviceLiveState()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 400, 1000);
			GridEngine.tick(grid, 0, false);
			assertEquals(400, service.getLastThroughput(), "sanity: the tick moved something");

			GridDevice copy = roundTrip(true).getDevice(service.getPos());

			assertNotNull(copy);
			assertTrue(copy.isOnline(), "a loaded device must not read as offline on the client");
			assertEquals(400, copy.getLastThroughput());
		}

		@Test
		@DisplayName("segment throughput and energization survive")
		void segmentLiveState()
		{
			GridDevice feed = feed(grid, segment, 1000, 1000);
			service(grid, segment, 400, 1000);
			feed.setLastLiveTick(0);
			GridEngine.tick(grid, 0, true);

			GridSegment copy = onlySegment(roundTrip(true));

			assertTrue(copy.isEnergized());
			assertTrue(copy.isSourceLive());
			assertEquals(400, copy.getTickOut());
		}

		@Test
		@DisplayName("the stats history survives, so the graph is not flat")
		void statsHistory()
		{
			//Supply and demand well clear of what the run consumes, so the last tick of the
			//loop is still moving flux and lastTickOut is not simply an exhausted source.
			feed(grid, segment, 10000000, 1000);
			service(grid, segment, 10000000, 1000);
			//A full second of ticks closes one history sample.
			for(int i = 0; i < GridStats.TICKS_PER_SAMPLE; i++)
				GridEngine.tick(grid, i, false);

			GridStats copied = onlySegment(roundTrip(true)).getStats();

			assertTrue(copied.getSampleCount() > 0, "at least one sample should have landed");
			assertTrue(copied.getLastTickOut() > 0);
		}

		@Test
		@DisplayName("a held or sleeping segment says so on the client")
		void gatesSurvive()
		{
			signal(grid, segment, false, false, true);
			segment.getPolicy().setScheduleEnabled(true);
			segment.getPolicy().setScheduleOn(12000);
			segment.getPolicy().setScheduleOff(23000);
			GridEngine.applySchedules(grid, 6000);
			GridEngine.tick(grid, 0, false);

			GridSegment copy = onlySegment(roundTrip(true));

			assertTrue(copy.isForcedOff());
			assertTrue(copy.isScheduleSuppressed());
			assertFalse(copy.isOperational(), "and therefore reads as not running");
		}
	}

	@Nested
	@DisplayName("save data stays free of derived state")
	class SaveData
	{
		@Test
		@DisplayName("nothing transient is written")
		void noLiveStateInSave()
		{
			GridDevice feed = feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 400, 1000);
			feed.setLastLiveTick(0);
			signal(grid, segment, false, false, true);
			GridEngine.tick(grid, 0, true);

			VirtualGrid loaded = roundTrip(false);
			GridSegment copiedSegment = onlySegment(loaded);
			GridDevice copiedService = loaded.getDevice(service.getPos());

			assertNotNull(copiedService);
			assertFalse(copiedService.isOnline(), "no tile entity has attached yet after a load");
			assertEquals(0, copiedService.getLastThroughput());
			assertFalse(copiedSegment.isEnergized());
			assertFalse(copiedSegment.isForcedOff());
			assertEquals(0, copiedSegment.getTickOut());
		}

		@Test
		@DisplayName("configuration and meters do survive")
		void configuredStateSurvives()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 400, 1000);
			service.setCustomName("Kiln feed");
			service.setCritical(true);
			service.setPriority(7);
			GridEngine.tick(grid, 0, false);
			assertEquals(400, service.getLifetimeThroughput());

			GridDevice copy = roundTrip(false).getDevice(service.getPos());

			assertNotNull(copy);
			assertEquals("Kiln feed", copy.getCustomName());
			assertTrue(copy.isCritical());
			assertEquals(7, copy.getPriority());
			assertEquals(400, copy.getLifetimeThroughput(), "the meter reading is not transient");
			assertEquals(segment.getId(), copy.getSegment());
		}

		@Test
		@DisplayName("a history array of a different length does not break the load")
		void toleratesForeignHistoryLength()
		{
			NBTTagCompound stats = segment.getStats().writeToNBT(new NBTTagCompound(), true);
			stats.setIntArray("histIn", new int[]{1, 2, 3});
			stats.setIntArray("histOut", new int[GridStats.HISTORY_SECONDS+40]);

			GridStats loaded = new GridStats();
			assertDoesNotThrow(() -> loaded.readFromNBT(stats));
		}
	}
}
