/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

/**
 * What outline a plate is cut to, and therefore where its lettering is allowed to go.
 * <p>
 * <strong>A plate is not the rectangle it is drawn inside.</strong> An oval twelve pixels across is
 * twelve pixels across only through the middle, and a diamond is a point at top and bottom -- so a
 * line of text fitted to the sprite's bounding box hangs off the paint at exactly the place it was
 * meant to be read. That is what this is for: {@link SignLayout} asks the shape how much plate is
 * still there at the height the tallest letter of a line reaches, and fits the line to that.
 * <p>
 * The inset is the painted border, in block pixels. The strips, the oval and the inspection tag all
 * carry a one-pixel outline that the lettering has no business sitting on; the plain LADWP tower
 * diamond has none at all -- its number is painted straight onto the plate -- and takes half a pixel
 * simply so the ink does not start on the bevel.
 *
 * @author LDImmersiveEngineering -- signage
 */
public enum SignShape
{
	/** A rectangular plate: the strips, and everything else with four corners. */
	RECT(1f, 1f)
			{
				@Override
				public float spanAt(float halfSpan, float halfDepth, float across)
				{
					return 2*halfSpan;
				}
			},
	/** The painted oval and the round inspection tag, which are the same shape at two ratios. */
	ELLIPSE(1f, 0.70710678f)
			{
				@Override
				public float spanAt(float halfSpan, float halfDepth, float across)
				{
					if(across >= halfDepth)
						return 0;
					return 2*halfSpan*(float)Math.sqrt(1-(across*across)/(halfDepth*halfDepth));
				}
			},
	/** A diamond stood on its point, which is how a tower number is hung. */
	DIAMOND(0.5f, 0.5f)
			{
				@Override
				public float spanAt(float halfSpan, float halfDepth, float across)
				{
					if(across >= halfDepth)
						return 0;
					return 2*halfSpan*(1-across/halfDepth);
				}
			};

	private final float inset;
	private final float stackFactor;

	SignShape(float inset, float stackFactor)
	{
		this.inset = inset;
		this.stackFactor = stackFactor;
	}

	/** @return how far in from the plate's edge the paint the text may not sit on reaches */
	public float getInset()
	{
		return inset;
	}

	/**
	 * How much of the plate's depth the stack of lines is allowed to occupy, as a fraction.
	 * <p>
	 * One for a rectangle, which has its full width everywhere. Less for the others, because a line
	 * pushed out to the very top of an oval has no oval left beside it to print on: the fraction is
	 * the one that makes the stack the largest box that fits <em>inside</em> the shape rather than
	 * around it -- a half for a diamond, a root of a half for an ellipse.
	 *
	 * @return the fraction, never above one
	 */
	public float getStackFactor()
	{
		return stackFactor;
	}

	/**
	 * How much plate is left at a given distance from the middle, measured across the text.
	 *
	 * @param halfSpan  half the plate's interior along the text
	 * @param halfDepth half its interior across the text
	 * @param across    how far from the middle to measure, in the same block pixels
	 *
	 * @return the width available there, which is zero once past the edge
	 */
	public abstract float spanAt(float halfSpan, float halfDepth, float across);
}
