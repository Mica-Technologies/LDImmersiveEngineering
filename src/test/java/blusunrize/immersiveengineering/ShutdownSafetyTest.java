/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the one method in this mod whose failure mode is "the game hangs".
 * <p>
 * FML turns an exception thrown from a mod's {@code FMLServerStoppedEvent} handler into a
 * {@code LoaderExceptionModCrash} on the Server thread, part-way through
 * {@code MinecraftServer.run}'s shutdown. The thread dies before it can signal that the server has
 * stopped, and an integrated client then waits for it forever. <strong>A bug there does not present
 * as a crash; it presents as "leaving a world hangs the game."</strong>
 * <p>
 * It shipped exactly once, and it was expensive to find: the dev-environment log config cannot build
 * a console appender, so the stack trace never reached the terminal and only ever existed in
 * {@code run/logs/latest.log}. The cause was
 * {@code ForgeChunkManager.releaseTicket} dereferencing the ticket's world after that world had
 * already been unloaded.
 * <p>
 * This is a source-text check rather than a behavioural one because the property is about a Forge
 * event contract that cannot be exercised in a test JVM at all. That trade is already made elsewhere
 * in this suite -- {@code PetroleumManualTest} reads {@code ClientProxy} as text for the same
 * reason: the alternative is not a better test, it is no test.
 */
class ShutdownSafetyTest
{
	private static final String MAIN =
			"src/main/java/blusunrize/immersiveengineering/ImmersiveEngineering.java";
	private static final String CHUNK_LOADER =
			"src/main/java/blusunrize/immersiveengineering/common/util/grid/GridChunkLoader.java";

	private static String source(String path)
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
	 * @return the body of a method, from its declaration to the matching closing brace
	 */
	private static String methodBody(String source, String declaration)
	{
		int start = source.indexOf(declaration);
		assertTrue(start >= 0, "could not find "+declaration);
		int open = source.indexOf('{', start);
		assertTrue(open >= 0, "no body for "+declaration);
		int depth = 0;
		for(int i = open; i < source.length(); i++)
		{
			char c = source.charAt(i);
			if(c=='{')
				depth++;
			else if(c=='}'&&--depth==0)
				return source.substring(open, i+1);
		}
		throw new AssertionError("unbalanced braces after "+declaration);
	}

	@Test
	@DisplayName("serverStopped catches everything it can throw")
	void serverStoppedIsGuarded()
	{
		String body = methodBody(source(MAIN), "public void serverStopped(");
		assertTrue(body.contains("try"), "serverStopped has no try block");
		assertTrue(body.contains("catch"),
				"serverStopped does not catch. Anything escaping it kills the Server thread "
						+"mid-shutdown and hangs the client on world exit.");
		//Specifically a RuntimeException catch, not just a checked one: the failure that shipped
		//was an NPE out of Forge.
		assertTrue(body.contains("catch(RuntimeException")||body.contains("catch(Exception")
						||body.contains("catch(Throwable"),
				"serverStopped must catch unchecked exceptions -- the failure that shipped was an "
						+"NPE thrown by ForgeChunkManager");
	}

	@Test
	@DisplayName("serverStopped still does its cleanup")
	void serverStoppedStillCleansUp()
	{
		//A guard that swallowed the work as well as the exception would pass the test above while
		//quietly letting a second world in the same session inherit the first one's state.
		String body = methodBody(source(MAIN), "public void serverStopped(");
		assertTrue(body.contains("releaseAll()"), "serverStopped no longer drops chunk tickets");
		assertTrue(body.contains("VirtualGrid.INSTANCE.detachAll()"),
				"serverStopped no longer detaches the grid");
		assertTrue(body.contains("VirtualFluidNet.INSTANCE.detachAll()"),
				"serverStopped no longer detaches the fluid network");
	}

	@Test
	@DisplayName("releasing a chunk ticket cannot throw")
	void ticketReleaseIsGuarded()
	{
		String body = methodBody(source(CHUNK_LOADER), "private static void release(int dimension)");
		assertTrue(body.contains("releaseTicket"), "release() no longer hands the ticket back");
		assertTrue(body.contains("catch"),
				"release() must not let ForgeChunkManager.releaseTicket throw: it dereferences the "
						+"ticket's world with no null check, and at server stop that world is gone");
	}

	@Test
	@DisplayName("releaseAll leaves no state behind even when a release fails")
	void releaseAllClearsItsMaps()
	{
		String body = methodBody(source(CHUNK_LOADER), "public static void releaseAll()");
		assertTrue(body.contains("tickets.clear()")&&body.contains("forced.clear()"),
				"releaseAll must clear its own maps unconditionally, so a failed hand-back cannot "
						+"leave a stale ticket to be re-used by the next world");
	}
}
