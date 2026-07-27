/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
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
 * {@link DimensionChunkCoords} is a {@link ChunkPos} plus a dimension id, with its own equality,
 * an offset helper and an NBT form. Note the constructor argument order: dimension comes first,
 * unlike {@link DimensionBlockPos} where it comes last.
 */
class DimensionChunkCoordsTest
{
	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("keeps dimension, x and z")
		void keepsAllThree()
		{
			DimensionChunkCoords c = new DimensionChunkCoords(7, 3, -4);
			assertEquals(7, c.dimension);
			assertEquals(3, c.x);
			assertEquals(-4, c.z);
		}

		@Test
		@DisplayName("the dimension field is public and mutable")
		void dimensionIsMutable()
		{
			DimensionChunkCoords c = new DimensionChunkCoords(0, 1, 1);
			c.dimension = -1;
			assertEquals(new DimensionChunkCoords(-1, 1, 1), c);
		}

		@Test
		@DisplayName("the inherited block-coordinate helpers still work")
		void inheritedHelpers()
		{
			DimensionChunkCoords c = new DimensionChunkCoords(0, 2, 3);
			assertEquals(32, c.getXStart());
			assertEquals(48, c.getZStart());
		}

		@Test
		@DisplayName("extreme chunk coordinates survive construction")
		void extremeCoordinates()
		{
			DimensionChunkCoords c = new DimensionChunkCoords(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
			assertEquals(Integer.MIN_VALUE, c.dimension);
			assertEquals(Integer.MAX_VALUE, c.x);
			assertEquals(Integer.MIN_VALUE, c.z);
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
			DimensionChunkCoords c = new DimensionChunkCoords(0, 1, 2);
			assertEquals(c, c);
		}

		@Test
		@DisplayName("two coordinates with the same dimension, x and z are equal")
		void sameValuesAreEqual()
		{
			DimensionChunkCoords a = new DimensionChunkCoords(2, 5, 9);
			DimensionChunkCoords b = new DimensionChunkCoords(2, 5, 9);
			assertEquals(a, b);
			assertEquals(b, a);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("the same chunk in two dimensions is not equal")
		void dimensionDiscriminates()
		{
			assertNotEquals(new DimensionChunkCoords(0, 5, 9), new DimensionChunkCoords(-1, 5, 9));
			assertNotEquals(new DimensionChunkCoords(-1, 5, 9), new DimensionChunkCoords(0, 5, 9));
		}

		@Test
		@DisplayName("x and z each discriminate")
		void coordinatesDiscriminate()
		{
			DimensionChunkCoords base = new DimensionChunkCoords(0, 5, 9);
			assertNotEquals(base, new DimensionChunkCoords(0, 6, 9));
			assertNotEquals(base, new DimensionChunkCoords(0, 5, 8));
		}

		@Test
		@DisplayName("x and z are not interchangeable")
		void xAndZAreNotSwapped()
		{
			assertNotEquals(new DimensionChunkCoords(0, 1, 2), new DimensionChunkCoords(0, 2, 1));
		}

		@Test
		@DisplayName("is never equal to null")
		void notEqualToNull()
		{
			assertNotEquals(null, new DimensionChunkCoords(0, 1, 2));
		}

		@Test
		@DisplayName("is never equal to an unrelated type")
		void notEqualToOtherType()
		{
			assertNotEquals("0,1,2", new DimensionChunkCoords(0, 1, 2));
		}

		@Test
		@DisplayName("is not equal to a plain ChunkPos with the same coordinates")
		void notEqualToPlainChunkPos()
		{
			assertNotEquals(new DimensionChunkCoords(0, 1, 2), new ChunkPos(1, 2),
					"a plain ChunkPos carries no dimension, so it cannot satisfy the instanceof check");
		}

		@Test
		@Disabled("DimensionChunkCoords#equals requires the other side to be a DimensionChunkCoords, but the inherited ChunkPos#equals only checks instanceof ChunkPos, so ChunkPos.equals(DimensionChunkCoords) is true while the reverse is false")
		@DisplayName("equality with a plain ChunkPos is symmetric")
		void chunkPosEqualityIsSymmetric()
		{
			DimensionChunkCoords dim = new DimensionChunkCoords(0, 1, 2);
			ChunkPos plain = new ChunkPos(1, 2);
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
			DimensionChunkCoords c = new DimensionChunkCoords(3, 4, 5);
			assertEquals(c.hashCode(), c.hashCode());
		}

		@Test
		@DisplayName("agrees for equal instances, which is all the contract requires")
		void agreesForEqualInstances()
		{
			assertEquals(new DimensionChunkCoords(3, 4, 5).hashCode(), new DimensionChunkCoords(3, 4, 5).hashCode());
		}

		@Test
		@DisplayName("is inherited from ChunkPos and therefore ignores the dimension")
		void dimensionCollides()
		{
			DimensionChunkCoords a = new DimensionChunkCoords(0, 4, 5);
			DimensionChunkCoords b = new DimensionChunkCoords(-1, 4, 5);
			assertNotEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode(),
					"hashCode is not overridden; unequal coordinates in different dimensions collide (legal, but they share a bucket)");
			assertEquals(new ChunkPos(4, 5).hashCode(), a.hashCode());
		}

		@Test
		@DisplayName("distinguishes different chunks")
		void differentChunksDiffer()
		{
			assertNotEquals(new DimensionChunkCoords(0, 4, 5).hashCode(), new DimensionChunkCoords(0, 5, 4).hashCode());
		}
	}

	@Nested
	@DisplayName("use as a collection key")
	class AsKey
	{
		@Test
		@DisplayName("a HashMap keeps the same chunk in two dimensions apart despite the hash collision")
		void hashMapSeparatesDimensions()
		{
			Map<DimensionChunkCoords, String> map = new HashMap<>();
			map.put(new DimensionChunkCoords(0, 1, 1), "overworld");
			map.put(new DimensionChunkCoords(-1, 1, 1), "nether");
			assertEquals(2, map.size());
			assertEquals("overworld", map.get(new DimensionChunkCoords(0, 1, 1)));
			assertEquals("nether", map.get(new DimensionChunkCoords(-1, 1, 1)));
		}

		@Test
		@DisplayName("a HashMap overwrites on an equal key")
		void hashMapOverwrites()
		{
			Map<DimensionChunkCoords, String> map = new HashMap<>();
			map.put(new DimensionChunkCoords(0, 1, 1), "first");
			map.put(new DimensionChunkCoords(0, 1, 1), "second");
			assertEquals(1, map.size());
			assertEquals("second", map.get(new DimensionChunkCoords(0, 1, 1)));
		}

		@Test
		@DisplayName("a HashSet deduplicates equal coordinates")
		void hashSetDeduplicates()
		{
			Set<DimensionChunkCoords> set = new HashSet<>();
			set.add(new DimensionChunkCoords(0, 1, 1));
			set.add(new DimensionChunkCoords(0, 1, 1));
			set.add(new DimensionChunkCoords(1, 1, 1));
			assertEquals(2, set.size());
			assertTrue(set.contains(new DimensionChunkCoords(1, 1, 1)));
		}
	}

	@Nested
	@DisplayName("withOffset")
	class WithOffset
	{
		@Test
		@DisplayName("shifts x and z and keeps the dimension")
		void shiftsAndKeepsDimension()
		{
			DimensionChunkCoords moved = new DimensionChunkCoords(4, 10, 20).withOffset(3, -5);
			assertEquals(4, moved.dimension);
			assertEquals(13, moved.x);
			assertEquals(15, moved.z);
		}

		@Test
		@DisplayName("returns a new instance rather than mutating")
		void returnsNewInstance()
		{
			DimensionChunkCoords origin = new DimensionChunkCoords(0, 10, 20);
			DimensionChunkCoords moved = origin.withOffset(1, 1);
			assertNotSame(origin, moved);
			assertEquals(10, origin.x);
			assertEquals(20, origin.z);
		}

		@Test
		@DisplayName("a zero offset yields an equal but distinct instance")
		void zeroOffset()
		{
			DimensionChunkCoords origin = new DimensionChunkCoords(2, 10, 20);
			DimensionChunkCoords same = origin.withOffset(0, 0);
			assertEquals(origin, same);
			assertNotSame(origin, same);
		}

		@Test
		@DisplayName("offsetting is reversible")
		void reversible()
		{
			DimensionChunkCoords origin = new DimensionChunkCoords(3, 7, 9);
			assertEquals(origin, origin.withOffset(5, -2).withOffset(-5, 2));
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("writeToNBT stores dim, x and z")
		void writeStoresAllThree()
		{
			NBTTagCompound tag = new DimensionChunkCoords(-1, 12, -34).writeToNBT();
			assertEquals(-1, tag.getInteger("dim"));
			assertEquals(12, tag.getInteger("x"));
			assertEquals(-34, tag.getInteger("z"));
			assertEquals(3, tag.getSize());
		}

		@Test
		@DisplayName("a write/read round-trip preserves everything")
		void roundTrip()
		{
			DimensionChunkCoords src = new DimensionChunkCoords(5, -7, 11);
			DimensionChunkCoords dst = DimensionChunkCoords.readFromNBT(src.writeToNBT());
			assertNotNull(dst);
			assertEquals(src, dst);
			assertEquals(src.dimension, dst.dimension);
			assertEquals(src.x, dst.x);
			assertEquals(src.z, dst.z);
		}

		@Test
		@DisplayName("extreme values round-trip")
		void roundTripExtremes()
		{
			DimensionChunkCoords src = new DimensionChunkCoords(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
			assertEquals(src, DimensionChunkCoords.readFromNBT(src.writeToNBT()));
		}

		@Test
		@DisplayName("an empty tag reads as null")
		void readEmptyTag()
		{
			assertNull(DimensionChunkCoords.readFromNBT(new NBTTagCompound()));
		}

		@Test
		@DisplayName("a tag missing any one key reads as null")
		void readIncompleteTag()
		{
			NBTTagCompound noDim = new NBTTagCompound();
			noDim.setInteger("x", 1);
			noDim.setInteger("z", 2);
			assertNull(DimensionChunkCoords.readFromNBT(noDim));

			NBTTagCompound noX = new NBTTagCompound();
			noX.setInteger("dim", 0);
			noX.setInteger("z", 2);
			assertNull(DimensionChunkCoords.readFromNBT(noX));

			NBTTagCompound noZ = new NBTTagCompound();
			noZ.setInteger("dim", 0);
			noZ.setInteger("x", 1);
			assertNull(DimensionChunkCoords.readFromNBT(noZ));
		}

		@Test
		@DisplayName("a tag whose keys hold the wrong type reads as null")
		void readWrongTypes()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString("dim", "0");
			tag.setInteger("x", 1);
			tag.setInteger("z", 2);
			assertNull(DimensionChunkCoords.readFromNBT(tag), "the type-3 (int) check must reject a string");
		}

		@Test
		@DisplayName("extra keys in the tag are ignored")
		void readIgnoresExtraKeys()
		{
			NBTTagCompound tag = new DimensionChunkCoords(1, 2, 3).writeToNBT();
			tag.setString("junk", "ignored");
			assertEquals(new DimensionChunkCoords(1, 2, 3), DimensionChunkCoords.readFromNBT(tag));
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString
	{
		@Test
		@DisplayName("renders dimension, x and z")
		void rendersEverything()
		{
			assertEquals("[dim:5; 1, 2]", new DimensionChunkCoords(5, 1, 2).toString());
		}

		@Test
		@DisplayName("renders negative values")
		void rendersNegatives()
		{
			assertEquals("[dim:-1; -3, -4]", new DimensionChunkCoords(-1, -3, -4).toString());
		}

		@Test
		@DisplayName("differs from the inherited ChunkPos rendering")
		void differsFromChunkPos()
		{
			assertNotEquals(new ChunkPos(1, 2).toString(), new DimensionChunkCoords(0, 1, 2).toString());
		}
	}
}
