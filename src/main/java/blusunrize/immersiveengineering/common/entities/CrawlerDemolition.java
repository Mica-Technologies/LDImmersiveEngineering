/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which blocks a Crawler's attachment is touching, and which of them it may break.
 * <p>
 * <strong>The world-free half of demolition, and the half worth testing.</strong> Everything here is
 * a function of a position, a radius and a number; the part that actually removes blocks is a dozen
 * lines on the entity and cannot be tested at all without a world. Splitting it this way means the
 * decisions -- how many, which order, how hard is too hard -- are all covered, and what is left
 * untested is the part with no decisions in it.
 * <p>
 * This is a machine that deletes people's buildings. The arithmetic deciding what it deletes should
 * not be the part nobody looked at.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public final class CrawlerDemolition
{
	private CrawlerDemolition()
	{
	}

	/**
	 * How hard a block may be and still come apart.
	 * <p>
	 * Obsidian's hardness exactly: "anything a diamond pick could take" was the rule chosen, and
	 * obsidian is where that ends. Blocks reporting a negative hardness are unbreakable by definition
	 * -- bedrock, the portal frame -- and are refused separately rather than by being above a number.
	 */
	public static final float MAX_HARDNESS = 50F;

	/** How far from the tool's tip a block can be and still count as touched. */
	public static final double REACH = 1.15;

	/** How many blocks come out in one bite. */
	public static final int BUDGET = 3;

	/** And how long until the next bite. */
	public static final int COOLDOWN = 10;

	/**
	 * @param hardness as {@code IBlockState.getBlockHardness} reports it
	 *
	 * @return whether the Breaker is allowed to take a block of that hardness
	 */
	public static boolean withinHardnessLimit(float hardness)
	{
		//Negative means unbreakable and must be refused before the comparison, not by it: bedrock's -1
		//is less than fifty, so a naive ceiling check lets the machine dig through the world floor.
		return hardness >= 0&&hardness <= MAX_HARDNESS;
	}

	/**
	 * The blocks an attachment at this point is touching, nearest first.
	 * <p>
	 * Nearest first because the budget is small: a machine that took an arbitrary three of the blocks
	 * around its bucket would chew holes through a wall in a random pattern, where one that takes the
	 * three closest eats into it from the face it is pressed against. That is the difference between
	 * looking like a machine and looking like a bug.
	 * <p>
	 * The order is fully determined -- distance, then coordinate -- so the same bite twice takes the
	 * same blocks. An unstable order would make the machine's behaviour unrepeatable and this function
	 * untestable, which for the thing that chooses what to destroy is not acceptable.
	 *
	 * @param limit the most positions to return, being the per-bite budget
	 *
	 * @return positions whose centres lie within {@link #REACH} of the point
	 */
	public static List<BlockPos> targetsAround(double x, double y, double z, double reach, int limit)
	{
		List<BlockPos> found = new ArrayList<>();
		if(limit <= 0||reach <= 0)
			return found;
		int minX = (int)Math.floor(x-reach), maxX = (int)Math.floor(x+reach);
		int minY = (int)Math.floor(y-reach), maxY = (int)Math.floor(y+reach);
		int minZ = (int)Math.floor(z-reach), maxZ = (int)Math.floor(z+reach);
		double reachSquared = reach*reach;
		for(int bx = minX; bx <= maxX; bx++)
			for(int by = minY; by <= maxY; by++)
				for(int bz = minZ; bz <= maxZ; bz++)
				{
					//Measured to the block's centre, so "touching" means the tool is actually in it
					//rather than merely in the same cubic region of space.
					double dx = bx+0.5-x, dy = by+0.5-y, dz = bz+0.5-z;
					if(dx*dx+dy*dy+dz*dz <= reachSquared)
						found.add(new BlockPos(bx, by, bz));
				}
		found.sort(Comparator
				.comparingDouble((BlockPos pos) -> squaredDistance(pos, x, y, z))
				//Ties broken by coordinate, so the order does not depend on which way the loops ran or
				//on the sort being stable.
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getZ));
		return found.size() > limit?new ArrayList<>(found.subList(0, limit)): found;
	}

	private static double squaredDistance(BlockPos pos, double x, double y, double z)
	{
		double dx = pos.getX()+0.5-x, dy = pos.getY()+0.5-y, dz = pos.getZ()+0.5-z;
		return dx*dx+dy*dy+dz*dz;
	}
}
