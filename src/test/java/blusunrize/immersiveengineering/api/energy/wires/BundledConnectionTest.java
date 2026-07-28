/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSet;
import blusunrize.immersiveengineering.api.energy.wires.conduit.ChannelSpec;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@link Connection} carrying a bundle instead of a single wire.
 * <p>
 * <strong>The migration half of this is the part that matters.</strong> Connections persist, and
 * every wire in every world built before conduits existed has to load back exactly as it was
 * written -- so the first nested class checks that an ordinary wire's save data has not moved a
 * byte, and that a tag with no channel key comes back as a wire rather than as an empty bundle.
 * Those two distinctions are cheap to get wrong and expensive to notice.
 */
class BundledConnectionTest
{
	private static final BlockPos A = new BlockPos(0, 64, 0);
	private static final BlockPos B = new BlockPos(8, 64, 0);

	private WireType lv;

	@BeforeEach
	void setUp()
	{
		TestWireType.resetRegistries();
		TestWireType.installConfigArrays();
		lv = new TestWireType("BUNDLE_LV", .05, 256, 16, WireType.LV_CATEGORY, true, 0xb36c3f);
	}

	private static ChannelSpec copper()
	{
		return new ChannelSpec("BUNDLE_LV", 256, 0.05);
	}

	private ChannelSet twoChannels()
	{
		ChannelSet set = new ChannelSet();
		set.patch(WireChannel.BLUE, copper());
		set.patch(WireChannel.RED, copper());
		return set;
	}

	@Nested
	@DisplayName("existing saves")
	class Migration
	{
		@Test
		@DisplayName("an ordinary wire is not a bundle")
		void plainWireIsNotABundle()
		{
			Connection wire = new Connection(A, B, lv, 8);
			assertFalse(wire.isBundle());
			assertNull(wire.channels);
		}

		@Test
		@DisplayName("an ordinary wire's tag has no channel key at all")
		void plainWireWritesNoChannelKey()
		{
			//Absent rather than empty. This is what makes a pre-conduit save and a post-conduit
			//save of the same wire byte-identical, and it is what lets the read path tell a wire
			//from a conduit with nothing patched.
			NBTTagCompound tag = new Connection(A, B, lv, 8).writeToNBT();
			assertFalse(tag.hasKey("channels"));
		}

		@Test
		@DisplayName("a tag with no channel key loads as a wire, not an empty bundle")
		void oldTagLoadsAsWire()
		{
			//Every connection in every world that predates conduits takes this branch.
			NBTTagCompound tag = new NBTTagCompound();
			tag.setIntArray("start", new int[]{0, 64, 0});
			tag.setIntArray("end", new int[]{8, 64, 0});
			tag.setString("cableType", lv.getUniqueName());
			tag.setInteger("length", 8);

			Connection loaded = Connection.readFromNBT(tag);
			assertNotNull(loaded);
			assertFalse(loaded.isBundle(), "an old wire came back as a conduit");
			assertNull(loaded.channels);
			assertEquals(A, loaded.start);
			assertEquals(B, loaded.end);
			assertSame(lv, loaded.cableType);
			assertEquals(8, loaded.length);
		}

		@Test
		@DisplayName("an ordinary wire round-trips unchanged")
		void plainWireRoundTrips()
		{
			Connection loaded = Connection.readFromNBT(new Connection(A, B, lv, 8).writeToNBT());
			assertNotNull(loaded);
			assertFalse(loaded.isBundle());
			assertEquals(new Connection(A, B, lv, 8), loaded);
		}

		@Test
		@DisplayName("the guards against corrupt data still fire for bundles")
		void corruptTagStillRefused()
		{
			//Adding a field must not weaken the check that keeps one bad connection from aborting
			//the load of every other one.
			NBTTagCompound tag = new NBTTagCompound();
			tag.setIntArray("start", new int[]{0, 64});
			tag.setIntArray("end", new int[]{8, 64, 0});
			tag.setString("cableType", lv.getUniqueName());
			tag.setTag("channels", twoChannels().writeToNBT());
			assertNull(Connection.readFromNBT(tag));
		}
	}

	@Nested
	@DisplayName("carrying channels")
	class Carrying
	{
		@Test
		@DisplayName("a bundle reports itself as one")
		void bundleReportsItself()
		{
			Connection bundle = new Connection(A, B, lv, 8, twoChannels());
			assertTrue(bundle.isBundle());
			assertEquals(2, bundle.channels.size());
		}

		@Test
		@DisplayName("an empty conduit is still a bundle")
		void emptyConduitIsStillABundle()
		{
			//A conduit with nothing patched is a conduit somebody has not finished wiring, not a
			//plain wire. Collapsing the two would make an empty run behave like a live one.
			Connection bundle = new Connection(A, B, lv, 8, new ChannelSet());
			assertTrue(bundle.isBundle());
			assertTrue(bundle.channels.isEmpty());
		}

		@Test
		@DisplayName("a bundle round-trips with its channels")
		void bundleRoundTrips()
		{
			Connection bundle = new Connection(A, B, lv, 8, twoChannels());
			Connection loaded = Connection.readFromNBT(bundle.writeToNBT());
			assertNotNull(loaded);
			assertTrue(loaded.isBundle());
			assertEquals(twoChannels(), loaded.channels);
		}

		@Test
		@DisplayName("an empty bundle round-trips as a bundle")
		void emptyBundleRoundTrips()
		{
			//The one case where "absent means wire" has to be paired with "present but empty means
			//conduit". If writeToNBT skipped an empty set, an unfinished run would come back a
			//wire.
			Connection loaded = Connection.readFromNBT(
					new Connection(A, B, lv, 8, new ChannelSet()).writeToNBT());
			assertNotNull(loaded);
			assertTrue(loaded.isBundle(), "an empty conduit came back as a wire");
			assertTrue(loaded.channels.isEmpty());
		}

		@Test
		@DisplayName("a full sixteen-channel bundle round-trips")
		void fullBundleRoundTrips()
		{
			ChannelSet full = new ChannelSet();
			for(WireChannel channel : WireChannel.VALUES)
				full.patch(channel, copper());
			Connection loaded = Connection.readFromNBT(
					new Connection(A, B, lv, 8, full).writeToNBT());
			assertNotNull(loaded);
			assertEquals(full, loaded.channels);
			assertEquals(16, loaded.channels.size());
		}
	}

	@Nested
	@DisplayName("identity")
	class Identity
	{
		@Test
		@DisplayName("a bundle and a wire between the same points are different connections")
		void bundleIsNotAWire()
		{
			//Without this they collapse into one entry in the sorted sets that hold connections,
			//and one of them silently stops existing.
			Connection wire = new Connection(A, B, lv, 8);
			Connection bundle = new Connection(A, B, lv, 8, twoChannels());
			assertNotEquals(wire, bundle);
			assertNotEquals(0, wire.compareTo(bundle));
			assertEquals(-wire.compareTo(bundle), Integer.signum(bundle.compareTo(wire)),
					"the comparison has to be antisymmetric or the sorted set misbehaves");
		}

		@Test
		@DisplayName("two bundles patched differently are different connections")
		void differentPatchingIsDifferent()
		{
			ChannelSet oneChannel = new ChannelSet();
			oneChannel.patch(WireChannel.BLUE, copper());
			Connection a = new Connection(A, B, lv, 8, oneChannel);
			Connection b = new Connection(A, B, lv, 8, twoChannels());
			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("two bundles patched the same way are the same connection")
		void samePatchingIsSame()
		{
			assertEquals(new Connection(A, B, lv, 8, twoChannels()),
					new Connection(A, B, lv, 8, twoChannels()));
		}

		@Test
		@DisplayName("two plain wires compare exactly as they did before")
		void plainWiresUnaffected()
		{
			//Both sides land on the same sentinel, so nothing about existing behaviour moves.
			assertEquals(new Connection(A, B, lv, 8), new Connection(A, B, lv, 8));
			assertEquals(0, new Connection(A, B, lv, 8).compareTo(new Connection(A, B, lv, 8)));
		}

		@Test
		@DisplayName("hasSameChannels tells the three cases apart")
		void hasSameChannelsDistinguishes()
		{
			Connection wire = new Connection(A, B, lv, 8);
			Connection otherWire = new Connection(A, B, lv, 8);
			Connection bundle = new Connection(A, B, lv, 8, twoChannels());
			Connection sameBundle = new Connection(A, B, lv, 8, twoChannels());

			assertTrue(wire.hasSameChannels(otherWire));
			assertTrue(bundle.hasSameChannels(sameBundle));
			assertFalse(wire.hasSameChannels(bundle));
			assertFalse(bundle.hasSameChannels(wire));
		}

		@Test
		@DisplayName("a set holds a wire and a bundle side by side")
		void sortedSetKeepsBoth()
		{
			//The failure this guards against is not an exception -- it is a connection quietly
			//vanishing from a save because a TreeSet considered it a duplicate.
			TreeSet<Connection> set = new TreeSet<>();
			assertTrue(set.add(new Connection(A, B, lv, 8)));
			assertTrue(set.add(new Connection(A, B, lv, 8, twoChannels())));
			assertEquals(2, set.size());
			assertFalse(set.add(new Connection(A, B, lv, 8, twoChannels())),
					"the same bundle twice is still one connection");
			assertEquals(2, set.size());
		}
	}
}
