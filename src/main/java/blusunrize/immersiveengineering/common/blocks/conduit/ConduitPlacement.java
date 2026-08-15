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
 * Deciding which surface a freshly placed conduit clips to.
 * <p>
 * The rule used to be one line -- the conduit mounts to whatever block you clicked -- and that is
 * right exactly once, for the first length of a run. It is wrong for every length after it, because
 * the natural way to continue a run is to click the end of the run, and doing that mounted the new
 * length <em>to the conduit</em> rather than to the wall the conduit was on. A conduit mounted on
 * another conduit sits in a different plane, so {@link ConduitGeometry#connects} refuses it, and it
 * ends up a dead stub that looks like every other length. Building a corridor correctly therefore
 * meant hitting the wall itself every single time -- on a floor run, the one face the conduit you
 * just placed is covering.
 * <p>
 * So: <strong>a conduit placed against another conduit joins that conduit's surface</strong>, which
 * is what somebody continuing a run means by the gesture. Clicking bare wall still behaves exactly
 * as before, which is what makes this a strictly wider rule rather than a different one.
 * <p>
 * Runs turning corners on their own added one more gesture and no more exceptions. Clicking the
 * wall a run is heading for, or the far side of the beam it is running along, already answered
 * correctly -- both are bare wall. What did not was clicking the <em>exposed face</em> of the last
 * length, which is the same instruction said differently, and used to lay the new piece flat on top
 * of a conduit. That is the branch below.
 * <p>
 * World-free -- the world arrives as a {@link Surroundings} -- so the decision can be tested against
 * a drawn building rather than a real one, the same way {@link ConduitRoute} is.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class ConduitPlacement
{
	private ConduitPlacement()
	{
	}

	/**
	 * The few things the rule needs to ask about the world.
	 */
	public interface Surroundings
	{
		/**
		 * @return the face a conduit at that position is clipped to, or null if there is no conduit
		 * there. A junction box is not a conduit and answers null.
		 */
		@Nullable
		EnumFacing conduitMountAt(BlockPos pos);

		/**
		 * @return true if there is a junction box at that position
		 */
		boolean isJunctionAt(BlockPos pos);

		/**
		 * @return the axis a ground feeder at that position passes a run along, or null if there is
		 * no feeder there
		 */
		@Nullable
		default EnumFacing.Axis feederAxisAt(BlockPos pos)
		{
			return null;
		}

		/**
		 * @return true if a conduit could clip to the block at that position, on the given face --
		 * that is, whether there is a surface there to mount against at all
		 */
		boolean isMountable(BlockPos pos, EnumFacing face);
	}

	/**
	 * The order faces are tried in when the rule has to pick a surface for itself, rather than
	 * being told one. Floors first, then ceilings, then walls: it is the order somebody building
	 * indoors would try, and being fixed is what stops the same click giving different answers on
	 * two different days.
	 */
	private static final EnumFacing[] PREFERENCE = {
			EnumFacing.DOWN, EnumFacing.UP,
			EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

	/**
	 * @param placed      where the new conduit is going
	 * @param clickedSide the face of the clicked block that was clicked, which is also the step from
	 *                    that block to {@code placed}
	 * @param around      the world
	 *
	 * @return the face the new conduit should clip to
	 */
	public static EnumFacing mountFor(BlockPos placed, EnumFacing clickedSide, Surroundings around)
	{
		//The clicked block is the one the new conduit is being placed against, so it is one step back
		//along the face that was clicked.
		BlockPos clicked = placed.offset(clickedSide.getOpposite());

		//	=================================
		//	Continuing a run
		//	=================================
		EnumFacing neighbour = around.conduitMountAt(clicked);
		//isInPlane, because only a step along that conduit's own surface is a continuation. Clicking
		//the exposed top of a floor conduit is a step off the surface, not along it, and inheriting
		//there would hang the new length in the air claiming to be mounted on a floor it cannot see.
		if(neighbour!=null&&ConduitGeometry.isInPlane(neighbour, clickedSide)
				&&around.isMountable(placed.offset(neighbour), neighbour.getOpposite()))
			return neighbour;

		//	=================================
		//	Turning the corner off a run's end
		//	=================================
		//The gesture that goes with runs wrapping around corners: a floor run reaches a wall, and
		//the next length goes on the wall. Clicking the wall itself already answered correctly --
		//the plain rule below does it -- but clicking the exposed top of the last floor length is
		//the same gesture and used to answer DOWN, laying the new piece flat on top of a conduit,
		//which is not a surface at all. A stub floating over a run is exactly what the report about
		//conduit "not wrapping around" was pointing at.
		//
		//The face picked is the one that makes the corner: a wall the new length can clip to, with
		//the same wall carrying on down beside the length that was clicked, since that lower block
		//is what the riser hugs on its way up. Requiring both is what stops this from answering at
		//all in the middle of a room, where the old answer is still the right one.
		if(neighbour!=null&&clickedSide==neighbour.getOpposite())
		{
			for(EnumFacing face : PREFERENCE)
				if(ConduitGeometry.isInPlane(neighbour, face)
						&&around.isMountable(placed.offset(face), face.getOpposite())
						&&around.isMountable(clicked.offset(face), face.getOpposite())
						//A ground feeder is solid and is not a corner to turn: a run reaching one
						//goes through it, which is the whole point of the block.
						&&around.feederAxisAt(clicked.offset(face))==null)
					return face;
		}

		//	=================================
		//	Starting a run off a ground feeder
		//	=================================
		//A feeder has no surface to inherit either, and picking one for itself the way the box rule
		//does would get the commonest case exactly wrong: clicking the top of a feeder set into a
		//floor would find the feeder's own top face and lay the new conduit flat on it, in the one
		//plane that cannot reach the feeder underneath. A run passes through a feeder along the
		//feeder's axis, so the conduit has to be clipped to a face whose plane contains that axis --
		//which is to say, a wall.
		//
		//isInPlane says exactly that on its own: an end face of the feeder was clicked, so
		//clickedSide already lies along the feeder's axis, and every face still in the running is
		//one the run can travel that axis on. Clicking a feeder's *side* is not this gesture at all
		//-- nothing there is on the pass-through path -- so it falls through to the wall rule below.
		EnumFacing.Axis axis = around.feederAxisAt(clicked);
		if(axis!=null&&clickedSide.getAxis()==axis)
		{
			for(EnumFacing face : PREFERENCE)
				if(ConduitGeometry.isInPlane(face, clickedSide)
						&&around.isMountable(placed.offset(face), face.getOpposite()))
					return face;
		}

		//	=================================
		//	Starting a run off a box
		//	=================================
		//A box has no surface of its own to inherit -- it is a cube, and which wall it is against is
		//not recorded anywhere. So the rule looks for a wall behind the new length instead. Without
		//this, the first conduit off a box mounts itself to the box, which puts it in a plane that
		//cannot reach the box it was placed on: the one placement guaranteed to disappoint.
		if(around.isJunctionAt(clicked))
		{
			for(EnumFacing face : PREFERENCE)
				if(ConduitGeometry.isInPlane(face, clickedSide)
						&&around.isMountable(placed.offset(face), face.getOpposite()))
					return face;
		}

		//	=================================
		//	Clicking a wall
		//	=================================
		//The original rule, unchanged, and still the right answer for the first length of a run: the
		//conduit clips to the surface you put it against.
		return clickedSide.getOpposite();
	}
}
