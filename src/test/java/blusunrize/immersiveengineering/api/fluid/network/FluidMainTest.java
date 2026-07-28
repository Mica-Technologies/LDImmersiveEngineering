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

import java.util.UUID;

import static blusunrize.immersiveengineering.api.fluid.network.FluidNetTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FluidMain}: the unit of management, and the one class in this feature that carries a rule
 * the virtual grid has no counterpart for.
 * <p>
 * The mirror of {@code GridSegmentTest}, plus everything about a main carrying exactly one fluid.
 */
class FluidMainTest
{
	private VirtualFluidNet net;

	@BeforeEach
	void setUp()
	{
		resetConfig();
		net = new VirtualFluidNet();
	}

	private FluidMain plain()
	{
		return new FluidMain(UUID.randomUUID(), "test");
	}

	@Nested
	@DisplayName("what it carries")
	class Typing
	{
		@Test
		@DisplayName("a new main carries nothing")
		void startsUntyped()
		{
			FluidMain main = plain();
			assertNull(main.getFluid());
			assertFalse(main.isTyped());
		}

		@Test
		@DisplayName("typeFrom sets an untyped main and refuses a typed one")
		void typeFromLatches()
		{
			FluidMain main = plain();
			assertTrue(main.typeFrom(DIESEL));
			assertEquals(DIESEL, main.getFluid());
			//The latch is the guard that stops a wrongly-stocked inlet re-typing a live main.
			assertFalse(main.typeFrom(WATER));
			assertEquals(DIESEL, main.getFluid());
		}

		@Test
		@DisplayName("typeFrom ignores nothing and empty")
		void typeFromIgnoresNonsense()
		{
			FluidMain main = plain();
			assertFalse(main.typeFrom(null));
			assertFalse(main.typeFrom(""));
			assertNull(main.getFluid(), "an empty name must not count as a type");
		}

		@Test
		@DisplayName("setFluid is refused while the pack holds anything")
		void retypingNeedsAnEmptyPack()
		{
			FluidMain main = plain();
			main.typeFrom(DIESEL);
			main.addToPack(1);
			assertFalse(main.setFluid(WATER),
					"re-typing a main with fluid in it would destroy it or deliver the wrong thing");
			assertEquals(DIESEL, main.getFluid());
		}

		@Test
		@DisplayName("setFluid works once the pack is empty, and can clear the type")
		void retypingAnEmptyMain()
		{
			FluidMain main = plain();
			main.typeFrom(DIESEL);
			assertTrue(main.setFluid(WATER));
			assertEquals(WATER, main.getFluid());
			assertTrue(main.setFluid(null));
			assertNull(main.getFluid());
			assertTrue(main.setFluid(""), "an empty name should clear rather than be stored");
			assertNull(main.getFluid());
		}

		@Test
		@DisplayName("setting the fluid it already carries is a no-op that succeeds")
		void settingTheSameFluidSucceeds()
		{
			//The console pushes the field's contents on every Apply, so "no change" must not read
			//as a refusal and light a warning at the player.
			FluidMain main = plain();
			main.typeFrom(DIESEL);
			main.addToPack(500);
			assertTrue(main.setFluid(DIESEL),
					"re-asserting the current fluid must be allowed even with a full pack");
		}
	}

	@Nested
	@DisplayName("line pack")
	class Pack
	{
		@Test
		@DisplayName("the pack never exceeds its cap")
		void packIsCapped()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(100);
			assertEquals(100, main.addToPack(250), "addToPack should report only what it stored");
			assertEquals(100, main.getPack());
		}

		@Test
		@DisplayName("drawing takes no more than is there")
		void drawIsBounded()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			main.addToPack(400);
			assertEquals(400, main.drawFromPack(900));
			assertEquals(0, main.getPack());
		}

		@Test
		@DisplayName("non-positive amounts move nothing")
		void nonPositiveIsIgnored()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			assertEquals(0, main.addToPack(0));
			assertEquals(0, main.addToPack(-50));
			assertEquals(0, main.drawFromPack(-50));
		}

		@Test
		@DisplayName("lowering the cap pulls the stored amount down with it")
		void loweringTheCapClamps()
		{
			//Otherwise a main would sit permanently over its own ceiling with no way to notice.
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			main.addToPack(1000);
			main.getPolicy().setPackCap(200);
			main.setPack(main.getPack());
			assertEquals(200, main.getPack());
		}
	}

	@Nested
	@DisplayName("open, closed and tripped")
	class Operational
	{
		@Test
		@DisplayName("a new main is open")
		void startsOpen()
		{
			assertTrue(plain().isOperational());
		}

		@Test
		@DisplayName("each of the four ways to be closed actually closes it")
		void everyGateCloses()
		{
			FluidMain main = plain();
			main.setEnabled(false);
			assertFalse(main.isOperational(), "the switch");
			main.setEnabled(true);

			main.setTripped(true);
			assertFalse(main.isOperational(), "the overpressure cut-out");
			main.setTripped(false);

			main.setForcedClosed(true);
			assertFalse(main.isOperational(), "a valve holding it shut");
			main.setForcedClosed(false);

			main.getPolicy().setScheduleEnabled(true);
			main.getPolicy().setScheduleOn(1000);
			main.getPolicy().setScheduleOff(2000);
			main.updateSchedule(5000);
			assertFalse(main.isOperational(), "the schedule");
		}

		@Test
		@DisplayName("opening a main is also the trip reset")
		void openingResetsTheTrip()
		{
			//One control, as on a real valve station, rather than a separate hidden unlatch.
			FluidMain main = plain();
			main.setTripped(true);
			main.setEnabled(true);
			assertFalse(main.isTripped());
			assertTrue(main.isOperational());
		}

		@Test
		@DisplayName("the schedule can only hold a main closed, never open one")
		void scheduleIsAGate()
		{
			//Otherwise the console's switch and the clock would fight every dusk and whichever ran
			//last would win.
			FluidMain main = plain();
			main.setEnabled(false);
			main.getPolicy().setScheduleEnabled(true);
			main.getPolicy().setScheduleOn(0);
			main.getPolicy().setScheduleOff(12000);
			main.updateSchedule(6000);
			assertFalse(main.isScheduleSuppressed(), "the clock says open");
			assertFalse(main.isOperational(), "but the player closed it, and that wins");
		}
	}

	@Nested
	@DisplayName("the up lamp")
	class UpState
	{
		@Test
		@DisplayName("normal mode: up means something moved or is in the pack")
		void upNeedsFluid()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			assertFalse(main.isUp(false), "an open main with nothing in it is not up");
			main.addToPack(10);
			assertTrue(main.isUp(false));
		}

		@Test
		@DisplayName("normal mode: a closed main is never up, however full")
		void closedIsNeverUp()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			main.addToPack(500);
			main.setEnabled(false);
			assertFalse(main.isUp(false));
		}

		@Test
		@DisplayName("city mode: up is exactly pressurised")
		void cityModeUsesPressure()
		{
			FluidMain main = plain();
			main.getPolicy().setPackCap(1000);
			main.addToPack(500);
			assertFalse(main.isUp(true), "pack means nothing in city mode");
			main.setPressurised(true);
			assertTrue(main.isUp(true));
		}
	}

	@Nested
	@DisplayName("failover links")
	class Failover
	{
		@Test
		@DisplayName("a main cannot back itself up, or list the same backup twice")
		void selfLinksAndDuplicatesAreRefused()
		{
			FluidMain main = plain();
			UUID other = UUID.randomUUID();
			assertFalse(main.addFailover(main.getId()));
			assertTrue(main.addFailover(other));
			assertFalse(main.addFailover(other));
			assertEquals(1, main.getFailover().size());
		}

		@Test
		@DisplayName("order is preserved and movable")
		void orderingWorks()
		{
			FluidMain main = plain();
			UUID a = UUID.randomUUID(), b = UUID.randomUUID();
			main.addFailover(a);
			main.addFailover(b);
			assertEquals(a, main.getFailover().get(0));
			assertTrue(main.moveFailover(b, true));
			assertEquals(b, main.getFailover().get(0));
			assertFalse(main.moveFailover(b, true), "already at the top");
			assertFalse(main.moveFailover(UUID.randomUUID(), true), "not in the list");
		}

		@Test
		@DisplayName("the list handed out cannot be edited behind the main's back")
		void listIsUnmodifiable()
		{
			FluidMain main = plain();
			main.addFailover(UUID.randomUUID());
			assertThrows(UnsupportedOperationException.class, () -> main.getFailover().clear());
		}
	}

	@Nested
	@DisplayName("budgets and the cut-out")
	class Budgets
	{
		@Test
		@DisplayName("budgets shrink as the tick is spent and reset on the next one")
		void budgetsArePerTick()
		{
			FluidMain main = plain();
			main.getPolicy().setMaxInput(100);
			main.getPolicy().setMaxOutput(80);
			main.beginTick();
			assertEquals(100, main.getInputBudget());
			main.recordIn(40);
			assertEquals(60, main.getInputBudget());
			main.recordOut(80);
			assertEquals(0, main.getOutputBudget());
			main.beginTick();
			assertEquals(100, main.getInputBudget(), "a new tick is a new budget");
		}

		@Test
		@DisplayName("a budget never reads negative")
		void budgetsDoNotGoNegative()
		{
			FluidMain main = plain();
			main.getPolicy().setMaxOutput(50);
			main.beginTick();
			main.recordOut(500);
			assertEquals(0, main.getOutputBudget());
		}

		@Test
		@DisplayName("the cut-out only latches after sustained saturation, and only when enabled")
		void tripNeedsSustainedSaturation()
		{
			FluidNetConfig.tripsEnabled = true;
			FluidNetConfig.tripSeconds = 1;
			FluidMain main = plain();
			main.getPolicy().setMaxOutput(10);

			for(int tick = 0; tick < 19; tick++)
			{
				main.beginTick();
				main.recordOut(10);
				assertFalse(main.updateTrip(), "tripped after only "+(tick+1)+" ticks");
			}
			main.beginTick();
			main.recordOut(10);
			assertTrue(main.updateTrip(), "twenty saturated ticks is one second");
			assertTrue(main.isTripped());
		}

		@Test
		@DisplayName("a main that is not saturating resets the counter")
		void slackResetsTheCounter()
		{
			FluidNetConfig.tripsEnabled = true;
			FluidNetConfig.tripSeconds = 1;
			FluidMain main = plain();
			main.getPolicy().setMaxOutput(10);
			for(int tick = 0; tick < 15; tick++)
			{
				main.beginTick();
				main.recordOut(10);
				main.updateTrip();
			}
			main.beginTick();
			main.recordOut(1);
			main.updateTrip();
			assertEquals(0, main.getSaturatedTicks());
		}

		@Test
		@DisplayName("cut-outs off means never tripping")
		void tripsCanBeSwitchedOff()
		{
			FluidNetConfig.tripsEnabled = false;
			FluidMain main = plain();
			main.getPolicy().setMaxOutput(10);
			for(int tick = 0; tick < 100; tick++)
			{
				main.beginTick();
				main.recordOut(10);
				assertFalse(main.updateTrip());
			}
		}
	}

	@Nested
	@DisplayName("ownership")
	class Ownership
	{
		@Test
		@DisplayName("an unlocked main is editable by anyone")
		void unlockedIsOpen()
		{
			FluidMain main = plain();
			assertTrue(main.canEdit(UUID.randomUUID()));
			assertTrue(main.canEdit(null));
		}

		@Test
		@DisplayName("a locked main is editable only by its owner")
		void lockedIsOwnerOnly()
		{
			FluidMain main = plain();
			UUID owner = UUID.randomUUID();
			main.setOwner(owner);
			main.setLocked(true);
			assertTrue(main.canEdit(owner));
			assertFalse(main.canEdit(UUID.randomUUID()));
			assertFalse(main.canEdit(null));
		}

		@Test
		@DisplayName("locking with no owner locks nobody out")
		void lockedWithoutOwnerIsOpen()
		{
			//Otherwise a stray lock would make a main permanently uneditable by everyone.
			FluidMain main = plain();
			main.setLocked(true);
			assertTrue(main.canEdit(UUID.randomUUID()));
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a main survives a save and reload")
		void roundTrips()
		{
			FluidMain main = plain();
			main.setName("town gas");
			main.setColor(0x123456);
			main.setEnabled(false);
			main.setLocked(true);
			main.setOwner(UUID.randomUUID());
			main.typeFrom(GAS);
			main.getPolicy().setMaxInput(555);
			main.getPolicy().setPackCap(777);
			main.addToPack(300);
			UUID backup = UUID.randomUUID();
			main.addFailover(backup);

			FluidMain loaded = FluidMain.readFromNBT(main.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(main.getId(), loaded.getId());
			assertEquals("town gas", loaded.getName());
			assertEquals(0x123456, loaded.getColor());
			assertFalse(loaded.isEnabled());
			assertTrue(loaded.isLocked());
			assertEquals(main.getOwner(), loaded.getOwner());
			assertEquals(GAS, loaded.getFluid());
			assertEquals(555, loaded.getPolicy().getMaxInput());
			assertEquals(777, loaded.getPolicy().getPackCap());
			assertEquals(300, loaded.getPack());
			assertEquals(1, loaded.getFailover().size());
			assertEquals(backup, loaded.getFailover().get(0));
		}

		@Test
		@DisplayName("the fluid survives even though setFluid would have refused it")
		void loadingBypassesTheRetypeGuard()
		{
			//The pack is read back before the fluid, so going through setFluid would silently drop
			//the type of every main that had anything in it -- and the whole network would come
			//back untyped after a restart.
			FluidMain main = plain();
			main.typeFrom(DIESEL);
			main.getPolicy().setPackCap(1000);
			main.addToPack(900);

			FluidMain loaded = FluidMain.readFromNBT(main.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(DIESEL, loaded.getFluid());
			assertEquals(900, loaded.getPack());
		}

		@Test
		@DisplayName("save data leaves out what the engine recomputes; a GUI sync keeps it")
		void liveStateIsOptional()
		{
			FluidMain main = plain();
			main.setPressurised(true);
			main.setSourceLive(true);
			main.setForcedClosed(true);

			FluidMain saved = FluidMain.readFromNBT(main.writeToNBT(new NBTTagCompound(), false));
			assertNotNull(saved);
			assertFalse(saved.isPressurised(), "derived state must not be persisted");
			assertFalse(saved.isForcedClosed(),
					"an external shut-off that is itself gone must not survive a restart");

			FluidMain synced = FluidMain.readFromNBT(main.writeToNBT(new NBTTagCompound(), true));
			assertNotNull(synced);
			assertTrue(synced.isPressurised(), "the console draws almost entirely from this");
			assertTrue(synced.isSourceLive());
		}

		@Test
		@DisplayName("a corrupt record is skipped rather than crashing world load")
		void malformedRecordsAreSkipped()
		{
			assertNull(FluidMain.readFromNBT(null));
			assertNull(FluidMain.readFromNBT(new NBTTagCompound()), "no id");
			NBTTagCompound bad = new NBTTagCompound();
			bad.setString("id", "not-a-uuid");
			assertNull(FluidMain.readFromNBT(bad));
		}
	}

	@Nested
	@DisplayName("membership views")
	class Views
	{
		@Test
		@DisplayName("only active devices appear, split by type")
		void viewsSplitByType()
		{
			FluidMain main = main(net, "main", DIESEL);
			inlet(net, main, DIESEL, 100, 100);
			outlet(net, main, DIESEL, 100, 100);
			valve(net, main, true, false, false);

			assertEquals(1, main.getActiveInlets().size());
			assertEquals(1, main.getActiveOutlets().size());
			assertEquals(1, main.getActiveValves().size());
			assertEquals(3, main.getDeviceCount());
		}

		@Test
		@DisplayName("a disabled device drops out of the active views")
		void disabledDevicesAreInactive()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice device = inlet(net, main, DIESEL, 100, 100);
			assertEquals(1, main.getActiveInlets().size());
			device.setEnabled(false);
			main.invalidateViews();
			assertEquals(0, main.getActiveInlets().size());
			assertEquals(1, main.getDeviceCount(), "but it is still a member");
		}

		@Test
		@DisplayName("inlets drain highest priority first")
		void inletOrdering()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice low = inlet(net, main, DIESEL, 100, 100, 0);
			FluidDevice high = inlet(net, main, DIESEL, 100, 100, 9);
			assertEquals(high, main.getActiveInlets().get(0));
			assertEquals(low, main.getActiveInlets().get(1));
		}

		@Test
		@DisplayName("critical outlets sort ahead of everything, whatever their priority")
		void criticalOutletsComeFirst()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice ordinary = outlet(net, main, DIESEL, 100, 100, 99, false);
			FluidDevice critical = outlet(net, main, DIESEL, 100, 100, -99, true);
			assertEquals(critical, main.getActiveOutlets().get(0));
			assertEquals(ordinary, main.getActiveOutlets().get(1));
		}

		@Test
		@DisplayName("ordering is stable across rebuilds")
		void orderingIsStable()
		{
			//Position is the tie-break so the order does not depend on hash iteration, which would
			//make the serving order differ between restarts.
			FluidMain main = main(net, "main", DIESEL);
			for(int i = 0; i < 8; i++)
				outlet(net, main, DIESEL, 100, 100);
			String first = main.getActiveOutlets().toString();
			main.invalidateViews();
			assertEquals(first, main.getActiveOutlets().toString());
		}
	}
}
