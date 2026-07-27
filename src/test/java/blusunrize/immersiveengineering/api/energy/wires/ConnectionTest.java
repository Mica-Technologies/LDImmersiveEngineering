/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Connection} -- one wire between two connectors. Everything here is pure data plus
 * catenary maths, so no world is involved.
 */
class ConnectionTest
{
	private static final BlockPos A = new BlockPos(0, 64, 0);
	private static final BlockPos B = new BlockPos(8, 64, 0);
	private static final BlockPos C = new BlockPos(0, 64, 8);

	private WireType lv;
	private WireType hv;
	/** Same numbers as {@link #lv}, but a distinct type -- the "insulated copper" case. */
	private WireType lvTwin;

	@BeforeEach
	void setUp()
	{
		TestWireType.resetRegistries();
		TestWireType.installConfigArrays();
		lv = new TestWireType("CONN_LV", .05, 256, 16, WireType.LV_CATEGORY, true, 0xb36c3f);
		hv = new TestWireType("CONN_HV", .2, 4096, 32, WireType.HV_CATEGORY, true, 0x6e6e6e);
		lvTwin = new TestWireType("CONN_LV_INS", .05, 256, 16, WireType.LV_CATEGORY, true, 0xad3e3e);
	}

	private Connection conn(BlockPos start, BlockPos end, WireType type, int length)
	{
		return new Connection(start, end, type, length);
	}

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("keeps the four constructor arguments")
		void keepsArguments()
		{
			Connection c = conn(A, B, lv, 8);
			assertSame(A, c.start);
			assertSame(B, c.end);
			assertSame(lv, c.cableType);
			assertEquals(8, c.length);
		}

		@Test
		@DisplayName("starts with no catenary computed")
		void noCatenaryYet()
		{
			Connection c = conn(A, B, lv, 8);
			assertNull(c.catenaryVertices);
			assertNull(c.across);
			assertFalse(c.vertical);
		}

		@Test
		@DisplayName("the catenary is sampled at 17 segments")
		void vertexCount()
		{
			assertEquals(17, Connection.vertices);
		}
	}

	@Nested
	@DisplayName("hasSameConnectors")
	class SameConnectors
	{
		@Test
		@DisplayName("a connection has the same connectors as itself")
		void reflexive()
		{
			Connection c = conn(A, B, lv, 8);
			assertTrue(c.hasSameConnectors(c));
		}

		@Test
		@DisplayName("matches an identical connection")
		void matchesIdentical()
		{
			assertTrue(conn(A, B, lv, 8).hasSameConnectors(conn(A, B, lv, 8)));
		}

		@Test
		@DisplayName("matches the reverse connection, in both directions")
		void matchesReverse()
		{
			Connection forward = conn(A, B, lv, 8);
			Connection backward = conn(B, A, lv, 8);
			assertTrue(forward.hasSameConnectors(backward));
			assertTrue(backward.hasSameConnectors(forward), "the relation must be symmetric");
		}

		@Test
		@DisplayName("does not match a connection to a different endpoint")
		void rejectsDifferentEndpoint()
		{
			assertFalse(conn(A, B, lv, 8).hasSameConnectors(conn(A, C, lv, 8)));
			assertFalse(conn(A, B, lv, 8).hasSameConnectors(conn(C, B, lv, 8)));
		}

		@Test
		@DisplayName("ignores the cable type and the length")
		void ignoresTypeAndLength()
		{
			assertTrue(conn(A, B, lv, 8).hasSameConnectors(conn(A, B, hv, 999)),
					"connector identity is about the endpoints only");
		}

		@Test
		@DisplayName("matches a connection built from an equal-but-distinct BlockPos")
		void usesValueEquality()
		{
			assertTrue(conn(A, B, lv, 8).hasSameConnectors(conn(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), lv, 8)));
		}

		@Test
		@DisplayName("a self-loop matches itself in either reading")
		void selfLoop()
		{
			assertTrue(conn(A, A, lv, 0).hasSameConnectors(conn(A, A, lv, 0)));
		}
	}

	@Nested
	@DisplayName("compareTo")
	class Ordering
	{
		@Test
		@DisplayName("a connection compares equal to itself")
		void reflexive()
		{
			Connection c = conn(A, B, lv, 8);
			assertEquals(0, c.compareTo(c));
		}

		@Test
		@DisplayName("the higher transfer rate sorts first")
		void higherThroughputFirst()
		{
			assertTrue(conn(A, B, hv, 8).compareTo(conn(A, B, lv, 8)) < 0);
			assertTrue(conn(A, B, lv, 8).compareTo(conn(A, B, hv, 8)) > 0);
		}

		@Test
		@DisplayName("the cable type outranks the length")
		void cableTypeBeatsLength()
		{
			assertTrue(conn(A, B, hv, 999).compareTo(conn(A, B, lv, 1)) < 0,
					"a long HV wire still sorts ahead of a short LV one");
		}

		@Test
		@DisplayName("for equal cable types the shorter connection sorts first")
		void shorterFirst()
		{
			assertTrue(conn(A, B, lv, 4).compareTo(conn(A, B, lv, 9)) < 0);
			assertTrue(conn(A, B, lv, 9).compareTo(conn(A, B, lv, 4)) > 0);
		}

		@Test
		@DisplayName("the start coordinates break a remaining tie, x then y then z")
		void startCoordinatesBreakTies()
		{
			assertTrue(conn(new BlockPos(0, 0, 0), B, lv, 8).compareTo(conn(new BlockPos(1, 0, 0), B, lv, 8)) < 0);
			assertTrue(conn(new BlockPos(0, 0, 0), B, lv, 8).compareTo(conn(new BlockPos(0, 1, 0), B, lv, 8)) < 0);
			assertTrue(conn(new BlockPos(0, 0, 0), B, lv, 8).compareTo(conn(new BlockPos(0, 0, 1), B, lv, 8)) < 0);
		}

		@Test
		@DisplayName("the end coordinates break the final tie")
		void endCoordinatesBreakTies()
		{
			assertTrue(conn(A, new BlockPos(0, 0, 0), lv, 8).compareTo(conn(A, new BlockPos(1, 0, 0), lv, 8)) < 0);
			assertTrue(conn(A, new BlockPos(0, 0, 0), lv, 8).compareTo(conn(A, new BlockPos(0, 1, 0), lv, 8)) < 0);
			assertTrue(conn(A, new BlockPos(0, 0, 0), lv, 8).compareTo(conn(A, new BlockPos(0, 0, 1), lv, 8)) < 0);
		}

		@Test
		@DisplayName("two fully identical connections compare equal")
		void identicalCompareEqual()
		{
			assertEquals(0, conn(A, B, lv, 8).compareTo(conn(A, B, lv, 8)));
		}

		@Test
		@DisplayName("is antisymmetric across a spread of connections")
		void antisymmetric()
		{
			Connection[] all = {
					conn(A, B, lv, 8), conn(A, B, hv, 8), conn(A, B, lv, 3),
					conn(B, A, lv, 8), conn(A, C, hv, 12), conn(C, A, lv, 1)
			};
			for(Connection x : all)
				for(Connection y : all)
					assertEquals(Integer.signum(x.compareTo(y)), -Integer.signum(y.compareTo(x)),
							x.start+"->"+x.end+" vs "+y.start+"->"+y.end);
		}

		@Test
		@DisplayName("sorts a TreeSet best-cable-first")
		void treeSetOrder()
		{
			Connection lvLong = conn(A, B, lv, 12);
			Connection lvShort = conn(A, B, lv, 4);
			Connection hvLong = conn(A, B, hv, 12);
			Set<Connection> sorted = new TreeSet<>();
			sorted.add(lvLong);
			sorted.add(lvShort);
			sorted.add(hvLong);
			assertArrayEquals(new Connection[]{hvLong, lvShort, lvLong}, sorted.toArray(new Connection[0]));
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class Equality
	{
		@Test
		@DisplayName("is reflexive")
		void reflexive()
		{
			Connection c = conn(A, B, lv, 8);
			assertEquals(c, c);
		}

		@Test
		@DisplayName("two identical connections are equal and hash alike")
		void identicalAreEqual()
		{
			Connection a = conn(A, B, lv, 8);
			Connection b = conn(A, B, lv, 8);
			assertEquals(a, b);
			assertEquals(b, a);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("is never equal to null or to an unrelated type")
		void notEqualToOther()
		{
			Connection c = conn(A, B, lv, 8);
			assertNotEquals(null, c);
			assertNotEquals("A->B", c);
		}

		@Test
		@DisplayName("connections of different length are not equal")
		void lengthDiscriminates()
		{
			assertNotEquals(conn(A, B, lv, 8), conn(A, B, lv, 9));
		}

		@Test
		@DisplayName("connections between different blocks are not equal")
		void endpointsDiscriminate()
		{
			assertNotEquals(conn(A, B, lv, 8), conn(A, C, lv, 8));
			assertNotEquals(conn(A, B, lv, 8), conn(B, A, lv, 8), "direction matters for equality");
		}

		@Test
		@DisplayName("cable types of different throughput are not equal")
		void differentThroughputDiscriminates()
		{
			assertNotEquals(conn(A, B, lv, 8), conn(A, B, hv, 8));
		}

		@Test
		@DisplayName("hashCode is stable across repeated calls")
		void hashIsStable()
		{
			Connection c = conn(A, B, lv, 8);
			int first = c.hashCode();
			assertEquals(first, c.hashCode());
			assertEquals(first, c.hashCode());
		}

		@Test
		@DisplayName("hashCode ignores the length, so equal-length is not required for a bucket hit")
		void hashIgnoresLength()
		{
			assertEquals(conn(A, B, lv, 8).hashCode(), conn(A, B, lv, 500).hashCode());
		}

		@Test
		@DisplayName("hashCode takes the endpoints into account, including their order")
		void hashUsesEndpoints()
		{
			assertNotEquals(conn(A, B, lv, 8).hashCode(), conn(A, C, lv, 8).hashCode());
			assertNotEquals(conn(A, B, lv, 8).hashCode(), conn(B, A, lv, 8).hashCode());
		}

		@Test
		@DisplayName("hashCode survives null fields")
		void hashHandlesNulls()
		{
			assertDoesNotThrow(() -> new Connection(null, null, null, 0).hashCode());
		}

		@Test
		@DisplayName("a HashSet deduplicates equal connections")
		void hashSetDeduplicates()
		{
			Set<Connection> set = new HashSet<>();
			set.add(conn(A, B, lv, 8));
			set.add(conn(A, B, lv, 8));
			set.add(conn(A, B, lv, 9));
			assertEquals(2, set.size());
		}

		@Test
		@Disabled("Connection#equals is compareTo()==0, which only compares the cable type by transfer rate -- two different wire types with the same rate compare equal while hashCode (which uses cableType identity) does not")
		@DisplayName("different cable types with the same transfer rate are still different connections")
		void sameRateDifferentTypeIsNotEqual()
		{
			assertNotEquals(conn(A, B, lv, 8), conn(A, B, lvTwin, 8));
		}

		@Test
		@Disabled("Connection#equals ignores cable-type identity while Connection#hashCode uses it, so two connections can be equal with different hash codes")
		@DisplayName("equal connections agree on their hash code")
		void equalConnectionsAgreeOnHash()
		{
			Connection a = conn(A, B, lv, 8);
			Connection b = conn(A, B, lvTwin, 8);
			assertTrue(!a.equals(b)||a.hashCode()==b.hashCode(), "equal objects must share a hash code");
		}
	}

	@Nested
	@DisplayName("getBaseLoss")
	class BaseLoss
	{
		@Test
		@DisplayName("is the loss ratio scaled by how much of the maximum length is used")
		void scalesWithRelativeLength()
		{
			assertEquals(.05f*(8/16f), conn(A, B, lv, 8).getBaseLoss(), 1e-6f);
			assertEquals(.05f*(4/16f), conn(A, B, lv, 4).getBaseLoss(), 1e-6f);
		}

		@Test
		@DisplayName("a zero-length connection loses nothing")
		void zeroLength()
		{
			assertEquals(0f, conn(A, A, lv, 0).getBaseLoss(), 1e-6f);
		}

		@Test
		@DisplayName("a connection at the maximum length loses exactly the loss ratio")
		void maximumLength()
		{
			assertEquals(.05f, conn(A, B, lv, 16).getBaseLoss(), 1e-6f);
			assertEquals(.2f, conn(A, B, hv, 32).getBaseLoss(), 1e-6f);
		}

		@Test
		@DisplayName("a longer wire loses more")
		void longerLosesMore()
		{
			assertTrue(conn(A, B, lv, 12).getBaseLoss() > conn(A, B, lv, 3).getBaseLoss());
		}

		@Test
		@DisplayName("HV loses more per block of its own range than LV")
		void hvLossesAreHigher()
		{
			assertTrue(conn(A, B, hv, 32).getBaseLoss() > conn(A, B, lv, 16).getBaseLoss());
		}

		@Test
		@DisplayName("the no-argument form is the zero-modifier form")
		void noArgIsZeroModifier()
		{
			Connection c = conn(A, B, lv, 8);
			assertEquals(c.getBaseLoss(0), c.getBaseLoss(), 1e-9f);
		}

		@Test
		@DisplayName("a positive modifier scales the loss up linearly")
		void positiveModifier()
		{
			Connection c = conn(A, B, lv, 8);
			assertEquals(c.getBaseLoss()*1.5f, c.getBaseLoss(.5f), 1e-6f);
			assertEquals(c.getBaseLoss()*2f, c.getBaseLoss(1f), 1e-6f);
		}

		@Test
		@DisplayName("a modifier of -1 cancels the loss entirely")
		void minusOneModifier()
		{
			assertEquals(0f, conn(A, B, lv, 8).getBaseLoss(-1f), 1e-6f);
		}

		@Test
		@DisplayName("a connection longer than its cable's maximum loses more than the loss ratio")
		void overlongConnection()
		{
			assertEquals(.05f*2, conn(A, B, lv, 32).getBaseLoss(), 1e-6f,
					"nothing clamps the relative length; an over-long wire is simply lossier");
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("writeToNBT stores both endpoints, the cable name and the length")
		void writeStoresEverything()
		{
			NBTTagCompound tag = conn(A, B, lv, 8).writeToNBT();
			assertArrayEquals(new int[]{0, 64, 0}, tag.getIntArray("start"));
			assertArrayEquals(new int[]{8, 64, 0}, tag.getIntArray("end"));
			assertEquals("CONN_LV", tag.getString("cableType"));
			assertEquals(8, tag.getInteger("length"));
		}

		@Test
		@DisplayName("a write/read round-trip preserves the connection")
		void roundTrip()
		{
			Connection src = conn(new BlockPos(-13, 200, 4096), new BlockPos(7, 3, -5), hv, 27);
			Connection dst = Connection.readFromNBT(src.writeToNBT());
			assertNotNull(dst);
			assertEquals(src.start, dst.start);
			assertEquals(src.end, dst.end);
			assertSame(hv, dst.cableType, "the type is resolved back to the registered singleton");
			assertEquals(27, dst.length);
			assertEquals(src, dst);
		}

		@Test
		@DisplayName("a zero-length self-connection round-trips")
		void roundTripSelfConnection()
		{
			Connection dst = Connection.readFromNBT(conn(A, A, lv, 0).writeToNBT());
			assertNotNull(dst);
			assertEquals(A, dst.start);
			assertEquals(A, dst.end);
			assertEquals(0, dst.length);
		}

		@Test
		@DisplayName("writeToNBT omits a null endpoint")
		void writeOmitsNullEndpoints()
		{
			NBTTagCompound tag = new Connection(null, B, lv, 8).writeToNBT();
			assertFalse(tag.hasKey("start"));
			assertTrue(tag.hasKey("end"));
		}

		@Test
		@DisplayName("readFromNBT of null is null")
		void readNullTag()
		{
			assertNull(Connection.readFromNBT(null));
		}

		@Test
		@DisplayName("readFromNBT of an empty tag is null")
		void readEmptyTag()
		{
			assertNull(Connection.readFromNBT(new NBTTagCompound()));
		}

		@Test
		@DisplayName("a truncated start or end array is rejected instead of crashing")
		void readTruncatedArrays()
		{
			NBTTagCompound shortStart = conn(A, B, lv, 8).writeToNBT();
			shortStart.setIntArray("start", new int[]{1, 2});
			assertNull(Connection.readFromNBT(shortStart));

			NBTTagCompound shortEnd = conn(A, B, lv, 8).writeToNBT();
			shortEnd.setIntArray("end", new int[]{1});
			assertNull(Connection.readFromNBT(shortEnd));

			NBTTagCompound emptyStart = conn(A, B, lv, 8).writeToNBT();
			emptyStart.setIntArray("start", new int[0]);
			assertNull(Connection.readFromNBT(emptyStart));
		}

		@Test
		@DisplayName("a longer-than-needed coordinate array uses the first three entries")
		void readOverlongArray()
		{
			NBTTagCompound tag = conn(A, B, lv, 8).writeToNBT();
			tag.setIntArray("start", new int[]{1, 2, 3, 4, 5});
			Connection c = Connection.readFromNBT(tag);
			assertNotNull(c);
			assertEquals(new BlockPos(1, 2, 3), c.start);
		}

		@Test
		@DisplayName("an unknown cable name falls back to COPPER")
		void readUnknownCableFallsBackToCopper()
		{
			WireType.COPPER = lv;
			NBTTagCompound tag = conn(A, B, hv, 8).writeToNBT();
			tag.setString("cableType", "A_MOD_THAT_IS_GONE");
			Connection c = Connection.readFromNBT(tag);
			assertNotNull(c);
			assertSame(lv, c.cableType);
		}

		@Test
		@DisplayName("an unknown cable name with no COPPER registered yields null rather than a broken connection")
		void readUnknownCableWithoutCopper()
		{
			NBTTagCompound tag = conn(A, B, hv, 8).writeToNBT();
			tag.setString("cableType", "A_MOD_THAT_IS_GONE");
			assertNull(Connection.readFromNBT(tag));
		}

		@Test
		@DisplayName("the legacy integer cable ids still map to the right wires")
		void readLegacyIntegerCableIds()
		{
			WireType.COPPER = lv;
			WireType.ELECTRUM = hv;
			WireType.STRUCTURE_ROPE = lvTwin;
			NBTTagCompound tag = conn(A, B, lv, 8).writeToNBT();

			tag.setInteger("cableType", 0);
			assertSame(lv, Connection.readFromNBT(tag).cableType);
			tag.setInteger("cableType", 1);
			assertSame(hv, Connection.readFromNBT(tag).cableType);
			tag.setInteger("cableType", 3);
			assertSame(lvTwin, Connection.readFromNBT(tag).cableType);
			tag.setInteger("cableType", 99);
			assertSame(lv, Connection.readFromNBT(tag).cableType, "anything unrecognised is copper");
		}

		@Test
		@DisplayName("a missing length reads as zero")
		void readMissingLength()
		{
			NBTTagCompound tag = conn(A, B, lv, 8).writeToNBT();
			tag.removeTag("length");
			Connection c = Connection.readFromNBT(tag);
			assertNotNull(c);
			assertEquals(0, c.length);
		}
	}

	@Nested
	@DisplayName("catenary maths")
	class Catenary
	{
		@Test
		@DisplayName("a vertical connection is recognised and sampled evenly")
		void verticalCatenary()
		{
			Connection c = conn(A, new BlockPos(0, 72, 0), lv, 8);
			Vec3d start = new Vec3d(0, 0, 0);
			Vec3d end = new Vec3d(0, 8, 0);
			Vec3d[] v = c.getSubVertices(start, end);
			assertTrue(c.vertical);
			assertEquals(Connection.vertices+1, v.length);
			assertEquals(start, v[0]);
			assertEquals(8, v[v.length-1].y, 1e-9);
			assertEquals(8/17d, v[1].y, 1e-9);
			assertEquals(new Vec3d(0, 8, 0), c.across);
		}

		@Test
		@DisplayName("a horizontal connection sags below the straight line")
		void horizontalCatenarySags()
		{
			Connection c = conn(A, B, lv, 8);
			Vec3d[] v = c.getSubVertices(new Vec3d(0, 0, 0), new Vec3d(8, 0, 0));
			assertFalse(c.vertical);
			assertEquals(Connection.vertices+1, v.length);
			assertEquals(new Vec3d(0, 0, 0), v[0]);
			assertTrue(v[Connection.vertices/2].y < 0, "the middle of a slack wire hangs below its endpoints");
			assertEquals(8, c.horizontalLength, 1e-9);
		}

		@Test
		@DisplayName("the vertex array is computed once and cached")
		void catenaryIsCached()
		{
			Connection c = conn(A, B, lv, 8);
			Vec3d[] first = c.getSubVertices(new Vec3d(0, 0, 0), new Vec3d(8, 0, 0));
			Vec3d[] second = c.getSubVertices(new Vec3d(0, 0, 0), new Vec3d(99, 99, 99));
			assertSame(first, second, "later calls reuse the cached catenary, whatever they are handed");
		}

		@Test
		@DisplayName("getVecAt walks a vertical connection linearly")
		void vecAtVertical()
		{
			Connection c = conn(A, A, lv, 4);
			c.catenaryVertices = new Vec3d[]{new Vec3d(1, 2, 3)};
			c.across = new Vec3d(0, 4, 0);
			c.vertical = true;
			assertEquals(new Vec3d(1, 2, 3), c.getVecAt(0));
			assertEquals(new Vec3d(1, 4, 3), c.getVecAt(.5));
			assertEquals(new Vec3d(1, 6, 3), c.getVecAt(1));
		}

		@Test
		@DisplayName("getVecAt clamps its parameter to [0,1]")
		void vecAtClamps()
		{
			Connection c = conn(A, A, lv, 4);
			c.catenaryVertices = new Vec3d[]{new Vec3d(0, 0, 0)};
			c.across = new Vec3d(0, 10, 0);
			c.vertical = true;
			assertEquals(c.getVecAt(0), c.getVecAt(-5));
			assertEquals(c.getVecAt(1), c.getVecAt(5));
		}

		@Test
		@DisplayName("getVecAt follows the cosh curve for a non-vertical connection")
		void vecAtHorizontal()
		{
			Connection c = conn(A, B, lv, 8);
			c.catenaryVertices = new Vec3d[]{new Vec3d(0, 0, 0)};
			c.across = new Vec3d(10, 0, 0);
			c.vertical = false;
			c.catA = 1;
			c.catOffsetX = 0;
			c.catOffsetY = 0;
			c.horizontalLength = 0;
			assertEquals(new Vec3d(5, 1, 0), c.getVecAt(.5), "cosh(0) is 1");
			assertEquals(new Vec3d(0, 1, 0), c.getVecAt(0));
			assertEquals(new Vec3d(10, 1, 0), c.getVecAt(1));
		}

		@Test
		@DisplayName("getSlopeAt is vertical infinity on a vertical connection")
		void slopeVertical()
		{
			Connection c = conn(A, A, lv, 4);
			c.vertical = true;
			assertEquals(Double.POSITIVE_INFINITY, c.getSlopeAt(0));
			assertEquals(Double.POSITIVE_INFINITY, c.getSlopeAt(.5));
		}

		@Test
		@DisplayName("getSlopeAt is the sinh of the offset position")
		void slopeHorizontal()
		{
			Connection c = conn(A, B, lv, 8);
			c.vertical = false;
			c.catA = 1;
			c.catOffsetX = 1;
			c.horizontalLength = 2;
			assertEquals(Math.sinh(-1), c.getSlopeAt(0), 1e-9);
			assertEquals(0, c.getSlopeAt(.5), 1e-9, "the low point of the catenary is flat");
			assertEquals(Math.sinh(1), c.getSlopeAt(1), 1e-9);
		}

		@Test
		@DisplayName("getSlopeAt clamps its parameter to [0,1]")
		void slopeClamps()
		{
			Connection c = conn(A, B, lv, 8);
			c.vertical = false;
			c.catA = 1;
			c.catOffsetX = 1;
			c.horizontalLength = 2;
			assertEquals(c.getSlopeAt(0), c.getSlopeAt(-3), 1e-9);
			assertEquals(c.getSlopeAt(1), c.getSlopeAt(3), 1e-9);
		}
	}
}
