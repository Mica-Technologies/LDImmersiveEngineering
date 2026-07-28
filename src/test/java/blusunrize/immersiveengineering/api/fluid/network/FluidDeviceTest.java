/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static blusunrize.immersiveengineering.api.fluid.network.FluidNetTestSupport.resetConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FluidDevice} and {@link FluidDeviceType}: one registered fitting.
 * <p>
 * The mirror of {@code GridDeviceTest}. Almost everything here is about a value being clamped or a
 * default being chosen, and the pattern that matters is what an <em>absent</em> NBT key means: a
 * record written by an older build has to load into something sensible rather than something
 * silently switched off.
 */
class FluidDeviceTest
{
	@BeforeEach
	void setUp()
	{
		resetConfig();
	}

	private static FluidDevice device(FluidDeviceType type)
	{
		return new FluidDevice(new DimensionBlockPos(1, 64, 2, 0), type);
	}

	@Nested
	@DisplayName("the type enum")
	class Types
	{
		@Test
		@DisplayName("only inlets and outlets move fluid")
		void movesFluid()
		{
			assertTrue(FluidDeviceType.INLET.movesFluid());
			assertTrue(FluidDeviceType.OUTLET.movesFluid());
			assertFalse(FluidDeviceType.VALVE.movesFluid());
			assertFalse(FluidDeviceType.CONSOLE.movesFluid());
		}

		@Test
		@DisplayName("an unknown ordinal resolves rather than throwing")
		void byIndexNeverThrows()
		{
			//Ordinals are persisted, so a save from a newer build must not crash world load.
			assertEquals(FluidDeviceType.INLET, FluidDeviceType.byIndex(-1));
			assertEquals(FluidDeviceType.INLET, FluidDeviceType.byIndex(999));
			assertEquals(FluidDeviceType.VALVE, FluidDeviceType.byIndex(FluidDeviceType.VALVE.ordinal()));
		}

		@Test
		@DisplayName("the persisted order has not been disturbed")
		void ordinalsAreStable()
		{
			//These are written into save data. Reordering them silently turns every Outlet in every
			//world into an Inlet.
			assertEquals(0, FluidDeviceType.INLET.ordinal());
			assertEquals(1, FluidDeviceType.OUTLET.ordinal());
			assertEquals(2, FluidDeviceType.CONSOLE.ordinal());
			assertEquals(3, FluidDeviceType.VALVE.ordinal());
		}

		@Test
		@DisplayName("names are lower case, because they are shown to players")
		void namesAreLowerCase()
		{
			for(FluidDeviceType type : FluidDeviceType.values())
				assertEquals(type.getName().toLowerCase(java.util.Locale.ENGLISH), type.getName());
		}
	}

	@Nested
	@DisplayName("settings")
	class Settings
	{
		@Test
		@DisplayName("a new fitting takes the configured default cap")
		void defaultCap()
		{
			FluidNetConfig.defaultDeviceCap = 4321;
			assertEquals(4321, device(FluidDeviceType.INLET).getTransferCap());
		}

		@Test
		@DisplayName("the cap is clamped into the configured range")
		void capIsClamped()
		{
			FluidNetConfig.maxMainIO = 1000;
			FluidDevice device = device(FluidDeviceType.INLET);
			device.setTransferCap(-5);
			assertEquals(0, device.getTransferCap(), "negative throughput is not a thing");
			device.setTransferCap(999999);
			assertEquals(1000, device.getTransferCap());
		}

		@Test
		@DisplayName("a name is trimmed to something, never null")
		void nameIsNeverNull()
		{
			FluidDevice device = device(FluidDeviceType.INLET);
			device.setCustomName(null);
			assertEquals("", device.getCustomName());
			assertTrue(device.getDisplayName().contains("inlet"),
					"an unnamed fitting still has to be identifiable in a list");
			device.setCustomName("Refinery feed");
			assertEquals("Refinery feed", device.getDisplayName());
		}

		@Test
		@DisplayName("chunk loading obeys the master config switch")
		void chunkLoadingIsGated()
		{
			FluidDevice device = device(FluidDeviceType.INLET);
			device.setChunkLoad(true);
			FluidNetConfig.allowChunkloading = true;
			assertTrue(device.isChunkLoad());
			FluidNetConfig.allowChunkloading = false;
			assertFalse(device.isChunkLoad(), "the config wins");
			assertTrue(device.isChunkLoadRequested(),
					"but the player's own setting is remembered for when it is switched back on");
		}
	}

	@Nested
	@DisplayName("valve wiring")
	class Valves
	{
		@Test
		@DisplayName("a plain shut-off closes on power")
		void plainShutOff()
		{
			FluidDevice valve = device(FluidDeviceType.VALVE);
			valve.setValveInverted(false);
			assertTrue(valve.isClosing(true));
			assertFalse(valve.isClosing(false));
		}

		@Test
		@DisplayName("an inverted shut-off is a dead-man's switch")
		void invertedShutOff()
		{
			//It demands a keep-open signal, so a branch cannot outlive whatever was controlling it.
			FluidDevice valve = device(FluidDeviceType.VALVE);
			valve.setValveInverted(true);
			assertFalse(valve.isClosing(true));
			assertTrue(valve.isClosing(false));
		}
	}

	@Nested
	@DisplayName("the meter")
	class Meter
	{
		@Test
		@DisplayName("throughput accumulates within a tick and the meter keeps climbing")
		void throughputAndMeter()
		{
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.recordThroughput(30);
			device.recordThroughput(20);
			assertEquals(50, device.getLastThroughput());
			assertEquals(50, device.getLifetimeThroughput());

			device.setLastThroughput(0);
			assertEquals(0, device.getLastThroughput(), "the per-tick figure is cleared every tick");
			assertEquals(50, device.getLifetimeThroughput(), "the meter is not");
		}

		@Test
		@DisplayName("non-positive amounts are ignored")
		void nonPositiveIgnored()
		{
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.recordThroughput(0);
			device.recordThroughput(-10);
			assertEquals(0, device.getLifetimeThroughput());
		}

		@Test
		@DisplayName("resetting the meter leaves the per-tick figure alone")
		void resetMeter()
		{
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.recordThroughput(50);
			device.resetMeter();
			assertEquals(0, device.getLifetimeThroughput());
			assertEquals(50, device.getLastThroughput());
		}
	}

	@Nested
	@DisplayName("city-mode liveness")
	class Liveness
	{
		@Test
		@DisplayName("a fitting that has never proved itself is not live")
		void neverProvedIsNotLive()
		{
			assertFalse(device(FluidDeviceType.INLET).isLive(0));
		}

		@Test
		@DisplayName("liveness has a grace of two intervals, so one missed check is survivable")
		void graceIsTwoIntervals()
		{
			FluidNetConfig.sipIntervalTicks = 100;
			FluidDevice device = device(FluidDeviceType.INLET);
			device.setLastLiveTick(0);
			assertTrue(device.isLive(199));
			assertTrue(device.isLive(200));
			assertFalse(device.isLive(201), "past the grace, the source counts as dead");
		}
	}

	@Nested
	@DisplayName("online state")
	class Online
	{
		@Test
		@DisplayName("a fitting is active only when enabled and attached")
		void activeNeedsBoth()
		{
			FluidDevice device = device(FluidDeviceType.INLET);
			assertFalse(device.isActive(), "not attached");
			device.setEndpoint(new FakeFluidEndpoint());
			assertTrue(device.isActive());
			device.setEnabled(false);
			assertFalse(device.isActive(), "disabled");
		}

		@Test
		@DisplayName("detaching clears the last tick's throughput")
		void detachClearsThroughput()
		{
			//Otherwise a fitting whose chunk unloaded would sit in the console showing whatever it
			//happened to be moving at the moment the chunk went away, forever.
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.setEndpoint(new FakeFluidEndpoint());
			device.recordThroughput(40);
			device.setEndpoint(null);
			assertEquals(0, device.getLastThroughput());
		}

		@Test
		@DisplayName("attaching tells the endpoint to resize itself")
		void attachNotifies()
		{
			FakeFluidEndpoint endpoint = new FakeFluidEndpoint();
			device(FluidDeviceType.INLET).setEndpoint(endpoint);
			assertEquals(1, endpoint.configChangeCalls);
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a fitting survives a save and reload")
		void roundTrip()
		{
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.setCustomName("hall feed");
			device.setTransferCap(321);
			device.setPriority(7);
			device.setCritical(true);
			device.setChunkLoad(true);
			device.setEnabled(false);
			device.recordThroughput(1234);

			FluidDevice loaded = FluidDevice.readFromNBT(device.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(FluidDeviceType.OUTLET, loaded.getType());
			assertEquals(device.getPos(), loaded.getPos());
			assertEquals("hall feed", loaded.getCustomName());
			assertEquals(321, loaded.getTransferCap());
			assertEquals(7, loaded.getPriority());
			assertTrue(loaded.isCritical());
			assertTrue(loaded.isChunkLoadRequested());
			assertFalse(loaded.isEnabled());
			assertEquals(1234, loaded.getLifetimeThroughput());
		}

		@Test
		@DisplayName("valve settings are written only for valves")
		void valveSettingsAreTypeScoped()
		{
			FluidDevice valve = device(FluidDeviceType.VALVE);
			valve.setValveOutput(false);
			valve.setValveInverted(true);
			NBTTagCompound nbt = valve.writeToNBT(new NBTTagCompound());
			assertTrue(nbt.hasKey("valveOutput"));

			assertFalse(device(FluidDeviceType.INLET).writeToNBT(new NBTTagCompound())
					.hasKey("valveOutput"), "an inlet has no valve settings to store");

			FluidDevice loaded = FluidDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertFalse(loaded.isValveOutput());
			assertTrue(loaded.isValveInverted());
		}

		@Test
		@DisplayName("an absent enabled flag reads as enabled, not as switched off")
		void absentEnabledDefaultsOn()
		{
			//A record written before the flag existed must not switch every fitting off on upgrade.
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", FluidDeviceType.INLET.ordinal());
			FluidDevice loaded = FluidDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertTrue(loaded.isEnabled());
			assertTrue(loaded.isValveOutput(),
					"and an absent valve direction reads as output -- an input would close a main");
		}

		@Test
		@DisplayName("a record with no type at all is skipped")
		void typelessRecordIsSkipped()
		{
			assertNull(FluidDevice.readFromNBT(null));
			assertNull(FluidDevice.readFromNBT(new NBTTagCompound()));
		}

		@Test
		@DisplayName("a malformed main id unlinks the fitting rather than failing world load")
		void malformedMainIdUnlinks()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", FluidDeviceType.INLET.ordinal());
			nbt.setString("main", "not-a-uuid");
			FluidDevice loaded = FluidDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertFalse(loaded.isLinked());
		}

		@Test
		@DisplayName("a negative meter reading is clamped rather than trusted")
		void negativeMeterIsClamped()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", FluidDeviceType.INLET.ordinal());
			nbt.setLong("meter", -500);
			FluidDevice loaded = FluidDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertEquals(0, loaded.getLifetimeThroughput());
		}

		@Test
		@DisplayName("live state rides along only when asked for")
		void liveStateIsOptional()
		{
			FluidDevice device = device(FluidDeviceType.OUTLET);
			device.setEndpoint(new FakeFluidEndpoint());
			device.recordThroughput(99);

			assertFalse(device.writeToNBT(new NBTTagCompound(), false).hasKey("online"));
			NBTTagCompound sync = device.writeToNBT(new NBTTagCompound(), true);
			assertTrue(sync.getBoolean("online"));
			assertEquals(99, sync.getInteger("throughput"));

			FluidDevice remote = FluidDevice.readFromNBT(sync);
			assertNotNull(remote);
			assertTrue(remote.isOnline(), "the client has no endpoint to ask, so the flag is the answer");
			assertEquals(99, remote.getLastThroughput());
		}

		@Test
		@DisplayName("parseUUID is lenient")
		void parseUuid()
		{
			assertNull(FluidDevice.parseUUID(null));
			assertNull(FluidDevice.parseUUID(""));
			assertNull(FluidDevice.parseUUID("nope"));
			UUID id = UUID.randomUUID();
			assertEquals(id, FluidDevice.parseUUID(id.toString()));
		}
	}
}
