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

import java.util.List;
import java.util.UUID;

import static blusunrize.immersiveengineering.api.energy.grid.GridTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class GridSegmentTest
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

	@Nested
	@DisplayName("identity and state")
	class Identity
	{
		@Test
		@DisplayName("a new segment is on, unlocked and untripped")
		void newSegmentDefaults()
		{
			assertTrue(segment.isEnabled());
			assertFalse(segment.isTripped());
			assertFalse(segment.isLocked());
			assertTrue(segment.isOperational());
			assertNull(segment.getOwner());
		}

		@Test
		@DisplayName("a null name is stored as empty")
		void nullNameBecomesEmpty()
		{
			segment.setName(null);
			assertEquals("", segment.getName());
		}

		@Test
		@DisplayName("colour is picked from the palette")
		void colourFromPalette()
		{
			boolean inPalette = false;
			for(int colour : GridSegment.PALETTE)
				if(colour==segment.getColor())
					inPalette = true;
			assertTrue(inPalette, "a new segment should start on a palette colour");
		}

		@Test
		@DisplayName("the palette has sixteen distinct colours")
		void paletteIsDistinct()
		{
			assertEquals(16, GridSegment.PALETTE.length);
			for(int i = 0; i < GridSegment.PALETTE.length; i++)
				for(int j = i+1; j < GridSegment.PALETTE.length; j++)
					assertNotEquals(GridSegment.PALETTE[i], GridSegment.PALETTE[j],
							"palette entries "+i+" and "+j+" collide");
		}

		@Test
		@DisplayName("a tripped segment is not operational even while switched on")
		void trippedIsNotOperational()
		{
			segment.setTripped(true);
			assertTrue(segment.isEnabled());
			assertFalse(segment.isOperational());
		}

		@Test
		@DisplayName("switching a segment on also resets its breaker")
		void switchingOnResetsBreaker()
		{
			segment.setTripped(true);
			segment.setEnabled(true);
			assertFalse(segment.isTripped(), "one control, as on a real panel");
			assertTrue(segment.isOperational());
		}

		@Test
		@DisplayName("switching off does not clear a trip")
		void switchingOffKeepsTrip()
		{
			segment.setTripped(true);
			segment.setEnabled(false);
			assertTrue(segment.isTripped());
		}
	}

	@Nested
	@DisplayName("ownership")
	class Ownership
	{
		@Test
		@DisplayName("an unlocked segment is editable by anyone")
		void unlockedIsPublic()
		{
			segment.setOwner(UUID.randomUUID());
			assertTrue(segment.canEdit(UUID.randomUUID()));
			assertTrue(segment.canEdit(null));
		}

		@Test
		@DisplayName("a locked segment is editable only by its owner")
		void lockedIsOwnerOnly()
		{
			UUID owner = UUID.randomUUID();
			segment.setOwner(owner);
			segment.setLocked(true);
			assertTrue(segment.canEdit(owner));
			assertFalse(segment.canEdit(UUID.randomUUID()));
			assertFalse(segment.canEdit(null));
		}

		@Test
		@DisplayName("a locked segment with no owner stays editable")
		void lockedWithoutOwnerIsPublic()
		{
			segment.setLocked(true);
			assertTrue(segment.canEdit(UUID.randomUUID()));
		}
	}

	@Nested
	@DisplayName("buffer")
	class Buffer
	{
		@Test
		@DisplayName("adding respects the cap and reports what was stored")
		void addRespectsCap()
		{
			segment.getPolicy().setBufferCap(100);
			assertEquals(60, segment.addToBuffer(60));
			assertEquals(40, segment.addToBuffer(500), "only the remaining room is stored");
			assertEquals(100, segment.getBuffer());
			assertEquals(0, segment.addToBuffer(10), "a full buffer stores nothing");
		}

		@Test
		@DisplayName("adding a non-positive amount does nothing")
		void addNonPositive()
		{
			assertEquals(0, segment.addToBuffer(0));
			assertEquals(0, segment.addToBuffer(-50));
			assertEquals(0, segment.getBuffer());
		}

		@Test
		@DisplayName("drawing never returns more than is present")
		void drawNeverOverdraws()
		{
			segment.addToBuffer(30);
			assertEquals(30, segment.drawFromBuffer(500));
			assertEquals(0, segment.getBuffer());
			assertEquals(0, segment.drawFromBuffer(10));
		}

		@Test
		@DisplayName("drawing a non-positive amount does nothing")
		void drawNonPositive()
		{
			segment.addToBuffer(30);
			assertEquals(0, segment.drawFromBuffer(0));
			assertEquals(0, segment.drawFromBuffer(-5));
			assertEquals(30, segment.getBuffer());
		}

		@Test
		@DisplayName("setBuffer clamps into 0..cap")
		void setBufferClamps()
		{
			segment.getPolicy().setBufferCap(100);
			segment.setBuffer(500);
			assertEquals(100, segment.getBuffer());
			segment.setBuffer(-20);
			assertEquals(0, segment.getBuffer());
		}

		@Test
		@DisplayName("lowering the cap pulls the stored amount down with it")
		void loweringCapPullsStoredDown()
		{
			segment.getPolicy().setBufferCap(1000);
			segment.addToBuffer(900);
			segment.getPolicy().setBufferCap(100);
			//The stored amount is only re-clamped when something touches it, which is what
			//VirtualGrid.onConfigChanged and the SET_POLICY handler both do.
			segment.setBuffer(segment.getBuffer());
			assertEquals(100, segment.getBuffer());
		}
	}

	@Nested
	@DisplayName("failover links")
	class Failover
	{
		@Test
		@DisplayName("links are kept in insertion order")
		void linksKeepOrder()
		{
			UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
			assertTrue(segment.addFailover(a));
			assertTrue(segment.addFailover(b));
			assertTrue(segment.addFailover(c));
			assertEquals(java.util.Arrays.asList(a, b, c), segment.getFailover());
		}

		@Test
		@DisplayName("a segment cannot back itself up")
		void noSelfLink()
		{
			assertFalse(segment.addFailover(segment.getId()));
			assertTrue(segment.getFailover().isEmpty());
		}

		@Test
		@DisplayName("duplicates are rejected")
		void noDuplicates()
		{
			UUID target = UUID.randomUUID();
			assertTrue(segment.addFailover(target));
			assertFalse(segment.addFailover(target));
			assertEquals(1, segment.getFailover().size());
		}

		@Test
		@DisplayName("a null target is rejected")
		void nullRejected()
		{
			assertFalse(segment.addFailover(null));
		}

		@Test
		@DisplayName("removing reports whether anything changed")
		void removeReportsChange()
		{
			UUID target = UUID.randomUUID();
			segment.addFailover(target);
			assertTrue(segment.removeFailover(target));
			assertFalse(segment.removeFailover(target));
			assertTrue(segment.getFailover().isEmpty());
		}

		@Test
		@DisplayName("moving reorders the priority chain")
		void moveReorders()
		{
			UUID a = UUID.randomUUID(), b = UUID.randomUUID();
			segment.addFailover(a);
			segment.addFailover(b);
			assertTrue(segment.moveFailover(b, true));
			assertEquals(java.util.Arrays.asList(b, a), segment.getFailover());
			assertTrue(segment.moveFailover(b, false));
			assertEquals(java.util.Arrays.asList(a, b), segment.getFailover());
		}

		@Test
		@DisplayName("moving past either end is refused")
		void moveOffEndRefused()
		{
			UUID a = UUID.randomUUID(), b = UUID.randomUUID();
			segment.addFailover(a);
			segment.addFailover(b);
			assertFalse(segment.moveFailover(a, true));
			assertFalse(segment.moveFailover(b, false));
			assertEquals(java.util.Arrays.asList(a, b), segment.getFailover());
		}

		@Test
		@DisplayName("moving an unknown target is refused")
		void moveUnknownRefused()
		{
			assertFalse(segment.moveFailover(UUID.randomUUID(), true));
		}

		@Test
		@DisplayName("the returned list cannot be modified from outside")
		void listIsUnmodifiable()
		{
			segment.addFailover(UUID.randomUUID());
			assertThrows(UnsupportedOperationException.class,
					() -> segment.getFailover().add(UUID.randomUUID()));
		}

		@Test
		@DisplayName("clearing drops every link")
		void clearDropsAll()
		{
			segment.addFailover(UUID.randomUUID());
			segment.addFailover(UUID.randomUUID());
			segment.clearFailover();
			assertTrue(segment.getFailover().isEmpty());
		}
	}

	@Nested
	@DisplayName("membership and ordering")
	class Membership
	{
		@Test
		@DisplayName("device counts are reported per type")
		void countsPerType()
		{
			feed(grid, segment, 100, 100);
			feed(grid, segment, 100, 100);
			service(grid, segment, 100, 100);
			assertEquals(3, segment.getDeviceCount());
			assertEquals(2, segment.getDeviceCount(GridDeviceType.FEED));
			assertEquals(1, segment.getDeviceCount(GridDeviceType.SERVICE));
			assertEquals(0, segment.getDeviceCount(GridDeviceType.CONSOLE));
		}

		@Test
		@DisplayName("online count excludes detached devices")
		void onlineCountExcludesDetached()
		{
			GridDevice a = feed(grid, segment, 100, 100);
			feed(grid, segment, 100, 100);
			assertEquals(2, segment.getOnlineDeviceCount());
			a.setEndpoint(null);
			assertEquals(1, segment.getOnlineDeviceCount());
		}

		@Test
		@DisplayName("the device list cannot be modified from outside")
		void deviceListUnmodifiable()
		{
			assertThrows(UnsupportedOperationException.class,
					() -> segment.getDevices().add(null));
		}

		@Test
		@DisplayName("feeds are ordered highest priority first")
		void feedsOrderedByPriority()
		{
			GridDevice low = feed(grid, segment, 100, 100, 1);
			GridDevice high = feed(grid, segment, 100, 100, 10);
			GridDevice mid = feed(grid, segment, 100, 100, 5);
			List<GridDevice> order = segment.getActiveFeeds();
			assertEquals(3, order.size());
			assertSame(high, order.get(0));
			assertSame(mid, order.get(1));
			assertSame(low, order.get(2));
		}

		@Test
		@DisplayName("services put critical loads before everything else")
		void criticalServedFirst()
		{
			GridDevice ordinary = service(grid, segment, 100, 100, 99, false);
			GridDevice critical = service(grid, segment, 100, 100, -99, true);
			List<GridDevice> order = segment.getActiveServices();
			assertSame(critical, order.get(0),
					"a critical load outranks even a much higher ordinary priority");
			assertSame(ordinary, order.get(1));
		}

		@Test
		@DisplayName("within a class, services are ordered by priority")
		void servicesOrderedWithinClass()
		{
			GridDevice lowCrit = service(grid, segment, 100, 100, 1, true);
			GridDevice highCrit = service(grid, segment, 100, 100, 9, true);
			GridDevice lowNorm = service(grid, segment, 100, 100, 1, false);
			GridDevice highNorm = service(grid, segment, 100, 100, 9, false);
			List<GridDevice> order = segment.getActiveServices();
			assertSame(highCrit, order.get(0));
			assertSame(lowCrit, order.get(1));
			assertSame(highNorm, order.get(2));
			assertSame(lowNorm, order.get(3));
		}

		@Test
		@DisplayName("ordering is stable, not hash-dependent")
		void orderingIsStable()
		{
			GridDevice a = grid.registerDevice(pos(5, 64, 0, 0), GridDeviceType.FEED);
			GridDevice b = grid.registerDevice(pos(1, 64, 0, 0), GridDeviceType.FEED);
			grid.assignDevice(a, segment.getId());
			grid.assignDevice(b, segment.getId());
			a.setEndpoint(new FakeEndpoint());
			b.setEndpoint(new FakeEndpoint());
			segment.invalidateViews();
			//Equal priority, so position decides -- and it must decide the same way every run.
			assertSame(b, segment.getActiveFeeds().get(0));
			assertSame(a, segment.getActiveFeeds().get(1));
		}

		@Test
		@DisplayName("offline and disabled devices are left out of the active views")
		void inactiveExcluded()
		{
			GridDevice online = feed(grid, segment, 100, 100);
			GridDevice offline = feed(grid, segment, 100, 100);
			GridDevice disabled = feed(grid, segment, 100, 100);
			offline.setEndpoint(null);
			disabled.setEnabled(false);
			segment.invalidateViews();

			List<GridDevice> feeds = segment.getActiveFeeds();
			assertEquals(1, feeds.size());
			assertSame(online, feeds.get(0));
		}

		@Test
		@DisplayName("the active views refresh after invalidation")
		void viewsRefreshAfterInvalidate()
		{
			assertTrue(segment.getActiveFeeds().isEmpty());
			feed(grid, segment, 100, 100);
			assertEquals(1, segment.getActiveFeeds().size());
		}

		@Test
		@DisplayName("feeds and services never appear in each other's view")
		void viewsDoNotCross()
		{
			feed(grid, segment, 100, 100);
			service(grid, segment, 100, 100);
			assertEquals(1, segment.getActiveFeeds().size());
			assertEquals(1, segment.getActiveServices().size());
			assertSame(GridDeviceType.FEED, segment.getActiveFeeds().get(0).getType());
			assertSame(GridDeviceType.SERVICE, segment.getActiveServices().get(0).getType());
		}
	}

	@Nested
	@DisplayName("tick budgets")
	class Budgets
	{
		@Test
		@DisplayName("budgets start at the policy caps")
		void budgetsStartAtCaps()
		{
			segment.getPolicy().setMaxInput(500);
			segment.getPolicy().setMaxOutput(300);
			segment.beginTick();
			assertEquals(500, segment.getInputBudget());
			assertEquals(300, segment.getOutputBudget());
		}

		@Test
		@DisplayName("recording consumes the budget")
		void recordingConsumesBudget()
		{
			segment.getPolicy().setMaxInput(500);
			segment.beginTick();
			segment.recordIn(200);
			assertEquals(300, segment.getInputBudget());
			assertEquals(200, segment.getTickIn());
		}

		@Test
		@DisplayName("a budget never goes negative")
		void budgetNeverNegative()
		{
			segment.getPolicy().setMaxOutput(100);
			segment.beginTick();
			segment.recordOut(500);
			assertEquals(0, segment.getOutputBudget());
		}

		@Test
		@DisplayName("beginTick resets the budgets")
		void beginTickResetsBudgets()
		{
			segment.getPolicy().setMaxInput(500);
			segment.beginTick();
			segment.recordIn(400);
			segment.endTick();
			segment.beginTick();
			assertEquals(500, segment.getInputBudget());
			assertEquals(0, segment.getTickIn());
		}

		@Test
		@DisplayName("recording feeds the statistics")
		void recordingFeedsStats()
		{
			segment.beginTick();
			segment.recordIn(7);
			segment.recordOut(3);
			segment.endTick();
			assertEquals(7, segment.getStats().getLifetimeIn());
			assertEquals(3, segment.getStats().getLifetimeOut());
		}
	}

	@Nested
	@DisplayName("breaker")
	class Breaker
	{
		@BeforeEach
		void enableBreakers()
		{
			GridConfig.breakersEnabled = true;
			GridConfig.breakerTripSeconds = 1;
			segment.getPolicy().setMaxOutput(100);
		}

		@Test
		@DisplayName("nothing trips while breakers are disabled")
		void disabledNeverTrips()
		{
			GridConfig.breakersEnabled = false;
			for(int i = 0; i < 100; i++)
			{
				segment.beginTick();
				segment.recordOut(100);
				assertFalse(segment.updateBreaker());
			}
			assertFalse(segment.isTripped());
		}

		@Test
		@DisplayName("sustained saturation trips the breaker")
		void saturationTrips()
		{
			boolean tripped = false;
			for(int i = 0; i < 20&&!tripped; i++)
			{
				segment.beginTick();
				segment.recordOut(100);
				tripped = segment.updateBreaker();
			}
			assertTrue(tripped, "20 saturated ticks at 1 second should trip");
			assertTrue(segment.isTripped());
			assertFalse(segment.isOperational());
		}

		@Test
		@DisplayName("output below the ceiling never trips")
		void belowCeilingNeverTrips()
		{
			for(int i = 0; i < 200; i++)
			{
				segment.beginTick();
				segment.recordOut(99);
				assertFalse(segment.updateBreaker());
			}
			assertFalse(segment.isTripped());
		}

		@Test
		@DisplayName("a single unsaturated tick resets the counter")
		void oneQuietTickResets()
		{
			for(int i = 0; i < 19; i++)
			{
				segment.beginTick();
				segment.recordOut(100);
				segment.updateBreaker();
			}
			segment.beginTick();
			segment.recordOut(0);
			segment.updateBreaker();
			assertEquals(0, segment.getSaturatedTicks());
			assertFalse(segment.isTripped());
		}

		@Test
		@DisplayName("an already tripped segment does not re-trip")
		void trippedDoesNotRetrip()
		{
			segment.setTripped(true);
			segment.beginTick();
			segment.recordOut(100);
			assertFalse(segment.updateBreaker());
		}

		@Test
		@DisplayName("a switched-off segment does not trip")
		void offDoesNotTrip()
		{
			segment.setEnabled(false);
			for(int i = 0; i < 100; i++)
			{
				segment.beginTick();
				segment.recordOut(100);
				assertFalse(segment.updateBreaker());
			}
		}

		@Test
		@DisplayName("a zero-output segment cannot trip")
		void zeroOutputCannotTrip()
		{
			segment.getPolicy().setMaxOutput(0);
			for(int i = 0; i < 100; i++)
			{
				segment.beginTick();
				assertFalse(segment.updateBreaker());
			}
		}

		@Test
		@DisplayName("clearing the trip resets the saturation counter")
		void clearingTripResetsCounter()
		{
			for(int i = 0; i < 10; i++)
			{
				segment.beginTick();
				segment.recordOut(100);
				segment.updateBreaker();
			}
			assertTrue(segment.getSaturatedTicks() > 0);
			segment.setTripped(false);
			assertEquals(0, segment.getSaturatedTicks());
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
			UUID owner = UUID.randomUUID();
			UUID backup = UUID.randomUUID();
			segment.setName("Docks");
			segment.setColor(0x123456);
			segment.setEnabled(false);
			segment.setOwner(owner);
			segment.setLocked(true);
			segment.setTripped(true);
			segment.getPolicy().setMaxOutput(4096);
			segment.addToBuffer(777);
			segment.addFailover(backup);
			segment.beginTick();
			segment.recordIn(50);
			segment.endTick();

			GridSegment loaded = GridSegment.readFromNBT(segment.writeToNBT(new NBTTagCompound()));

			assertNotNull(loaded);
			assertEquals(segment.getId(), loaded.getId());
			assertEquals("Docks", loaded.getName());
			assertEquals(0x123456, loaded.getColor());
			assertFalse(loaded.isEnabled());
			assertEquals(owner, loaded.getOwner());
			assertTrue(loaded.isLocked());
			assertTrue(loaded.isTripped());
			assertEquals(4096, loaded.getPolicy().getMaxOutput());
			assertEquals(777, loaded.getBuffer());
			assertEquals(java.util.Collections.singletonList(backup), loaded.getFailover());
			assertEquals(50, loaded.getStats().getLifetimeIn());
		}

		@Test
		@DisplayName("devices are owned by the grid, not written into the segment tag")
		void devicesNotWrittenIntoSegment()
		{
			feed(grid, segment, 100, 100);
			GridSegment loaded = GridSegment.readFromNBT(segment.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(0, loaded.getDeviceCount(),
					"VirtualGrid re-attaches devices, so a device can never land in two segments");
		}

		@Test
		@DisplayName("null NBT yields null")
		void nullNbtYieldsNull()
		{
			assertNull(GridSegment.readFromNBT(null));
		}

		@Test
		@DisplayName("a tag with no id is rejected")
		void missingIdRejected()
		{
			assertNull(GridSegment.readFromNBT(new NBTTagCompound()));
		}

		@Test
		@DisplayName("a segment from before the enabled flag loads switched on")
		void missingEnabledDefaultsToOn()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setString("id", UUID.randomUUID().toString());
			GridSegment loaded = GridSegment.readFromNBT(nbt);
			assertNotNull(loaded);
			assertTrue(loaded.isEnabled());
		}

		@Test
		@DisplayName("a self-referencing failover link in a save file is dropped")
		void selfLinkInSaveDropped()
		{
			UUID id = UUID.randomUUID();
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setString("id", id.toString());
			net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
			list.appendTag(new net.minecraft.nbt.NBTTagString(id.toString()));
			nbt.setTag("failover", list);

			GridSegment loaded = GridSegment.readFromNBT(nbt);
			assertNotNull(loaded);
			assertTrue(loaded.getFailover().isEmpty());
		}

		@Test
		@DisplayName("a malformed failover entry is skipped, not fatal")
		void malformedFailoverSkipped()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setString("id", UUID.randomUUID().toString());
			net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
			list.appendTag(new net.minecraft.nbt.NBTTagString("garbage"));
			UUID good = UUID.randomUUID();
			list.appendTag(new net.minecraft.nbt.NBTTagString(good.toString()));
			nbt.setTag("failover", list);

			GridSegment loaded = GridSegment.readFromNBT(nbt);
			assertNotNull(loaded);
			assertEquals(java.util.Collections.singletonList(good), loaded.getFailover());
		}
	}
}
