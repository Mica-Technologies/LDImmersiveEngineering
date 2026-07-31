/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deciding which block a ground feeder should pretend to be.
 * <p>
 * A feeder is a hole in a wall or a floor that a run goes through, and the whole point of it is that
 * you cannot see it. So it wears the surroundings -- and "the surroundings" has to mean what a
 * player standing there would say it means, not what a naive tally says.
 * <p>
 * <strong>Counting neighbours does not work.</strong> The obvious rule -- most common of the
 * twenty-six blocks around it -- gets the single most important case wrong. A feeder set into a
 * grass field has eight grass blocks around it at its own level and nine dirt blocks underneath, so
 * a straight count wears dirt, in the middle of a lawn, which is the one answer nobody wanted. The
 * count is not wrong about the numbers; it is wrong about which blocks are the surroundings.
 * <p>
 * So a candidate is weighted twice:
 * <ul>
 *     <li><strong>Exposed blocks count for more.</strong> A block with nothing but solid rock
 *     against it is not part of what anyone sees. The nine buried dirt blocks under a lawn are
 *     invisible; the ring of grass is the lawn.</li>
 *     <li><strong>Blocks you actually touch count for more.</strong> A face-neighbour is a stronger
 *     claim on what a feeder should look like than a block meeting it at a corner.</li>
 * </ul>
 * That is enough to make the lawn come out grass -- thirty-six to ten -- and it still says stone
 * for a feeder buried in bedrock, because down there the stone is what is exposed to the shaft.
 * <p>
 * World-free, like {@link ConduitRoute} and {@link ConduitPlacement}: the world arrives as a
 * {@link Neighbourhood} and the candidates are opaque tokens, so the rule can be tested against a
 * lawn drawn out of strings instead of against a real one.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class ConduitCamouflage
{
	private ConduitCamouflage()
	{
	}

	/**
	 * How far the survey looks: one block, so a 3x3x3 with the feeder itself cut out.
	 * <p>
	 * Deliberately not more. Two would take in a hundred and twenty-four blocks per placement and
	 * per neighbour change, and would start reporting on terrain the feeder is nowhere near -- a
	 * feeder in a stone doorway would begin wearing the grass of the field outside.
	 */
	public static final int RADIUS = 1;

	/** What a block sharing a face with the feeder is worth against one meeting it at a corner. */
	static final int TOUCHING = 2;

	/** What being visible is worth against being buried. This is what wins the lawn. */
	static final int EXPOSED = 3;

	/**
	 * The world, in the shape the rule asks for.
	 *
	 * @param <T> whatever identifies a block well enough to be counted -- an {@code IBlockState} in
	 *            the game, a string in a test. Needs {@code equals} and {@code hashCode}.
	 */
	public interface Neighbourhood<T>
	{
		/**
		 * @return something identifying the block at that position, or null if it is not the sort of
		 * block a feeder could pass for. Air, half-blocks, anything with a tile entity and the
		 * conduit hardware itself all answer null -- see
		 * {@code TileEntityGroundFeeder} for where that line is drawn.
		 */
		@Nullable
		T candidateAt(BlockPos pos);

		/**
		 * @return true if the block at that position can be seen from anywhere -- that is, if any of
		 * its six faces has something other than a solid block against it.
		 */
		boolean isExposed(BlockPos pos);
	}

	/**
	 * Pick the block a feeder at {@code centre} should wear.
	 * <p>
	 * Ties go to whichever candidate the scan met first, and the scan order is fixed, so the same
	 * surroundings always give the same answer. A feeder that redecorated itself every time a
	 * neighbour changed would be worse than one that guessed wrong: wrong is at least something you
	 * can pin with a right-click.
	 *
	 * @return the winning candidate, or null if there was nothing around worth wearing
	 */
	@Nullable
	public static <T> T choose(BlockPos centre, Neighbourhood<T> around)
	{
		//LinkedHashMap rather than HashMap: the tie-break is "first one the scan met", and that is
		//only stable if iteration order is insertion order.
		Map<T, Integer> score = new LinkedHashMap<>();
		for(int dy = -RADIUS; dy <= RADIUS; dy++)
			for(int dz = -RADIUS; dz <= RADIUS; dz++)
				for(int dx = -RADIUS; dx <= RADIUS; dx++)
				{
					if(dx==0&&dy==0&&dz==0)
						continue;
					BlockPos at = centre.add(dx, dy, dz);
					T candidate = around.candidateAt(at);
					if(candidate==null)
						continue;
					int weight = 1;
					//Exactly one step away on exactly one axis: a face-neighbour rather than an edge
					//or a corner.
					if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)==1)
						weight *= TOUCHING;
					if(around.isExposed(at))
						weight *= EXPOSED;
					Integer running = score.get(candidate);
					score.put(candidate, (running==null?0: running)+weight);
				}

		T best = null;
		int bestScore = 0;
		//Strictly greater, so the first candidate to reach a score keeps it against a later tie.
		for(Map.Entry<T, Integer> entry : score.entrySet())
			if(entry.getValue() > bestScore)
			{
				best = entry.getKey();
				bestScore = entry.getValue();
			}
		return best;
	}
}
