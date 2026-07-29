/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * The upright tank: the tall bottle that stands beside a building rather than under a barbecue.
 * <p>
 * Twelve buckets, three times the cylinder. The middle of the three propane bodies and the one that
 * reads as installed: too big to be casually carried in a pocketful, small enough to stand next to a
 * generator without being a depot.
 * <p>
 * Everything it does is {@link TileEntityPropaneCylinder}'s -- propane only, contents kept when
 * broken, fillable by hand or by pipe. Only the size and the shape differ, which is the whole point
 * of the three: a forecourt, a back garden and a job site do not look alike, and until now they all
 * had the same bottle on them.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPropaneTankUpright extends TileEntityPropaneCylinder
{
	/** Twelve buckets. */
	public static final int CAPACITY = 12000;

	public TileEntityPropaneTankUpright()
	{
		super(CAPACITY);
	}
}
