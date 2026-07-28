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

import static blusunrize.immersiveengineering.api.fluid.network.FluidNetTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VirtualFluidNet}: the registry that owns every main and every fitting.
 * <p>
 * The mirror of {@code VirtualGridTest}. Most of what is checked here is relational integrity --
 * that a fitting is in exactly one main, that deleting a main leaves nothing dangling, and that a
 * reload rebuilds both sides of the relation. Those are the failures that do not announce
 * themselves: the network keeps working and simply moves the wrong fluid to the wrong place.
 */
class VirtualFluidNetTest
{
	private VirtualFluidNet net;

	@BeforeEach
	void setUp()
	{
		resetConfig();
		net = new VirtualFluidNet();
	}

	@Nested
	@DisplayName("mains")
	class Mains
	{
		@Test
		@DisplayName("created mains are found by id and by name")
		void lookups()
		{
			FluidMain main = net.createMain("Town Gas");
			assertEquals(1, net.getMainCount());
			assertSame(main, net.getMain(main.getId()));
			assertSame(main, net.getMainByName("town gas"), "name lookup is case-insensitive");
			assertNull(net.getMainByName("nothing"));
			assertNull(net.getMain(null));
		}

		@Test
		@DisplayName("every main gets a distinct id")
		void idsAreUnique()
		{
			assertNotEquals(net.createMain("a").getId(), net.createMain("b").getId());
		}

		@Test
		@DisplayName("addMain refuses to overwrite an existing id")
		void addMainDoesNotOverwrite()
		{
			FluidMain main = net.createMain("a");
			assertFalse(net.addMain(new FluidMain(main.getId(), "impostor")));
			assertEquals("a", net.getMain(main.getId()).getName());
		}

		@Test
		@DisplayName("deleting a main unlinks its fittings rather than deleting them")
		void deleteUnlinksDevices()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 100);
			assertTrue(net.deleteMain(main.getId()));

			assertNull(net.getMain(main.getId()));
			assertFalse(device.isLinked(), "the fitting should be unlinked");
			assertNotNull(net.getDevice(device.getPos()), "but it should still be registered");
			assertEquals(1, net.getUnlinkedDevices().size());
		}

		@Test
		@DisplayName("deleting a main strips it out of every other main's failover list")
		void deleteClearsFailoverReferences()
		{
			//A dangling reference here would make the failover walk defend against nulls at
			//runtime, on the hot path, forever.
			FluidMain primary = net.createMain("primary");
			FluidMain backup = net.createMain("backup");
			primary.addFailover(backup.getId());
			net.deleteMain(backup.getId());
			assertTrue(primary.getFailover().isEmpty());
		}

		@Test
		@DisplayName("deleting something that is not there is not an error")
		void deletingNothingIsFalse()
		{
			assertFalse(net.deleteMain(UUID.randomUUID()));
		}
	}

	@Nested
	@DisplayName("fittings")
	class Devices
	{
		@Test
		@DisplayName("registering twice at one position returns the same record")
		void registrationIsIdempotent()
		{
			DimensionBlockPos at = pos();
			FluidDevice first = net.registerDevice(at, FluidDeviceType.INLET);
			assertSame(first, net.registerDevice(at, FluidDeviceType.INLET));
			assertEquals(1, net.getDeviceCount());
		}

		@Test
		@DisplayName("a different type at the same position replaces the record")
		void changingTypeReplaces()
		{
			//That means the block was broken and another put in its place, and the old record's
			//settings should not survive onto a block that is not the same machine.
			DimensionBlockPos at = pos();
			FluidDevice inlet = net.registerDevice(at, FluidDeviceType.INLET);
			inlet.setTransferCap(999);
			FluidDevice outlet = net.registerDevice(at, FluidDeviceType.OUTLET);
			assertNotSame(inlet, outlet);
			assertEquals(FluidDeviceType.OUTLET, net.getDevice(at).getType());
			assertEquals(1, net.getDeviceCount());
		}

		@Test
		@DisplayName("a fitting belongs to exactly one main")
		void reassignmentMoves()
		{
			FluidMain a = main(net, "a", DIESEL);
			FluidMain b = main(net, "b", DIESEL);
			FluidDevice device = inlet(net, a, DIESEL, 100, 100);
			assertEquals(1, a.getDeviceCount());

			assertTrue(net.assignDevice(device, b.getId()));
			assertEquals(0, a.getDeviceCount(), "it must leave the old main");
			assertEquals(1, b.getDeviceCount());
			assertEquals(b.getId(), device.getMain());
		}

		@Test
		@DisplayName("assigning to null unlinks")
		void assigningNullUnlinks()
		{
			FluidMain main = main(net, "a", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 100);
			assertTrue(net.assignDevice(device, null));
			assertFalse(device.isLinked());
			assertEquals(0, main.getDeviceCount());
		}

		@Test
		@DisplayName("assigning to a main that does not exist is refused")
		void unknownMainIsRefused()
		{
			FluidDevice device = net.registerDevice(pos(), FluidDeviceType.INLET);
			assertFalse(net.assignDevice(device, UUID.randomUUID()));
			assertFalse(device.isLinked());
		}

		@Test
		@DisplayName("unregistering removes the record and its membership")
		void unregisterCleansUp()
		{
			FluidMain main = main(net, "a", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 100);
			assertNotNull(net.unregisterDevice(device.getPos()));
			assertNull(net.getDevice(device.getPos()));
			assertEquals(0, main.getDeviceCount());
			assertNull(net.unregisterDevice(device.getPos()), "twice is not an error");
		}

		@Test
		@DisplayName("with cross-dimension off, a main is pinned to its first fitting's dimension")
		void crossDimensionCanBeRefused()
		{
			FluidNetConfig.crossDimension = false;
			FluidMain main = main(net, "a", DIESEL);
			FluidDevice here = net.registerDevice(pos(0, 64, 0, 0), FluidDeviceType.INLET);
			FluidDevice nether = net.registerDevice(pos(0, 64, 0, -1), FluidDeviceType.INLET);
			assertTrue(net.assignDevice(here, main.getId()));
			assertFalse(net.assignDevice(nether, main.getId()));

			FluidNetConfig.crossDimension = true;
			assertTrue(net.assignDevice(nether, main.getId()), "and allowed again when re-enabled");
		}
	}

	@Nested
	@DisplayName("attach and detach")
	class Lifecycle
	{
		@Test
		@DisplayName("attaching an unknown position registers it -- the network is self-healing")
		void attachRegisters()
		{
			//If the save file is lost, reloading the chunks has to be enough to rebuild the network.
			DimensionBlockPos at = pos();
			FluidDevice device = net.attach(at, FluidDeviceType.INLET, new FakeFluidEndpoint());
			assertNotNull(net.getDevice(at));
			assertTrue(device.isOnline());
		}

		@Test
		@DisplayName("detaching keeps the record and drops only the attachment")
		void detachKeepsTheRecord()
		{
			//A fitting whose chunk unloaded must stay listed and configurable in the console.
			DimensionBlockPos at = pos();
			net.attach(at, FluidDeviceType.INLET, new FakeFluidEndpoint());
			net.detach(at);
			assertNotNull(net.getDevice(at));
			assertFalse(net.getDevice(at).isOnline());
		}

		@Test
		@DisplayName("detachAll drops every attachment and keeps every record")
		void detachAllKeepsRecords()
		{
			FluidMain main = main(net, "a", DIESEL);
			inlet(net, main, DIESEL, 100, 100);
			outlet(net, main, DIESEL, 100, 100);
			net.detachAll();
			assertEquals(2, net.getDeviceCount());
			for(FluidDevice device : net.getDevices())
				assertFalse(device.isOnline());
		}

		@Test
		@DisplayName("clear wipes everything, so a second world cannot inherit the first")
		void clearWipes()
		{
			main(net, "a", DIESEL);
			net.registerDevice(pos(), FluidDeviceType.INLET);
			net.clear();
			assertEquals(0, net.getMainCount());
			assertEquals(0, net.getDeviceCount());
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a whole network survives a save and reload with its relations intact")
		void roundTrip()
		{
			FluidMain main = main(net, "town gas", GAS);
			FluidDevice in = inlet(net, main, GAS, 100, 250);
			net.registerDevice(pos(), FluidDeviceType.OUTLET);

			NBTTagCompound nbt = net.writeToNBT(new NBTTagCompound());
			VirtualFluidNet loaded = new VirtualFluidNet();
			loaded.readFromNBT(nbt);

			assertEquals(1, loaded.getMainCount());
			assertEquals(2, loaded.getDeviceCount());
			FluidMain loadedMain = loaded.getMainByName("town gas");
			assertNotNull(loadedMain);
			assertEquals(GAS, loadedMain.getFluid());
			assertEquals(1, loadedMain.getDeviceCount(), "membership must be rebuilt, not just the id");
			assertEquals(250, loaded.getDevice(in.getPos()).getTransferCap());
			assertEquals(1, loaded.getUnlinkedDevices().size());
		}

		@Test
		@DisplayName("reading replaces rather than merges")
		void readReplaces()
		{
			net.createMain("stale");
			VirtualFluidNet other = new VirtualFluidNet();
			other.createMain("fresh");
			net.readFromNBT(other.writeToNBT(new NBTTagCompound()));
			assertEquals(1, net.getMainCount());
			assertNotNull(net.getMainByName("fresh"));
			assertNull(net.getMainByName("stale"));
		}

		@Test
		@DisplayName("a fitting whose main vanished comes back unlinked, not dangling")
		void orphanedDevicesAreUnlinked()
		{
			FluidMain main = main(net, "gone", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 100);
			NBTTagCompound nbt = net.writeToNBT(new NBTTagCompound());
			//Strip the mains, keeping the devices -- what a hand-edited or half-corrupt save looks
			//like.
			nbt.setTag("mains", new net.minecraft.nbt.NBTTagList());

			VirtualFluidNet loaded = new VirtualFluidNet();
			loaded.readFromNBT(nbt);
			assertEquals(1, loaded.getDeviceCount());
			assertFalse(loaded.getDevice(device.getPos()).isLinked(),
					"better an unlinked fitting the player can re-assign than a reference into nothing");
		}

		@Test
		@DisplayName("failover links to mains that no longer exist are dropped on load")
		void danglingFailoverIsDropped()
		{
			FluidMain primary = net.createMain("primary");
			primary.addFailover(UUID.randomUUID());
			VirtualFluidNet loaded = new VirtualFluidNet();
			loaded.readFromNBT(net.writeToNBT(new NBTTagCompound()));
			assertTrue(loaded.getMainByName("primary").getFailover().isEmpty(),
					"so the traversal never has to defend against them at runtime");
		}

		@Test
		@DisplayName("reading nothing leaves an empty network rather than throwing")
		void readingNullIsSafe()
		{
			net.createMain("a");
			net.readFromNBT(null);
			assertEquals(0, net.getMainCount());
		}
	}

	@Nested
	@DisplayName("config changes")
	class ConfigChanges
	{
		@Test
		@DisplayName("lowering a ceiling pulls existing mains and fittings back inside it")
		void configClampsExisting()
		{
			FluidMain main = main(net, "a", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 900000);
			main.addToPack(90000);

			FluidNetConfig.maxMainIO = 500;
			FluidNetConfig.packCapMax = 400;
			net.onConfigChanged();

			assertEquals(500, main.getPolicy().getMaxInput());
			assertEquals(400, main.getPolicy().getPackCap());
			assertTrue(main.getPack() <= 400, "the stored amount must come down with the cap");
			assertEquals(500, device.getTransferCap());
		}
	}

	@Nested
	@DisplayName("aggregates")
	class Aggregates
	{
		@Test
		@DisplayName("totals add up across mains")
		void totalsSum()
		{
			FluidMain a = main(net, "a", DIESEL);
			FluidMain b = main(net, "b", DIESEL);
			a.beginTick();
			b.beginTick();
			a.recordIn(10);
			a.recordOut(4);
			b.recordOut(6);
			//The totals read the previous tick's numbers, so they only appear after the roll.
			a.beginTick();
			b.beginTick();
			assertEquals(10, net.getTotalIn());
			assertEquals(10, net.getTotalOut());
		}

		@Test
		@DisplayName("the pressurised count is what the console lamp adds up")
		void pressurisedCount()
		{
			FluidMain a = main(net, "a", DIESEL);
			main(net, "b", DIESEL);
			a.setPressurised(true);
			assertEquals(1, net.getPressurisedMainCount());
		}
	}
}
