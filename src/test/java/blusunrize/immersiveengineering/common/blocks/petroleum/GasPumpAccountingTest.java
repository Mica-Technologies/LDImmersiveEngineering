/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Gas Station Pump's transaction rules.
 * <p>
 * The pump is the one block in this fork that a server's economy plugin talks to, through
 * {@code FuelDispensedEvent}. What happens when a listener asks for more than the pump holds, or
 * for a negative amount, or vetoes the sale outright, is therefore a contract somebody may depend
 * on -- and it was three lines wrapped around a Forge event, a player's inventory and a FluidStack,
 * which is to say unreachable from a test.
 */
class GasPumpAccountingTest
{
	@Nested
	@DisplayName("what a listener is allowed to change")
	class Authorisation
	{
		@Test
		@DisplayName("a listener that lowers the amount gets exactly what it asked for")
		void loweringIsHonoured()
		{
			//The point of being able to listen: "this player can afford half a bucket".
			assertEquals(500, GasPumpAccounting.granted(500, 16000, false));
		}

		@Test
		@DisplayName("a listener that raises it gets no more than the pump is holding")
		void raisingIsCappedByTheTank()
		{
			//A pump cannot dispense fuel that is not in it, however generous the economy feels.
			assertEquals(300, GasPumpAccounting.granted(999999, 300, false));
		}

		@Test
		@DisplayName("a cancelled sale dispenses nothing")
		void cancelledSellsNothing()
		{
			assertEquals(0, GasPumpAccounting.granted(16000, 16000, true));
		}

		@Test
		@DisplayName("a negative answer sells nothing rather than running the sale backwards")
		void negativeSellsNothing()
		{
			//Left unguarded this would reach the tank as a negative drain, which is a refill.
			assertEquals(0, GasPumpAccounting.granted(-500, 16000, false));
		}

		@Test
		@DisplayName("an empty pump dispenses nothing whatever the listener says")
		void emptyPumpSellsNothing()
		{
			assertEquals(0, GasPumpAccounting.granted(1000, 0, false));
			assertEquals(0, GasPumpAccounting.granted(1000, -5, false));
		}

		@Test
		@DisplayName("the amount offered to listeners is the smaller of the room and the stock")
		void requestIsTheSmallerOfRoomAndStock()
		{
			assertEquals(1000, GasPumpAccounting.requested(1000, 16000), "container is the limit");
			assertEquals(400, GasPumpAccounting.requested(1000, 400), "tank is the limit");
			assertEquals(0, GasPumpAccounting.requested(0, 16000), "a full container takes nothing");
			assertEquals(0, GasPumpAccounting.requested(-10, 16000), "negative room is no room");
		}
	}

	@Nested
	@DisplayName("city mode moves the meter without emptying the tank")
	class CityMode
	{
		@Test
		@DisplayName("the tank is charged a token sip rather than the full amount")
		void tankTakesOnlyASip()
		{
			assertEquals(GasPumpAccounting.CITY_SIP, GasPumpAccounting.tankDebit(1000, true));
			assertEquals(1000, GasPumpAccounting.tankDebit(1000, false));
		}

		@Test
		@DisplayName("but the odometer still counts what was actually handed over")
		void odometerCountsTheRealSale()
		{
			//Otherwise a roleplay forecourt reports having sold a millibucket a day, and the
			//lifetime counter -- the whole reason the block has one -- becomes meaningless.
			assertEquals(1000, GasPumpAccounting.odometerAdvance(1000));
		}

		@Test
		@DisplayName("a sale smaller than the sip costs only what it was")
		void sipNeverExceedsTheSale()
		{
			//Or a one-millibucket top-up would debit more than it dispensed.
			assertEquals(0, GasPumpAccounting.tankDebit(0, true));
			assertTrue(GasPumpAccounting.tankDebit(1, true) <= 1);
		}

		@Test
		@DisplayName("nothing dispensed costs nothing in either mode")
		void nothingCostsNothing()
		{
			assertEquals(0, GasPumpAccounting.tankDebit(0, false));
			assertEquals(0, GasPumpAccounting.tankDebit(-50, true));
			assertEquals(0, GasPumpAccounting.odometerAdvance(-50));
		}
	}

	@Nested
	@DisplayName("the price is a reported number, not a currency")
	class Price
	{
		@Test
		@DisplayName("a negative price reads as free")
		void negativeIsFree()
		{
			assertEquals(0, GasPumpAccounting.clampPrice(-1));
			assertEquals(0, GasPumpAccounting.clampPrice(Integer.MIN_VALUE));
		}

		@Test
		@DisplayName("an absurd price is capped rather than stored")
		void absurdIsCapped()
		{
			//A malformed packet must not be able to park a nonsense number in world save data.
			assertEquals(GasPumpAccounting.MAX_PRICE, GasPumpAccounting.clampPrice(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("an ordinary price is stored unchanged")
		void ordinaryIsKept()
		{
			assertEquals(0, GasPumpAccounting.clampPrice(0));
			assertEquals(25, GasPumpAccounting.clampPrice(25));
			assertEquals(GasPumpAccounting.MAX_PRICE,
					GasPumpAccounting.clampPrice(GasPumpAccounting.MAX_PRICE));
		}
	}

	@Nested
	@DisplayName("the comparator distinguishes a drop from nothing")
	class Comparator
	{
		@Test
		@DisplayName("an empty pump reads zero and a nearly empty one reads one")
		void emptyAndNearlyEmptyDiffer()
		{
			//A forecourt that switches a refill pump on at zero would otherwise never switch it off
			//until the tank was bone dry.
			assertEquals(0, GasPumpAccounting.comparatorLevel(0, 16000));
			assertEquals(1, GasPumpAccounting.comparatorLevel(1, 16000));
		}

		@Test
		@DisplayName("a full pump reads fifteen")
		void fullReadsFifteen()
		{
			assertEquals(15, GasPumpAccounting.comparatorLevel(16000, 16000));
		}

		@Test
		@DisplayName("the level never leaves 0..15 at any fill")
		void alwaysInRange()
		{
			for(int amount = 0; amount <= 16000; amount += 37)
			{
				int level = GasPumpAccounting.comparatorLevel(amount, 16000);
				assertTrue(level >= 0&&level <= 15, "level "+level+" at "+amount+" mB");
			}
		}

		@Test
		@DisplayName("the level never falls as the tank fills")
		void monotonic()
		{
			int previous = 0;
			for(int amount = 0; amount <= 16000; amount += 11)
			{
				int level = GasPumpAccounting.comparatorLevel(amount, 16000);
				assertTrue(level >= previous,
						"level dropped from "+previous+" to "+level+" at "+amount+" mB");
				previous = level;
			}
		}

		@Test
		@DisplayName("a capacity of nothing reads zero rather than dividing by it")
		void zeroCapacityIsSafe()
		{
			assertEquals(0, GasPumpAccounting.comparatorLevel(100, 0));
		}

		@Test
		@DisplayName("a depot-sized tank does not overflow the level calculation")
		void hugeTankDoesNotOverflow()
		{
			//15 * 4,000,000 still fits in an int, but a bigger tank would not -- and the buried
			//depot is exactly that size, so this is the shape that has bitten this feature before.
			int level = GasPumpAccounting.comparatorLevel(4000000, 4000000);
			assertEquals(15, level);
			assertTrue(GasPumpAccounting.comparatorLevel(Integer.MAX_VALUE, Integer.MAX_VALUE) >= 0);
		}
	}

	@Nested
	@DisplayName("the nozzle reaches exactly as far as it says")
	class NozzleRange
	{
		@Test
		@DisplayName("a target at the limit is in range and one past it is not")
		void limitIsInclusive()
		{
			assertTrue(GasPumpAccounting.withinNozzleRange(0, 0, 0, 8, 0, 0, 8));
			assertFalse(GasPumpAccounting.withinNozzleRange(0, 0, 0, 9, 0, 0, 8));
		}

		@Test
		@DisplayName("a diagonal reach is measured the same way as a straight one")
		void diagonalUsesTrueDistance()
		{
			//A hose does not care which way it is pointing, so this is a sphere and not a box.
			//6,6,0 is 8.49 blocks away and must be refused even though each axis is under 8.
			assertFalse(GasPumpAccounting.withinNozzleRange(0, 0, 0, 6, 6, 0, 8));
			assertTrue(GasPumpAccounting.withinNozzleRange(0, 0, 0, 4, 4, 4, 8));
		}

		@Test
		@DisplayName("height counts towards the reach")
		void verticalCounts()
		{
			assertFalse(GasPumpAccounting.withinNozzleRange(0, 0, 0, 0, 9, 0, 8));
		}

		@Test
		@DisplayName("the pump reaches itself")
		void reachesItself()
		{
			assertTrue(GasPumpAccounting.withinNozzleRange(100, 64, -50, 100, 64, -50, 8));
		}

		@Test
		@DisplayName("far-apart coordinates do not overflow into a false positive")
		void farCoordinatesDoNotOverflow()
		{
			//Squared in int, a pair of coordinates 30 million apart wraps negative and reads as
			//"in range" -- and 30 million is inside the world border.
			assertFalse(GasPumpAccounting.withinNozzleRange(-30000000, 0, 0, 30000000, 0, 0, 8));
		}
	}
}
