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
 * Failover: a segment's loads being carried by a linked backup.
 * <p>
 * The two trigger conditions are deliberately different. Backups always step in for an
 * outage (switched off or tripped); they only cover an ordinary shortfall when the
 * segment's {@code failoverTopUp} is set.
 */
class GridEngineFailoverTest
{
	private VirtualGrid grid;
	private GridSegment primary;
	private GridSegment backup;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
		primary = segment(grid, "Primary");
		backup = segment(grid, "Backup");
		primary.addFailover(backup.getId());
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

	/**
	 * Gives {@code segment} a full buffer without running a tick.
	 */
	private static void prime(GridSegment segment, int amount)
	{
		segment.addToBuffer(amount);
	}

	@Nested
	@DisplayName("outage")
	class Outage
	{
		@Test
		@DisplayName("a backup carries the loads of a switched-off segment")
		void backupCarriesOffSegment()
		{
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 500);
			primary.setEnabled(false);

			tick();
			assertEquals(500, endpointOf(service).totalInserted);
			assertEquals(0, backup.getBuffer(), "the backup paid for it");
			assertEquals(500, backup.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("a backup carries the loads of a tripped segment")
		void backupCarriesTrippedSegment()
		{
			GridDevice service = service(grid, primary, 400, 1000);
			prime(backup, 400);
			primary.setTripped(true);

			tick();
			assertEquals(400, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("outage failover works even with top-up disabled")
		void outageIgnoresTopUpSetting()
		{
			primary.getPolicy().setFailoverTopUp(false);
			GridDevice service = service(grid, primary, 300, 1000);
			prime(backup, 300);
			primary.setEnabled(false);

			tick();
			assertEquals(300, endpointOf(service).totalInserted,
					"an outage always engages backups, regardless of the shortfall setting");
		}

		@Test
		@DisplayName("the primary's own feeds stay idle during an outage")
		void primaryFeedsStayIdle()
		{
			GridDevice feed = feed(grid, primary, 1000, 1000);
			service(grid, primary, 300, 1000);
			prime(backup, 300);
			primary.setEnabled(false);

			tick();
			assertEquals(0, endpointOf(feed).totalExtracted,
					"a switched-off segment must not still be collecting");
		}

		@Test
		@DisplayName("a backup that is itself off supplies nothing")
		void offBackupSuppliesNothing()
		{
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 500);
			primary.setEnabled(false);
			backup.setEnabled(false);

			tick();
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a tripped backup supplies nothing")
		void trippedBackupSuppliesNothing()
		{
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 500);
			primary.setEnabled(false);
			backup.setTripped(true);

			tick();
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("an empty backup supplies nothing")
		void emptyBackupSuppliesNothing()
		{
			GridDevice service = service(grid, primary, 500, 1000);
			primary.setEnabled(false);
			tick();
			assertEquals(0, endpointOf(service).totalInserted);
		}
	}

	@Nested
	@DisplayName("shortfall top-up")
	class TopUp
	{
		@Test
		@DisplayName("with top-up on, a backup covers what the primary could not")
		void topUpCoversShortfall()
		{
			primary.getPolicy().setFailoverTopUp(true);
			feed(grid, primary, 100, 1000);
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 1000);

			tick();
			assertEquals(500, endpointOf(service).totalInserted,
					"100 from the primary plus 400 from the backup");
			assertEquals(100, primary.getStats().getLifetimeOut());
			assertEquals(400, backup.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("with top-up off, a shortfall is simply not covered")
		void topUpOffLeavesShortfall()
		{
			primary.getPolicy().setFailoverTopUp(false);
			feed(grid, primary, 100, 1000);
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 1000);

			tick();
			assertEquals(100, endpointOf(service).totalInserted);
			assertEquals(1000, backup.getBuffer(), "the backup was never asked");
		}

		@Test
		@DisplayName("a satisfied load never reaches for the backup")
		void satisfiedLoadDoesNotUseBackup()
		{
			primary.getPolicy().setFailoverTopUp(true);
			feed(grid, primary, 1000, 1000);
			GridDevice service = service(grid, primary, 200, 1000);
			prime(backup, 1000);

			tick();
			assertEquals(200, endpointOf(service).totalInserted);
			assertEquals(1000, backup.getBuffer());
		}

		@Test
		@DisplayName("a load capped by its own transfer rate is not a shortfall")
		void deviceCapIsNotAShortfall()
		{
			primary.getPolicy().setFailoverTopUp(true);
			feed(grid, primary, 1000, 1000);
			GridDevice service = service(grid, primary, 5000, 100);
			prime(backup, 1000);

			tick();
			assertEquals(100, endpointOf(service).totalInserted);
			assertEquals(1000, backup.getBuffer(),
					"the device is at its own ceiling, so there is nothing to make up");
		}

		@Test
		@DisplayName("the backup's own output cap still applies")
		void backupOutputCapApplies()
		{
			primary.getPolicy().setFailoverTopUp(true);
			backup.getPolicy().setMaxOutput(150);
			GridDevice service = service(grid, primary, 1000, 1000);
			prime(backup, 1000);

			tick();
			assertEquals(150, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a backup already busy this tick has a reduced budget")
		void backupBudgetIsShared()
		{
			primary.getPolicy().setFailoverTopUp(true);
			backup.getPolicy().setMaxOutput(300);
			//The backup serves its own load first, spending part of its output budget.
			feed(grid, backup, 1000, 1000);
			GridDevice backupService = service(grid, backup, 200, 1000);
			GridDevice primaryService = service(grid, primary, 1000, 1000);

			tick();
			assertEquals(200, endpointOf(backupService).totalInserted);
			assertEquals(100, endpointOf(primaryService).totalInserted,
					"only the remainder of the backup's output budget is available");
		}
	}

	@Nested
	@DisplayName("chains")
	class Chains
	{
		@Test
		@DisplayName("a shortfall walks down the chain when the first backup runs out")
		void walksDownTheChain()
		{
			GridSegment second = segment(grid, "Second");
			backup.addFailover(second.getId());
			prime(backup, 100);
			prime(second, 400);

			GridDevice service = service(grid, primary, 500, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(500, endpointOf(service).totalInserted);
			assertEquals(0, backup.getBuffer());
			assertEquals(0, second.getBuffer());
		}

		@Test
		@DisplayName("sibling backups are asked in declared order")
		void siblingsAskedInOrder()
		{
			GridSegment second = segment(grid, "Second");
			primary.addFailover(second.getId());
			prime(backup, 1000);
			prime(second, 1000);

			GridDevice service = service(grid, primary, 300, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(700, backup.getBuffer(), "the first link is drained first");
			assertEquals(1000, second.getBuffer(), "the second is untouched");
		}

		@Test
		@DisplayName("reordering the links changes who pays")
		void reorderingChangesWhoPays()
		{
			GridSegment second = segment(grid, "Second");
			primary.addFailover(second.getId());
			primary.moveFailover(second.getId(), true);
			prime(backup, 1000);
			prime(second, 1000);

			GridDevice service = service(grid, primary, 300, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(700, second.getBuffer());
			assertEquals(1000, backup.getBuffer());
		}

		@Test
		@DisplayName("the chain stops at the configured depth")
		void depthIsBounded()
		{
			GridConfig.maxFailoverDepth = 1;
			GridSegment deep = segment(grid, "Deep");
			backup.addFailover(deep.getId());
			prime(deep, 1000);

			GridDevice service = service(grid, primary, 500, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(0, endpointOf(service).totalInserted,
					"depth 1 reaches the first backup only");
			assertEquals(1000, deep.getBuffer());
		}

		@Test
		@DisplayName("raising the depth reaches further down the chain")
		void raisingDepthReachesFurther()
		{
			GridConfig.maxFailoverDepth = 2;
			GridSegment deep = segment(grid, "Deep");
			backup.addFailover(deep.getId());
			prime(deep, 1000);

			GridDevice service = service(grid, primary, 500, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(500, endpointOf(service).totalInserted);
		}
	}

	@Nested
	@DisplayName("cycles")
	class Cycles
	{
		@Test
		@DisplayName("a two-segment cycle terminates")
		void twoSegmentCycleTerminates()
		{
			backup.addFailover(primary.getId());
			prime(backup, 500);
			GridDevice service = service(grid, primary, 500, 1000);
			primary.setEnabled(false);

			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> tick());
			assertEquals(500, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a three-segment cycle terminates")
		void threeSegmentCycleTerminates()
		{
			GridSegment third = segment(grid, "Third");
			backup.addFailover(third.getId());
			third.addFailover(primary.getId());
			prime(third, 700);

			GridDevice service = service(grid, primary, 700, 1000);
			primary.setEnabled(false);

			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> tick());
			assertEquals(700, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a segment in a cycle is charged only once per walk")
		void cycleSegmentChargedOnce()
		{
			backup.addFailover(primary.getId());
			prime(backup, 1000);
			GridDevice service = service(grid, primary, 300, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(700, backup.getBuffer(), "300 taken exactly once");
		}

		@Test
		@DisplayName("mutual backups do not double-supply each other")
		void mutualBackupsDoNotDoubleSupply()
		{
			backup.addFailover(primary.getId());
			primary.getPolicy().setFailoverTopUp(true);
			backup.getPolicy().setFailoverTopUp(true);
			prime(primary, 100);
			prime(backup, 100);

			GridDevice primaryService = service(grid, primary, 1000, 1000);
			GridDevice backupService = service(grid, backup, 1000, 1000);

			tick();
			int delivered = endpointOf(primaryService).totalInserted
					+endpointOf(backupService).totalInserted;
			assertEquals(200, delivered, "only the 200 that actually existed may be delivered");
		}
	}

	@Nested
	@DisplayName("phase ordering")
	class PhaseOrdering
	{
		/**
		 * Regression: the three phases used to be nested inside one per-segment loop, so a
		 * segment processed before its backup saw that backup's buffer from <em>before</em>
		 * it had collected. Whether failover worked therefore depended on the order the
		 * segments happened to be created in.
		 */
		@Test
		@DisplayName("a backup created after the primary can still supply it the same tick")
		void backupCreatedLaterStillSupplies()
		{
			//`backup` was created after `primary` in setUp, which is the failing order.
			feed(grid, backup, 400, 1000);
			GridDevice service = service(grid, primary, 400, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(400, endpointOf(service).totalInserted,
					"the backup's feeds must be collected before anyone draws on it");
		}

		@Test
		@DisplayName("a backup created before the primary behaves identically")
		void backupCreatedEarlierBehavesTheSame()
		{
			VirtualGrid other = new VirtualGrid();
			GridSegment earlyBackup = segment(other, "Backup");
			GridSegment latePrimary = segment(other, "Primary");
			latePrimary.addFailover(earlyBackup.getId());

			feed(other, earlyBackup, 400, 1000);
			GridDevice service = service(other, latePrimary, 400, 1000);
			latePrimary.setEnabled(false);

			GridEngine.tick(other, 0, false);
			assertEquals(400, endpointOf(service).totalInserted,
					"creation order must not change the outcome");
		}

		@Test
		@DisplayName("a backup serving its own load first leaves only the remainder")
		void backupServesItselfFirst()
		{
			primary.getPolicy().setFailoverTopUp(true);
			backup.getPolicy().setMaxOutput(300);
			feed(grid, backup, 1000, 1000);
			GridDevice backupService = service(grid, backup, 200, 1000);
			GridDevice primaryService = service(grid, primary, 1000, 1000);

			tick();
			assertEquals(200, endpointOf(backupService).totalInserted,
					"a backup's own loads come first");
			assertEquals(100, endpointOf(primaryService).totalInserted,
					"and only its leftover output budget goes to the segment it backs up");
		}
	}

	@Nested
	@DisplayName("accounting")
	class Accounting
	{
		@Test
		@DisplayName("failover output is charged to the backup, not the primary")
		void chargedToBackup()
		{
			GridDevice service = service(grid, primary, 250, 1000);
			prime(backup, 250);
			primary.setEnabled(false);

			tick();
			assertEquals(0, primary.getStats().getLifetimeOut());
			assertEquals(250, backup.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("the served device still records its own throughput")
		void deviceRecordsThroughput()
		{
			GridDevice service = service(grid, primary, 250, 1000);
			prime(backup, 250);
			primary.setEnabled(false);

			tick();
			assertEquals(250, service.getLastThroughput());
		}

		@Test
		@DisplayName("a top-up device's throughput is the sum of both sources")
		void topUpThroughputIsCombined()
		{
			primary.getPolicy().setFailoverTopUp(true);
			feed(grid, primary, 100, 1000);
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 1000);

			tick();
			assertEquals(500, service.getLastThroughput());
		}

		@Test
		@DisplayName("no failover links means no extra work and no supply")
		void noLinksNoSupply()
		{
			primary.clearFailover();
			GridDevice service = service(grid, primary, 500, 1000);
			prime(backup, 1000);
			primary.setEnabled(false);

			tick();
			assertEquals(0, endpointOf(service).totalInserted);
			assertEquals(1000, backup.getBuffer());
		}

		@Test
		@DisplayName("critical loads on a dead segment are covered first")
		void criticalCoveredFirst()
		{
			GridDevice ordinary = service(grid, primary, 500, 500, 50, false);
			GridDevice critical = service(grid, primary, 500, 500, -50, true);
			prime(backup, 500);
			primary.setEnabled(false);

			tick();
			assertEquals(500, endpointOf(critical).totalInserted);
			assertEquals(0, endpointOf(ordinary).totalInserted);
		}
	}
}
