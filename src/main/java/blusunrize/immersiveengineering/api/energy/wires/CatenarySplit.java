/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Deciding where a wire's two halves meet.
 * <p>
 * A wire is drawn twice, once from each end, and each half is baked into the chunk of the connector
 * that drew it. That is what stops a wire vanishing wholesale when the chunk at one end is culled --
 * each end owns the part of the wire nearest itself. The two halves have to meet somewhere, and the
 * meeting point is put on a chunk boundary so that a half whose far end never arrives fades out at a
 * chunk edge rather than in mid-air.
 * <p>
 * <strong>The two ends must choose the same meeting point, and choosing it from the drawn curve does
 * not achieve that.</strong> Each end computes the whole catenary itself, and to do so it needs the
 * attachment offset of the connector at <em>both</em> ends -- see {@link ApiUtils#getVecForIICAt},
 * which quietly answers {@link Vec3d#ZERO} when the tile entity it is asking about is not loaded. So
 * an end that bakes while the far end's chunk is absent computes a curve displaced by about half a
 * block, counts a different number of chunk-boundary crossings, and picks a different meeting point
 * from the one the far end picked. Everything between the two choices is drawn by neither end.
 * <p>
 * That is a long wire with a hole in the middle of it: rare, because it needs a vertex to sit near a
 * chunk boundary; commonest on the longest runs, because those are the ones whose far end is usually
 * unloaded; and it appears to fix itself when anything forces the near chunk to bake again, because
 * by then the far end has usually loaded. Measured over several thousand endpoint geometries, a
 * disagreement costs up to eight of a wire's seventeen segments.
 * <p>
 * The fix is to choose the meeting point from something the two ends cannot disagree about. Both
 * know the two <em>block positions</em> exactly -- they are synced, and integers -- so the crossings
 * are counted along a curve built from those alone. The half-block attachment offsets are left out
 * deliberately: they are the very thing that can differ, and the meeting point does not need to be
 * accurate, only identical. Each end still draws its own vertices; they now simply stop at matching
 * indices.
 *
 * @author LDImmersiveEngineering -- wire rendering
 */
public final class CatenarySplit
{
	private CatenarySplit()
	{
	}

	/**
	 * How many vertices a catenary has, and therefore how the two ends' indices line up: vertex
	 * {@code i} from one end is vertex {@code COUNT-1-i} from the other, so segment {@code i} from
	 * one end is segment {@code COUNT-i} from the other.
	 */
	public static final int COUNT = Connection.vertices+1;

	/**
	 * Where this end should stop drawing.
	 *
	 * @param start the connector doing the drawing
	 * @param end   the connector at the other end of the wire
	 * @param slack the cable type's slack, which both ends agree on because it comes from the wire
	 *
	 * @return the index one past the last segment this end draws, in this end's own indexing --
	 * exactly the {@code max} the render loop wants. The two ends' answers always cover the whole
	 * wire, with a segment or two of overlap and never a gap.
	 */
	public static int drawUpTo(BlockPos start, BlockPos end, double slack)
	{
		//Which end is which is decided by comparing the positions, so the two ends agree on that too.
		boolean greater = start.compareTo(end) > 0;
		BlockPos low = greater?end: start;
		BlockPos high = greater?start: end;

		Vec3d[] canonical = canonicalCurve(low, high, slack);
		List<Integer> crossings = new ArrayList<>();
		for(int i = 1; i < canonical.length; i++)
			if(crossesChunkBoundary(canonical[i], canonical[i-1], low))
				crossings.add(i);

		//A wire that never leaves its chunk has no boundary to meet on, so one end draws all of it
		//rather than the two splitting an arbitrary vertex. Which end does not matter; it only has to
		//be the same choice at both.
		if(crossings.isEmpty())
			return greater?canonical.length+1: 0;

		//The middle crossing, counted from the low end. Both ends compute this identically because
		//the curve above is identical; the only difference is which way they then read it.
		int split = crossings.get(crossings.size()/2);
		//The low end draws up to the meeting point; the high end draws back from its own start to the
		//same place, in its own reversed indexing. The +1 and +2 leave a segment of overlap so the
		//two halves cannot leave a hairline gap between them.
		return greater?(canonical.length-split)+1: split+2;
	}

	/**
	 * The curve the meeting point is chosen along: block position to block position, no attachment
	 * offsets.
	 * <p>
	 * Using integer positions is not only what makes the two ends agree -- it also keeps the
	 * catenary maths away from its own degenerate case. The horizontal distance here is either zero,
	 * which is handled separately, or at least one whole block.
	 */
	private static Vec3d[] canonicalCurve(BlockPos low, BlockPos high, double slack)
	{
		Vec3d delta = new Vec3d(high.getX()-low.getX(), high.getY()-low.getY(), high.getZ()-low.getZ());
		if(delta.x==0&&delta.z==0)
		{
			//Straight up: no catenary to speak of, and the general formula divides by the horizontal
			//distance. Mirrors the vertical branch in ApiUtils.getConnectionCatenary.
			Vec3d[] vertical = new Vec3d[COUNT];
			for(int i = 0; i < COUNT; i++)
				vertical[i] = new Vec3d(0, i*delta.y/Connection.vertices, 0);
			return vertical;
		}
		return ApiUtils.getConnectionCatenary(Vec3d.ZERO, delta, slack);
	}

	/**
	 * Whether a segment of the curve leaves the chunk it started in, in any of the three axes.
	 *
	 * @param offset what the curve's coordinates are relative to
	 */
	public static boolean crossesChunkBoundary(Vec3d start, Vec3d end, BlockPos offset)
	{
		return crossesInOneDimension(start.x, end.x, offset.getX())
				||crossesInOneDimension(start.y, end.y, offset.getY())
				||crossesInOneDimension(start.z, end.z, offset.getZ());
	}

	private static boolean crossesInOneDimension(double a, double b, int offset)
	{
		return ((int)Math.floor(a+offset)) >> 4!=((int)Math.floor(b+offset)) >> 4;
	}
}
