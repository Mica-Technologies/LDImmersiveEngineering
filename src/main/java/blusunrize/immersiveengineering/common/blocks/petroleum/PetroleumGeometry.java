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
	/**
	 * Tall enough that all seven draw ports sit exactly two layers apart.
	 * <p>
	 * This is a derived number, not a taste one: ports run from {@code HEIGHT-1} down to layer 1,
	 * so seven of them spaced two apart need a span of twelve and therefore a column of fourteen.
	 * At twelve the spacing rounded to 11, 9, 8, 6, 4, 3, 1 -- two pairs landing on adjacent
	 * layers, where a pipe run climbing to the upper port of a pair connects to the lower one on
	 * the way past and quietly mixes two cuts into one line.
	 */
	public static final int TOWER_HEIGHT = 14;
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

	//	=================================
	//		GAS SCRUBBER
	//	=================================
	/**
	 * H, L, W. Twin vertical vessels, tall enough to read as pressure equipment.
	 */
	public static final int SCRUBBER_HEIGHT = 6;
	public static final int SCRUBBER_DEPTH = 3;
	public static final int SCRUBBER_WIDTH = 3;
	public static final int[] SCRUBBER_SIZE = {SCRUBBER_HEIGHT, SCRUBBER_DEPTH, SCRUBBER_WIDTH};

	//	=================================
	//		GAS TURBINE
	//	=================================
	/**
	 * H, L, W. Long and low: intake house, nacelle, exhaust stack in a line.
	 */
	public static final int TURBINE_HEIGHT = 3;
	public static final int TURBINE_DEPTH = 6;
	public static final int TURBINE_WIDTH = 3;
	public static final int[] TURBINE_SIZE = {TURBINE_HEIGHT, TURBINE_DEPTH, TURBINE_WIDTH};

	//	=================================
	//		FUEL OIL BOILER
	//	=================================
	/**
	 * H, L, W. The furnace half of a power station: a firing floor with a water wall above it.
	 */
	public static final int BOILER_HEIGHT = 5;
	public static final int BOILER_DEPTH = 5;
	public static final int BOILER_WIDTH = 7;
	public static final int[] BOILER_SIZE = {BOILER_HEIGHT, BOILER_DEPTH, BOILER_WIDTH};

	//	=================================
	//		HEAT RECOVERY STEAM GENERATOR
	//	=================================
	/**
	 * H, L, W. Sized to sit in line behind a Gas Turbine, whose exhaust end is three wide and
	 * three tall -- the HRSG has to present a matching face or it could not butt against it.
	 */
	public static final int HRSG_HEIGHT = 3;
	public static final int HRSG_DEPTH = 5;
	public static final int HRSG_WIDTH = 3;
	public static final int[] HRSG_SIZE = {HRSG_HEIGHT, HRSG_DEPTH, HRSG_WIDTH};

	//	=================================
	//		STEAM TURBINE HALL
	//	=================================
	/**
	 * H, L, W. The largest structure in the expansion: condenser, generator hall, switchyard in
	 * a line down the long axis.
	 */
	public static final int HALL_HEIGHT = 5;
	public static final int HALL_DEPTH = 9;
	public static final int HALL_WIDTH = 5;
	public static final int[] HALL_SIZE = {HALL_HEIGHT, HALL_DEPTH, HALL_WIDTH};

	//	=================================
	//		RECIPROCATING ENGINE BANK
	//	=================================
	/**
	 * H, L, W. One bank. Scaling is done by building another alongside rather than by growing
	 * this one -- see {@code TileEntityEngineBank}.
	 */
	public static final int ENGINE_HEIGHT = 4;
	public static final int ENGINE_DEPTH = 5;
	public static final int ENGINE_WIDTH = 5;
	public static final int[] ENGINE_SIZE = {ENGINE_HEIGHT, ENGINE_DEPTH, ENGINE_WIDTH};

	//	=================================
	//		CRACKING UNIT
	//	=================================
	/**
	 * H, L, W. Two reactor columns with a coke drum between them: wide rather than tall, so it
	 * reads as a different kind of object from the Distillation Tower it stands beside.
	 */
	public static final int CRACKER_HEIGHT = 6;
	public static final int CRACKER_DEPTH = 3;
	public static final int CRACKER_WIDTH = 5;
	public static final int[] CRACKER_SIZE = {CRACKER_HEIGHT, CRACKER_DEPTH, CRACKER_WIDTH};

	//	=================================
	//		FLUID LOADING GANTRY
	//	=================================
	/**
	 * H, L, W. Two legs and a beam over a one-block bay: the smallest shape that reads as a gantry
	 * rather than as a frame, and the only one whose bay is wide enough to stand a chest in either
	 * side of.
	 */
	public static final int GANTRY_HEIGHT = 4;
	public static final int GANTRY_DEPTH = 1;
	public static final int GANTRY_WIDTH = 3;
	public static final int[] GANTRY_SIZE = {GANTRY_HEIGHT, GANTRY_DEPTH, GANTRY_WIDTH};

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
