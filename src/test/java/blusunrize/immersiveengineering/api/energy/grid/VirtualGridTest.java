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
import java.util.concurrent.atomic.AtomicInteger;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class VirtualGridTest
{
	private VirtualGrid grid;

	@BeforeEach
	void setUp()
	{
		GridTestSupport.resetConfig();
		grid = new VirtualGrid();
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("segments")
	class Segments
	{
		@Test
		@DisplayName("creating adds a segment with a fresh id")
		void createAddsSegment()
		{
			GridSegment a = grid.createSegment("A");
			GridSegment b = grid.createSegment("B");
			assertEquals(2, grid.getSegmentCount());
			assertNotEquals(a.getId(), b.getId());
			assertSame(a, grid.getSegment(a.getId()));
		}

		@Test
		@DisplayName("creating can record an owner")
		void createRecordsOwner()
		{
			UUID owner = UUID.randomUUID();
			assertEquals(owner, grid.createSegment("A", owner).getOwner());
		}

		@Test
		@DisplayName("lookup by unknown or null id yields null")
		void unknownLookupIsNull()
		{
			assertNull(grid.getSegment(UUID.randomUUID()));
			assertNull(grid.getSegment(null));
		}

		@Test
		@DisplayName("lookup by name is case-insensitive")
		void nameLookupIsCaseInsensitive()
		{
			GridSegment segment = grid.createSegment("Harbour Ring");
			assertSame(segment, grid.getSegmentByName("harbour ring"));
			assertSame(segment, grid.getSegmentByName("HARBOUR RING"));
			assertNull(grid.getSegmentByName("harbour"));
			assertNull(grid.getSegmentByName(null));
		}

		@Test
		@DisplayName("addSegment refuses to overwrite an existing id")
		void addRefusesDuplicateId()
		{
			GridSegment segment = grid.createSegment("A");
			assertFalse(grid.addSegment(segment));
			assertFalse(grid.addSegment(null));
			assertEquals(1, grid.getSegmentCount());
		}

		@Test
		@DisplayName("the segment collection cannot be modified from outside")
		void collectionUnmodifiable()
		{
			assertThrows(UnsupportedOperationException.class, () -> grid.getSegments().clear());
		}

		@Test
		@DisplayName("deleting unlinks its devices instead of orphaning them")
		void deleteUnlinksDevices()
		{
			GridSegment segment = segment(grid, "A");
			GridDevice device = feed(grid, segment, 100, 100);
			assertTrue(device.isLinked());

			assertTrue(grid.deleteSegment(segment.getId()));
			assertFalse(device.isLinked());
			assertNull(device.getSegment());
			assertNotNull(grid.getDevice(device.getPos()), "the device itself survives");
		}

		@Test
		@DisplayName("deleting strips the segment out of every failover list")
		void deleteStripsFailoverReferences()
		{
			GridSegment primary = segment(grid, "Primary");
			GridSegment backup = segment(grid, "Backup");
			primary.addFailover(backup.getId());

			grid.deleteSegment(backup.getId());
			assertTrue(primary.getFailover().isEmpty(), "no dangling reference may survive");
		}

		@Test
		@DisplayName("deleting an unknown segment reports false")
		void deleteUnknownReportsFalse()
		{
			assertFalse(grid.deleteSegment(UUID.randomUUID()));
		}
	}

	@Nested
	@DisplayName("devices")
	class Devices
	{
		@Test
		@DisplayName("registering the same position twice returns the same record")
		void registerIsIdempotent()
		{
			DimensionBlockPos p = pos();
			GridDevice first = grid.registerDevice(p, GridDeviceType.FEED);
			GridDevice second = grid.registerDevice(p, GridDeviceType.FEED);
			assertSame(first, second);
			assertEquals(1, grid.getDeviceCount());
		}

		@Test
		@DisplayName("a different type at the same position replaces the record")
		void differentTypeReplaces()
		{
			DimensionBlockPos p = pos();
			GridDevice feed = grid.registerDevice(p, GridDeviceType.FEED);
			GridDevice service = grid.registerDevice(p, GridDeviceType.SERVICE);
			assertNotSame(feed, service);
			assertSame(GridDeviceType.SERVICE, grid.getDevice(p).getType());
			assertEquals(1, grid.getDeviceCount());
		}

		@Test
		@DisplayName("the same coordinates in different dimensions are different devices")
		void samePositionDifferentDimension()
		{
			grid.registerDevice(pos(0, 64, 0, 0), GridDeviceType.FEED);
			grid.registerDevice(pos(0, 64, 0, -1), GridDeviceType.FEED);
			assertEquals(2, grid.getDeviceCount());
		}

		@Test
		@DisplayName("unregistering removes it from its segment too")
		void unregisterRemovesFromSegment()
		{
			GridSegment segment = segment(grid, "A");
			GridDevice device = feed(grid, segment, 100, 100);
			assertEquals(1, segment.getDeviceCount());

			assertSame(device, grid.unregisterDevice(device.getPos()));
			assertEquals(0, segment.getDeviceCount());
			assertEquals(0, grid.getDeviceCount());
			assertFalse(device.isOnline());
		}

		@Test
		@DisplayName("unregistering an unknown position yields null")
		void unregisterUnknownYieldsNull()
		{
			assertNull(grid.unregisterDevice(pos()));
		}

		@Test
		@DisplayName("unlinked devices are listed separately")
		void unlinkedListed()
		{
			GridSegment segment = segment(grid, "A");
			feed(grid, segment, 100, 100);
			GridDevice loose = grid.registerDevice(pos(), GridDeviceType.SERVICE);
			assertEquals(1, grid.getUnlinkedDevices().size());
			assertSame(loose, grid.getUnlinkedDevices().get(0));
		}

		@Test
		@DisplayName("lookup by null position yields null")
		void nullLookupYieldsNull()
		{
			assertNull(grid.getDevice(null));
		}
	}

	@Nested
	@DisplayName("assignment")
	class Assignment
	{
		@Test
		@DisplayName("assigning moves the device between segments")
		void assignMovesDevice()
		{
			GridSegment a = segment(grid, "A");
			GridSegment b = segment(grid, "B");
			GridDevice device = feed(grid, a, 100, 100);

			assertTrue(grid.assignDevice(device, b.getId()));
			assertEquals(0, a.getDeviceCount());
			assertEquals(1, b.getDeviceCount());
			assertEquals(b.getId(), device.getSegment());
		}

		@Test
		@DisplayName("assigning null unlinks")
		void assignNullUnlinks()
		{
			GridSegment a = segment(grid, "A");
			GridDevice device = feed(grid, a, 100, 100);
			assertTrue(grid.assignDevice(device, null));
			assertFalse(device.isLinked());
			assertEquals(0, a.getDeviceCount());
		}

		@Test
		@DisplayName("assigning to an unknown segment is refused")
		void assignUnknownRefused()
		{
			GridDevice device = grid.registerDevice(pos(), GridDeviceType.FEED);
			assertFalse(grid.assignDevice(device, UUID.randomUUID()));
			assertFalse(device.isLinked());
		}

		@Test
		@DisplayName("assigning a null device is refused")
		void assignNullDeviceRefused()
		{
			GridSegment a = segment(grid, "A");
			assertFalse(grid.assignDevice(null, a.getId()));
		}

		@Test
		@DisplayName("re-assigning to the current segment is a no-op that still succeeds")
		void reassignSameIsNoop()
		{
			GridSegment a = segment(grid, "A");
			GridDevice device = feed(grid, a, 100, 100);
			assertTrue(grid.assignDevice(device, a.getId()));
			assertEquals(1, a.getDeviceCount(), "must not double-add");
		}

		@Test
		@DisplayName("a segment spans dimensions when the config allows it")
		void crossDimensionAllowed()
		{
			GridConfig.crossDimension = true;
			GridSegment a = segment(grid, "A");
			GridDevice here = grid.registerDevice(pos(0, 64, 0, 0), GridDeviceType.FEED);
			GridDevice nether = grid.registerDevice(pos(0, 64, 0, -1), GridDeviceType.FEED);
			assertTrue(grid.assignDevice(here, a.getId()));
			assertTrue(grid.assignDevice(nether, a.getId()));
			assertEquals(2, a.getDeviceCount());
		}

		@Test
		@DisplayName("with cross-dimension off a segment is pinned to its first dimension")
		void crossDimensionRefused()
		{
			GridConfig.crossDimension = false;
			GridSegment a = segment(grid, "A");
			GridDevice here = grid.registerDevice(pos(0, 64, 0, 0), GridDeviceType.FEED);
			GridDevice nether = grid.registerDevice(pos(0, 64, 0, -1), GridDeviceType.FEED);

			assertTrue(grid.assignDevice(here, a.getId()));
			assertFalse(grid.assignDevice(nether, a.getId()));
			assertEquals(1, a.getDeviceCount());
		}

		@Test
		@DisplayName("with cross-dimension off, a same-dimension device still joins")
		void sameDimensionStillJoins()
		{
			GridConfig.crossDimension = false;
			GridSegment a = segment(grid, "A");
			GridDevice first = grid.registerDevice(pos(0, 64, 0, 0), GridDeviceType.FEED);
			GridDevice second = grid.registerDevice(pos(1, 64, 0, 0), GridDeviceType.FEED);
			assertTrue(grid.assignDevice(first, a.getId()));
			assertTrue(grid.assignDevice(second, a.getId()));
		}

		@Test
		@DisplayName("an empty segment accepts any dimension even with the option off")
		void emptySegmentAcceptsAnyDimension()
		{
			GridConfig.crossDimension = false;
			GridSegment a = segment(grid, "A");
			GridDevice nether = grid.registerDevice(pos(0, 64, 0, -1), GridDeviceType.FEED);
			assertTrue(grid.assignDevice(nether, a.getId()));
		}
	}

	@Nested
	@DisplayName("attach and detach")
	class AttachDetach
	{
		@Test
		@DisplayName("attaching registers an unknown device -- the self-healing path")
		void attachRegistersUnknown()
		{
			DimensionBlockPos p = pos();
			GridDevice device = grid.attach(p, GridDeviceType.FEED, new FakeEndpoint());
			assertNotNull(device);
			assertTrue(device.isOnline());
			assertSame(device, grid.getDevice(p));
		}

		@Test
		@DisplayName("attaching an existing record keeps its settings")
		void attachKeepsSettings()
		{
			DimensionBlockPos p = pos();
			GridDevice registered = grid.registerDevice(p, GridDeviceType.FEED);
			registered.setCustomName("Tap 4");
			registered.setPriority(7);

			GridDevice attached = grid.attach(p, GridDeviceType.FEED, new FakeEndpoint());
			assertSame(registered, attached);
			assertEquals("Tap 4", attached.getCustomName());
			assertEquals(7, attached.getPriority());
		}

		@Test
		@DisplayName("detaching keeps the record but drops the endpoint")
		void detachKeepsRecord()
		{
			GridSegment segment = segment(grid, "A");
			GridDevice device = feed(grid, segment, 100, 100);

			grid.detach(device.getPos());
			assertFalse(device.isOnline());
			assertNotNull(grid.getDevice(device.getPos()), "an unloaded chunk must not lose the record");
			assertEquals(1, segment.getDeviceCount(), "and it stays listed in its segment");
			assertTrue(segment.getActiveFeeds().isEmpty(), "but the tick engine skips it");
		}

		@Test
		@DisplayName("detaching an unknown position is harmless")
		void detachUnknownIsHarmless()
		{
			assertDoesNotThrow(() -> grid.detach(pos()));
		}

		@Test
		@DisplayName("detachAll drops every endpoint but keeps every record")
		void detachAllDropsEndpoints()
		{
			GridSegment segment = segment(grid, "A");
			feed(grid, segment, 100, 100);
			service(grid, segment, 100, 100);

			grid.detachAll();
			assertEquals(2, grid.getDeviceCount());
			for(GridDevice device : grid.getDevices())
				assertFalse(device.isOnline());
			assertTrue(segment.getActiveFeeds().isEmpty());
			assertTrue(segment.getActiveServices().isEmpty());
		}

		@Test
		@DisplayName("clear wipes everything, so a second world cannot inherit the first")
		void clearWipesEverything()
		{
			GridSegment segment = segment(grid, "A");
			feed(grid, segment, 100, 100);
			grid.clear();
			assertEquals(0, grid.getSegmentCount());
			assertEquals(0, grid.getDeviceCount());
		}
	}

	@Nested
	@DisplayName("aggregates")
	class Aggregates
	{
		@Test
		@DisplayName("totals sum the last completed tick across segments")
		void totalsSumSegments()
		{
			GridSegment a = segment(grid, "A");
			GridSegment b = segment(grid, "B");
			for(GridSegment segment : new GridSegment[]{a, b})
			{
				segment.beginTick();
				segment.recordIn(10);
				segment.recordOut(4);
				segment.endTick();
				segment.beginTick();
			}
			assertEquals(20, grid.getTotalIn());
			assertEquals(8, grid.getTotalOut());
		}

		@Test
		@DisplayName("totals are zero on an empty grid")
		void totalsZeroWhenEmpty()
		{
			assertEquals(0, grid.getTotalIn());
			assertEquals(0, grid.getTotalOut());
			assertEquals(0, grid.getEnergizedSegmentCount());
		}

		@Test
		@DisplayName("energized count reflects the city-mode flag on each segment")
		void energizedCount()
		{
			GridSegment a = segment(grid, "A");
			GridSegment b = segment(grid, "B");
			a.setEnergized(true);
			b.setEnergized(false);
			assertEquals(1, grid.getEnergizedSegmentCount());
		}
	}

	@Nested
	@DisplayName("config changes")
	class ConfigChanges
	{
		@Test
		@DisplayName("onConfigChanged pulls policies inside a lowered ceiling")
		void policiesReclamped()
		{
			GridSegment segment = segment(grid, "A");
			segment.getPolicy().setMaxOutput(50000);
			segment.getPolicy().setBufferCap(50000);
			segment.addToBuffer(40000);

			GridConfig.maxSegmentIO = 1000;
			GridConfig.bufferCapMax = 2000;
			grid.onConfigChanged();

			assertEquals(1000, segment.getPolicy().getMaxOutput());
			assertEquals(2000, segment.getPolicy().getBufferCap());
			assertEquals(2000, segment.getBuffer(), "stored energy follows the cap down");
		}

		@Test
		@DisplayName("onConfigChanged re-clamps device caps and notifies endpoints")
		void deviceCapsReclamped()
		{
			GridSegment segment = segment(grid, "A");
			GridDevice device = feed(grid, segment, 0, 50000);
			FakeEndpoint endpoint = endpointOf(device);
			int before = endpoint.configChangeCalls;

			GridConfig.maxSegmentIO = 256;
			grid.onConfigChanged();

			assertEquals(256, device.getTransferCap());
			assertTrue(endpoint.configChangeCalls > before, "the buffer has to be resized");
		}
	}

	@Nested
	@DisplayName("dirty listener")
	class DirtyListener
	{
		@Test
		@DisplayName("mutations mark the save data dirty")
		void mutationsMarkDirty()
		{
			AtomicInteger dirty = new AtomicInteger();
			grid.setDirtyListener(dirty::incrementAndGet);

			GridSegment segment = grid.createSegment("A");
			assertTrue(dirty.get() >= 1);

			int afterCreate = dirty.get();
			GridDevice device = grid.registerDevice(pos(), GridDeviceType.FEED);
			assertTrue(dirty.get() > afterCreate);

			int afterRegister = dirty.get();
			grid.assignDevice(device, segment.getId());
			assertTrue(dirty.get() > afterRegister);

			int afterAssign = dirty.get();
			grid.unregisterDevice(device.getPos());
			assertTrue(dirty.get() > afterAssign);

			int afterUnregister = dirty.get();
			grid.deleteSegment(segment.getId());
			assertTrue(dirty.get() > afterUnregister);
		}

		@Test
		@DisplayName("a null listener is tolerated")
		void nullListenerTolerated()
		{
			grid.setDirtyListener(null);
			assertDoesNotThrow(() -> grid.createSegment("A"));
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("segments and devices round-trip with their relationship intact")
		void roundTripsRelationship()
		{
			GridSegment segment = segment(grid, "Harbour");
			GridDevice device = feed(grid, segment, 0, 777);
			device.setCustomName("Quay tap");

			NBTTagCompound nbt = grid.writeToNBT(new NBTTagCompound());
			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(nbt);

			assertEquals(1, loaded.getSegmentCount());
			assertEquals(1, loaded.getDeviceCount());
			GridSegment loadedSegment = loaded.getSegmentByName("Harbour");
			assertNotNull(loadedSegment);
			assertEquals(1, loadedSegment.getDeviceCount());
			GridDevice loadedDevice = loaded.getDevice(device.getPos());
			assertNotNull(loadedDevice);
			assertEquals("Quay tap", loadedDevice.getCustomName());
			assertEquals(777, loadedDevice.getTransferCap());
			assertEquals(loadedSegment.getId(), loadedDevice.getSegment());
		}

		@Test
		@DisplayName("the data version is stamped")
		void dataVersionStamped()
		{
			NBTTagCompound nbt = grid.writeToNBT(new NBTTagCompound());
			assertEquals(VirtualGrid.DATA_VERSION, nbt.getInteger("gridDataVersion"));
		}

		@Test
		@DisplayName("reading replaces existing state rather than merging into it")
		void readingReplaces()
		{
			grid.createSegment("Stale");
			VirtualGrid source = new VirtualGrid();
			source.createSegment("Fresh");

			grid.readFromNBT(source.writeToNBT(new NBTTagCompound()));
			assertEquals(1, grid.getSegmentCount());
			assertNull(grid.getSegmentByName("Stale"));
			assertNotNull(grid.getSegmentByName("Fresh"));
		}

		@Test
		@DisplayName("null NBT clears rather than throwing")
		void nullNbtClears()
		{
			grid.createSegment("A");
			assertDoesNotThrow(() -> grid.readFromNBT(null));
			assertEquals(0, grid.getSegmentCount());
		}

		@Test
		@DisplayName("an empty tag yields an empty grid")
		void emptyNbtYieldsEmptyGrid()
		{
			grid.readFromNBT(new NBTTagCompound());
			assertEquals(0, grid.getSegmentCount());
			assertEquals(0, grid.getDeviceCount());
		}

		@Test
		@DisplayName("a device pointing at a missing segment loads as unlinked")
		void orphanDeviceLoadsUnlinked()
		{
			GridSegment segment = segment(grid, "Doomed");
			GridDevice device = feed(grid, segment, 0, 100);
			NBTTagCompound nbt = grid.writeToNBT(new NBTTagCompound());
			//Simulate the segment list being lost while the device list survived.
			nbt.setTag("segments", new net.minecraft.nbt.NBTTagList());

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(nbt);
			GridDevice loadedDevice = loaded.getDevice(device.getPos());
			assertNotNull(loadedDevice);
			assertFalse(loadedDevice.isLinked(), "better unlinked than pointing at nothing");
		}

		@Test
		@DisplayName("failover links to segments that no longer exist are dropped on load")
		void danglingFailoverDropped()
		{
			GridSegment primary = segment(grid, "Primary");
			primary.addFailover(UUID.randomUUID());

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));

			GridSegment loadedPrimary = loaded.getSegmentByName("Primary");
			assertNotNull(loadedPrimary);
			assertTrue(loadedPrimary.getFailover().isEmpty(),
					"the traversal should never have to defend against these at runtime");
		}

		@Test
		@DisplayName("a valid failover link survives the round trip")
		void validFailoverSurvives()
		{
			GridSegment primary = segment(grid, "Primary");
			GridSegment backup = segment(grid, "Backup");
			primary.addFailover(backup.getId());

			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));

			GridSegment loadedPrimary = loaded.getSegmentByName("Primary");
			assertNotNull(loadedPrimary);
			assertEquals(1, loadedPrimary.getFailover().size());
			assertEquals(backup.getId(), loadedPrimary.getFailover().get(0));
		}

		@Test
		@DisplayName("a many-segment, many-device grid survives intact")
		void largeGridRoundTrips()
		{
			for(int s = 0; s < 8; s++)
			{
				GridSegment segment = segment(grid, "Segment "+s);
				for(int d = 0; d < 5; d++)
					feed(grid, segment, 0, 100+d);
			}
			VirtualGrid loaded = new VirtualGrid();
			loaded.readFromNBT(grid.writeToNBT(new NBTTagCompound()));

			assertEquals(8, loaded.getSegmentCount());
			assertEquals(40, loaded.getDeviceCount());
			for(GridSegment segment : loaded.getSegments())
				assertEquals(5, segment.getDeviceCount());
		}
	}
}
