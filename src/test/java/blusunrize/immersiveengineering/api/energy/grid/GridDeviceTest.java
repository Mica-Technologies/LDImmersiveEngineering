/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GridDeviceTest
{
	private DimensionBlockPos pos;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		pos = new DimensionBlockPos(10, 64, -20, 0);
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("a new device is enabled, unlinked and at the configured cap")
		void newDeviceDefaults()
		{
			GridConfig.defaultDeviceCap = 512;
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			assertTrue(device.isEnabled());
			assertFalse(device.isLinked());
			assertNull(device.getSegment());
			assertEquals(512, device.getTransferCap());
			assertEquals(0, device.getPriority());
			assertFalse(device.isCritical());
			assertFalse(device.isChunkLoadRequested());
			assertEquals("", device.getCustomName());
		}

		@Test
		@DisplayName("position and type are what was asked for")
		void identityIsPreserved()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.SERVICE);
			assertEquals(pos, device.getPos());
			assertSame(GridDeviceType.SERVICE, device.getType());
			assertEquals(0, device.getDimension());
		}

		@Test
		@DisplayName("dimension comes from the position")
		void dimensionFromPosition()
		{
			GridDevice device = new GridDevice(new DimensionBlockPos(0, 0, 0, -1), GridDeviceType.FEED);
			assertEquals(-1, device.getDimension());
		}
	}

	@Nested
	@DisplayName("settings")
	class Settings
	{
		@Test
		@DisplayName("transfer cap is clamped to the configured ceiling")
		void transferCapClamped()
		{
			GridConfig.maxSegmentIO = 4096;
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setTransferCap(999999);
			assertEquals(4096, device.getTransferCap());
		}

		@Test
		@DisplayName("a negative transfer cap becomes zero")
		void negativeTransferCapIsZero()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setTransferCap(-100);
			assertEquals(0, device.getTransferCap());
		}

		@Test
		@DisplayName("priority accepts negatives -- lower than default is a valid choice")
		void priorityAcceptsNegatives()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.SERVICE);
			device.setPriority(-5);
			assertEquals(-5, device.getPriority());
		}

		@Test
		@DisplayName("a null custom name is stored as empty, never null")
		void nullNameBecomesEmpty()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setCustomName(null);
			assertEquals("", device.getCustomName());
		}

		@Test
		@DisplayName("display name falls back to type and coordinates")
		void displayNameFallsBack()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			String name = device.getDisplayName();
			assertTrue(name.contains("feed"), name);
			assertTrue(name.contains("10"), name);
			assertTrue(name.contains("64"), name);
			assertTrue(name.contains("-20"), name);
		}

		@Test
		@DisplayName("display name prefers the custom name")
		void displayNamePrefersCustom()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setCustomName("North substation tap");
			assertEquals("North substation tap", device.getDisplayName());
		}

		@Test
		@DisplayName("chunk loading is suppressed while the config forbids it")
		void chunkLoadGatedByConfig()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setChunkLoad(true);
			assertTrue(device.isChunkLoad());

			GridConfig.allowChunkloading = false;
			assertFalse(device.isChunkLoad(), "config must win over the per-device toggle");
			assertTrue(device.isChunkLoadRequested(), "but the player's choice is remembered");

			GridConfig.allowChunkloading = true;
			assertTrue(device.isChunkLoad(), "and comes back when the config allows it again");
		}
	}

	@Nested
	@DisplayName("online state")
	class OnlineState
	{
		@Test
		@DisplayName("a device with no endpoint is offline and inactive")
		void noEndpointIsOffline()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			assertFalse(device.isOnline());
			assertFalse(device.isActive());
			assertNull(device.getEndpoint());
		}

		@Test
		@DisplayName("attaching an endpoint brings the device online")
		void attachingBringsOnline()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setEndpoint(new FakeEndpoint());
			assertTrue(device.isOnline());
			assertTrue(device.isActive());
		}

		@Test
		@DisplayName("a disabled device is online but not active")
		void disabledIsOnlineButInactive()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setEndpoint(new FakeEndpoint());
			device.setEnabled(false);
			assertTrue(device.isOnline());
			assertFalse(device.isActive());
		}

		@Test
		@DisplayName("attaching notifies the endpoint so it can size its buffer")
		void attachingNotifiesEndpoint()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			FakeEndpoint endpoint = new FakeEndpoint();
			device.setEndpoint(endpoint);
			assertEquals(1, endpoint.configChangeCalls);
		}

		@Test
		@DisplayName("changing the cap notifies the endpoint")
		void capChangeNotifiesEndpoint()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			FakeEndpoint endpoint = new FakeEndpoint();
			device.setEndpoint(endpoint);
			device.setTransferCap(64);
			assertEquals(2, endpoint.configChangeCalls);
		}

		@Test
		@DisplayName("detaching clears the recorded throughput")
		void detachingClearsThroughput()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setEndpoint(new FakeEndpoint());
			device.setLastThroughput(500);
			device.setEndpoint(null);
			assertEquals(0, device.getLastThroughput());
			assertFalse(device.isOnline());
		}
	}

	@Nested
	@DisplayName("city-mode liveness")
	class Liveness
	{
		@Test
		@DisplayName("a device that never reported is not live")
		void neverReportedIsNotLive()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			assertFalse(device.isLive(0));
			assertFalse(device.isLive(100000));
		}

		@Test
		@DisplayName("a device stays live for two sip intervals")
		void staysLiveForTwoIntervals()
		{
			GridConfig.sipIntervalTicks = 100;
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setLastLiveTick(1000);
			assertTrue(device.isLive(1000));
			assertTrue(device.isLive(1100), "one missed check must not brown out a city");
			assertTrue(device.isLive(1200), "exactly two intervals is still live");
			assertFalse(device.isLive(1201));
		}

		@Test
		@DisplayName("the grace period follows the configured interval")
		void graceFollowsConfig()
		{
			GridDevice device = new GridDevice(pos, GridDeviceType.FEED);
			device.setLastLiveTick(0);
			GridConfig.sipIntervalTicks = 20;
			assertTrue(device.isLive(40));
			assertFalse(device.isLive(41));
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("round-trips every persisted field")
		void roundTripsAllFields()
		{
			UUID segment = UUID.randomUUID();
			GridDevice original = new GridDevice(pos, GridDeviceType.SERVICE);
			original.setSegmentInternal(segment);
			original.setCustomName("Tram feeder");
			original.setTransferCap(1234);
			original.setPriority(-3);
			original.setCritical(true);
			original.setChunkLoad(true);
			original.setEnabled(false);

			GridDevice loaded = GridDevice.readFromNBT(original.writeToNBT(new NBTTagCompound()));

			assertNotNull(loaded);
			assertEquals(pos, loaded.getPos());
			assertEquals(0, loaded.getDimension());
			assertSame(GridDeviceType.SERVICE, loaded.getType());
			assertEquals(segment, loaded.getSegment());
			assertEquals("Tram feeder", loaded.getCustomName());
			assertEquals(1234, loaded.getTransferCap());
			assertEquals(-3, loaded.getPriority());
			assertTrue(loaded.isCritical());
			assertTrue(loaded.isChunkLoadRequested());
			assertFalse(loaded.isEnabled());
		}

		@Test
		@DisplayName("a non-zero dimension survives the round trip")
		void dimensionRoundTrips()
		{
			GridDevice original = new GridDevice(new DimensionBlockPos(1, 2, 3, -1), GridDeviceType.FEED);
			GridDevice loaded = GridDevice.readFromNBT(original.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(-1, loaded.getDimension());
			assertEquals(new DimensionBlockPos(1, 2, 3, -1), loaded.getPos());
		}

		@Test
		@DisplayName("an unlinked device round-trips as unlinked")
		void unlinkedRoundTrips()
		{
			GridDevice original = new GridDevice(pos, GridDeviceType.FEED);
			GridDevice loaded = GridDevice.readFromNBT(original.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertNull(loaded.getSegment());
			assertFalse(loaded.isLinked());
		}

		@Test
		@DisplayName("online state is never persisted")
		void onlineStateNotPersisted()
		{
			GridDevice original = new GridDevice(pos, GridDeviceType.FEED);
			original.setEndpoint(new FakeEndpoint());
			GridDevice loaded = GridDevice.readFromNBT(original.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertFalse(loaded.isOnline(), "a loaded device comes online when its chunk does");
		}

		@Test
		@DisplayName("null NBT yields null rather than throwing")
		void nullNbtYieldsNull()
		{
			assertNull(GridDevice.readFromNBT(null));
		}

		@Test
		@DisplayName("a tag without a type is rejected")
		void tagWithoutTypeRejected()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("x", 1);
			assertNull(GridDevice.readFromNBT(nbt));
		}

		@Test
		@DisplayName("a malformed segment id unlinks rather than failing world load")
		void malformedSegmentUnlinks()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", 0);
			nbt.setString("segment", "definitely-not-a-uuid");
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertNull(loaded.getSegment());
		}

		@Test
		@DisplayName("a record from before the enabled flag existed loads as enabled")
		void missingEnabledDefaultsToOn()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", 0);
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertTrue(loaded.isEnabled(), "upgrading must not switch every device off");
		}

		@Test
		@DisplayName("an out-of-range type ordinal falls back instead of crashing")
		void unknownTypeFallsBack()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("type", 99);
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertSame(GridDeviceType.FEED, loaded.getType());
		}

		@Test
		@DisplayName("a cap saved above a since-lowered ceiling is clamped on load")
		void loadedCapIsClamped()
		{
			GridDevice original = new GridDevice(pos, GridDeviceType.FEED);
			original.setTransferCap(50000);
			NBTTagCompound nbt = original.writeToNBT(new NBTTagCompound());
			GridConfig.maxSegmentIO = 1000;
			GridDevice loaded = GridDevice.readFromNBT(nbt);
			assertNotNull(loaded);
			assertEquals(1000, loaded.getTransferCap());
		}
	}

	@Nested
	@DisplayName("parseUUID")
	class ParseUuid
	{
		@Test
		@DisplayName("parses a well-formed id")
		void parsesValid()
		{
			UUID id = UUID.randomUUID();
			assertEquals(id, GridDevice.parseUUID(id.toString()));
		}

		@Test
		@DisplayName("returns null for junk rather than throwing")
		void rejectsJunk()
		{
			assertNull(GridDevice.parseUUID("nonsense"));
			assertNull(GridDevice.parseUUID(""));
			assertNull(GridDevice.parseUUID(null));
			assertNull(GridDevice.parseUUID("1234"));
		}
	}
}
