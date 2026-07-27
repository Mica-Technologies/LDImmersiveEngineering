/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import org.junit.jupiter.api.*;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * City mode: presence semantics instead of flux accounting.
 * <p>
 * The contract mirrors what {@code CityMode.wires()} does to the wire network -- stop
 * simulating the physics, just ask whether there is power. A segment is energized when one
 * of its feed units has recently proved its source is live, and its service units then
 * deliver freely.
 */
class GridEngineCityModeTest
{
	private VirtualGrid grid;
	private GridSegment segment;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
		segment = segment(grid, "City");
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	private void tick(long time)
	{
		GridEngine.tick(grid, time, true);
	}

	/**
	 * Runs enough ticks that every staggered feed unit has had a sip opportunity.
	 */
	private void tickThroughOneInterval()
	{
		for(long t = 0; t < GridConfig.sipIntervalTicks; t++)
			tick(t);
	}

	@Nested
	@DisplayName("liveness")
	class Liveness
	{
		@Test
		@DisplayName("a feed with a live source energizes its segment")
		void liveFeedEnergizes()
		{
			feed(grid, segment, 1000, 1000);
			tickThroughOneInterval();
			assertTrue(segment.isSourceLive());
			assertTrue(segment.isEnergized());
		}

		@Test
		@DisplayName("a feed with a dead source leaves the segment unenergized")
		void deadFeedDoesNotEnergize()
		{
			feed(grid, segment, 0, 1000);
			tickThroughOneInterval();
			assertFalse(segment.isSourceLive());
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("a segment with no feeds is never energized")
		void noFeedsNeverEnergized()
		{
			service(grid, segment, 1000, 1000);
			tickThroughOneInterval();
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("the liveness check costs one sip, not a tick's worth of throughput")
		void sipIsTiny()
		{
			GridConfig.sipAmount = 1;
			GridDevice feed = feed(grid, segment, 1000, 4096);
			tickThroughOneInterval();
			assertEquals(1, endpointOf(feed).totalExtracted,
					"a city-scale grid must cost almost nothing to run");
		}

		@Test
		@DisplayName("the sip amount follows the config")
		void sipAmountFollowsConfig()
		{
			GridConfig.sipAmount = 5;
			GridDevice feed = feed(grid, segment, 1000, 4096);
			tickThroughOneInterval();
			assertEquals(5, endpointOf(feed).totalExtracted);
		}

		@Test
		@DisplayName("sips repeat once per interval, not once per tick")
		void sipRepeatsPerInterval()
		{
			GridConfig.sipIntervalTicks = 20;
			GridDevice feed = feed(grid, segment, 1000, 4096);
			for(long t = 0; t < 100; t++)
				tick(t);
			assertEquals(5, endpointOf(feed).totalExtracted, "100 ticks / 20 = 5 sips");
		}

		@Test
		@DisplayName("a segment stays energized between sips")
		void staysEnergizedBetweenSips()
		{
			GridConfig.sipIntervalTicks = 20;
			feed(grid, segment, 1000, 1000);
			for(long t = 0; t < 25; t++)
				tick(t);
			assertTrue(segment.isEnergized(), "the grace period covers the gap between checks");
		}

		@Test
		@DisplayName("a segment de-energizes once its source stops answering")
		void deEnergizesWhenSourceDies()
		{
			GridConfig.sipIntervalTicks = 20;
			GridDevice feed = feed(grid, segment, 1, 1000);
			//One sip is available, then the source is dry.
			for(long t = 0; t < 20; t++)
				tick(t);
			assertTrue(segment.isEnergized());

			for(long t = 20; t < 200; t++)
				tick(t);
			assertFalse(segment.isEnergized(), "a dead source must not look live forever");
			assertEquals(1, endpointOf(feed).totalExtracted);
		}

		@Test
		@DisplayName("one live feed among several is enough")
		void oneLiveFeedIsEnough()
		{
			feed(grid, segment, 0, 1000);
			feed(grid, segment, 1000, 1000);
			feed(grid, segment, 0, 1000);
			tickThroughOneInterval();
			assertTrue(segment.isEnergized());
		}

		@Test
		@DisplayName("sips are staggered across feed units by position")
		void sipsAreStaggered()
		{
			GridConfig.sipIntervalTicks = 100;
			for(int i = 0; i < 12; i++)
				feed(grid, segment, 1000, 1000);

			int busiestTick = 0;
			for(long t = 0; t < GridConfig.sipIntervalTicks; t++)
			{
				int before = totalExtractCalls();
				tick(t);
				busiestTick = Math.max(busiestTick, totalExtractCalls()-before);
			}
			assertTrue(busiestTick < 12,
					"a city's worth of feed units must not all sip on the same tick");
		}

		private int totalExtractCalls()
		{
			int total = 0;
			for(GridDevice device : grid.getDevices())
				if(device.getType()==GridDeviceType.FEED)
					total += endpointOf(device).extractCalls;
			return total;
		}
	}

	@Nested
	@DisplayName("delivery")
	class Delivery
	{
		@Test
		@DisplayName("an energized segment delivers without a pool")
		void energizedDeliversFreely()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			tickThroughOneInterval();

			assertTrue(endpointOf(service).totalInserted > 0);
			assertEquals(0, segment.getBuffer(), "city mode does not use the buffer at all");
		}

		@Test
		@DisplayName("delivery per tick is bounded by the device's transfer cap")
		void deliveryBoundedByDeviceCap()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 250);
			tick(0);
			int first = endpointOf(service).totalInserted;
			//The very first tick may precede this device's stagger, so measure a later one.
			int before = endpointOf(service).totalInserted;
			tick(GridConfig.sipIntervalTicks*2L);
			assertTrue(endpointOf(service).totalInserted-before <= 250);
			assertTrue(first <= 250);
		}

		@Test
		@DisplayName("the segment's own output cap is still honoured")
		void segmentOutputCapHonoured()
		{
			segment.getPolicy().setMaxOutput(300);
			feed(grid, segment, 1000, 1000);
			GridDevice a = service(grid, segment, 100000, 100000);
			GridDevice b = service(grid, segment, 100000, 100000);

			//Energize first, then measure a single clean tick.
			tickThroughOneInterval();
			int before = endpointOf(a).totalInserted+endpointOf(b).totalInserted;
			tick(GridConfig.sipIntervalTicks+1);
			int delivered = endpointOf(a).totalInserted+endpointOf(b).totalInserted-before;
			assertEquals(300, delivered, "the cap the player set is a setting, not a physics term");
		}

		@Test
		@DisplayName("an unenergized segment delivers nothing")
		void unenergizedDeliversNothing()
		{
			feed(grid, segment, 0, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			tickThroughOneInterval();
			assertEquals(0, endpointOf(service).totalInserted);
			assertEquals(0, service.getLastThroughput());
		}

		@Test
		@DisplayName("a switched-off segment delivers nothing even with a live feed")
		void offSegmentDeliversNothing()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			segment.setEnabled(false);
			tickThroughOneInterval();
			assertEquals(0, endpointOf(service).totalInserted);
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("a tripped segment delivers nothing")
		void trippedDeliversNothing()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			segment.setTripped(true);
			tickThroughOneInterval();
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("delivery is limited by what the world accepts")
		void limitedByWorldDemand()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 40, 100000);
			tickThroughOneInterval();
			assertEquals(40, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("delivered totals are still recorded for the stats tab")
		void deliveryIsRecorded()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 100000, 100);
			tickThroughOneInterval();
			assertTrue(segment.getStats().getLifetimeOut() > 0);
		}

		@Test
		@DisplayName("a disabled service is skipped")
		void disabledServiceSkipped()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			service.setEnabled(false);
			segment.invalidateViews();
			tickThroughOneInterval();
			assertEquals(0, endpointOf(service).totalInserted);
		}
	}

	@Nested
	@DisplayName("failover")
	class Failover
	{
		private GridSegment backup;

		@BeforeEach
		void linkBackup()
		{
			backup = segment(grid, "Backup");
			segment.addFailover(backup.getId());
		}

		@Test
		@DisplayName("a live backup energizes a segment with no source of its own")
		void liveBackupEnergizes()
		{
			feed(grid, backup, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);

			tickThroughOneInterval();
			assertFalse(segment.isSourceLive(), "it has no feeds of its own");
			assertTrue(segment.isEnergized(), "but a live backup stands in");
			assertTrue(endpointOf(service).totalInserted > 0);
		}

		@Test
		@DisplayName("a dead backup does not energize")
		void deadBackupDoesNotEnergize()
		{
			feed(grid, backup, 0, 1000);
			service(grid, segment, 100000, 500);
			tickThroughOneInterval();
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("a switched-off backup does not energize")
		void offBackupDoesNotEnergize()
		{
			feed(grid, backup, 1000, 1000);
			backup.setEnabled(false);
			service(grid, segment, 100000, 500);
			tickThroughOneInterval();
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("energization propagates down a chain")
		void chainPropagates()
		{
			GridSegment deep = segment(grid, "Deep");
			backup.addFailover(deep.getId());
			feed(grid, deep, 1000, 1000);
			service(grid, segment, 100000, 500);

			tickThroughOneInterval();
			assertTrue(segment.isEnergized());
		}

		@Test
		@DisplayName("the chain stops at the configured depth")
		void depthBounded()
		{
			GridConfig.maxFailoverDepth = 1;
			GridSegment deep = segment(grid, "Deep");
			backup.addFailover(deep.getId());
			feed(grid, deep, 1000, 1000);
			service(grid, segment, 100000, 500);

			tickThroughOneInterval();
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("a cycle terminates instead of hanging")
		void cycleTerminates()
		{
			backup.addFailover(segment.getId());
			service(grid, segment, 100000, 500);
			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), this::runOneInterval);
			assertFalse(segment.isEnergized());
		}

		@Test
		@DisplayName("a cycle with a live source still energizes")
		void cycleWithSourceEnergizes()
		{
			backup.addFailover(segment.getId());
			feed(grid, backup, 1000, 1000);
			service(grid, segment, 100000, 500);
			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), this::runOneInterval);
			assertTrue(segment.isEnergized());
		}

		private void runOneInterval()
		{
			for(long t = 0; t < GridConfig.sipIntervalTicks; t++)
				tick(t);
		}
	}

	@Nested
	@DisplayName("mode independence")
	class ModeIndependence
	{
		@Test
		@DisplayName("the master switch still stops everything")
		void masterSwitchStopsEverything()
		{
			GridDevice feed = feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 100000, 500);
			GridConfig.enabled = false;
			tickThroughOneInterval();
			assertEquals(0, endpointOf(feed).totalExtracted);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("switching back to normal mode resumes real accounting")
		void switchingBackResumesAccounting()
		{
			GridDevice feed = feed(grid, segment, 100000, 1000);
			GridDevice service = service(grid, segment, 100000, 1000);
			tickThroughOneInterval();

			int sipped = endpointOf(feed).totalExtracted;
			assertTrue(sipped > 0&&sipped <= GridConfig.sipAmount*2, "city mode only sips");
			assertTrue(endpointOf(service).totalInserted > sipped,
					"yet it delivers far more than it drew -- that is the presence contract");

			//Back to real accounting: everything delivered must have been drawn.
			int extractedBefore = endpointOf(feed).totalExtracted;
			int insertedBefore = endpointOf(service).totalInserted;
			GridEngine.tick(grid, 0, false);
			int extracted = endpointOf(feed).totalExtracted-extractedBefore;
			int inserted = endpointOf(service).totalInserted-insertedBefore;

			assertTrue(extracted > 0, "normal mode actually draws from the source");
			assertEquals(extracted, inserted+segment.getBuffer(),
					"and conserves it: delivered plus buffered equals drawn");
		}
	}
}
