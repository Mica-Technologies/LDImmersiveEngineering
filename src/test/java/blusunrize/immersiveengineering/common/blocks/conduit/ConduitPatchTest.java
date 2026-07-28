/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A junction box's patch table: which conductor leaves by which face.
 * <p>
 * Small, but it is where per-channel addressing actually lives, and the one rule with teeth --
 * a channel can only be patched to one face at a time -- is the difference between a breakout and
 * a short.
 */
class ConduitPatchTest
{
	private ConduitPatch patch;

	@BeforeEach
	void setUp()
	{
		patch = new ConduitPatch();
	}

	@Nested
	@DisplayName("patching a face")
	class Patching
	{
		@Test
		@DisplayName("a new box has nothing patched")
		void startsEmpty()
		{
			assertTrue(patch.isEmpty());
			assertEquals(0, patch.count());
			for(EnumFacing face : EnumFacing.VALUES)
			{
				assertNull(patch.get(face));
				assertFalse(patch.isPatched(face));
			}
		}

		@Test
		@DisplayName("a patched face reports its channel")
		void patchThenRead()
		{
			assertTrue(patch.set(EnumFacing.NORTH, WireChannel.BLUE));
			assertSame(WireChannel.BLUE, patch.get(EnumFacing.NORTH));
			assertTrue(patch.isPatched(EnumFacing.NORTH));
			assertEquals(1, patch.count());
		}

		@Test
		@DisplayName("setting the same thing twice changes nothing")
		void idempotentSetReportsNoChange()
		{
			//The return value is what a caller uses to skip a block update, so a no-op has to say so.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			assertFalse(patch.set(EnumFacing.NORTH, WireChannel.BLUE));
		}

		@Test
		@DisplayName("clearing a face frees it")
		void clearFrees()
		{
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			assertTrue(patch.set(EnumFacing.NORTH, null));
			assertNull(patch.get(EnumFacing.NORTH));
			assertTrue(patch.isEmpty());
		}

		@Test
		@DisplayName("all six faces can be patched at once")
		void allSixFaces()
		{
			for(EnumFacing face : EnumFacing.VALUES)
				patch.set(face, WireChannel.VALUES[face.ordinal()]);
			assertEquals(6, patch.count());
		}

		@Test
		@DisplayName("a missing face is a no-op rather than a crash")
		void nullFaceIsSafe()
		{
			assertFalse(patch.set(null, WireChannel.BLUE));
			assertNull(patch.get(null));
			assertFalse(patch.isPatched(null));
		}
	}

	@Nested
	@DisplayName("one face per channel")
	class Uniqueness
	{
		@Test
		@DisplayName("finding where a channel comes out")
		void faceOfFindsIt()
		{
			patch.set(EnumFacing.EAST, WireChannel.RED);
			assertSame(EnumFacing.EAST, patch.faceOf(WireChannel.RED));
			assertNull(patch.faceOf(WireChannel.BLUE));
			assertNull(patch.faceOf(null));
		}

		@Test
		@DisplayName("patching a channel somewhere new takes it off its old face")
		void moveTakesItOffTheOldFace()
		{
			//The same conductor arriving at two connectors is a short, not a feature.
			patch.set(EnumFacing.EAST, WireChannel.RED);
			assertSame(EnumFacing.EAST, patch.moveTo(EnumFacing.WEST, WireChannel.RED));
			assertNull(patch.get(EnumFacing.EAST));
			assertSame(WireChannel.RED, patch.get(EnumFacing.WEST));
			assertEquals(1, patch.count(), "the channel ended up on two faces");
		}

		@Test
		@DisplayName("re-patching a channel to the face it is already on does nothing")
		void moveToTheSameFaceIsANoOp()
		{
			patch.set(EnumFacing.EAST, WireChannel.RED);
			assertNull(patch.moveTo(EnumFacing.EAST, WireChannel.RED));
			assertSame(WireChannel.RED, patch.get(EnumFacing.EAST));
			assertEquals(1, patch.count());
		}

		@Test
		@DisplayName("moving onto an occupied face replaces what was there")
		void moveOverwritesTheTarget()
		{
			//Right-clicking a face that already has a colour is the obvious way to change it, so it
			//has to mean "this face is blue now" rather than being refused.
			patch.set(EnumFacing.EAST, WireChannel.RED);
			patch.set(EnumFacing.WEST, WireChannel.BLUE);
			patch.moveTo(EnumFacing.EAST, WireChannel.BLUE);
			assertSame(WireChannel.BLUE, patch.get(EnumFacing.EAST));
			assertNull(patch.get(EnumFacing.WEST), "blue stayed on its old face too");
			assertEquals(1, patch.count(), "red should have been displaced, not duplicated");
		}

		@Test
		@DisplayName("no channel is ever on two faces, however it is patched")
		void neverTwoFacesForOneChannel()
		{
			//A sweep rather than a case: this is the invariant the whole class exists to hold.
			for(EnumFacing face : EnumFacing.VALUES)
				patch.moveTo(face, WireChannel.GREEN);
			assertEquals(1, patch.count());
			assertSame(EnumFacing.EAST, patch.faceOf(WireChannel.GREEN));
		}
	}

	@Nested
	@DisplayName("what a face does")
	class Modes
	{
		@Test
		@DisplayName("a face is a power breakout until told otherwise")
		void defaultsToPower()
		{
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(EnumFacing.NORTH));
			assertFalse(patch.hasRedstone());
		}

		@Test
		@DisplayName("an unpatched face reports power rather than nothing")
		void barefaceReportsPower()
		{
			//Nothing reads the mode of a bare face, and returning null would only give every caller
			//a branch to forget.
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(EnumFacing.NORTH));
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(null));
		}

		@Test
		@DisplayName("the modes cycle and come back round")
		void modesCycle()
		{
			//One right-click with dust moves a face on by one, so the cycle has to close.
			ConduitPatch.Mode mode = ConduitPatch.Mode.POWER;
			for(int i = 0; i < ConduitPatch.Mode.VALUES.length; i++)
				mode = mode.next();
			assertSame(ConduitPatch.Mode.POWER, mode);
		}

		@Test
		@DisplayName("a face is never both an input and an output")
		void modesAreExclusive()
		{
			//Reading and emitting on one face is how a redstone network latches itself on and never
			//lets go. The type system is what forbids it -- there is one mode, not two flags.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.setMode(EnumFacing.NORTH, ConduitPatch.Mode.REDSTONE_IN);
			assertSame(ConduitPatch.Mode.REDSTONE_IN, patch.modeOf(EnumFacing.NORTH));
			patch.setMode(EnumFacing.NORTH, ConduitPatch.Mode.REDSTONE_OUT);
			assertSame(ConduitPatch.Mode.REDSTONE_OUT, patch.modeOf(EnumFacing.NORTH));
		}

		@Test
		@DisplayName("hasRedstone answers for the whole box")
		void hasRedstoneScansEveryFace()
		{
			//This is what lets a box of ordinary power breakouts skip the signal walk entirely.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.set(EnumFacing.SOUTH, WireChannel.RED);
			assertFalse(patch.hasRedstone());
			patch.setMode(EnumFacing.SOUTH, ConduitPatch.Mode.REDSTONE_OUT);
			assertTrue(patch.hasRedstone());
		}

		@Test
		@DisplayName("clearing a face forgets what it was doing")
		void clearingForgetsTheMode()
		{
			//Otherwise re-patching that face later silently inherits a mode from a circuit somebody
			//removed weeks ago.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.setMode(EnumFacing.NORTH, ConduitPatch.Mode.REDSTONE_OUT);
			patch.set(EnumFacing.NORTH, null);
			patch.set(EnumFacing.NORTH, WireChannel.GREEN);
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(EnumFacing.NORTH));
		}

		@Test
		@DisplayName("moving a channel takes its mode with it")
		void modeTravelsWithTheChannel()
		{
			//Somebody who set a face to emit and then moved that colour elsewhere meant to move the
			//arrangement, not to reset it.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.setMode(EnumFacing.NORTH, ConduitPatch.Mode.REDSTONE_OUT);
			patch.moveTo(EnumFacing.EAST, WireChannel.BLUE);
			assertSame(ConduitPatch.Mode.REDSTONE_OUT, patch.modeOf(EnumFacing.EAST));
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(EnumFacing.NORTH),
					"the face it left kept the mode as well");
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("a patch table round-trips")
		void roundTrip()
		{
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.set(EnumFacing.UP, WireChannel.YELLOW);

			ConduitPatch loaded = new ConduitPatch();
			loaded.readFromNBT(patch.writeToNBT());
			assertSame(WireChannel.BLUE, loaded.get(EnumFacing.NORTH));
			assertSame(WireChannel.YELLOW, loaded.get(EnumFacing.UP));
			assertEquals(2, loaded.count());
		}

		@Test
		@DisplayName("reading replaces whatever was there")
		void readingClearsFirst()
		{
			//Otherwise a box reloaded after being rewired keeps both patchings at once.
			patch.set(EnumFacing.SOUTH, WireChannel.PINK);
			patch.readFromNBT(new NBTTagCompound());
			assertTrue(patch.isEmpty());
		}

		@Test
		@DisplayName("an absent tag leaves an empty table")
		void absentTagIsEmpty()
		{
			patch.readFromNBT(null);
			assertTrue(patch.isEmpty());
		}

		@Test
		@DisplayName("an unrecognised colour clears its face rather than guessing one")
		void unknownColourIsDropped()
		{
			//A channel renamed by a future version should cost a visible blank, not a silent
			//rewire onto whichever conductor happened to be first in the list.
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString(EnumFacing.NORTH.getName(), "chartreuse");
			tag.setString(EnumFacing.SOUTH.getName(), WireChannel.LIME.getName());
			patch.readFromNBT(tag);
			assertNull(patch.get(EnumFacing.NORTH));
			assertSame(WireChannel.LIME, patch.get(EnumFacing.SOUTH));
		}

		@Test
		@DisplayName("modes round-trip")
		void modesRoundTrip()
		{
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			patch.setMode(EnumFacing.NORTH, ConduitPatch.Mode.REDSTONE_IN);
			patch.set(EnumFacing.UP, WireChannel.RED);
			patch.setMode(EnumFacing.UP, ConduitPatch.Mode.REDSTONE_OUT);

			ConduitPatch loaded = new ConduitPatch();
			loaded.readFromNBT(patch.writeToNBT());
			assertSame(ConduitPatch.Mode.REDSTONE_IN, loaded.modeOf(EnumFacing.NORTH));
			assertSame(ConduitPatch.Mode.REDSTONE_OUT, loaded.modeOf(EnumFacing.UP));
		}

		@Test
		@DisplayName("a box of power breakouts saves what it always did")
		void powerOnlyBoxWritesNoModeKeys()
		{
			//Written only when it is not the default, so a save made before redstone channels
			//existed and one made after are the same bytes for the same box.
			patch.set(EnumFacing.NORTH, WireChannel.BLUE);
			assertFalse(patch.writeToNBT().hasKey("north_mode"));
		}

		@Test
		@DisplayName("an unreadable mode falls back to power rather than guessing redstone")
		void unknownModeIsInert()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString(EnumFacing.NORTH.getName(), WireChannel.BLUE.getName());
			tag.setString("north_mode", "TELEPATHY");
			patch.readFromNBT(tag);
			assertSame(ConduitPatch.Mode.POWER, patch.modeOf(EnumFacing.NORTH));
		}

		@Test
		@DisplayName("a full table round-trips")
		void fullTableRoundTrips()
		{
			for(EnumFacing face : EnumFacing.VALUES)
				patch.set(face, WireChannel.VALUES[face.ordinal()*2]);
			ConduitPatch loaded = new ConduitPatch();
			loaded.readFromNBT(patch.writeToNBT());
			for(EnumFacing face : EnumFacing.VALUES)
				assertSame(patch.get(face), loaded.get(face));
		}
	}
}
