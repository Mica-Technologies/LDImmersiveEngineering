/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry.ArmMode;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each of a conduit's arms is doing, and the save format that says so.
 * <p>
 * The half worth having is {@link OldWorlds}. Every conduit already placed in the user's world was
 * saved when a run could only lie flat, and those tags have to come back as the runs they were. A
 * mistake here would not crash anything -- it would quietly redraw somebody's wiring, which is the
 * kind of thing that gets noticed a week later and is impossible to attribute.
 */
class ConduitArmsTest
{
	private static ConduitArms armsWith(ArmMode... modes)
	{
		ConduitArms arms = new ConduitArms();
		for(int i = 0; i < modes.length; i++)
			arms.set(i, modes[i]);
		return arms;
	}

	private static ConduitArms reloaded(ConduitArms arms)
	{
		NBTTagCompound tag = new NBTTagCompound();
		arms.writeToNBT(tag);
		ConduitArms out = new ConduitArms();
		out.readFromNBT(tag);
		return out;
	}

	@Nested
	@DisplayName("the four modes")
	class Modes
	{
		@Test
		@DisplayName("an arm nobody set is not there")
		void bareIsBare()
		{
			ConduitArms arms = new ConduitArms();
			for(int i = 0; i < ConduitGeometry.ARMS; i++)
			{
				assertEquals(ArmMode.NONE, arms.mode(i));
				assertFalse(arms.isConnected(i));
			}
			assertEquals(0, arms.getConnections());
		}

		@Test
		@DisplayName("every mode survives being set and read back")
		void modesRoundTrip()
		{
			for(ArmMode mode : ArmMode.values())
				for(int arm = 0; arm < ConduitGeometry.ARMS; arm++)
				{
					ConduitArms arms = new ConduitArms();
					arms.set(arm, mode);
					assertEquals(mode, arms.mode(arm), "arm "+arm+" forgot it was "+mode);
					assertEquals(mode!=ArmMode.NONE, arms.isConnected(arm));
				}
		}

		@Test
		@DisplayName("all three joined modes count as joined")
		void everyJoinIsAConnection()
		{
			//The connection mask is what the hitbox and the shape readout are built from, and a
			//corner is as joined as a straight length. A riser that did not count would give a run
			//whose last cell drew as bare at the very corner it was turning.
			ConduitArms arms = armsWith(ArmMode.STRAIGHT, ArmMode.RISER, ArmMode.WRAP, ArmMode.NONE);
			assertEquals(0b0111, arms.getConnections());
			assertEquals(0b0010, arms.getRisers());
		}

		@Test
		@DisplayName("setting an arm again replaces what was there")
		void settingClearsTheOldMode()
		{
			//The masks are rebuilt from the world on every neighbour change, so a mode that stuck
			//would be a riser drawn into a wall somebody had just mined away.
			ConduitArms arms = new ConduitArms();
			arms.set(0, ArmMode.RISER);
			arms.set(0, ArmMode.STRAIGHT);
			assertEquals(ArmMode.STRAIGHT, arms.mode(0));
			arms.set(0, ArmMode.WRAP);
			assertEquals(ArmMode.WRAP, arms.mode(0));
			arms.set(0, ArmMode.NONE);
			assertEquals(ArmMode.NONE, arms.mode(0));
			assertEquals(0, arms.getConnections());
		}

		@Test
		@DisplayName("a direction outside the plane is ignored rather than corrupting a mask")
		void outOfPlaneIsIgnored()
		{
			//ConduitGeometry.armIndex answers -1 for a direction along the mounting axis, and the
			//tile entity hands that straight through.
			ConduitArms arms = new ConduitArms();
			arms.set(-1, ArmMode.RISER);
			arms.set(ConduitGeometry.ARMS, ArmMode.RISER);
			assertEquals(0, arms.getConnections());
			assertEquals(ArmMode.NONE, arms.mode(-1));
		}
	}

	@Nested
	@DisplayName("noticing a change")
	class Changes
	{
		@Test
		@DisplayName("taking on identical arms changes nothing")
		void identicalIsNoChange()
		{
			//A conduit recomputes its arms on every neighbour change, and most neighbour changes are
			//somebody walking past with a torch. Saying "nothing changed" is what stops each of them
			//costing a block update and a packet.
			ConduitArms arms = armsWith(ArmMode.STRAIGHT, ArmMode.RISER);
			assertFalse(arms.copyFrom(armsWith(ArmMode.STRAIGHT, ArmMode.RISER)));
		}

		@Test
		@DisplayName("a change of mode alone counts as a change")
		void modeAloneIsAChange()
		{
			//The trap in splitting the state into three masks: a straight arm becoming a riser leaves
			//the connection mask identical, and comparing only that would leave the corner undrawn
			//until something else happened to poke the block.
			ConduitArms arms = armsWith(ArmMode.STRAIGHT);
			assertTrue(arms.copyFrom(armsWith(ArmMode.RISER)));
			assertEquals(ArmMode.RISER, arms.mode(0));

			ConduitArms wrapping = armsWith(ArmMode.STRAIGHT);
			assertTrue(wrapping.copyFrom(armsWith(ArmMode.WRAP)));
			assertEquals(ArmMode.WRAP, wrapping.mode(0));
		}
	}

	@Nested
	@DisplayName("the save format")
	class Saving
	{
		@Test
		@DisplayName("every combination survives a save and a load")
		void everythingRoundTrips()
		{
			//All 256 arrangements of four arms in four modes, because the format is three masks and
			//a bit shifted into the wrong one would only show up on a shape nobody happened to build.
			ArmMode[] values = ArmMode.values();
			for(int a = 0; a < values.length; a++)
				for(int b = 0; b < values.length; b++)
					for(int c = 0; c < values.length; c++)
						for(int d = 0; d < values.length; d++)
						{
							ConduitArms arms = armsWith(values[a], values[b], values[c], values[d]);
							ConduitArms back = reloaded(arms);
							for(int arm = 0; arm < ConduitGeometry.ARMS; arm++)
								assertEquals(arms.mode(arm), back.mode(arm),
										"arm "+arm+" came back wrong");
						}
		}

		@Test
		@DisplayName("a flat run writes exactly what it always wrote")
		void flatRunsWriteNothingNew()
		{
			//Most conduit in most bases is flat, and its tag should be byte-identical to the one it
			//had before corners existed -- no new keys, no bigger chunks, no bigger packets.
			NBTTagCompound tag = new NBTTagCompound();
			armsWith(ArmMode.STRAIGHT, ArmMode.STRAIGHT).writeToNBT(tag);
			assertEquals(1, tag.getKeySet().size(), "a flat run started writing "+tag.getKeySet());
			assertTrue(tag.hasKey("connections"));
		}
	}

	@Nested
	@DisplayName("worlds saved before runs turned corners")
	class OldWorlds
	{
		@Test
		@DisplayName("a tag with only the old key loads as the flat run it was")
		void oldTagsAreFlat()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//Every conduit already in the user's world was saved like this. The two new masks are
			//absent, read as zero, and zero spells "straight" -- which is what those runs were,
			//because it was the only thing a run could be.
			NBTTagCompound old = new NBTTagCompound();
			old.setInteger("connections", 0b1011);

			ConduitArms arms = new ConduitArms();
			arms.readFromNBT(old);
			assertEquals(0b1011, arms.getConnections());
			assertEquals(ArmMode.STRAIGHT, arms.mode(0));
			assertEquals(ArmMode.STRAIGHT, arms.mode(1));
			assertEquals(ArmMode.NONE, arms.mode(2));
			assertEquals(ArmMode.STRAIGHT, arms.mode(3));
		}

		@Test
		@DisplayName("a tag with nothing in it loads as a bare length")
		void emptyTagIsBare()
		{
			ConduitArms arms = armsWith(ArmMode.RISER, ArmMode.WRAP);
			arms.readFromNBT(new NBTTagCompound());
			assertEquals(0, arms.getConnections(), "a stale mask survived a load");
			arms.readFromNBT(null);
			assertEquals(0, arms.getConnections());
		}

		@Test
		@DisplayName("a tag claiming a corner on an arm that is not there is not believed")
		void nonsenseIsClamped()
		{
			//A tag can say anything -- an edited save, a mod that rewrote it, a partial write. A
			//riser on an arm that does not exist is a model drawn into empty space.
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("connections", 0b0001);
			tag.setInteger("risers", 0b1111);
			tag.setInteger("wraps", 0b1111);

			ConduitArms arms = new ConduitArms();
			arms.readFromNBT(tag);
			assertEquals(0b0001, arms.getConnections());
			assertEquals(ArmMode.RISER, arms.mode(0));
			for(int arm = 1; arm < ConduitGeometry.ARMS; arm++)
				assertEquals(ArmMode.NONE, arms.mode(arm));
		}

		@Test
		@DisplayName("bits above the four arms are dropped")
		void extraBitsAreDropped()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("connections", 0xFFFF);
			ConduitArms arms = new ConduitArms();
			arms.readFromNBT(tag);
			assertEquals(0xF, arms.getConnections());
		}
	}
}
