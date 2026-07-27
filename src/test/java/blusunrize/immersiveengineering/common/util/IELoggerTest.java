/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the logging facade forwards to whatever {@link Logger} it was handed.
 * <p>
 * {@code IELogger.logger} is a mutable static wired up during FML pre-init, so these tests swap in
 * a recording proxy and put the original back afterwards.
 */
class IELoggerTest
{
	private Logger originalLogger;
	private boolean originalDebug;
	private List<Object[]> calls;

	@BeforeEach
	void installRecordingLogger()
	{
		originalLogger = IELogger.logger;
		originalDebug = IELogger.debug;
		calls = new ArrayList<>();
		IELogger.logger = (Logger)Proxy.newProxyInstance(
				Logger.class.getClassLoader(),
				new Class<?>[]{Logger.class},
				(proxy, method, args) -> {
					if("log".equals(method.getName()))
						calls.add(args);
					if(method.getReturnType()==boolean.class)
						return false;
					return null;
				});
	}

	@AfterEach
	void restoreLogger()
	{
		IELogger.logger = originalLogger;
		IELogger.debug = originalDebug;
	}

	private Object[] onlyCall()
	{
		assertEquals(1, calls.size(), "expected exactly one call to Logger.log");
		return calls.get(0);
	}

	@Test
	@DisplayName("log() forwards the level and the stringified message")
	void logForwardsLevelAndMessage()
	{
		IELogger.log(Level.TRACE, "hello");
		Object[] call = onlyCall();
		assertEquals(Level.TRACE, call[0]);
		assertEquals("hello", call[1]);
	}

	@Test
	@DisplayName("log() stringifies non-string arguments")
	void logStringifiesObjects()
	{
		IELogger.log(Level.INFO, 42);
		assertEquals("42", onlyCall()[1]);
	}

	@Test
	@DisplayName("log() turns a null message into the string \"null\" rather than throwing")
	void logHandlesNull()
	{
		IELogger.log(Level.INFO, null);
		assertEquals("null", onlyCall()[1]);
	}

	@Test
	@DisplayName("info() logs at INFO")
	void infoUsesInfoLevel()
	{
		IELogger.info("m");
		assertEquals(Level.INFO, onlyCall()[0]);
	}

	@Test
	@DisplayName("warn() logs at WARN")
	void warnUsesWarnLevel()
	{
		IELogger.warn("m");
		assertEquals(Level.WARN, onlyCall()[0]);
	}

	@Test
	@DisplayName("error() logs at ERROR")
	void errorUsesErrorLevel()
	{
		IELogger.error("m");
		assertEquals(Level.ERROR, onlyCall()[0]);
	}

	@Test
	@DisplayName("the parameterised overloads pass the format string and parameters through untouched")
	void parameterisedOverloadsPassParams()
	{
		IELogger.info("a {} b", 1, 2);
		Object[] call = onlyCall();
		assertEquals(Level.INFO, call[0]);
		assertEquals("a {} b", call[1]);
		assertArrayEquals(new Object[]{1, 2}, (Object[])call[2],
				"parameters must reach log4j unformatted, so a disabled level costs nothing");
	}

	@Test
	@DisplayName("the parameterised overloads use the right levels")
	void parameterisedOverloadLevels()
	{
		IELogger.warn("m", 1);
		IELogger.error("m", 1);
		assertEquals(Arrays.asList(Level.WARN, Level.ERROR),
				Arrays.asList(calls.get(0)[0], calls.get(1)[0]));
	}

	@Test
	@DisplayName("debug() is disabled and never reaches the logger")
	void debugIsDisabled()
	{
		IELogger.debug = true;
		IELogger.debug("this should go nowhere");
		assertTrue(calls.isEmpty());
	}

	@Test
	@DisplayName("debug() is safe before a logger has been installed")
	void debugWorksWithoutALogger()
	{
		IELogger.logger = null;
		assertDoesNotThrow(() -> IELogger.debug("no logger yet"));
	}
}
