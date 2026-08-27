/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * How far a tag has to reach to touch what it is bolted to, and how that reach travels in the block
 * state.
 * <p>
 * <strong>A pole is not a wall.</strong> A sign is placed in the empty cell beside its support and
 * its plate sits against the face they share -- which is right against a wall, and two to four
 * pixels short of a pole, because a pole's skin is inside its own block. City Super Mod's light
 * poles and traffic poles are a quarter to an eighth of a block in on every side, so a tag hung on
 * one hangs in the air beside it, which is what a playtester saw and reported.
 * <p>
 * The answer is the one the junction box already uses for the same problem: <em>grow toward the
 * neighbour</em>. The plate stays where it is and a short standoff reaches back from it to whatever
 * is really there, measured off that block's own bounding box rather than guessed at or configured.
 * <p>
 * <strong>The plate does not move, and that is deliberate.</strong> Sliding it into the pole's cell
 * would look the same and would make the sign unclickable: {@code World.rayTraceBlocks} walks cell
 * by cell and only ever tests the block that owns the cell it is in, so a plate drawn inside its
 * neighbour's cell is a plate whose neighbour gets hit instead. A standoff keeps every pixel of the
 * clickable plate in the sign's own block.
 * <p>
 * <strong>One packed property, not two.</strong> Forge builds a blockstate variant out of the
 * cartesian product of the submaps it is given and merges the partial variants, so two submaps that
 * both want to name a model cannot both be honoured -- the second simply wins. The plate a sign
 * draws depends on <em>both</em> its kind and its reach, so the two travel as one integer and the
 * blockstate has one submap naming one model per combination. See {@code make_signage_assets.py},
 * which writes them.
 *
 * @author LDImmersiveEngineering -- signage
 */
public final class SignMount
{
	private SignMount()
	{
	}

	/**
	 * The deepest a standoff reaches, in block pixels.
	 * <p>
	 * Six covers everything that is recognisably a pole: CSM's own are two pixels in (the large
	 * poles and the octagonal and round concrete light poles) or four (the small traffic pole).
	 * Past six a block is not a pole with a gap beside it, it is a block with a hole in it, and a
	 * tag bolted to the far side of one would be a tag standing off nothing.
	 */
	public static final int MAX = 6;

	/** How many reaches there are, counting no reach at all. */
	public static final int STEPS = MAX+1;

	/** @return {@code mount} brought inside {@code 0..MAX} */
	public static int clamp(int mount)
	{
		return mount < 0?0: Math.min(mount, MAX);
	}

	/**
	 * @return the one integer the block state carries: which plate, and how far it reaches
	 */
	public static int pack(UtilitySignKind kind, int mount)
	{
		return kind.ordinal()*STEPS+clamp(mount);
	}

	/** @return how many packed values there are, which is what the block property is sized to */
	public static int packedCount()
	{
		return UtilitySignKind.VALUES.length*STEPS;
	}

	/** @return the kind a packed value names, defaulting the way {@link UtilitySignKind#byIndex} does */
	public static UtilitySignKind kindOf(int packed)
	{
		return UtilitySignKind.byIndex(packed < 0?-1: packed/STEPS);
	}

	/** @return the reach a packed value names */
	public static int mountOf(int packed)
	{
		return packed < 0?0: clamp(packed%STEPS);
	}

	/**
	 * How far short of the shared face a neighbour's own geometry stops, in block pixels.
	 * <p>
	 * <strong>Unclamped on purpose.</strong> A caller deciding whether a block is something to bolt
	 * a sign to needs to be able to tell "two pixels in, like a pole" from "nowhere near, like a
	 * flower" -- and everything that is not there at all measures the full sixteen. Clamping is
	 * {@link #clamp}'s job, once the block has been accepted.
	 *
	 * @param box        the neighbour's bounding box, in its own block's units
	 * @param towardSign which way the sign lies from that neighbour, so which of the box's faces is
	 *                   the one the sign is looking at
	 *
	 * @return the gap in block pixels, never negative -- a model that overhangs its own cell, as
	 * CSM's octagonal pole top does, reaches past the face rather than short of it, and there is
	 * nothing to stand off
	 */
	public static int gapPixels(AxisAlignedBB box, EnumFacing towardSign)
	{
		double surface;
		switch(towardSign.getAxis())
		{
			case X:
				surface = towardSign.getAxisDirection()==EnumFacing.AxisDirection.POSITIVE
						?box.maxX: 1-box.minX;
				break;
			case Y:
				surface = towardSign.getAxisDirection()==EnumFacing.AxisDirection.POSITIVE
						?box.maxY: 1-box.minY;
				break;
			default:
				surface = towardSign.getAxisDirection()==EnumFacing.AxisDirection.POSITIVE
						?box.maxZ: 1-box.minZ;
				break;
		}
		int gap = (int)Math.round((1-surface)*16);
		return Math.max(0, gap);
	}
}
