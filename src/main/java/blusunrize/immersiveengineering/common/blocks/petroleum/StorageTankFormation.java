/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * Finding the tank a player just hammered.
 * <p>
 * A fixed-size multiblock knows its own shape and only has to check whether the blocks are there.
 * A free-form one has to work the shape out first, from one block somewhere on its surface, and
 * that is what this does: grow a bounding box out from the struck block along each axis for as long
 * as tank blocks continue, then check the result really is a hollow shell.
 * <p>
 * Growing before checking matters. Searching for "some hollow box containing this block" would have
 * to consider every box the block could belong to, which is cubic in the size limit; growing gives
 * one candidate and then verifies it.
 * <p>
 * World-free -- the world arrives as a {@link Probe} -- so the search can be tested against a
 * drawn shape rather than a built one.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class StorageTankFormation
{
	private StorageTankFormation()
	{
	}

	public interface Probe
	{
		/**
		 * @return true if that position holds an unformed tank block. A block already part of
		 * another tank must report false, or two tanks sharing a wall would merge into one.
		 */
		boolean isFreeTankBlock(BlockPos pos);
	}

	/**
	 * A found tank: where its corner is and how big it is.
	 */
	public static final class Found
	{
		public final BlockPos origin;
		public final int width, height, depth;

		Found(BlockPos origin, int width, int height, int depth)
		{
			this.origin = origin;
			this.width = width;
			this.height = height;
			this.depth = depth;
		}

		public int capacity()
		{
			return StorageTankGeometry.capacity(width, height, depth);
		}
	}

	/**
	 * @param struck any block of the intended shell
	 * @return the tank, or null if what is there is not a complete hollow box
	 */
	@Nullable
	public static Found find(BlockPos struck, Probe probe)
	{
		if(!probe.isFreeTankBlock(struck))
			return null;

		int[] x = spanAlong(struck, 1, 0, 0, probe);
		int[] y = spanAlong(struck, 0, 1, 0, probe);
		int[] z = spanAlong(struck, 0, 0, 1, probe);

		BlockPos origin = new BlockPos(struck.getX()-x[0], struck.getY()-y[0], struck.getZ()-z[0]);
		int width = x[0]+x[1]+1;
		int height = y[0]+y[1]+1;
		int depth = z[0]+z[1]+1;

		if(!StorageTankGeometry.isValid(width, height, depth))
			return null;
		if(!isCompleteShell(origin, width, height, depth, probe))
			return null;
		return new Found(origin, width, height, depth);
	}

	/**
	 * How far the tank reaches either side of the struck block along one axis.
	 * <p>
	 * <strong>Two cases, and missing the second one is the mistake this method exists to not
	 * make.</strong> Along an axis the struck block lies <em>in</em> -- an edge running that way,
	 * or a face parallel to it -- the tank blocks are contiguous and simply counting them is
	 * right. Along the axis the struck block's face is perpendicular to, the very next cell is the
	 * hollow inside, so counting stops at one and the tank looks a block wide.
	 * <p>
	 * In that second case the extent is found by looking across the gap for the opposite wall.
	 * Only one direction can hold it -- the other is outside the tank -- so whichever finds a block
	 * first gives the span.
	 *
	 * @return {before, after}: steps of tank the box extends in the negative and positive directions
	 */
	private static int[] spanAlong(BlockPos struck, int dx, int dy, int dz, Probe probe)
	{
		int before = extend(struck, -dx, -dy, -dz, probe);
		int after = extend(struck, dx, dy, dz, probe);
		if(before+after+1 >= StorageTankGeometry.MIN_SIDE)
			return new int[]{before, after};

		//Collapsed: the struck block is on a wall facing along this axis. Look across the inside
		//for the far wall.
		int across = findAcross(struck, dx, dy, dz, probe);
		if(across > 0)
			return new int[]{0, across};
		across = findAcross(struck, -dx, -dy, -dz, probe);
		if(across > 0)
			return new int[]{across, 0};
		return new int[]{before, after};
	}

	/**
	 * @return the distance to the next tank block in that direction across a gap, or 0 if there is
	 * none within the size limit
	 */
	private static int findAcross(BlockPos from, int dx, int dy, int dz, Probe probe)
	{
		for(int step = 2; step < StorageTankGeometry.MAX_SIDE; step++)
			if(probe.isFreeTankBlock(from.add(dx*step, dy*step, dz*step)))
				return step;
		return 0;
	}

	/**
	 * @return how many steps that direction runs before leaving the tank
	 */
	private static int extend(BlockPos from, int dx, int dy, int dz, Probe probe)
	{
		int steps = 0;
		BlockPos at = from;
		while(steps < StorageTankGeometry.MAX_SIDE)
		{
			BlockPos next = at.add(dx, dy, dz);
			if(!probe.isFreeTankBlock(next))
				break;
			at = next;
			steps++;
		}
		return steps;
	}

	/**
	 * Every wall cell is a tank block, and -- just as importantly -- every inside cell is not.
	 * <p>
	 * The second half is what stops a solid cube of tank blocks forming as though it were hollow,
	 * which would give a player the capacity of a box they had actually filled in.
	 */
	public static boolean isCompleteShell(BlockPos origin, int width, int height, int depth,
										  Probe probe)
	{
		for(int x = 0; x < width; x++)
			for(int y = 0; y < height; y++)
				for(int z = 0; z < depth; z++)
				{
					boolean shell = StorageTankGeometry.isShell(x, y, z, width, height, depth);
					boolean present = probe.isFreeTankBlock(origin.add(x, y, z));
					if(shell!=present)
						return false;
				}
		return true;
	}
}
