/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

/**
 * Which way a plate's lettering runs.
 * <p>
 * <strong>The vertical strips were turned on their side, and that is not how they are printed.</strong>
 * A tag six pixels wide and fourteen tall cannot hold "34789E" across itself, and the first answer
 * to that was to turn the whole line ninety degrees -- which reads bottom to top with every letter
 * lying on its side, and is what a playtester recognised as wrong the moment they saw it against a
 * photograph. A real yellow strip has its characters upright and stacked, one to a row, reading
 * <em>downwards</em>: it is a column of letters, not a line of them rotated.
 * <p>
 * <strong>A transmission tower tag does both at once, and one of them really is turned.</strong>
 * The same playtester's tower photographs settled the other three constants here. An identification
 * tag reads down the pole in upright groups and then puts its circuit designation <em>across</em>
 * the foot of the plate; and the number painted straight onto a tower leg is a line of huge
 * characters lying on their side, which is the one place the rejected rotation is what is actually
 * out there. So the choice is per kind and, for {@link #DOWN_FOOT_ACROSS}, per line -- not one rule
 * for the whole set.
 * <p>
 * That is the whole of this enum. It says nothing about how big the letters are or where the rows
 * fall, which is {@link SignLayout}'s arithmetic, and nothing about drawing, which belongs to the
 * renderer and the editing window -- both of which have to agree, which is why the decision lives
 * on the kind rather than in either of them.
 *
 * @author LDImmersiveEngineering -- signage
 */
public enum SignTextFlow
{
	/**
	 * Left to right along the plate, one row per line. Every horizontal tag, and the multi-line
	 * plates whose stacked lines are each read across.
	 */
	ACROSS,
	/**
	 * Downwards, one character to a row, every character upright. The vertical strips: yellow,
	 * white, silver and orange.
	 * <p>
	 * A kind that flows this way carries a single line, and it is that line's characters -- not a
	 * stack of separate lines -- that the rows are made of. The number of rows is therefore however
	 * many characters somebody typed, which is why this cannot be laid out by the same per-line
	 * arithmetic as everything else.
	 */
	DOWN,
	/**
	 * Downwards in groups, except the last line, which reads across the foot of the plate.
	 * <p>
	 * What both transmission tower identification tags do. The tall one carries the tower number,
	 * then the two stations the run joins, each as its own column of upright characters reading
	 * downwards; the short one carries only the station pair. Both then print the circuit -- "L1"
	 * -- across the bottom, two characters side by side rather than stacked, which is how they are
	 * painted and is why the last line is the exception rather than a fourth flow of its own.
	 */
	DOWN_FOOT_ACROSS,
	/**
	 * One line lying on its side, reading top to bottom with every character's top edge toward the
	 * right.
	 * <p>
	 * The number stencilled straight onto a transmission tower's leg. Nothing else in the set is
	 * printed this way -- it is a line rotated a quarter turn, which is exactly what
	 * {@link #DOWN}'s comment above says is wrong for a strip -- and it is here because on a tower
	 * leg it is what is really painted: characters as tall as the panel is wide, which they can
	 * only be if the line runs down its long side.
	 */
	TURNED;

	/**
	 * @return true if this plate's rows are the characters of a line rather than lines of their own
	 * -- worth a name, because it is read at every draw
	 */
	public boolean isStacked()
	{
		return this==DOWN||this==DOWN_FOOT_ACROSS;
	}

	/** @return true if this is {@link #TURNED}: one line drawn a quarter turn round */
	public boolean isTurned()
	{
		return this==TURNED;
	}

	/**
	 * Whether line {@code index} of a plate carrying {@code lines} of them reads across the plate
	 * rather than down it.
	 *
	 * @return true only for the last line of a {@link #DOWN_FOOT_ACROSS} plate
	 */
	public boolean isFootLine(int index, int lines)
	{
		return this==DOWN_FOOT_ACROSS&&index==lines-1;
	}
}
