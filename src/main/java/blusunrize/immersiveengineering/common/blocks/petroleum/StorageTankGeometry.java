/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * The rules for a Storage Tank: a hollow box of any rectangular shape, whose capacity is what it
 * encloses.
 * <p>
 * The three buried tiers are fixed sizes, which is right for something you bury and forget and
 * wrong for a tank farm you are trying to fit against a building. This is the other kind: build the
 * shell you want, hammer it, and it holds what its inside would hold.
 * <p>
 * <strong>Walls only.</strong> A shell of a 9x9x9 is 386 blocks against 729, and a shell is what a
 * tank actually is -- the Bulk Depot already made this call for the same reasons, and this follows
 * it. Only the wall cells are ever blocks; the enclosed volume is a number, not a region.
 * <p>
 * World-free arithmetic, so every rule here can be checked without placing anything.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class StorageTankGeometry
{
	private StorageTankGeometry()
	{
	}

	/**
	 * The smallest tank that has an inside. Two of anything is all wall and holds nothing, and a
	 * tank that reported zero capacity would be a puzzle rather than a mistake anybody could see.
	 */
	public static final int MIN_SIDE = 3;

	/**
	 * The largest side length. Bounds the search that runs when somebody hammers a wall, which is
	 * the only reason there is a limit at all -- a tank this size is 1538 blocks of steel and
	 * nobody is building one by accident.
	 */
	public static final int MAX_SIDE = 16;

	/**
	 * What one enclosed cell holds. Sixteen buckets, chosen so the familiar sizes land somewhere
	 * sensible: a 3x3x3 holds 16k -- half a Domestic tank -- and a 5x5x5 holds 432k, just under the
	 * Sheetmetal Tank it stands next to. A 9x9x9 holds 5.5M, comfortably past the Bulk Depot, which
	 * is the right shape of reward for 386 blocks of steel.
	 */
	public static final int CAPACITY_PER_CELL = 16000;

	public static boolean isValidSide(int side)
	{
		return side >= MIN_SIDE&&side <= MAX_SIDE;
	}

	public static boolean isValid(int width, int height, int depth)
	{
		return isValidSide(width)&&isValidSide(height)&&isValidSide(depth);
	}

	/**
	 * @return how many cells are inside the shell
	 */
	public static int innerVolume(int width, int height, int depth)
	{
		if(!isValid(width, height, depth))
			return 0;
		return (width-2)*(height-2)*(depth-2);
	}

	/**
	 * @return how many blocks the shell itself is made of
	 */
	public static int shellCount(int width, int height, int depth)
	{
		if(!isValid(width, height, depth))
			return 0;
		return width*height*depth-innerVolume(width, height, depth);
	}

	/**
	 * The tank's capacity in millibuckets.
	 * <p>
	 * Computed as a long and clamped, because a 16x16x16 holds 39,200,000 mB -- fine in an int, but
	 * one careless change to {@link #CAPACITY_PER_CELL} away from not being, and a capacity that
	 * silently wrapped negative would let a tank swallow the world.
	 */
	public static int capacity(int width, int height, int depth)
	{
		long cells = innerVolume(width, height, depth);
		long capacity = cells*CAPACITY_PER_CELL;
		return capacity > Integer.MAX_VALUE?Integer.MAX_VALUE: (int)capacity;
	}

	/**
	 * Whether a cell of the bounding box is part of the shell rather than the space inside it.
	 *
	 * @param x 0-based, along the width
	 * @param y 0-based, along the height
	 * @param z 0-based, along the depth
	 */
	public static boolean isShell(int x, int y, int z, int width, int height, int depth)
	{
		return x==0||y==0||z==0||x==width-1||y==height-1||z==depth-1;
	}

	/**
	 * How full a tank is, on a comparator's fifteen steps.
	 * <p>
	 * Longs throughout: the largest tank holds nearly forty million millibuckets, and
	 * {@code amount*15} in an int overflows well before that.
	 */
	public static int comparatorLevel(int amount, int capacity)
	{
		if(amount <= 0||capacity <= 0)
			return 0;
		//Anything at all reads as at least one, so "some" and "none" are never the same picture.
		return (int)Math.max(1, Math.min(15, (long)amount*15/capacity));
	}
}
