/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * Where a conduit may run and what shape that makes it.
 * <p>
 * A conduit is <em>surface mounted</em>: it lies flat against the face of the block it is clipped
 * to and runs in the plane of that face, turning in right angles. That single sentence is what
 * every rule here follows from, and it is the whole reason conduits exist -- IE's wires are
 * catenaries, which are right across a valley and wrong along a ceiling.
 * <p>
 * <strong>A run wraps around corners.</strong> It reaches a wall and climbs it; it reaches the edge
 * of a beam and follows the beam's side down. Both happen without a junction box, and both happen
 * inside the two cells that meet at the corner rather than through a third block.
 * <p>
 * This reverses the rule the feature shipped with -- "a run stays on one surface; a plane change
 * goes through a junction box" -- which a playtester reported as the conduit not behaving like the
 * wiring it is modelled on. The old rule was cheap and defensible on paper, and wrong in the hand:
 * the gesture somebody makes when a run meets a pole is to keep laying conduit, and being handed a
 * dead stub for it reads as the feature being broken rather than as a design decision. Junction
 * boxes remain what they always were -- where a bundle splits or breaks out -- rather than a toll
 * on every corner in a build.
 * <p>
 * Two shapes of corner, and they are not the same shape:
 * <ul>
 * <li><strong>Inner</strong> -- a floor run meets a wall and climbs it. The two conduits are
 * stacked one cell apart along the mounting axis, and the corner is turned <em>inside</em> the
 * lower cell by a {@link ArmMode#RISER}: the arm runs to the wall and then up the wall's face.</li>
 * <li><strong>Outer</strong> -- a run reaches the edge of the block it is mounted on and follows
 * that block's next face round. The two conduits are <em>diagonal</em> neighbours clipped to two
 * faces of the same supporting block, and each simply reaches the edge they share.</li>
 * </ul>
 * <p>
 * World-free on purpose, like the rest of the conduit code -- the rules are arithmetic on facings
 * and positions, so they can be tested without a game running. The two rules that need to know
 * whether a block is there ({@link #innerCornerSupport}) hand back the position to ask about rather
 * than asking themselves.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitGeometry
{
	private ConduitGeometry()
	{
	}

	/**
	 * How many arms a conduit can have: the four directions in its own plane. The two along the
	 * mounting axis are excluded -- one is the surface it is clipped to, and the other is open air.
	 * <p>
	 * A corner does not add a fifth arm. Both kinds are turned by an arm that already points along
	 * the surface: an inner corner by that arm climbing at the end of it, an outer one by it
	 * carrying on past the edge. That is what keeps the state four flags wide instead of six.
	 */
	public static final int ARMS = 4;

	/**
	 * What one arm of a conduit does, which is the whole of the difference wrapping made.
	 * <p>
	 * Four values in two bits, held on the tile entity as two of {@code ConduitArms}' three masks
	 * and read from there by {@code ConduitRunModel}. Nothing about an arm is in the block state at
	 * all, which matters more than it looks: Forge builds the cartesian product of every listed
	 * property at startup and gives each resulting state a model reference of its own, and this was
	 * once twelve booleans' worth -- 73,728 states for a block made of seventy-eight little boxes.
	 */
	public enum ArmMode
	{
		/** No arm at all. */
		NONE,
		/** An arm running from the hub to the block edge, in the plane of the mounting face. */
		STRAIGHT,
		/**
		 * An arm that reaches the block in front of it and then turns 90 degrees to climb that
		 * block's face, inside this cell -- the inner corner. Its partner is the conduit clipped to
		 * that face one cell out along the mounting axis, and the two meet at the boundary between
		 * them.
		 */
		RISER,
		/**
		 * A straight arm that carries on a little past the block edge to cap an outer corner.
		 * <p>
		 * Both halves of an outer corner are plain arms reaching the same edge from two sides, so
		 * without this there is a conduit-sized notch missing at the corner itself. Exactly one of
		 * the two draws the cap -- see {@link #drawsCornerCap} -- because two identical cubes in the
		 * same place is z-fighting rather than a corner.
		 */
		WRAP
	}

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
	 * Whether two conduits, adjacent in the world, join along a flat surface.
	 * <p>
	 * They do when they lie on the same surface and the step from one to the other runs along it.
	 * This is the plain case and always was; the two corner rules below are what a run does when
	 * the surface itself stops.
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

	//	=================================
	//		INNER CORNERS
	//	=================================

	/**
	 * Whether two conduits, adjacent in the world and clipped to two perpendicular faces, join by
	 * climbing the corner between them.
	 * <p>
	 * A floor run reaching a wall and continuing up it. The two cells are stacked along the
	 * <em>lower</em> conduit's mounting axis, not laid along its surface, which is why this is a
	 * separate rule rather than a loosening of {@link #connects}: the pair is
	 * <em>floor conduit at P</em> and <em>wall conduit at P minus the floor conduit's mount</em>.
	 * <p>
	 * There are two ways to be standing at that joint and both are covered here, so that a walk and
	 * a renderer starting from either end reach the same answer:
	 * <ul>
	 * <li>from the piece that <em>rises</em>: the step leaves its own surface ({@code towards} is
	 * the opposite of its mount) and the neighbour is clipped to the face this one's arm climbs.</li>
	 * <li>from the piece on the <em>wall</em>: the step runs along its surface toward a neighbour
	 * clipped to the very face it is stepping onto.</li>
	 * </ul>
	 * <p>
	 * <strong>It is not the whole rule.</strong> The corner also needs a block to be in the corner
	 * -- the one the riser hugs on its way up. {@link #innerCornerSupport} says which, and every
	 * caller has to ask, or a run would turn corners in mid-air around a wall that is not there.
	 *
	 * @param mount      the face this conduit is clipped to
	 * @param otherMount the face the neighbour is clipped to
	 * @param towards    the direction from this conduit to the neighbour
	 */
	public static boolean joinsInnerCorner(EnumFacing mount, @Nullable EnumFacing otherMount,
										   @Nullable EnumFacing towards)
	{
		if(otherMount==null||towards==null||otherMount.getAxis()==mount.getAxis())
			return false;
		//The riser's side: stepping straight out of its own surface, onto the piece stuck to the
		//wall its arm climbs. The arm direction is the neighbour's mount, which is why that has to
		//be in this conduit's plane.
		if(towards==mount.getOpposite())
			return isInPlane(mount, otherMount);
		//The wall's side: stepping along its own surface onto a piece clipped to the face it is
		//stepping toward. A floor is exactly that, seen from a wall.
		return towards==otherMount&&isInPlane(otherMount, mount);
	}

	/**
	 * The block an inner corner is turned around, which has to be there for the corner to exist.
	 * <p>
	 * It is the block the riser hugs on its way up: the one in front of the lower conduit, under
	 * the upper conduit's own surface. Both ends of the joint name the same block, which is the
	 * point of deriving it here rather than at each end -- two ends disagreeing about whether the
	 * wall is there would give a run that draws joined and carries nothing.
	 * <p>
	 * <strong>It must also be empty of conduit hardware.</strong> A ground feeder is a solid cube a
	 * run passes straight through, so a run reaching one carries on through it rather than climbing
	 * it; a junction box is not solid at all. Callers check {@code nodeAt} as well as solidity, and
	 * the face to ask about is the one the riser lies against.
	 *
	 * @param pos        where this conduit is
	 * @param mount      the face this conduit is clipped to
	 * @param otherMount the face the neighbour is clipped to
	 * @param towards    the direction from this conduit to the neighbour
	 *
	 * @return the position of the block the corner turns around
	 */
	public static BlockPos innerCornerSupport(BlockPos pos, EnumFacing mount, EnumFacing otherMount,
											  EnumFacing towards)
	{
		//From the riser's side the corner block is simply the one its arm points at.
		if(towards==mount.getOpposite())
			return pos.offset(otherMount);
		//From the wall's side it is one step along the run and then one step into this conduit's own
		//surface -- the block directly below the one this piece is bolted to.
		return pos.offset(towards).offset(mount);
	}

	/** The face of {@link #innerCornerSupport} a riser lies against, for the solidity test. */
	public static EnumFacing innerCornerSupportFace(EnumFacing mount, EnumFacing otherMount,
													EnumFacing towards)
	{
		return towards==mount.getOpposite()?otherMount.getOpposite(): mount.getOpposite();
	}

	//	=================================
	//		OUTER CORNERS
	//	=================================

	/**
	 * Where the conduit continuing a run around an outer corner sits.
	 * <p>
	 * <strong>Diagonally.</strong> A run along the top of a beam that reaches the beam's end
	 * carries on down the beam's end face, and the cell that face's conduit occupies touches this
	 * one only along an edge. Both are clipped to the <em>same supporting block</em> -- the last
	 * block of the beam -- on two of its faces, which is what makes the join real rather than a
	 * coincidence of position: {@code pos+mount} and {@code (pos+dir+mount)+(-dir)} are one block.
	 * <p>
	 * The formula is its own inverse. Feeding it the partner's position, mount and arm direction
	 * gives this conduit back, so neither end has to be told it is the second one.
	 *
	 * @param pos   where this conduit is
	 * @param mount the face this conduit is clipped to
	 * @param dir   the arm direction, in this conduit's plane
	 */
	public static BlockPos outerCornerCell(BlockPos pos, EnumFacing mount, EnumFacing dir)
	{
		return pos.offset(dir).offset(mount);
	}

	/** The face a conduit wrapping an outer corner out of {@code dir} has to be clipped to. */
	public static EnumFacing outerCornerMount(EnumFacing dir)
	{
		return dir.getOpposite();
	}

	/**
	 * Which half of an outer corner draws the little cap that fills the corner itself.
	 * <p>
	 * Both halves are plain arms reaching the same edge from two sides, and the cube where they
	 * meet belongs to neither. One of them has to grow into it; if both did, two identical cubes
	 * would occupy the same space and z-fight, which reads as a flickering corner rather than as a
	 * corner. The order is fixed rather than first-come so the same corner looks the same on two
	 * different days -- the same reason {@link #junctionBoxMount} has one.
	 *
	 * @param mount      the face this conduit is clipped to
	 * @param otherMount the face the other half of the corner is clipped to
	 */
	public static boolean drawsCornerCap(EnumFacing mount, EnumFacing otherMount)
	{
		return preference(mount) < preference(otherMount);
	}

	private static int preference(EnumFacing face)
	{
		for(int i = 0; i < MOUNT_PREFERENCE.length; i++)
			if(MOUNT_PREFERENCE[i]==face)
				return i;
		return MOUNT_PREFERENCE.length;
	}

	/**
	 * What one arm looks like, given what was found around it.
	 * <p>
	 * Stated once, here, because the renderer, the hitbox and the saved state all have to agree
	 * about it and there are three ways to be joined in one direction.
	 *
	 * @param straight whether anything joins this arm along the surface -- a conduit, a box, a
	 *                 feeder, or the flat half of an inner corner
	 * @param riser    whether this arm climbs the block in front of it to meet a conduit above
	 * @param wrap     whether this arm continues around an outer corner
	 * @param cap      whether this half of that outer corner is the one drawing the corner cube
	 */
	public static ArmMode armMode(boolean straight, boolean riser, boolean wrap, boolean cap)
	{
		//A riser wins if it is there at all: it needs a solid block in front of it, which is
		//exactly what stops anything else joining along that arm, so this decides nothing in
		//practice and says what to draw in the one contrived case where a wrap coexists with it.
		if(riser)
			return ArmMode.RISER;
		if(wrap&&cap)
			return ArmMode.WRAP;
		return straight||wrap?ArmMode.STRAIGHT: ArmMode.NONE;
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
	 * <p>
	 * <strong>A run arrives at a box face on, and never around a corner.</strong> Corners are turned
	 * between two lengths of conduit; a box is a cube in the middle of its cell with six faces to
	 * patch, and a run wrapping round the outside of one would have nowhere to arrive. Put the box
	 * <em>at</em> the corner instead -- it sits in the plane of whichever run reaches it and both
	 * runs meet it flush, which is the gesture the box was always for.
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
	 * Lower case and mount-first, matching the files this fork's asset script writes. It lives here
	 * rather than in the script because both the script and {@code ConduitRunModel} spell these
	 * names, and a part the model asks for and nobody wrote is a hole in a run with nothing in the
	 * log -- one of the two silent causes of a purple block in 1.12.
	 */
	public static String armModelName(EnumFacing mount, EnumFacing dir)
	{
		return "conduit_"+mount.getName()+"_"+dir.getName();
	}

	/**
	 * The arm that climbs the block in front of it: the inner corner, drawn as one model rather
	 * than as the straight arm plus a piece, because a multipart part either applies or does not
	 * and {@link ArmMode#STRAIGHT} and {@link ArmMode#RISER} are different states of one arm.
	 */
	public static String riserModelName(EnumFacing mount, EnumFacing dir)
	{
		return armModelName(mount, dir)+"_riser";
	}

	/** The straight arm plus the cube that fills an outer corner. See {@link #drawsCornerCap}. */
	public static String wrapModelName(EnumFacing mount, EnumFacing dir)
	{
		return armModelName(mount, dir)+"_wrap";
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
	 * Read off the neighbours rather than stored: it costs no block state at all -- the box's model
	 * asks {@code TileEntityJunctionBox.getRenderShape} for it at render time -- and it needs no
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

	/**
	 * Where a wire strung to a junction box attaches: the centre of that face's patch plate.
	 * <p>
	 * <strong>On the surface, not in the middle of the block.</strong> A box is a ten-pixel housing
	 * hugging whatever it is bolted to, so the centre of its cell is inside the block next door as
	 * often as not; a catenary that started there would visibly begin inside the wall. This lands on
	 * the plate the face already wears when it is patched, which is the thing a player is looking at
	 * when they decide to string a wire to that face.
	 * <p>
	 * Derived from {@link ConduitBounds#junctionBox} rather than written out, for the same reason
	 * the plate models are derived from the housing: two sets of numbers for one shape is how the
	 * wire and the box it is attached to end up half a pixel apart with nothing to explain it.
	 *
	 * @param mount the face the box is bolted to
	 * @param face  the face the wire is on
	 *
	 * @return x, y, z within the block, in block-relative units
	 */
	public static float[] junctionTerminal(EnumFacing mount, EnumFacing face)
	{
		int[] box = ConduitBounds.junctionBox(mount);
		float[] point = new float[3];
		for(int i = 0; i < 3; i++)
			point[i] = (box[i]+box[i+3])/32f;
		int axis = axisIndex(face.getAxis());
		point[axis] = (face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE
				?box[axis]: box[axis+3])/16f;
		return point;
	}

	private static int axisIndex(EnumFacing.Axis axis)
	{
		switch(axis)
		{
			case X:
				return 0;
			case Y:
				return 1;
			default:
				return 2;
		}
	}

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

	/**
	 * The same stub cut two pixels short, worn by a face that has a conductor broken out on it so
	 * that {@link #junctionMouthModelName} can finish it in that conductor's colour.
	 * <p>
	 * Two models rather than one stub with a coloured cap laid over its end: the cap would share
	 * the stub's outer face exactly, and two coplanar faces in one place is z-fighting. Cutting the
	 * stub instead leaves the two abutting, and whichever of the pair faces the camera is the one
	 * buried inside the solid.
	 */
	public static String junctionShortRunModelName(EnumFacing mount, EnumFacing dir)
	{
		return junctionRunModelName(mount, dir)+"_short";
	}

	/**
	 * The coloured mouth of a breakout: the last two pixels of the stub, in the conductor's dye.
	 * <p>
	 * <strong>This is where a breakout's colour lives now.</strong> It used to be a plate painted
	 * flat against the housing, which was right while the housing stopped short of the block edge
	 * and wrong the moment it stopped doing that -- a stub grown out to meet the hardware bolted
	 * beside it swallows a plate lying underneath it. The mouth is also where decision 9 always
	 * said the colour belonged, and where somebody standing in front of a pole can read it.
	 */
	public static String junctionMouthModelName(EnumFacing mount, EnumFacing dir)
	{
		return "junction_mouth_"+mount.getName()+"_"+dir.getName();
	}
}
