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
	DOWN;

	/** @return true if this is {@link #DOWN} -- worth a name, because it is read at every draw */
	public boolean isStacked()
	{
		return this==DOWN;
	}
}
