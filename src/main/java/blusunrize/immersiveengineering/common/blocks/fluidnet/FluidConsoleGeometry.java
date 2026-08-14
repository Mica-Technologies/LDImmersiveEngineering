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
 * The Fluid Control Console's shape and its kit of parts, kept free of any dependency on
 * registered blocks.
 * <p>
 * {@code MultiblockFluidConsole} cannot hold this: its static initialiser builds
 * {@code ItemStack}s from {@code IEContent}, so the class cannot even be loaded outside a running
 * game. Splitting the arithmetic out means the part of this that can silently go wrong -- and
 * whose failure mode is "hammering does nothing, with no message" -- is directly testable.
 * <p>
 * The deliberate mirror of {@code GridConsoleGeometry}: the two consoles are the same cabinet, so
 * a player who has built one already knows how to build the other.
 * <p>
 * <strong>Facing points into the wall, not out of it.</strong> That is IE's convention for every
 * formed multiblock ({@code MultiblockExcavator} and friends take {@code side.getOpposite()}), and
 * it is not decoration: {@code TileEntityMultiblockPart} walks a structure as
 * {@code origin.offset(facing, l).offset(facing.rotateY(), w)} when it disassembles one. This
 * console used to lay itself out along {@code rotateYCCW} instead -- the same latent bug the grid's
 * console carried -- so half of it was invisible to that walk and stayed behind as formed blocks
 * nobody could remove. Width runs along {@code facing.rotateY()} here for exactly that reason,
 * which -- because facing points away from the player -- is also the player's left-to-right.
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
	 * One of the four blocks a console is built out of.
	 * <p>
	 * The console used to be four copies of one housing block, which made a 2x2 of identical
	 * cabinets and a recipe that read as "four of the same thing" rather than as a console. It is
	 * now one of each, exactly as the grid's console is: the terminal is the screen, and the three
	 * stock engineering blocks are the hardware behind it.
	 */
	public enum Part
	{
		/**
		 * The Fluid Console Housing -- the fork's own block, and the screen of the finished console.
		 */
		TERMINAL,
		/**
		 * Redstone Engineering Block: the instrument rack beside the monitor.
		 */
		LOGIC,
		/**
		 * Light Engineering Block: the operator's desk, under the screen.
		 */
		DESK,
		/**
		 * Heavy Engineering Block: the pump cabinet, carrying the weight at the base.
		 */
		POWER
	}

	/**
	 * Which part sits at which structure index, seen from the front:
	 * <pre>
	 *     TERMINAL  LOGIC       (upper row: the monitor bay)
	 *     DESK      POWER       (lower row: the desk and the cabinet)
	 * </pre>
	 * Index order is the structure's own -- row-major over height then width, bottom row first --
	 * so the master (index 0) is the bottom-left block as the player sees it.
	 */
	private static final Part[] PARTS = {Part.DESK, Part.POWER, Part.TERMINAL, Part.LOGIC};

	/**
	 * Linear index into the structure, matching {@code TileEntityMultiblockPart.pos}. With a depth
	 * of one this is simply row-major over height and width.
	 */
	public static int structureIndex(int height, int width)
	{
		return height*WIDTH+width;
	}

	/**
	 * @return the height (row) of a structure index, 0 being the bottom
	 */
	public static int heightOf(int structureIndex)
	{
		return structureIndex/WIDTH;
	}

	/**
	 * @return the width (column) of a structure index, 0 being the player's left
	 */
	public static int widthOf(int structureIndex)
	{
		return structureIndex%WIDTH;
	}

	/**
	 * @return which of the four component blocks belongs at that structure index
	 */
	public static Part partAt(int structureIndex)
	{
		return PARTS[structureIndex];
	}

	/**
	 * @return which component block belongs at that height and column
	 */
	public static Part partAt(int height, int width)
	{
		return partAt(structureIndex(height, width));
	}

	/**
	 * The four positions a console occupies, given the origin (the bottom-left block as seen from
	 * the front) and the facing that points into the wall.
	 */
	public static BlockPos[] cells(BlockPos origin, EnumFacing facing)
	{
		EnumFacing right = facing.rotateY();
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
	public static BlockPos[] candidateOrigins(BlockPos clicked, EnumFacing facing)
	{
		EnumFacing right = facing.rotateY();
		BlockPos[] origins = new BlockPos[HEIGHT*WIDTH];
		int i = 0;
		for(int dh = 0; dh > -HEIGHT; dh--)
			for(int dw = 0; dw > -WIDTH; dw--)
				origins[i++] = clicked.add(0, dh, 0).offset(right, dw);
		return origins;
	}
}
