/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

/**
 * Where the Hydraulic Crawler's arm goes when the operator looks somewhere.
 * <p>
 * <strong>The arm is aimed, not driven joint by joint.</strong> The operator's pitch says how far
 * down they are looking, and the arm solves its own boom and stick angles to put the tool there. That
 * was chosen over a key per joint for a reason worth restating: a rider's pitch is <em>already</em>
 * synchronised by vanilla, so the server and the client solve the same angles from the same input and
 * cannot disagree about where the tool is. Once the tool is what destroys blocks, a disagreement about
 * its position is a disagreement about whose wall just fell down.
 *
 * <h2>The plane</h2>
 * Everything here happens in the vertical plane the house is facing, in model units, with the boom's
 * pivot at the origin. Two axes:
 * <ul>
 * <li><strong>out</strong> -- away from the machine, along the house's facing</li>
 * <li><strong>down</strong> -- towards the ground</li>
 * </ul>
 * "Down" rather than "up" because it matches the model: a positive {@code rotateAngleX} on the boom
 * lowers it, so an angle in this plane is directly the number the model wants. Every sign error this
 * code could have had was a choice to measure one of the two the other way round.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public final class CrawlerArm
{
	private CrawlerArm()
	{
	}

	/**
	 * How far up and down the arm can be aimed.
	 * <p>
	 * Wide, because the first version was not: {@code -40} put the tool barely head height, and a
	 * machine that cannot lift above a wall is no use for taking one down.
	 */
	public static final double MIN_DEPRESSION = -62;
	/** And down. Steeper than this and the machine would be digging under its own tracks. */
	public static final double MAX_DEPRESSION = 75;

	/**
	 * How far the elbow is held from the boom's pivot -- a constant, so the arm sweeps an arc.
	 * <p>
	 * <strong>This has now been all three shapes, and the reasoning is worth keeping.</strong>
	 * <p>
	 * A reach that <em>shrank</em> with depression came first. It broke: the target's height is
	 * {@code reach * sin(depression)}, whose derivative goes negative once the shrinking outpaces the
	 * angle, so past about sixty degrees looking further down <em>raised</em> the bucket.
	 * <p>
	 * A straight line from "far out and high" to "close in and deep" fixed that by being monotonic in
	 * both axes by construction. But a straight line between two points that are both close in is
	 * close in all the way along, so widening the range to reach higher gave up most of the horizontal
	 * reach in the middle -- the arm could go up and it could go down and it could no longer stretch
	 * out.
	 * <p>
	 * A constant reach is the shape that gives all three. Height is {@code REACH * sin(depression)},
	 * monotonic for any angle inside a quarter turn because the reach no longer varies to fight it;
	 * the arm is furthest out at level, which is where an operator wants the stretch; and the range is
	 * limited only by the angles, so it can be as wide as the boom can swing. It is also what the
	 * machine actually does -- an arm on one control traces an arc.
	 * <p>
	 * Forty-two against an arm of forty-six: nearly straight out at level, which is both the most
	 * reach available and how a real one looks doing it.
	 */
	public static final double REACH = 42;

	/** Degrees a joint may move in one tick. Hydraulics are not instant. */
	public static final double JOINT_RATE = 4.0;

	/**
	 * Kept a hair inside the arm's true limits. A two-link solve is singular when the target is
	 * exactly at full stretch or exactly at the fold, and acos of a value a whisker outside [-1, 1]
	 * from rounding is NaN -- which propagates into the tool's position and, later, into the set of
	 * blocks to destroy.
	 */
	private static final double SINGULARITY_MARGIN = 0.5;

	/**
	 * Solve the arm for a given view pitch.
	 *
	 * @param pitchDegrees the operator's pitch: negative looking up, positive looking down, which is
	 *                     Minecraft's convention and happens to be the same sense as depression here
	 *
	 * @return {@code {boom, stick, tool}} in degrees, as the model's rotateAngleX wants them: the boom
	 * absolute, the stick relative to the boom, the tool relative to the stick
	 */
	public static double[] solve(double pitchDegrees)
	{
		double[] target = targetFor(pitchDegrees);
		double bearing = Math.toDegrees(Math.atan2(target[1], target[0]));

		double l1 = CrawlerGeometry.BOOM_LENGTH;
		double l2 = CrawlerGeometry.STICK_LENGTH;
		//Clamped into what two links of these lengths can actually span. REACH_FAR was once fifty
		//against an arm forty-six long, so the arm silently stopped where it ran out and the aim was
		//quietly wrong everywhere near level -- which the round-trip test caught and nothing else would.
		double d = CrawlerGeometry.clamp(Math.hypot(target[0], target[1]),
				Math.abs(l1-l2)+SINGULARITY_MARGIN, l1+l2-SINGULARITY_MARGIN);

		//Law of cosines, twice. The interior angle at the elbow, and the angle between the boom and
		//the straight line to the target.
		double interior = Math.acos(CrawlerGeometry.clamp(
				(l1*l1+l2*l2-d*d)/(2*l1*l2), -1, 1));
		double alpha = Math.acos(CrawlerGeometry.clamp(
				(l1*l1+d*d-l2*l2)/(2*l1*d), -1, 1));

		//Boom above the line to the target, so the elbow bends downward -- an excavator carries its
		//boom up and its stick angled down, and the other solution of a two-link IK is that posture
		//upside down.
		double boom = bearing-Math.toDegrees(alpha);
		//Zero when the arm is straight, which is what interior == 180 degrees means.
		double stick = 180-Math.toDegrees(interior);

		//The tool hangs at a fixed attitude rather than being solved: it wants to point at the ground
		//whatever the rest of the arm is doing, which is what a bucket does when it is not being
		//curled. Relative, because the model's angles are.
		double tool = CrawlerGeometry.clamp(TOOL_ATTITUDE-(boom+stick), -140, 140);
		return new double[]{boom, stick, tool};
	}

	/** Absolute depression the tool is held at: straight down, so it presents its edge to the ground. */
	public static final double TOOL_ATTITUDE = 90;

	/**
	 * Where the elbow is being aimed for a given view pitch, in the arm's plane.
	 * <p>
	 * The <em>elbow</em>, not the tool's tip: the tool is deliberately outside the solve, hanging at a
	 * fixed attitude, so this is the point the two solved joints are asked to reach. Separated out so a
	 * test can hold the solver to it -- "did the arm arrive where it was aimed" is not a question the
	 * law of cosines answers on its own once clamps are in the way.
	 *
	 * @return {@code {out, down}} in model units
	 */
	public static double[] targetFor(double pitchDegrees)
	{
		double depression = CrawlerGeometry.clamp(pitchDegrees, MIN_DEPRESSION, MAX_DEPRESSION);
		double radians = Math.toRadians(depression);
		return new double[]{REACH*Math.cos(radians), REACH*Math.sin(radians)};
	}

	/**
	 * Forward kinematics: where the tool's tip ends up, in the arm's plane, from the boom's pivot.
	 * <p>
	 * The other half of the solver, and the half that matters most later: this is the point that will
	 * decide which blocks get broken. Kept separate from {@link #solve} so a test can put the two
	 * together and check that the arm actually arrives where it was aimed -- which is not something
	 * the law of cosines guarantees on its own once clamps are involved.
	 *
	 * @return {@code {out, down}} in model units
	 */
	public static double[] tipInPlane(double boom, double stick, double tool)
	{
		return alongArm(boom, stick, tool, TOTAL_LENGTH);
	}

	/** Pivot to tool tip, following the steel rather than the straight line. */
	public static final double TOTAL_LENGTH =
			CrawlerGeometry.BOOM_LENGTH+CrawlerGeometry.STICK_LENGTH+CrawlerGeometry.TOOL_LENGTH;

	/**
	 * A point some distance along the arm's centreline, measured from the boom's pivot and following
	 * each section in turn.
	 * <p>
	 * The general case of {@link #tipInPlane}, and what the arm's hitboxes are hung off: a folded arm
	 * is a shape, not a line, so knowing only where the tip is says nothing about where the middle of
	 * the boom went. Walking the sections is also the only way to get points that stay on the steel
	 * when the arm bends -- interpolating between the pivot and the tip would put them in mid-air
	 * across the inside of the elbow.
	 *
	 * @param distance along the arm in model units, clamped to the arm
	 *
	 * @return {@code {out, down}} in model units
	 */
	public static double[] alongArm(double boom, double stick, double tool, double distance)
	{
		double remaining = CrawlerGeometry.clamp(distance, 0, TOTAL_LENGTH);
		double out = 0, down = 0;
		double[] angles = {boom, boom+stick, boom+stick+tool};
		double[] lengths = {CrawlerGeometry.BOOM_LENGTH, CrawlerGeometry.STICK_LENGTH,
				CrawlerGeometry.TOOL_LENGTH};
		for(int i = 0; i < 3&&remaining > 0; i++)
		{
			double span = Math.min(remaining, lengths[i]);
			double radians = Math.toRadians(angles[i]);
			out += span*Math.cos(radians);
			down += span*Math.sin(radians);
			remaining -= span;
		}
		return new double[]{out, down};
	}

	/**
	 * A point along the arm as an offset from the entity's position, in blocks.
	 *
	 * @return {@code {x, y, z}}
	 */
	public static double[] armPointOffset(double slewDegrees, double boom, double stick, double tool,
										  double distance)
	{
		double[] plane = alongArm(boom, stick, tool, distance);
		double out = CrawlerGeometry.BOOM_PIVOT_OUT+plane[0];
		double height = CrawlerGeometry.BOOM_PIVOT_HEIGHT-plane[1];
		double[] facing = CrawlerGeometry.heading(slewDegrees);
		return new double[]{
				facing[0]*out*CrawlerGeometry.UNIT,
				height*CrawlerGeometry.UNIT,
				facing[1]*out*CrawlerGeometry.UNIT};
	}

	/**
	 * The tool's tip as an offset from the entity's own position, in blocks.
	 * <p>
	 * The plane's "out" swung round to the house's heading, its "down" turned back into a height, and
	 * the whole thing scaled out of model units. This is the number the world cares about.
	 *
	 * @param slewDegrees where the house is pointing, in world terms
	 *
	 * @return {@code {x, y, z}} offsets in blocks
	 */
	public static double[] tipOffset(double slewDegrees, double boom, double stick, double tool)
	{
		double[] plane = tipInPlane(boom, stick, tool);
		//The pivot is forward of the machine's centre and above its tracks, so the tip is measured from
		//there rather than from the entity's origin.
		double out = CrawlerGeometry.BOOM_PIVOT_OUT+plane[0];
		double height = CrawlerGeometry.BOOM_PIVOT_HEIGHT-plane[1];
		double[] facing = CrawlerGeometry.heading(slewDegrees);
		return new double[]{
				facing[0]*out*CrawlerGeometry.UNIT,
				height*CrawlerGeometry.UNIT,
				facing[1]*out*CrawlerGeometry.UNIT};
	}

	/**
	 * Step the three joints towards a solved pose at the speed hydraulics move.
	 *
	 * @param current {@code {boom, stick, tool}} now
	 * @param target  {@code {boom, stick, tool}} wanted
	 *
	 * @return the pose one tick later
	 */
	public static double[] step(double[] current, double[] target)
	{
		double[] next = new double[3];
		for(int i = 0; i < 3; i++)
			next[i] = CrawlerGeometry.approach(current[i], target[i], JOINT_RATE);
		return next;
	}
}
