/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import net.minecraft.command.CommandException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link CommandHandler}: the client/server split and the {@code <...>}
 * quoting that lets a sub-command receive an argument containing spaces.
 * <p>
 * Only the failure paths of the quote parser are reachable without a live server, but those are the
 * interesting ones -- a malformed quote must be reported rather than silently re-split.
 */
class CommandHandlerTest
{
	@Test
	@DisplayName("the client handler is /cie and needs no permission")
	void clientHandlerIsUnprivileged()
	{
		CommandHandler client = new CommandHandler(true);

		assertEquals("cie", client.getName());
		assertEquals(0, client.getRequiredPermissionLevel(),
				"the client-side render reset must stay usable without op");
	}

	@Test
	@DisplayName("the server handler is /ie and requires the highest permission level")
	void serverHandlerRequiresOp()
	{
		CommandHandler server = new CommandHandler(false);

		assertEquals("ie", server.getName());
		assertEquals(4, server.getRequiredPermissionLevel(),
				"the server commands rewrite world data and must stay op-only");
	}

	@Test
	@DisplayName("the two handlers do not share a command name")
	void handlerNamesDoNotCollide()
	{
		assertNotEquals(new CommandHandler(true).getName(), new CommandHandler(false).getName());
	}

	@Test
	@DisplayName("the usage string points at the handler's own help sub-command")
	void usagePointsAtHelp()
	{
		assertEquals("Use \"/cie help\" for more information", new CommandHandler(true).getUsage(null));
		assertEquals("Use \"/ie help\" for more information", new CommandHandler(false).getUsage(null));
	}

	@Test
	@DisplayName("both handlers register at least a help sub-command")
	void handlersHaveSubCommands()
	{
		assertFalse(new CommandHandler(true).getSubCommands().isEmpty());
		assertFalse(new CommandHandler(false).getSubCommands().isEmpty());
		assertTrue(new CommandHandler(false).getSubCommands().size() > new CommandHandler(true).getSubCommands().size(),
				"the server handler carries the extra mineral and shader commands");
	}

	@Test
	@DisplayName("opening a quoted argument twice is rejected")
	void doubleOpenIsRejected()
	{
		CommandException thrown = assertThrows(CommandException.class,
				() -> new CommandHandler(false).execute(null, null, new String[]{"<first", "<second"}));
		assertTrue(thrown.getMessage().contains("opens twice"), "unexpected message: "+thrown.getMessage());
	}

	@Test
	@DisplayName("closing a quoted argument that was never opened is rejected")
	void strayCloseIsRejected()
	{
		CommandException thrown = assertThrows(CommandException.class,
				() -> new CommandHandler(false).execute(null, null, new String[]{"never-opened>"}));
		assertTrue(thrown.getMessage().contains("without being"), "unexpected message: "+thrown.getMessage());
	}

	@Test
	@DisplayName("leaving a quoted argument unclosed is rejected")
	void unclosedQuoteIsRejected()
	{
		CommandException thrown = assertThrows(CommandException.class,
				() -> new CommandHandler(false).execute(null, null, new String[]{"set", "<never", "closed"}));
		assertTrue(thrown.getMessage().contains("Unclosed"), "unexpected message: "+thrown.getMessage());
	}

	@Test
	@DisplayName("the clear-shaders command stays op-only and keeps its name")
	void clearShadersCommandMetadata()
	{
		CommandShaders command = new CommandShaders();

		assertEquals("clearshaders", command.getName());
		assertEquals(4, command.getRequiredPermissionLevel());
		assertTrue(command.getUsage(null).startsWith("/ie clearshaders"));
	}

	@Test
	@DisplayName("the render reset command stays client-side and unprivileged")
	void resetRendersCommandMetadata()
	{
		CommandResetRenders command = new CommandResetRenders();

		assertEquals("resetrender", command.getName());
		assertEquals(0, command.getRequiredPermissionLevel());
	}

	@Test
	@DisplayName("the mineral command keeps its name and its four sub-commands plus help")
	void mineralCommandMetadata()
	{
		CommandMineral command = new CommandMineral();

		assertEquals("mineral", command.getName());
		assertEquals(4, command.getRequiredPermissionLevel());
		assertEquals(5, command.getSubCommands().size(),
				"list, get, set, setdepletion and help");
	}
}
