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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hydraulic Crawler's arm.
 * <p>
 * The most important tests in the feature, and the reason the solver was written world-free at all.
 * The arm is about to become the thing that decides which of somebody's blocks stop existing, and the
 * whole chain from "the operator looked down" to "that block is gone" runs through this arithmetic. A
 * sign error here does not crash, does not log, and does not look broken -- it destroys the wrong wall.
 * <p>
 * The round-trip tests are the load-bearing ones: they put the solver and the forward kinematics
 * together and check the arm actually arrives where it was aimed. Two applications of the law of
 * cosines can each be individually plausible and still not compose, especially once clamping is
 * involved, and nothing but the round trip notices.
 */
class CrawlerArmTest
{
	/** Model units. A tenth of a pixel is far below anything visible or mechanically meaningful. */
	private static final double TOLERANCE = 0.1;

	private static double[] parked()
	{
		return new double[]{-38, 96, 24};
	}

	@Nested
	@DisplayName("solving for a view pitch")
	class Solve
	{
		@Test
		@DisplayName("it returns three finite angles at every pitch, including past the limits")
		void neverProducesNaN()
		{
			//	=================================
			//	The failure that would be worst.
			//	=================================
			//
			// A two-link solve is singular at full stretch and at the fold, and acos of 1.0000000001
			// from rounding is NaN. A NaN angle propagates into the tool's position and from there into
			// the set of blocks to break, where it becomes either nothing happening or something
			// happening somewhere unrelated. The margin inside the reachable range exists for this.
			for(double pitch = -180; pitch <= 180; pitch += 0.5)
			{
				double[] pose = CrawlerArm.solve(pitch);
				for(int i = 0; i < 3; i++)
				{
					assertFalse(Double.isNaN(pose[i]), "NaN joint "+i+" at pitch "+pitch);
					assertFalse(Double.isInfinite(pose[i]), "infinite joint "+i+" at pitch "+pitch);
				}
			}
		}

		@Test
		@DisplayName("looking further down puts the tool lower")
		void lowerViewDigsLower()
		{
			//The property an operator will actually feel. Monotonic, so there is no pitch at which
			//looking further down raises the bucket.
			double previous = Double.NEGATIVE_INFINITY;
			for(double pitch = CrawlerArm.MIN_DEPRESSION; pitch <= CrawlerArm.MAX_DEPRESSION; pitch += 1)
			{
				double[] pose = CrawlerArm.solve(pitch);
				double down = CrawlerArm.tipInPlane(pose[0], pose[1], pose[2])[1];
				assertTrue(down >= previous-TOLERANCE,
						"the tool rose when the view went down, at pitch "+pitch);
				previous = down;
			}
		}

		@Test
		@DisplayName("looking further down brings the tool in closer, at every step")
		void lowerViewDigsCloser()
		{
			//Reach shortens with depression, which is how an operator works: level is the far side of
			//the trench, steep is your own feet. Checked all the way along rather than just at the ends,
			//because the version of this that shipped first was monotonic at the ends and not in the
			//middle -- which is exactly the shape of bug that endpoints miss.
			double previous = Double.POSITIVE_INFINITY;
			for(double pitch = CrawlerArm.MIN_DEPRESSION; pitch <= CrawlerArm.MAX_DEPRESSION; pitch += 1)
			{
				double[] pose = CrawlerArm.solve(pitch);
				double out = CrawlerArm.tipInPlane(pose[0], pose[1], pose[2])[0];
				assertTrue(out <= previous+TOLERANCE,
						"the tool reached further out when the view went down, at pitch "+pitch);
				previous = out;
			}
		}

		@Test
		@DisplayName("pitches past the limits are clamped, not extrapolated")
		void clampsBeyondLimits()
		{
			double[] atLimit = CrawlerArm.solve(CrawlerArm.MAX_DEPRESSION);
			double[] wayPast = CrawlerArm.solve(CrawlerArm.MAX_DEPRESSION+60);
			for(int i = 0; i < 3; i++)
				assertEquals(atLimit[i], wayPast[i], 1e-9,
						"joint "+i+" kept moving past the arm's limit");
		}

		@Test
		@DisplayName("the boom carries its elbow up, the way an excavator does")
		void elbowIsUp()
		{
			//A two-link IK has two solutions, mirror images about the line to the target. The other one
			//is this posture upside down -- boom down, stick up -- which reads as a broken machine.
			//The boom is above the line to the target, so its angle is less depressed than the target's.
			for(double pitch = -30; pitch <= 60; pitch += 5)
			{
				double[] pose = CrawlerArm.solve(pitch);
				double depression = CrawlerGeometry.clamp(pitch,
						CrawlerArm.MIN_DEPRESSION, CrawlerArm.MAX_DEPRESSION);
				assertTrue(pose[0] <= depression+1e-9,
						"at pitch "+pitch+" the boom was below the line to its target");
			}
		}
	}

	@Nested
	@DisplayName("the arm arrives where it was aimed")
	class RoundTrip
	{
		@Test
		@DisplayName("the boom and stick put the elbow's tip at the intended reach and angle")
		void solveThenForwardKinematics()
		{
			//	=================================
			//	The test the whole solver exists to pass.
			//	=================================
			//
			// Checked at the *stick's* tip rather than the tool's, because the tool is deliberately not
			// part of the solve -- it hangs at a fixed attitude, so including it would be measuring the
			// aim against a target the solver was never given.
			for(double pitch = CrawlerArm.MIN_DEPRESSION; pitch <= CrawlerArm.MAX_DEPRESSION; pitch += 2)
			{
				double[] pose = CrawlerArm.solve(pitch);
				double a1 = Math.toRadians(pose[0]);
				double a2 = Math.toRadians(pose[0]+pose[1]);
				double out = CrawlerGeometry.BOOM_LENGTH*Math.cos(a1)
						+CrawlerGeometry.STICK_LENGTH*Math.cos(a2);
				double down = CrawlerGeometry.BOOM_LENGTH*Math.sin(a1)
						+CrawlerGeometry.STICK_LENGTH*Math.sin(a2);

				//Against the target the solver was actually given. This is the assertion that caught
				//REACH_FAR being set to fifty on an arm forty-six long: the arm stopped where it ran
				//out of reach and quietly aimed short of everything near level.
				double[] target = CrawlerArm.targetFor(pitch);
				assertEquals(target[0], out, TOLERANCE,
						"at pitch "+pitch+" the elbow was at the wrong distance out");
				assertEquals(target[1], down, TOLERANCE,
						"at pitch "+pitch+" the elbow was at the wrong depth");
			}
		}

		@Test
		@DisplayName("the tip never claims to be further out than the arm is long")
		void neverExceedsItsOwnReach()
		{
			double maximum = CrawlerGeometry.BOOM_LENGTH+CrawlerGeometry.STICK_LENGTH
					+CrawlerGeometry.TOOL_LENGTH;
			for(double pitch = -180; pitch <= 180; pitch += 1)
			{
				double[] pose = CrawlerArm.solve(pitch);
				double[] tip = CrawlerArm.tipInPlane(pose[0], pose[1], pose[2]);
				assertTrue(Math.hypot(tip[0], tip[1]) <= maximum+TOLERANCE,
						"at pitch "+pitch+" the tip was "+Math.hypot(tip[0], tip[1])
								+" from a pivot on an arm only "+maximum+" long");
			}
		}
	}

	@Nested
	@DisplayName("the tip in the world")
	class WorldPosition
	{
		@Test
		@DisplayName("it swings round with the house")
		void followsTheSlew()
		{
			double[] pose = CrawlerArm.solve(30);
			double[] north = CrawlerArm.tipOffset(0, pose[0], pose[1], pose[2]);
			double[] east = CrawlerArm.tipOffset(90, pose[0], pose[1], pose[2]);
			//Same height, same distance, different direction: the arm has not changed shape, only which
			//way it is pointing.
			assertEquals(north[1], east[1], 1e-9, "the tip changed height when the house turned");
			assertEquals(Math.hypot(north[0], north[2]), Math.hypot(east[0], east[2]), 1e-9,
					"the tip changed its distance from the machine when the house turned");
			assertTrue(Math.abs(north[0]-east[0]) > 0.5||Math.abs(north[2]-east[2]) > 0.5,
					"the tip did not move when the house turned a quarter turn");
		}

		@Test
		@DisplayName("it points along the house's heading, not across it")
		void alignedWithTheHouse()
		{
			//The arm has to come out of the front of the house. If this is off by ninety degrees the
			//machine digs sideways, which is the sort of thing that looks deliberate in a screenshot.
			double[] pose = CrawlerArm.solve(20);
			for(double slew = -180; slew <= 180; slew += 15)
			{
				double[] tip = CrawlerArm.tipOffset(slew, pose[0], pose[1], pose[2]);
				double[] facing = CrawlerGeometry.heading(slew);
				double horizontal = Math.hypot(tip[0], tip[2]);
				//The tip's horizontal offset, normalised, must be the house's heading.
				assertEquals(facing[0], tip[0]/horizontal, 1e-9, "slew "+slew+" x");
				assertEquals(facing[1], tip[2]/horizontal, 1e-9, "slew "+slew+" z");
			}
		}

		@Test
		@DisplayName("digging at your feet still puts the tip below the pivot")
		void reachesBelowThePivot()
		{
			//A machine that cannot get its bucket below its own tracks cannot dig a hole, which is
			//most of the job.
			double[] pose = CrawlerArm.solve(CrawlerArm.MAX_DEPRESSION);
			double[] tip = CrawlerArm.tipOffset(0, pose[0], pose[1], pose[2]);
			assertTrue(tip[1] < 0,
					"at full depression the tip was still "+tip[1]+" above the machine's base");
		}
	}

	@Nested
	@DisplayName("hydraulic speed")
	class Stepping
	{
		@Test
		@DisplayName("no joint moves faster than its rate")
		void respectsTheRate()
		{
			double[] from = parked();
			double[] to = CrawlerArm.solve(CrawlerArm.MAX_DEPRESSION);
			double[] next = CrawlerArm.step(from, to);
			for(int i = 0; i < 3; i++)
				assertTrue(Math.abs(CrawlerGeometry.shortestTurn(from[i], next[i]))
								<= CrawlerArm.JOINT_RATE+1e-9,
						"joint "+i+" moved "+Math.abs(next[i]-from[i])+" degrees in one tick");
		}

		@Test
		@DisplayName("it arrives, and then stays there")
		void convergesAndSettles()
		{
			//Rate limiting that overshoots would oscillate forever, and an arm that never settled would
			//never let the tool stop moving -- which, once the tool breaks things, is a machine that
			//never stops breaking things.
			double[] pose = parked();
			double[] target = CrawlerArm.solve(45);
			for(int tick = 0; tick < 400; tick++)
				pose = CrawlerArm.step(pose, target);
			for(int i = 0; i < 3; i++)
				assertEquals(target[i], pose[i], 1e-9, "joint "+i+" never settled");

			double[] settled = CrawlerArm.step(pose, target);
			for(int i = 0; i < 3; i++)
				assertEquals(target[i], settled[i], 1e-9, "joint "+i+" moved after arriving");
		}

		@Test
		@DisplayName("it takes the short way round")
		void takesTheShortWay()
		{
			//Through shortestTurn, so a joint at 179 heading for -179 moves two degrees rather than
			//three hundred and fifty eight.
			double[] next = CrawlerArm.step(new double[]{179, 0, 0}, new double[]{-179, 0, 0});
			assertTrue(Math.abs(CrawlerGeometry.shortestTurn(179, next[0])) <= CrawlerArm.JOINT_RATE,
					"the joint took the long way across the wrap");
		}
	}
}
