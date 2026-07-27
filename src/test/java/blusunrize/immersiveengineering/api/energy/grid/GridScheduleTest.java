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
 * Per-segment time-of-day scheduling -- street lighting that switches itself on at dusk.
 */
class GridScheduleTest
{
	private VirtualGrid grid;
	private GridSegment segment;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
		segment = segment(grid, "Streetlights");
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	private void tickAt(long dayTime)
	{
		GridEngine.applySchedules(grid, dayTime);
		GridEngine.tick(grid, 0, false);
	}

	@Nested
	@DisplayName("the window")
	class Window
	{
		@Test
		@DisplayName("with no schedule a segment runs whenever it is switched on")
		void disabledScheduleAlwaysRuns()
		{
			GridPolicy policy = segment.getPolicy();
			assertFalse(policy.isScheduleEnabled());
			for(int t = 0; t < GridPolicy.DAY_LENGTH; t += 1000)
				assertTrue(policy.isWithinSchedule(t), "no schedule means always open at "+t);
		}

		@Test
		@DisplayName("a daytime window opens and closes within one day")
		void simpleWindow()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(6000);
			policy.setScheduleOff(9000);

			assertFalse(policy.isWithinSchedule(5999));
			assertTrue(policy.isWithinSchedule(6000), "the on time is inside the window");
			assertTrue(policy.isWithinSchedule(8999));
			assertFalse(policy.isWithinSchedule(9000), "the off time is outside it");
			assertFalse(policy.isWithinSchedule(0));
		}

		@Test
		@DisplayName("a dusk-to-dawn window wraps across midnight")
		void wrappingWindow()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(GridPolicy.DEFAULT_ON);
			policy.setScheduleOff(GridPolicy.DEFAULT_OFF);

			assertFalse(policy.isWithinSchedule(6000), "midday: lights out");
			assertTrue(policy.isWithinSchedule(12000), "dusk: on");
			assertTrue(policy.isWithinSchedule(18000), "midnight: still on");
			assertTrue(policy.isWithinSchedule(22999));
			assertFalse(policy.isWithinSchedule(23000), "dawn: off");
		}

		@Test
		@DisplayName("equal endpoints mean a window that never opens")
		void equalEndpointsNeverOpen()
		{
			//Not "always open": a schedule that runs all day is indistinguishable from having
			//no schedule, so it would hide the typo rather than showing it.
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(6000);
			policy.setScheduleOff(6000);

			assertFalse(policy.isWithinSchedule(6000));
			assertFalse(policy.isWithinSchedule(0));
		}

		@Test
		@DisplayName("times outside a day are wrapped, not rejected")
		void timesWrap()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleOn(GridPolicy.DAY_LENGTH+500);
			assertEquals(500, policy.getScheduleOn());
			policy.setScheduleOff(-1000);
			assertEquals(GridPolicy.DAY_LENGTH-1000, policy.getScheduleOff());

			policy.setScheduleEnabled(true);
			policy.setScheduleOn(1000);
			policy.setScheduleOff(2000);
			//A raw world time, not a day time: the caller should not have to remember to mod it.
			assertTrue(policy.isWithinSchedule(5L*GridPolicy.DAY_LENGTH+1500));
		}
	}

	@Nested
	@DisplayName("the gate")
	class Gate
	{
		private void scheduleDuskToDawn()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(12000);
			policy.setScheduleOff(23000);
		}

		@Test
		@DisplayName("a segment outside its window delivers nothing")
		void asleepDeliversNothing()
		{
			scheduleDuskToDawn();
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 500, 1000);

			tickAt(6000);

			assertTrue(segment.isScheduleSuppressed());
			assertFalse(segment.isOperational());
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("the same segment delivers once the window opens")
		void awakeDelivers()
		{
			scheduleDuskToDawn();
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 500, 1000);

			tickAt(13000);

			assertFalse(segment.isScheduleSuppressed());
			assertEquals(500, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("the schedule cannot switch on a segment the player switched off")
		void scheduleNeverOverridesTheSwitch()
		{
			//The two must not fight. A gate can only hold a segment down; if it could also
			//raise one, the console toggle and the clock would take turns winning.
			scheduleDuskToDawn();
			segment.setEnabled(false);
			feed(grid, segment, 1000, 1000);
			GridDevice service = service(grid, segment, 500, 1000);

			tickAt(13000);

			assertFalse(segment.isScheduleSuppressed(), "the window is open");
			assertFalse(segment.isOperational(), "but the switch still says off");
			assertEquals(0, endpointOf(service).totalInserted);
		}

		@Test
		@DisplayName("a sleeping segment engages its failover")
		void sleepingSegmentFailsOver()
		{
			scheduleDuskToDawn();
			GridSegment backup = segment(grid, "Mains");
			feed(grid, backup, 1000, 1000);
			GridDevice service = service(grid, segment, 400, 1000);
			segment.addFailover(backup.getId());

			tickAt(6000);

			assertEquals(400, endpointOf(service).totalInserted,
					"an outage is an outage, whatever caused it");
		}

		@Test
		@DisplayName("a sleeping segment does not accumulate against its breaker")
		void sleepingSegmentDoesNotTrip()
		{
			GridConfig.breakersEnabled = true;
			GridConfig.breakerTripSeconds = 1;
			scheduleDuskToDawn();
			segment.getPolicy().setMaxOutput(100);
			feed(grid, segment, 100000, 100000);
			service(grid, segment, 100000, 100000);

			for(int i = 0; i < 60; i++)
				tickAt(6000);

			assertFalse(segment.isTripped(), "a segment that is asleep is not saturating");
			assertEquals(0, segment.getSaturatedTicks());
		}

		@Test
		@DisplayName("crossing dusk flips the gate without anyone touching the console")
		void gateFollowsTheClock()
		{
			scheduleDuskToDawn();
			feed(grid, segment, 100000, 1000);
			GridDevice service = service(grid, segment, 100000, 1000);

			tickAt(11999);
			assertEquals(0, endpointOf(service).totalInserted);

			tickAt(12000);
			assertEquals(1000, endpointOf(service).totalInserted);

			tickAt(23000);
			assertEquals(1000, endpointOf(service).totalInserted, "dawn closed it again");
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("the schedule survives a save and load")
		void roundTrips()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(13500);
			policy.setScheduleOff(22500);

			GridPolicy loaded = GridPolicy.readFromNBT(policy.writeToNBT(new NBTTagCompound()));

			assertTrue(loaded.isScheduleEnabled());
			assertEquals(13500, loaded.getScheduleOn());
			assertEquals(22500, loaded.getScheduleOff());
		}

		@Test
		@DisplayName("a policy from before scheduling existed loads with none")
		void legacyPolicyHasNoSchedule()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("maxInput", 4096);

			GridPolicy loaded = GridPolicy.readFromNBT(nbt);

			assertFalse(loaded.isScheduleEnabled());
			assertEquals(4096, loaded.getMaxInput());
		}

		@Test
		@DisplayName("copy carries the schedule")
		void copyCarriesSchedule()
		{
			GridPolicy policy = segment.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(1234);
			policy.setScheduleOff(5678);

			GridPolicy copy = policy.copy();

			assertTrue(copy.isScheduleEnabled());
			assertEquals(1234, copy.getScheduleOn());
			assertEquals(5678, copy.getScheduleOff());
		}

		@Test
		@DisplayName("suppression is recomputed, never persisted")
		void suppressionIsNotSaved()
		{
			segment.getPolicy().setScheduleEnabled(true);
			segment.getPolicy().setScheduleOn(12000);
			segment.getPolicy().setScheduleOff(23000);
			segment.updateSchedule(6000);
			assertTrue(segment.isScheduleSuppressed());

			GridSegment loaded = GridSegment.readFromNBT(segment.writeToNBT(new NBTTagCompound()));

			assertNotNull(loaded);
			assertFalse(loaded.isScheduleSuppressed());
			assertTrue(loaded.getPolicy().isScheduleEnabled(), "the schedule itself does persist");
		}
	}
}
