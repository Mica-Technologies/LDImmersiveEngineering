/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * The oilfield structures' shapes, kept free of any dependency on registered blocks.
 * <p>
 * The {@code Multiblock*} classes cannot hold these: their static initialisers build
 * {@code ItemStack}s from {@code IEContent}, so they cannot be loaded outside a running game.
 * Splitting the arithmetic out means the part that can silently go wrong -- and whose failure
 * mode is "hammering does nothing, with no message" -- stays directly testable.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class PetroleumGeometry
{
	private PetroleumGeometry()
	{
	}

	//	=================================
	//		DRILLING DERRICK
	//	=================================
	/**
	 * H, L, W. A tall, narrow tower: it has to read as a derrick from a distance, and its
	 * footprint has to be small enough to site on uneven ground.
	 */
	public static final int DERRICK_HEIGHT = 9;
	public static final int DERRICK_DEPTH = 3;
	public static final int DERRICK_WIDTH = 3;
	public static final int[] DERRICK_SIZE = {DERRICK_HEIGHT, DERRICK_DEPTH, DERRICK_WIDTH};

	//	=================================
	//		PUMPJACK
	//	=================================
	/**
	 * H, L, W. Long and low, because the walking beam is the silhouette that sells it.
	 */
	public static final int PUMPJACK_HEIGHT = 5;
	public static final int PUMPJACK_DEPTH = 6;
	public static final int PUMPJACK_WIDTH = 3;
	public static final int[] PUMPJACK_SIZE = {PUMPJACK_HEIGHT, PUMPJACK_DEPTH, PUMPJACK_WIDTH};

	//	=================================
	//		DISTILLATION TOWER
	//	=================================
	/**
	 * H, L, W. Tall and square: the draw ports sit at heights matching the column order, so
	 * the height is the mechanic and not just the silhouette.
	 */
	public static final int TOWER_HEIGHT = 12;
	public static final int TOWER_DEPTH = 4;
	public static final int TOWER_WIDTH = 4;
	public static final int[] TOWER_SIZE = {TOWER_HEIGHT, TOWER_DEPTH, TOWER_WIDTH};

	//	=================================
	//		INDUSTRIAL BURNER
	//	=================================
	/**
	 * H, L, W. A squat firebox that sits beside whatever it heats.
	 */
	public static final int BURNER_HEIGHT = 3;
	public static final int BURNER_DEPTH = 3;
	public static final int BURNER_WIDTH = 3;
	public static final int[] BURNER_SIZE = {BURNER_HEIGHT, BURNER_DEPTH, BURNER_WIDTH};

	/**
	 * Linear index into a structure, matching {@code TileEntityMultiblockPart.pos}.
	 */
	public static int structureIndex(int[] size, int height, int depth, int width)
	{
		return (height*size[1]+depth)*size[2]+width;
	}

	/**
	 * @return the height layer a structure index sits in
	 */
	public static int heightOf(int[] size, int index)
	{
		return index/(size[1]*size[2]);
	}
}
