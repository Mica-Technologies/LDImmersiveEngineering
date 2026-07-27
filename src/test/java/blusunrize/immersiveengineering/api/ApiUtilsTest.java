/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the world-free helpers in {@link ApiUtils}. Only the pure maths, vector and
 * map helpers are covered here -- everything that needs a live world, a registry or an ItemStack is
 * out of reach of a plain JUnit run.
 */
class ApiUtilsTest
{
	private static final double EPS = 1e-9;

	@Test
	@DisplayName("acosh(1) is exactly zero")
	void acoshOfOneIsZero()
	{
		assertEquals(0d, ApiUtils.acosh(1), 0d);
	}

	@Test
	@DisplayName("acosh inverts cosh")
	void acoshInvertsCosh()
	{
		for(double x : new double[]{0, 0.5, 1, 2, 5, 10})
			assertEquals(x, ApiUtils.acosh(Math.cosh(x)), 1e-8, "acosh(cosh("+x+")) drifted");
	}

	@Test
	@DisplayName("acosh matches the closed form on known values")
	void acoshMatchesKnownValues()
	{
		assertEquals(Math.log(2+Math.sqrt(3)), ApiUtils.acosh(2), EPS);
		assertEquals(Math.log(3+Math.sqrt(8)), ApiUtils.acosh(3), EPS);
	}

	@Test
	@DisplayName("acosh is undefined below 1 -- the catenary solver must never feed it such a value")
	void acoshBelowOneIsNaN()
	{
		assertTrue(Double.isNaN(ApiUtils.acosh(0.5)));
		assertTrue(Double.isNaN(ApiUtils.acosh(0)));
	}

	@Test
	@DisplayName("acosh grows monotonically")
	void acoshIsMonotonic()
	{
		double previous = ApiUtils.acosh(1);
		for(double x = 1.1; x < 50; x += 0.1)
		{
			double current = ApiUtils.acosh(x);
			assertTrue(current > previous, "acosh is not monotonic at "+x);
			previous = current;
		}
	}

	@Test
	@DisplayName("addVectors adds component-wise")
	void addVectorsAddsComponentWise()
	{
		Vec3d sum = ApiUtils.addVectors(new Vec3d(1, 2, 3), new Vec3d(0.5, -2, 10));
		assertEquals(1.5, sum.x, EPS);
		assertEquals(0d, sum.y, EPS);
		assertEquals(13d, sum.z, EPS);
	}

	@Test
	@DisplayName("addVectors leaves its arguments untouched")
	void addVectorsDoesNotMutate()
	{
		Vec3d a = new Vec3d(1, 2, 3);
		Vec3d b = new Vec3d(4, 5, 6);
		ApiUtils.addVectors(a, b);

		assertEquals(new Vec3d(1, 2, 3), a);
		assertEquals(new Vec3d(4, 5, 6), b);
	}

	@Test
	@DisplayName("addVectors with the zero vector is the identity")
	void addVectorsWithZeroIsIdentity()
	{
		Vec3d a = new Vec3d(-3.5, 7, 0.25);
		assertEquals(a, ApiUtils.addVectors(a, new Vec3d(0, 0, 0)));
	}

	@Test
	@DisplayName("getDim picks x, y, z for 0, 1, 2")
	void getDimPicksTheRightComponent()
	{
		Vec3d v = new Vec3d(7, 8, 9);
		assertEquals(7d, ApiUtils.getDim(v, 0), EPS);
		assertEquals(8d, ApiUtils.getDim(v, 1), EPS);
		assertEquals(9d, ApiUtils.getDim(v, 2), EPS);
	}

	@Test
	@DisplayName("offsetDim(BlockPos) moves along exactly one axis")
	void offsetDimOnBlockPos()
	{
		BlockPos origin = new BlockPos(10, 20, 30);
		assertEquals(new BlockPos(15, 20, 30), ApiUtils.offsetDim(origin, 0, 5));
		assertEquals(new BlockPos(10, 25, 30), ApiUtils.offsetDim(origin, 1, 5));
		assertEquals(new BlockPos(10, 20, 35), ApiUtils.offsetDim(origin, 2, 5));
		assertEquals(origin, ApiUtils.offsetDim(origin, 0, 0));
	}

	@Test
	@DisplayName("offsetDim(BlockPos) accepts negative offsets and does not mutate")
	void offsetDimOnBlockPosIsPure()
	{
		BlockPos origin = new BlockPos(0, 0, 0);
		assertEquals(new BlockPos(-4, 0, 0), ApiUtils.offsetDim(origin, 0, -4));
		assertEquals(new BlockPos(0, 0, 0), origin);
	}

	@Test
	@DisplayName("offsetDim(Vec3d) moves along exactly one axis")
	void offsetDimOnVec3d()
	{
		Vec3d origin = new Vec3d(1.5, 2.5, 3.5);
		assertEquals(new Vec3d(2d, 2.5, 3.5), ApiUtils.offsetDim(origin, 0, 0.5));
		assertEquals(new Vec3d(1.5, 3d, 3.5), ApiUtils.offsetDim(origin, 1, 0.5));
		assertEquals(new Vec3d(1.5, 2.5, 4d), ApiUtils.offsetDim(origin, 2, 0.5));
	}

	@Test
	@DisplayName("offsetDim and getDim round-trip on every axis")
	void offsetDimAndGetDimAgree()
	{
		Vec3d origin = new Vec3d(1, 2, 3);
		for(int dim = 0; dim < 3; dim++)
			assertEquals(ApiUtils.getDim(origin, dim)+2.5,
					ApiUtils.getDim(ApiUtils.offsetDim(origin, dim, 2.5), dim), EPS);
	}

	@Test
	@DisplayName("sortMap orders descending by value by default")
	void sortMapDescendingByDefault()
	{
		Map<String, Integer> input = new LinkedHashMap<>();
		input.put("small", 1);
		input.put("big", 30);
		input.put("medium", 7);

		assertEquals(new ArrayList<>(java.util.Arrays.asList("big", "medium", "small")),
				new ArrayList<>(ApiUtils.sortMap(input, false).keySet()));
	}

	@Test
	@DisplayName("sortMap orders ascending by value when inverted")
	void sortMapAscendingWhenInverted()
	{
		Map<String, Integer> input = new LinkedHashMap<>();
		input.put("small", 1);
		input.put("big", 30);
		input.put("medium", 7);

		assertEquals(new ArrayList<>(java.util.Arrays.asList("small", "medium", "big")),
				new ArrayList<>(ApiUtils.sortMap(input, true).keySet()));
	}

	@Test
	@DisplayName("sortMap keeps every entry, including ties, and leaves the input alone")
	void sortMapKeepsEveryEntry()
	{
		Map<String, Integer> input = new LinkedHashMap<>();
		input.put("a", 5);
		input.put("b", 5);
		input.put("c", 5);
		input.put("d", 1);

		Map<String, Integer> sorted = ApiUtils.sortMap(input, false);
		assertEquals(4, sorted.size(), "a tie must not swallow an entry");
		// iterate rather than query: the comparator deliberately never reports two keys as equal,
		// so the returned TreeMap can order its keys but cannot look one up again
		List<String> keys = new ArrayList<>(sorted.keySet());
		assertTrue(keys.containsAll(input.keySet()), "an entry went missing: "+keys);
		assertEquals("d", keys.get(keys.size()-1), "the smallest value must sort last");
		assertEquals(4, input.size(), "sortMap must not mutate its input");
	}

	@Test
	@DisplayName("sortMap handles the empty and single-entry cases")
	void sortMapHandlesDegenerateCases()
	{
		assertTrue(ApiUtils.sortMap(new HashMap<>(), false).isEmpty());

		Map<String, Integer> single = new HashMap<>();
		single.put("only", 42);
		assertEquals(1, ApiUtils.sortMap(single, true).size());
	}

	@Test
	@DisplayName("ValueComparator is equal only to one over the same map and direction")
	void valueComparatorEquality()
	{
		Map<String, Integer> base = new HashMap<>();
		Map<String, Integer> other = new HashMap<>();

		assertEquals(new ApiUtils.ValueComparator(base, false), new ApiUtils.ValueComparator(base, false));
		assertNotEquals(new ApiUtils.ValueComparator(base, false), new ApiUtils.ValueComparator(base, true));
		assertNotEquals(new ApiUtils.ValueComparator(base, false), new ApiUtils.ValueComparator(other, false));
		assertNotEquals(new ApiUtils.ValueComparator(base, false), "not a comparator");
	}

	@Test
	@DisplayName("ValueComparator never reports two distinct keys as equal")
	void valueComparatorNeverReturnsZero()
	{
		Map<String, Integer> base = new HashMap<>();
		base.put("x", 3);
		base.put("y", 3);
		base.put("z", 9);

		ApiUtils.ValueComparator descending = new ApiUtils.ValueComparator(base, false);
		assertNotEquals(0, descending.compare("x", "y"), "equal values must still be kept apart");
		assertTrue(descending.compare("z", "x") < 0, "the larger value must sort first");

		ApiUtils.ValueComparator ascending = new ApiUtils.ValueComparator(base, true);
		assertTrue(ascending.compare("x", "z") < 0, "the smaller value must sort first when inverted");
	}
}
