/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

/**
 * How the Hydraulic Crawler gets under way, slows down and turns -- as arithmetic, with no world and
 * no entity in sight.
 * <p>
 * <strong>The machine used to have no acceleration model at all.</strong> A held key put a fixed
 * impulse into {@code motion} every tick and a friction multiplier took most of it back out, which
 * meant full speed on the first tick of W, a dead stop four ticks after letting go, and a turn rate
 * that switched between zero and its maximum between one tick and the next. Twenty tonnes on steel
 * tracks does not do any of those things, and a machine that did all three read -- correctly -- as
 * janky.
 * <p>
 * Everything here is therefore a <em>rate</em>: the throttle asks for a speed and the machine walks
 * towards it, the steering asks for a turn and the tracks wind up to it. That is the whole difference
 * between a vehicle that feels driven and one that feels dragged, and it is also the part of the
 * physics that can be held to a test -- see {@link CrawlerGeometry} for why anything left on the
 * entity is code no test will ever run.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public final class CrawlerDrive
{
	private CrawlerDrive()
	{
	}

	//	=================================
	//		THROTTLE
	//	=================================

	/**
	 * Blocks per tick flat out, forwards.
	 * <p>
	 * Deliberately close to what the machine already did, because the complaint was never that it was
	 * the wrong speed. Under the old impulse-and-friction scheme the steady state worked out at about
	 * 0.13 blocks a tick, and this is that number stated directly rather than as the fixed point of two
	 * others -- which is a small gain on its own: it can now be changed without recomputing what it
	 * settles at.
	 */
	public static final double TOP_SPEED = 0.14;

	/** Reverse is slower, as it is on anything with tracks. */
	public static final double REVERSE_FACTOR = 0.6;

	/**
	 * Blocks per tick, per tick, winding up.
	 * <p>
	 * Full speed in about seventeen ticks -- just under a second. Slower than this and the machine feels
	 * like it is refusing; faster and there is no sense of mass at all, which is the thing that was
	 * missing.
	 */
	public static final double ACCELERATION = 0.008;

	/**
	 * And coming off the throttle, or changing your mind about which way you are going.
	 * <p>
	 * Faster than it accelerates, because that is what brakes are: from full speed to standing takes
	 * about seven ticks. It has to stay comfortably quicker than {@link #ACCELERATION} or the machine
	 * would coast like a boat, which is the one thing a tracked vehicle must never feel like.
	 */
	public static final double BRAKING = 0.02;

	//	=================================
	//		STEERING
	//	=================================

	/** Degrees a tick the undercarriage turns at full lock, standing still. */
	public static final double TURN_RATE = 2.6;

	/**
	 * Degrees a tick, per tick, that the turn winds up and unwinds.
	 * <p>
	 * A skid steer turns by driving one track against the other, and tracks take a moment to do that.
	 * Ramping is also what stops the yaw stepping: the heading is sent to clients as a single byte, so a
	 * turn that switches on at its full rate arrives as a stair rather than a curve. Unwinding is
	 * quicker than winding up for the same reason braking is -- letting go of A should stop the turn,
	 * not begin negotiating about it.
	 */
	public static final double TURN_ACCELERATION = 0.4;
	public static final double TURN_BRAKING = 0.9;

	/**
	 * How much of the turn rate is given up at full speed.
	 * <p>
	 * A tracked machine pivots on the spot when it is standing and describes an arc when it is moving,
	 * because the same track speed difference is a smaller fraction of the total. Without this the
	 * machine could carve a right angle at full throttle, which looks like an entity being rotated
	 * rather than a vehicle being driven.
	 */
	public static final double MOVING_TURN_PENALTY = 0.4;

	//	=================================
	//		GROUND
	//	=================================

	/**
	 * How much speed a full block of climb costs.
	 * <p>
	 * The step up itself is vanilla's: {@code stepHeight} is a block, so the machine mounts a kerb
	 * instead of stopping dead at it. What vanilla will not do is charge anything for it, and a machine
	 * that crosses a metre-high ledge without so much as slowing is one that feels weightless exactly
	 * where it should feel heaviest. Bleeding speed in proportion to the height gained also spreads the
	 * climb over more ticks, which is what makes it read as heaving itself up rather than teleporting.
	 */
	public static final double CLIMB_COST = 0.45;

	//	=================================
	//		CLIENT INTERPOLATION
	//	=================================

	/**
	 * Ticks a client takes to move the machine to where the server last said it was.
	 * <p>
	 * <strong>Three, and the number is a compromise with a wrong answer at each end.</strong> One is no
	 * interpolation at all -- the entity teleports on each packet, which is the state this machine
	 * shipped in. Ten is what a vanilla boat uses for boats it is not steering, and at ten the machine
	 * you are <em>driving</em> answers the throttle a third of a second late. Three smooths the arrival
	 * jitter of a packet a tick without putting a visible delay between the key and the machine.
	 */
	public static final int LERP_STEPS = 3;

	/**
	 * Beyond this many blocks of correction, stop interpolating and go there.
	 * <p>
	 * Interpolation is for the difference between where the client guessed and where the server knows.
	 * A dimension change, a teleport or a client that has been asleep is not that difference, and
	 * sliding smoothly across a hundred blocks would be a machine visibly flying through the terrain.
	 */
	public static final double SNAP_DISTANCE = 4.0;

	//	=================================
	//		THE ARITHMETIC
	//	=================================

	/**
	 * Move a rate towards the rate being asked for, at one of two speeds.
	 * <p>
	 * <strong>Two, because speeding up and slowing down are not the same thing.</strong> An engine can
	 * only push so hard and a brake can stop very much faster, so a single rate would have to be one or
	 * the other and would be wrong for the half it was not chosen for. Which of the two applies is a
	 * question about direction rather than sign: asking for more speed <em>in the direction already
	 * travelled</em> is acceleration, and everything else -- easing off, stopping, reversing through
	 * zero -- is braking. That falls out of the two conditions below and is what makes a change of mind
	 * at full speed brake to a stop and then accelerate away, rather than crawl backwards through zero
	 * on the accelerator.
	 * <p>
	 * Used for the throttle and for the steering both, because they are the same shape.
	 *
	 * @return the rate one tick later, never overshooting the target
	 */
	public static double ramp(double current, double target, double accelerating, double braking)
	{
		double step = Math.abs(target) > Math.abs(current)&&current*target >= 0
				?accelerating: braking;
		double delta = target-current;
		if(Math.abs(delta) <= step)
			return target;
		return current+Math.signum(delta)*step;
	}

	/**
	 * The speed a given throttle is asking for.
	 *
	 * @param throttle the rider's forward input, nominally -1 to 1 -- clamped, because a modded or
	 *                 sprinting rider's movement input is not guaranteed to be either of those and a
	 *                 machine that went faster for one of them would be a machine with an exploit in it
	 *
	 * @return blocks per tick, signed
	 */
	public static double targetSpeed(double throttle)
	{
		double wanted = CrawlerGeometry.clamp(throttle, -1, 1);
		return TOP_SPEED*wanted*(wanted < 0?REVERSE_FACTOR: 1);
	}

	/**
	 * The turn a given steering input is asking for, at the speed the machine is doing.
	 *
	 * @param steer the rider's strafe input, nominally -1 to 1
	 * @param speed blocks per tick, signed -- only its magnitude matters, since a machine reversing
	 *              turns no more easily than one going forwards
	 *
	 * @return degrees per tick, signed the way {@code rotationYaw} is
	 */
	public static double targetTurn(double steer, double speed)
	{
		double wanted = CrawlerGeometry.clamp(steer, -1, 1);
		double travelling = CrawlerGeometry.clamp(Math.abs(speed)/TOP_SPEED, 0, 1);
		//Negated: Minecraft's yaw increases to the left of the heading, and A is a left turn.
		return -wanted*TURN_RATE*(1-MOVING_TURN_PENALTY*travelling);
	}

	/**
	 * What the machine is really doing after a tick's worth of collision has had its say.
	 * <p>
	 * <strong>Without this the machine keeps a speed it never got to use.</strong> Held against a wall
	 * for two seconds it would build up -- and hold -- a full throttle's worth of momentum it had not
	 * travelled a millimetre on, and then spend all of it the instant the wall was dug away. The
	 * machine's speed is therefore reconciled with the ground it actually covered, which as a bonus is
	 * what makes it bog down against an obstacle instead of grinding into it at full power.
	 *
	 * @param speed     what the machine thought it was doing, signed
	 * @param travelled how far it got horizontally this tick, unsigned
	 * @param climbed   how much height it gained, unsigned; a drop costs nothing
	 *
	 * @return the speed to carry into the next tick
	 */
	public static double afterMove(double speed, double travelled, double climbed)
	{
		double moving = Math.abs(speed);
		//A whisker of tolerance: the move itself is floating point, and demanding an exact match would
		//shave the speed every tick on perfectly clear ground.
		if(moving > travelled+1e-4)
			moving = travelled;
		if(climbed > 0)
			moving *= 1-CLIMB_COST*CrawlerGeometry.clamp(climbed, 0, 1);
		return Math.signum(speed)*moving;
	}

	/**
	 * How many ticks a client should take to reach a position the server has just sent.
	 *
	 * @param correction how far away that position is, in blocks
	 *
	 * @return {@link #LERP_STEPS}, or one -- which is "be there next tick" -- for a correction too big
	 * to be worth smoothing
	 */
	public static int lerpSteps(double correction)
	{
		return correction > SNAP_DISTANCE?1: LERP_STEPS;
	}
}
