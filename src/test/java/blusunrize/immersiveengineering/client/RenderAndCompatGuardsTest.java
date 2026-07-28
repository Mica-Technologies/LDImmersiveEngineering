/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client;

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
 * Invariants of the client renderers and the compat modules that no unit test can execute.
 * <p>
 * None of this is reachable from a test JVM: a TESR needs a GL context, a baked model needs the
 * texture atlas, and the compat drivers need mods that are not on the test classpath. What they
 * <em>are</em> is a small set of rules that have to hold by inspection, each of which was broken
 * at least once -- so the check is source text, the same compromise
 * {@code FluidNetChunkLoaderTest.Wiring} makes and for the same reason. A source-text test is worth
 * roughly what its search string is worth; each one below therefore looks for the smallest thing
 * that cannot be true if the bug is back.
 */
class RenderAndCompatGuardsTest
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

	@Nested
	@DisplayName("the GL matrix stack")
	class MatrixStack
	{
		@Test
		@DisplayName("the belljar does not return out of a pushed matrix")
		void belljarDoesNotReturnMidRender()
		{
			//The original: a plant handler returning no states bailed out two pushMatrix calls deep,
			//leaking two stack entries per frame. A bounded stack overflows in about a second of
			//looking at it, and every model drawn afterwards comes out skewed.
			String text = source("client/render/TileRenderBelljar.java");
			int push = text.indexOf("GlStateManager.pushMatrix()");
			assertTrue(push > 0, "the belljar no longer pushes a matrix -- rewrite this test");
			String body = text.substring(push);
			assertFalse(body.contains("\t\t\t\t\treturn;"),
					"a bare return inside the belljar's render body skips the popMatrix calls "
							+"beneath it; skip the loop instead of leaving the method");
		}

		@Test
		@DisplayName("every renderer's pushes and pops balance by count")
		void pushesAndPopsBalance()
		{
			//A crude check -- it cannot tell a pop on the wrong branch from a pop on the right one --
			//but an unequal count is always wrong, and that is the shape the belljar bug had.
			//Comments are stripped first: the bucket wheel carries a commented-out predecessor of its
			//own render method, and counting that reports an imbalance in code nobody runs.
			for(String file : new String[]{
					"client/render/TileRenderBelljar.java",
					"client/render/TileRenderBucketWheel.java",
					"client/render/TileRenderTeslaCoil.java",
					"client/render/TileRenderSampleDrill.java"})
			{
				String text = uncommented(source(file));
				assertEquals(count(text, "pushMatrix()"), count(text, "popMatrix()"),
						file+": pushMatrix and popMatrix counts differ");
			}
		}

		private String uncommented(String text)
		{
			StringBuilder out = new StringBuilder(text.length());
			for(String line : text.split("\n", -1))
			{
				String trimmed = line.trim();
				if(!trimmed.startsWith("//")&&!trimmed.startsWith("*")&&!trimmed.startsWith("/*"))
					out.append(line).append('\n');
			}
			return out.toString();
		}

		private int count(String text, String needle)
		{
			int n = 0;
			for(int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i+needle.length()))
				n++;
			return n;
		}
	}

	@Nested
	@DisplayName("render caches")
	class RenderCaches
	{
		/**
		 * A cache that a resource reload cannot reach keeps drawing the old resource pack until the
		 * game is restarted. The bucket wheel was the only one in the package not registered.
		 */
		@Test
		@DisplayName("every static render cache is registered for clearing")
		void everyCacheIsCleared()
		{
			String proxy = source("client/ClientProxy.java");
			for(String owner : new String[]{
					"TileRenderBelljar", "TileRenderWatermill", "TileRenderWindmill",
					"TileRenderBucketWheel"})
				assertTrue(proxy.contains(owner+"::reset"),
						owner+" caches a baked model but nothing clears it on a resource reload");
		}

		/**
		 * {@code getQuads} is called from vanilla's chunk-render worker pool, one thread per chunk
		 * being compiled. A plain {@code HashMap} there can lose entries, throw from a worker, or in
		 * the classic case spin forever inside a resize.
		 */
		@Test
		@DisplayName("block model quad caches are thread-safe")
		void quadCachesAreConcurrent()
		{
			for(String file : new String[]{
					"client/models/ModelConveyor.java",
					"client/models/IESmartObjModel.java",
					"client/models/ModelConfigurableSides.java"})
			{
				String text = source(file);
				int decl = text.indexOf("modelCache =");
				assertTrue(decl > 0, file+" no longer declares modelCache -- rewrite this test");
				String line = text.substring(text.lastIndexOf('\n', decl)+1, text.indexOf(';', decl));
				assertTrue(line.contains("ConcurrentHashMap"),
						file+": modelCache is filled from getQuads, which runs on the chunk-render "
								+"workers, so it cannot be a plain HashMap -- found: "+line.trim());
			}
		}
	}

	@Nested
	@DisplayName("compat modules")
	class Compat
	{
		@Test
		@DisplayName("the OpenComputers helper never hands back a null tile")
		void ocTileIsNeverNull()
		{
			//Ninety-odd callbacks dereference this immediately. Making the nullable form opt-in is
			//what keeps that from being ninety-odd NPEs the first time somebody breaks a machine
			//while a program is polling it.
			String helper = source("common/util/compat/opencomputers/ManagedEnvironmentIE.java");
			assertTrue(helper.contains("getTileEntityOrNull()"),
					"the nullable form has to exist for onDisconnect and friends to use");
			int throwing = helper.indexOf("protected T getTileEntity()");
			assertTrue(throwing > 0, "the throwing form is gone");
			assertTrue(helper.indexOf("throw new IllegalStateException", throwing) > throwing,
					"getTileEntity must throw rather than return null -- its callers do not check");
		}

		@Test
		@DisplayName("WAILA guards the Tesla Coil's paired half")
		void wailaGuardsTheCoilPair()
		{
			String waila = source("common/util/compat/waila/IEWailaDataProvider.java");
			int hop = waila.indexOf("((TileEntityTeslaCoil)te).facing, -1)");
			assertTrue(hop > 0, "the dummy-to-master hop is gone -- rewrite this test");
			//The re-check has to come after the hop, not before: the `instanceof` at the top of the
			//block says nothing about what is at the *other* position.
			assertTrue(waila.indexOf("if(te instanceof TileEntityTeslaCoil)", hop) > hop,
					"the tile fetched across the hop is cast without being checked; an orphaned "
							+"dummy half throws on the server every time somebody looks at it");
		}

		@Test
		@DisplayName("the shader tooltip cannot dereference null")
		void shaderTooltipIsEmptyNotNull()
		{
			//`ItemStack shader = wrapper!=null ? ... : null` followed by `shader.isEmpty()` is a
			//guard that does the opposite of guarding. EMPTY is the sentinel this code base uses.
			String handler = source("client/ClientEventHandler.java");
			assertFalse(handler.contains("wrapper!=null?wrapper.getShaderItem(): null"),
					"the null branch feeds straight into isEmpty(); use ItemStack.EMPTY");
		}
	}
}
