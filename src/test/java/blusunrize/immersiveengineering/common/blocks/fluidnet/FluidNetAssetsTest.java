/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

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
 * Validates the fluid network's resource files.
 * <p>
 * None of this fails the build on its own. A broken blockstate is a purple-and-black block; Forge
 * parses every key under {@code variants} as a property name, so one stray {@code __comment} there
 * makes the <em>whole file</em> fail to load and takes every block it describes with it. A recipe
 * pointing at a metadata no enum defines produces a broken item in an otherwise green build. A
 * missing recipe makes a block creative-only and says nothing.
 * <p>
 * <strong>All three of those have shipped in this fork already</strong> -- the propane cylinder at
 * meta 5, the flare stack with no recipe at all, and two more missing recipes caught during the
 * forecourt work. This is the same guard the petroleum feature has, pointed at the fluid network.
 */
class FluidNetAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";
	private static final String[] BLOCKSTATES = {"fluidnet_device", "fluidnet_multiblock"};

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

	private static JsonObject blockstate(String name)
	{
		return read("blockstates/"+name+".json");
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
		@DisplayName("every blockstate parses and uses the forge marker")
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
		@DisplayName("fluidnet_device covers every meta it declares")
		void deviceCoversItsMetas()
		{
			Set<String> expected = new HashSet<>();
			for(BlockTypes_FluidNetDevice type : BlockTypes_FluidNetDevice.values())
				expected.add(type.getName());
			assertEquals(expected,
					keys(blockstate("fluidnet_device").getAsJsonObject("variants").getAsJsonObject("type")));
		}

		@Test
		@DisplayName("fluidnet_multiblock covers every meta it declares")
		void multiblockCoversItsMetas()
		{
			Set<String> expected = new HashSet<>();
			for(BlockTypes_FluidNetMultiblock type : BlockTypes_FluidNetMultiblock.values())
				expected.add(type.getName());
			assertEquals(expected,
					keys(blockstate("fluidnet_multiblock").getAsJsonObject("variants").getAsJsonObject("type")));
		}

		@Test
		@DisplayName("every meta has an inventory variant so its item renders")
		void everyMetaHasAnInventoryVariant()
		{
			//Without one the item in hand and in JEI renders as the missing model.
			Set<String> deviceVariants = keys(blockstate("fluidnet_device").getAsJsonObject("variants"));
			for(BlockTypes_FluidNetDevice type : BlockTypes_FluidNetDevice.values())
				assertTrue(deviceVariants.contains("inventory,type="+type.getName()),
						"fluidnet_device has no inventory variant for "+type.getName());

			Set<String> mbVariants = keys(blockstate("fluidnet_multiblock").getAsJsonObject("variants"));
			for(BlockTypes_FluidNetMultiblock type : BlockTypes_FluidNetMultiblock.values())
				assertTrue(mbVariants.contains("inventory,type="+type.getName()),
						"fluidnet_multiblock has no inventory variant for "+type.getName());
		}

		@Test
		@DisplayName("the wall-mounted fittings declare all six facings")
		void facingsAreComplete()
		{
			//These bolt to any surface including ceilings and floors, so a missing facing is a
			//block that renders wrong only when placed one particular way.
			Set<String> facings = keys(blockstate("fluidnet_device").getAsJsonObject("variants")
					.getAsJsonObject("facing"));
			assertEquals(new HashSet<>(Arrays.asList("north", "east", "south", "west", "up", "down")),
					facings);
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
			assertFalse(textures.isEmpty(), "no textures referenced at all -- did the file change shape?");
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
	}

	@Nested
	@DisplayName("texture artwork")
	class Artwork
	{
		private List<File> netTextures()
		{
			File dir = new File(ASSETS+"textures/blocks");
			File[] all = dir.listFiles();
			assertNotNull(all, "no block texture directory");
			List<File> out = new ArrayList<>();
			for(File file : all)
				if(file.getName().startsWith("fluidnet_")&&file.getName().endsWith(".png"))
					out.add(file);
			assertFalse(out.isEmpty(), "no fluid network textures found");
			return out;
		}

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

		/**
		 * The console's display is one screen across two blocks, and the halves have to join
		 * without a seam.
		 * <p>
		 * The mirror of the grid console's test, because this console is the grid console's mirror:
		 * it had the identical bug -- the whole screen painted on both upper blocks, so the pair
		 * drew as two terminals with a border down the middle -- and it gets the identical fix. If
		 * one of these two tests is ever deleted, the other is covering a feature that no longer
		 * exists in the same shape.
		 */
		@Test
		@DisplayName("the two screen halves glue back into one bordered display")
		void seamedScreenHalvesFormOneDisplay()
		{
			BufferedImage left = load(new File(ASSETS+"textures/blocks/fluidnet_console_screen_left.png"));
			BufferedImage right = load(new File(ASSETS+"textures/blocks/fluidnet_console_screen_right.png"));
			assertEquals(left.getWidth(), right.getWidth(), "the halves are different widths");
			assertEquals(left.getHeight(), right.getHeight(), "the halves have different frame counts");

			int w = left.getWidth(), h = left.getHeight();
			Set<Integer> ring = new HashSet<>();
			for(int x = 0; x < w; x++)
			{
				ring.add(left.getRGB(x, 0));
				ring.add(left.getRGB(x, h-1));
				ring.add(right.getRGB(x, 0));
				ring.add(right.getRGB(x, h-1));
			}
			//Outer edges only. The two columns either side of the seam are meant to be glass.
			for(int y = 0; y < h; y++)
			{
				ring.add(left.getRGB(0, y));
				ring.add(right.getRGB(w-1, y));
			}
			assertEquals(1, ring.size(),
					"the reassembled display does not have a single-colour border: found "+ring.size()
							+" colours around its outside");

			int frame = ring.iterator().next();
			boolean seamIsClear = false;
			for(int y = 1; y < h-1; y++)
				if(left.getRGB(w-1, y)!=frame||right.getRGB(0, y)!=frame)
					seamIsClear = true;
			assertTrue(seamIsClear,
					"both columns either side of the seam are the frame colour, so the display still "
							+"draws with a line down its middle");
		}

		/**
		 * Width is always 16 -- these are block faces -- but height is allowed to be
		 * any whole multiple of that: MC's animated sprite format stacks square frames
		 * top to bottom in one PNG, so a taller texture is only wrong if its height does
		 * not divide evenly into 16px frames, which is the one shape MC cannot slice.
		 */
		@Test
		@DisplayName("every texture is 16 wide and a whole number of 16px frames tall")
		void texturesAreBlockSized()
		{
			for(File file : netTextures())
			{
				BufferedImage image = load(file);
				assertEquals(16, image.getWidth(), file.getName());
				assertEquals(0, image.getHeight()%16,
						file.getName()+": height "+image.getHeight()+" is not a whole number of 16px frames");
			}
		}

		/**
		 * Regression: an animated sheet MC finds with no .mcmeta next to it does not
		 * fail to load -- it just squashes every stacked frame into one texture, which
		 * reads in-game as a mangled export, not a missing asset.
		 */
		@Test
		@DisplayName("every animated texture has a matching .mcmeta")
		void animatedTexturesHaveMcmeta()
		{
			for(File file : netTextures())
			{
				BufferedImage image = load(file);
				if(image.getHeight()!=image.getWidth())
					assertTrue(new File(file.getPath()+".mcmeta").isFile(),
							file.getName()+" is "+image.getWidth()+"x"+image.getHeight()
									+" but has no .mcmeta to tell MC how to slice it into frames");
			}
		}

		@Test
		@DisplayName("every texture is fully opaque")
		void texturesAreOpaque()
		{
			//These are solid hardware. A stray transparent pixel reads as a hole in the model.
			for(File file : netTextures())
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
		private String[] recipeFiles()
		{
			//The whole folder, not a hand-written list: a list goes stale the moment somebody adds
			//a recipe, which is exactly when the check is worth having.
			String[] files = new File(ASSETS+"recipes/fluidnet").list((dir, n) -> n.endsWith(".json"));
			assertNotNull(files, "the fluidnet recipe folder is missing");
			assertTrue(files.length > 0, "no fluid network recipes at all");
			Arrays.sort(files);
			return files;
		}

		private Properties lang()
		{
			Properties lang = new Properties();
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
		@DisplayName("every fitting is craftable")
		void everyDeviceIsObtainable()
		{
			for(BlockTypes_FluidNetDevice type : BlockTypes_FluidNetDevice.values())
				assertTrue(new File(ASSETS+"recipes/fluidnet/"+type.getName()+".json").isFile(),
						type.getName()+" has no recipe, so it can only be had in creative");
		}

		@Test
		@DisplayName("no recipe produces a metadata no enum defines")
		void recipesTargetCorrectMetas()
		{
			Set<Integer> deviceMetas = new HashSet<>();
			for(BlockTypes_FluidNetDevice type : BlockTypes_FluidNetDevice.values())
				deviceMetas.add(type.getMeta());
			Map<String, Set<Integer>> valid = new HashMap<>();
			valid.put("immersiveengineering:fluidnet_device", deviceMetas);

			for(String file : recipeFiles())
			{
				JsonObject result = read("recipes/fluidnet/"+file).getAsJsonObject("result");
				String item = result.get("item").getAsString();
				Set<Integer> allowed = valid.get(item);
				assertNotNull(allowed, file+" produces an unknown block: "+item);
				int data = result.has("data")?result.get("data").getAsInt(): 0;
				assertTrue(allowed.contains(data),
						file+" produces "+item+" meta "+data+", which no block enum defines");
			}
		}

		@Test
		@DisplayName("every recipe's key covers exactly the symbols its pattern uses")
		void recipeKeysMatchPattern()
		{
			//A symbol in the pattern with no key entry makes Forge drop the recipe silently.
			for(String file : recipeFiles())
			{
				JsonObject json = read("recipes/fluidnet/"+file);
				assertEquals(json.has("pattern"), json.has("key"),
						file+": a shaped recipe needs both a pattern and a key");
				if(!json.has("pattern"))
					continue;
				Set<String> used = new HashSet<>();
				for(JsonElement row : json.getAsJsonArray("pattern"))
					for(char c : row.getAsString().toCharArray())
						if(c!=' ')
							used.add(String.valueOf(c));
				assertEquals(used, keys(json.getAsJsonObject("key")),
						file+": pattern symbols and key entries disagree");
			}
		}

		@Test
		@DisplayName("every block meta has a name")
		void blocksAreNamed()
		{
			Properties lang = lang();
			for(BlockTypes_FluidNetDevice type : BlockTypes_FluidNetDevice.values())
				assertNotNull(lang.getProperty(
								"tile.immersiveengineering.fluidnet_device."+type.getName()+".name"),
						"unnamed: "+type.getName());
			for(BlockTypes_FluidNetMultiblock type : BlockTypes_FluidNetMultiblock.values())
				assertNotNull(lang.getProperty(
								"tile.immersiveengineering.fluidnet_multiblock."+type.getName()+".name"),
						"unnamed: "+type.getName());
		}

		@Test
		@DisplayName("the manual chapter has a title, a subtitle and no gaps")
		void manualChapterIsComplete()
		{
			//A page whose lang key is missing renders the raw key at the reader, and the only way
			//to see it is to open the book at that exact page.
			Properties lang = lang();
			assertNotNull(lang.getProperty("ie.manual.entry.fluidNetwork.name"));
			assertNotNull(lang.getProperty("ie.manual.entry.fluidNetwork.subtext"));
			int page = 0;
			while(lang.getProperty("ie.manual.entry.fluidNetwork"+page)!=null)
				page++;
			assertTrue(page > 0, "the chapter has no pages at all");
			//And nothing beyond the run, which would be a page written but never registered.
			assertNull(lang.getProperty("ie.manual.entry.fluidNetwork"+(page+1)),
					"page "+(page+1)+" has text but page "+page+" does not -- a gap in the chapter");
		}

		@Test
		@DisplayName("the unformed-console hint exists, because the block is otherwise silent")
		void consoleHintExists()
		{
			assertNotNull(lang().getProperty("chat.immersiveengineering.info.fluidnet.consoleUnformed"),
					"a Console Housing right-clicked with anything but a hammer says nothing at all "
							+"without this");
		}
	}
}
