/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.fluidnet;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidDeviceType;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid network's chunk-loading policy.
 * <p>
 * <strong>This toggle was dead UI for a while.</strong> The console showed a "Chunkload on/off"
 * button, the action packet applied it, {@link FluidDevice} stored it, and the config offered both
 * {@code fluidNetAllowChunkloading} and {@code fluidNetChunkloadBudget} -- with nothing anywhere
 * reading {@code isChunkLoad()}. A player could switch it on, walk away, and watch their Outlet
 * stop working, having been told it would not.
 * <p>
 * `ForgeChunkManager` cannot be exercised in a test JVM, so this splits in two: the policy
 * (<em>which</em> chunks should be pinned, and how the budget is spent) is checked directly, and
 * the wiring (that something actually calls the loader at all) is checked as source text. The
 * second half is the one that would have caught the original mistake.
 */
class FluidNetChunkLoaderTest
{
	private VirtualFluidNet net;

	@BeforeEach
	void setUp()
	{
		FluidNetConfig.resetToDefaults();
		net = new VirtualFluidNet();
	}

	@AfterEach
	void tearDown()
	{
		FluidNetConfig.resetToDefaults();
	}

	private FluidDevice at(int x, int z, boolean chunkLoad)
	{
		FluidDevice device = net.registerDevice(new DimensionBlockPos(x, 64, z, 0), FluidDeviceType.INLET);
		device.setChunkLoad(chunkLoad);
		return device;
	}

	/**
	 * The same rule {@code FluidNetChunkLoader.refresh} applies, expressed once so a test can assert
	 * against it without a {@code World}.
	 */
	private Set<String> wantedChunks()
	{
		Set<String> wanted = new HashSet<>();
		int budget = Math.max(0, FluidNetConfig.chunkloadBudget);
		if(!FluidNetConfig.enabled||!FluidNetConfig.allowChunkloading)
			return wanted;
		for(FluidDevice device : net.getDevices())
		{
			if(!device.isChunkLoad()||!device.isEnabled())
				continue;
			String key = device.getDimension()+":"+(device.getPos().getX() >> 4)
					+","+(device.getPos().getZ() >> 4);
			if(wanted.contains(key))
				continue;
			if(wanted.size() >= budget)
				continue;
			wanted.add(key);
		}
		return wanted;
	}

	@Nested
	@DisplayName("which chunks are wanted")
	class Policy
	{
		@Test
		@DisplayName("an empty network asks for nothing")
		void emptyAsksForNothing()
		{
			assertTrue(wantedChunks().isEmpty());
		}

		@Test
		@DisplayName("a fitting without the flag asks for nothing")
		void unflaggedAsksForNothing()
		{
			at(0, 0, false);
			assertTrue(wantedChunks().isEmpty());
		}

		@Test
		@DisplayName("a flagged fitting asks for its own chunk")
		void flaggedAsksForOne()
		{
			at(0, 0, true);
			assertEquals(1, wantedChunks().size());
		}

		@Test
		@DisplayName("fittings sharing a chunk only cost one")
		void sharedChunkCountsOnce()
		{
			//A forecourt with an Inlet and an Outlet a few blocks apart must not spend two of the
			//budget on one chunk.
			at(0, 0, true);
			at(5, 5, true);
			assertEquals(1, wantedChunks().size());
		}

		@Test
		@DisplayName("fittings in different chunks each cost one")
		void separateChunksCountSeparately()
		{
			at(0, 0, true);
			at(64, 64, true);
			assertEquals(2, wantedChunks().size());
		}

		@Test
		@DisplayName("negative coordinates map to the right chunk")
		void negativeCoordinates()
		{
			//Arithmetic shift, not division: -1 >> 4 is -1, while -1/16 is 0, and the difference is
			//a fitting at x=-1 pinning the chunk next door instead of its own.
			at(-1, -1, true);
			at(-20, -20, true);
			assertEquals(2, wantedChunks().size());
		}

		@Test
		@DisplayName("a disabled fitting stops pinning its chunk")
		void disabledStopsPinning()
		{
			//"Disabled" means not in service, and something not in service should not be holding a
			//chunk in memory.
			FluidDevice device = at(0, 0, true);
			assertEquals(1, wantedChunks().size());
			device.setEnabled(false);
			assertTrue(wantedChunks().isEmpty());
		}

		@Test
		@DisplayName("the budget is a hard cap")
		void budgetIsHard()
		{
			FluidNetConfig.chunkloadBudget = 2;
			for(int i = 0; i < 10; i++)
				at(i*64, 0, true);
			assertEquals(2, wantedChunks().size());
		}

		@Test
		@DisplayName("a budget of zero pins nothing")
		void zeroBudgetPinsNothing()
		{
			FluidNetConfig.chunkloadBudget = 0;
			at(0, 0, true);
			assertTrue(wantedChunks().isEmpty());
		}

		@Test
		@DisplayName("the config master switches both override the per-fitting flag")
		void configOverridesTheFlag()
		{
			at(0, 0, true);
			FluidNetConfig.allowChunkloading = false;
			assertTrue(wantedChunks().isEmpty(), "allowChunkloading off means nothing is pinned");
			FluidNetConfig.allowChunkloading = true;
			FluidNetConfig.enabled = false;
			assertTrue(wantedChunks().isEmpty(), "a disabled network pins nothing either");
		}

		@Test
		@DisplayName("the flag survives the config being switched off and back on")
		void flagIsRemembered()
		{
			//The player's own setting is theirs; the config is the server's. Turning the server
			//switch off must not silently erase what everyone asked for.
			FluidDevice device = at(0, 0, true);
			FluidNetConfig.allowChunkloading = false;
			assertFalse(device.isChunkLoad());
			assertTrue(device.isChunkLoadRequested());
			FluidNetConfig.allowChunkloading = true;
			assertTrue(device.isChunkLoad());
		}
	}

	@Nested
	@DisplayName("the wiring")
	class Wiring
	{
		private String source(String path)
		{
			try
			{
				return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new UncheckedIOException("could not read "+path, e);
			}
		}

		/**
		 * The check that would have caught the original bug: a stored flag nobody reads.
		 */
		@Test
		@DisplayName("something outside the loader actually calls it")
		void theLoaderIsCalled()
		{
			String main = source("src/main/java/blusunrize/immersiveengineering/ImmersiveEngineering.java");
			assertTrue(main.contains("FluidNetChunkLoader.refresh()"),
					"nothing rebuilds the forced set at server start, so a network loaded from save "
							+"data would pin nothing until somebody edited a fitting");
			assertTrue(main.contains("FluidNetChunkLoader.releaseAll()"),
					"tickets are never dropped at server stop, so a second world inherits them");

			String action = source("src/main/java/blusunrize/immersiveengineering/common/util/"
					+"network/MessageFluidNetAction.java");
			assertTrue(action.contains("FluidNetChunkLoader.refresh()"),
					"toggling chunk loading in the console would change a stored flag and nothing else");

			String device = source("src/main/java/blusunrize/immersiveengineering/common/blocks/"
					+"fluidnet/TileEntityFluidNetDevice.java");
			assertTrue(device.contains("FluidNetChunkLoader.refresh()"),
					"breaking a chunk-loading fitting would leave its chunk pinned by a block that "
							+"no longer exists");
		}

		@Test
		@DisplayName("only one chunk-loading callback is registered for the whole mod")
		void oneCallbackPerMod()
		{
			//ForgeChunkManager stores ONE callback per mod container, so a second registration
			//silently replaces the first. The grid's is the one that counts; the fluid network
			//deliberately does not register its own.
			//Matched with the open paren, so that a comment *explaining* why there is no second
			//registration does not read as one. The first version of this test failed on its own
			//documentation, which is a good reminder that a source-text check is only as precise as
			//the string it looks for.
			String fluid = source("src/main/java/blusunrize/immersiveengineering/common/util/"
					+"fluidnet/FluidNetChunkLoader.java");
			assertFalse(fluid.contains("setForcedChunkLoadingCallback("),
					"the fluid network must not register a second callback -- it would replace the "
							+"grid's rather than coexist with it");

			String grid = source("src/main/java/blusunrize/immersiveengineering/common/util/"
					+"grid/GridChunkLoader.java");
			assertTrue(grid.contains("setForcedChunkLoadingCallback("),
					"somebody has to register it, and the grid's loader is where it lives");
		}

		@Test
		@DisplayName("releasing a ticket cannot throw, in either loader")
		void bothReleasesAreGuarded()
		{
			//ForgeChunkManager.releaseTicket dereferences the ticket's world with no null check, and
			//at server stop that world is already gone. An exception escaping the stop handler kills
			//the Server thread mid-shutdown and hangs the client -- the grid shipped exactly that.
			for(String path : new String[]{
					"src/main/java/blusunrize/immersiveengineering/common/util/grid/GridChunkLoader.java",
					"src/main/java/blusunrize/immersiveengineering/common/util/fluidnet/FluidNetChunkLoader.java"})
			{
				String text = source(path);
				int release = text.indexOf("ForgeChunkManager.releaseTicket");
				assertTrue(release > 0, path+" no longer hands tickets back at all");
				assertTrue(text.lastIndexOf("catch", release+400) > release
								||text.substring(release, Math.min(text.length(), release+400)).contains("catch"),
						path+": releaseTicket must be inside a try/catch");
			}
		}
	}
}
