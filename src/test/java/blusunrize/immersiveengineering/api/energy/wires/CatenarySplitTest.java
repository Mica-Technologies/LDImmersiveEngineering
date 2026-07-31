/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a wire's two halves meet.
 * <p>
 * A wire is drawn twice, once from each end, and the two halves have to cover it between them. When
 * they do not, the result is a long wire with a hole in the middle: no crash, nothing logged, and it
 * looks like the wire is simply not there. It also appears to fix itself whenever anything makes the
 * chunk bake again, which is exactly the sort of symptom that gets reported as "sometimes wires go
 * invisible" and never reproduced.
 * <p>
 * So the property worth testing is not any particular meeting point but the invariant behind it:
 * <strong>for any pair of endpoints, the two halves together cover every segment of the wire.</strong>
 * That is checked by brute force over a few thousand geometries, including the case that caused the
 * bug -- the two ends having computed slightly different curves.
 */
class CatenarySplitTest
{
	private static final double SLACK = 1.005;

	/**
	 * Which segments of the wire get drawn, in the low end's indexing.
	 * <p>
	 * Segment {@code i} runs between vertices {@code i-1} and {@code i}, so a wire of
	 * {@link CatenarySplit#COUNT} vertices has {@code COUNT-1} segments, numbered from 1. The high
	 * end draws in reverse, so its segment {@code i} is the low end's segment {@code COUNT-i}.
	 */
	private static boolean[] coverage(BlockPos a, BlockPos b)
	{
		int segments = CatenarySplit.COUNT-1;
		boolean[] drawn = new boolean[segments+1];

		int maxA = CatenarySplit.drawUpTo(a, b, SLACK);
		int maxB = CatenarySplit.drawUpTo(b, a, SLACK);
		boolean aIsHigh = a.compareTo(b) > 0;

		for(int i = 1; i < maxA&&i <= segments; i++)
			drawn[aIsHigh?CatenarySplit.COUNT-i: i] = true;
		for(int i = 1; i < maxB&&i <= segments; i++)
			drawn[aIsHigh?i: CatenarySplit.COUNT-i] = true;
		return drawn;
	}

	private static List<Integer> gapsBetween(BlockPos a, BlockPos b)
	{
		boolean[] drawn = coverage(a, b);
		List<Integer> missing = new ArrayList<>();
		for(int i = 1; i < drawn.length; i++)
			if(!drawn[i])
				missing.add(i);
		return missing;
	}

	@Nested
	@DisplayName("the invariant")
	class Coverage
	{
		@Test
		@DisplayName("the two halves cover the whole wire, for every geometry tried")
		void everyWireIsFullyCovered()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//
			// Swept rather than spot-checked, because the failure depends on where the wire's
			// vertices happen to fall relative to chunk boundaries -- which is to say, on the exact
			// numbers. A handful of hand-picked cases would have passed against the old code too.
			int checked = 0;
			for(int dx = -48; dx <= 48; dx++)
				for(int dz = -48; dz <= 48; dz += 5)
					for(int dy = -8; dy <= 8; dy += 4)
					{
						BlockPos a = new BlockPos(9, 71, 6);
						BlockPos b = a.add(dx, dy, dz);
						if(a.equals(b))
							continue;
						checked++;
						List<Integer> gaps = gapsBetween(a, b);
						assertTrue(gaps.isEmpty(),
								"segments "+gaps+" of the wire "+a+" -> "+b+" are drawn by neither end");
					}
			assertTrue(checked > 4000, "the sweep stopped covering anything meaningful");
		}

		@Test
		@DisplayName("it holds for a wire that never leaves its chunk")
		void shortWireIsCovered()
		{
			//No chunk boundary to meet on. One end has to take the whole wire, and the other has to
			//know that it did.
			assertTrue(gapsBetween(new BlockPos(4, 70, 4), new BlockPos(6, 70, 7)).isEmpty());
		}

		@Test
		@DisplayName("it holds for a wire going straight up")
		void verticalWireIsCovered()
		{
			//The catenary formula divides by the horizontal distance, so a vertical wire takes a
			//different path through the code and would otherwise produce NaN vertices, no crossings
			//at all, and a wire drawn by neither end.
			assertTrue(gapsBetween(new BlockPos(8, 60, 8), new BlockPos(8, 100, 8)).isEmpty());
			assertTrue(gapsBetween(new BlockPos(8, 100, 8), new BlockPos(8, 60, 8)).isEmpty());
		}
	}

	@Nested
	@DisplayName("why it holds")
	class Symmetry
	{
		@Test
		@DisplayName("the meeting point does not depend on the curve either end drew")
		void splitIgnoresTheDrawnCurve()
		{
			//	=================================
			//	The actual fix.
			//	=================================
			//
			// The bug was that each end chose the meeting point by counting chunk crossings along the
			// curve *it* had computed. An end that bakes while the far end's chunk is unloaded gets
			// Vec3d.ZERO for the far attachment point instead of the real half-block offset, computes
			// a slightly different curve, counts differently and picks a different meeting point.
			//
			// drawUpTo takes only the two block positions and the slack -- all of which both ends
			// have exactly and identically -- so there is no longer anything for them to disagree
			// about. This test says so by construction: the signature has nowhere to put a curve.
			BlockPos a = new BlockPos(9, 71, 6);
			BlockPos b = new BlockPos(-21, 65, 15);
			int first = CatenarySplit.drawUpTo(a, b, SLACK);
			for(int i = 0; i < 50; i++)
				assertEquals(first, CatenarySplit.drawUpTo(a, b, SLACK),
						"the meeting point is not a pure function of the endpoints");
		}

		@Test
		@DisplayName("the two ends split at the same place, from opposite directions")
		void endsAgree()
		{
			//A long, slightly falling run of the sort the bug showed up on.
			BlockPos a = new BlockPos(8, 70, 8);
			BlockPos b = new BlockPos(-21, 64, 17);
			int maxLow = CatenarySplit.drawUpTo(a.compareTo(b) > 0?b: a, a.compareTo(b) > 0?a: b, SLACK);
			int maxHigh = CatenarySplit.drawUpTo(a.compareTo(b) > 0?a: b, a.compareTo(b) > 0?b: a, SLACK);
			//The overlap is deliberate: the halves share a segment or two rather than meeting exactly,
			//so no rounding can open a hairline gap between them.
			assertTrue(maxLow+maxHigh >= CatenarySplit.COUNT+1,
					"the two halves meet with a gap between them: "+maxLow+" + "+maxHigh
							+" does not reach "+(CatenarySplit.COUNT+1));
			assertTrue(gapsBetween(a, b).isEmpty());
		}

		@Test
		@DisplayName("each end draws the part of the wire nearest itself")
		void eachEndDrawsItsOwnSide()
		{
			//The point of splitting at all: a wire's geometry is baked into the chunk of whichever
			//connector drew it, so the half you can see has to be the half whose chunk you are
			//standing next to. An end drawing the whole wire, or the far half, would put the geometry
			//in the wrong chunk and bring the culling problem back.
			BlockPos a = new BlockPos(9, 71, 6);
			BlockPos b = a.add(60, 0, 0);
			int maxLow = CatenarySplit.drawUpTo(a, b, SLACK);
			assertTrue(maxLow > 1, "the low end drew nothing at all");
			assertTrue(maxLow < CatenarySplit.COUNT, "the low end drew the entire wire");
		}
	}
}
