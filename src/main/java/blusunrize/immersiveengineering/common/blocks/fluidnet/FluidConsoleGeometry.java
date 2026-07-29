/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * The Fluid Control Console's shape, kept free of any dependency on registered blocks.
 * <p>
 * {@code MultiblockFluidConsole} cannot hold this: its static initialiser builds
 * {@code ItemStack}s from {@code IEContent}, so the class cannot even be loaded outside a running
 * game. Splitting the arithmetic out means the part of this that can silently go wrong -- and
 * whose failure mode is "hammering does nothing, with no message" -- is directly testable.
 * <p>
 * The deliberate mirror of {@code GridConsoleGeometry}: the two consoles are the same cabinet, so
 * a player who has built one already knows how to build the other.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public final class FluidConsoleGeometry
{
	private FluidConsoleGeometry()
	{
	}

	/**
	 * H, L, W -- two tall, one deep, two wide.
	 */
	public static final int HEIGHT = 2;
	public static final int DEPTH = 1;
	public static final int WIDTH = 2;

	public static final int[] SIZE = {HEIGHT, DEPTH, WIDTH};

	/**
	 * Linear index into the structure, matching {@code TileEntityMultiblockPart.pos}. With a depth
	 * of one this is simply row-major over height and width.
	 */
	public static int structureIndex(int height, int width)
	{
		return height*WIDTH+width;
	}

	/**
	 * @return true if this structure index belongs to the upper row, which carries the screen
	 */
	public static boolean isUpperRow(int structureIndex)
	{
		return structureIndex >= WIDTH;
	}

	/**
	 * @return true if this structure index is the right-hand column, seen from the front
	 * <p>
	 * The mirror of {@code GridConsoleGeometry.isRightColumn}, and for the same reason: the screen
	 * is one display across two blocks, and painting all of it on both gives two consoles standing
	 * next to each other.
	 */
	public static boolean isRightColumn(int structureIndex)
	{
		return structureIndex%WIDTH==WIDTH-1;
	}

	/**
	 * The four positions a console occupies, given the origin (the bottom block of the left-hand
	 * column as seen from the front).
	 */
	public static BlockPos[] cells(BlockPos origin, EnumFacing right)
	{
		BlockPos[] cells = new BlockPos[HEIGHT*WIDTH];
		int i = 0;
		for(int h = 0; h < HEIGHT; h++)
			for(int w = 0; w < WIDTH; w++)
				cells[i++] = origin.add(0, h, 0).offset(right, w);
		return cells;
	}

	/**
	 * Every origin for which {@code clicked} would fall inside the square. Exactly one of these is
	 * the real origin when the player hammers a complete console.
	 */
	public static BlockPos[] candidateOrigins(BlockPos clicked, EnumFacing right)
	{
		BlockPos[] origins = new BlockPos[HEIGHT*WIDTH];
		int i = 0;
		for(int dh = 0; dh > -HEIGHT; dh--)
			for(int dw = 0; dw > -WIDTH; dw--)
				origins[i++] = clicked.add(0, dh, 0).offset(right, dw);
		return origins;
	}
}
