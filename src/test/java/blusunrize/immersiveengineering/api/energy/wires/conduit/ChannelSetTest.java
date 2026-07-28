/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payload a conduit connection carries.
 * <p>
 * This is the class the whole feature's performance argument rests on -- a sixteen-channel run is
 * one edge in the connection graph, and this is what rides on it -- so it is tested harder than
 * its size suggests. In particular {@link Intersecting} is the behaviour a route walk will lean on
 * every tick, and {@link Persistence} is the behaviour that decides whether somebody's base
 * survives a restart.
 */
class ChannelSetTest
{
	/** IE's own copper: 2048 IF/t, and a loss that matters over distance. */
	private static ChannelSpec copper()
	{
		return new ChannelSpec("COPPER", 2048, 0.05);
	}

	private static ChannelSpec steel()
	{
		return new ChannelSpec("STEEL", 32768, 0.025);
	}

	@Nested
	@DisplayName("patching")
	class Patching
	{
		@Test
		@DisplayName("a new set carries nothing")
		void startsEmpty()
		{
			ChannelSet set = new ChannelSet();
			assertTrue(set.isEmpty());
			assertEquals(0, set.size());
			assertEquals(0, set.getMask());
			for(WireChannel channel : WireChannel.VALUES)
			{
				assertFalse(set.isPatched(channel));
				assertNull(set.getSpec(channel));
			}
		}

		@Test
		@DisplayName("a patched channel reports its spec")
		void patchThenRead()
		{
			ChannelSet set = new ChannelSet();
			assertNull(set.patch(WireChannel.BLUE, copper()));
			assertTrue(set.isPatched(WireChannel.BLUE));
			assertEquals(copper(), set.getSpec(WireChannel.BLUE));
			assertEquals(1, set.size());
			assertEquals(WireChannel.BLUE.getMask(), set.getMask());
		}

		@Test
		@DisplayName("re-patching replaces and hands back what was there")
		void repatchReturnsOld()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			assertEquals(copper(), set.patch(WireChannel.BLUE, steel()));
			assertEquals(steel(), set.getSpec(WireChannel.BLUE));
			assertEquals(1, set.size(), "re-patching must not add a second entry");
		}

		@Test
		@DisplayName("unpatching clears the bit as well as the entry")
		void unpatchClearsMask()
		{
			//The mask is derived state. If it and the map ever disagree, intersect() starts
			//dereferencing a spec that is not there, and the crash is nowhere near the cause.
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.RED, copper());
			set.patch(WireChannel.GREEN, copper());
			assertEquals(copper(), set.unpatch(WireChannel.RED));
			assertFalse(set.isPatched(WireChannel.RED));
			assertEquals(WireChannel.GREEN.getMask(), set.getMask());
			assertEquals(1, set.size());
		}

		@Test
		@DisplayName("unpatching a free channel is a no-op, not an error")
		void unpatchFreeChannel()
		{
			ChannelSet set = new ChannelSet();
			assertNull(set.unpatch(WireChannel.PINK));
			assertNull(set.unpatch(null));
			assertTrue(set.isEmpty());
		}

		@Test
		@DisplayName("all sixteen can be patched at once")
		void allSixteen()
		{
			ChannelSet set = new ChannelSet();
			for(WireChannel channel : WireChannel.VALUES)
				set.patch(channel, copper());
			assertEquals(16, set.size());
			assertEquals(WireChannel.ALL_MASK, set.getMask());
		}

		@Test
		@DisplayName("patching refuses a missing channel or spec")
		void patchRefusesNulls()
		{
			ChannelSet set = new ChannelSet();
			assertThrows(IllegalArgumentException.class, () -> set.patch(null, copper()));
			assertThrows(IllegalArgumentException.class, () -> set.patch(WireChannel.BLUE, null));
		}

		@Test
		@DisplayName("a copy is independent of its original")
		void copyIsIndependent()
		{
			ChannelSet original = new ChannelSet();
			original.patch(WireChannel.BLUE, copper());
			ChannelSet copy = new ChannelSet(original);
			copy.patch(WireChannel.RED, steel());
			assertEquals(1, original.size(), "editing the copy changed the original");
			assertEquals(2, copy.size());
			assertTrue(copy.isPatched(WireChannel.BLUE));
		}

		@Test
		@DisplayName("a copy has carried nothing yet")
		void copyHasNoThroughput()
		{
			//A copy is a different edge. Inheriting the original's last-tick figures would have a
			//brand new conduit claiming to be busy before it moved anything.
			ChannelSet original = new ChannelSet();
			original.patch(WireChannel.BLUE, copper());
			original.setLastThroughput(WireChannel.BLUE, 500);
			assertEquals(0, new ChannelSet(original).getLastThroughput(WireChannel.BLUE));
		}
	}

	@Nested
	@DisplayName("specs")
	class Specs
	{
		@Test
		@DisplayName("a spec needs a wire name")
		void nameIsRequired()
		{
			assertThrows(IllegalArgumentException.class, () -> new ChannelSpec(null, 100, 0.1));
			assertThrows(IllegalArgumentException.class, () -> new ChannelSpec("", 100, 0.1));
		}

		@Test
		@DisplayName("a negative rate clamps to zero rather than carrying backwards")
		void rateIsClamped()
		{
			assertEquals(0, new ChannelSpec("COPPER", -50, 0.1).getTransferRate());
		}

		@Test
		@DisplayName("loss is clamped to a ratio")
		void lossIsClamped()
		{
			//A loss above 1 is a wire that eats more than it carries; below 0 is free energy.
			//Clamping rather than throwing, because a bad config value should degrade the wire and
			//not refuse to load the save it is written into.
			assertEquals(1.0, new ChannelSpec("COPPER", 100, 4).getLossRatio());
			assertEquals(0.0, new ChannelSpec("COPPER", 100, -1).getLossRatio());
		}

		@Test
		@DisplayName("reconciling takes the narrower rate and its name")
		void reconcileTakesTheNarrower()
		{
			//A route is as fat as its narrowest segment, and the readout should name the wire that
			//is actually limiting it rather than whichever end was asked first.
			ChannelSpec result = steel().reconcile(copper());
			assertNotNull(result);
			assertEquals(2048, result.getTransferRate());
			assertEquals("COPPER", result.getName());
			assertEquals(copper().reconcile(steel()), result, "reconcile must be symmetric");
		}

		@Test
		@DisplayName("reconciling takes the worse loss, not the sum")
		void reconcileTakesTheWorseLoss()
		{
			//Maximum rather than sum: this is one hop charging its own loss when its two ends
			//disagree about the wire, not a running total for a route. Summing here would
			//double-count the moment a third conduit joined the run.
			ChannelSpec result = steel().reconcile(copper());
			assertNotNull(result);
			assertEquals(0.05, result.getLossRatio());
		}

		@Test
		@DisplayName("reconciling with nothing gives nothing")
		void reconcileWithNull()
		{
			assertNull(copper().reconcile(null));
		}

		@Test
		@DisplayName("a spec round-trips through NBT")
		void specRoundTrip()
		{
			assertEquals(copper(), ChannelSpec.readFromNBT(copper().writeToNBT()));
		}

		@Test
		@DisplayName("a spec with no wire name will not load")
		void specWithoutNameIsNull()
		{
			assertNull(ChannelSpec.readFromNBT(null));
			assertNull(ChannelSpec.readFromNBT(new NBTTagCompound()));
		}
	}

	@Nested
	@DisplayName("intersecting")
	class Intersecting
	{
		@Test
		@DisplayName("only channels patched at both ends survive")
		void onlySharedChannelsSurvive()
		{
			//A conductor that stops at a junction box does not continue past it.
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.RED, copper());
			a.patch(WireChannel.BLUE, copper());
			ChannelSet b = new ChannelSet();
			b.patch(WireChannel.BLUE, copper());
			b.patch(WireChannel.GREEN, copper());

			ChannelSet shared = a.intersect(b);
			assertEquals(1, shared.size());
			assertTrue(shared.isPatched(WireChannel.BLUE));
			assertFalse(shared.isPatched(WireChannel.RED));
			assertFalse(shared.isPatched(WireChannel.GREEN));
		}

		@Test
		@DisplayName("a shared channel takes the narrower wire")
		void sharedChannelTakesTheMinimum()
		{
			ChannelSet fat = new ChannelSet();
			fat.patch(WireChannel.BLUE, steel());
			ChannelSet thin = new ChannelSet();
			thin.patch(WireChannel.BLUE, copper());
			assertEquals(2048, fat.intersect(thin).getSpec(WireChannel.BLUE).getTransferRate());
		}

		@Test
		@DisplayName("nothing in common gives an empty set, not null")
		void disjointGivesEmpty()
		{
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.RED, copper());
			ChannelSet b = new ChannelSet();
			b.patch(WireChannel.BLUE, copper());
			assertTrue(a.intersect(b).isEmpty());
		}

		@Test
		@DisplayName("intersecting with nothing gives an empty set")
		void intersectNull()
		{
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.RED, copper());
			assertTrue(a.intersect(null).isEmpty());
		}

		@Test
		@DisplayName("neither input is modified")
		void intersectDoesNotMutate()
		{
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.RED, copper());
			a.patch(WireChannel.BLUE, steel());
			ChannelSet b = new ChannelSet();
			b.patch(WireChannel.BLUE, copper());
			a.intersect(b);
			assertEquals(2, a.size());
			assertEquals(steel(), a.getSpec(WireChannel.BLUE), "a's own spec was overwritten");
			assertEquals(1, b.size());
		}

		@Test
		@DisplayName("intersecting is symmetric")
		void intersectIsSymmetric()
		{
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.RED, steel());
			a.patch(WireChannel.BLUE, copper());
			ChannelSet b = new ChannelSet();
			b.patch(WireChannel.BLUE, steel());
			b.patch(WireChannel.GREEN, copper());
			assertEquals(a.intersect(b), b.intersect(a));
		}

		@Test
		@DisplayName("a long route narrows once and stays narrow")
		void chainedIntersectionNarrows()
		{
			//What a route walk will actually do: fold the sets along the path. A copper segment in
			//the middle of a steel run has to cap the whole thing.
			ChannelSet fat = new ChannelSet();
			fat.patch(WireChannel.BLUE, steel());
			ChannelSet thin = new ChannelSet();
			thin.patch(WireChannel.BLUE, copper());

			ChannelSet route = fat.intersect(fat).intersect(thin).intersect(fat);
			assertEquals(2048, route.getSpec(WireChannel.BLUE).getTransferRate());
			assertEquals("COPPER", route.getSpec(WireChannel.BLUE).getName());
		}

		@Test
		@DisplayName("all sixteen intersect at once")
		void fullBundleIntersects()
		{
			ChannelSet a = new ChannelSet();
			ChannelSet b = new ChannelSet();
			for(WireChannel channel : WireChannel.VALUES)
			{
				a.patch(channel, steel());
				b.patch(channel, copper());
			}
			ChannelSet shared = a.intersect(b);
			assertEquals(16, shared.size());
			assertEquals(WireChannel.ALL_MASK, shared.getMask());
			for(WireChannel channel : WireChannel.VALUES)
				assertEquals(2048, shared.getSpec(channel).getTransferRate());
		}
	}

	@Nested
	@DisplayName("throughput")
	class Throughput
	{
		@Test
		@DisplayName("a channel that carried nothing reads zero")
		void unreadIsZero()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			assertEquals(0, set.getLastThroughput(WireChannel.BLUE));
			assertEquals(0, set.getLastThroughput(WireChannel.RED));
			assertEquals(0, set.getLastThroughput(null));
		}

		@Test
		@DisplayName("an unpatched channel refuses to record a figure")
		void unpatchedRecordsNothing()
		{
			//Otherwise the console draws a reading on a row that has no conductor behind it.
			ChannelSet set = new ChannelSet();
			set.setLastThroughput(WireChannel.BLUE, 400);
			assertEquals(0, set.getLastThroughput(WireChannel.BLUE));
			assertEquals(0, set.getTotalThroughput());
		}

		@Test
		@DisplayName("unpatching forgets what the channel carried")
		void unpatchClearsThroughput()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.setLastThroughput(WireChannel.BLUE, 400);
			set.unpatch(WireChannel.BLUE);
			assertEquals(0, set.getLastThroughput(WireChannel.BLUE));
			assertEquals(0, set.getTotalThroughput());
		}

		@Test
		@DisplayName("the total is the sum across channels")
		void totalSums()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.patch(WireChannel.RED, steel());
			set.setLastThroughput(WireChannel.BLUE, 400);
			set.setLastThroughput(WireChannel.RED, 1600);
			assertEquals(2000, set.getTotalThroughput());
			set.clearThroughput();
			assertEquals(0, set.getTotalThroughput());
		}

		@Test
		@DisplayName("a negative reading clamps to zero")
		void negativeThroughputClamps()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.setLastThroughput(WireChannel.BLUE, -100);
			assertEquals(0, set.getLastThroughput(WireChannel.BLUE));
		}
	}

	@Nested
	@DisplayName("refreshing against the registry")
	class Refreshing
	{
		private Map<String, ChannelSpec> registry()
		{
			Map<String, ChannelSpec> known = new HashMap<>();
			known.put("COPPER", copper());
			known.put("STEEL", steel());
			return known;
		}

		@Test
		@DisplayName("a changed rate reaches existing conduits")
		void refreshUpdatesTheSnapshot()
		{
			//The snapshot is what keeps the transfer loop off the registry; this is the one place
			//that pays for it, and it runs at server start rather than per tick.
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, new ChannelSpec("COPPER", 1, 0.9));
			Map<String, ChannelSpec> known = registry();
			assertEquals(0, set.refresh(known::get));
			assertEquals(2048, set.getSpec(WireChannel.BLUE).getTransferRate());
			assertEquals(0.05, set.getSpec(WireChannel.BLUE).getLossRatio());
		}

		@Test
		@DisplayName("a wire that no longer exists unpatches its channel")
		void missingWireIsDropped()
		{
			//Losing the circuit is visible and fixable. Leaving it holding a stale figure means
			//energy quietly moving down a wire type that is no longer in the game.
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.patch(WireChannel.RED, new ChannelSpec("SOME_REMOVED_MOD_WIRE", 500, 0.1));
			Map<String, ChannelSpec> known = registry();
			assertEquals(1, set.refresh(known::get));
			assertTrue(set.isPatched(WireChannel.BLUE));
			assertFalse(set.isPatched(WireChannel.RED));
			assertEquals(WireChannel.BLUE.getMask(), set.getMask());
		}

		@Test
		@DisplayName("refreshing an empty set does nothing and asks nothing")
		void refreshEmpty()
		{
			ChannelSet set = new ChannelSet();
			assertEquals(0, set.refresh(name -> {
				throw new AssertionError("the resolver was called for a set with no channels");
			}));
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a patched set round-trips")
		void roundTrip()
		{
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.patch(WireChannel.RED, steel());
			ChannelSet loaded = ChannelSet.readFromNBT(set.writeToNBT());
			assertEquals(set, loaded);
			assertEquals(set.getMask(), loaded.getMask());
			assertEquals(copper(), loaded.getSpec(WireChannel.BLUE));
			assertEquals(steel(), loaded.getSpec(WireChannel.RED));
		}

		@Test
		@DisplayName("a full bundle round-trips")
		void fullBundleRoundTrips()
		{
			ChannelSet set = new ChannelSet();
			for(WireChannel channel : WireChannel.VALUES)
				set.patch(channel, channel.ordinal()%2==0?copper(): steel());
			assertEquals(set, ChannelSet.readFromNBT(set.writeToNBT()));
		}

		@Test
		@DisplayName("an absent tag gives an empty set rather than null")
		void absentTagIsEmpty()
		{
			//This is the case every pre-conduit save hits. Returning null here would mean
			//Connection.readFromNBT has to decide what an old wire means, in a method that
			//currently cannot fail for that reason.
			assertTrue(ChannelSet.readFromNBT(null).isEmpty());
			assertTrue(ChannelSet.readFromNBT(new NBTTagCompound()).isEmpty());
		}

		@Test
		@DisplayName("a channel with an unreadable spec is dropped, not guessed")
		void unreadableChannelIsDropped()
		{
			//Defaulting to some wire type would quietly rewire somebody's base with a conductor
			//they did not choose.
			NBTTagCompound tag = new NBTTagCompound();
			tag.setTag(WireChannel.BLUE.getName(), copper().writeToNBT());
			tag.setTag(WireChannel.RED.getName(), new NBTTagCompound());
			ChannelSet loaded = ChannelSet.readFromNBT(tag);
			assertEquals(1, loaded.size());
			assertTrue(loaded.isPatched(WireChannel.BLUE));
			assertFalse(loaded.isPatched(WireChannel.RED));
		}

		@Test
		@DisplayName("throughput is not saved")
		void throughputIsNotSaved()
		{
			//It is a measurement, not a setting. Persisting it means a freshly-loaded world
			//claiming a conduit was busy at the instant it was saved.
			ChannelSet set = new ChannelSet();
			set.patch(WireChannel.BLUE, copper());
			set.setLastThroughput(WireChannel.BLUE, 900);
			assertEquals(0, ChannelSet.readFromNBT(set.writeToNBT())
					.getLastThroughput(WireChannel.BLUE));
		}

		@Test
		@DisplayName("equality ignores what was carried last tick")
		void equalityIgnoresThroughput()
		{
			ChannelSet a = new ChannelSet();
			a.patch(WireChannel.BLUE, copper());
			ChannelSet b = new ChannelSet();
			b.patch(WireChannel.BLUE, copper());
			a.setLastThroughput(WireChannel.BLUE, 700);
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}
	}
}
