/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which wire is on which face of a junction box.
 * <p>
 * Small, and it is the whole of what makes six wires on one box six circuits rather than six ways
 * into the same one: energy arriving over a wire has to land on that wire's face's conductor, and
 * this table is the only thing that knows which face that is.
 */
class JunctionWiresTest
{
	private static final BlockPos FAR = new BlockPos(10, 64, -3);
	private static final BlockPos OTHER = new BlockPos(-40, 12, 900);
	private static final String LV = "COPPER";
	private static final String HV = "STEEL";

	private JunctionWires wires;

	@BeforeEach
	void setUp()
	{
		wires = new JunctionWires();
	}

	@Nested
	@DisplayName("recording a wire")
	class Recording
	{
		@Test
		@DisplayName("a fresh table has nothing on it")
		void freshTableIsEmpty()
		{
			assertTrue(wires.isEmpty());
			assertEquals(0, wires.count());
			assertEquals(0, wires.mask());
			for(EnumFacing face : EnumFacing.VALUES)
				assertFalse(wires.has(face));
		}

		@Test
		@DisplayName("a face remembers the wire's far end and its kind")
		void faceRemembersBoth()
		{
			assertTrue(wires.set(EnumFacing.NORTH, FAR, LV));
			assertTrue(wires.has(EnumFacing.NORTH));
			assertEquals(FAR, wires.endOf(EnumFacing.NORTH));
			assertEquals(LV, wires.typeOf(EnumFacing.NORTH));
			assertEquals(1, wires.count());
		}

		@Test
		@DisplayName("six faces hold six different wires at once")
		void sixIndependentWires()
		{
			//The point of the whole feature: one box, six circuits, and nothing shared between them
			//but the bundle they come off.
			for(EnumFacing face : EnumFacing.VALUES)
				wires.set(face, FAR.offset(face), face.getAxis()==EnumFacing.Axis.Y?HV: LV);
			assertEquals(6, wires.count());
			assertEquals(0b111111, wires.mask());
			for(EnumFacing face : EnumFacing.VALUES)
				assertEquals(FAR.offset(face), wires.endOf(face));
		}

		@Test
		@DisplayName("re-recording the same wire changes nothing")
		void idempotent()
		{
			assertTrue(wires.set(EnumFacing.UP, FAR, HV));
			assertFalse(wires.set(EnumFacing.UP, FAR, HV));
		}

		@Test
		@DisplayName("a null face or a null wire is ignored rather than throwing")
		void nullsIgnored()
		{
			assertFalse(wires.set(null, FAR, LV));
			assertFalse(wires.set(EnumFacing.UP, null, LV));
			assertFalse(wires.set(EnumFacing.UP, FAR, null));
			assertTrue(wires.isEmpty());
			assertNull(wires.endOf(null));
			assertNull(wires.typeOf(null));
			assertFalse(wires.has(null));
		}
	}

	@Nested
	@DisplayName("finding the face a wire is on")
	class Lookup
	{
		@Test
		@DisplayName("it answers with the face the wire was recorded on")
		void findsTheFace()
		{
			wires.set(EnumFacing.EAST, FAR, LV);
			assertSame(EnumFacing.EAST, wires.faceOf(FAR, LV));
		}

		@Test
		@DisplayName("the kind has to match as well as the far end")
		void kindMattersToo()
		{
			//Two nodes can be joined by more than one connection -- a bundle and a strung wire
			//between the same pair of boxes is the obvious case -- and answering with the wrong one
			//would credit the wrong conductor.
			wires.set(EnumFacing.EAST, FAR, LV);
			assertNull(wires.faceOf(FAR, HV));
			assertNull(wires.faceOf(OTHER, LV));
		}

		@Test
		@DisplayName("nothing there is null, not a guess")
		void noMatchIsNull()
		{
			assertNull(wires.faceOf(FAR, LV));
			assertNull(wires.faceOf(null, LV));
			assertNull(wires.faceOf(FAR, null));
		}
	}

	@Nested
	@DisplayName("taking a wire off")
	class Clearing
	{
		@Test
		@DisplayName("clearing frees that face and leaves the others alone")
		void clearsOneFace()
		{
			wires.set(EnumFacing.NORTH, FAR, LV);
			wires.set(EnumFacing.SOUTH, OTHER, HV);
			assertTrue(wires.clear(EnumFacing.NORTH));
			assertFalse(wires.has(EnumFacing.NORTH));
			assertTrue(wires.has(EnumFacing.SOUTH));
			assertEquals(1, wires.count());
		}

		@Test
		@DisplayName("clearing a bare face reports that nothing happened")
		void clearingNothing()
		{
			assertFalse(wires.clear(EnumFacing.WEST));
			assertFalse(wires.clear(null));
		}

		@Test
		@DisplayName("clearing everything empties the table")
		void clearAll()
		{
			wires.set(EnumFacing.NORTH, FAR, LV);
			wires.set(EnumFacing.DOWN, OTHER, HV);
			wires.clearAll();
			assertTrue(wires.isEmpty());
		}
	}

	@Nested
	@DisplayName("saving and loading")
	class Persistence
	{
		@Test
		@DisplayName("a table survives a round trip through NBT")
		void roundTrip()
		{
			wires.set(EnumFacing.NORTH, FAR, LV);
			wires.set(EnumFacing.UP, OTHER, HV);

			JunctionWires loaded = new JunctionWires();
			loaded.readFromNBT(wires.writeToNBT());
			assertEquals(FAR, loaded.endOf(EnumFacing.NORTH));
			assertEquals(LV, loaded.typeOf(EnumFacing.NORTH));
			assertEquals(OTHER, loaded.endOf(EnumFacing.UP));
			assertEquals(HV, loaded.typeOf(EnumFacing.UP));
			assertEquals(2, loaded.count());
			assertEquals(wires.mask(), loaded.mask());
		}

		@Test
		@DisplayName("reading replaces whatever was there")
		void readingClearsFirst()
		{
			//Otherwise a box reloaded after being rewired keeps both wirings at once, and the older
			//of the two points at a wire that is not in the graph any more.
			wires.set(EnumFacing.SOUTH, FAR, LV);
			wires.readFromNBT(new NBTTagCompound());
			assertTrue(wires.isEmpty());
		}

		@Test
		@DisplayName("an absent tag leaves an empty table")
		void absentTag()
		{
			wires.readFromNBT(null);
			assertTrue(wires.isEmpty());
		}

		@Test
		@DisplayName("a malformed entry leaves the face blank rather than pointing nowhere")
		void malformedEntry()
		{
			//The graph is the authority and onLoad reconciles against it, so a blank face is
			//re-adopted. A half-read one would be a face claiming a wire that is not there.
			NBTTagCompound tag = new NBTTagCompound();
			NBTTagCompound broken = new NBTTagCompound();
			broken.setIntArray("end", new int[]{1, 2});
			broken.setString("type", LV);
			tag.setTag(EnumFacing.NORTH.getName(), broken);
			NBTTagCompound noType = new NBTTagCompound();
			noType.setIntArray("end", new int[]{1, 2, 3});
			tag.setTag(EnumFacing.SOUTH.getName(), noType);

			wires.readFromNBT(tag);
			assertFalse(wires.has(EnumFacing.NORTH));
			assertFalse(wires.has(EnumFacing.SOUTH));
		}
	}
}
