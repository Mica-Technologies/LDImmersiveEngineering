/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

/**
 * What is printed across the middle of a plate, and which line of its text stops there.
 * <p>
 * <strong>Three kinds of tag have something in the way of their lettering, and it is not always
 * ink.</strong> The vertical tower tag has a rule with the receiving station's initials under it;
 * the painted oval is a fraction, and a fraction without its bar is two numbers stacked for no
 * reason; and the round inspection tag is bolted through its own middle, which a playtester noticed
 * was missing -- the nail is how the sign is held on, and it sits exactly where the text would.
 * <p>
 * <strong>One token, so the generator and the layout cannot disagree.</strong> The plate artwork is
 * drawn in Python and the text is laid out in Java, and both need to know where the divider falls
 * and how thick it is. Naming the whole thing once, on the kind, is what lets the generator switch
 * on it and paint the right object at the right place rather than carrying its own table of which
 * sign has a bar two thirds of the way down.
 *
 * @author LDImmersiveEngineering -- signage
 */
public enum SignDivider
{
	/** Nothing across the plate. Ten of the thirteen. */
	NONE(-1, Float.NaN, 0f),
	/**
	 * The rule on the vertical tower tag, two thirds of the way down: the plant's initials and the
	 * tower number above it, the receiving station's initials below.
	 */
	TOWER_RULE(1, 2f/3f, 1f),
	/**
	 * The bar of the fraction a series-wired street light wears: the series number over the pole
	 * number, which is what makes the two of them one reading rather than two.
	 * <p>
	 * Two pixels thick, and not for weight. A plate is an even number of pixels deep, so a bar an
	 * odd number of pixels thick cannot sit in the middle of one: it lands a half pixel to one side
	 * and the two numbers either side of it come out different sizes, which on a fraction is the
	 * one thing that must not happen.
	 */
	FRACTION_BAR(0, 0.5f, 2f),
	/**
	 * The bolt the round inspection tag hangs on. Not ink at all -- it is the fastening, drawn where
	 * it really is, and it is in the way of the lettering for the same reason it is on the real one.
	 */
	NAIL(0, 0.5f, 2f);

	private final int afterLine;
	private final float fraction;
	private final float thickness;

	SignDivider(int afterLine, float fraction, float thickness)
	{
		this.afterLine = afterLine;
		this.fraction = fraction;
		this.thickness = thickness;
	}

	/**
	 * @return the index of the last line above the divider, or -1 if there is none -- which is what
	 * says the plate's lines are one even stack rather than two groups
	 */
	public int getAfterLine()
	{
		return afterLine;
	}

	/**
	 * @return how far down the plate the divider's middle sits, as a fraction of its depth, or
	 * {@link Float#NaN} for a plain plate
	 */
	public float getFraction()
	{
		return fraction;
	}

	/** @return how deep the divider is, in block pixels: the band of plate the text may not use */
	public float getThickness()
	{
		return thickness;
	}
}
