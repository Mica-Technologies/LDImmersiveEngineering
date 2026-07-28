/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;

import javax.annotation.Nullable;

/**
 * Where a conduit may run and what shape that makes it.
 * <p>
 * A conduit is <em>surface mounted</em>: it lies flat against the face of the block it is clipped
 * to and runs in the plane of that face, turning in right angles. That single sentence is what
 * every rule here follows from, and it is the whole reason conduits exist -- IE's wires are
 * catenaries, which are right across a valley and wrong along a ceiling.
 * <p>
 * <strong>Runs stay in one plane.</strong> A conduit on the floor does not climb the wall by
 * itself; a plane change goes through a junction box, which is a real block and the thing the
 * reference image puts at every corner anyway. That is a design decision rather than a limitation
 * worked around: an in-plane run is four possible neighbours and an axis-aligned box, where a
 * plane-changing one is an L-shaped transition per pair of faces, which is where the fiddliness of
 * this whole feature would otherwise live.
 * <p>
 * World-free on purpose, like the rest of the conduit code -- the rules are arithmetic on facings,
 * so they can be tested without a game running.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitGeometry
{
	private ConduitGeometry()
	{
	}

	/**
	 * How many neighbours a conduit can have: the four directions in its own plane. The two along
	 * the mounting axis are excluded -- one is the surface it is clipped to, and the other is the
	 * open air a plane change would go through, which is a junction box's job.
	 */
	public static final int ARMS = 4;

	/**
	 * The four in-plane directions for each mount face, indexed by {@code mount.ordinal()}.
	 * <p>
	 * Derived from {@code EnumFacing.values()} order rather than chosen, so the ordering is stable
	 * against anything but a Minecraft change: the index of a direction ends up in generated
	 * blockstate names, and a reshuffle would silently repoint every model.
	 */
	private static final EnumFacing[][] IN_PLANE = new EnumFacing[6][];

	static
	{
		for(EnumFacing mount : EnumFacing.VALUES)
		{
			EnumFacing[] plane = new EnumFacing[ARMS];
			int i = 0;
			for(EnumFacing dir : EnumFacing.VALUES)
				if(dir.getAxis()!=mount.getAxis())
					plane[i++] = dir;
			IN_PLANE[mount.ordinal()] = plane;
		}
	}

	/**
	 * @return the four directions a conduit on that face may run in. The array belongs to this
	 * class -- read it, do not write to it.
	 */
	public static EnumFacing[] inPlane(EnumFacing mount)
	{
		return IN_PLANE[mount.ordinal()];
	}

	public static boolean isInPlane(EnumFacing mount, @Nullable EnumFacing dir)
	{
		return dir!=null&&dir.getAxis()!=mount.getAxis();
	}

	/**
	 * @return where that direction sits in {@link #inPlane}, or -1 if it is not in the plane at all
	 */
	public static int armIndex(EnumFacing mount, @Nullable EnumFacing dir)
	{
		if(!isInPlane(mount, dir))
			return -1;
		EnumFacing[] plane = IN_PLANE[mount.ordinal()];
		for(int i = 0; i < plane.length; i++)
			if(plane[i]==dir)
				return i;
		return -1;
	}

	/**
	 * Whether two conduits, adjacent in the world, join up.
	 * <p>
	 * They do when they lie on the same surface and the step from one to the other runs along it.
	 * Two conduits on different faces meeting at a corner do <em>not</em> join -- put a junction
	 * box there. Saying so plainly beats a run that looks continuous and is not.
	 *
	 * @param mount     the face this conduit is clipped to
	 * @param otherMount the face the neighbour is clipped to
	 * @param towards   the direction from this conduit to the neighbour
	 */
	public static boolean connects(EnumFacing mount, @Nullable EnumFacing otherMount,
								   @Nullable EnumFacing towards)
	{
		return otherMount==mount&&isInPlane(mount, towards);
	}

	/**
	 * What a run looks like where it passes through one block, for readouts and for deciding which
	 * pieces of model to draw.
	 */
	public enum Shape
	{
		/** Clipped to a wall with nothing joined to it -- a run somebody has started. */
		BARE,
		/** One neighbour: the end of a run. */
		END,
		/** Two opposite neighbours: a straight length. */
		STRAIGHT,
		/** Two neighbours at right angles: the corner the whole look is built around. */
		CORNER,
		/** Three. */
		TEE,
		/** Four. */
		CROSS
	}

	/**
	 * @param mask which arms are joined, one bit per {@link #inPlane} index
	 */
	public static Shape shapeOf(EnumFacing mount, int mask)
	{
		switch(Integer.bitCount(mask&0xF))
		{
			case 0:
				return Shape.BARE;
			case 1:
				return Shape.END;
			case 2:
				//Opposite arms make a straight; adjacent ones make a corner. Comparing the two
				//joined directions is clearer than any bit trick, and this is not a hot path.
				EnumFacing[] plane = IN_PLANE[mount.ordinal()];
				EnumFacing first = null;
				for(int i = 0; i < ARMS; i++)
					if((mask&(1 << i))!=0)
					{
						if(first==null)
							first = plane[i];
						else
							return first.getOpposite()==plane[i]?Shape.STRAIGHT: Shape.CORNER;
					}
				return Shape.BARE;
			case 3:
				return Shape.TEE;
			default:
				return Shape.CROSS;
		}
	}

	/**
	 * The name a generated model file uses for one arm.
	 * <p>
	 * Lower case and mount-first, matching the blockstate this fork's asset script writes. It lives
	 * here rather than in the script so that a test can check the block and the assets agree
	 * without either of them guessing at the other's spelling -- the two silent causes of a purple
	 * block in 1.12 are a blockstate naming a model nobody wrote and a model nobody references.
	 */
	public static String armModelName(EnumFacing mount, EnumFacing dir)
	{
		return "conduit_"+mount.getName()+"_"+dir.getName();
	}

	public static String hubModelName(EnumFacing mount)
	{
		return "conduit_"+mount.getName()+"_hub";
	}
}
