/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hydraulic Crawler's arithmetic.
 * <p>
 * This is the whole of the machine that can be tested at all: the harness has no Minecraft
 * bootstrap, so the entity itself cannot be constructed -- its constructor wants a World. Which is
 * exactly why the numbers were moved off it. A machine whose job is deciding which of somebody's
 * blocks to destroy should not carry untested trigonometry, and the trigonometry is the part where
 * a sign error looks like a feature until you are standing in the wrong place.
 */
class CrawlerGeometryTest
{
	private static final double EPSILON = 1e-9;

	@Nested
	@DisplayName("heading")
	class Heading
	{
		@Test
		@DisplayName("zero yaw drives along positive Z, which is Minecraft's convention and not maths'")
		void zeroIsSouth()
		{
			double[] h = CrawlerGeometry.heading(0);
			assertEquals(0, h[0], EPSILON);
			assertEquals(1, h[1], EPSILON);
		}

		@Test
		@DisplayName("ninety degrees drives along negative X")
		void ninetyIsWest()
		{
			//The sign that catches people: MC's yaw runs the opposite way round from the unit circle,
			//so a positive quarter turn goes to -X rather than +X.
			double[] h = CrawlerGeometry.heading(90);
			assertEquals(-1, h[0], EPSILON);
			assertEquals(0, h[1], EPSILON);
		}

		@Test
		@DisplayName("a hundred and eighty reverses the direction of travel")
		void oppositeReverses()
		{
			double[] forward = CrawlerGeometry.heading(37);
			double[] back = CrawlerGeometry.heading(37+180);
			assertEquals(-forward[0], back[0], EPSILON);
			assertEquals(-forward[1], back[1], EPSILON);
		}

		@Test
		@DisplayName("it is always a unit vector, so throttle alone sets the speed")
		void alwaysUnitLength()
		{
			//Otherwise the machine would be faster on the diagonals, which nobody would report as a
			//bug and everybody would feel.
			for(int yaw = -360; yaw <= 360; yaw += 7)
			{
				double[] h = CrawlerGeometry.heading(yaw);
				assertEquals(1, Math.sqrt(h[0]*h[0]+h[1]*h[1]), 1e-9, "yaw "+yaw);
			}
		}
	}

	@Nested
	@DisplayName("the cab's seat")
	class Cab
	{
		@Test
		@DisplayName("it sits off to one side, not on the centreline")
		void offsetToOneSide()
		{
			double[] seat = CrawlerGeometry.cabOffset(0);
			assertEquals(CrawlerGeometry.CAB_SIDE, seat[0], EPSILON);
			assertEquals(CrawlerGeometry.CAB_FORWARD, seat[1], EPSILON);
		}

		@Test
		@DisplayName("it travels round with the slew rather than staying put")
		void followsTheSlew()
		{
			//The failure this prevents: a seat fixed to the tracks while the cab is on the house, so
			//slewing leaves the operator sitting in the air beside the machine.
			double[] parked = CrawlerGeometry.cabOffset(0);
			double[] slewed = CrawlerGeometry.cabOffset(90);
			assertTrue(Math.abs(parked[0]-slewed[0]) > 0.5,
					"the seat did not move when the house turned a quarter turn");
		}

		@Test
		@DisplayName("its distance from the mast never changes, whatever the slew")
		void radiusIsConstant()
		{
			double expected = Math.hypot(CrawlerGeometry.CAB_SIDE, CrawlerGeometry.CAB_FORWARD);
			for(int slew = -360; slew <= 360; slew += 11)
			{
				double[] seat = CrawlerGeometry.cabOffset(slew);
				assertEquals(expected, Math.hypot(seat[0], seat[1]), 1e-9, "slew "+slew);
			}
		}

		@Test
		@DisplayName("the seat is actually inside the cab")
		void seatIsInsideTheCab()
		{
			//	=================================
			//	The one that shipped wrong.
			//	=================================
			//
			// The seat's offsets and the cab's box were independent numbers, both of which looked about
			// right, and the seat was outside the cab. Nothing in the game reports that: a passenger
			// hanging in the air beside a machine looks exactly like a passenger positioned badly on
			// purpose. Both are now read off the model's cab box, and this is what says so.
			double[] seat = CrawlerGeometry.cabOffset(0);
			assertTrue(seat[0] >= CrawlerGeometry.CAB_MIN_X&&seat[0] <= CrawlerGeometry.CAB_MAX_X,
					"the seat is at x="+seat[0]+", outside the cab's "
							+CrawlerGeometry.CAB_MIN_X+".."+CrawlerGeometry.CAB_MAX_X);
			assertTrue(seat[1] >= CrawlerGeometry.CAB_MIN_Z&&seat[1] <= CrawlerGeometry.CAB_MAX_Z,
					"the seat is at z="+seat[1]+", outside the cab's "
							+CrawlerGeometry.CAB_MIN_Z+".."+CrawlerGeometry.CAB_MAX_Z);
		}

		@Test
		@DisplayName("the seat is not on the machine's centreline, because the cab is not")
		void seatIsOffCentre()
		{
			//Every excavator's cab is offset to one side. A seat on the centreline would mean the two
			//had been derived from different things again.
			assertTrue(CrawlerGeometry.CAB_SIDE > 0.2,
					"the seat is nearly on the centreline; the cab is offset and the seat should be too");
		}

		@Test
		@DisplayName("a full turn returns it to where it started")
		void fullTurnIsIdentity()
		{
			double[] zero = CrawlerGeometry.cabOffset(0);
			double[] round = CrawlerGeometry.cabOffset(360);
			assertEquals(zero[0], round[0], 1e-9);
			assertEquals(zero[1], round[1], 1e-9);
		}
	}

	@Nested
	@DisplayName("shortestTurn")
	class ShortestTurn
	{
		@Test
		@DisplayName("it takes the short way across the wrap, not the long way round")
		void crossesTheWrap()
		{
			//The bug it exists to prevent: interpolating 179 to -179 the long way spins the house a
			//full revolution, once per wrap, for a two degree change.
			assertEquals(2, CrawlerGeometry.shortestTurn(179, -179), EPSILON);
			assertEquals(-2, CrawlerGeometry.shortestTurn(-179, 179), EPSILON);
		}

		@Test
		@DisplayName("it never returns more than half a turn")
		void boundedToHalfATurn()
		{
			for(int from = -360; from <= 360; from += 13)
				for(int to = -360; to <= 360; to += 17)
				{
					double turn = CrawlerGeometry.shortestTurn(from, to);
					assertTrue(turn >= -180&&turn <= 180,
							"turn from "+from+" to "+to+" was "+turn);
				}
		}

		@Test
		@DisplayName("no turn at all is no turn at all")
		void identityIsZero()
		{
			assertEquals(0, CrawlerGeometry.shortestTurn(42, 42), EPSILON);
			//And the same heading expressed a revolution apart is still no turn.
			assertEquals(0, CrawlerGeometry.shortestTurn(42, 402), EPSILON);
		}
	}
}
