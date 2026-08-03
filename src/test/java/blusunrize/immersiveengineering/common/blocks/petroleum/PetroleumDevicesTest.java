/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.util.petroleum.PetroleumTickHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The small petroleum devices: the Lubrication Manifold, the three propane bodies, and the
 * forecourt's reach.
 * <p>
 * None of these had a test class. They are mostly numbers, but they are numbers with relationships
 * between them -- the manifold's rebate against what a machine actually costs to run, the propane
 * bodies against each other and against the buried tank they stop short of -- and a relationship is
 * exactly the kind of thing that gets broken by a plausible-looking edit to one side of it.
 */
class PetroleumDevicesTest
{
	@Nested
	@DisplayName("the Lubrication Manifold's rebate")
	class Manifold
	{
		@Test
		@DisplayName("the rebate is a flat number, small enough that it cannot pay for a machine")
		void rebateCannotMakeAHostNetPositive()
		{
			//	=================================
			//	This is bug class 5, pinned.
			//	=================================
			//
			// The rebate was briefly a fraction of the host's buffer capacity, which is unrelated to
			// what the host spends. On the Distillation Tower that came to a 59% discount, and
			// stacked with the burner's heat rebate it made the tower net-positive: it ran forever
			// with its power disconnected.
			//
			// The guard is that the rebate is a small absolute offset. The cheapest IE multiblock
			// draws in the region of sixteen flux a tick, so a rebate below that is a discount on
			// anything and a free lunch on nothing.
			assertTrue(TileEntityLubricationManifold.REBATE_PER_TICK < 16,
					"a rebate of "+TileEntityLubricationManifold.REBATE_PER_TICK
							+" flux a tick can pay for a cheap machine outright");
			assertTrue(TileEntityLubricationManifold.REBATE_PER_TICK > 0,
					"a rebate of nothing is not a rebate");
		}

		@Test
		@DisplayName("a bucket of lubricant is about eight minutes of continuous work")
		void aBucketLastsAboutEightMinutes()
		{
			//The design promise, and what makes greasing an occasional errand rather than a second
			//supply chain. Asserted as a range because the exact figure is a tuning knob.
			long ticks = (long)TileEntityLubricationManifold.CAPACITY
					/TileEntityLubricationManifold.LUBRICANT_PER_INTERVAL
					*TileEntityLubricationManifold.INTERVAL;
			long minutes = ticks/20/60;
			assertTrue(minutes >= 20&&minutes <= 40,
					"a full manifold runs "+minutes+" minutes; the tank is four buckets, so a bucket "
							+"should be about eight");
		}

		@Test
		@DisplayName("it tops up often enough that a machine is never visibly unlubricated")
		void intervalIsShort()
		{
			assertTrue(TileEntityLubricationManifold.INTERVAL <= 20,
					"a machine switched on should not run dry for a visible stretch");
		}

		@Test
		@DisplayName("the interval's rebate is the per-tick rate times the interval")
		void rebateArithmeticIsConsistent()
		{
			//The manifold pays once per interval for work done every tick of it, so these two have
			//to be multiplied and not confused -- paying REBATE_PER_TICK once per interval would be
			//a twentieth of the advertised benefit.
			int perInterval = TileEntityLubricationManifold.REBATE_PER_TICK
					*TileEntityLubricationManifold.INTERVAL;
			assertEquals(160, perInterval,
					"the standing rebate per top-up has changed; check it against what hosts spend");
		}
	}

	@Nested
	@DisplayName("the three propane bodies")
	class Propane
	{
		@Test
		@DisplayName("each body holds more than the last")
		void capacitiesAscend()
		{
			assertTrue(TileEntityPropaneTankUpright.CAPACITY > TileEntityPropaneCylinder.CAPACITY,
					"the upright tank must beat the cylinder");
			assertTrue(TileEntityPropaneTankTorpedo.CAPACITY > TileEntityPropaneTankUpright.CAPACITY,
					"the torpedo must beat the upright tank");
		}

		@Test
		@DisplayName("the capacities are the ones that shipped")
		void capacitiesAreFrozen()
		{
			assertEquals(4000, TileEntityPropaneCylinder.CAPACITY, "cylinder");
			assertEquals(12000, TileEntityPropaneTankUpright.CAPACITY, "upright");
			assertEquals(24000, TileEntityPropaneTankTorpedo.CAPACITY, "torpedo");
		}

		@Test
		@DisplayName("the largest portable body stops short of the smallest buried tank")
		void torpedoStopsShortOfTheBuriedTank()
		{
			//Deliberate: above this size, fuel belongs underground. If a surface block ever held
			//more than the Domestic Tank, there would be no reason to dig the hole.
			assertTrue(TileEntityPropaneTankTorpedo.CAPACITY
							< BuriedTankGeometry.DOMESTIC.capacity,
					"the torpedo holds "+TileEntityPropaneTankTorpedo.CAPACITY
							+" mB, which is not less than the buried Domestic Tank's "
							+BuriedTankGeometry.DOMESTIC.capacity);
		}
	}

	@Nested
	@DisplayName("the forecourt")
	class Forecourt
	{
		@Test
		@DisplayName("the price sign searches exactly as far as the nozzle reaches")
		void signSearchMatchesNozzleReach()
		{
			//A sign that found pumps the hose could not reach would advertise a price for fuel you
			//cannot buy at that island; one that searched less far would show nothing beside a pump
			//that is plainly working.
			assertEquals(TileEntityGasPump.NOZZLE_RANGE, TileEntityForecourtSign.SEARCH_RADIUS);
		}

		@Test
		@DisplayName("the hose reaches across a two-island canopy")
		void hoseCrossesACanopy()
		{
			//Six blocks is a canopy over two pump islands, which is the case the range was widened
			//from five to eight for.
			assertTrue(GasPumpAccounting.withinNozzleRange(0, 0, 0, 6, 0, 0,
					TileEntityGasPump.NOZZLE_RANGE));
		}

		@Test
		@DisplayName("the pump's buffer is deep enough to be useful unplumbed")
		void pumpHoldsAnAfternoon()
		{
			//Sixteen buckets: useful for an afternoon on its own, shallow enough that a real
			//forecourt is plumbed to a buried tank.
			assertEquals(16000, TileEntityGasPump.CAPACITY);
			assertTrue(TileEntityGasPump.CAPACITY < BuriedTankGeometry.DOMESTIC.capacity,
					"a pump that held as much as a buried tank would make the tank pointless");
		}
	}

	@Nested
	@DisplayName("the flare keeps up with the well it is fitted to")
	class FlareAgainstTheWell
	{
		@Test
		@DisplayName("its buffer is small, because anything it holds is already lost")
		void bufferIsSmall()
		{
			assertEquals(2000, TileEntityFlareStack.CAPACITY);
			assertTrue(TileEntityFlareStack.CAPACITY < TileEntityGasPump.CAPACITY,
					"a flare is a drain, not a store");
		}

		@Test
		@DisplayName("its buffer holds at least one wellhead production pass")
		void bufferHoldsAPass()
		{
			//Otherwise a delivery would be refused mid-pass and the well would back up behind a
			//flare that was working perfectly well.
			int gasPerPass = WellheadFlow.associatedGas(
					blusunrize.immersiveengineering.api.petroleum.PetroleumConfig.peakFlowRate
							*PetroleumTickHandler.PRODUCTION_INTERVAL,
					blusunrize.immersiveengineering.api.petroleum.PetroleumConfig.associatedGasRatio);
			assertTrue(TileEntityFlareStack.CAPACITY >= gasPerPass,
					"a wellhead pass brings up "+gasPerPass+" mB but the flare only holds "
							+TileEntityFlareStack.CAPACITY);
		}
	}
}
