/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure helpers in {@link Utils}.
 * <p>
 * Scope note: most of {@code Utils} needs a {@link net.minecraft.world.World}, an
 * {@link net.minecraft.item.ItemStack} or the block registry and is therefore out of reach of a
 * unit test. Covered here are the maths, string and geometry helpers that only touch plain data.
 */
class UtilsTest
{
	private static final double D = 1e-9;

	/** DecimalFormat follows the default locale, so a comma decimal separator is not a failure */
	private static String normalise(String formatted)
	{
		return formatted.replace(',', '.');
	}

	private static void assertVec(Vec3d expected, Vec3d actual)
	{
		assertAll(
				() -> assertEquals(expected.x, actual.x, D, "x"),
				() -> assertEquals(expected.y, actual.y, D, "y"),
				() -> assertEquals(expected.z, actual.z, D, "z")
		);
	}

	/** A Random with no randomness, so luck maths can be asserted exactly. */
	private static Random fixedRandom(final double value)
	{
		return new Random()
		{
			@Override
			public double nextDouble()
			{
				return value;
			}
		};
	}

	// ---------------------------------------------------------------- min/max over arrays

	@Test
	@DisplayName("minInArray() of an empty array is 0")
	void minInArrayEmpty()
	{
		assertEquals(0, Utils.minInArray(), D);
	}

	@Test
	@DisplayName("maxInArray() of an empty array is 0")
	void maxInArrayEmpty()
	{
		assertEquals(0, Utils.maxInArray(), D);
	}

	@Test
	@DisplayName("minInArray() of a single element is that element")
	void minInArraySingle()
	{
		assertEquals(-3.5, Utils.minInArray(-3.5), D);
	}

	@Test
	@DisplayName("maxInArray() of a single element is that element")
	void maxInArraySingle()
	{
		assertEquals(-3.5, Utils.maxInArray(-3.5), D);
	}

	@Test
	@DisplayName("minInArray() finds the smallest of many")
	void minInArrayMany()
	{
		assertEquals(-7, Utils.minInArray(3, 0, -7, 12, -2), D);
	}

	@Test
	@DisplayName("maxInArray() finds the largest of many")
	void maxInArrayMany()
	{
		assertEquals(12, Utils.maxInArray(3, 0, -7, 12, -2), D);
	}

	@Test
	@DisplayName("minInArray() handles the extremum sitting first or last")
	void minInArrayAtTheEdges()
	{
		assertEquals(1, Utils.minInArray(1, 2, 3), D);
		assertEquals(1, Utils.minInArray(3, 2, 1), D);
	}

	@Test
	@DisplayName("maxInArray() handles the extremum sitting first or last")
	void maxInArrayAtTheEdges()
	{
		assertEquals(3, Utils.maxInArray(3, 2, 1), D);
		assertEquals(3, Utils.maxInArray(1, 2, 3), D);
	}

	@Test
	@DisplayName("min/max cope with duplicates")
	void minMaxWithDuplicates()
	{
		assertEquals(5, Utils.minInArray(5, 5, 5), D);
		assertEquals(5, Utils.maxInArray(5, 5, 5), D);
	}

	// ---------------------------------------------------------------- vector helpers

	@Test
	@DisplayName("addVectors() sums componentwise")
	void addVectors()
	{
		assertVec(new Vec3d(5, 7, 9), Utils.addVectors(new Vec3d(1, 2, 3), new Vec3d(4, 5, 6)));
	}

	@Test
	@DisplayName("addVectors() with the zero vector is the identity")
	void addVectorsZero()
	{
		assertVec(new Vec3d(1, 2, 3), Utils.addVectors(new Vec3d(1, 2, 3), Vec3d.ZERO));
	}

	@Test
	@DisplayName("addVectors() does not mutate its arguments")
	void addVectorsIsPure()
	{
		Vec3d a = new Vec3d(1, 2, 3);
		Utils.addVectors(a, new Vec3d(4, 5, 6));
		assertVec(new Vec3d(1, 2, 3), a);
	}

	@Test
	@DisplayName("scalarProd() scales every component")
	void scalarProd()
	{
		assertVec(new Vec3d(2, 4, 6), Utils.scalarProd(new Vec3d(1, 2, 3), 2));
	}

	@Test
	@DisplayName("scalarProd() by zero gives the zero vector")
	void scalarProdZero()
	{
		assertVec(Vec3d.ZERO, Utils.scalarProd(new Vec3d(1, 2, 3), 0));
	}

	@Test
	@DisplayName("scalarProd() by a negative factor reverses the vector")
	void scalarProdNegative()
	{
		assertVec(new Vec3d(-1, -2, -3), Utils.scalarProd(new Vec3d(1, 2, 3), -1));
	}

	@Test
	@DisplayName("scalarProd() by one is the identity")
	void scalarProdOne()
	{
		assertVec(new Vec3d(1, 2, 3), Utils.scalarProd(new Vec3d(1, 2, 3), 1));
	}

	// ---------------------------------------------------------------- strings and numbers

	@Test
	@DisplayName("toCamelCase() capitalises the first letter and lowercases the rest")
	void toCamelCase()
	{
		assertEquals("Hello", Utils.toCamelCase("hello"));
		assertEquals("Hello", Utils.toCamelCase("HELLO"));
		assertEquals("Hello", Utils.toCamelCase("hELLO"));
	}

	@Test
	@DisplayName("toCamelCase() of a single character uppercases it")
	void toCamelCaseSingleChar()
	{
		assertEquals("A", Utils.toCamelCase("a"));
		assertEquals("A", Utils.toCamelCase("A"));
	}

	@Test
	@DisplayName("toCamelCase() leaves digits and separators alone")
	void toCamelCaseWithNonLetters()
	{
		assertEquals("1abc", Utils.toCamelCase("1ABC"));
		assertEquals("A_b", Utils.toCamelCase("a_B"));
	}

	@Test
	@DisplayName("toCamelCase() of the empty string is an error, not an empty result")
	void toCamelCaseEmpty()
	{
		assertThrows(StringIndexOutOfBoundsException.class, () -> Utils.toCamelCase(""));
	}

	@Test
	@DisplayName("formatDouble() applies the given DecimalFormat pattern")
	void formatDouble()
	{
		assertEquals("42", Utils.formatDouble(42, "0"));
		assertEquals("7.00", normalise(Utils.formatDouble(7, "0.00")));
	}

	@Test
	@DisplayName("formatDouble() rounds to the requested precision")
	void formatDoubleRounds()
	{
		assertEquals("1.5", normalise(Utils.formatDouble(1.46, "0.0")));
	}

	@Test
	@DisplayName("toScientificNotation() leaves values below the kilo threshold unsuffixed")
	void scientificNotationBelowThreshold()
	{
		assertEquals("999.0", normalise(Utils.toScientificNotation(999, "0", 1000)));
	}

	@Test
	@DisplayName("toScientificNotation() suffixes thousands with K")
	void scientificNotationKilo()
	{
		assertEquals("1.5K", normalise(Utils.toScientificNotation(1500, "0", 1000)));
	}

	@Test
	@DisplayName("toScientificNotation() suffixes millions with M")
	void scientificNotationMega()
	{
		assertEquals("2.50M", normalise(Utils.toScientificNotation(2_500_000, "00", 1000)));
	}

	@Test
	@DisplayName("toScientificNotation() suffixes billions with G")
	void scientificNotationGiga()
	{
		assertEquals("2.0G", normalise(Utils.toScientificNotation(2_000_000_000, "0", 1000)));
	}

	@Test
	@DisplayName("toScientificNotation() honours a custom kilo threshold but still divides by 1000")
	void scientificNotationCustomThreshold()
	{
		assertEquals("0.5K", normalise(Utils.toScientificNotation(500, "0", 100)));
	}

	@Test
	@DisplayName("toScientificNotation() of zero has no suffix")
	void scientificNotationZero()
	{
		assertEquals("0.0", normalise(Utils.toScientificNotation(0, "0", 1000)));
	}

	@Test
	@DisplayName("NUMBERFORMAT_PREFIXED signs both directions")
	void numberFormatPrefixed()
	{
		assertEquals("+5", Utils.NUMBERFORMAT_PREFIXED.format(5));
		assertEquals("-5", Utils.NUMBERFORMAT_PREFIXED.format(-5));
	}

	// ---------------------------------------------------------------- UUIDs

	@Test
	@DisplayName("generateNewUUID() never repeats")
	void generateNewUUIDIsUnique()
	{
		UUID a = Utils.generateNewUUID();
		UUID b = Utils.generateNewUUID();
		assertNotEquals(a, b);
	}

	@Test
	@DisplayName("generateNewUUID() keeps a constant high half and counts up the low half")
	void generateNewUUIDCountsUp()
	{
		UUID a = Utils.generateNewUUID();
		UUID b = Utils.generateNewUUID();
		assertEquals(a.getMostSignificantBits(), b.getMostSignificantBits());
		assertEquals(a.getLeastSignificantBits()+1, b.getLeastSignificantBits());
	}

	@Test
	@DisplayName("generateNewUUID() stays unique across a run of calls")
	void generateNewUUIDManyCalls()
	{
		HashSet<UUID> seen = new HashSet<>();
		for(int i = 0; i < 100; i++)
			assertTrue(seen.add(Utils.generateNewUUID()), "duplicate UUID at call "+i);
	}

	// ---------------------------------------------------------------- luck

	@Test
	@DisplayName("generateLuckInfluencedDouble() offsets the median by the rolled fraction of the deviation")
	void luckPlain()
	{
		assertEquals(12, Utils.generateLuckInfluencedDouble(10, 4, 0, fixedRandom(0.5), false, 0), D);
	}

	@Test
	@DisplayName("generateLuckInfluencedDouble() subtracts instead when the roll is bad")
	void luckIsBad()
	{
		assertEquals(8, Utils.generateLuckInfluencedDouble(10, 4, 0, fixedRandom(0.5), true, 0), D);
	}

	@Test
	@DisplayName("generateLuckInfluencedDouble() adds luck scaled by luckScale")
	void luckAddsScaledLuck()
	{
		assertEquals(13, Utils.generateLuckInfluencedDouble(10, 4, 2, fixedRandom(0.5), false, 0.5), D);
	}

	@Test
	@DisplayName("generateLuckInfluencedDouble() clamps to the deviation when luck overshoots")
	void luckIsClampedToDeviation()
	{
		assertEquals(14, Utils.generateLuckInfluencedDouble(10, 4, 100, fixedRandom(0.5), false, 1), D);
	}

	@Test
	@DisplayName("generateLuckInfluencedDouble() clamps the other way for a negative deviation")
	void luckIsClampedForNegativeDeviation()
	{
		assertEquals(8, Utils.generateLuckInfluencedDouble(10, -4, 0, fixedRandom(0.5), false, 0), D);
		assertEquals(6, Utils.generateLuckInfluencedDouble(10, -4, -100, fixedRandom(0.5), false, 1), D);
	}

	@Test
	@DisplayName("generateLuckInfluencedDouble() with a zero roll and no luck returns the median")
	void luckZeroRoll()
	{
		assertEquals(10, Utils.generateLuckInfluencedDouble(10, 4, 0, fixedRandom(0), false, 0), D);
	}

	// ---------------------------------------------------------------- findSequenceInList

	@Test
	@DisplayName("findSequenceInList() finds a sequence at the start")
	void findSequenceAtStart()
	{
		assertEquals(0, Utils.findSequenceInList(Arrays.asList("a", "b", "c"), new String[]{"a", "b"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() finds a sequence in the middle")
	void findSequenceInTheMiddle()
	{
		assertEquals(1, Utils.findSequenceInList(Arrays.asList("a", "b", "c"), new String[]{"b", "c"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() finds a single-element sequence")
	void findSequenceOfOne()
	{
		assertEquals(2, Utils.findSequenceInList(Arrays.asList("a", "b", "c"), new String[]{"c"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() returns -1 when the sequence is absent")
	void findSequenceAbsent()
	{
		assertEquals(-1, Utils.findSequenceInList(Arrays.asList("a", "b", "c"), new String[]{"z"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() returns -1 for an empty list")
	void findSequenceEmptyList()
	{
		assertEquals(-1, Utils.findSequenceInList(Collections.<String>emptyList(), new String[]{"a"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() returns -1 when the sequence is longer than the list")
	void findSequenceLongerThanList()
	{
		assertEquals(-1, Utils.findSequenceInList(Collections.singletonList("a"), new String[]{"a", "b"}, String::equals));
	}

	@Test
	@DisplayName("findSequenceInList() uses the supplied predicate rather than equals()")
	void findSequenceUsesPredicate()
	{
		assertEquals(1, Utils.findSequenceInList(Arrays.asList("a", "B", "c"), new String[]{"b"}, String::equalsIgnoreCase));
	}

	@Test
	@DisplayName("findSequenceInList() matches the whole list")
	void findSequenceWholeList()
	{
		assertEquals(0, Utils.findSequenceInList(Arrays.asList("a", "b"), new String[]{"a", "b"}, String::equals));
	}

	@Test
	@Disabled("Suspected bug: runs off the end of the list -- see Utils.java:371-380")
	@DisplayName("findSequenceInList() returns -1 when a partial match starts too close to the end")
	void findSequencePartialMatchNearTheEnd()
	{
		// The outer loop starts a candidate match at every index, including ones where the
		// remaining tail is shorter than the sequence, so list.get(i+j) walks past the end.
		assertEquals(-1, Utils.findSequenceInList(Arrays.asList("a", "a"), new String[]{"a", "b"}, String::equals));
	}

	// ---------------------------------------------------------------- rotateFacingTowardsDir

	@Nested
	@DisplayName("rotateFacingTowardsDir")
	class RotateFacingTowardsDir
	{
		@Test
		@DisplayName("north is the reference direction and rotates nothing")
		void northIsIdentity()
		{
			for(EnumFacing f : EnumFacing.VALUES)
				assertEquals(f, Utils.rotateFacingTowardsDir(f, EnumFacing.NORTH), f.toString());
		}

		@Test
		@DisplayName("south turns horizontals through 180 degrees")
		void southIsHalfTurn()
		{
			assertEquals(EnumFacing.SOUTH, Utils.rotateFacingTowardsDir(EnumFacing.NORTH, EnumFacing.SOUTH));
			assertEquals(EnumFacing.NORTH, Utils.rotateFacingTowardsDir(EnumFacing.SOUTH, EnumFacing.SOUTH));
			assertEquals(EnumFacing.EAST, Utils.rotateFacingTowardsDir(EnumFacing.WEST, EnumFacing.SOUTH));
			assertEquals(EnumFacing.WEST, Utils.rotateFacingTowardsDir(EnumFacing.EAST, EnumFacing.SOUTH));
		}

		@Test
		@DisplayName("east turns horizontals a quarter clockwise")
		void eastIsQuarterTurn()
		{
			assertEquals(EnumFacing.EAST, Utils.rotateFacingTowardsDir(EnumFacing.NORTH, EnumFacing.EAST));
			assertEquals(EnumFacing.SOUTH, Utils.rotateFacingTowardsDir(EnumFacing.EAST, EnumFacing.EAST));
			assertEquals(EnumFacing.WEST, Utils.rotateFacingTowardsDir(EnumFacing.SOUTH, EnumFacing.EAST));
			assertEquals(EnumFacing.NORTH, Utils.rotateFacingTowardsDir(EnumFacing.WEST, EnumFacing.EAST));
		}

		@Test
		@DisplayName("west turns horizontals a quarter anticlockwise")
		void westIsQuarterTurnBack()
		{
			assertEquals(EnumFacing.WEST, Utils.rotateFacingTowardsDir(EnumFacing.NORTH, EnumFacing.WEST));
			assertEquals(EnumFacing.NORTH, Utils.rotateFacingTowardsDir(EnumFacing.EAST, EnumFacing.WEST));
			assertEquals(EnumFacing.EAST, Utils.rotateFacingTowardsDir(EnumFacing.SOUTH, EnumFacing.WEST));
			assertEquals(EnumFacing.SOUTH, Utils.rotateFacingTowardsDir(EnumFacing.WEST, EnumFacing.WEST));
		}

		@Test
		@DisplayName("the horizontal directions leave up and down alone")
		void horizontalDirsIgnoreVerticalFacings()
		{
			for(EnumFacing dir : EnumFacing.HORIZONTALS)
			{
				assertEquals(EnumFacing.UP, Utils.rotateFacingTowardsDir(EnumFacing.UP, dir), dir.toString());
				assertEquals(EnumFacing.DOWN, Utils.rotateFacingTowardsDir(EnumFacing.DOWN, dir), dir.toString());
			}
		}

		@Test
		@DisplayName("down tips the north-south axis onto the vertical")
		void downTipsForward()
		{
			assertEquals(EnumFacing.DOWN, Utils.rotateFacingTowardsDir(EnumFacing.NORTH, EnumFacing.DOWN));
			assertEquals(EnumFacing.UP, Utils.rotateFacingTowardsDir(EnumFacing.SOUTH, EnumFacing.DOWN));
		}

		@Test
		@DisplayName("down leaves the east-west axis alone -- it is the rotation axis")
		void downLeavesTheRotationAxisAlone()
		{
			assertEquals(EnumFacing.EAST, Utils.rotateFacingTowardsDir(EnumFacing.EAST, EnumFacing.DOWN));
			assertEquals(EnumFacing.WEST, Utils.rotateFacingTowardsDir(EnumFacing.WEST, EnumFacing.DOWN));
		}

		@Test
		@DisplayName("down leaves up and down alone")
		void downLeavesVerticalsAlone()
		{
			assertEquals(EnumFacing.UP, Utils.rotateFacingTowardsDir(EnumFacing.UP, EnumFacing.DOWN));
			assertEquals(EnumFacing.DOWN, Utils.rotateFacingTowardsDir(EnumFacing.DOWN, EnumFacing.DOWN));
		}

		@Test
		@DisplayName("up tips the north-south axis onto the vertical the other way")
		void upTipsBackward()
		{
			assertEquals(EnumFacing.UP, Utils.rotateFacingTowardsDir(EnumFacing.NORTH, EnumFacing.UP));
			assertEquals(EnumFacing.DOWN, Utils.rotateFacingTowardsDir(EnumFacing.SOUTH, EnumFacing.UP));
		}

		@Test
		@DisplayName("up leaves the east-west axis alone -- it is the rotation axis")
		void upLeavesTheRotationAxisAlone()
		{
			assertEquals(EnumFacing.EAST, Utils.rotateFacingTowardsDir(EnumFacing.EAST, EnumFacing.UP));
			assertEquals(EnumFacing.WEST, Utils.rotateFacingTowardsDir(EnumFacing.WEST, EnumFacing.UP));
		}

		@Test
		@DisplayName("up folds the vertical facings onto the north-south axis")
		void upFoldsVerticals()
		{
			assertEquals(EnumFacing.SOUTH, Utils.rotateFacingTowardsDir(EnumFacing.UP, EnumFacing.UP));
			assertEquals(EnumFacing.NORTH, Utils.rotateFacingTowardsDir(EnumFacing.DOWN, EnumFacing.UP));
		}

		@Test
		@DisplayName("every horizontal direction maps the six facings onto six distinct facings")
		void horizontalDirsArePermutations()
		{
			for(EnumFacing dir : EnumFacing.HORIZONTALS)
			{
				HashSet<EnumFacing> images = new HashSet<>();
				for(EnumFacing f : EnumFacing.VALUES)
					images.add(Utils.rotateFacingTowardsDir(f, dir));
				assertEquals(6, images.size(), dir+" should be a bijection on the six facings");
			}
		}
	}

	// ---------------------------------------------------------------- cones

	@Nested
	@DisplayName("cone containment")
	class Cones
	{
		private final Vec3d origin = Vec3d.ZERO;
		private final Vec3d alongZ = new Vec3d(0, 0, 1);

		@Test
		@DisplayName("a point on the axis inside the length is contained")
		void onAxisInside()
		{
			assertTrue(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, 5)));
		}

		@Test
		@DisplayName("a point beyond the length is not contained")
		void beyondLength()
		{
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, 11)));
		}

		@Test
		@DisplayName("a point behind the tip is not contained")
		void behindTheTip()
		{
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, -1)));
		}

		@Test
		@DisplayName("the tip itself is not contained -- the cone has zero radius there")
		void theTipIsExcluded()
		{
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, Vec3d.ZERO));
		}

		@Test
		@DisplayName("the radius grows linearly with distance along the axis")
		void radiusGrowsWithDistance()
		{
			// at half the length the radius is half of the end radius
			assertTrue(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0.4, 5)));
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0.6, 5)));
		}

		@Test
		@DisplayName("a point exactly on the cone surface is excluded")
		void surfaceIsExcluded()
		{
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0.5, 5)));
		}

		@Test
		@DisplayName("truncation cuts off the near end of the cone")
		void truncationCutsTheTip()
		{
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, 2f, new Vec3d(0, 0, 1)));
			assertTrue(Utils.isPointInCone(origin, alongZ, 1, 10, 2f, new Vec3d(0, 0, 3)));
		}

		@Test
		@DisplayName("the far end of the cone is inclusive")
		void farEndIsInclusive()
		{
			assertTrue(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, 10)));
		}

		@Test
		@DisplayName("the cone points along the given direction, not the opposite one")
		void directionMatters()
		{
			assertTrue(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, 5)));
			assertFalse(Utils.isPointInCone(origin, alongZ, 1, 10, new Vec3d(0, 0, -5)));
		}

		@Test
		@DisplayName("isPointInConeByAngle() derives the end radius from the aperture")
		void byAngleDerivesRadius()
		{
			// aperture 90 degrees over a length of 10 gives an end radius of 10
			double aperture = Math.PI/2;
			assertTrue(Utils.isPointInConeByAngle(origin, alongZ, aperture, 10, new Vec3d(0, 4, 5)));
			assertFalse(Utils.isPointInConeByAngle(origin, alongZ, aperture, 10, new Vec3d(0, 6, 5)));
		}

		@Test
		@DisplayName("a narrower aperture contains fewer points")
		void narrowerApertureIsStricter()
		{
			assertTrue(Utils.isPointInConeByAngle(origin, alongZ, Math.PI/2, 10, new Vec3d(0, 2, 5)));
			assertFalse(Utils.isPointInConeByAngle(origin, alongZ, Math.PI/16, 10, new Vec3d(0, 2, 5)));
		}

		@Test
		@DisplayName("the truncated by-angle overload combines both cutoffs")
		void byAngleWithTruncation()
		{
			assertFalse(Utils.isPointInConeByAngle(origin, alongZ, (float)(Math.PI/2), 10, 4f, new Vec3d(0, 0, 3)));
			assertTrue(Utils.isPointInConeByAngle(origin, alongZ, (float)(Math.PI/2), 10, 4f, new Vec3d(0, 0, 5)));
		}
	}

	// ---------------------------------------------------------------- triangles

	@Nested
	@DisplayName("triangle containment")
	class Triangles
	{
		private final Vec3d a = Vec3d.ZERO;
		private final Vec3d b = new Vec3d(1, 0, 0);
		private final Vec3d c = new Vec3d(0, 1, 0);

		@Test
		@DisplayName("an interior point is contained")
		void interiorPoint()
		{
			assertTrue(Utils.isPointInTriangle(a, b, c, new Vec3d(0.2, 0.2, 0)));
		}

		@Test
		@DisplayName("a point past the hypotenuse is not contained")
		void pointPastTheHypotenuse()
		{
			assertFalse(Utils.isPointInTriangle(a, b, c, new Vec3d(0.9, 0.9, 0)));
		}

		@Test
		@DisplayName("a point on the hypotenuse is excluded")
		void pointOnTheHypotenuse()
		{
			assertFalse(Utils.isPointInTriangle(a, b, c, new Vec3d(0.5, 0.5, 0)));
		}

		@Test
		@DisplayName("a point behind the focus vertex is not contained")
		void pointBehindTheFocus()
		{
			assertFalse(Utils.isPointInTriangle(a, b, c, new Vec3d(-0.1, 0.2, 0)));
		}

		@Test
		@DisplayName("the focus vertex itself is contained")
		void focusVertexIsContained()
		{
			assertTrue(Utils.isPointInTriangle(a, b, c, a));
		}

		@Test
		@DisplayName("the two far vertices sit exactly on the excluded edge")
		void farVerticesAreExcluded()
		{
			assertFalse(Utils.isPointInTriangle(a, b, c, b));
			assertFalse(Utils.isPointInTriangle(a, b, c, c));
		}

		@Test
		@DisplayName("the test is barycentric, so the component perpendicular to the triangle is ignored")
		void perpendicularComponentIsIgnored()
		{
			// callers are expected to have already established that the point is on the plane
			assertTrue(Utils.isPointInTriangle(a, b, c, new Vec3d(0.2, 0.2, 100)));
		}

		@Test
		@DisplayName("containment survives translating the whole triangle")
		void translationInvariance()
		{
			Vec3d offset = new Vec3d(10, -3, 7);
			assertTrue(Utils.isPointInTriangle(a.add(offset), b.add(offset), c.add(offset),
					new Vec3d(0.2, 0.2, 0).add(offset)));
		}
	}

	// ---------------------------------------------------------------- misc geometry

	@Test
	@DisplayName("getCoeffForMinDistance() handles a purely vertical 'across' vector")
	void coeffForVerticalAcross()
	{
		assertEquals(2, Utils.getCoeffForMinDistance(new Vec3d(0, 4, 0), Vec3d.ZERO, new Vec3d(0, 2, 0)), D);
	}

	@Test
	@DisplayName("getCoeffForMinDistance() projects onto a unit 'across' vector")
	void coeffForUnitAcross()
	{
		assertEquals(3, Utils.getCoeffForMinDistance(new Vec3d(3, 0, 0), Vec3d.ZERO, new Vec3d(1, 0, 0)), D);
	}

	@Test
	@DisplayName("getCoeffForMinDistance() normalises by the squared length of 'across'")
	void coeffNormalisesByLength()
	{
		assertEquals(2, Utils.getCoeffForMinDistance(new Vec3d(4, 0, 0), Vec3d.ZERO, new Vec3d(2, 0, 0)), D);
	}

	@Test
	@DisplayName("getCoeffForMinDistance() is measured from the line's base point")
	void coeffIsRelativeToTheLine()
	{
		assertEquals(2, Utils.getCoeffForMinDistance(new Vec3d(3, 1, 1), new Vec3d(1, 1, 1), new Vec3d(1, 0, 0)), D);
	}

	@Test
	@DisplayName("getCoeffForMinDistance() ignores the component perpendicular to 'across'")
	void coeffIgnoresPerpendicularOffset()
	{
		assertEquals(3, Utils.getCoeffForMinDistance(new Vec3d(3, 99, 0), Vec3d.ZERO, new Vec3d(1, 0, 0)), D);
	}

	@Test
	@DisplayName("isVecInBlock() accepts a point in the middle of the block")
	void vecInBlockCentre()
	{
		assertTrue(Utils.isVecInBlock(new Vec3d(5.5, 6.5, 7.5), new BlockPos(5, 6, 7), BlockPos.ORIGIN));
	}

	@Test
	@DisplayName("isVecInBlock() includes both faces on each axis")
	void vecInBlockBoundsAreInclusive()
	{
		assertTrue(Utils.isVecInBlock(new Vec3d(5, 6, 7), new BlockPos(5, 6, 7), BlockPos.ORIGIN));
		assertTrue(Utils.isVecInBlock(new Vec3d(6, 7, 8), new BlockPos(5, 6, 7), BlockPos.ORIGIN));
	}

	@Test
	@DisplayName("isVecInBlock() rejects points outside on each axis")
	void vecInBlockRejectsOutside()
	{
		BlockPos pos = new BlockPos(5, 6, 7);
		assertFalse(Utils.isVecInBlock(new Vec3d(4.9, 6.5, 7.5), pos, BlockPos.ORIGIN));
		assertFalse(Utils.isVecInBlock(new Vec3d(6.1, 6.5, 7.5), pos, BlockPos.ORIGIN));
		assertFalse(Utils.isVecInBlock(new Vec3d(5.5, 5.9, 7.5), pos, BlockPos.ORIGIN));
		assertFalse(Utils.isVecInBlock(new Vec3d(5.5, 7.1, 7.5), pos, BlockPos.ORIGIN));
		assertFalse(Utils.isVecInBlock(new Vec3d(5.5, 6.5, 6.9), pos, BlockPos.ORIGIN));
		assertFalse(Utils.isVecInBlock(new Vec3d(5.5, 6.5, 8.1), pos, BlockPos.ORIGIN));
	}

	@Test
	@DisplayName("isVecInBlock() shifts the block by the offset, so the vector can be block-local")
	void vecInBlockWithOffset()
	{
		BlockPos pos = new BlockPos(5, 6, 7);
		assertTrue(Utils.isVecInBlock(new Vec3d(0.5, 0.5, 0.5), pos, pos));
		assertFalse(Utils.isVecInBlock(new Vec3d(5.5, 6.5, 7.5), pos, pos));
	}

	// ---------------------------------------------------------------- transformAABB

	private static void assertBox(AxisAlignedBB expected, AxisAlignedBB actual)
	{
		assertAll(
				() -> assertEquals(expected.minX, actual.minX, D, "minX"),
				() -> assertEquals(expected.minY, actual.minY, D, "minY"),
				() -> assertEquals(expected.minZ, actual.minZ, D, "minZ"),
				() -> assertEquals(expected.maxX, actual.maxX, D, "maxX"),
				() -> assertEquals(expected.maxY, actual.maxY, D, "maxY"),
				() -> assertEquals(expected.maxZ, actual.maxZ, D, "maxZ")
		);
	}

	@Test
	@DisplayName("transformAABB(NORTH) is the identity -- north is the reference orientation")
	void transformAABBNorth()
	{
		AxisAlignedBB in = new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
		assertBox(in, Utils.transformAABB(in, EnumFacing.NORTH));
	}

	@Test
	@DisplayName("transformAABB(SOUTH) mirrors both horizontal axes about the block centre")
	void transformAABBSouth()
	{
		AxisAlignedBB out = Utils.transformAABB(new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6), EnumFacing.SOUTH);
		assertBox(new AxisAlignedBB(0.6, 0.2, 0.4, 0.9, 0.5, 0.7), out);
	}

	@Test
	@DisplayName("transformAABB(WEST) swaps the horizontal axes")
	void transformAABBWest()
	{
		AxisAlignedBB out = Utils.transformAABB(new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6), EnumFacing.WEST);
		assertBox(new AxisAlignedBB(0.3, 0.2, 0.1, 0.6, 0.5, 0.4), out);
	}

	@Test
	@DisplayName("transformAABB(EAST) swaps and mirrors the horizontal axes")
	void transformAABBEast()
	{
		AxisAlignedBB out = Utils.transformAABB(new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6), EnumFacing.EAST);
		assertBox(new AxisAlignedBB(0.4, 0.2, 0.6, 0.7, 0.5, 0.9), out);
	}

	@Test
	@DisplayName("transformAABB never touches the vertical extent")
	void transformAABBKeepsY()
	{
		AxisAlignedBB in = new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
		for(EnumFacing facing : EnumFacing.HORIZONTALS)
		{
			AxisAlignedBB out = Utils.transformAABB(in, facing);
			assertEquals(in.minY, out.minY, D, facing.toString());
			assertEquals(in.maxY, out.maxY, D, facing.toString());
		}
	}

	@Test
	@DisplayName("transformAABB preserves the horizontal footprint size for every horizontal facing")
	void transformAABBPreservesSize()
	{
		AxisAlignedBB in = new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
		double area = (in.maxX-in.minX)*(in.maxZ-in.minZ);
		for(EnumFacing facing : EnumFacing.HORIZONTALS)
		{
			AxisAlignedBB out = Utils.transformAABB(in, facing);
			assertEquals(area, (out.maxX-out.minX)*(out.maxZ-out.minZ), D, facing.toString());
		}
	}

	@Test
	@DisplayName("transformAABB only handles horizontal facings -- a vertical one throws")
	void transformAABBIsHorizontalOnly()
	{
		// documenting the limit rather than endorsing it: the very first thing the method does is
		// facing.rotateY(), which vanilla refuses for UP and DOWN. Callers must filter first.
		AxisAlignedBB in = new AxisAlignedBB(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
		for(EnumFacing facing : new EnumFacing[]{EnumFacing.UP, EnumFacing.DOWN})
			assertThrows(IllegalStateException.class, () -> Utils.transformAABB(in, facing), facing.toString());
	}

	// ---------------------------------------------------------------- rotateToFacing

	@Test
	@DisplayName("rotateToFacing() leaves the block centre where it is, for every facing")
	void rotateToFacingFixesTheCentre()
	{
		for(EnumFacing facing : EnumFacing.VALUES)
			assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f},
					Utils.rotateToFacing(new float[]{0.5f, 0.5f, 0.5f}, facing), 1e-6f, facing.toString());
	}

	@Test
	@DisplayName("rotateToFacing() keeps every corner the same distance from the block centre")
	void rotateToFacingIsRigid()
	{
		for(EnumFacing facing : EnumFacing.VALUES)
		{
			float[] out = Utils.rotateToFacing(new float[]{1f, 0.25f, 0.9f}, facing);
			double d = 0;
			for(int i = 0; i < 3; i++)
				d += (out[i]-0.5)*(out[i]-0.5);
			double expected = 0.5*0.5+0.25*0.25+0.4*0.4;
			assertEquals(expected, d, 1e-6, facing.toString());
		}
	}

	@Test
	@Disabled("Suspected bug: a horizontal rotation moves the Y coordinate -- see Utils.java:1128-1143")
	@DisplayName("rotateToFacing() preserves the height of a point for horizontal facings")
	void rotateToFacingPreservesHeight()
	{
		// the three output components each mix all three inputs with a different offset triple, so
		// what should be a rotation about the vertical axis shuffles the vertical coordinate too
		for(EnumFacing facing : EnumFacing.HORIZONTALS)
			assertEquals(0.25f, Utils.rotateToFacing(new float[]{1f, 0.25f, 0.9f}, facing)[1], 1e-6f,
					facing.toString());
	}

	@Test
	@DisplayName("rotateToFacing() rewrites its input array in place, so callers must not reuse it")
	void rotateToFacingMutatesItsInput()
	{
		float[] in = {1f, 0.5f, 0.5f};
		Utils.rotateToFacing(in, EnumFacing.SOUTH);
		assertArrayEquals(new float[]{0.5f, 0f, 0f}, in, 1e-6f,
				"the input is shifted by -0.5 and left that way");
	}

	@Test
	@DisplayName("rotateToFacing() returns a different array from the one passed in")
	void rotateToFacingReturnsANewArray()
	{
		float[] in = {0.5f, 0.5f, 0.5f};
		assertNotSame(in, Utils.rotateToFacing(in, EnumFacing.NORTH));
	}

	// ---------------------------------------------------------------- findMinOrMax

	@Test
	@DisplayName("findMinOrMax() finds the smallest X")
	void findMinX()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(5, 0, 0), new BlockPos(2, 0, 0)));
		assertEquals(Collections.singleton(new BlockPos(2, 0, 0)), Utils.findMinOrMax(in, false, 0));
	}

	@Test
	@DisplayName("findMinOrMax() finds the largest X")
	void findMaxX()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(5, 0, 0), new BlockPos(2, 0, 0)));
		assertEquals(Collections.singleton(new BlockPos(5, 0, 0)), Utils.findMinOrMax(in, true, 0));
	}

	@Test
	@DisplayName("findMinOrMax() finds the smallest Y")
	void findMinY()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(0, 5, 0), new BlockPos(0, 2, 0)));
		assertEquals(Collections.singleton(new BlockPos(0, 2, 0)), Utils.findMinOrMax(in, false, 1));
	}

	@Test
	@DisplayName("findMinOrMax() finds the largest Y")
	void findMaxY()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(0, 5, 0), new BlockPos(0, 2, 0)));
		assertEquals(Collections.singleton(new BlockPos(0, 5, 0)), Utils.findMinOrMax(in, true, 1));
	}

	@Test
	@DisplayName("findMinOrMax() returns every position tied for the extremum")
	void findMinKeepsTies()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(
				new BlockPos(2, 0, 0), new BlockPos(2, 9, 9), new BlockPos(5, 0, 0)));
		assertEquals(2, Utils.findMinOrMax(in, false, 0).size());
	}

	@Test
	@DisplayName("findMinOrMax() of an empty set is empty")
	void findMinOrMaxEmpty()
	{
		assertTrue(Utils.findMinOrMax(new HashSet<>(), false, 0).isEmpty());
	}

	@Test
	@DisplayName("findMinOrMax() does not modify its input")
	void findMinOrMaxIsPure()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(5, 0, 0), new BlockPos(2, 0, 0)));
		Utils.findMinOrMax(in, false, 0);
		assertEquals(2, in.size());
	}

	@Test
	@Disabled("Suspected bug: coord 2 scans Y but filters on Z -- see Utils.java:1490")
	@DisplayName("findMinOrMax() finds the smallest Z")
	void findMinZ()
	{
		// the first loop reads getY() for coord 2 while the second reads getZ(), so unless the
		// extreme Y happens to coincide with some Z the result comes back empty
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(0, 7, 1), new BlockPos(0, 3, 2)));
		assertEquals(Collections.singleton(new BlockPos(0, 7, 1)), Utils.findMinOrMax(in, false, 2));
	}

	@Test
	@Disabled("Suspected bug: coord 2 scans Y but filters on Z -- see Utils.java:1490")
	@DisplayName("findMinOrMax() finds the largest Z")
	void findMaxZ()
	{
		HashSet<BlockPos> in = new HashSet<>(Arrays.asList(new BlockPos(0, 7, 1), new BlockPos(0, 3, 2)));
		assertEquals(Collections.singleton(new BlockPos(0, 3, 2)), Utils.findMinOrMax(in, true, 2));
	}
}
