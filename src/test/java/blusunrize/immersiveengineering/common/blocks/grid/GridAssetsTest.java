/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import com.google.gson.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the virtual grid's resource files.
 * <p>
 * These exist because a broken blockstate does not fail the build -- it fails silently at
 * runtime as a purple-and-black block, which is exactly what happened when a {@code
 * __comment} key was left inside a {@code variants} block. Forge parses every key under
 * {@code variants} as a property name and every key under that as a variant value, so a
 * stray string there makes the <em>whole file</em> fail to load and takes every block it
 * describes with it.
 */
class GridAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";

	private static final String[] BLOCKSTATES = {"grid_device", "grid_multiblock"};

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

	/**
	 * Minecraft 1.12 ships Gson 2.2.4, which predates {@code JsonObject.keySet()}.
	 */
	private static Set<String> keys(JsonObject object)
	{
		Set<String> out = new HashSet<>();
		for(Map.Entry<String, JsonElement> entry : object.entrySet())
			out.add(entry.getKey());
		return out;
	}

	/**
	 * Collects every {@code "model"} and {@code "textures"} value anywhere in a tree.
	 */
	private static void collectRefs(JsonElement element, Set<String> models, Set<String> textures)
	{
		if(element.isJsonObject())
		{
			JsonObject object = element.getAsJsonObject();
			for(Map.Entry<String, JsonElement> entry : object.entrySet())
			{
				if("model".equals(entry.getKey())&&entry.getValue().isJsonPrimitive())
					models.add(entry.getValue().getAsString());
				else if("textures".equals(entry.getKey())&&entry.getValue().isJsonObject())
					for(Map.Entry<String, JsonElement> tex : entry.getValue().getAsJsonObject().entrySet())
					{
						String value = tex.getValue().getAsString();
						//"#side" style references point at another key, not a file.
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
		@DisplayName("every grid blockstate parses")
		void blockstatesParse()
		{
			for(String name : BLOCKSTATES)
				assertNotNull(blockstate(name), name);
		}

		@Test
		@DisplayName("every grid blockstate uses the forge marker")
		void usesForgeMarker()
		{
			for(String name : BLOCKSTATES)
				assertEquals(1, blockstate(name).get("forge_marker").getAsInt(), name);
		}

		/**
		 * The regression this whole class exists for.
		 */
		@Test
		@DisplayName("no comment keys survive anywhere under variants")
		void noCommentKeysUnderVariants()
		{
			for(String name : BLOCKSTATES)
			{
				JsonObject variants = blockstate(name).getAsJsonObject("variants");
				for(Map.Entry<String, JsonElement> property : variants.entrySet())
				{
					assertFalse(property.getKey().startsWith("__"),
							name+": \""+property.getKey()+"\" under variants is parsed as a "
									+"property name, so a comment here breaks the whole file");
					//An "inventory,..." entry is a variant definition; anything else is a
					//property whose children are its values.
					if(property.getKey().contains("="))
						continue;
					assertTrue(property.getValue().isJsonObject(),
							name+": property \""+property.getKey()+"\" must map to an object");
					for(Map.Entry<String, JsonElement> value : property.getValue().getAsJsonObject().entrySet())
					{
						assertFalse(value.getKey().startsWith("__"),
								name+": \""+property.getKey()+"."+value.getKey()
										+"\" is parsed as a variant value, not a comment");
						assertTrue(value.getValue().isJsonObject()||value.getValue().isJsonArray(),
								name+": variant \""+property.getKey()+"="+value.getKey()
										+"\" must be an object or array, was "+value.getValue());
					}
				}
			}
		}

		@Test
		@DisplayName("grid_device covers every meta and every facing")
		void gridDeviceCoversItsProperties()
		{
			//Taken from the enum rather than listed here, so adding a meta and forgetting its
			//blockstate variant fails immediately. Listing them in both places only meant this test
			//had to be hand-edited every time, which is exactly when somebody edits it to match the
			//bug rather than the intent.
			JsonObject variants = blockstate("grid_device").getAsJsonObject("variants");
			Set<String> expected = new HashSet<>();
			for(BlockTypes_GridDevice type : BlockTypes_GridDevice.values())
				expected.add(type.getName());
			assertEquals(expected, keys(variants.getAsJsonObject("type")));

			//The block declares IEProperties.FACING_ALL, so all six must resolve even though
			//the tile restricts placement to the horizontals.
			assertEquals(new HashSet<>(Arrays.asList("north", "east", "south", "west", "up", "down")),
					keys(variants.getAsJsonObject("facing")));
			assertEquals(new HashSet<>(Arrays.asList("true", "false")),
					keys(variants.getAsJsonObject("boolean0")));
		}

		@Test
		@DisplayName("grid_multiblock covers every property it declares")
		void gridMultiblockCoversItsProperties()
		{
			JsonObject variants = blockstate("grid_multiblock").getAsJsonObject("variants");
			Set<String> expectedTypes = new HashSet<>();
			for(BlockTypes_GridMultiblock type : BlockTypes_GridMultiblock.values())
				expectedTypes.add(type.getName());
			assertEquals(expectedTypes, keys(variants.getAsJsonObject("type")));
			//BlockIEMultiblock contributes FACING_HORIZONTAL and MULTIBLOCKSLAVE.
			assertEquals(new HashSet<>(Arrays.asList("north", "east", "south", "west")),
					keys(variants.getAsJsonObject("facing")));
			assertEquals(new HashSet<>(Arrays.asList("true", "false")),
					keys(variants.getAsJsonObject("_0multiblockslave")));
			assertEquals(new HashSet<>(Arrays.asList("true", "false")),
					keys(variants.getAsJsonObject("boolean0")));
		}

		/**
		 * Regression: the utility box model is authored with its decorated face on the
		 * SOUTH side (the side that points away from whatever it is bolted to). Vanilla's
		 * default block GUI transform shows a block's NORTH face -- that is why a furnace
		 * shows its front in the inventory -- so the item variant has to spin the model
		 * 180 degrees or the player sees the blank back of the box.
		 */
		@Test
		@DisplayName("the utility box item variants are turned to show their front")
		void utilityBoxItemsFaceTheViewer()
		{
			JsonObject variants = blockstate("grid_device").getAsJsonObject("variants");
			for(String type : new String[]{"feed_unit", "service_unit"})
			{
				JsonElement entry = variants.get("inventory,type="+type);
				JsonObject definition = entry.isJsonArray()
						?entry.getAsJsonArray().get(0).getAsJsonObject(): entry.getAsJsonObject();
				assertTrue(definition.has("y"), type+": item variant needs a rotation");
				assertEquals(180, definition.get("y").getAsInt(),
						type+": the model's front is on south, so the item must turn 180");
			}
		}

		/**
		 * Regression: these are the rotations that put the box's back against the block it
		 * is bolted to. Facing means "toward the mount" (see
		 * TileEntityGridDevice.getFacingLimitation), and the model is authored for NORTH.
		 */
		@Test
		@DisplayName("every facing maps to the rotation that puts the back against the mount")
		void facingRotationsMatchTheModel()
		{
			JsonObject facing = blockstate("grid_device").getAsJsonObject("variants")
					.getAsJsonObject("facing");
			assertFalse(facing.getAsJsonObject("north").has("y"), "north is the authored orientation");
			assertEquals(90, facing.getAsJsonObject("east").get("y").getAsInt());
			assertEquals(180, facing.getAsJsonObject("south").get("y").getAsInt());
			assertEquals(270, facing.getAsJsonObject("west").get("y").getAsInt());
			//A rotation of x:90 about the X axis carries -Z (the model's back) to -Y.
			assertEquals(90, facing.getAsJsonObject("down").get("x").getAsInt(),
					"mounted on the floor: the back must end up pointing down");
			assertEquals(270, facing.getAsJsonObject("up").get("x").getAsInt(),
					"mounted on the ceiling: the back must end up pointing up");
		}

		@Test
		@DisplayName("each block meta has an inventory variant so the item renders")
		void everyMetaHasAnInventoryVariant()
		{
			JsonObject deviceVariants = blockstate("grid_device").getAsJsonObject("variants");
			for(String type : new String[]{"feed_unit", "service_unit", "console_housing"})
				assertTrue(deviceVariants.has("inventory,type="+type),
						"grid_device is missing the inventory variant for "+type);
			assertTrue(blockstate("grid_multiblock").getAsJsonObject("variants").has("inventory,type=grid_console"));
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
			Set<String> models = new HashSet<>(), textures = new HashSet<>();
			for(String name : BLOCKSTATES)
				collectRefs(blockstate(name), models, textures);
			assertFalse(textures.isEmpty(), "the blockstates should reference some textures");

			for(String texture : textures)
			{
				assertTrue(texture.startsWith("immersiveengineering:"),
						"unexpected texture domain: "+texture);
				File file = new File(ASSETS+"textures/"+texture.split(":", 2)[1]+".png");
				assertTrue(file.isFile(), "missing texture: "+file.getPath());
			}
		}

		@Test
		@DisplayName("every referenced IE model exists on disk")
		void modelsExist()
		{
			Set<String> models = new HashSet<>(), textures = new HashSet<>();
			for(String name : BLOCKSTATES)
				collectRefs(blockstate(name), models, textures);

			for(String model : models)
			{
				if(!model.startsWith("immersiveengineering:"))
					continue;//vanilla, e.g. cube_all
				File file = new File(ASSETS+"models/block/"+model.split(":", 2)[1]+".json");
				assertTrue(file.isFile(), "missing model: "+file.getPath());
			}
		}

		@Test
		@DisplayName("grid models parse and reference only declared textures")
		void modelsParseAndResolveTextures()
		{
			for(String model : new String[]{"grid/utility_box", "grid/console_panel"})
			{
				JsonObject json = read("models/block/"+model+".json");
				Set<String> declared = new HashSet<>();
				if(json.has("textures"))
					declared.addAll(keys(json.getAsJsonObject("textures")));

				//Face texture references are "#name"; the name must either be declared here
				//or supplied by the blockstate's defaults.
				Set<String> fromBlockstates = new HashSet<>(
						Arrays.asList("side", "top", "front", "back", "all", "particle"));
				for(JsonElement element : json.getAsJsonArray("elements"))
					for(Map.Entry<String, JsonElement> face :
							element.getAsJsonObject().getAsJsonObject("faces").entrySet())
					{
						String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
						assertTrue(texture.startsWith("#"), model+": expected a #reference, got "+texture);
						String key = texture.substring(1);
						assertTrue(declared.contains(key)||fromBlockstates.contains(key),
								model+": face texture #"+key+" is never supplied");
					}
			}
		}
	}

	@Nested
	@DisplayName("texture artwork")
	class Artwork
	{
		private java.awt.image.BufferedImage load(File file)
		{
			try
			{
				java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file);
				assertNotNull(image, "unreadable png: "+file.getPath());
				return image;
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+file.getPath(), e);
			}
		}

		private File[] gridTextures()
		{
			File dir = new File(ASSETS+"textures/blocks");
			File[] files = dir.listFiles((d, n) -> n.startsWith("grid_")&&n.endsWith(".png"));
			assertNotNull(files, "no texture directory at "+dir.getPath());
			assertTrue(files.length > 0, "expected some grid textures");
			return files;
		}

		/**
		 * Block faces are always 16 wide, but height can be a multiple of that: MC's
		 * animated sprite format is just square frames stacked top to bottom in one PNG,
		 * so a texture taller than 16 is not a mistake by itself -- only a height that
		 * is not an exact multiple of the width is, because that leaves a partial frame
		 * with no sensible way to render it.
		 */
		@Test
		@DisplayName("every grid texture is 16 wide and a whole number of 16px frames tall")
		void texturesAreSixteenSquare()
		{
			for(File file : gridTextures())
			{
				java.awt.image.BufferedImage image = load(file);
				assertEquals(16, image.getWidth(), file.getName());
				assertEquals(0, image.getHeight()%16,
						file.getName()+": height "+image.getHeight()+" is not a whole number of 16px frames");
			}
		}

		/**
		 * Regression: an animated sheet with no sibling .mcmeta is not an error MC
		 * reports -- it is a block that renders as every frame squashed into one
		 * smeared texture, which looks like a broken export rather than a missing file.
		 */
		@Test
		@DisplayName("every animated grid texture has a matching .mcmeta")
		void animatedTexturesHaveMcmeta()
		{
			for(File file : gridTextures())
			{
				java.awt.image.BufferedImage image = load(file);
				if(image.getHeight()!=image.getWidth())
					assertTrue(new File(file.getPath()+".mcmeta").isFile(),
							file.getName()+" is "+image.getWidth()+"x"+image.getHeight()
									+" but has no .mcmeta to tell MC how to slice it into frames");
			}
		}

		/**
		 * Regression: the console panel's switch bank was laid out on a 4px pitch, which put
		 * the rightmost switch and lamp on top of the 1px frame. In game that reads as the
		 * decoration bleeding out of the block's edge.
		 */
		@Test
		@DisplayName("nothing is painted into the one-pixel border frame")
		void borderFrameIsUniform()
		{
			for(File file : gridTextures())
			{
				java.awt.image.BufferedImage image = load(file);
				int w = image.getWidth(), h = image.getHeight();
				Set<Integer> ring = new HashSet<>();
				for(int x = 0; x < w; x++)
				{
					ring.add(image.getRGB(x, 0));
					ring.add(image.getRGB(x, h-1));
				}
				for(int y = 0; y < h; y++)
				{
					ring.add(image.getRGB(0, y));
					ring.add(image.getRGB(w-1, y));
				}
				assertEquals(1, ring.size(),
						file.getName()+": the border must be a single frame colour, but the "
								+"outer ring has "+ring.size()+" colours -- something is "
								+"overflowing into the edge");
			}
		}

		@Test
		@DisplayName("every grid texture is fully opaque")
		void texturesAreOpaque()
		{
			for(File file : gridTextures())
			{
				java.awt.image.BufferedImage image = load(file);
				for(int x = 0; x < image.getWidth(); x++)
					for(int y = 0; y < image.getHeight(); y++)
						assertEquals(0xFF, (image.getRGB(x, y) >>> 24),
								file.getName()+": pixel "+x+","+y+" is not opaque");
			}
		}
	}

	@Nested
	@DisplayName("localisation")
	class Localisation
	{
		private java.util.Properties lang()
		{
			java.util.Properties lang = new java.util.Properties();
			try(java.io.Reader reader = new java.io.InputStreamReader(
					new java.io.FileInputStream(ASSETS+"lang/en_us.lang"),
					java.nio.charset.StandardCharsets.UTF_8))
			{
				lang.load(reader);
			} catch(java.io.IOException e)
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
			for(BlockTypes_GridDevice type : BlockTypes_GridDevice.values())
			{
				String key = "tile.immersiveengineering.grid_device."+type.getName()+".name";
				assertTrue(lang.containsKey(key), "missing lang key "+key);
			}
		}

		/**
		 * A manual page whose key is absent renders as the raw key in game -- visible, ugly,
		 * and easy to miss when adding a page to the chapter without its text.
		 */
		@Test
		@DisplayName("every page of the Grid Engineering chapter has text")
		void manualChapterIsComplete()
		{
			java.util.Properties lang = lang();
			assertTrue(lang.containsKey("ie.manual.entry.virtualGrid.name"), "chapter has no title");
			assertTrue(lang.containsKey("ie.manual.entry.virtualGrid.subtext"), "chapter has no subtitle");
			//Matches the page list registered in ClientProxy. Adding a page there without
			//its text here is exactly the mistake this catches.
			for(int page = 0; page <= 10; page++)
			{
				String key = "ie.manual.entry.virtualGrid"+page;
				assertTrue(lang.containsKey(key), "missing manual page "+key);
				assertFalse(lang.getProperty(key).trim().isEmpty(), key+" is empty");
			}
		}
	}

	@Nested
	@DisplayName("recipes")
	class Recipes
	{
		@Test
		@DisplayName("every grid recipe parses and targets the right block and meta")
		void recipesTargetCorrectMetas()
		{
			Object[][] expected = {
					{"feed_unit", "immersiveengineering:grid_device", BlockTypes_GridDevice.FEED_UNIT.getMeta()},
					{"service_unit", "immersiveengineering:grid_device", BlockTypes_GridDevice.SERVICE_UNIT.getMeta()},
					{"console_housing", "immersiveengineering:grid_device", BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta()},
					{"signal_unit", "immersiveengineering:grid_device", BlockTypes_GridDevice.SIGNAL_UNIT.getMeta()}
			};
			for(Object[] row : expected)
			{
				JsonObject json = read("recipes/grid/"+row[0]+".json");
				JsonObject result = json.getAsJsonObject("result");
				assertEquals(row[1], result.get("item").getAsString(), row[0].toString());
				assertEquals(row[2], result.get("data").getAsInt(), row[0]+" targets the wrong meta");
			}
		}

		@Test
		@DisplayName("recipe keys are all referenced by the pattern and vice versa")
		void recipeKeysMatchPattern()
		{
			for(String name : new String[]{"feed_unit", "service_unit", "console_housing", "signal_unit"})
			{
				JsonObject json = read("recipes/grid/"+name+".json");
				Set<String> recipeKeys = keys(json.getAsJsonObject("key"));
				Set<String> used = new HashSet<>();
				for(JsonElement row : json.getAsJsonArray("pattern"))
					for(char c : row.getAsString().toCharArray())
						if(c!=' ')
							used.add(String.valueOf(c));
				assertEquals(recipeKeys, used, name+": pattern symbols and key entries must match exactly");
			}
		}
	}
}
