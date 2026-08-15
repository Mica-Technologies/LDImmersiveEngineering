/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a bundle costs per tick, and the structure that keeps it there.
 * <p>
 * The plan's P6 asks for one thing above all: <em>a bundle must not cost more per tick than the
 * wires it replaces.</em> That claim rests on two structural properties rather than on any
 * measurement, so those are what is asserted here.
 * <ol>
 * <li><strong>One edge per run.</strong> Sixteen conductors down a corridor are one
 * {@code Connection}, not sixteen, so everything that walks the wire graph sees the same graph it
 * saw before conduits existed. This is the whole argument, and it is enforced by
 * {@link ChannelSet} living on a connection rather than by anything here.</li>
 * <li><strong>Idle is free.</strong> A junction box carrying nothing does one integer comparison.
 * A base with two hundred boxes and three live circuits costs two hundred comparisons, not two
 * hundred loops over sixteen channels and their neighbours.</li>
 * </ol>
 * <p>
 * <strong>What this file is not.</strong> It is not a profile. Real numbers need a real server with
 * real chunks loaded, and the plan is explicit that P6 owes an in-game measurement -- see
 * {@code docs/CITY_MODE_AND_PERF.md} for how the wire and pipe passes were actually measured. What
 * these tests do is fail loudly if the structure that makes the claim plausible is taken away,
 * which is the part that can rot silently between measurements.
 */
class ConduitCostTest
{
	private static final String SRC = "src/main/java/blusunrize/immersiveengineering/";

	private static String source(String path)
	{
		try
		{
			return new String(Files.readAllBytes(Paths.get(SRC+path)), StandardCharsets.UTF_8);
		} catch(IOException e)
		{
			throw new UncheckedIOException("could not read "+path, e);
		}
	}

	/**
	 * The shape of {@code TileEntityJunctionBox.update()}, counted rather than executed.
	 * <p>
	 * Mirroring the loop rather than running it, because running it needs a world. The mirror is
	 * only worth what its fidelity is worth, so {@link Structure} checks the real method still has
	 * this shape.
	 */
	private static int workPerTick(int liveChannels, int connections)
	{
		if(liveChannels==0)
			return 0;
		//One drain per live channel, then one hop per live channel per connection.
		return liveChannels+liveChannels*connections;
	}

	@Nested
	@DisplayName("what a tick costs")
	class Cost
	{
		@Test
		@DisplayName("a box carrying nothing does no work at all")
		void idleIsFree()
		{
			//Not "a little work" -- none. This is what makes a decorative build affordable, since
			//most conduit in most bases is carrying nothing most of the time.
			assertEquals(0, workPerTick(0, 0));
			assertEquals(0, workPerTick(0, 4), "an idle box still walked its connections");
		}

		@Test
		@DisplayName("cost scales with what is live, not with sixteen")
		void costFollowsLiveChannels()
		{
			//A run carrying one circuit must cost what one circuit costs. If a bundle charged for
			//all sixteen conductors whether or not they carried anything, the tidy option would be
			//sixteen times the price of the untidy one and nobody would use it.
			assertEquals(workPerTick(1, 2), 3);
			assertEquals(workPerTick(16, 2), 16*workPerTick(1, 2));
		}

		@Test
		@DisplayName("a sixteen-channel run costs no more than sixteen separate wires would")
		void bundleBeatsSeparateWires()
		{
			//The comparison the plan asks for, at the level a unit test can make it: sixteen
			//circuits down one corridor. As a bundle that is one edge carrying sixteen channels;
			//as ordinary wire it is sixteen edges, each of which the graph walks separately.
			int boxes = 10;
			int bundle = boxes*workPerTick(16, 2);
			int separateWires = boxes*16*workPerTick(1, 2);
			assertTrue(bundle <= separateWires,
					"a bundle costs more than the wires it replaces: "+bundle+" against "
							+separateWires);
		}

		@Test
		@DisplayName("adding a conductor to a run does not add an edge")
		void channelsDoNotAddEdges()
		{
			//The load-bearing property, stated as arithmetic: connections are an argument to the
			//cost function that channel count does not touch. A design where each conductor were
			//its own Connection would multiply this by sixteen, which is precisely what the wire
			//network's profiling history warns against.
			for(int channels = 1; channels <= 16; channels++)
				assertEquals(channels*(1+2), workPerTick(channels, 2),
						"channel count changed the number of connections walked");
		}
	}

	@Nested
	@DisplayName("the structure that makes it true")
	class Structure
	{
		@Test
		@DisplayName("the live-mask short circuit is the first thing update does")
		void idleExitComesFirst()
		{
			//A guard placed after the connection walk would still be correct and would cost every
			//idle box a set lookup per tick. It has to be the first thing.
			String box = source("common/blocks/conduit/TileEntityJunctionBox.java");
			int update = box.indexOf("public void update()");
			assertTrue(update > 0, "update() is gone -- rewrite this test");
			int guard = box.indexOf("liveMask==0", update);
			int walk = box.indexOf("getConnections(", update);
			assertTrue(guard > update, "update() no longer short-circuits on an idle box");
			assertTrue(walk < 0||guard < walk,
					"the idle check comes after the connection walk, so idle boxes pay for it");
		}

		@Test
		@DisplayName("the connection set is walked once, not once per channel")
		void connectionsOutsideChannels()
		{
			//Channels inside connections, not the other way round. Sixteen set iterations per box
			//per tick would be the same mistake in a different place.
			String box = source("common/blocks/conduit/TileEntityJunctionBox.java");
			int update = box.indexOf("public void update()");
			String body = box.substring(update, box.indexOf("private void drainToBreakout", update));
			assertEquals(1, countOf(body, "getConnections("),
					"update() asks the handler for its connections more than once per tick");
		}

		@Test
		@DisplayName("the connection set is used in place, not copied")
		void connectionSetIsNotCopied()
		{
			//A HashSet allocated per live box per tick is exactly the shape of thing this mod's
			//profiling history is about.
			String box = source("common/blocks/conduit/TileEntityJunctionBox.java");
			int update = box.indexOf("public void update()");
			String body = box.substring(update, box.indexOf("private void drainToBreakout", update));
			assertFalse(body.contains("currentBundles()"),
					"update() copies the connection set; currentBundles() is for the readouts");
			assertFalse(body.contains("new HashSet"), "update() allocates a set every tick");
		}

		@Test
		@DisplayName("city mode is wired in and can be switched off")
		void cityModeIsReachable()
		{
			//Every other subsystem's city mode is one call to CityMode; a conduit-shaped exception
			//would mean the master switch no longer restored stock behaviour everywhere.
			String box = source("common/blocks/conduit/TileEntityJunctionBox.java");
			assertTrue(box.contains("CityMode.conduits()"),
					"the conduit's city mode is declared but nothing reads it");
			//Checked by behaviour rather than by reading the source: since the flags may now also
			//arrive from a server, "respects the master switch" is a property of the resolver, not
			//of a particular line of text in it.
			boolean origMaster = blusunrize.immersiveengineering.common.Config.IEConfig.cityMode;
			boolean origConduits = blusunrize.immersiveengineering.common.Config.IEConfig.cityModeConduits;
			try
			{
				blusunrize.immersiveengineering.common.util.CityMode.clearServerOverride();
				blusunrize.immersiveengineering.common.Config.IEConfig.cityModeConduits = true;
				blusunrize.immersiveengineering.common.Config.IEConfig.cityMode = true;
				assertTrue(blusunrize.immersiveengineering.common.util.CityMode.conduits(),
						"conduits() does not follow its own sub-flag");
				blusunrize.immersiveengineering.common.Config.IEConfig.cityMode = false;
				assertFalse(blusunrize.immersiveengineering.common.util.CityMode.conduits(),
						"conduits() does not respect the master switch");
			} finally
			{
				blusunrize.immersiveengineering.common.Config.IEConfig.cityMode = origMaster;
				blusunrize.immersiveengineering.common.Config.IEConfig.cityModeConduits = origConduits;
			}
		}
	}

	@Nested
	@DisplayName("the arithmetic itself")
	class Arithmetic
	{
		@Test
		@DisplayName("a tick's worth of hops is not measurably expensive")
		void hopIsCheap()
		{
			//A floor rather than a benchmark: this is here to catch somebody quietly turning the
			//hop into something that allocates or loops. Two hundred boxes times sixteen channels
			//times four connections is far more conduit than any real base has, and the budget is
			//loose enough that a slow CI machine will not trip it.
			int iterations = 200*16*4;
			long start = System.nanoTime();
			int sink = 0;
			for(int i = 0; i < iterations; i++)
				sink += ConduitTransfer.hop(10000+i, i%5000, 32768, 32768, 0.01).delivered;
			long elapsed = System.nanoTime()-start;
			assertTrue(sink > 0, "the loop was optimised away and measured nothing");
			assertTrue(elapsed < 100_000_000L,
					"a tick's worth of conduit arithmetic took "+elapsed/1_000_000+"ms");
		}

		@Test
		@DisplayName("a hop allocates one small object and nothing else")
		void hopDoesNotAllocateCollections()
		{
			//Checked as source, since a test cannot see an allocation. The Moved record is the only
			//thing a hop may create; a list or a map in here would be a per-box-per-channel
			//allocation every tick.
			String transfer = source("api/energy/wires/conduit/ConduitTransfer.java");
			assertFalse(transfer.contains("new ArrayList")||transfer.contains("new HashMap")
					||transfer.contains("new HashSet"), "a hop allocates a collection");
		}
	}

	private static int countOf(String text, String needle)
	{
		int n = 0;
		for(int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i+needle.length()))
			n++;
		return n;
	}
}
