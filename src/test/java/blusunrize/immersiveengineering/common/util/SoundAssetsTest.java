/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every sound the mod registers has a file, an entry and a subtitle, mod-wide.
 * <p>
 * <strong>A sound is the quietest thing in Minecraft to get wrong.</strong> A {@code SoundEvent}
 * registered with no entry in {@code sounds.json}, or an entry naming an ogg nobody wrote, does not
 * throw and does not log at any level anybody reads: the game simply plays nothing. The symptom is
 * a machine that is silent, which is indistinguishable from a machine whose sounds were never
 * written -- and from a machine whose sounds are working and whose volume is turned down.
 * <p>
 * So this checks the whole chain, in both directions, for every sound in the mod rather than only
 * for the ones added here: the Java constant, the entry, the file on disk, and the subtitle key a
 * player with subtitles on will otherwise see as raw translation text across the bottom of their
 * screen.
 */
class SoundAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";
	private static final String SOUNDS_JSON = ASSETS+"sounds.json";
	private static final String SOUNDS_DIR = ASSETS+"sounds/";
	private static final String LANG = ASSETS+"lang/en_us.lang";
	private static final String REGISTRY =
			"src/main/java/blusunrize/immersiveengineering/common/util/IESounds.java";

	private static String read(String path)
	{
		try
		{
			//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files
			//CRLF, and every pattern below is written with the bare newline the repository stores.
			return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
					.replace("\r\n", "\n");
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+path, e);
		}
	}

	private static JsonObject soundsJson()
	{
		try(FileReader reader = new FileReader(new File(SOUNDS_JSON)))
		{
			return new JsonParser().parse(reader).getAsJsonObject();
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+SOUNDS_JSON, e);
		}
	}

	/** @return every name passed to {@code registerSound} in {@link IESounds} */
	private static Set<String> registered()
	{
		Set<String> names = new LinkedHashSet<>();
		Matcher m = Pattern.compile("registerSound\\(\"([^\"]+)\"\\)").matcher(read(REGISTRY));
		while(m.find())
			names.add(m.group(1));
		assertFalse(names.isEmpty(), "no sounds are registered at all; IESounds has been rewritten "
				+"in some way this test can no longer read");
		return names;
	}

	/** @return every line of Java the mod is built from, run together */
	private static String allSource()
	{
		StringBuilder all = new StringBuilder();
		try
		{
			Files.walk(Paths.get("src/main/java"))
					.filter(path -> path.toString().endsWith(".java"))
					.forEach(path -> all.append(read(path.toString())));
		} catch(IOException e)
		{
			throw new AssertionError("could not walk the source tree", e);
		}
		assertTrue(all.length() > 0, "the source tree is empty; this test is reading the wrong place");
		return all.toString();
	}

	@Nested
	@DisplayName("sounds.json")
	class Entries
	{
		@Test
		@DisplayName("every sound it names is a file that exists")
		void everySoundHasAFile()
		{
			for(Map.Entry<String, JsonElement> entry : soundsJson().entrySet())
				for(JsonElement sound : entry.getValue().getAsJsonObject().getAsJsonArray("sounds"))
				{
					//Either a bare name or an object with "name" -- both are legal, and a generator
					//that started emitting the second form should not silently stop being checked.
					String name = sound.isJsonObject()
							?sound.getAsJsonObject().get("name").getAsString()
							: sound.getAsString();
					//The loader prepends sounds/ and appends .ogg itself, exactly as it prepends
					//models/block/ for a blockstate -- the mistake that made every conduit purple.
					String file = name.substring(name.indexOf(':')+1);
					assertTrue(new File(SOUNDS_DIR+file+".ogg").isFile(),
							"\""+entry.getKey()+"\" names "+name+", and "+SOUNDS_DIR+file+".ogg "
									+"does not exist -- so it plays nothing, silently");
				}
		}

		@Test
		@DisplayName("every registered sound event has an entry")
		void everyEventHasAnEntry()
		{
			JsonObject json = soundsJson();
			for(String name : registered())
				assertTrue(json.has(name),
						"IESounds registers \""+name+"\" and sounds.json has no entry for it, so "
								+"every call that plays it does nothing");
		}

		@Test
		@DisplayName("nothing is declared that nothing plays")
		void noStrandedEntries()
		{
			//The other direction, which is not a bug so much as a file nobody will ever hear: an
			//entry with no event behind it ships an ogg in the jar that no code can reach.
			//
			//A registered SoundEvent is the usual way to reach one, and it is not the only way: the
			//Skyhook builds its ResourceLocation by hand, because what it plays is a repeating stream
			//rather than an event fired at a position. So the second half of this looks for the name
			//spelled out anywhere in the mod's source, which is what both routes have in common.
			Set<String> events = registered();
			String source = allSource();
			for(Map.Entry<String, JsonElement> entry : soundsJson().entrySet())
				assertTrue(events.contains(entry.getKey())||source.contains('"'+entry.getKey()+'"'),
						"sounds.json declares \""+entry.getKey()+"\", and nothing in the mod either "
								+"registers it as a sound event or names it, so nothing can play it");
		}

		@Test
		@DisplayName("every subtitle it names is translated")
		void everySubtitleIsTranslated()
		{
			//Untranslated, a subtitle is displayed as its own key: a player who has subtitles turned
			//on -- which is a good many of them -- reads "subtitle.immersiveengineering.crawlerEngine"
			//across the bottom of the screen instead of what the machine is doing.
			String lang = read(LANG);
			for(Map.Entry<String, JsonElement> entry : soundsJson().entrySet())
			{
				JsonElement subtitle = entry.getValue().getAsJsonObject().get("subtitle");
				if(subtitle==null)
					continue;
				String key = subtitle.getAsString();
				assertTrue(lang.contains("\n"+key+"=")||lang.startsWith(key+"="),
						"\""+entry.getKey()+"\" names the subtitle "+key+", which en_us.lang does "
								+"not translate");
			}
		}
	}
}
