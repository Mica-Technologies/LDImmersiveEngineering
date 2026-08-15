/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;

/**
 * The box a conduit occupies, in the same sixteenths the model is drawn in.
 * <p>
 * One box, not several: 1.12 gives a block a single axis-aligned bounding box, so a corner or a tee
 * is covered by the smallest box containing every arm rather than by the arms themselves. That
 * means a little empty air inside the elbow is clickable, which is the ordinary compromise every
 * fence and pipe in the game makes and is invisible in play.
 * <p>
 * A riser grows the box along the mounting axis, out to the far face of the cell: it is the one
 * shape a conduit makes that is not flat against its surface. The cap on the far side of an outer
 * corner is the one piece of a conduit that is <em>not</em> in its own box -- it hangs three pixels
 * into the cell beyond the edge, and 1.12 gives a block one bounding box, so including it would
 * make a conduit selectable and breakable from a cell it does not occupy. Three pixels of corner
 * you cannot click is the cheaper of the two wrongs.
 * <p>
 * <strong>The numbers here and the numbers the asset script draws with have to agree.</strong> They
 * are stated once, here, and the script reads them from this file's constants -- a conduit whose
 * hitbox and model disagree is the sort of thing that is obvious in a screenshot and impossible to
 * find in code.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitBounds
{
	private ConduitBounds()
	{
	}

	/** How far the tubing stands off its surface, in sixteenths. Thin: it is conduit, not pipe. */
	public static final int DEPTH = 3;
	/** Half the width of the tubing, in sixteenths. Six to ten, so four wide and centred. */
	public static final int HALF_WIDTH = 2;

	private static final float U = 1/16f;

	/**
	 * @param mount       the face the conduit is clipped to
	 * @param connections one bit per {@link ConduitGeometry#inPlane} index
	 * @return minX, minY, minZ, maxX, maxY, maxZ, in block-relative units
	 */
	public static float[] of(EnumFacing mount, int connections)
	{
		return of(mount, connections, 0);
	}

	/**
	 * @param mount       the face the conduit is clipped to
	 * @param connections one bit per {@link ConduitGeometry#inPlane} index
	 * @param risers      which of those arms climb the block in front of them, same indexing
	 *
	 * @return minX, minY, minZ, maxX, maxY, maxZ, in block-relative units
	 */
	public static float[] of(EnumFacing mount, int connections, int risers)
	{
		//Start with the hub: a centred pad against the mounting face, then grow it toward each arm.
		int[] min = {8-HALF_WIDTH, 8-HALF_WIDTH, 8-HALF_WIDTH};
		int[] max = {8+HALF_WIDTH, 8+HALF_WIDTH, 8+HALF_WIDTH};
		//Flatten along the mounting axis: the tubing hugs the surface rather than floating in the
		//middle of the block.
		int axis = axisIndex(mount.getAxis());
		if(mount.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
		{
			min[axis] = 0;
			max[axis] = DEPTH;
		}
		else
		{
			min[axis] = 16-DEPTH;
			max[axis] = 16;
		}

		EnumFacing[] plane = ConduitGeometry.inPlane(mount);
		for(int i = 0; i < plane.length; i++)
		{
			if((connections&(1 << i))==0)
				continue;
			EnumFacing dir = plane[i];
			int armAxis = axisIndex(dir.getAxis());
			//An arm runs from the hub out to the block edge it points at, so two joined conduits
			//meet flush at the boundary between them.
			if(dir.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
				min[armAxis] = 0;
			else
				max[armAxis] = 16;
			//A riser turns at the end of that arm and climbs the block in front of it, so it fills
			//the cell right out to the far face -- the only thing a conduit does that is not flat
			//against its surface, and the reason the mounting axis is grown here and nowhere else.
			if((risers&(1 << i))==0)
				continue;
			if(mount.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
				max[axis] = 16;
			else
				min[axis] = 0;
		}

		return new float[]{min[0]*U, min[1]*U, min[2]*U, max[0]*U, max[1]*U, max[2]*U};
	}

	private static int axisIndex(Axis axis)
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
}
