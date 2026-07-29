/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * The torpedo tank: the horizontal drum on saddles that sits at the end of a garden.
 * <p>
 * Twenty-four buckets, the largest of the three bodies and the one a house actually runs on. It
 * deliberately stops short of the buried Domestic Tank's thirty-two: the buried tier's proposition
 * is that it is invisible and cheap, and an above-ground tank that beat it on capacity as well as
 * on convenience would leave it with nothing to be.
 * <p>
 * Everything it does is {@link TileEntityPropaneCylinder}'s. Only the size and the shape differ.
 * <p>
 * <strong>It lies north to south and does not rotate.</strong> That is a shortfall rather than a
 * decision: {@code petroleum_device} declares no facing property at all -- the two directional
 * blocks on it keep their facing on the tile and are drawn as plain cubes -- so making this one
 * turn means adding a facing to every meta of the block and a rotation submap that would apply to
 * all of them, which is the kind of blockstate change that fails as a purple block with nothing in
 * the log. Worth doing on its own, not as a rider on a new tank.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPropaneTankTorpedo extends TileEntityPropaneCylinder
{
	/** Twenty-four buckets. */
	public static final int CAPACITY = 24000;

	public TileEntityPropaneTankTorpedo()
	{
		super(CAPACITY);
	}
}
