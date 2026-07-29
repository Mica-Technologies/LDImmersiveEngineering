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
	 * Where the operator sits, relative to the centre of the machine.
	 * <p>
	 * The cab is on the house, not on the tracks, so the seat travels round with the slew. Getting
	 * this wrong does not look wrong -- it puts the player three metres to the left of the machine,
	 * in the air, which reads as the seat being broken rather than as a sign error.
	 */
	public static final double CAB_SIDE = 0.85;
	public static final double CAB_FORWARD = 0.35;
	public static final double CAB_HEIGHT = 1.9;

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
}
