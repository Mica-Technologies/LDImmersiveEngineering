/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common;

import com.google.gson.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@code "#name"} ingredient in a recipe must name a constant declared in
 * {@code recipes/_constants.json}.
 * <p>
 * The failure mode this exists for is quiet and easy to ship: Forge logs
 * "Ingredient referenced invalid constant" once during load, carries on, and the recipe is
 * simply absent from the game. Nothing crashes and nothing else complains, so a typo in a
 * shorthand can survive a whole play-test. It is also an easy mistake to make, because the
 * shorthands look exactly like ore-dictionary names but are not -- writing one that happens to
 * be a real ore-dict entry, but was never declared as a constant, fails the same silent way.
 */
class RecipeConstantsTest
{
	private static final String RECIPES =
			"src/main/resources/assets/immersiveengineering/recipes/";

	private static JsonElement read(File file)
	{
		try(Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(reader);
		} catch(IOException|JsonParseException e)
		{
			throw new AssertionError("could not parse "+file, e);
		}
	}

	private static Set<String> declaredConstants()
	{
		Set<String> names = new HashSet<>();
		for(JsonElement entry : read(new File(RECIPES+"_constants.json")).getAsJsonArray())
			names.add(entry.getAsJsonObject().get("name").getAsString());
		return names;
	}

	private static void collectRecipes(File dir, List<File> into)
	{
		File[] children = dir.listFiles();
		if(children==null)
			return;
		for(File child : children)
			if(child.isDirectory())
				collectRecipes(child, into);
			else if(child.getName().endsWith(".json")&&!child.getName().startsWith("_"))
				into.add(child);
	}

	/**
	 * Walks every value in a recipe looking for {@code "item": "#..."}.
	 */
	private static void collectReferences(JsonElement element, Set<String> into)
	{
		if(element.isJsonObject())
		{
			for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				JsonElement value = entry.getValue();
				if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())
				{
					String text = value.getAsString();
					if(text.startsWith("#"))
						into.add(text.substring(1));
				}
				else
					collectReferences(value, into);
			}
		}
		else if(element.isJsonArray())
			for(JsonElement child : element.getAsJsonArray())
				collectReferences(child, into);
	}

	@Test
	@DisplayName("every recipe shorthand names a declared constant")
	void allShorthandsAreDeclared()
	{
		Set<String> declared = declaredConstants();
		assertFalse(declared.isEmpty(), "no constants were parsed at all");

		List<File> recipes = new ArrayList<>();
		collectRecipes(new File(RECIPES), recipes);
		assertFalse(recipes.isEmpty(), "no recipes were found to check");

		List<String> problems = new ArrayList<>();
		for(File recipe : recipes)
		{
			Set<String> referenced = new TreeSet<>();
			collectReferences(read(recipe), referenced);
			for(String name : referenced)
				if(!declared.contains(name))
					problems.add(recipe.getPath()+" references undeclared constant \"#"+name+"\"");
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	/**
	 * A name declared twice with <em>different</em> ingredients is a real bug: the later
	 * declaration silently wins, so half the recipes using that shorthand get an ingredient
	 * their author did not intend, and nothing reports it.
	 * <p>
	 * An exact duplicate is merely dead data. This file comes from upstream, so the test
	 * deliberately tolerates those rather than inviting a merge conflict over a line that
	 * changes nothing -- there is one such pair today (`blockSheetmetalIron`).
	 */
	@Test
	@DisplayName("no constant is declared twice with a different meaning")
	void constantsDoNotConflict()
	{
		Map<String, String> seen = new HashMap<>();
		List<String> conflicts = new ArrayList<>();
		for(JsonElement entry : read(new File(RECIPES+"_constants.json")).getAsJsonArray())
		{
			JsonObject object = entry.getAsJsonObject();
			String name = object.get("name").getAsString();
			String ingredient = String.valueOf(object.get("ingredient"));
			String previous = seen.put(name, ingredient);
			if(previous!=null&&!previous.equals(ingredient))
				conflicts.add(name+": "+previous+" vs "+ingredient);
		}
		assertTrue(conflicts.isEmpty(), "conflicting constants:\n"+String.join("\n", conflicts));
	}
}
