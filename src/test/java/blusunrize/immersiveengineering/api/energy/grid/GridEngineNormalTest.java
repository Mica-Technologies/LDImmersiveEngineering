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
 * The normal-mode energy pass: real accounting, where what comes out came in.
 */
class GridEngineNormalTest
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

	private void tick()
	{
		GridEngine.tick(grid, 0, false);
	}

	@Nested
	@DisplayName("basic transfer")
	class BasicTransfer
	{
		@Test
		@DisplayName("energy moves from a feed to a service in a single tick")
		void movesInOneTick()
		{
			GridDevice feed = feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);

			tick();

			assertEquals(500, endpointOf(feed).totalExtracted);
			assertEquals(500, endpointOf(service).totalInserted);
			assertEquals(0, segment.getBuffer(), "the buffer is a conduit, not storage");
		}

		@Test
		@DisplayName("statistics record the flow")
		void statsRecordFlow()
		{
			feed(grid, segment, 300, 1000);
			service(grid, segment, 300, 1000);
			tick();
			assertEquals(300, segment.getStats().getLifetimeIn());
			assertEquals(300, segment.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("per-device throughput is recorded for the readout")
		void deviceThroughputRecorded()
		{
			GridDevice feed = feed(grid, segment, 250, 1000);
			GridDevice service = service(grid, segment, 250, 1000);
			tick();
			assertEquals(250, feed.getLastThroughput());
			assertEquals(250, service.getLastThroughput());
		}

		@Test
		@DisplayName("with nothing to deliver into, energy stays in the buffer")
		void unusedEnergyStaysBuffered()
		{
			feed(grid, segment, 400, 1000);
			tick();
			assertEquals(400, segment.getBuffer());
			assertEquals(400, segment.getStats().getLifetimeIn());
			assertEquals(0, segment.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("buffered energy is delivered on a later tick")
		void bufferedEnergyDeliveredLater()
		{
			feed(grid, segment, 400, 1000);
			tick();
			GridDevice service = service(grid, segment, 400, 1000);
			tick();
			assertEquals(400, endpointOf(service).totalInserted);
			assertEquals(0, segment.getBuffer());
		}

		@Test
		@DisplayName("a service with no supply receives nothing")
		void noSupplyNoDelivery()
		{
			GridDevice service = service(grid, segment, 500, 1000);
			tick();
			assertEquals(0, endpointOf(service).totalInserted);
			assertEquals(0, service.getLastThroughput());
		}

		@Test
		@DisplayName("delivery is limited by what the world will accept")
		void limitedByWorldDemand()
		{
			GridDevice feed = feed(grid, segment, 1000, 5000);
			GridDevice service = service(grid, segment, 120, 5000);
			tick();
			assertEquals(120, endpointOf(service).totalInserted);
			assertEquals(880, segment.getBuffer(), "the rest waits in the buffer");
			assertEquals(1000, endpointOf(feed).totalExtracted);
		}

		@Test
		@DisplayName("an empty segment ticks harmlessly")
		void emptySegmentIsHarmless()
		{
			assertDoesNotThrow(() -> tick());
			assertEquals(0, segment.getBuffer());
		}

		@Test
		@DisplayName("an empty grid ticks harmlessly")
		void emptyGridIsHarmless()
		{
			assertDoesNotThrow(() -> GridEngine.tick(new VirtualGrid(), 0, false));
		}
	}

	@Nested
	@DisplayName("gating")
	class Gating
	{
		@Test
		@DisplayName("nothing moves while the master switch is off")
		void masterSwitchStopsEverything()
		{
			GridDevice feed = feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			GridConfig.enabled = false;
			tick();
			assertEquals(0, endpointOf(feed).totalExtracted);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a switched-off segment moves nothing")
		void offSegmentMovesNothing()
		{
			GridDevice feed = feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			segment.setEnabled(false);
			tick();
			assertEquals(0, endpointOf(feed).totalExtracted);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a tripped segment moves nothing")
		void trippedSegmentMovesNothing()
		{
			GridDevice feed = feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			segment.setTripped(true);
			tick();
			assertEquals(0, endpointOf(feed).totalExtracted);
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a disabled device is skipped but its neighbours still run")
		void disabledDeviceSkipped()
		{
			GridDevice disabled = feed(grid, segment, 500, 1000);
			GridDevice enabled = feed(grid, segment, 500, 1000);
			disabled.setEnabled(false);
			segment.invalidateViews();
			service(grid, segment, 5000, 5000);

			tick();
			assertEquals(0, endpointOf(disabled).totalExtracted);
			assertEquals(500, endpointOf(enabled).totalExtracted);
		}

		@Test
		@DisplayName("an offline device is skipped")
		void offlineDeviceSkipped()
		{
			GridDevice offline = feed(grid, segment, 500, 1000);
			FakeEndpoint endpoint = endpointOf(offline);
			offline.setEndpoint(null);
			segment.invalidateViews();
			service(grid, segment, 5000, 5000);

			tick();
			assertEquals(0, endpoint.totalExtracted);
		}

		@Test
		@DisplayName("an unlinked device never participates")
		void unlinkedDeviceIgnored()
		{
			GridDevice loose = feed(grid, null, 500, 1000);
			service(grid, segment, 5000, 5000);
			tick();
			assertEquals(0, endpointOf(loose).totalExtracted);
		}
	}

	@Nested
	@DisplayName("caps and budgets")
	class Caps
	{
		@Test
		@DisplayName("a feed never gives more than its own transfer cap")
		void feedCapRespected()
		{
			GridDevice feed = feed(grid, segment, 100000, 250);
			service(grid, segment, 100000, 100000);
			tick();
			assertEquals(250, endpointOf(feed).totalExtracted);
		}

		@Test
		@DisplayName("a service never takes more than its own transfer cap")
		void serviceCapRespected()
		{
			feed(grid, segment, 100000, 100000);
			GridDevice service = service(grid, segment, 100000, 250);
			tick();
			assertEquals(250, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("the segment input cap bounds the whole tick's intake")
		void segmentInputCapRespected()
		{
			segment.getPolicy().setMaxInput(300);
			feed(grid, segment, 100000, 100000);
			feed(grid, segment, 100000, 100000);
			tick();
			assertEquals(300, segment.getStats().getLifetimeIn());
		}

		@Test
		@DisplayName("the segment output cap bounds the whole tick's delivery")
		void segmentOutputCapRespected()
		{
			segment.getPolicy().setMaxOutput(300);
			feed(grid, segment, 100000, 100000);
			service(grid, segment, 100000, 100000);
			service(grid, segment, 100000, 100000);
			tick();
			assertEquals(300, segment.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("the buffer cap bounds how much may be collected")
		void bufferCapBoundsIntake()
		{
			segment.getPolicy().setBufferCap(200);
			GridDevice feed = feed(grid, segment, 100000, 100000);
			tick();
			assertEquals(200, endpointOf(feed).totalExtracted);
			assertEquals(200, segment.getBuffer());
		}

		@Test
		@DisplayName("a zero input cap stops collection entirely")
		void zeroInputCapStopsCollection()
		{
			segment.getPolicy().setMaxInput(0);
			GridDevice feed = feed(grid, segment, 500, 1000);
			tick();
			assertEquals(0, endpointOf(feed).totalExtracted);
		}

		@Test
		@DisplayName("a zero output cap stops delivery entirely")
		void zeroOutputCapStopsDelivery()
		{
			segment.getPolicy().setMaxOutput(0);
			feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			tick();
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a zero-cap device is skipped without blocking the others")
		void zeroCapDeviceSkipped()
		{
			GridDevice mute = feed(grid, segment, 500, 0);
			GridDevice live = feed(grid, segment, 500, 500);
			service(grid, segment, 100000, 100000);
			tick();
			assertEquals(0, endpointOf(mute).totalExtracted);
			assertEquals(500, endpointOf(live).totalExtracted);
		}

		@Test
		@DisplayName("budgets reset between ticks")
		void budgetsResetBetweenTicks()
		{
			segment.getPolicy().setMaxInput(100);
			GridDevice feed = feed(grid, segment, 1000, 1000);
			tick();
			assertEquals(100, endpointOf(feed).totalExtracted);
			tick();
			assertEquals(200, endpointOf(feed).totalExtracted);
		}
	}

	@Nested
	@DisplayName("priority")
	class Priority
	{
		@Test
		@DisplayName("higher-priority feeds are drained first")
		void highPriorityFeedDrainedFirst()
		{
			segment.getPolicy().setMaxInput(100);
			GridDevice low = feed(grid, segment, 500, 500, 1);
			GridDevice high = feed(grid, segment, 500, 500, 10);
			tick();
			assertEquals(100, endpointOf(high).totalExtracted);
			assertEquals(0, endpointOf(low).totalExtracted);
		}

		@Test
		@DisplayName("the next feed takes over once the first runs dry")
		void nextFeedTakesOver()
		{
			GridDevice high = feed(grid, segment, 60, 500, 10);
			GridDevice low = feed(grid, segment, 500, 500, 1);
			service(grid, segment, 100000, 100000);
			tick();
			assertEquals(60, endpointOf(high).totalExtracted);
			assertTrue(endpointOf(low).totalExtracted > 0, "the rest is made up by the next feed");
		}

		@Test
		@DisplayName("higher-priority services are served first")
		void highPriorityServiceServedFirst()
		{
			feed(grid, segment, 100, 100000);
			GridDevice low = service(grid, segment, 500, 500, 1, false);
			GridDevice high = service(grid, segment, 500, 500, 10, false);
			tick();
			assertEquals(100, endpointOf(high).totalInserted);
			assertEquals(0, endpointOf(low).totalInserted);
		}

		@Test
		@DisplayName("a partly-served tick spills the remainder to the next service")
		void remainderSpillsDown()
		{
			feed(grid, segment, 150, 100000);
			GridDevice high = service(grid, segment, 100, 100, 10, false);
			GridDevice low = service(grid, segment, 500, 500, 1, false);
			tick();
			assertEquals(100, endpointOf(high).totalInserted);
			assertEquals(50, endpointOf(low).totalInserted);
		}
	}

	@Nested
	@DisplayName("load shedding")
	class LoadShedding
	{
		@Test
		@DisplayName("critical loads are served before ordinary ones during a shortfall")
		void criticalServedFirst()
		{
			feed(grid, segment, 100, 100000);
			GridDevice ordinary = service(grid, segment, 500, 500, 50, false);
			GridDevice critical = service(grid, segment, 500, 500, -50, true);
			tick();
			assertEquals(100, endpointOf(critical).totalInserted,
					"a critical load outranks a much higher ordinary priority");
			assertEquals(0, endpointOf(ordinary).totalInserted);
		}

		@Test
		@DisplayName("ordinary loads still get the surplus")
		void ordinaryGetsSurplus()
		{
			feed(grid, segment, 300, 100000);
			GridDevice critical = service(grid, segment, 100, 100, 0, true);
			GridDevice ordinary = service(grid, segment, 500, 500, 0, false);
			tick();
			assertEquals(100, endpointOf(critical).totalInserted);
			assertEquals(200, endpointOf(ordinary).totalInserted);
		}

		@Test
		@DisplayName("critical loads shed among themselves by priority")
		void criticalShedByPriority()
		{
			feed(grid, segment, 100, 100000);
			GridDevice lowCrit = service(grid, segment, 500, 500, 1, true);
			GridDevice highCrit = service(grid, segment, 500, 500, 9, true);
			tick();
			assertEquals(100, endpointOf(highCrit).totalInserted);
			assertEquals(0, endpointOf(lowCrit).totalInserted);
		}
	}

	@Nested
	@DisplayName("transmission loss")
	class Loss
	{
		@Test
		@DisplayName("loss is taken on intake so delivered figures stay honest")
		void lossTakenOnIntake()
		{
			segment.getPolicy().setLossPct(0.5);
			GridDevice feed = feed(grid, segment, 100, 1000);
			GridDevice service = service(grid, segment, 1000, 1000);
			tick();
			assertEquals(100, endpointOf(feed).totalExtracted, "the source still loses 100");
			assertEquals(50, endpointOf(service).totalInserted, "but only half arrives");
		}

		@Test
		@DisplayName("zero loss delivers everything")
		void zeroLossDeliversEverything()
		{
			segment.getPolicy().setLossPct(0);
			feed(grid, segment, 100, 1000);
			GridDevice service = service(grid, segment, 1000, 1000);
			tick();
			assertEquals(100, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a fractional result is floored, never rounded up")
		void fractionalLossFloors()
		{
			segment.getPolicy().setLossPct(0.5);
			feed(grid, segment, 7, 1000);
			GridDevice service = service(grid, segment, 1000, 1000);
			tick();
			assertEquals(3, endpointOf(service).totalInserted, "3.5 must not become 4");
		}

		@Test
		@DisplayName("total loss collects nothing rather than draining sources into the void")
		void totalLossCollectsNothing()
		{
			segment.getPolicy().setLossPct(1.0);
			GridDevice feed = feed(grid, segment, 500, 1000);
			tick();
			assertEquals(0, endpointOf(feed).totalExtracted,
					"draining a source for energy that can never arrive would be a bug");
		}

		@Test
		@DisplayName("with loss, intake is scaled up so the buffer still fills")
		void intakeScaledForRoom()
		{
			segment.getPolicy().setLossPct(0.5);
			segment.getPolicy().setBufferCap(100);
			GridDevice feed = feed(grid, segment, 100000, 100000);
			tick();
			assertEquals(100, segment.getBuffer(), "the buffer should end up full");
			assertEquals(200, endpointOf(feed).totalExtracted, "which costs twice as much at 50% loss");
		}
	}

	@Nested
	@DisplayName("multiple segments")
	class MultipleSegments
	{
		@Test
		@DisplayName("segments are independent pools")
		void segmentsAreIndependent()
		{
			GridSegment other = segment(grid, "Other");
			feed(grid, segment, 500, 1000);
			GridDevice otherService = service(grid, other, 500, 1000);

			tick();
			assertEquals(0, endpointOf(otherService).totalInserted,
					"power must not leak between unlinked segments");
			assertEquals(500, segment.getBuffer());
			assertEquals(0, other.getBuffer());
		}

		@Test
		@DisplayName("one segment being off does not affect another")
		void offSegmentDoesNotAffectOthers()
		{
			GridSegment other = segment(grid, "Other");
			other.setEnabled(false);
			feed(grid, other, 500, 1000);

			feed(grid, segment, 500, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			tick();
			assertEquals(500, endpointOf(service).totalInserted);
		}
	}

	@Nested
	@DisplayName("hostile endpoints")
	class HostileEndpoints
	{
		@Test
		@DisplayName("a feed that over-reports cannot mint energy")
		void overReportingFeedClamped()
		{
			GridDevice feed = grid.registerDevice(pos(), GridDeviceType.FEED);
			feed.setTransferCap(100);
			grid.assignDevice(feed, segment.getId());
			feed.setEndpoint(new FakeEndpoint.Misbehaving(9999));
			segment.invalidateViews();

			tick();
			assertEquals(100, segment.getStats().getLifetimeIn(),
					"the ledger must never exceed what the engine offered");
			assertTrue(segment.getBuffer() <= 100);
		}

		@Test
		@DisplayName("a service that over-reports cannot drain more than it was offered")
		void overReportingServiceClamped()
		{
			feed(grid, segment, 500, 1000);
			GridDevice service = grid.registerDevice(pos(), GridDeviceType.SERVICE);
			service.setTransferCap(100);
			grid.assignDevice(service, segment.getId());
			service.setEndpoint(new FakeEndpoint.Misbehaving(9999));
			segment.invalidateViews();

			tick();
			assertEquals(100, segment.getStats().getLifetimeOut());
			assertEquals(400, segment.getBuffer(), "the buffer may only be debited by what was offered");
		}

		@Test
		@DisplayName("a negative report is treated as nothing moved")
		void negativeReportTreatedAsZero()
		{
			GridDevice feed = grid.registerDevice(pos(), GridDeviceType.FEED);
			feed.setTransferCap(100);
			grid.assignDevice(feed, segment.getId());
			feed.setEndpoint(new FakeEndpoint.Misbehaving(-99999));
			segment.invalidateViews();

			tick();
			assertEquals(0, segment.getStats().getLifetimeIn());
			assertEquals(0, segment.getBuffer());
		}
	}

	@Nested
	@DisplayName("sustained running")
	class Sustained
	{
		@Test
		@DisplayName("a steady grid conserves energy over many ticks")
		void energyIsConservedOverTime()
		{
			GridDevice feed = feed(grid, segment, 20000, 100);
			GridDevice service = service(grid, segment, 20000, 100);
			for(int i = 0; i < 100; i++)
				tick();

			int extracted = endpointOf(feed).totalExtracted;
			int inserted = endpointOf(service).totalInserted;
			assertEquals(extracted, inserted+segment.getBuffer(),
					"every flux taken in must be delivered or still be in the buffer");
			assertEquals(10000, extracted, "100 ticks at a 100/t device cap");
		}

		@Test
		@DisplayName("statistics track a long run")
		void statsTrackLongRun()
		{
			feed(grid, segment, 100000, 50);
			service(grid, segment, 100000, 50);
			for(int i = 0; i < GridStats.TICKS_PER_SAMPLE*3; i++)
				tick();

			assertEquals(3, segment.getStats().getSampleCount());
			assertEquals(1000, segment.getStats().getHistoryOut()[0], "50/t for 20 ticks");
		}
	}
}
