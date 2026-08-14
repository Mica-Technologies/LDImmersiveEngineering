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
 * The Hydraulic Crawler's driving physics.
 * <p>
 * The same reason everything else about this machine is a pure function: the harness has no Minecraft
 * bootstrap, so the entity cannot be constructed and anything left on it is untested by construction.
 * "It feels janky" is not a thing a test can answer -- but "the throttle reaches full speed in about a
 * second", "letting go stops it without it coasting" and "it cannot bank momentum against a wall" all
 * are, and those are what the feel is made of.
 */
class CrawlerDriveTest
{
	private static final double EPSILON = 1e-9;

	@Nested
	@DisplayName("ramp")
	class Ramp
	{
		@Test
		@DisplayName("it never overshoots what was asked for")
		void neverOvershoots()
		{
			//The failure this prevents is a machine that oscillates about its target speed for ever,
			//which at twenty ticks a second reads as a vibration rather than as a control system.
			assertEquals(0.05, CrawlerDrive.ramp(0.05, 0.05, 1, 1), EPSILON);
			assertEquals(0.1, CrawlerDrive.ramp(0.09, 0.1, 1, 1), EPSILON);
			assertEquals(-0.1, CrawlerDrive.ramp(-0.09, -0.1, 1, 1), EPSILON);
		}

		@Test
		@DisplayName("speeding up uses the acceleration and easing off uses the brake")
		void twoRates()
		{
			assertEquals(0.01, CrawlerDrive.ramp(0, 1, 0.01, 0.5), EPSILON);
			assertEquals(0.5, CrawlerDrive.ramp(1, 0, 0.01, 0.5), EPSILON);
		}

		@Test
		@DisplayName("changing your mind at speed brakes rather than accelerating backwards")
		void reversalBrakes()
		{
			//	=================================
			//	The one that decides how a change of direction feels.
			//	=================================
			//
			// Asking for reverse while going forwards is asking for a bigger number in the other
			// direction. A rule written only about magnitude would call that acceleration and drag the
			// machine backwards through zero on the accelerator, which is a slow-motion pirouette rather
			// than a stop. Direction is what the test is: while the two disagree about which way the
			// machine is going, the brake applies.
			assertEquals(0.5, CrawlerDrive.ramp(1, -1, 0.01, 0.5), EPSILON);
			//And once it is actually going that way, the accelerator does.
			assertEquals(-0.51, CrawlerDrive.ramp(-0.5, -1, 0.01, 0.5), EPSILON);
		}

		@Test
		@DisplayName("it always arrives, from anywhere, and stays there")
		void alwaysConverges()
		{
			for(double target : new double[]{-CrawlerDrive.TOP_SPEED, 0, CrawlerDrive.TOP_SPEED})
			{
				double speed = -CrawlerDrive.TOP_SPEED;
				for(int tick = 0; tick < 200; tick++)
					speed = CrawlerDrive.ramp(speed, target, CrawlerDrive.ACCELERATION,
							CrawlerDrive.BRAKING);
				assertEquals(target, speed, EPSILON, "target "+target);
			}
		}
	}

	@Nested
	@DisplayName("the throttle")
	class Throttle
	{
		@Test
		@DisplayName("full ahead is the top speed and full astern is slower")
		void reverseIsSlower()
		{
			assertEquals(CrawlerDrive.TOP_SPEED, CrawlerDrive.targetSpeed(1), EPSILON);
			assertEquals(-CrawlerDrive.TOP_SPEED*CrawlerDrive.REVERSE_FACTOR,
					CrawlerDrive.targetSpeed(-1), EPSILON);
			assertTrue(CrawlerDrive.REVERSE_FACTOR < 1, "reverse is not slower than forward");
		}

		@Test
		@DisplayName("no input is no speed")
		void neutralIsStopped()
		{
			assertEquals(0, CrawlerDrive.targetSpeed(0), EPSILON);
		}

		@Test
		@DisplayName("an input past the stops is not a faster machine")
		void clampedToTheStops()
		{
			//A rider's movement input is not guaranteed to be within one -- sprinting, a modified
			//client, a mob steering. None of them may be a speed boost.
			assertEquals(CrawlerDrive.targetSpeed(1), CrawlerDrive.targetSpeed(4), EPSILON);
			assertEquals(CrawlerDrive.targetSpeed(-1), CrawlerDrive.targetSpeed(-4), EPSILON);
		}

		@Test
		@DisplayName("it reaches full speed in about a second, and stops in half of one")
		void feelsLikeMass()
		{
			//The numbers the feel is actually made of. Both directions are stated because "heavy" and
			//"unresponsive" are the same thing measured either side of about a second.
			int ticks = 0;
			double speed = 0;
			while(speed < CrawlerDrive.TOP_SPEED&&ticks < 200)
			{
				speed = CrawlerDrive.ramp(speed, CrawlerDrive.TOP_SPEED, CrawlerDrive.ACCELERATION,
						CrawlerDrive.BRAKING);
				ticks++;
			}
			assertTrue(ticks >= 10&&ticks <= 30, "full throttle took "+ticks+" ticks to reach speed");

			int stopping = 0;
			while(speed > 0&&stopping < 200)
			{
				speed = CrawlerDrive.ramp(speed, 0, CrawlerDrive.ACCELERATION, CrawlerDrive.BRAKING);
				stopping++;
			}
			assertTrue(stopping <= 12, "letting go took "+stopping+" ticks to stop; it should not coast");
			assertTrue(stopping < ticks, "it brakes no harder than it accelerates, which is a boat");
		}
	}

	@Nested
	@DisplayName("the steering")
	class Steering
	{
		@Test
		@DisplayName("A turns one way and D the other")
		void steersBothWays()
		{
			assertEquals(-CrawlerDrive.targetTurn(1, 0), CrawlerDrive.targetTurn(-1, 0), EPSILON);
			assertTrue(CrawlerDrive.targetTurn(1, 0)!=0);
		}

		@Test
		@DisplayName("it turns tightest standing still, which is what a skid steer is for")
		void tightestAtRest()
		{
			double parked = Math.abs(CrawlerDrive.targetTurn(1, 0));
			double moving = Math.abs(CrawlerDrive.targetTurn(1, CrawlerDrive.TOP_SPEED));
			assertEquals(CrawlerDrive.TURN_RATE, parked, EPSILON);
			assertTrue(moving < parked, "it turns as tightly at speed as it does parked");
			assertTrue(moving > 0, "it cannot be steered at all while moving");
		}

		@Test
		@DisplayName("reversing turns no better than going forwards")
		void symmetricInSpeed()
		{
			assertEquals(CrawlerDrive.targetTurn(1, CrawlerDrive.TOP_SPEED),
					CrawlerDrive.targetTurn(1, -CrawlerDrive.TOP_SPEED), EPSILON);
		}

		@Test
		@DisplayName("nothing beyond full lock, however hard the input pushes")
		void clampedToFullLock()
		{
			assertEquals(CrawlerDrive.TURN_RATE, Math.abs(CrawlerDrive.targetTurn(9, 0)), EPSILON);
			//And a speed past the top speed does not reverse the penalty into a bonus.
			assertTrue(Math.abs(CrawlerDrive.targetTurn(1, 10*CrawlerDrive.TOP_SPEED)) > 0);
			assertEquals(CrawlerDrive.targetTurn(1, CrawlerDrive.TOP_SPEED),
					CrawlerDrive.targetTurn(1, 10*CrawlerDrive.TOP_SPEED), EPSILON);
		}

		@Test
		@DisplayName("the turn winds up over several ticks rather than switching on")
		void rampsRatherThanSteps()
		{
			//What the client sees is a heading quantised to a byte. A turn that arrives at its full
			//rate in one tick is therefore drawn as a stair; one that winds up is drawn as a curve.
			double rate = 0;
			double full = CrawlerDrive.targetTurn(1, 0);
			int ticks = 0;
			while(Math.abs(rate) < Math.abs(full)&&ticks < 200)
			{
				rate = CrawlerDrive.ramp(rate, full, CrawlerDrive.TURN_ACCELERATION,
						CrawlerDrive.TURN_BRAKING);
				ticks++;
			}
			assertTrue(ticks >= 3, "the steering reaches full lock in "+ticks+" ticks, which is a step");
			assertTrue(ticks <= 15, "the steering takes "+ticks+" ticks to answer, which is a delay");
		}

		@Test
		@DisplayName("letting go of the steering stops the turn faster than holding it started one")
		void unwindsFaster()
		{
			assertTrue(CrawlerDrive.TURN_BRAKING > CrawlerDrive.TURN_ACCELERATION,
					"the machine keeps turning after the key is released");
		}
	}

	@Nested
	@DisplayName("after the move")
	class AfterMove
	{
		@Test
		@DisplayName("a clear tick changes nothing")
		void unobstructed()
		{
			double speed = CrawlerDrive.TOP_SPEED;
			assertEquals(speed, CrawlerDrive.afterMove(speed, speed, 0), EPSILON);
		}

		@Test
		@DisplayName("a machine against a wall banks no momentum")
		void blockedLosesEverything()
		{
			//	=================================
			//	The one that would have been a catapult.
			//	=================================
			//
			// Speed the machine never spent is speed it would spend all at once the moment the obstacle
			// was dug out from in front of it -- which on a demolition machine is a thing that happens
			// several times a minute.
			assertEquals(0, CrawlerDrive.afterMove(CrawlerDrive.TOP_SPEED, 0, 0), EPSILON);
			assertEquals(0, CrawlerDrive.afterMove(-CrawlerDrive.TOP_SPEED, 0, 0), EPSILON);
		}

		@Test
		@DisplayName("a partial tick keeps the part it managed, and its direction")
		void partiallyBlocked()
		{
			assertEquals(0.03, CrawlerDrive.afterMove(0.14, 0.03, 0), 1e-9);
			assertEquals(-0.03, CrawlerDrive.afterMove(-0.14, 0.03, 0), 1e-9);
		}

		@Test
		@DisplayName("climbing costs speed, and falling does not")
		void climbingCosts()
		{
			double speed = CrawlerDrive.TOP_SPEED;
			double climbed = CrawlerDrive.afterMove(speed, speed, 1);
			assertTrue(climbed < speed, "mounting a full block cost the machine nothing");
			assertTrue(climbed > 0, "mounting a block stopped the machine dead");
			assertEquals(speed, CrawlerDrive.afterMove(speed, speed, -1), EPSILON);
		}

		@Test
		@DisplayName("it never turns a machine round or speeds one up")
		void neverGainsOrReverses()
		{
			for(double speed : new double[]{-0.2, -0.05, 0.05, 0.2})
				for(double travelled : new double[]{0, 0.01, 0.2, 5})
					for(double climbed : new double[]{-1, 0, 0.4, 1})
					{
						double after = CrawlerDrive.afterMove(speed, travelled, climbed);
						assertTrue(Math.abs(after) <= Math.abs(speed)+1e-9,
								"speed grew: "+speed+" -> "+after);
						assertTrue(after*speed >= 0, "direction flipped: "+speed+" -> "+after);
					}
		}
	}

	@Nested
	@DisplayName("client interpolation")
	class Interpolation
	{
		@Test
		@DisplayName("an ordinary correction is spread over several ticks")
		void smoothsSmallCorrections()
		{
			assertEquals(CrawlerDrive.LERP_STEPS, CrawlerDrive.lerpSteps(0));
			assertEquals(CrawlerDrive.LERP_STEPS, CrawlerDrive.lerpSteps(CrawlerDrive.TOP_SPEED));
			assertTrue(CrawlerDrive.LERP_STEPS > 1, "no interpolation at all is where this came in");
		}

		@Test
		@DisplayName("a teleport is not a correction and is not slid across")
		void snapsLargeOnes()
		{
			//Sliding smoothly across a hundred blocks is a machine visibly flying through the terrain.
			assertEquals(1, CrawlerDrive.lerpSteps(100));
		}

		@Test
		@DisplayName("interpolating is quick enough not to feel like input lag")
		void notADelay()
		{
			//At a packet a tick, this is roughly how far behind the server the operator's own machine
			//sits. Ten -- what a vanilla boat uses for boats it is not steering -- would be a third of a
			//second of delay between the key and the tracks.
			assertTrue(CrawlerDrive.LERP_STEPS <= 4,
					"the machine you are driving would answer "+CrawlerDrive.LERP_STEPS+" ticks late");
		}
	}
}
