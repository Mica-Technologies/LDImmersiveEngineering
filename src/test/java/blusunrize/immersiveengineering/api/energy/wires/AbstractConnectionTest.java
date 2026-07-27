/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractConnection} is a whole route between two connectors: a {@link Connection} facade
 * over the list of wires the energy actually travels through.
 */
class AbstractConnectionTest
{
	private static final BlockPos A = new BlockPos(0, 64, 0);
	private static final BlockPos M = new BlockPos(8, 64, 0);
	private static final BlockPos B = new BlockPos(16, 64, 0);

	private WireType lv;
	private WireType hv;

	@BeforeEach
	void setUp()
	{
		TestWireType.resetRegistries();
		TestWireType.installConfigArrays();
		// maxLength 16, so a length-8 wire loses half of the loss ratio: .025 for lv, .1 for hv
		lv = new TestWireType("ABS_LV", .05, 256, 16, WireType.LV_CATEGORY, true, 0);
		hv = new TestWireType("ABS_HV", .2, 4096, 16, WireType.HV_CATEGORY, true, 0);
	}

	private AbstractConnection route(WireType type, int length, Connection... parts)
	{
		return new AbstractConnection(A, B, type, length, parts);
	}

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("keeps the Connection fields it inherits")
		void keepsInheritedFields()
		{
			AbstractConnection ac = route(lv, 16, new Connection(A, M, lv, 8), new Connection(M, B, lv, 8));
			assertSame(A, ac.start);
			assertSame(B, ac.end);
			assertSame(lv, ac.cableType);
			assertEquals(16, ac.length);
			assertEquals(2, ac.subConnections.length);
		}

		@Test
		@DisplayName("defaults to being an energy output")
		void defaultsToOutput()
		{
			assertTrue(route(lv, 8, new Connection(A, B, lv, 8)).isEnergyOutput);
		}

		@Test
		@DisplayName("the explicit constructor can mark a route as a non-output")
		void explicitNonOutput()
		{
			AbstractConnection ac = new AbstractConnection(A, B, lv, 8, false, new Connection(A, B, lv, 8));
			assertFalse(ac.isEnergyOutput);
		}

		@Test
		@DisplayName("accepts a route with no sub-connections")
		void emptyRoute()
		{
			AbstractConnection ac = route(lv, 0);
			assertEquals(0, ac.subConnections.length);
		}

		@Test
		@DisplayName("is itself a Connection")
		void isAConnection()
		{
			assertTrue(route(lv, 8, new Connection(A, B, lv, 8)) instanceof Connection);
		}
	}

	@Nested
	@DisplayName("getAverageLossRate")
	class AverageLoss
	{
		@Test
		@DisplayName("sums the base loss of every leg")
		void sumsLegs()
		{
			AbstractConnection ac = route(lv, 16, new Connection(A, M, lv, 8), new Connection(M, B, lv, 8));
			assertEquals(.05f, ac.getAverageLossRate(), 1e-6f, "two half-length LV wires lose .025 each");
		}

		@Test
		@DisplayName("a single-leg route loses exactly what that leg loses")
		void singleLeg()
		{
			Connection only = new Connection(A, B, lv, 8);
			assertEquals(only.getBaseLoss(), route(lv, 8, only).getAverageLossRate(), 1e-6f);
		}

		@Test
		@DisplayName("a route with no legs loses nothing")
		void emptyRouteLosesNothing()
		{
			assertEquals(0f, route(lv, 0).getAverageLossRate(), 1e-9f);
		}

		@Test
		@DisplayName("mixes the loss of legs of different tiers")
		void mixedTiers()
		{
			AbstractConnection ac = route(lv, 16, new Connection(A, M, lv, 8), new Connection(M, B, hv, 8));
			assertEquals(.025f+.1f, ac.getAverageLossRate(), 1e-6f);
		}

		@Test
		@DisplayName("is capped at 1, so a network can never lose more than everything")
		void cappedAtOne()
		{
			Connection[] legs = new Connection[40];
			for(int i = 0; i < legs.length; i++)
				legs[i] = new Connection(A, B, hv, 16);
			assertEquals(1f, route(hv, 640, legs).getAverageLossRate(), 1e-9f);
		}

		@Test
		@DisplayName("reaches exactly 1 without being clipped early")
		void exactlyOne()
		{
			Connection[] legs = new Connection[5];
			for(int i = 0; i < legs.length; i++)
				legs[i] = new Connection(A, B, hv, 16);// .2 each
			assertEquals(1f, route(hv, 80, legs).getAverageLossRate(), 1e-6f);
		}

		@Test
		@DisplayName("is computed once and then cached")
		void isCached()
		{
			Connection leg = new Connection(A, B, lv, 8);
			AbstractConnection ac = route(lv, 8, leg);
			float first = ac.getAverageLossRate();
			leg.length = 16;// would double the loss if it were recomputed
			assertEquals(first, ac.getAverageLossRate(), 0f, "the average loss is memoised after the first call");
		}

		@Test
		@DisplayName("a longer route loses more than a shorter one over the same cable")
		void longerRouteLosesMore()
		{
			AbstractConnection shortRoute = route(lv, 4, new Connection(A, B, lv, 4));
			AbstractConnection longRoute = route(lv, 12, new Connection(A, B, lv, 12));
			assertTrue(longRoute.getAverageLossRate() > shortRoute.getAverageLossRate());
		}
	}

	@Nested
	@DisplayName("getPreciseLossRate")
	class PreciseLoss
	{
		@Test
		@DisplayName("matches the average when the connector is running at full input")
		void fullInputMatchesAverage()
		{
			AbstractConnection ac = route(lv, 16, new Connection(A, M, lv, 8), new Connection(M, B, lv, 8));
			assertEquals(ac.getAverageLossRate(), ac.getPreciseLossRate(1000, 1000), 1e-6f);
		}

		@Test
		@DisplayName("an idle connector is penalised by the full 40% modifier")
		void idleConnectorIsPenalised()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertEquals(.025f*1.4f, ac.getPreciseLossRate(0, 1000), 1e-6f);
		}

		@Test
		@DisplayName("the penalty scales linearly with how idle the connector is")
		void penaltyScales()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertEquals(.025f*1.2f, ac.getPreciseLossRate(500, 1000), 1e-6f);
			assertEquals(.025f*1.1f, ac.getPreciseLossRate(750, 1000), 1e-6f);
		}

		@Test
		@DisplayName("an over-driven connector gets a discount")
		void overdriveDiscount()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertEquals(.025f*.6f, ac.getPreciseLossRate(2000, 1000), 1e-6f);
		}

		@Test
		@DisplayName("is capped at 1")
		void cappedAtOne()
		{
			Connection[] legs = new Connection[20];
			for(int i = 0; i < legs.length; i++)
				legs[i] = new Connection(A, B, hv, 16);
			assertEquals(1f, route(hv, 320, legs).getPreciseLossRate(0, 1000), 1e-9f);
		}

		@Test
		@DisplayName("a route with no legs loses nothing whatever the load")
		void emptyRoute()
		{
			assertEquals(0f, route(lv, 0).getPreciseLossRate(0, 1000), 1e-9f);
		}

		@Test
		@DisplayName("is recomputed on every call, unlike the average")
		void notCached()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertNotEquals(ac.getPreciseLossRate(0, 1000), ac.getPreciseLossRate(1000, 1000));
		}
	}

	@Nested
	@DisplayName("compareTo")
	class Ordering
	{
		@Test
		@DisplayName("the lossier route sorts later")
		void lessLossyFirst()
		{
			AbstractConnection cheap = route(lv, 4, new Connection(A, B, lv, 4));
			AbstractConnection dear = route(lv, 12, new Connection(A, B, lv, 12));
			assertTrue(cheap.compareTo(dear) < 0);
			assertTrue(dear.compareTo(cheap) > 0);
		}

		@Test
		@DisplayName("routes with equal loss fall through to the Connection ordering")
		void tieFallsThroughToSuper()
		{
			AbstractConnection viaLv = new AbstractConnection(A, B, lv, 8, new Connection(A, B, lv, 8));
			AbstractConnection viaHv = new AbstractConnection(A, B, hv, 2, new Connection(A, B, lv, 8));
			assertEquals(viaLv.getAverageLossRate(), viaHv.getAverageLossRate(), 0f);
			assertTrue(viaHv.compareTo(viaLv) < 0, "with the loss tied, the faster cable wins");
		}

		@Test
		@DisplayName("an identical route compares equal")
		void identicalCompareEqual()
		{
			assertEquals(0, route(lv, 8, new Connection(A, B, lv, 8))
					.compareTo(route(lv, 8, new Connection(A, B, lv, 8))));
		}

		@Test
		@DisplayName("comparing against a plain Connection is the negation of the reverse comparison")
		void plainConnectionIsNegated()
		{
			AbstractConnection ac = route(hv, 8, new Connection(A, B, hv, 8));
			Connection plain = new Connection(A, B, lv, 8);
			assertEquals(-plain.compareTo(ac), ac.compareTo(plain));
			assertTrue(ac.compareTo(plain) < 0, "the HV route still outranks the LV wire");
		}

		@Test
		@DisplayName("stays antisymmetric across a spread of routes")
		void antisymmetric()
		{
			AbstractConnection[] all = {
					route(lv, 4, new Connection(A, B, lv, 4)),
					route(lv, 12, new Connection(A, B, lv, 12)),
					route(hv, 8, new Connection(A, B, hv, 8)),
					new AbstractConnection(A, M, lv, 8, new Connection(A, M, lv, 8))
			};
			for(AbstractConnection x : all)
				for(AbstractConnection y : all)
					assertEquals(Integer.signum(x.compareTo(y)), -Integer.signum(y.compareTo(x)));
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
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertEquals(ac, ac);
		}

		@Test
		@DisplayName("two identical routes are equal and hash alike")
		void identicalAreEqual()
		{
			AbstractConnection a = route(lv, 8, new Connection(A, B, lv, 8));
			AbstractConnection b = route(lv, 8, new Connection(A, B, lv, 8));
			assertEquals(a, b);
			assertEquals(b, a);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("routes with different legs are not equal")
		void legsDiscriminate()
		{
			assertNotEquals(route(lv, 8, new Connection(A, M, lv, 8)),
					route(lv, 8, new Connection(A, B, lv, 8)));
		}

		@Test
		@DisplayName("a different number of legs makes routes unequal")
		void legCountDiscriminates()
		{
			assertNotEquals(route(lv, 8, new Connection(A, B, lv, 8)),
					route(lv, 8, new Connection(A, M, lv, 4), new Connection(M, B, lv, 4)));
		}

		@Test
		@DisplayName("is never equal to null or an unrelated type")
		void notEqualToOther()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertNotEquals(null, ac);
			assertNotEquals("route", ac);
		}

		@Test
		@DisplayName("a route is not equal to a plain Connection")
		void notEqualToPlainConnection()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertNotEquals(ac, new Connection(A, B, lv, 8), "the exact-class check rejects the superclass");
		}

		@Test
		@DisplayName("hashCode is stable across repeated calls")
		void hashIsStable()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			int first = ac.hashCode();
			assertEquals(first, ac.hashCode());
			assertEquals(first, ac.hashCode());
		}

		@Test
		@DisplayName("hashCode differs from the plain Connection hash of the same endpoints")
		void hashDiffersFromSuper()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			assertNotEquals(new Connection(A, B, lv, 8).hashCode(), ac.hashCode());
		}

		@Test
		@DisplayName("a HashSet deduplicates equal routes")
		void hashSetDeduplicates()
		{
			Set<AbstractConnection> set = new HashSet<>();
			set.add(route(lv, 8, new Connection(A, B, lv, 8)));
			set.add(route(lv, 8, new Connection(A, B, lv, 8)));
			set.add(route(lv, 8, new Connection(A, M, lv, 8)));
			assertEquals(2, set.size());
		}

		@Test
		@Disabled("Connection#equals accepts any Connection and compares only by compareTo, while AbstractConnection#equals demands an exact class match -- so plainConnection.equals(route) can be true while route.equals(plainConnection) is false")
		@DisplayName("equality between a route and a plain Connection is symmetric")
		void symmetricWithPlainConnection()
		{
			AbstractConnection ac = route(lv, 8, new Connection(A, B, lv, 8));
			Connection plain = new Connection(A, B, lv, 8);
			assertEquals(plain.equals(ac), ac.equals(plain), "equals must be symmetric in both directions");
		}
	}
}
