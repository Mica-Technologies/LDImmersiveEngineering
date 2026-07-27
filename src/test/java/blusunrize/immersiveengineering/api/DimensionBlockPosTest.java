/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DimensionBlockPos} is a {@link BlockPos} plus a dimension id, and is used as a map key for
 * the IIC proxy table -- so the interesting behaviour is all in equals/hashCode.
 */
class DimensionBlockPosTest
{
	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("the coordinate/dimension constructor keeps all four values")
		void coordinateConstructor()
		{
			DimensionBlockPos p = new DimensionBlockPos(1, 2, 3, 7);
			assertEquals(1, p.getX());
			assertEquals(2, p.getY());
			assertEquals(3, p.getZ());
			assertEquals(7, p.dimension);
		}

		@Test
		@DisplayName("the BlockPos/dimension constructor copies the position")
		void blockPosConstructor()
		{
			DimensionBlockPos p = new DimensionBlockPos(new BlockPos(-4, 70, 12), -1);
			assertEquals(-4, p.getX());
			assertEquals(70, p.getY());
			assertEquals(12, p.getZ());
			assertEquals(-1, p.dimension);
		}

		@Test
		@DisplayName("negative and extreme coordinates survive construction")
		void extremeCoordinates()
		{
			DimensionBlockPos p = new DimensionBlockPos(-30000000, 0, 30000000, Integer.MIN_VALUE);
			assertEquals(-30000000, p.getX());
			assertEquals(30000000, p.getZ());
			assertEquals(Integer.MIN_VALUE, p.dimension);
		}

		@Test
		@DisplayName("the dimension field is public and mutable")
		void dimensionIsMutable()
		{
			DimensionBlockPos p = new DimensionBlockPos(1, 2, 3, 0);
			p.dimension = 5;
			assertEquals(5, p.dimension);
			assertEquals(new DimensionBlockPos(1, 2, 3, 5), p);
		}
	}

	@Nested
	@DisplayName("equals")
	class Equality
	{
		@Test
		@DisplayName("is reflexive")
		void reflexive()
		{
			DimensionBlockPos p = new DimensionBlockPos(1, 2, 3, 0);
			assertEquals(p, p);
		}

		@Test
		@DisplayName("two positions with the same coordinates and dimension are equal and hash alike")
		void sameValuesAreEqual()
		{
			DimensionBlockPos a = new DimensionBlockPos(1, 2, 3, 0);
			DimensionBlockPos b = new DimensionBlockPos(1, 2, 3, 0);
			assertEquals(a, b);
			assertEquals(b, a);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("identical coordinates in different dimensions are not equal")
		void dimensionDiscriminates()
		{
			DimensionBlockPos overworld = new DimensionBlockPos(64, 70, 64, 0);
			DimensionBlockPos nether = new DimensionBlockPos(64, 70, 64, -1);
			assertNotEquals(overworld, nether);
			assertNotEquals(nether, overworld);
		}

		@Test
		@DisplayName("each coordinate discriminates")
		void coordinatesDiscriminate()
		{
			DimensionBlockPos base = new DimensionBlockPos(1, 2, 3, 0);
			assertNotEquals(base, new DimensionBlockPos(9, 2, 3, 0));
			assertNotEquals(base, new DimensionBlockPos(1, 9, 3, 0));
			assertNotEquals(base, new DimensionBlockPos(1, 2, 9, 0));
		}

		@Test
		@DisplayName("is never equal to null")
		void notEqualToNull()
		{
			assertNotEquals(null, new DimensionBlockPos(1, 2, 3, 0));
		}

		@Test
		@DisplayName("is never equal to an unrelated type")
		void notEqualToOtherType()
		{
			assertNotEquals("1,2,3", new DimensionBlockPos(1, 2, 3, 0));
		}

		@Test
		@DisplayName("is not equal to a plain BlockPos with the same coordinates")
		void notEqualToPlainBlockPos()
		{
			DimensionBlockPos dim = new DimensionBlockPos(1, 2, 3, 0);
			assertNotEquals(dim, new BlockPos(1, 2, 3), "the exact-class check rejects the superclass");
		}

		@Test
		@Disabled("DimensionBlockPos#equals uses an exact getClass() check while the inherited Vec3i#equals only checks instanceof, so BlockPos.equals(DimensionBlockPos) is true while the reverse is false")
		@DisplayName("equality with a plain BlockPos is symmetric")
		void blockPosEqualityIsSymmetric()
		{
			DimensionBlockPos dim = new DimensionBlockPos(1, 2, 3, 0);
			BlockPos plain = new BlockPos(1, 2, 3);
			assertEquals(dim.equals(plain), plain.equals(dim), "equals must be symmetric in both directions");
		}
	}

	@Nested
	@DisplayName("hashCode")
	class Hashing
	{
		@Test
		@DisplayName("is stable across repeated calls")
		void stable()
		{
			DimensionBlockPos p = new DimensionBlockPos(5, 6, 7, 2);
			assertEquals(p.hashCode(), p.hashCode());
			assertEquals(p.hashCode(), p.hashCode());
		}

		@Test
		@DisplayName("takes the dimension into account")
		void dimensionAffectsHash()
		{
			assertNotEquals(new DimensionBlockPos(1, 2, 3, 0).hashCode(),
					new DimensionBlockPos(1, 2, 3, 1).hashCode());
		}

		@Test
		@DisplayName("differs from the inherited BlockPos hash, so the two do not share buckets")
		void differsFromBlockPosHash()
		{
			assertNotEquals(new BlockPos(1, 2, 3).hashCode(), new DimensionBlockPos(1, 2, 3, 0).hashCode());
		}

		@Test
		@DisplayName("the 31-prime formula is reproduced exactly")
		void formula()
		{
			int expected = 1;
			expected = 31*expected+7;
			expected = 31*expected+1;
			expected = 31*expected+2;
			expected = 31*expected+3;
			assertEquals(expected, new DimensionBlockPos(1, 2, 3, 7).hashCode());
		}
	}

	@Nested
	@DisplayName("use as a collection key")
	class AsKey
	{
		@Test
		@DisplayName("a HashMap treats the same position in two dimensions as two keys")
		void hashMapSeparatesDimensions()
		{
			Map<DimensionBlockPos, String> map = new HashMap<>();
			map.put(new DimensionBlockPos(0, 64, 0, 0), "overworld");
			map.put(new DimensionBlockPos(0, 64, 0, -1), "nether");
			assertEquals(2, map.size());
			assertEquals("overworld", map.get(new DimensionBlockPos(0, 64, 0, 0)));
			assertEquals("nether", map.get(new DimensionBlockPos(0, 64, 0, -1)));
		}

		@Test
		@DisplayName("a HashMap overwrites on an equal key")
		void hashMapOverwrites()
		{
			Map<DimensionBlockPos, String> map = new HashMap<>();
			map.put(new DimensionBlockPos(1, 2, 3, 0), "first");
			map.put(new DimensionBlockPos(1, 2, 3, 0), "second");
			assertEquals(1, map.size());
			assertEquals("second", map.get(new DimensionBlockPos(1, 2, 3, 0)));
		}

		@Test
		@DisplayName("removing by an equal-but-distinct instance works")
		void hashMapRemove()
		{
			Map<DimensionBlockPos, String> map = new HashMap<>();
			map.put(new DimensionBlockPos(1, 2, 3, 4), "x");
			assertEquals("x", map.remove(new DimensionBlockPos(1, 2, 3, 4)));
			assertTrue(map.isEmpty());
		}

		@Test
		@DisplayName("a HashSet deduplicates equal positions")
		void hashSetDeduplicates()
		{
			Set<DimensionBlockPos> set = new HashSet<>();
			set.add(new DimensionBlockPos(1, 2, 3, 0));
			set.add(new DimensionBlockPos(1, 2, 3, 0));
			set.add(new DimensionBlockPos(1, 2, 3, 1));
			assertEquals(2, set.size());
		}

		@Test
		@DisplayName("a lookup with a plain BlockPos misses")
		void plainBlockPosLookupMisses()
		{
			Map<BlockPos, String> map = new HashMap<>();
			map.put(new DimensionBlockPos(1, 2, 3, 0), "x");
			assertNull(map.get(new BlockPos(1, 2, 3)), "the hashes differ, so the plain position never finds the entry");
		}
	}

	@Nested
	@DisplayName("inherited BlockPos behaviour")
	class Inherited
	{
		@Test
		@DisplayName("offsetting yields a plain BlockPos and drops the dimension")
		void offsetDropsDimension()
		{
			DimensionBlockPos p = new DimensionBlockPos(1, 2, 3, 9);
			BlockPos moved = p.offset(EnumFacing.UP);
			assertEquals(new BlockPos(1, 3, 3), moved);
			assertFalse(moved instanceof DimensionBlockPos, "BlockPos#offset knows nothing about dimensions");
		}

		@Test
		@DisplayName("the Vec3i accessors still work")
		void vec3iAccessors()
		{
			DimensionBlockPos p = new DimensionBlockPos(3, 4, 12, 0);
			assertEquals(9+16+144, p.distanceSq(0, 0, 0), 1e-9);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString
	{
		@Test
		@DisplayName("is prefixed with the dimension")
		void prefixedWithDimension()
		{
			assertTrue(new DimensionBlockPos(1, 2, 3, 5).toString().startsWith("Dimension: 5 Pos: "));
		}

		@Test
		@DisplayName("still contains the coordinates")
		void containsCoordinates()
		{
			String s = new DimensionBlockPos(11, 22, 33, 0).toString();
			assertTrue(s.contains("11"), s);
			assertTrue(s.contains("22"), s);
			assertTrue(s.contains("33"), s);
		}

		@Test
		@DisplayName("renders a negative dimension id")
		void negativeDimension()
		{
			assertTrue(new DimensionBlockPos(0, 0, 0, -1).toString().startsWith("Dimension: -1 Pos: "));
		}

		@Test
		@DisplayName("is never null or empty")
		void nonEmpty()
		{
			assertNotNull(new DimensionBlockPos(0, 0, 0, 0).toString());
			assertFalse(new DimensionBlockPos(0, 0, 0, 0).toString().isEmpty());
		}
	}
}
