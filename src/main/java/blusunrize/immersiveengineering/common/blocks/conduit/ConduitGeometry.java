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
	 * Whether a junction box should draw a run physically arriving on face {@code dir}.
	 * <p>
	 * The box's own version of {@link #connects}: a conduit joins the box if the step from the box
	 * to the conduit lies in the conduit's own plane -- the same rule {@code ConduitRoute} walks the
	 * world with -- and a ground feeder joins it if the step runs along the feeder's axis. Two boxes
	 * touching each other are never joined; a run ends at the first box it meets, and a box is not a
	 * length of conduit.
	 * <p>
	 * This exists because the two halves of a joint used to be decided differently: the conduit drew
	 * an arm reaching the box (see {@code TileEntityConduit.connectsTo}), but the box itself had no
	 * matching idea of which of its faces a run actually touched, so its model never grew to meet
	 * that arm. The gap between a flush conduit end and an unmoved box read as a run that had not
	 * finished, or as a second one starting next to it.
	 *
	 * @param dir           the direction from the box toward the neighbour
	 * @param neighbourMount the neighbour's mounting face, if it is a conduit; null otherwise
	 * @param feederAxis    the neighbour's axis, if it is a ground feeder; null otherwise
	 */
	public static boolean joinsJunctionBox(EnumFacing dir, @Nullable EnumFacing neighbourMount,
										   @Nullable EnumFacing.Axis feederAxis)
	{
		if(neighbourMount!=null)
			return isInPlane(neighbourMount, dir);
		if(feederAxis!=null)
			return dir.getAxis()==feederAxis;
		return false;
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

	/**
	 * Which surface a junction box sits against, given what is around it.
	 * <p>
	 * <strong>A box has to be in the run's plane or it cannot meet the run.</strong> A conduit hugs
	 * the face it is clipped to -- three pixels of it, no more -- so a box that does not hug the same
	 * face is not merely offset from the run, it is in a part of the block the run never enters. That
	 * was the whole of a defect this feature shipped with: the box was modelled as a lump standing on
	 * the floor of its own cell whichever way it was bolted, so on a wall or a ceiling the run's arm
	 * arrived at a boundary with nothing on the other side of it, and the piece grown out to close
	 * that gap grew along the floor, three pixels clear of the wall the run was on.
	 * <p>
	 * Read off the neighbours rather than stored: it costs no block state -- {@code facing} is already
	 * declared for every meta of this block and nothing else fills it in for a box -- and it needs no
	 * placement rule, no tile entity field and no packet. It also cannot go stale, which a stored
	 * mount would: a box is placed before the run reaches it as often as after.
	 * <p>
	 * A box where two planes meet -- which is what a box is <em>for</em> -- can only sit in one of
	 * them, so the order is fixed rather than first-found: the same box in the same corner has to look
	 * the same on two different days.
	 *
	 * @param neighbourMounts the mounting face of the conduit joining on each side, indexed by
	 *                        {@code EnumFacing.ordinal()}, null where no run joins there
	 *
	 * @return the face to draw the box against; {@link EnumFacing#DOWN} when nothing joins it, which
	 * is a box sitting on the floor of its cell and is what a box with no runs has always looked like
	 */
	public static EnumFacing junctionBoxMount(EnumFacing[] neighbourMounts)
	{
		for(EnumFacing candidate : MOUNT_PREFERENCE)
			for(EnumFacing side : EnumFacing.VALUES)
				if(neighbourMounts[side.ordinal()]==candidate&&isInPlane(candidate, side))
					return candidate;
		return EnumFacing.DOWN;
	}

	/**
	 * The order a box picks a plane in when more than one run reaches it. Floors, then ceilings, then
	 * walls -- the same order {@link ConduitPlacement} picks a surface in, for the same reason.
	 */
	private static final EnumFacing[] MOUNT_PREFERENCE = {
			EnumFacing.DOWN, EnumFacing.UP,
			EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

	/** The name a generated model file uses for the box's housing on one mounting face. */
	public static String junctionBoxModelName(EnumFacing mount)
	{
		return "junction_box_"+mount.getName();
	}

	/** The name a generated model file uses for one patch plate on a box mounted that way. */
	public static String junctionPatchModelName(EnumFacing mount, EnumFacing face)
	{
		return "junction_patch_"+mount.getName()+"_"+face.getName();
	}

	/**
	 * The name a generated model file uses for the junction box's stub toward one face -- the piece
	 * that closes the gap between the box's own model and the block edge a conduit's arm reaches, on
	 * whichever faces the box does not already touch on its own.
	 * <p>
	 * Per mount as well as per face: where the gap is, and what shape the piece closing it has to be
	 * to actually meet the arm, are both decided by the surface the box is against.
	 */
	public static String junctionRunModelName(EnumFacing mount, EnumFacing dir)
	{
		return "junction_run_"+mount.getName()+"_"+dir.getName();
	}
}
