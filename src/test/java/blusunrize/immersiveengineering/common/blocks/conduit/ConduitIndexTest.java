/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitRoute.Node;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The saved index of conduit hardware: what it holds, what it will admit to guessing, and what a walk
 * makes of it.
 * <p>
 * The whole reason this exists is a two-hundred block run between two towns that was never in the wire
 * graph, because a walk cannot see an unloaded chunk and rebuilds only happen where somebody is
 * standing. So the cases worth over-testing are the two ways that can still go wrong: a run that does
 * not link when it should, and -- much worse -- a walk that leans on a remembered answer and then
 * deletes a working run on the strength of it.
 * <p>
 * The world is a hand-drawn map with chunks marked unloaded, behaving exactly as
 * {@link ConduitWorldProbe} does over a real one: an unloaded position answers "nothing there", and
 * says that it could not be seen.
 */
class ConduitIndexTest
{
	private static final int DIM = 0;
	private static final int NETHER = -1;

	/**
	 * A building drawn a block at a time, with chunks that can be taken away.
	 * <p>
	 * Unloading is per chunk rather than per block because that is what a server does, and because the
	 * index's "seen" test is a question about a chunk: see {@code ConduitIndex.Remembering.isLoaded}.
	 */
	private static final class Built implements ConduitRoute.Probe
	{
		private final Map<BlockPos, Node> nodes = new HashMap<>();
		private final Map<BlockPos, EnumFacing> mounts = new HashMap<>();
		private final Map<BlockPos, EnumFacing.Axis> axes = new HashMap<>();
		private final Set<BlockPos> solid = new HashSet<>();
		private final Set<Long> unloaded = new HashSet<>();

		Built conduit(int x, int y, int z, EnumFacing mount)
		{
			BlockPos pos = new BlockPos(x, y, z);
			nodes.put(pos, Node.CONDUIT);
			mounts.put(pos, mount);
			return this;
		}

		Built junction(int x, int y, int z)
		{
			nodes.put(new BlockPos(x, y, z), Node.JUNCTION);
			return this;
		}

		Built wall(int x, int y, int z)
		{
			solid.add(new BlockPos(x, y, z));
			return this;
		}

		/** A strip of floor, wide enough for a run to lie on and long enough to cross three chunks. */
		Built floor(int y, int fromX, int toX)
		{
			for(int x = fromX; x <= toX; x++)
				for(int z = -2; z <= 2; z++)
					solid.add(new BlockPos(x, y, z));
			return this;
		}

		Built unloadChunk(int chunkX, int chunkZ)
		{
			unloaded.add(key(chunkX, chunkZ));
			return this;
		}

		private static long key(int chunkX, int chunkZ)
		{
			return (long)chunkX<<32|(chunkZ&0xffffffffL);
		}

		@Override
		public boolean isLoaded(BlockPos pos)
		{
			return !unloaded.contains(key(pos.getX() >> 4, pos.getZ() >> 4));
		}

		@Override
		public Node nodeAt(BlockPos pos)
		{
			if(!isLoaded(pos))
				return Node.NOTHING;
			Node node = nodes.get(pos);
			return node==null?Node.NOTHING: node;
		}

		@Override
		public EnumFacing mountAt(BlockPos pos)
		{
			return isLoaded(pos)?mounts.get(pos): null;
		}

		@Override
		public EnumFacing.Axis axisAt(BlockPos pos)
		{
			return isLoaded(pos)?axes.get(pos): null;
		}

		@Override
		public boolean isMountable(BlockPos pos, EnumFacing face)
		{
			return isLoaded(pos)&&solid.contains(pos);
		}
	}

	private ConduitIndex index;

	@BeforeEach
	void setUp()
	{
		index = new ConduitIndex();
	}

	private static BlockPos at(int x, int y, int z)
	{
		return new BlockPos(x, y, z);
	}

	// ------------------------------------------------------------------
	// The table itself
	// ------------------------------------------------------------------

	@Nested
	@DisplayName("writing hardware down")
	class Store
	{
		@Test
		@DisplayName("what went in comes back out")
		void rememberAndRead()
		{
			assertTrue(index.rememberConduit(DIM, at(1, 2, 3), EnumFacing.NORTH));
			assertTrue(index.rememberJunction(DIM, at(4, 5, 6)));
			assertTrue(index.rememberFeeder(DIM, at(7, 8, 9), EnumFacing.Axis.Z));

			assertEquals(Node.CONDUIT, index.nodeAt(DIM, at(1, 2, 3)));
			assertEquals(EnumFacing.NORTH, index.get(DIM, at(1, 2, 3)).getMount());
			assertEquals(Node.JUNCTION, index.nodeAt(DIM, at(4, 5, 6)));
			assertEquals(Node.PASS_THROUGH, index.nodeAt(DIM, at(7, 8, 9)));
			assertEquals(EnumFacing.Axis.Z, index.get(DIM, at(7, 8, 9)).getAxis());
			assertEquals(3, index.size());
		}

		@Test
		@DisplayName("a position nobody wrote down reads as nothing rather than throwing")
		void unknownIsNothing()
		{
			assertEquals(Node.NOTHING, index.nodeAt(DIM, at(0, 0, 0)));
			assertNull(index.get(DIM, at(0, 0, 0)));
			assertFalse(index.knows(DIM, at(0, 0, 0)));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("rewriting the same thing is not news")
		void unchangedIsNotDirty()
		{
			//A chunk reloading rewrites every entry in it, unchanged, which is a save write for nothing
			//sixty times a second on a busy server if this answers yes.
			int[] dirtied = {0};
			index.setDirtyListener(() -> dirtied[0]++);
			assertTrue(index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN));
			assertFalse(index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN));
			assertEquals(1, dirtied[0]);
			//But a conduit remounted on another wall is.
			assertTrue(index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.EAST));
			assertEquals(2, dirtied[0]);
			assertEquals(EnumFacing.EAST, index.get(DIM, at(1, 0, 0)).getMount());
		}

		@Test
		@DisplayName("forgetting the last entry in a chunk takes the chunk with it")
		void emptyChunksAreDropped()
		{
			index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(2, 0, 0), EnumFacing.DOWN);
			assertEquals(1, index.chunkCount(DIM));
			assertTrue(index.forget(DIM, at(1, 0, 0)));
			assertEquals(1, index.chunkCount(DIM), "one entry left, so the chunk stays");
			assertTrue(index.forget(DIM, at(2, 0, 0)));
			assertEquals(0, index.chunkCount(DIM), "a player mining a corridor must not leave a map "
					+"per chunk behind");
			assertTrue(index.isEmpty());
			assertFalse(index.forget(DIM, at(1, 0, 0)), "forgetting what is not there is not a change");
		}

		@Test
		@DisplayName("dimensions are kept apart")
		void dimensionsDoNotMix()
		{
			index.rememberJunction(DIM, at(0, 0, 0));
			index.rememberJunction(NETHER, at(0, 0, 0));
			assertEquals(Node.JUNCTION, index.nodeAt(DIM, at(0, 0, 0)));
			assertEquals(Node.JUNCTION, index.nodeAt(NETHER, at(0, 0, 0)));
			index.forget(DIM, at(0, 0, 0));
			assertEquals(Node.NOTHING, index.nodeAt(DIM, at(0, 0, 0)));
			assertEquals(Node.JUNCTION, index.nodeAt(NETHER, at(0, 0, 0)),
					"the other dimension's box went with it");
			assertEquals(1, index.dimensions().size());
		}

		@Test
		@DisplayName("one chunk's entries can be had without walking the table")
		void perChunkLookup()
		{
			index.rememberConduit(DIM, at(1, 0, 1), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(15, 0, 15), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(16, 0, 0), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(-1, 0, 0), EnumFacing.DOWN);
			assertEquals(2, index.inChunk(DIM, 0, 0).size());
			assertEquals(1, index.inChunk(DIM, 1, 0).size());
			assertEquals(1, index.inChunk(DIM, -1, 0).size(), "negative coordinates floor, they do not "
					+"truncate: x=-1 is in chunk -1, not chunk 0");
			assertTrue(index.inChunk(DIM, 7, 7).isEmpty());
		}

		@Test
		@DisplayName("a chunk with anything in it counts as a chunk that has been seen")
		void knowsChunk()
		{
			index.rememberConduit(DIM, at(20, 0, 0), EnumFacing.DOWN);
			assertTrue(index.knowsChunk(DIM, at(31, 70, 15)), "same chunk, different block");
			assertFalse(index.knowsChunk(DIM, at(32, 70, 0)), "next chunk along");
			assertFalse(index.knowsChunk(NETHER, at(20, 0, 0)));
		}
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	@Nested
	@DisplayName("saving and loading")
	class Persistence
	{
		@Test
		@DisplayName("a populated index survives a round trip")
		void roundTrip()
		{
			index.rememberConduit(DIM, at(1, 2, 3), EnumFacing.NORTH);
			index.rememberConduit(DIM, at(-40, 64, -900), EnumFacing.UP);
			index.rememberJunction(DIM, at(4, 5, 6));
			index.rememberFeeder(DIM, at(7, 8, 9), EnumFacing.Axis.X);
			index.rememberJunction(NETHER, at(10, 11, 12));

			ConduitIndex back = new ConduitIndex();
			back.readFromNBT(index.writeToNBT(new NBTTagCompound()));

			assertEquals(index.size(), back.size());
			assertEquals(index.dimensions(), back.dimensions());
			assertEquals(EnumFacing.NORTH, back.get(DIM, at(1, 2, 3)).getMount());
			assertEquals(EnumFacing.UP, back.get(DIM, at(-40, 64, -900)).getMount(),
					"negative coordinates came back wrong, which is most of a map");
			assertEquals(Node.JUNCTION, back.nodeAt(DIM, at(4, 5, 6)));
			assertNull(back.get(DIM, at(4, 5, 6)).getMount(),
					"a box has no mounting face of its own, and a zero byte must not read as DOWN");
			assertEquals(EnumFacing.Axis.X, back.get(DIM, at(7, 8, 9)).getAxis());
			assertNull(back.get(DIM, at(7, 8, 9)).getMount());
			assertEquals(Node.JUNCTION, back.nodeAt(NETHER, at(10, 11, 12)));
			assertEquals(Node.NOTHING, back.nodeAt(DIM, at(10, 11, 12)));
		}

		@Test
		@DisplayName("nothing to read leaves an empty index and does not throw")
		void emptyFileIsFine()
		{
			index.rememberJunction(DIM, at(0, 0, 0));
			index.readFromNBT(new NBTTagCompound());
			assertTrue(index.isEmpty(), "reading an empty file must also clear what was there");
		}

		@Test
		@DisplayName("a nonsense entry is skipped rather than trusted")
		void rubbishIsSkipped()
		{
			//The bytes came off disk. An ordinal outside the enum would either throw or, worse, wrap
			//round to something plausible and remount a remembered conduit on the wrong wall.
			NBTTagCompound bad = new NBTTagCompound();
			bad.setLong("p", at(1, 0, 0).toLong());
			bad.setByte("k", (byte)99);
			NBTTagCompound nothing = new NBTTagCompound();
			nothing.setLong("p", at(2, 0, 0).toLong());
			nothing.setByte("k", (byte)Node.NOTHING.ordinal());
			NBTTagCompound good = new NBTTagCompound();
			good.setLong("p", at(3, 0, 0).toLong());
			good.setByte("k", (byte)Node.CONDUIT.ordinal());
			good.setByte("m", (byte)(EnumFacing.WEST.ordinal()+1));

			NBTTagList entries = new NBTTagList();
			entries.appendTag(bad);
			entries.appendTag(nothing);
			entries.appendTag(good);
			NBTTagCompound perDimension = new NBTTagCompound();
			perDimension.setInteger("dim", DIM);
			perDimension.setTag("at", entries);
			NBTTagList dimensions = new NBTTagList();
			dimensions.appendTag(perDimension);
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setTag("conduitIndex", dimensions);

			index.readFromNBT(nbt);
			assertEquals(1, index.size(), "only the good entry should have been taken");
			assertEquals(EnumFacing.WEST, index.get(DIM, at(3, 0, 0)).getMount());
		}
	}

	// ------------------------------------------------------------------
	// The sweep
	// ------------------------------------------------------------------

	@Nested
	@DisplayName("sweeping a chunk that has just loaded")
	class Sweep
	{
		@Test
		@DisplayName("an entry whose block is gone is dropped and its neighbour is kept")
		void staleEntriesGo()
		{
			//Somebody's WorldEdit, or a /setblock with flags that skip breakBlock: the index says there
			//is conduit at (2,0,0) and there is not. Left alone it would make a run out of nothing.
			index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(2, 0, 0), EnumFacing.DOWN);
			index.rememberJunction(DIM, at(3, 0, 0));

			Map<BlockPos, Node> world = new HashMap<>();
			world.put(at(1, 0, 0), Node.CONDUIT);
			world.put(at(3, 0, 0), Node.JUNCTION);

			assertEquals(1, index.sweepChunk(DIM, 0, 0, pos -> world.getOrDefault(pos, Node.NOTHING)));
			assertEquals(Node.CONDUIT, index.nodeAt(DIM, at(1, 0, 0)), "the good entry went too");
			assertEquals(Node.NOTHING, index.nodeAt(DIM, at(2, 0, 0)));
			assertEquals(Node.JUNCTION, index.nodeAt(DIM, at(3, 0, 0)));
		}

		@Test
		@DisplayName("a block that is now something else is dropped")
		void kindMismatchGoes()
		{
			//The kinds are what a walk routes on: a remembered box where a length of conduit now sits
			//would end a run in the middle of a corridor.
			index.rememberJunction(DIM, at(1, 0, 0));
			assertEquals(1, index.sweepChunk(DIM, 0, 0, pos -> Node.CONDUIT));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("only the chunk that loaded is looked at")
		void otherChunksAreLeftAlone()
		{
			//The point of keying by chunk. A chunk load happens constantly and this must not be a pass
			//over a few thousand entries -- nor may it delete the entries for chunks nobody can see,
			//which is the entire table's reason for existing.
			index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(20, 0, 0), EnumFacing.DOWN);
			index.rememberConduit(DIM, at(1, 0, 20), EnumFacing.DOWN);

			assertEquals(1, index.sweepChunk(DIM, 0, 0, pos -> Node.NOTHING));
			assertEquals(Node.CONDUIT, index.nodeAt(DIM, at(20, 0, 0)));
			assertEquals(Node.CONDUIT, index.nodeAt(DIM, at(1, 0, 20)));
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName("a chunk with nothing indexed in it costs nothing and changes nothing")
		void anEmptyChunkIsFree()
		{
			int[] dirtied = {0};
			index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN);
			index.setDirtyListener(() -> dirtied[0]++);
			assertEquals(0, index.sweepChunk(DIM, 9, 9, pos -> {
				throw new AssertionError("a chunk with no entries must not be read at all");
			}));
			assertEquals(0, index.sweepChunk(NETHER, 0, 0, pos -> Node.NOTHING));
			//And a sweep that finds everything in order is not a save write either.
			assertEquals(0, index.sweepChunk(DIM, 0, 0, pos -> Node.CONDUIT));
			assertEquals(0, dirtied[0]);
		}

		@Test
		@DisplayName("a sweep never rewrites a mount, only removes a lie")
		void sweepDoesNotRepair()
		{
			//It cannot: the mount lives on the tile entity and the sweep reads block states, for the
			//reason ConduitIndex.Hardware gives. The block's own load is what keeps a mount fresh.
			index.rememberConduit(DIM, at(1, 0, 0), EnumFacing.DOWN);
			assertEquals(0, index.sweepChunk(DIM, 0, 0, pos -> Node.CONDUIT));
			assertEquals(EnumFacing.DOWN, index.get(DIM, at(1, 0, 0)).getMount());
		}
	}

	// ------------------------------------------------------------------
	// Standing in for the world
	// ------------------------------------------------------------------

	@Nested
	@DisplayName("the probe that reads the world first and the index second")
	class Backing
	{
		@Test
		@DisplayName("a loaded block is the world's answer, whatever the index says")
		void theWorldWins()
		{
			//The world is what is actually there. An entry that disagrees with a block somebody is
			//standing next to is a stale entry, and deferring to it would be the index overruling
			//reality.
			Built world = new Built().conduit(1, 0, 0, EnumFacing.DOWN);
			index.rememberJunction(DIM, at(1, 0, 0));
			ConduitRoute.Probe probe = index.backing(DIM, world);
			assertEquals(Node.CONDUIT, probe.nodeAt(at(1, 0, 0)));
			assertEquals(EnumFacing.DOWN, probe.mountAt(at(1, 0, 0)));
			assertFalse(probe.answeredFromMemory(), "nothing was borrowed: the world could see it");
		}

		@Test
		@DisplayName("an unloaded block is the index's answer, and the probe admits it")
		void theIndexFillsIn()
		{
			Built world = new Built().unloadChunk(1, 0);
			index.rememberConduit(DIM, at(20, 0, 0), EnumFacing.EAST);
			ConduitRoute.Probe probe = index.backing(DIM, world);
			assertEquals(Node.CONDUIT, probe.nodeAt(at(20, 0, 0)));
			assertEquals(EnumFacing.EAST, probe.mountAt(at(20, 0, 0)));
			assertTrue(probe.answeredFromMemory(),
					"a probe that borrows without saying so lets its caller delete a working run");
		}

		@Test
		@DisplayName("an unloaded block nobody wrote down is still nothing")
		void unknownStaysUnknown()
		{
			Built world = new Built().unloadChunk(1, 0);
			ConduitRoute.Probe probe = index.backing(DIM, world);
			assertEquals(Node.NOTHING, probe.nodeAt(at(20, 0, 0)));
			assertFalse(probe.isLoaded(at(20, 0, 0)),
					"an unvisited chunk must read as unseen, or the walk stops looking for it");
			assertFalse(probe.answeredFromMemory(), "there was nothing to borrow");
		}

		@Test
		@DisplayName("a chunk the index has seen certifies its own empty cells")
		void anIndexedChunkCertifiesItsAir()
		{
			//The reason the test above is about the chunk and not the position. Every piece of conduit
			//in a loaded chunk writes itself down, so a chunk with an entry in it is a chunk whose empty
			//cells are honestly empty -- and without this, every walk down an unloaded corridor would
			//come back truncated on the strength of the air beside it.
			Built world = new Built().unloadChunk(1, 0).unloadChunk(2, 0);
			index.rememberConduit(DIM, at(20, 0, 0), EnumFacing.DOWN);
			ConduitRoute.Probe probe = index.backing(DIM, world);
			assertTrue(probe.isLoaded(at(25, 40, 9)), "same chunk, and it has been seen");
			assertFalse(probe.isLoaded(at(40, 40, 0)), "the chunk after it has not");
		}
	}

	// ------------------------------------------------------------------
	// What a walk makes of it
	// ------------------------------------------------------------------

	/**
	 * The run this whole phase is about: Alewife to Mt. Manchester, two hundred blocks, the middle of
	 * it unloaded whenever nobody is flying the line.
	 * <p>
	 * Five chunks of floor, a box at each end and conduit all the way, so that the interesting cases --
	 * a travelled chunk next to an untravelled one -- can be built at all. Chunk 0 is x 0 to 15, chunk
	 * 1 is 16 to 31, and so on to the box at x=79.
	 * <p>
	 * A chunk is the granularity throughout, and that is not a simplification: the index is filled by
	 * blocks announcing themselves as they load, and every piece of conduit in a chunk loads with it.
	 * "Half a chunk indexed" is not a state the server can be in.
	 */
	@Nested
	@DisplayName("a walk over a run that is not all there")
	class TheLongRun
	{
		private static final int FAR = 79;
		private Built world;

		@BeforeEach
		void buildTheLine()
		{
			world = new Built().floor(-1, -1, FAR+1);
			world.junction(0, 0, 0);
			for(int x = 1; x < FAR; x++)
				world.conduit(x, 0, 0, EnumFacing.DOWN);
			world.junction(FAR, 0, 0);
		}

		private ConduitRoute.Walk walk()
		{
			return ConduitRoute.walkFrom(at(0, 0, 0), index.backing(DIM, world));
		}

		/** Writes down one chunk of the line, as that chunk loading around a player would. */
		private void indexChunk(int chunkX)
		{
			for(int x = chunkX << 4; x < (chunkX << 4)+16; x++)
				if(x > 0&&x < FAR)
					index.rememberConduit(DIM, at(x, 0, 0), EnumFacing.DOWN);
		}

		@Test
		@DisplayName("with the whole line in sight it links, and it may prune")
		void allLoaded()
		{
			ConduitRoute.Walk found = walk();
			assertEquals(FAR, (int)found.boxes().get(at(FAR, 0, 0)));
			assertFalse(found.isTruncated());
			assertFalse(found.reliedOnMemory());
			assertTrue(found.mayPrune(), "a walk that saw everything is the one walk allowed to delete");
		}

		@Test
		@DisplayName("with the middle unloaded and indexed it still links, and is not truncated")
		void indexedMiddleLinks()
		{
			//The case the feature exists for. Before the index this walk came back with nothing at all
			//past x=15, so the far box was not in the wire graph and Mt. Manchester was dark.
			world.unloadChunk(1, 0).unloadChunk(2, 0).unloadChunk(3, 0);
			indexChunk(1);
			indexChunk(2);
			indexChunk(3);

			ConduitRoute.Walk found = walk();
			assertEquals(FAR, (int)found.boxes().get(at(FAR, 0, 0)),
					"the far box was not found across the unloaded stretch");
			assertFalse(found.isTruncated(),
					"every chunk of the gap has been read, so nothing was left unseen");
		}

		@Test
		@DisplayName("and a walk that read the index may never prune")
		void indexedWalkNeverPrunes()
		{
			//The rule that keeps 67dbf6ed4's bug from coming back by another door. An entry is right
			//until somebody edits the world without going through breakBlock, and the sweep that
			//catches that only runs when the chunk loads -- which for the middle of an inter-town run
			//is never. So this walk may make a run and may not delete one.
			world.unloadChunk(1, 0).unloadChunk(2, 0).unloadChunk(3, 0);
			indexChunk(1);
			indexChunk(2);
			indexChunk(3);

			ConduitRoute.Walk found = walk();
			assertTrue(found.reliedOnMemory());
			assertFalse(found.mayPrune(),
					"a walk built on remembered blocks is not grounds for deleting saved state");
		}

		@Test
		@DisplayName("a gap that is neither loaded nor indexed truncates the walk")
		void unknownGapTruncates()
		{
			//Nobody has been down this line since the index arrived, so there is nothing written down
			//and nothing to see. The walk has to say so, or the box would prune the far end off the run
			//it cannot currently find -- which is exactly how the link came apart in play.
			world.unloadChunk(1, 0);

			ConduitRoute.Walk found = walk();
			assertTrue(found.isTruncated());
			assertFalse(found.mayPrune());
			assertTrue(found.boxes().isEmpty(), "and it found no box past the gap");
		}

		@Test
		@DisplayName("half a line travelled is half a line indexed, and still short")
		void halfIndexedIsStillShort()
		{
			//What the operator will actually see after the update: fly part of the line and those chunks
			//are written down. The walk gets further, does not link yet, and stays truncated -- which is
			//what keeps the box retrying until the last chunk of the gap has been read.
			world.unloadChunk(1, 0).unloadChunk(2, 0).unloadChunk(3, 0);
			indexChunk(1);

			ConduitRoute.Walk found = walk();
			assertTrue(found.boxes().isEmpty());
			assertTrue(found.isTruncated(), "the rest of the gap is still unseen, so the box must retry");
		}

		@Test
		@DisplayName("a hole in a chunk the index has read is a real hole")
		void anIndexedChunkIsBelieved()
		{
			//The other half of the chunk rule, and the reason the rule is safe. Somebody dug the line up
			//from x=24 on while chunk 1 was loaded, which took those entries out through breakBlock, and
			//then left. The index has read chunk 1, so it knows every piece of conduit in it; there is
			//genuinely nothing past x=23, and the walk is entitled to say so rather than reporting that
			//it could not see.
			Built dug = new Built().floor(-1, -1, FAR+1);
			dug.junction(0, 0, 0);
			for(int x = 1; x <= 23; x++)
				dug.conduit(x, 0, 0, EnumFacing.DOWN);
			dug.unloadChunk(1, 0);
			for(int x = 16; x <= 23; x++)
				index.rememberConduit(DIM, at(x, 0, 0), EnumFacing.DOWN);

			ConduitRoute.Walk found = ConduitRoute.walkFrom(at(0, 0, 0), index.backing(DIM, dug));
			assertTrue(found.boxes().isEmpty());
			assertFalse(found.isTruncated(),
					"a chunk the index has read is not a blind spot -- the gap is the answer");
			assertFalse(found.mayPrune(), "but it still leant on memory to get as far as it did");
		}

		@Test
		@DisplayName("MAX_NODES still bounds a walk the index made longer")
		void theCeilingStillHolds()
		{
			//The index lets one walk cover far more ground than it used to, so the bound matters more
			//than it did rather than less.
			Built huge = new Built();
			huge.junction(0, 0, 0);
			for(int x = 1; x <= ConduitRoute.MAX_NODES+500; x++)
				huge.conduit(x, 0, 0, EnumFacing.DOWN);
			huge.junction(ConduitRoute.MAX_NODES+501, 0, 0);
			assertTrue(ConduitRoute.walkFrom(at(0, 0, 0), index.backing(DIM, huge)).boxes().isEmpty(),
					"the walk ran past its ceiling");
		}
	}

	/**
	 * The one question the index cannot answer, and what is done about it.
	 * <p>
	 * A run turns an inner corner around an ordinary block, and remembering which blocks on a map are
	 * solid would be a copy of the map. So an unloaded corner is taken on trust -- and the trust is
	 * recorded, which is what keeps the walk that took it from deleting anything.
	 * <p>
	 * The corner is put at x=16 so that it falls in the next chunk along from the conduit that turns
	 * around it, which is the arrangement a run crossing a chunk boundary really makes.
	 */
	@Nested
	@DisplayName("an inner corner nobody can see")
	class OptimisticCorner
	{
		private Built world;

		@BeforeEach
		void buildTheCorner()
		{
			//A run along the floor at y=1, a wall at x=16, and the run climbing it: the gesture the
			//playtester asked for, standing on a chunk boundary.
			world = new Built().floor(0, 10, 20);
			world.junction(13, 1, 0);
			world.conduit(14, 1, 0, EnumFacing.DOWN);
			world.conduit(15, 1, 0, EnumFacing.DOWN);
			world.conduit(15, 2, 0, EnumFacing.EAST);
			world.junction(15, 3, 0);
		}

		private ConduitRoute.Walk walk()
		{
			return ConduitRoute.walkFrom(at(13, 1, 0), index.backing(DIM, world));
		}

		@Test
		@DisplayName("with the wall in sight the corner is a corner")
		void loadedAndSolid()
		{
			world.wall(16, 1, 0);
			ConduitRoute.Walk found = walk();
			assertTrue(found.boxes().containsKey(at(15, 3, 0)));
			assertTrue(found.mayPrune());
		}

		@Test
		@DisplayName("with the wall in sight and nothing there, there is no corner")
		void loadedAndEmpty()
		{
			//Unchanged, and it has to be: a doorway is a real thing to build, and the two conduits are
			//then a floor run and a wall run that happen to be near each other.
			ConduitRoute.Walk found = walk();
			assertTrue(found.boxes().isEmpty(), "the run turned around a wall that is not there");
			assertTrue(found.mayPrune(), "nothing was guessed, so this reading is authoritative");
		}

		@Test
		@DisplayName("an unloaded corner is taken on trust, and the walk says it guessed")
		void unloadedIsTrusted()
		{
			//Two lengths of conduit clipped to perpendicular faces in an inner-corner arrangement do not
			//exist without the block they turn around -- a conduit is clipped to a surface, and that
			//surface is the corner block. Refusing the joint instead would cost a run that never links,
			//every time somebody builds a corner near a chunk boundary.
			world.wall(16, 1, 0).unloadChunk(1, 0);
			ConduitRoute.Walk found = walk();
			assertTrue(found.boxes().containsKey(at(15, 3, 0)),
					"the corner was refused because nobody was standing at it");
			assertFalse(found.mayPrune(),
					"a guessed corner is a guess, and a guess may not delete a run");
		}

		@Test
		@DisplayName("and it is still trusted when there is genuinely nothing there")
		void unloadedIsTrustedEvenWhenWrong()
		{
			//The price of the rule, stated out loud: a doorway in an unloaded chunk reads as a wall, so
			//the two runs are joined by one spurious edge. The next walk with that chunk loaded takes it
			//out again -- which it may, because that walk will not have guessed.
			world.unloadChunk(1, 0);
			assertTrue(walk().boxes().containsKey(at(15, 3, 0)));
			buildTheCorner();
			assertTrue(walk().boxes().isEmpty(), "and with the chunk back it is not a corner again");
		}

		@Test
		@DisplayName("but remembered hardware in the corner still refuses it")
		void rememberedFeederIsNotAWall()
		{
			//A ground feeder is a solid cube a run goes *through* rather than round, and a box is not
			//solid at all. That reading comes out of the index like any other and is as good unloaded as
			//loaded -- so the one test the optimism does not relax is the one that matters.
			world.wall(16, 1, 0).unloadChunk(1, 0);
			index.rememberFeeder(DIM, at(16, 1, 0), EnumFacing.Axis.Y);
			assertFalse(walk().boxes().containsKey(at(15, 3, 0)),
					"a run climbed a feeder instead of passing through it");

			index.forget(DIM, at(16, 1, 0));
			index.rememberJunction(DIM, at(16, 1, 0));
			//A box at the end of the flat run is a box the run legitimately ends at, so it turns up in
			//the result. What must not is the piece up the wall, which would mean the corner was turned
			//around a block that is not solid at all.
			assertFalse(walk().boxes().containsKey(at(15, 3, 0)),
					"a run turned a corner around a junction box");
			assertTrue(walk().boxes().containsKey(at(16, 1, 0)),
					"and the remembered box at the end of the flat run should still be found");
		}
	}
}
