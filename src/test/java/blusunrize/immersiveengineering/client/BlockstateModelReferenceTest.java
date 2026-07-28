/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every model a blockstate names, across the whole mod, resolves to a file that exists.
 * <p>
 * <strong>Written after shipping a feature where none of them did.</strong> The conduit's
 * blockstates referred to {@code immersiveengineering:block/conduit/...}, which reads as the
 * obviously-correct path and is wrong: the loader prepends {@code models/block/} itself, so every
 * reference resolved to {@code models/block/block/conduit/...}. Every conduit and every junction
 * box was purple, in the world and in the inventory, and <em>nothing was logged</em>.
 * <p>
 * The feature's own asset test did not catch it, because that test applied the same wrong rule as
 * the code it was checking -- a self-consistent pair that agreed with each other and not with
 * Minecraft. This one is deliberately mod-wide and knows only the loader's rule, so a new
 * blockstate written next year gets checked whether or not anybody remembers to write a test for
 * it.
 * <p>
 * The two silent ways to earn a purple block in 1.12 are a blockstate naming a model nobody wrote
 * and a custom state mapping with no matching file. This is the first of them, everywhere.
 */
class BlockstateModelReferenceTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";

	/**
	 * Models that come with Minecraft rather than with the mod, so there is no file here to find.
	 * A reference with no namespace is vanilla's by definition.
	 */
	private static boolean isVanilla(String reference)
	{
		return !reference.contains(":")||reference.startsWith("minecraft:");
	}

	private static void collectModels(JsonElement node, List<String> out)
	{
		if(node.isJsonObject())
		{
			for(Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet())
			{
				if("model".equals(entry.getKey())&&entry.getValue().isJsonPrimitive())
					out.add(entry.getValue().getAsString());
				else
					collectModels(entry.getValue(), out);
			}
		}
		else if(node.isJsonArray())
			for(JsonElement child : node.getAsJsonArray())
				collectModels(child, out);
	}

	@Test
	@DisplayName("no blockstate writes out the models/block/ prefix the loader adds itself")
	void noBlockstateDoublesThePrefix()
	{
		List<String> offenders = new ArrayList<>();
		for(File file : blockstates())
			for(String model : modelsIn(file))
			{
				if(isVanilla(model))
					continue;
				String path = model.substring(model.indexOf(':')+1);
				if(path.startsWith("block/"))
					offenders.add(file.getName()+": "+model);
			}
		assertTrue(offenders.isEmpty(),
				"these resolve to models/block/block/... and render as purple blocks with nothing "
						+"in the log: "+offenders);
	}

	/**
	 * Only plain JSON models are checked for existence.
	 * <p>
	 * IE also names OBJ models and two custom loaders, and those do not resolve to a
	 * {@code .json} file: {@code smartmodel/...} is IE's own loader and has no file at all, and
	 * anything under another namespace ({@code forge:multi-layer}, {@code forge:fluid}) belongs to
	 * somebody else. An OBJ reference <em>does</em> name a real file, so it is checked as itself
	 * rather than skipped.
	 */
	private static boolean modelExists(String reference)
	{
		String path = reference.substring(reference.indexOf(':')+1);
		if(path.startsWith("smartmodel/"))
			return true;
		if(path.endsWith(".obj.ie")||path.endsWith(".mtl"))
			return new File(ASSETS+"models/block/"+path).isFile();
		if(path.endsWith(".obj"))
			//IE's loader takes a plain .obj reference and accepts an .obj.ie file for it -- the
			//Auto Workbench is referenced as workbench.obj and shipped as workbench.obj.ie. Either
			//spelling on disk satisfies a .obj reference.
			return new File(ASSETS+"models/block/"+path).isFile()
					||new File(ASSETS+"models/block/"+path+".ie").isFile();
		return new File(ASSETS+"models/block/"+path+".json").isFile();
	}

	@Test
	@DisplayName("every model a blockstate names exists on disk")
	void everyReferencedModelExists()
	{
		List<String> missing = new ArrayList<>();
		for(File file : blockstates())
			for(String model : modelsIn(file))
			{
				//Another namespace is another mod's loader; there is nothing here to look for.
				if(isVanilla(model)||!model.startsWith("immersiveengineering:"))
					continue;
				if(!modelExists(model))
					missing.add(file.getName()+": "+model);
			}
		assertTrue(missing.isEmpty(), "blockstates naming models nobody wrote: "+missing);
	}

	@Test
	@DisplayName("every blockstate parses at all")
	void everyBlockstateParses()
	{
		//A file that will not parse takes every block it describes with it, and Forge reports it
		//once at startup in a log nobody is reading at the time.
		for(File file : blockstates())
			read(file);
	}

	private static File[] blockstates()
	{
		File dir = new File(ASSETS+"blockstates");
		assertTrue(dir.isDirectory(), "no blockstates directory at all");
		File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
		assertNotNull(files);
		assertTrue(files.length > 0, "no blockstates found -- rewrite this test");
		return files;
	}

	private static List<String> modelsIn(File file)
	{
		List<String> models = new ArrayList<>();
		collectModels(read(file), models);
		return models;
	}

	private static JsonObject read(File file)
	{
		try(FileReader reader = new FileReader(file))
		{
			return new JsonParser().parse(reader).getAsJsonObject();
		} catch(IOException|JsonParseException|IllegalStateException e)
		{
			throw new AssertionError("could not parse "+file.getPath(), e);
		}
	}
}
