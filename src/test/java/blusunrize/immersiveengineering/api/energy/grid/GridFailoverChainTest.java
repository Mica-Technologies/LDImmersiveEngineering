/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import org.junit.jupiter.api.*;

import java.util.List;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The console's failover "resolution preview".
 * <p>
 * This shares its cycle guard and depth bound with the engine's own walk, so these tests
 * are really about one property: what the panel promises is what the engine would do.
 */
class GridFailoverChainTest
{
	private VirtualGrid grid;
	private GridSegment primary, backup;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
		primary = segment(grid, "Primary");
		backup = segment(grid, "Backup");
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("chain resolution")
	class Chain
	{
		@Test
		@DisplayName("no links yields an empty chain")
		void noLinks()
		{
			assertTrue(GridEngine.failoverChain(grid, primary).isEmpty());
		}

		@Test
		@DisplayName("a null segment yields an empty chain rather than throwing")
		void nullSegment()
		{
			assertTrue(GridEngine.failoverChain(grid, null).isEmpty());
		}

		@Test
		@DisplayName("links are listed in the order they will be asked")
		void declaredOrder()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());

			List<GridSegment> chain = GridEngine.failoverChain(grid, primary);
			assertEquals(2, chain.size());
			assertSame(backup, chain.get(0));
			assertSame(third, chain.get(1));
		}

		@Test
		@DisplayName("reordering the links reorders the preview")
		void reorderingIsReflected()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());
			primary.moveFailover(third.getId(), true);

			assertSame(third, GridEngine.failoverChain(grid, primary).get(0));
		}

		@Test
		@DisplayName("the chain follows links depth-first")
		void depthFirst()
		{
			GridSegment deep = segment(grid, "Deep");
			GridSegment sibling = segment(grid, "Sibling");
			primary.addFailover(backup.getId());
			primary.addFailover(sibling.getId());
			backup.addFailover(deep.getId());

			List<GridSegment> chain = GridEngine.failoverChain(grid, primary);
			assertEquals(3, chain.size());
			assertSame(backup, chain.get(0));
			assertSame(deep, chain.get(1), "a backup's own backup comes before the sibling");
			assertSame(sibling, chain.get(2));
		}

		@Test
		@DisplayName("a cycle terminates and never repeats a segment")
		void cycleTerminates()
		{
			primary.addFailover(backup.getId());
			backup.addFailover(primary.getId());

			List<GridSegment> chain = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
					() -> GridEngine.failoverChain(grid, primary));
			assertEquals(1, chain.size());
			assertSame(backup, chain.get(0));
		}

		@Test
		@DisplayName("a longer cycle also terminates")
		void longCycleTerminates()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			backup.addFailover(third.getId());
			third.addFailover(primary.getId());

			List<GridSegment> chain = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
					() -> GridEngine.failoverChain(grid, primary));
			assertEquals(2, chain.size());
		}

		@Test
		@DisplayName("the chain honours the configured depth bound")
		void depthBound()
		{
			GridSegment deep = segment(grid, "Deep");
			primary.addFailover(backup.getId());
			backup.addFailover(deep.getId());

			GridConfig.maxFailoverDepth = 1;
			assertEquals(1, GridEngine.failoverChain(grid, primary).size());
			GridConfig.maxFailoverDepth = 2;
			assertEquals(2, GridEngine.failoverChain(grid, primary).size());
		}

		@Test
		@DisplayName("a link to a deleted segment is skipped")
		void missingSegmentSkipped()
		{
			primary.addFailover(backup.getId());
			primary.addFailover(java.util.UUID.randomUUID());
			assertEquals(1, GridEngine.failoverChain(grid, primary).size());
		}
	}

	@Nested
	@DisplayName("first available backup")
	class FirstAvailable
	{
		@Test
		@DisplayName("null when nothing is linked")
		void nullWithoutLinks()
		{
			assertNull(GridEngine.firstAvailableBackup(grid, primary, false));
		}

		@Test
		@DisplayName("normal mode: the first backup holding energy")
		void normalModePicksBuffered()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());
			third.addToBuffer(500);

			assertSame(third, GridEngine.firstAvailableBackup(grid, primary, false),
					"the empty first link cannot supply, so the preview must skip it");
		}

		@Test
		@DisplayName("normal mode: an empty chain reports nothing")
		void normalModeEmptyChain()
		{
			primary.addFailover(backup.getId());
			assertNull(GridEngine.firstAvailableBackup(grid, primary, false));
		}

		@Test
		@DisplayName("a switched-off backup is skipped")
		void offBackupSkipped()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());
			backup.addToBuffer(500);
			backup.setEnabled(false);
			third.addToBuffer(500);

			assertSame(third, GridEngine.firstAvailableBackup(grid, primary, false));
		}

		@Test
		@DisplayName("a tripped backup is skipped")
		void trippedBackupSkipped()
		{
			primary.addFailover(backup.getId());
			backup.addToBuffer(500);
			backup.setTripped(true);
			assertNull(GridEngine.firstAvailableBackup(grid, primary, false));
		}

		@Test
		@DisplayName("city mode: the first backup with a live source")
		void cityModePicksLiveSource()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());
			//Buffers are irrelevant in city mode; liveness is what counts.
			backup.addToBuffer(9999);
			third.setSourceLive(true);

			assertSame(third, GridEngine.firstAvailableBackup(grid, primary, true));
		}

		@Test
		@DisplayName("city mode ignores a buffered but dead backup")
		void cityModeIgnoresBuffer()
		{
			primary.addFailover(backup.getId());
			backup.addToBuffer(9999);
			assertNull(GridEngine.firstAvailableBackup(grid, primary, true));
		}
	}

	@Nested
	@DisplayName("agreement with the engine")
	class AgreesWithEngine
	{
		/**
		 * The preview would be worse than useless if it disagreed with what actually
		 * happens, so this drives a real tick and checks the promise was kept.
		 */
		@Test
		@DisplayName("the previewed backup is the one actually charged")
		void previewMatchesReality()
		{
			GridSegment third = segment(grid, "Third");
			primary.addFailover(backup.getId());
			primary.addFailover(third.getId());
			third.addToBuffer(400);

			GridSegment predicted = GridEngine.firstAvailableBackup(grid, primary, false);
			assertSame(third, predicted);

			GridDevice service = service(grid, primary, 400, 1000);
			primary.setEnabled(false);
			GridEngine.tick(grid, 0, false);

			assertEquals(400, endpointOf(service).totalInserted);
			assertEquals(400, third.getStats().getLifetimeOut(), "the predicted backup paid");
			assertEquals(0, backup.getStats().getLifetimeOut());
		}

		@Test
		@DisplayName("a chain the engine cannot use is previewed as unusable")
		void unusableChainPreviewedAsSuch()
		{
			primary.addFailover(backup.getId());
			assertNull(GridEngine.firstAvailableBackup(grid, primary, false));

			GridDevice service = service(grid, primary, 400, 1000);
			primary.setEnabled(false);
			GridEngine.tick(grid, 0, false);
			assertEquals(0, endpointOf(service).totalInserted);
		}
	}
}
