/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the Oilfield Engineering chapter is whole.
 * <p>
 * A manual page whose lang key is missing does not fail the build and does not throw: it renders
 * the raw key at the reader, which is both ugly and easy to miss, because the only way to see it
 * is to open the book at that exact page. Registering a page in {@code ClientProxy} and
 * forgetting its text is the mistake this exists to catch, so the page list is <em>read out
 * of</em> {@code ClientProxy} rather than restated here -- restating it would let the two drift
 * apart silently, which is the very failure being guarded against.
 * <p>
 * Neither file can be loaded as a class in a test JVM ({@code ClientProxy} pulls in the whole
 * client), so both are read as text. That is the same trade {@code PetroleumAssetsTest} makes
 * with the blockstates.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
class PetroleumManualTest
{
	private static final String LANG =
			"src/main/resources/assets/immersiveengineering/lang/en_us.lang";
	private static final String CLIENT_PROXY =
			"src/main/java/blusunrize/immersiveengineering/client/ClientProxy.java";

	/**
	 * The chapter's entry name, which is also the prefix of every one of its page keys.
	 */
	private static final String ENTRY = "petroleum";

	private static Properties lang()
	{
		Properties lang = new Properties();
		File file = new File(LANG);
		assertTrue(file.isFile(), "missing lang file: "+file.getPath());
		try(Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
		{
			lang.load(reader);
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+file.getPath(), e);
		}
		return lang;
	}

	private static String source(String path)
	{
		File file = new File(path);
		assertTrue(file.isFile(), "missing source file: "+file.getPath());
		try
		{
			//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files CRLF,
			//and every pattern below is written with the bare newline the repository stores.
			return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+file.getPath(), e);
		}
	}

	/**
	 * Cuts the chapter's registration out of {@code ClientProxy}: everything from its
	 * {@code addEntry("petroleum", ...} to the {@code ));} that closes the call.
	 */
	private static String registration()
	{
		String proxy = source(CLIENT_PROXY);
		int start = proxy.indexOf("addEntry(\""+ENTRY+"\"");
		assertTrue(start >= 0, "ClientProxy registers no \""+ENTRY+"\" manual entry at all");
		int end = proxy.indexOf("));", start);
		assertTrue(end > start, "the "+ENTRY+" entry registration is never closed");
		return proxy.substring(start, end);
	}

	/**
	 * @return every page key the chapter registers, in the order it registers them
	 */
	private static List<String> registeredPages()
	{
		//Deliberately not a set first: a key used twice is a mistake worth reporting, and the
		//order matters for the contiguity check below.
		List<String> pages = new ArrayList<>();
		Matcher matcher = Pattern.compile("\""+ENTRY+"(\\d+)\"").matcher(registration());
		while(matcher.find())
			pages.add(ENTRY+matcher.group(1));
		return pages;
	}

	@Test
	@DisplayName("the chapter registers pages at all")
	void theChapterHasPages()
	{
		//A guard on the guard: if the regex ever stops matching, every other assertion here
		//would pass vacuously.
		assertFalse(registeredPages().isEmpty(),
				"no "+ENTRY+" page keys found in ClientProxy -- either the chapter was removed or "
						+"this test can no longer find it, and both are worth knowing about");
	}

	@Test
	@DisplayName("the chapter has a title and a subtitle")
	void theChapterIsNamed()
	{
		Properties lang = lang();
		for(String suffix : new String[]{".name", ".subtext"})
		{
			String key = "ie.manual.entry."+ENTRY+suffix;
			assertTrue(lang.containsKey(key), "missing "+key);
			assertFalse(lang.getProperty(key).trim().isEmpty(), key+" is empty");
		}
	}

	/**
	 * The regression this class exists for.
	 */
	@Test
	@DisplayName("every page registered in ClientProxy has text in en_us.lang")
	void everyRegisteredPageHasText()
	{
		Properties lang = lang();
		for(String page : registeredPages())
		{
			String key = "ie.manual.entry."+page;
			assertTrue(lang.containsKey(key),
					"missing manual page "+key+" -- it is registered in ClientProxy, so the book "
							+"will show the raw key");
			assertFalse(lang.getProperty(key).trim().isEmpty(), key+" is empty");
		}
	}

	@Test
	@DisplayName("no page is registered twice")
	void everyPageIsRegisteredOnce()
	{
		List<String> pages = registeredPages();
		Set<String> unique = new LinkedHashSet<>(pages);
		assertEquals(pages.size(), unique.size(),
				"a page key is registered more than once: "+pages);
	}

	/**
	 * Numbering is the only thing tying a page to its text, so a gap in it is always either a
	 * page that was dropped without its text or text that was written for a page that never got
	 * registered.
	 */
	@Test
	@DisplayName("page numbers run from zero without a gap")
	void pageNumbersAreContiguous()
	{
		List<String> pages = registeredPages();
		for(int i = 0; i < pages.size(); i++)
			assertEquals(ENTRY+i, pages.get(i),
					"page "+i+" of the chapter is "+pages.get(i)+"; numbering must run from 0 in "
							+"registration order");
	}

	/**
	 * The other direction: text sitting in the lang file for a page nobody registers is invisible
	 * in game, which usually means a page was deleted from the chapter and its text left behind.
	 */
	@Test
	@DisplayName("no page text is left orphaned in the lang file")
	void everyPageTextIsRegistered()
	{
		Set<String> registered = new LinkedHashSet<>(registeredPages());
		Pattern key = Pattern.compile("^ie\\.manual\\.entry\\.("+ENTRY+"\\d+)=");
		for(String line : source(LANG).split("\n"))
		{
			Matcher matcher = key.matcher(line.trim());
			if(matcher.find())
				assertTrue(registered.contains(matcher.group(1)),
						matcher.group(1)+" has text but is registered by nobody, so it can never "
								+"be read in game");
		}
	}
}
