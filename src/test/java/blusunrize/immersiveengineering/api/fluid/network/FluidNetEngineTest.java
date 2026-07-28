/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static blusunrize.immersiveengineering.api.fluid.network.FluidNetTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid network's tick engine.
 * <p>
 * The engine is the one part of this feature that can be wrong quietly: a mis-ordered phase or a
 * missing clamp does not crash, it just moves the wrong amount of the wrong thing to the wrong
 * machine, and a player debugging that has no way in. It is also deliberately world-free, so
 * there is no excuse for not testing it directly.
 * <p>
 * The classes below split into what the engine shares with {@code GridEngine} -- phases, caps,
 * leakage, priorities, shedding, failover, city mode -- and what is genuinely new here, which is
 * everything about a main carrying exactly one fluid.
 */
class FluidNetEngineTest
{
	private VirtualFluidNet net;

	@BeforeEach
	void setUp()
	{
		resetConfig();
		net = new VirtualFluidNet();
	}

	private void tick()
	{
		FluidNetEngine.tick(net, 0, false);
	}

	@Nested
	@DisplayName("the fluid a main carries")
	class Typing
	{
		@Test
		@DisplayName("an untyped main takes its fluid from the first inlet with something to offer")
		void typesItselfFromItsInlets()
		{
			FluidMain main = main(net, "town gas");
			assertNull(main.getFluid(), "a new main carries nothing");
			inlet(net, main, GAS, 500, 1000);
			outlet(net, main, GAS, 500, 1000);

			tick();

			assertEquals(GAS, main.getFluid());
		}

		@Test
		@DisplayName("a typed main ignores an inlet holding something else")
		void wrongFluidIsNotDrawn()
		{
			FluidMain main = main(net, "diesel main", DIESEL);
			FluidDevice good = inlet(net, main, DIESEL, 300, 1000);
			FluidDevice bad = inlet(net, main, WATER, 900, 1000);
			outlet(net, main, DIESEL, 10000, 1000);

			tick();

			//The water inlet is asked -- the engine does not know what it holds until it answers --
			//but it hands over nothing, and its contents are untouched.
			assertEquals(300, endpointOf(good).totalExtracted, "diesel should have moved");
			assertEquals(0, endpointOf(bad).totalExtracted, "water must not enter a diesel main");
			assertEquals(900, endpointOf(bad).available, "the water inlet should be untouched");
		}

		@Test
		@DisplayName("a wrongly-stocked inlet cannot re-type a live main")
		void aLiveMainKeepsItsType()
		{
			//The failure this guards against: a player plumbs a water inlet onto the gas main by
			//mistake, the gas runs out for a tick, and the whole city quietly switches to water.
			FluidMain main = main(net, "town gas");
			FluidDevice gas = inlet(net, main, GAS, 100, 1000);
			outlet(net, main, GAS, 10000, 1000);
			tick();
			assertEquals(GAS, main.getFluid());

			endpointOf(gas).available = 0;
			inlet(net, main, WATER, 5000, 1000);
			tick();
			tick();

			assertEquals(GAS, main.getFluid(), "an empty gas main is still a gas main");
		}

		@Test
		@DisplayName("the engine offers the main's fluid, not the endpoint's")
		void transfersNameTheMainsFluid()
		{
			FluidMain main = main(net, "diesel main", DIESEL);
			inlet(net, main, DIESEL, 500, 1000);
			FluidDevice out = outlet(net, main, DIESEL, 500, 1000);

			tick();

			assertEquals(DIESEL, endpointOf(out).lastOffered);
		}

		@Test
		@DisplayName("an untyped main with nothing on offer does nothing at all")
		void untypedAndEmptyIsQuiet()
		{
			FluidMain main = main(net, "not built yet");
			FluidDevice in = inlet(net, main, GAS, 0, 1000);
			FluidDevice out = outlet(net, main, GAS, 1000, 1000);

			tick();

			assertNull(main.getFluid());
			assertEquals(0, main.getPack());
			assertEquals(0, endpointOf(out).insertCalls,
					"an untyped main must not offer an outlet anything");
			assertEquals(0, endpointOf(in).totalExtracted);
		}

		@Test
		@DisplayName("the fluid can be changed by hand only while the pack is empty")
		void retypingNeedsAnEmptyMain()
		{
			FluidMain main = main(net, "diesel main", DIESEL);
			inlet(net, main, DIESEL, 500, 1000);
			tick();
			assertTrue(main.getPack() > 0, "the pack should be holding the tick's intake");

			assertFalse(main.setFluid(WATER), "re-typing a main with fluid in it must be refused");
			assertEquals(DIESEL, main.getFluid());

			main.setPack(0);
			assertTrue(main.setFluid(WATER));
			assertEquals(WATER, main.getFluid());
		}
	}

	@Nested
	@DisplayName("the normal-mode pass")
	class NormalMode
	{
		@Test
		@DisplayName("fluid collected this tick is delivered in the same tick")
		void collectThenServe()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice in = inlet(net, main, DIESEL, 400, 1000);
			FluidDevice out = outlet(net, main, DIESEL, 400, 1000);

			tick();

			assertEquals(400, endpointOf(in).totalExtracted);
			assertEquals(400, endpointOf(out).totalInserted);
			assertEquals(0, main.getPack(), "the pack is smoothing, not storage");
		}

		@Test
		@DisplayName("a device never moves more than its own transfer cap")
		void perDeviceCapHolds()
		{
			FluidMain main = main(net, "main", DIESEL);
			inlet(net, main, DIESEL, 10000, 250);
			FluidDevice out = outlet(net, main, DIESEL, 10000, 100);

			tick();

			assertEquals(250, main.getStats().getTickIn());
			assertEquals(100, endpointOf(out).totalInserted);
		}

		@Test
		@DisplayName("the main's own in and out caps hold")
		void mainCapsHold()
		{
			FluidMain main = main(net, "main", DIESEL);
			main.getPolicy().setMaxInput(150);
			main.getPolicy().setMaxOutput(80);
			inlet(net, main, DIESEL, 10000, 10000);
			inlet(net, main, DIESEL, 10000, 10000);
			outlet(net, main, DIESEL, 10000, 10000);

			tick();

			assertEquals(150, main.getStats().getTickIn());
			assertEquals(80, main.getStats().getTickOut());
		}

		@Test
		@DisplayName("leakage is taken off what arrives, not off what is drawn")
		void leakageIsChargedOnIntake()
		{
			FluidMain main = main(net, "leaky", DIESEL);
			main.getPolicy().setLeakPct(0.25);
			FluidDevice in = inlet(net, main, DIESEL, 400, 1000);
			FluidDevice out = outlet(net, main, DIESEL, 10000, 1000);

			tick();

			//The source loses the whole 400; only 300 ever reaches the far end. That is what makes
			//leakage a cost rather than a discount.
			assertEquals(400, endpointOf(in).totalExtracted);
			assertEquals(300, endpointOf(out).totalInserted);
		}

		@Test
		@DisplayName("a main that leaks everything does not drain its sources")
		void totalLeakageDrawsNothing()
		{
			FluidMain main = main(net, "severed", DIESEL);
			main.getPolicy().setLeakPct(1.0);
			FluidDevice in = inlet(net, main, DIESEL, 400, 1000);

			tick();

			assertEquals(0, endpointOf(in).totalExtracted,
					"draining a well into nothing is a bug, not a feature");
		}

		@Test
		@DisplayName("critical loads are served before everything else")
		void criticalLoadsAreShedLast()
		{
			FluidMain main = main(net, "short", DIESEL);
			inlet(net, main, DIESEL, 100, 1000);
			FluidDevice ordinary = outlet(net, main, DIESEL, 1000, 1000, 10, false);
			FluidDevice critical = outlet(net, main, DIESEL, 1000, 1000, 0, true);

			tick();

			assertEquals(100, endpointOf(critical).totalInserted,
					"the critical load takes the whole of a short supply");
			assertEquals(0, endpointOf(ordinary).totalInserted);
		}

		@Test
		@DisplayName("higher-priority inlets are drained first")
		void inletPriorityOrders()
		{
			FluidMain main = main(net, "main", DIESEL);
			main.getPolicy().setMaxInput(100);
			FluidDevice low = inlet(net, main, DIESEL, 500, 1000, 0);
			FluidDevice high = inlet(net, main, DIESEL, 500, 1000, 5);
			outlet(net, main, DIESEL, 10000, 10000);

			tick();

			assertEquals(100, endpointOf(high).totalExtracted);
			assertEquals(0, endpointOf(low).totalExtracted);
		}

		@Test
		@DisplayName("a closed main moves nothing")
		void closedMainIsInert()
		{
			FluidMain main = main(net, "closed", DIESEL);
			main.setEnabled(false);
			FluidDevice in = inlet(net, main, DIESEL, 500, 1000);
			FluidDevice out = outlet(net, main, DIESEL, 500, 1000);

			tick();

			assertEquals(0, endpointOf(in).totalExtracted);
			assertEquals(0, endpointOf(out).totalInserted);
		}

		@Test
		@DisplayName("an endpoint that over-reports cannot mint fluid")
		void misbehavingEndpointsAreClamped()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice in = net.registerDevice(pos(), FluidDeviceType.INLET);
			in.setTransferCap(100);
			net.assignDevice(in, main.getId());
			in.setEndpoint(new FakeFluidEndpoint.Misbehaving(DIESEL, 5000));
			main.invalidateViews();

			tick();

			assertEquals(100, main.getStats().getTickIn(),
					"the ledger must never record more than the engine offered");
		}
	}

	@Nested
	@DisplayName("valves")
	class Valves
	{
		@Test
		@DisplayName("a powered shut-off closes the main")
		void aShutOffCloses()
		{
			FluidMain main = main(net, "branch", DIESEL);
			valve(net, main, false, false, true);
			FluidDevice out = outlet(net, main, DIESEL, 500, 1000);
			inlet(net, main, DIESEL, 500, 1000);

			tick();

			assertTrue(main.isForcedClosed());
			assertEquals(0, endpointOf(out).totalInserted);
		}

		@Test
		@DisplayName("an inverted shut-off demands a keep-open signal")
		void invertedShutOffIsADeadMansSwitch()
		{
			FluidMain main = main(net, "branch", DIESEL);
			valve(net, main, false, true, false);
			inlet(net, main, DIESEL, 500, 1000);
			FluidDevice out = outlet(net, main, DIESEL, 500, 1000);

			tick();

			assertTrue(main.isForcedClosed(), "no signal means closed when inverted");
			assertEquals(0, endpointOf(out).totalInserted);
		}

		@Test
		@DisplayName("one closed valve closes the main whatever the others say")
		void shutOffsAreInSeries()
		{
			FluidMain main = main(net, "branch", DIESEL);
			valve(net, main, false, false, false);
			valve(net, main, false, false, true);
			inlet(net, main, DIESEL, 500, 1000);

			tick();

			assertTrue(main.isForcedClosed());
		}

		@Test
		@DisplayName("an indicator valve reports whether the main is flowing")
		void indicatorReportsState()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice lamp = valve(net, main, true, false, false);
			inlet(net, main, DIESEL, 500, 1000);
			outlet(net, main, DIESEL, 500, 1000);

			tick();
			assertEquals(15, endpointOf(lamp).publishedLevel);

			//Nothing left to move: the main is up in name only, which is exactly the case an alarm
			//exists for.
			tick();
			assertEquals(0, endpointOf(lamp).publishedLevel);
		}

		@Test
		@DisplayName("an inverted indicator is an alarm")
		void invertedIndicatorIsAnAlarm()
		{
			FluidMain main = main(net, "main", DIESEL);
			FluidDevice alarm = valve(net, main, true, true, false);
			inlet(net, main, DIESEL, 500, 1000);
			outlet(net, main, DIESEL, 500, 1000);

			tick();

			assertEquals(0, endpointOf(alarm).publishedLevel, "a healthy main should not alarm");
		}
	}

	@Nested
	@DisplayName("failover")
	class Failover
	{
		@Test
		@DisplayName("a backup main covers an outage")
		void backupCoversAnOutage()
		{
			FluidMain primary = main(net, "primary", DIESEL);
			FluidMain backup = main(net, "backup", DIESEL);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);

			inlet(net, backup, DIESEL, 500, 1000);
			FluidDevice load = outlet(net, primary, DIESEL, 500, 1000);

			tick();

			assertEquals(500, endpointOf(load).totalInserted);
			assertEquals(0, backup.getPack(), "the backup should have been drawn down");
		}

		@Test
		@DisplayName("a backup carrying a different fluid is not a backup")
		void wrongFluidCannotCover()
		{
			//	=================================
			//	The outlet here takes ANYTHING, on purpose.
			//	=================================
			//
			// This test used to use an ordinary outlet, which refuses a fluid it does not hold -- and
			// so it passed while the engine was taking its failover fluid from the backup rather than
			// from the main. A real TileEntityFluidOutlet does no such check: it fills its neighbours
			// with whatever the engine names. The guard has to be in the engine, so the fake must not
			// provide one, or the test is only ever exercising the fake.
			FluidMain primary = main(net, "diesel", DIESEL);
			FluidMain backup = main(net, "water", WATER);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);

			inlet(net, backup, WATER, 500, 1000);
			FluidDevice load = promiscuousOutlet(net, primary, 500, 1000);

			tick();

			assertEquals(0, endpointOf(load).totalInserted,
					"a diesel main was covered out of a water main");
			assertTrue(backup.getPack() > 0, "the water main should be untouched");
		}

		@Test
		@DisplayName("a failover never offers an outlet anything but its own main's fluid")
		void failoverOffersTheMainsFluid()
		{
			FluidMain primary = main(net, "diesel", DIESEL);
			FluidMain backup = main(net, "backup diesel", DIESEL);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);

			inlet(net, backup, DIESEL, 500, 1000);
			FluidDevice load = promiscuousOutlet(net, primary, 500, 1000);

			tick();

			assertEquals(500, endpointOf(load).totalInserted, "a matching backup should cover");
			assertEquals(DIESEL, endpointOf(load).lastOffered,
					"the fluid named must be the main's, never the backup's");
		}

		@Test
		@DisplayName("an untyped main cannot be covered at all")
		void untypedMainDoesNotFailOver()
		{
			//It has never carried anything, so there is nothing to cover -- and a main whose console
			//says "untyped" must not quietly start delivering diesel.
			FluidMain primary = main(net, "not built yet");
			FluidMain backup = main(net, "diesel", DIESEL);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);

			inlet(net, backup, DIESEL, 500, 1000);
			FluidDevice load = promiscuousOutlet(net, primary, 500, 1000);

			tick();

			assertEquals(0, endpointOf(load).totalInserted);
			assertNull(primary.getFluid(), "an untyped main must not be typed by its backup");
		}

		@Test
		@DisplayName("a failover cycle terminates")
		void cyclesTerminate()
		{
			FluidMain a = main(net, "a", DIESEL);
			FluidMain b = main(net, "b", DIESEL);
			a.addFailover(b.getId());
			b.addFailover(a.getId());
			a.setEnabled(false);
			outlet(net, a, DIESEL, 500, 1000);

			//The assertion is that this returns at all.
			tick();

			assertTrue(true);
		}

		@Test
		@DisplayName("the chain preview matches the order the engine would ask in")
		void chainPreviewIsTheRealOrder()
		{
			FluidMain a = main(net, "a", DIESEL);
			FluidMain b = main(net, "b", DIESEL);
			FluidMain c = main(net, "c", DIESEL);
			a.addFailover(b.getId());
			b.addFailover(c.getId());

			assertEquals(2, FluidNetEngine.failoverChain(net, a).size());
			assertEquals(b, FluidNetEngine.failoverChain(net, a).get(0));
			assertEquals(c, FluidNetEngine.failoverChain(net, a).get(1));
		}
	}

	@Nested
	@DisplayName("city mode")
	class CityMode
	{
		private void cityTick(long tick)
		{
			FluidNetEngine.tick(net, tick, true);
		}

		@Test
		@DisplayName("a main with a live inlet delivers freely")
		void aLiveSourcePressurisesTheMain()
		{
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain main = main(net, "town gas", GAS);
			inlet(net, main, GAS, 10000, 1000);
			FluidDevice load = outlet(net, main, GAS, 100000, 1000);

			cityTick(0);

			assertTrue(main.isPressurised());
			//The outlet gets its whole cap without any of it having been collected: that is the
			//trade city mode makes everywhere else too.
			assertEquals(1000, endpointOf(load).totalInserted);
		}

		@Test
		@DisplayName("proving the source is live costs a sip and nothing more")
		void livenessCostsASip()
		{
			FluidNetConfig.sipIntervalTicks = 1;
			FluidNetConfig.sipAmount = 1;
			FluidMain main = main(net, "town gas", GAS);
			FluidDevice source = inlet(net, main, GAS, 10000, 1000);
			outlet(net, main, GAS, 100000, 1000);

			cityTick(0);

			assertEquals(1, endpointOf(source).totalExtracted);
		}

		@Test
		@DisplayName("a dead source depressurises the main")
		void aDeadSourceStopsDelivery()
		{
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain main = main(net, "town gas", GAS);
			inlet(net, main, GAS, 0, 1000);
			FluidDevice load = outlet(net, main, GAS, 100000, 1000);

			cityTick(0);

			assertFalse(main.isPressurised());
			assertEquals(0, endpointOf(load).totalInserted);
		}

		@Test
		@DisplayName("city mode types an untyped main from the sip")
		void theSipTypesTheMain()
		{
			//There is no collect phase in city mode, so if the sip did not do this a city-mode
			//network would never decide what it carries and would never deliver anything.
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain main = main(net, "town gas");
			inlet(net, main, GAS, 10000, 1000);
			FluidDevice load = outlet(net, main, GAS, 100000, 1000);

			cityTick(0);

			assertEquals(GAS, main.getFluid());
			assertEquals(1000, endpointOf(load).totalInserted);
		}

		@Test
		@DisplayName("a live backup carrying a different fluid does not pressurise a main")
		void wrongFluidDoesNotPressurise()
		{
			//City mode trades accounting for a single bit, so that bit has to be trustworthy. A live
			//water main is not evidence that a diesel main has supply.
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain primary = main(net, "diesel", DIESEL);
			FluidMain backup = main(net, "water", WATER);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);
			inlet(net, backup, WATER, 10000, 1000);
			FluidDevice load = promiscuousOutlet(net, primary, 100000, 1000);

			cityTick(0);

			assertFalse(primary.isPressurised());
			assertEquals(0, endpointOf(load).totalInserted);
		}

		@Test
		@DisplayName("a live backup carrying the same fluid does pressurise it")
		void matchingBackupPressurises()
		{
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain primary = main(net, "diesel", DIESEL);
			FluidMain backup = main(net, "reserve", DIESEL);
			primary.addFailover(backup.getId());
			primary.setEnabled(false);
			inlet(net, backup, DIESEL, 10000, 1000);
			FluidDevice load = outlet(net, primary, DIESEL, 100000, 1000);

			cityTick(0);

			assertTrue(primary.isPressurised());
			assertEquals(1000, endpointOf(load).totalInserted);
		}

		@Test
		@DisplayName("the main's output cap is still a setting the player chose")
		void outputCapSurvivesCityMode()
		{
			FluidNetConfig.sipIntervalTicks = 1;
			FluidMain main = main(net, "town gas", GAS);
			main.getPolicy().setMaxOutput(250);
			inlet(net, main, GAS, 10000, 1000);
			FluidDevice load = outlet(net, main, GAS, 100000, 1000);

			cityTick(0);

			assertEquals(250, endpointOf(load).totalInserted);
		}
	}
}
