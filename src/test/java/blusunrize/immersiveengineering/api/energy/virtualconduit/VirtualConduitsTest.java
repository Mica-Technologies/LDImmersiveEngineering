package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The table of conduit breakouts that keep delivering while their box is unloaded.
 * <p>
 * The thing worth pinning down here is that a record is per <em>conductor</em>, not per box and not
 * per run: sixteen circuits sharing one bundle must not merge the moment nobody is standing near
 * them, and that property lives entirely in this key.
 */
@DisplayName("VirtualConduits")
class VirtualConduitsTest
{
	private VirtualConduits reg;
	private final BlockPos box = new BlockPos(100, 64, -200);
	private final BlockPos end = new BlockPos(140, 70, -200);

	@BeforeEach
	void setUp()
	{
		reg = new VirtualConduits();
	}

	@Nested
	@DisplayName("the table")
	class Table
	{
		@Test
		@DisplayName("observing a conductor twice updates one record rather than making two")
		void observeReplaces()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.RED, 128, "COPPER", end);
			assertEquals(1, reg.size());
			assertEquals(128, reg.get(0, box, WireChannel.RED).getRate());
		}

		@Test
		@DisplayName("two conductors on one box are two records")
		void conductorsAreSeparate()
		{
			//The whole point of sixteen conductors, and the property that would quietly disappear if
			//the key were just the box.
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.GREEN, 32, "COPPER", end);
			assertEquals(2, reg.size());
			assertEquals(64, reg.get(0, box, WireChannel.RED).getRate());
			assertEquals(32, reg.get(0, box, WireChannel.GREEN).getRate());
		}

		@Test
		@DisplayName("the same box in two dimensions is two records")
		void dimensionsAreSeparate()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(-1, box, WireChannel.RED, 64, "COPPER", end);
			assertEquals(2, reg.size());
		}

		@Test
		@DisplayName("a conductor that went dark is forgotten")
		void removeOne()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.GREEN, 64, "COPPER", end);
			assertTrue(reg.remove(0, box, WireChannel.RED));
			assertFalse(reg.remove(0, box, WireChannel.RED), "removing twice is not a change");
			assertEquals(1, reg.size());
			assertNull(reg.get(0, box, WireChannel.RED));
			assertNotNull(reg.get(0, box, WireChannel.GREEN), "its neighbour is untouched");
		}

		@Test
		@DisplayName("a box that is gone takes all sixteen with it")
		void removeWholeBox()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.BLUE, 64, "COPPER", end);
			reg.observe(0, new BlockPos(0, 0, 0), WireChannel.RED, 64, "COPPER", end);
			assertEquals(2, reg.removeBox(0, box));
			assertEquals(1, reg.size(), "the other box is untouched");
		}

		@Test
		@DisplayName("a mutable position cannot change under the key")
		void mutablePositionIsCopied()
		{
			BlockPos.MutableBlockPos moving = new BlockPos.MutableBlockPos(1, 2, 3);
			reg.observe(0, moving, WireChannel.RED, 64, "COPPER", end);
			moving.setPos(9, 9, 9);
			assertNotNull(reg.get(0, new BlockPos(1, 2, 3), WireChannel.RED),
					"the record must still be filed where it was observed");
		}
	}

	@Nested
	@DisplayName("what a link is good for")
	class Usable
	{
		@Test
		@DisplayName("a record with a rate and a wire can be pushed")
		void completeIsUsable()
		{
			assertTrue(reg.observe(0, box, WireChannel.RED, 64, "COPPER", end).isUsable());
		}

		@Test
		@DisplayName("a conductor delivering nothing is not")
		void zeroRateIsNotUsable()
		{
			assertFalse(reg.observe(0, box, WireChannel.RED, 0, "COPPER", end).isUsable());
		}

		@Test
		@DisplayName("a breakout with no wire on it is not")
		void noWireIsNotUsable()
		{
			//A face feeding something bolted against the box has nothing for an unloaded box to hand
			//a neighbour that is also unloaded.
			assertFalse(reg.observe(0, box, WireChannel.RED, 64, null, null).isUsable());
			assertFalse(reg.observe(0, box, WireChannel.BLUE, 64, "", end).isUsable());
		}
	}

	@Nested
	@DisplayName("saving and loading")
	class Persistence
	{
		@Test
		@DisplayName("a table survives a round trip")
		void roundTrip()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.GREEN, 128, "ELECTRUM", new BlockPos(-5, 6, -7));
			reg.observe(-1, new BlockPos(1, 2, 3), WireChannel.WHITE, 8, "STEEL", end);

			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));

			assertEquals(reg.size(), back.size());
			VirtualConduitLink green = back.get(0, box, WireChannel.GREEN);
			assertNotNull(green);
			assertEquals(128, green.getRate());
			assertEquals("ELECTRUM", green.getWireTypeName());
			assertEquals(new BlockPos(-5, 6, -7), green.getWireEnd());
			assertNotNull(back.get(-1, new BlockPos(1, 2, 3), WireChannel.WHITE));
		}

		@Test
		@DisplayName("negative coordinates survive, which is most of a map")
		void negativeCoordinates()
		{
			BlockPos far = new BlockPos(-2048, 250, -6045);
			reg.observe(0, far, WireChannel.BLUE, 40, "COPPER", new BlockPos(-2050, 250, -6045));
			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));
			VirtualConduitLink link = back.get(0, far, WireChannel.BLUE);
			assertNotNull(link, "a long packs to a negative number and must come back the same");
			assertEquals(new BlockPos(-2050, 250, -6045), link.getWireEnd());
		}

		@Test
		@DisplayName("a record with no wire round-trips as one")
		void partialRecordRoundTrips()
		{
			reg.observe(0, box, WireChannel.RED, 64, null, null);
			VirtualConduits back = new VirtualConduits();
			back.readFromNBT(reg.writeToNBT(new NBTTagCompound()));
			VirtualConduitLink link = back.get(0, box, WireChannel.RED);
			assertNotNull(link);
			assertNull(link.getWireTypeName());
			assertNull(link.getWireEnd());
			assertFalse(link.isUsable());
		}

		@Test
		@DisplayName("a conductor this build does not have is dropped rather than thrown over")
		void unknownChannelIsDropped()
		{
			//A save written by a version carrying more conductors must load, minus the ones that
			//mean nothing here.
			NBTTagCompound tag = reg.writeToNBT(new NBTTagCompound());
			NBTTagCompound stray = new NBTTagCompound();
			stray.setInteger("dim", 0);
			stray.setLong("box", box.toLong());
			stray.setString("channel", "chartreuse");
			stray.setInteger("rate", 64);
			tag.getTagList("links", 10).appendTag(stray);

			VirtualConduits back = new VirtualConduits();
			assertDoesNotThrow(() -> back.readFromNBT(tag));
			assertEquals(0, back.size());
		}

		@Test
		@DisplayName("reading replaces what was there rather than adding to it")
		void readingClearsFirst()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			NBTTagCompound saved = reg.writeToNBT(new NBTTagCompound());
			reg.observe(0, box, WireChannel.BLUE, 64, "COPPER", end);
			reg.readFromNBT(saved);
			assertEquals(1, reg.size(), "a second world in one session starts clean");
			assertNull(reg.get(0, box, WireChannel.BLUE));
		}
	}

	@Nested
	@DisplayName("telling the save it is dirty")
	class Dirty
	{
		private int calls;

		@BeforeEach
		void listen()
		{
			calls = 0;
			reg.setDirtyListener(() -> calls++);
		}

		@Test
		@DisplayName("a new record and a changed one both dirty the save")
		void changesAreDirty()
		{
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			assertEquals(1, calls);
			reg.observe(0, box, WireChannel.RED, 128, "COPPER", end);
			assertEquals(2, calls);
			reg.remove(0, box, WireChannel.RED);
			assertEquals(3, calls);
		}

		@Test
		@DisplayName("observing the same figures again does not")
		void unchangedIsNotDirty()
		{
			//This runs every tick a conductor is delivering, so a save marked dirty on every one of
			//them would write the world constantly.
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			calls = 0;
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			reg.observe(0, box, WireChannel.RED, 64, "COPPER", end);
			assertEquals(0, calls);
		}
	}
}
