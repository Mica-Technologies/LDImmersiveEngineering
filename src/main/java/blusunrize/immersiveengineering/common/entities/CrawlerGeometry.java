/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

/**
 * The Hydraulic Crawler's arithmetic, with no world and no entity in sight.
 * <p>
 * <strong>Everything that can be a function of numbers lives here rather than on the entity.</strong>
 * The test harness has no Minecraft bootstrap and no client, so an {@code Entity} cannot even be
 * constructed in a test -- its constructor wants a {@code World}. Anything left on the entity is
 * therefore code that will never be run by anything but a player, and this machine is going to spend
 * its life deciding which of somebody's blocks to destroy. That is the wrong place for untested
 * trigonometry.
 * <p>
 * The same split {@code ConduitRoute} and {@code ConduitPlacement} use, for the same reason.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public final class CrawlerGeometry
{
	private CrawlerGeometry()
	{
	}

	/**
	 * How much bigger than one-to-one the machine is drawn.
	 * <p>
	 * The model is authored at Minecraft's usual sixteen units to the block, and then rendered half
	 * again as large: at one-to-one it read as a mini-digger rather than as something intimidating.
	 * Scaling at render time rather than by enlarging every box keeps the texture layout untouched --
	 * a box's UV footprint is a function of its size, so growing the boxes would mean relaying out
	 * the whole sheet and finding room for it. The cost is that each texel covers more of the screen,
	 * which on panelled steel reads as bigger panel lines and is if anything a gain.
	 * <p>
	 * <strong>Everything dimensional here is derived from this.</strong> The collision box, the seat
	 * and the model all have to agree about how big the machine is, and three independent copies of
	 * "one and a half" would eventually be two different numbers.
	 */
	public static final double SCALE = 1.5;

	/** Blocks per model unit. */
	public static final double UNIT = SCALE/16;

	//	=================================
	//		THE ARM, IN MODEL UNITS
	//	=================================

	/**
	 * The arm's section lengths -- the spacing between pivots, not the length of the boxes drawn.
	 * <p>
	 * <strong>Here rather than on the model, because the model is client-only.</strong> The server
	 * decides where the bucket is and therefore which blocks it destroys; it cannot ask a
	 * {@code @SideOnly(CLIENT)} class how long the boom is. The model reads these instead, so the
	 * picture and the arithmetic are the same numbers by construction rather than by agreement.
	 */
	public static final double BOOM_LENGTH = 26;
	public static final double STICK_LENGTH = 20;
	public static final double TOOL_LENGTH = 10;

	/**
	 * Height of the boom's pivot above the tracks, in model units: the slew ring at 14 plus the
	 * boom's mounting at 10 above that.
	 */
	public static final double BOOM_PIVOT_HEIGHT = 24;

	/** How far forward of the machine's centre the boom is pinned. */
	public static final double BOOM_PIVOT_OUT = 16;

	/**
	 * The collision footprint, in blocks, and it is <strong>square on purpose</strong>.
	 * <p>
	 * A 1.12 entity has exactly one collision box and it is axis-aligned: it cannot rotate, and there
	 * is no second box to describe an undercarriage pointing one way and a house pointing another. A
	 * square footprint is the same box at every angle, so neither steering nor slewing can make it
	 * wrong. Taken from the outer edges of the tracks, which span 48 model units.
	 */
	public static final double WIDTH = 48*UNIT;

	/** Ground to the top of the cab, 46 model units. The arm may go higher; the collider need not. */
	public static final double HEIGHT = 46*UNIT;

	/**
	 * Half the distance between the two tracks' centrelines, in model units.
	 * <p>
	 * What a skid steer turns about. The outside track of a turn covers this much extra ground
	 * per radian and the inside one covers that much less, which is how the two tracks are wound
	 * on at different rates so that a machine spinning on the spot still has running gear that
	 * is visibly running. The tracks span 12 to 24 units out from the centre, so their
	 * centrelines are at 18.
	 */
	public static final double TRACK_CENTRE = 18;

	/**
	 * Where the operator sits, relative to the centre of the machine.
	 * <p>
	 * <strong>Read off the model's cab box rather than guessed.</strong> The cab spans model x 1 to 15
	 * and z -14 to 4, so its centre is at (8, -5); the seat's floor is its lower edge, 28 units above
	 * the tracks. The first version of these numbers was three values that looked about right and were
	 * not any part of the model -- which put the seat outside the cab it was supposed to be inside.
	 * <p>
	 * The cab is on the house, not on the tracks, so the seat travels round with the slew.
	 */
	public static final double CAB_SIDE = 8*UNIT;
	public static final double CAB_FORWARD = 5*UNIT;
	public static final double CAB_HEIGHT = 28*UNIT;

	/**
	 * The cab's walls, in blocks, as world offsets at zero slew -- the box the seat has to be inside.
	 * <p>
	 * Here so a test can say so. The seat and the cab were independent numbers once and the seat was
	 * outside the cab; nothing in the game reports that, because a passenger positioned in mid-air
	 * beside a machine looks exactly like a passenger positioned badly on purpose.
	 */
	public static final double CAB_MIN_X = 1*UNIT, CAB_MAX_X = 15*UNIT;
	public static final double CAB_MIN_Z = -4*UNIT, CAB_MAX_Z = 14*UNIT;

	/**
	 * The seat's horizontal offset from the machine's centre at a given slew.
	 *
	 * @param slewDegrees where the house is pointing
	 *
	 * @return {@code {x, z}}
	 */
	public static double[] cabOffset(double slewDegrees)
	{
		double radians = Math.toRadians(slewDegrees);
		double sin = Math.sin(radians), cos = Math.cos(radians);
		//Written out rather than going through Vec3d.rotateYaw, which takes radians in the opposite
		//sense: the sign error that produces is invisible in the code and obvious in the world.
		return new double[]{CAB_SIDE*cos-CAB_FORWARD*sin, CAB_SIDE*sin+CAB_FORWARD*cos};
	}

	/**
	 * The unit vector a machine at that heading drives along.
	 * <p>
	 * Minecraft's yaw convention, which is worth stating because it is not the one trigonometry
	 * expects: zero looks along <em>positive Z</em>, and X runs on the <em>negative</em> sine.
	 *
	 * @return {@code {x, z}}
	 */
	public static double[] heading(double yawDegrees)
	{
		double radians = Math.toRadians(yawDegrees);
		return new double[]{-Math.sin(radians), Math.cos(radians)};
	}

	/**
	 * The shortest signed turn from one heading to another, in degrees.
	 * <p>
	 * Used wherever an angle is interpolated or compared. Plain subtraction takes the long way round
	 * whenever the value wraps, so a house slewing past north spins the wrong way once per wrap --
	 * a whole revolution of visible glitch from an arithmetic detail.
	 */
	public static double shortestTurn(double fromDegrees, double toDegrees)
	{
		double delta = (toDegrees-fromDegrees)%360;
		if(delta > 180)
			delta -= 360;
		if(delta < -180)
			delta += 360;
		return delta;
	}

	/**
	 * Move an angle towards a target by at most {@code maxStep} degrees.
	 * <p>
	 * Hydraulics have a speed. Snapping a joint straight to its solved angle would look like a glitch
	 * rather than a machine, and it matters for more than looks: the moment the tool destroys what it
	 * touches, an arm that can teleport is an arm that reaches through a wall in one tick and takes
	 * the far side of it. Rate limiting is what makes the tip's path continuous, and a continuous path
	 * is the only kind that can be swept for what it passes through.
	 */
	public static double approach(double current, double target, double maxStep)
	{
		double delta = shortestTurn(current, target);
		if(Math.abs(delta) <= maxStep)
			return target;
		return current+Math.signum(delta)*maxStep;
	}

	/**
	 * Clamp, spelled out because {@code Math.min(Math.max(..))} at three arguments reads as noise at
	 * every call site and this is called at most of them.
	 */
	public static double clamp(double value, double min, double max)
	{
		return value < min?min: value > max?max: value;
	}
}
