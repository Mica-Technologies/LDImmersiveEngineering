/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a Substation: a transformer yard three wide, two deep and two tall.
 * <p>
 * Twelve blocks, which is the smallest footprint that still reads as a <em>yard</em> rather than as
 * another wall box. That is most of the point of the thing -- a Feed Unit and a Service Unit do
 * everything a substation does, and what a substation adds is that you can see it from across the
 * valley and know what the building behind it is for.
 * <p>
 * It is not only flavour, though. One structure carries <em>both</em> directions: a feed and a
 * service device, registered at two different cells so the grid can tell them apart, each with a
 * cap well above what a single box gets. A town's worth of small boxes becomes one thing to look
 * at and one thing to name.
 * <p>
 * World-free arithmetic, in the manner of {@code GridConsoleGeometry} and
 * {@code BuriedTankGeometry}, so the cell layout can be tested without a game.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class SubstationGeometry
{
	private SubstationGeometry()
	{
	}

	/** Along the face the player struck. */
	public static final int WIDTH = 3;
	/** Back from that face. */
	public static final int DEPTH = 2;
	public static final int HEIGHT = 2;

	public static final int BLOCK_COUNT = WIDTH*DEPTH*HEIGHT;

	/**
	 * The shape as {@code TileEntityMultiblockPart} wants it: height, depth, width, in that order.
	 */
	public static final int[] SIZE = {HEIGHT, DEPTH, WIDTH};

	/**
	 * Where the two grid devices sit within the structure.
	 * <p>
	 * The feed is the master cell and the service is the far bottom corner: two <em>different</em>
	 * positions, because the grid keys a device by its block position and a single cell could only
	 * ever hold one of them. Putting them at opposite ends of the yard also means the console's
	 * device list shows them as neighbours rather than as the same coordinates twice, which is the
	 * difference between a readable list and a confusing one.
	 */
	public static final int FEED_INDEX = 0;
	public static final int SERVICE_INDEX = WIDTH-1;

	/**
	 * A cell's index within the structure. The same packing {@code TileEntityMultiblockPart.pos}
	 * carries, so it has to be stable: it is written into save data.
	 */
	public static int structureIndex(int height, int depth, int width)
	{
		return (height*DEPTH+depth)*WIDTH+width;
	}

	public static int heightOf(int index)
	{
		return index/(DEPTH*WIDTH);
	}

	public static int depthOf(int index)
	{
		return (index/WIDTH)%DEPTH;
	}

	public static int widthOf(int index)
	{
		return index%WIDTH;
	}

	public static boolean isPart(int index)
	{
		return index >= 0&&index < BLOCK_COUNT;
	}

	/**
	 * @param origin the front bottom corner every offset is measured from; the yard grows right,
	 *               back and up from it
	 * @param front  the direction the yard faces
	 * @param right  along the struck face
	 */
	public static BlockPos cell(BlockPos origin, EnumFacing front, EnumFacing right,
								int height, int depth, int width)
	{
		return origin.add(0, height, 0).offset(right, width).offset(front.getOpposite(), depth);
	}

	public static List<BlockPos> cells(BlockPos origin, EnumFacing front, EnumFacing right)
	{
		List<BlockPos> out = new ArrayList<>(BLOCK_COUNT);
		for(int h = 0; h < HEIGHT; h++)
			for(int d = 0; d < DEPTH; d++)
				for(int w = 0; w < WIDTH; w++)
					out.add(cell(origin, front, right, h, d, w));
		return out;
	}

	/**
	 * Every origin that would place the struck block somewhere inside the yard.
	 * <p>
	 * The player may hammer any block of it, so the formation code has to try each origin that
	 * would be consistent with what they hit rather than assuming they found the corner.
	 */
	public static List<BlockPos> candidateOrigins(BlockPos struck, EnumFacing front, EnumFacing right)
	{
		List<BlockPos> out = new ArrayList<>(BLOCK_COUNT);
		for(int h = 0; h < HEIGHT; h++)
			for(int d = 0; d < DEPTH; d++)
				for(int w = 0; w < WIDTH; w++)
					out.add(struck.add(0, -h, 0).offset(right.getOpposite(), w).offset(front, d));
		return out;
	}
}
