/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

/**
 * The arithmetic behind the Seismic Survey Kit: which way, and how far.
 * <p>
 * Prospecting with a Core Sample Drill answers "is there oil <em>here</em>", one cell at a time,
 * and a cell is eight chunks across. Finding a field that way means building a drill, reading it,
 * walking a hundred and twenty-eight blocks and building another -- which is fine as the precise
 * answer and hopeless as the search. The survey kit is the search: it fires a charge, reads the
 * cells around it, and tells the player which direction to walk.
 * <p>
 * Kept free of {@code World} and of Minecraft entirely so the banding, the bearings and the
 * distances can be checked directly. A survey that pointed the wrong way would send a player on a
 * four-hundred-block walk to nothing, and nothing in game would ever say why.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class SeismicSurvey
{
	private SeismicSurvey()
	{
	}

	/**
	 * How many cells out from the player's own the kit reads, in each direction.
	 * <p>
	 * One, giving a three by three block of cells. At the default cell size that is three hundred
	 * and eighty-four blocks across, which is far enough to be worth the charge and near enough
	 * that walking to what it finds is a decision rather than an expedition.
	 */
	public static final int CELL_RADIUS = 1;

	/**
	 * Compass points, in the order {@link #bearing} indexes them: clockwise from north.
	 */
	public static final String[] BEARINGS = {
			"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"
	};

	/**
	 * @param dx east-positive offset to the target
	 * @param dz south-positive offset to the target
	 * @return one of {@link #BEARINGS}, or "here" when the target is where the player is standing
	 */
	public static String bearing(int dx, int dz)
	{
		if(dx==0&&dz==0)
			return "here";
		//North is -Z in Minecraft, so the angle is measured from -Z towards +X to make "clockwise
		//from north" come out as clockwise on the player's map rather than mirrored.
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		if(angle < 0)
			angle += 360;
		//Rounded to the nearest eighth rather than floored: a target one degree east of north
		//should read as north, not as north-east.
		int index = (int)Math.round(angle/45.0)%BEARINGS.length;
		return BEARINGS[index];
	}

	/**
	 * @return the straight-line distance in blocks, rounded to the nearest ten. A survey is a
	 * bearing and a rough range, not a coordinate: reporting "372 blocks" would imply a precision
	 * the reading does not have, and would make the Core Sample Drill pointless.
	 */
	public static int roundedDistance(int dx, int dz)
	{
		double exact = Math.sqrt((double)dx*dx+(double)dz*dz);
		return (int)(Math.round(exact/10.0)*10);
	}

	/**
	 * @param cellChunkSize edge of a cell in chunks
	 * @return the block coordinate of the middle of a cell, on one axis
	 */
	public static int cellCentreBlock(int cell, int cellChunkSize)
	{
		int size = Math.max(1, cellChunkSize);
		//Cell to chunk to block, then half a cell along to land in the middle rather than on the
		//corner -- a bearing to a corner is off by up to forty-five degrees on a near cell.
		return cell*size*16+size*8;
	}
}
