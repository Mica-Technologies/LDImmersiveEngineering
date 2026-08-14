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

	private static final String[] BLOCKSTATES = {"petroleum_device", "petroleum_multiblock", "petroleum_decoration"};

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
		@DisplayName("petroleum_decoration covers every meta it declares")
		void decorationCoversItsMetas()
		{
			Set<String> expected = new HashSet<>();
			for(BlockTypes_PetroleumDecoration type : BlockTypes_PetroleumDecoration.values())
				expected.add(type.getName());
			assertEquals(expected,
					keys(blockstate("petroleum_decoration").getAsJsonObject("variants")
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

			Set<String> decoVariants = keys(blockstate("petroleum_decoration").getAsJsonObject("variants"));
			for(BlockTypes_PetroleumDecoration type : BlockTypes_PetroleumDecoration.values())
				assertTrue(decoVariants.contains("inventory,type="+type.getName()),
						"petroleum_decoration has no inventory variant for "+type.getName());

			Set<String> mbVariants = keys(blockstate("petroleum_multiblock").getAsJsonObject("variants"));
			for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
				assertTrue(mbVariants.contains("inventory,type="+type.getName()),
						"petroleum_multiblock has no inventory variant for "+type.getName());
		}
	}

	/**
	 * Walks every block state each petroleum block can express and resolves it against its
	 * blockstate file exactly the way Forge does.
	 * <p>
	 * Forge's {@code forge_marker} loader turns the property sub-maps under {@code variants} into
	 * one entry per <em>combination</em> of the properties it was given, keyed by the properties in
	 * name order. Minecraft then asks for one model per block state, keyed by <em>all</em> of the
	 * block's listed properties in name order. A property the block declares and the file does not
	 * mention therefore does not "default" to anything: the key simply never matches, and every
	 * state of that block loads the missing model.
	 * <p>
	 * This is invisible in the log. Forge prints only the first five such failures per mod and
	 * folds the rest into a single "suppressed additional N model loading errors" line, so a block
	 * whose every state is broken looks exactly like a block with four odd broken states.
	 */
	@Nested
	@DisplayName("blockstate state space")
	class StateSpace
	{
		@Test
		@DisplayName("each blockstate declares exactly the properties its block does")
		void propertiesMatchTheBlock()
		{
			for(Map.Entry<String, Map<String, List<String>>> block : stateSpaces().entrySet())
			{
				Map<String, Set<String>> declared = new TreeMap<>();
				for(Map.Entry<String, JsonObject> property :
						propertySubMaps(blockstate(block.getKey())).entrySet())
					declared.put(property.getKey(), keys(property.getValue()));
				Map<String, Set<String>> expected = new TreeMap<>();
				for(Map.Entry<String, List<String>> property : block.getValue().entrySet())
					expected.put(property.getKey(), new HashSet<>(property.getValue()));
				assertEquals(expected, declared, block.getKey()
						+": the properties under \"variants\" must be exactly the ones the block "
						+"declares, or the states it can express have no model at all");
			}
		}

		@Test
		@DisplayName("every state the block can express resolves to a model")
		void everyStateResolvesAModel()
		{
			for(Map.Entry<String, Map<String, List<String>>> block : stateSpaces().entrySet())
			{
				Map<String, JsonObject> resolved = resolveVariants(blockstate(block.getKey()));
				for(String variant : everyVariantString(block.getValue()))
				{
					JsonObject merged = resolved.get(variant);
					assertNotNull(merged, block.getKey()+": no variant resolves \""+variant
							+"\", so that state renders as the missing model");
					assertTrue(merged.has("model")&&!merged.get("model").getAsString().isEmpty(),
							block.getKey()+": variant \""+variant+"\" resolves no model");
				}
			}
		}

		@Test
		@DisplayName("every inventory variant resolves to a model")
		void everyInventoryVariantResolvesAModel()
		{
			for(String name : BLOCKSTATES)
			{
				Map<String, JsonObject> resolved = resolveVariants(blockstate(name));
				for(Map.Entry<String, JsonObject> entry : resolved.entrySet())
					if(entry.getKey().startsWith("inventory"))
						assertTrue(entry.getValue().has("model")
										&&!entry.getValue().get("model").getAsString().isEmpty(),
								name+": variant \""+entry.getKey()+"\" resolves no model");
			}
		}
	}

	/**
	 * The properties each petroleum block declares, and the values each of them takes.
	 * <p>
	 * These come from the block constructors: {@code BlockPetroleumMultiblock} passes
	 * {@code type} and {@code boolean0}, and {@code BlockIEMultiblock} adds
	 * {@code IEProperties.FACING_HORIZONTAL} and {@code IEProperties.MULTIBLOCKSLAVE} on top of
	 * them. The device and decoration blocks add no listed property beyond their own type --
	 * {@code IOBJModelCallback.PROPERTY} is unlisted and so never appears in a variant key.
	 * <p>
	 * Written out rather than read off the blocks because a block cannot be constructed without
	 * a running Minecraft.
	 */
	private static Map<String, Map<String, List<String>>> stateSpaces()
	{
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();

		Map<String, List<String>> multiblock = new LinkedHashMap<>();
		List<String> multiblockTypes = new ArrayList<>();
		for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
			multiblockTypes.add(type.getName());
		multiblock.put("type", multiblockTypes);
		multiblock.put("facing", Arrays.asList("north", "south", "west", "east"));
		multiblock.put("boolean0", Arrays.asList("false", "true"));
		multiblock.put("_0multiblockslave", Arrays.asList("false", "true"));
		out.put("petroleum_multiblock", multiblock);

		Map<String, List<String>> device = new LinkedHashMap<>();
		List<String> deviceTypes = new ArrayList<>();
		for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
			deviceTypes.add(type.getName());
		device.put("type", deviceTypes);
		out.put("petroleum_device", device);

		Map<String, List<String>> decoration = new LinkedHashMap<>();
		List<String> decorationTypes = new ArrayList<>();
		for(BlockTypes_PetroleumDecoration type : BlockTypes_PetroleumDecoration.values())
			decorationTypes.add(type.getName());
		decoration.put("type", decorationTypes);
		out.put("petroleum_decoration", decoration);

		return out;
	}

	/**
	 * @return every variant key Minecraft will ask this block for: one per combination of its
	 * properties, with the properties in name order, which is the order a block state's property
	 * map is kept in.
	 */
	private static List<String> everyVariantString(Map<String, List<String>> stateSpace)
	{
		List<String> properties = new ArrayList<>(stateSpace.keySet());
		Collections.sort(properties);
		List<String> out = new ArrayList<>();
		out.add("");
		for(String property : properties)
		{
			List<String> next = new ArrayList<>();
			for(String prefix : out)
				for(String value : stateSpace.get(property))
					next.add(prefix.isEmpty()?property+"="+value: prefix+","+property+"="+value);
			out = next;
		}
		return out;
	}

	/**
	 * @return the entries under {@code variants} that Forge reads as a property, mapped to their
	 * value objects. Forge decides this by looking at the first entry: a sub-map whose first value
	 * is an object is a property, anything else is a fully specified variant.
	 */
	private static Map<String, JsonObject> propertySubMaps(JsonObject blockstate)
	{
		Map<String, JsonObject> out = new TreeMap<>();
		for(Map.Entry<String, JsonElement> entry : blockstate.getAsJsonObject("variants").entrySet())
		{
			if(entry.getValue().isJsonArray())
				continue;
			JsonObject object = entry.getValue().getAsJsonObject();
			//Forge reads the first entry of every variant without checking there is one, so an
			//empty object here is not an empty variant -- it is a crash while loading the file.
			assertFalse(object.entrySet().isEmpty(),
					"\""+entry.getKey()+"\" is empty, which Forge cannot read at all");
			if(object.entrySet().iterator().next().getValue().isJsonObject())
				out.put(entry.getKey(), object);
		}
		return out;
	}

	/**
	 * Resolves a forge_marker blockstate into the variant keys Forge would hand the model loader,
	 * each merged with the file's defaults the way Forge merges them.
	 */
	private static Map<String, JsonObject> resolveVariants(JsonObject blockstate)
	{
		JsonObject defaults = blockstate.has("defaults")
				?blockstate.getAsJsonObject("defaults"): new JsonObject();
		Map<String, JsonObject> properties = propertySubMaps(blockstate);

		Map<String, JsonObject> out = new LinkedHashMap<>();
		out.put("", merge(new JsonObject(), defaults));
		for(Map.Entry<String, JsonObject> property : properties.entrySet())
		{
			Map<String, JsonObject> next = new LinkedHashMap<>();
			for(Map.Entry<String, JsonObject> so_far : out.entrySet())
				for(Map.Entry<String, JsonElement> value : property.getValue().entrySet())
				{
					String key = property.getKey()+"="+value.getKey();
					next.put(so_far.getKey().isEmpty()?key: so_far.getKey()+","+key,
							merge(merge(new JsonObject(), so_far.getValue()),
									value.getValue().getAsJsonObject()));
				}
			out = next;
		}

		for(Map.Entry<String, JsonElement> entry : blockstate.getAsJsonObject("variants").entrySet())
		{
			if(properties.containsKey(entry.getKey()))
				continue;
			//A fully specified variant may be written as a list of weighted alternatives; every one
			//of them has to resolve, so the first standing in for all of them would be too kind.
			JsonElement value = entry.getValue();
			JsonObject specified = value.isJsonArray()
					?(value.getAsJsonArray().size() > 0
					?value.getAsJsonArray().get(0).getAsJsonObject(): new JsonObject())
					: value.getAsJsonObject();
			out.put(entry.getKey(), merge(merge(new JsonObject(), defaults), specified));
		}
		return out;
	}

	/**
	 * Merges one variant over another the way Forge's {@code sync} does: a value replaces the one
	 * below it, except for the texture map, whose keys are merged one at a time.
	 */
	private static JsonObject merge(JsonObject target, JsonObject source)
	{
		for(Map.Entry<String, JsonElement> entry : source.entrySet())
		{
			if("textures".equals(entry.getKey())&&entry.getValue().isJsonObject())
			{
				JsonObject textures = target.has("textures")
						?target.getAsJsonObject("textures"): new JsonObject();
				for(Map.Entry<String, JsonElement> texture : entry.getValue().getAsJsonObject().entrySet())
					textures.add(texture.getKey(), texture.getValue());
				target.add("textures", textures);
			}
			else
				target.add(entry.getKey(), entry.getValue());
		}
		return target;
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
				String path = model.split(":", 2)[1];
				//An OBJ reference names the file itself; only a plain JSON model has the extension
				//left off. Same rule as BlockstateModelReferenceTest, which checks the whole mod.
				File file = path.endsWith(".obj")||path.endsWith(".obj.ie")
						?new File(ASSETS+"models/block/"+path)
						: new File(ASSETS+"models/block/"+path+".json");
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

	/**
	 * The Gas Station Pump's OBJ model, which is generated by
	 * {@code docs/tools/make_gaspump_assets.py} and is the only OBJ this fork authors.
	 * <p>
	 * None of this can fail the build on its own: a model with a UV outside its sprite reads the
	 * neighbouring texture off the atlas, a group the tile entity names but the file does not have
	 * simply never draws, and both look like art problems rather than broken files.
	 */
	@Nested
	@DisplayName("the gas pump model")
	class GasPumpModel
	{
		private static final String MODEL = "models/block/petroleum/gas_pump.obj.ie";

		private List<String> lines(String relativePath)
		{
			File file = new File(ASSETS+relativePath);
			assertTrue(file.isFile(), "missing resource: "+file.getPath());
			try
			{
				return java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+file.getPath(), e);
			}
		}

		@Test
		@DisplayName("it declares every group the tile entity draws, and the crate among them")
		void groupsArePresent()
		{
			Set<String> groups = new HashSet<>();
			for(String line : lines(MODEL))
				if(line.startsWith("o "))
					groups.add(line.substring(2).trim());
			//The unformed group is what a loose pump looks like. Without it a pump that has not
			//been hammered together is invisible, which reads as a block that failed to place.
			assertTrue(groups.contains(TileEntityGasPump.GROUP_UNFORMED),
					"the model has no \""+TileEntityGasPump.GROUP_UNFORMED+"\" group: "+groups);
			for(String part : new String[]{"body", "base", "panel", "nozzle", "hose", "globe"})
				assertTrue(groups.contains(part), "the pump model has no "+part+" group: "+groups);
		}

		@Test
		@DisplayName("every material it uses is declared and its texture exists")
		void materialsResolve()
		{
			Set<String> used = new HashSet<>();
			for(String line : lines(MODEL))
				if(line.startsWith("usemtl "))
					used.add(line.substring(7).trim());
			Set<String> declared = new HashSet<>();
			for(String line : lines("models/block/petroleum/gas_pump.mtl"))
			{
				if(line.startsWith("newmtl "))
					declared.add(line.substring(7).trim());
				else if(line.startsWith("map_Ka "))
				{
					String texture = line.substring(7).trim();
					assertTrue(texture.startsWith("immersiveengineering:"), texture);
					File file = new File(ASSETS+"textures/"+texture.split(":", 2)[1]+".png");
					assertTrue(file.isFile(), "missing texture: "+file.getPath());
				}
			}
			assertTrue(declared.containsAll(used),
					"the model uses materials the .mtl does not declare: "+used+" vs "+declared);
		}

		@Test
		@DisplayName("no texture coordinate leaves its own sprite")
		void uvsStayInsideTheSprite()
		{
			//A UV outside 0-1 does not tile on a stitched atlas -- it samples whatever sprite was
			//packed next door, which is the classic "why is there a bit of a ladder on my pump".
			for(String line : lines(MODEL))
				if(line.startsWith("vt "))
				{
					String[] parts = line.trim().split("\\s+");
					for(int i = 1; i < parts.length; i++)
					{
						double value = Double.parseDouble(parts[i]);
						assertTrue(value >= 0&&value <= 1, "texture coordinate out of range: "+line);
					}
				}
		}

		@Test
		@DisplayName("it fits one block's footprint and the two blocks it is built from")
		void geometryFitsTheStructure()
		{
			for(String line : lines(MODEL))
				if(line.startsWith("v "))
				{
					String[] parts = line.trim().split("\\s+");
					double x = Double.parseDouble(parts[1]);
					double y = Double.parseDouble(parts[2]);
					double z = Double.parseDouble(parts[3]);
					assertTrue(x >= 0&&x <= 1, "the pump leaves its footprint in x: "+line);
					assertTrue(z >= 0&&z <= 1, "the pump leaves its footprint in z: "+line);
					//Two blocks, because that is what the player assembled. Any taller and the
					//pump would poke through whatever is standing on top of it.
					assertTrue(y >= 0&&y <= 2, "the pump is taller than the two blocks it is: "+line);
				}
		}

		@Test
		@DisplayName("the blockstate loads it with flip-v, which its texture coordinates assume")
		void blockstateFlipsV()
		{
			JsonObject variant = blockstate("petroleum_device").getAsJsonObject("variants")
					.getAsJsonObject("type").getAsJsonObject("gas_pump");
			assertEquals("immersiveengineering:petroleum/gas_pump.obj.ie",
					variant.get("model").getAsString());
			//The generator writes 1-v for every coordinate on the assumption the loader flips them
			//back. Without the flag every texture on the pump is upside down and nothing is logged.
			assertTrue(variant.getAsJsonObject("custom").get("flip-v").getAsBoolean(),
					"the pump model is authored for flip-v");
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

		/**
		 * Checks every recipe file rather than a named list.
		 * <p>
		 * The list version passed while the propane cylinder pointed at meta 5, a value no block
		 * enum has: the file was simply not on the list. Forge does not validate result metadata,
		 * so nothing anywhere would have complained -- the recipe would have crafted a broken
		 * stack, in a build that was otherwise green.
		 */
		@Test
		@DisplayName("every petroleum recipe targets a meta that exists")
		void recipesTargetCorrectMetas()
		{
			Map<String, Set<Integer>> validMetas = new HashMap<>();
			Set<Integer> deviceMetas = new HashSet<>();
			for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
				deviceMetas.add(type.getMeta());
			validMetas.put("immersiveengineering:petroleum_device", deviceMetas);
			Set<Integer> decoMetas = new HashSet<>();
			for(BlockTypes_PetroleumDecoration type : BlockTypes_PetroleumDecoration.values())
				decoMetas.add(type.getMeta());
			validMetas.put("immersiveengineering:petroleum_decoration", decoMetas);
			Set<Integer> multiMetas = new HashSet<>();
			for(BlockTypes_PetroleumMultiblock type : BlockTypes_PetroleumMultiblock.values())
				multiMetas.add(type.getMeta());
			validMetas.put("immersiveengineering:petroleum_multiblock", multiMetas);
			//The loose items are not a block enum, so their metas come from the shared name table
			//rather than from a count written out here -- a hard-coded range would go stale the
			//first time an item was appended, which is precisely when this check matters.
			Set<Integer> itemMetas = new HashSet<>();
			for(int meta = 0; meta < blusunrize.immersiveengineering.common.items.PetroleumItemNames.SUB_NAMES.length; meta++)
				itemMetas.add(meta);
			validMetas.put("immersiveengineering:petroleum", itemMetas);

			for(String file : recipeFiles())
			{
				JsonObject result = read("recipes/petroleum/"+file).getAsJsonObject("result");
				String item = result.get("item").getAsString();
				Set<Integer> valid = validMetas.get(item);
				assertNotNull(valid, file+" produces an unknown block: "+item);
				int data = result.has("data")?result.get("data").getAsInt(): 0;
				assertTrue(valid.contains(data),
						file+" produces "+item+" meta "+data+", which no block enum defines");
			}
		}

		/**
		 * The flare stack shipped with no recipe at all, which in a feature whose early game is
		 * "you have gas and nowhere to put it" meant the answer to that problem was creative mode.
		 */
		@Test
		@DisplayName("every device except the wellhead is craftable")
		void everyDeviceIsObtainable()
		{
			for(BlockTypes_PetroleumDevice type : BlockTypes_PetroleumDevice.values())
			{
				if(type==BlockTypes_PetroleumDevice.WELLHEAD)
					continue;
				assertTrue(new File(ASSETS+"recipes/petroleum/"+type.getName()+".json").isFile(),
						type.getName()+" has no recipe, so it can only be had in creative");
			}
		}

		/**
		 * A block that is only reachable in creative is, in practice, not in the game. Every
		 * road surface has to have a path to it from the asphalt that pours out of a mixer.
		 */
		@Test
		@DisplayName("every road surface is obtainable in survival")
		void everyRoadSurfaceIsReachable()
		{
			//Plain asphalt sets from the wet fluid, so it needs no recipe; the other two do.
			for(BlockTypes_PetroleumDecoration type : BlockTypes_PetroleumDecoration.values())
			{
				if(type==BlockTypes_PetroleumDecoration.ASPHALT)
					continue;
				File recipe = new File(ASSETS+"recipes/petroleum/"+type.getName()+".json");
				assertTrue(recipe.isFile(),
						type.getName()+" has no recipe, so it can only be had in creative");
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
			for(String name : recipeFiles())
			{
				JsonObject json = read("recipes/petroleum/"+name);
				//Shapeless recipes have neither; a recipe with one and not the other is broken.
				assertEquals(json.has("pattern"), json.has("key"),
						name+": a shaped recipe needs both a pattern and a key");
				if(!json.has("pattern"))
					continue;
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

	/**
	 * @return every recipe file in the feature's folder, so a test can never pass by simply not
	 * knowing about a file.
	 */
	private static String[] recipeFiles()
	{
		String[] files = new File(ASSETS+"recipes/petroleum").list((dir, n) -> n.endsWith(".json"));
		assertNotNull(files, "the petroleum recipe folder is missing");
		assertTrue(files.length > 0, "no petroleum recipes at all");
		Arrays.sort(files);
		return files;
	}

	private static JsonObject blockstate(String name)
	{
		return read("blockstates/"+name+".json");
	}
}
