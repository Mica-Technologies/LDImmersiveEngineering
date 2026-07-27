/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import com.google.gson.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the petroleum feature's resource files.
 * <p>
 * A broken blockstate does not fail the build -- it fails silently at runtime as a
 * purple-and-black block. Forge parses every key under {@code variants} as a property name,
 * so a stray {@code __comment} there makes the <em>whole file</em> fail to load and takes
 * every block it describes with it. The same class of mistake in a recipe just quietly
 * removes an item from the game.
 */
class PetroleumAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";

	private static final String[] BLOCKSTATES = {"petroleum_device", "petroleum_multiblock"};

	private static JsonObject read(String relativePath)
	{
		File file = new File(ASSETS+relativePath);
		assertTrue(file.isFile(), "missing resource: "+file.getPath());
		try(FileReader reader = new FileReader(file))
		{
			return new JsonParser().parse(reader).getAsJsonObject();
		} catch(IOException|JsonParseException e)
		{
			throw new AssertionError("could not parse "+file.getPath(), e);
		}
	}

	private static Set<String> keys(JsonObject object)
	{
		Set<String> out = new HashSet<>();
		for(Map.Entry<String, JsonElement> entry : object.entrySet())
			out.add(entry.getKey());
		return out;
	}

	private static void collectRefs(JsonElement element, Set<String> models, Set<String> textures)
	{
		if(element.isJsonObject())
		{
			for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				if("model".equals(entry.getKey())&&entry.getValue().isJsonPrimitive())
					models.add(entry.getValue().getAsString());
				else if("textures".equals(entry.getKey())&&entry.getValue().isJsonObject())
					for(Map.Entry<String, JsonElement> tex : entry.getValue().getAsJsonObject().entrySet())
					{
						String value = tex.getValue().getAsString();
						//"#stack" style references point at another key, not a file.
						if(!value.startsWith("#"))
							textures.add(value);
					}
				else
					collectRefs(entry.getValue(), models, textures);
			}
		}
		else if(element.isJsonArray())
			for(JsonElement child : element.getAsJsonArray())
				collectRefs(child, models, textures);
	}

	@Nested
	@DisplayName("blockstate structure")
	class Structure
	{
		@Test
		@DisplayName("every petroleum blockstate parses and uses the forge marker")
		void parsesWithForgeMarker()
		{
			for(String name : BLOCKSTATES)
			{
				JsonObject json = blockstate(name);
				assertTrue(json.has("forge_marker"), name+" is missing forge_marker");
				assertEquals(1, json.get("forge_marker").getAsInt(), name);
			}
		}

		/**
		 * The regression this whole class exists for.
		 */
		@Test
		@DisplayName("no comment keys survive anywhere under variants")
		void noCommentsUnderVariants()
		{
			for(String name : BLOCKSTATES)
			{
				Deque<JsonElement> queue = new ArrayDeque<>();
				queue.add(blockstate(name).getAsJsonObject("variants"));
				while(!queue.isEmpty())
				{
					JsonElement element = queue.poll();
					if(element.isJsonObject())
						for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
						{
							assertFalse(entry.getKey().startsWith("__"),
									name+": \""+entry.getKey()+"\" under variants would make the "
											+"whole file fail to load");
							queue.add(entry.getValue());
						}
					else if(element.isJsonArray())
						for(JsonElement child : element.getAsJsonArray())
							queue.add(child);
				}
			}
		}

		@Test
		@DisplayName("petroleum_device covers every meta it declares")
		void deviceCoversItsMetas()
		{
			Set<String> expected = new HashSet<>();
			for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
				expected.add(type.getName());
			assertEquals(expected,
					keys(blockstate("petroleum_device").getAsJsonObject("variants")
							.getAsJsonObject("type")));
		}

		@Test
		@DisplayName("petroleum_multiblock covers every meta it declares")
		void multiblockCoversItsMetas()
		{
			Set<String> expected = new HashSet<>();
			for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
				expected.add(type.getName());
			assertEquals(expected,
					keys(blockstate("petroleum_multiblock").getAsJsonObject("variants")
							.getAsJsonObject("type")));
		}

		@Test
		@DisplayName("every meta has an inventory variant so its item renders")
		void everyMetaHasAnInventoryVariant()
		{
			//Without one the item in hand and in JEI renders as the missing model.
			Set<String> deviceVariants = keys(blockstate("petroleum_device").getAsJsonObject("variants"));
			for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
				assertTrue(deviceVariants.contains("inventory,type="+type.getName()),
						"petroleum_device has no inventory variant for "+type.getName());

			Set<String> mbVariants = keys(blockstate("petroleum_multiblock").getAsJsonObject("variants"));
			for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
				assertTrue(mbVariants.contains("inventory,type="+type.getName()),
						"petroleum_multiblock has no inventory variant for "+type.getName());
		}
	}

	@Nested
	@DisplayName("referenced files")
	class References
	{
		@Test
		@DisplayName("every referenced texture exists on disk")
		void texturesExist()
		{
			Set<String> models = new HashSet<>();
			Set<String> textures = new HashSet<>();
			for(String name : BLOCKSTATES)
				collectRefs(blockstate(name), models, textures);
			for(String texture : textures)
			{
				if(!texture.startsWith("immersiveengineering:"))
					continue;
				File file = new File(ASSETS+"textures/"+texture.split(":", 2)[1]+".png");
				assertTrue(file.isFile(), "missing texture: "+file.getPath());
			}
		}

		@Test
		@DisplayName("every referenced IE model exists on disk")
		void modelsExist()
		{
			Set<String> models = new HashSet<>();
			Set<String> textures = new HashSet<>();
			for(String name : BLOCKSTATES)
				collectRefs(blockstate(name), models, textures);
			for(String model : models)
			{
				if(!model.startsWith("immersiveengineering:"))
					continue;
				File file = new File(ASSETS+"models/block/"+model.split(":", 2)[1]+".json");
				assertTrue(file.isFile(), "missing model: "+file.getPath());
			}
		}

		@Test
		@DisplayName("the wellhead model references only textures it declares")
		void modelTexturesResolve()
		{
			JsonObject model = read("models/block/petroleum/wellhead.json");
			Set<String> declared = keys(model.getAsJsonObject("textures"));
			for(JsonElement element : model.getAsJsonArray("elements"))
				for(Map.Entry<String, JsonElement> face :
						element.getAsJsonObject().getAsJsonObject("faces").entrySet())
				{
					String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
					assertTrue(texture.startsWith("#"), "face texture should be a reference: "+texture);
					String key = texture.substring(1);
					//"#stack" is supplied by the blockstate rather than the model, so it counts
					//as declared even though the model itself does not define it.
					assertTrue(declared.contains(key)||"stack".equals(key),
							"model references undeclared texture key \""+texture+"\"");
				}
		}
	}

	@Nested
	@DisplayName("texture artwork")
	class Artwork
	{
		private BufferedImage load(File file)
		{
			try
			{
				BufferedImage image = javax.imageio.ImageIO.read(file);
				assertNotNull(image, "could not decode "+file.getPath());
				return image;
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+file.getPath(), e);
			}
		}

		private List<File> petroleumTextures()
		{
			File dir = new File(ASSETS+"textures/blocks");
			File[] all = dir.listFiles();
			assertNotNull(all, "no block texture directory");
			List<File> out = new ArrayList<>();
			for(File file : all)
				if(file.getName().startsWith("petroleum_")&&file.getName().endsWith(".png"))
					out.add(file);
			assertFalse(out.isEmpty(), "no petroleum textures found");
			return out;
		}

		@Test
		@DisplayName("every petroleum block texture is 16x16")
		void texturesAreBlockSized()
		{
			for(File file : petroleumTextures())
			{
				BufferedImage image = load(file);
				assertEquals(16, image.getWidth(), file.getName());
				assertEquals(16, image.getHeight(), file.getName());
			}
		}

		@Test
		@DisplayName("every petroleum block texture is fully opaque")
		void texturesAreOpaque()
		{
			//These are solid machinery. A stray transparent pixel reads as a hole in the model.
			for(File file : petroleumTextures())
			{
				BufferedImage image = load(file);
				for(int x = 0; x < image.getWidth(); x++)
					for(int y = 0; y < image.getHeight(); y++)
						assertEquals(255, image.getRGB(x, y) >>> 24,
								file.getName()+" has a transparent pixel at "+x+","+y);
			}
		}
	}

	@Nested
	@DisplayName("recipes and localisation")
	class RecipesAndLang
	{
		private java.util.Properties lang()
		{
			java.util.Properties lang = new java.util.Properties();
			try(Reader reader = new InputStreamReader(
					new FileInputStream(ASSETS+"lang/en_us.lang"), StandardCharsets.UTF_8))
			{
				lang.load(reader);
			} catch(IOException e)
			{
				throw new AssertionError("could not read en_us.lang", e);
			}
			return lang;
		}

		@Test
		@DisplayName("every block meta has a name")
		void blocksAreNamed()
		{
			java.util.Properties lang = lang();
			for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
				assertTrue(lang.containsKey("tile.immersiveengineering.petroleum_device."
						+type.getName()+".name"), "unnamed device meta: "+type.getName());
			for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
				assertTrue(lang.containsKey("tile.immersiveengineering.petroleum_multiblock."
						+type.getName()+".name"), "unnamed multiblock meta: "+type.getName());
		}

		@Test
		@DisplayName("petroleum recipes parse and target the right block and meta")
		void recipesTargetCorrectMetas()
		{
			Object[][] expected = {
					{"oilfield_frame", BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()}
			};
			for(Object[] row : expected)
			{
				JsonObject result = read("recipes/petroleum/"+row[0]+".json").getAsJsonObject("result");
				assertEquals("immersiveengineering:petroleum_device",
						result.get("item").getAsString(), row[0].toString());
				assertEquals(row[1], result.get("data").getAsInt(),
						row[0]+" targets the wrong meta");
			}
		}

		@Test
		@DisplayName("the wellhead has no crafting recipe")
		void wellheadIsNotCraftable()
		{
			//A wellhead is what drilling leaves behind. If it could simply be crafted the
			//Drilling Derrick would have no purpose at all, since a wellhead works on any
			//surveyed chunk on its own.
			assertFalse(new File(ASSETS+"recipes/petroleum/wellhead.json").exists(),
					"a craftable wellhead would make the derrick pointless");
		}

		@Test
		@DisplayName("recipe keys and pattern symbols match exactly")
		void recipeKeysMatchPattern()
		{
			for(String name : new String[]{"oilfield_frame"})
			{
				JsonObject json = read("recipes/petroleum/"+name+".json");
				Set<String> recipeKeys = keys(json.getAsJsonObject("key"));
				Set<String> used = new HashSet<>();
				for(JsonElement row : json.getAsJsonArray("pattern"))
					for(char c : row.getAsString().toCharArray())
						if(c!=' ')
							used.add(String.valueOf(c));
				assertEquals(recipeKeys, used,
						name+": pattern symbols and key entries must match exactly");
			}
		}
	}

	private static JsonObject blockstate(String name)
	{
		return read("blockstates/"+name+".json");
	}
}
