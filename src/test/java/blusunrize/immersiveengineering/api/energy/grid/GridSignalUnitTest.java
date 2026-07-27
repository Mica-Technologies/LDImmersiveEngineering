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
 * Signal Units: redstone in as an external kill switch, redstone out as a state lamp.
 */
class GridSignalUnitTest
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

	private void tickCity()
	{
		GridEngine.tick(grid, 0, true);
	}

	@Nested
	@DisplayName("input mode -- the kill switch")
	class InputMode
	{
		@Test
		@DisplayName("a powered input unit holds its segment off")
		void powerHoldsSegmentOff()
		{
			GridDevice feed = feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 1000, 1000);
			signal(grid, segment, false, false, true);

			tick();

			assertFalse(segment.isOperational(), "a held segment is not operational");
			assertTrue(segment.isForcedOff());
			assertEquals(0, endpointOf(service).totalInserted, "nothing should be delivered");
			assertEquals(0, endpointOf(feed).totalExtracted, "nor collected -- it is held before phase A");
		}

		@Test
		@DisplayName("an unpowered input unit leaves the segment alone")
		void noPowerLeavesSegmentRunning()
		{
			GridDevice service = service(grid, segment, 500, 1000);
			feed(grid, segment, 1000, 1000);
			signal(grid, segment, false, false, false);

			tick();

			assertFalse(segment.isForcedOff());
			assertEquals(500, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("inverted, the absence of redstone is what stops the segment")
		void invertedDemandsKeepAlive()
		{
			GridDevice service = service(grid, segment, 500, 1000);
			feed(grid, segment, 1000, 1000);
			GridDevice killer = signal(grid, segment, false, true, false);

			tick();
			assertTrue(segment.isForcedOff(), "no keep-alive signal means stop");
			assertEquals(0, endpointOf(service).totalInserted);

			endpointOf(killer).redstoneHigh = true;
			tick();
			assertFalse(segment.isForcedOff(), "keep-alive restored");
			assertEquals(500, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("any one unit calling for a stop is enough")
		void killSwitchesAreInSeries()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 1000, 1000);
			signal(grid, segment, false, false, false);
			signal(grid, segment, false, false, true);
			signal(grid, segment, false, false, false);

			tick();

			assertTrue(segment.isForcedOff());
		}

		@Test
		@DisplayName("a disabled input unit stops holding the segment")
		void disabledUnitReleases()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice killer = signal(grid, segment, false, false, true);

			tick();
			assertTrue(segment.isForcedOff());

			killer.setEnabled(false);
			segment.invalidateViews();
			tick();
			assertFalse(segment.isForcedOff(), "an inactive device votes on nothing");
		}

		@Test
		@DisplayName("an offline input unit stops holding the segment")
		void offlineUnitReleases()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice killer = signal(grid, segment, false, false, true);

			tick();
			assertTrue(segment.isForcedOff());

			//The chunk unloaded: the kill switch is not there to be read any more, and a grid
			//that stayed dead because of a switch nobody can see would be unfixable.
			killer.setEndpoint(null);
			segment.invalidateViews();
			tick();
			assertFalse(segment.isForcedOff());
		}

		@Test
		@DisplayName("holding a segment down engages its failover, exactly like the switch")
		void heldSegmentFailsOver()
		{
			GridSegment backup = segment(grid, "Backup");
			feed(grid, backup, 1000, 1000);
			GridDevice service = service(grid, segment, 400, 1000);
			segment.addFailover(backup.getId());
			signal(grid, segment, false, false, true);

			tick();

			assertTrue(segment.isForcedOff());
			assertEquals(400, endpointOf(service).totalInserted,
					"the backup should cover a segment held off by a signal");
		}

		@Test
		@DisplayName("a unit in output mode is never a kill switch")
		void outputModeDoesNotKill()
		{
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 500, 1000);
			signal(grid, segment, true, false, true);

			tick();

			assertFalse(segment.isForcedOff());
			assertEquals(500, endpointOf(service).totalInserted);
		}
	}

	@Nested
	@DisplayName("output mode -- the state lamp")
	class OutputMode
	{
		@Test
		@DisplayName("emits full power while the segment is carrying flux")
		void emitsWhenUp()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 500, 1000);
			GridDevice lamp = signal(grid, segment, true, false, false);

			tick();

			assertEquals(15, endpointOf(lamp).publishedLevel);
		}

		@Test
		@DisplayName("emits nothing while the segment is switched off")
		void silentWhenOff()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 500, 1000);
			GridDevice lamp = signal(grid, segment, true, false, false);
			segment.setEnabled(false);

			tick();

			assertEquals(0, endpointOf(lamp).publishedLevel);
		}

		@Test
		@DisplayName("emits nothing when the segment is on but has no source")
		void silentWhenStarved()
		{
			//No feed unit at all: switched on, but nothing is arriving. This is the case the
			//fault lamp exists for, and it must not read the same as "healthy".
			service(grid, segment, 500, 1000);
			GridDevice lamp = signal(grid, segment, true, false, false);

			tick();

			assertEquals(0, endpointOf(lamp).publishedLevel);
		}

		@Test
		@DisplayName("inverted, it is a fault lamp")
		void invertedIsAlarm()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 500, 1000);
			GridDevice alarm = signal(grid, segment, true, true, false);

			tick();
			assertEquals(0, endpointOf(alarm).publishedLevel, "quiet while healthy");

			segment.setTripped(true);
			tick();
			assertEquals(15, endpointOf(alarm).publishedLevel, "shouts once the breaker trips");
		}

		@Test
		@DisplayName("in city mode it follows energization rather than flux")
		void cityModeFollowsPresence()
		{
			GridDevice lamp = signal(grid, segment, true, false, false);
			GridDevice feed = feed(grid, segment, 1000, 1000);

			//The stagger means the sip may not land on tick 0; drive it directly so the test
			//is about the lamp, not about when the sip happens to fire.
			feed.setLastLiveTick(0);
			tickCity();
			assertEquals(15, endpointOf(lamp).publishedLevel);

			feed.setLastLiveTick(Long.MIN_VALUE);
			tickCity();
			assertEquals(0, endpointOf(lamp).publishedLevel, "no live source, no light");
		}

		@Test
		@DisplayName("a unit in input mode is never driven")
		void inputModeIsNotDriven()
		{
			feed(grid, segment, 1000, 1000);
			service(grid, segment, 500, 1000);
			GridDevice killer = signal(grid, segment, false, false, false);

			tick();

			assertEquals(-1, endpointOf(killer).publishedLevel,
					"an input unit must not have a level pushed onto it");
		}

		@Test
		@DisplayName("segments with no signal units cost nothing")
		void noSignalsNoWork()
		{
			GridDevice service = service(grid, segment, 500, 1000);
			feed(grid, segment, 1000, 1000);

			tick();

			assertEquals(500, endpointOf(service).totalInserted);
			assertTrue(segment.getActiveSignals().isEmpty());
		}
	}

	@Nested
	@DisplayName("the device record")
	class Record
	{
		@Test
		@DisplayName("signal settings survive a save and load")
		void roundTripsThroughNBT()
		{
			GridDevice device = signal(grid, segment, false, true, false);
			device.setCustomName("Kill switch");

			GridDevice loaded = GridDevice.readFromNBT(device.writeToNBT(new NBTTagCompound()));

			assertNotNull(loaded);
			assertEquals(GridDeviceType.SIGNAL, loaded.getType());
			assertFalse(loaded.isSignalOutput());
			assertTrue(loaded.isSignalInverted());
			assertEquals("Kill switch", loaded.getCustomName());
		}

		@Test
		@DisplayName("a record from before Signal Units existed defaults to output")
		void legacyRecordDefaultsToOutput()
		{
			//Input is the dangerous default: an unconfigured unit would silently switch a
			//segment off on upgrade.
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", GridDeviceType.SIGNAL.ordinal());
			nbt.setInteger("x", 1);
			nbt.setInteger("y", 2);
			nbt.setInteger("z", 3);
			nbt.setInteger("dim", 0);

			GridDevice loaded = GridDevice.readFromNBT(nbt);

			assertNotNull(loaded);
			assertTrue(loaded.isSignalOutput());
			assertFalse(loaded.isSignalInverted());
		}

		@Test
		@DisplayName("forcedOff is never persisted")
		void forcedOffIsNotSaved()
		{
			feed(grid, segment, 1000, 1000);
			signal(grid, segment, false, false, true);
			tick();
			assertTrue(segment.isForcedOff());

			GridSegment loaded = GridSegment.readFromNBT(segment.writeToNBT(new NBTTagCompound()));

			assertNotNull(loaded);
			assertFalse(loaded.isForcedOff(),
					"a kill switch that is gone must not keep a segment off across a restart");
		}

		@Test
		@DisplayName("the signal type does not take part in the energy pass")
		void signalMovesNoEnergy()
		{
			assertFalse(GridDeviceType.SIGNAL.movesEnergy());
			assertTrue(GridDeviceType.FEED.movesEnergy());
			assertTrue(GridDeviceType.SERVICE.movesEnergy());
			assertFalse(GridDeviceType.CONSOLE.movesEnergy());
		}

		@Test
		@DisplayName("signal units stay out of the feed and service views")
		void notInEnergyViews()
		{
			signal(grid, segment, true, false, false);
			signal(grid, segment, false, false, false);

			assertTrue(segment.getActiveFeeds().isEmpty());
			assertTrue(segment.getActiveServices().isEmpty());
			assertEquals(2, segment.getActiveSignals().size());
		}

		@Test
		@DisplayName("isKilling resolves the mode against the reading")
		void killingLogic()
		{
			GridDevice plain = signal(grid, segment, false, false, false);
			assertTrue(plain.isKilling(true), "power stops a plain kill switch's segment");
			assertFalse(plain.isKilling(false));

			GridDevice inverted = signal(grid, segment, false, true, false);
			assertFalse(inverted.isKilling(true), "power is the keep-alive when inverted");
			assertTrue(inverted.isKilling(false));
		}
	}
}
