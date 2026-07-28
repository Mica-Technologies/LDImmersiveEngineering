/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FluidPolicy}: the per-main rules, and every clamp that stands between a hand-crafted
 * packet and a broken network.
 * <p>
 * The mirror of {@code GridPolicyTest}. The schedule gets the most attention because its window
 * wraps midnight, and a wrapping range is the classic place for an off-by-one that only shows up
 * for two hours of every in-game day.
 */
class FluidPolicyTest
{
	@BeforeEach
	void setUp()
	{
		FluidNetConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("defaults")
	class Defaults
	{
		@Test
		@DisplayName("a new policy carries the current config, not the shipped constants")
		void takesConfigAtConstruction()
		{
			FluidNetConfig.maxMainIO = 4242;
			FluidNetConfig.defaultLeakPct = 0.5;
			FluidPolicy policy = new FluidPolicy();
			assertEquals(4242, policy.getMaxInput());
			assertEquals(4242, policy.getMaxOutput());
			assertEquals(0.5, policy.getLeakPct(), 1e-9);
		}

		@Test
		@DisplayName("the default pack is packTicks of the main's own output rate")
		void defaultPack()
		{
			FluidNetConfig.packTicks = 3;
			FluidNetConfig.packCapMax = 1000000;
			assertEquals(3000, FluidPolicy.defaultPackCap(1000));
		}

		@Test
		@DisplayName("the default pack never exceeds the hard ceiling")
		void defaultPackIsCapped()
		{
			//This is the anti-tank clamp: a main is a conduit, and the buried tanks are storage.
			FluidNetConfig.packTicks = 20;
			FluidNetConfig.packCapMax = 500;
			assertEquals(500, FluidPolicy.defaultPackCap(1000));
		}

		@Test
		@DisplayName("the shipped defaults line up so a new main gets its full smoothing pack")
		void shippedDefaultsAreConsistent()
		{
			//maxMainIO * packTicks must land on packCapMax, or a new main is silently clamped to
			//less smoothing than the collect-then-serve ordering assumes.
			assertEquals(FluidNetConfig.packCapMax,
					(long)FluidNetConfig.maxMainIO*FluidNetConfig.packTicks,
					"raise fluidNetMaxMainIO and fluidNetPackCapMax together, or neither");
		}

		@Test
		@DisplayName("a degenerate output rate does not produce a negative pack")
		void zeroOutputIsSafe()
		{
			assertEquals(0, FluidPolicy.defaultPackCap(0));
			assertEquals(0, FluidPolicy.defaultPackCap(-100));
		}
	}

	@Nested
	@DisplayName("clamping")
	class Clamping
	{
		@Test
		@DisplayName("rates are clamped into 0..maxMainIO")
		void ratesClamp()
		{
			FluidNetConfig.maxMainIO = 500;
			FluidPolicy policy = new FluidPolicy();
			policy.setMaxInput(-1);
			assertEquals(0, policy.getMaxInput());
			policy.setMaxOutput(999999);
			assertEquals(500, policy.getMaxOutput());
		}

		@Test
		@DisplayName("leakage is a fraction, and stays one")
		void leakIsAFraction()
		{
			FluidPolicy policy = new FluidPolicy();
			policy.setLeakPct(-0.5);
			assertEquals(0, policy.getLeakPct(), 1e-9);
			policy.setLeakPct(5);
			assertEquals(1, policy.getLeakPct(), 1e-9);
		}

		@Test
		@DisplayName("clamp() pulls an existing policy back inside a lowered ceiling")
		void clampReappliesBounds()
		{
			FluidNetConfig.maxMainIO = 100000;
			FluidPolicy policy = new FluidPolicy();
			policy.setMaxOutput(90000);
			FluidNetConfig.maxMainIO = 500;
			policy.clamp();
			assertEquals(500, policy.getMaxOutput());
		}

		@Test
		@DisplayName("a copy is independent of its original")
		void copyIsDeep()
		{
			FluidPolicy policy = new FluidPolicy();
			policy.setMaxInput(123);
			policy.setScheduleEnabled(true);
			FluidPolicy copy = policy.copy();
			policy.setMaxInput(456);
			assertEquals(123, copy.getMaxInput());
			assertTrue(copy.isScheduleEnabled());
		}
	}

	@Nested
	@DisplayName("the schedule")
	class Schedule
	{
		@Test
		@DisplayName("a disabled schedule is always inside its window")
		void disabledIsAlwaysOpen()
		{
			FluidPolicy policy = new FluidPolicy();
			for(int time = 0; time < FluidPolicy.DAY_LENGTH; time += 500)
				assertTrue(policy.isWithinSchedule(time));
		}

		@Test
		@DisplayName("a plain window is inclusive at the start and exclusive at the end")
		void plainWindow()
		{
			FluidPolicy policy = new FluidPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(1000);
			policy.setScheduleOff(2000);
			assertFalse(policy.isWithinSchedule(999));
			assertTrue(policy.isWithinSchedule(1000));
			assertTrue(policy.isWithinSchedule(1999));
			assertFalse(policy.isWithinSchedule(2000));
		}

		@Test
		@DisplayName("a window that crosses midnight wraps")
		void wrappingWindow()
		{
			//The interesting case: dusk to dawn, which is most of what anyone schedules.
			FluidPolicy policy = new FluidPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(FluidPolicy.DEFAULT_ON);
			policy.setScheduleOff(FluidPolicy.DEFAULT_OFF);
			assertTrue(policy.isWithinSchedule(12000), "dusk");
			assertTrue(policy.isWithinSchedule(18000), "midnight");
			assertTrue(policy.isWithinSchedule(22999));
			assertFalse(policy.isWithinSchedule(23000), "dawn, exclusive");
			assertFalse(policy.isWithinSchedule(6000), "midday");
		}

		@Test
		@DisplayName("equal endpoints mean never, not always")
		void equalEndpointsMeanNever()
		{
			//A schedule that never runs is a visible mistake; one that always runs is
			//indistinguishable from having no schedule and would hide the typo.
			FluidPolicy policy = new FluidPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(6000);
			policy.setScheduleOff(6000);
			for(int time = 0; time < FluidPolicy.DAY_LENGTH; time += 500)
				assertFalse(policy.isWithinSchedule(time));
		}

		@Test
		@DisplayName("any time value at all is accepted and wrapped")
		void timesWrap()
		{
			FluidPolicy policy = new FluidPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(1000);
			policy.setScheduleOff(2000);
			//World time keeps counting past a day, and a negative can arrive from a command.
			assertTrue(policy.isWithinSchedule(FluidPolicy.DAY_LENGTH+1500));
			assertTrue(policy.isWithinSchedule(-FluidPolicy.DAY_LENGTH+1500));
			policy.setScheduleOn(FluidPolicy.DAY_LENGTH+1000);
			assertEquals(1000, policy.getScheduleOn(), "a stored time is wrapped once, not repeatedly");
			policy.setScheduleOn(-1000);
			assertEquals(FluidPolicy.DAY_LENGTH-1000, policy.getScheduleOn());
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a policy survives a round trip")
		void roundTrip()
		{
			FluidPolicy policy = new FluidPolicy();
			policy.setMaxInput(111);
			policy.setMaxOutput(222);
			policy.setLeakPct(0.125);
			policy.setPackCap(333);
			policy.setFailoverTopUp(false);
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(4000);
			policy.setScheduleOff(5000);

			FluidPolicy loaded = FluidPolicy.readFromNBT(policy.writeToNBT(new NBTTagCompound()));
			assertEquals(111, loaded.getMaxInput());
			assertEquals(222, loaded.getMaxOutput());
			assertEquals(0.125, loaded.getLeakPct(), 1e-9);
			assertEquals(333, loaded.getPackCap());
			assertFalse(loaded.isFailoverTopUp());
			assertTrue(loaded.isScheduleEnabled());
			assertEquals(4000, loaded.getScheduleOn());
			assertEquals(5000, loaded.getScheduleOff());
		}

		@Test
		@DisplayName("reading nothing gives a default policy rather than throwing")
		void readingNullIsSafe()
		{
			FluidPolicy loaded = FluidPolicy.readFromNBT(null);
			assertNotNull(loaded);
			assertEquals(FluidNetConfig.maxMainIO, loaded.getMaxInput());
		}

		@Test
		@DisplayName("an absent key keeps the default rather than reading as zero")
		void absentKeysKeepDefaults()
		{
			//getInteger returns 0 for a missing key, so reading unconditionally would silently set
			//every unwritten rate to zero and stop the main dead.
			NBTTagCompound sparse = new NBTTagCompound();
			sparse.setInteger("maxInput", 42);
			FluidPolicy loaded = FluidPolicy.readFromNBT(sparse);
			assertEquals(42, loaded.getMaxInput());
			assertEquals(FluidNetConfig.maxMainIO, loaded.getMaxOutput(),
					"an unwritten output rate must not come back as zero");
		}

		@Test
		@DisplayName("values out of range in a hand-edited save are clamped on load")
		void loadedValuesAreClamped()
		{
			FluidNetConfig.maxMainIO = 100;
			NBTTagCompound tampered = new NBTTagCompound();
			tampered.setInteger("maxOutput", 999999);
			tampered.setDouble("leakPct", -3);
			FluidPolicy loaded = FluidPolicy.readFromNBT(tampered);
			assertEquals(100, loaded.getMaxOutput());
			assertEquals(0, loaded.getLeakPct(), 1e-9);
		}
	}
}
