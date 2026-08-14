/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the conduit's generated resources.
 * <p>
 * A broken blockstate does not fail the build. It fails at runtime as a purple-and-black block,
 * with nothing in the log, and there are exactly two ways to earn one: a blockstate that names a
 * model nobody wrote, and a custom state mapping with no matching file. The conduit does both
 * things at once -- thirty generated models and a mapping that splits the block and item halves
 * into separate files -- so it gets a test for both.
 * <p>
 * The last nested class is the one worth having beyond that: it checks the models against
 * {@link ConduitBounds}, so the box you click and the tube you see cannot drift apart.
 */
class ConduitAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";

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

	/** Minecraft 1.12 ships Gson 2.2.4, which predates {@code JsonObject.keySet()}. */
	private static Set<String> keys(JsonObject object)
	{
		Set<String> out = new HashSet<>();
		for(Map.Entry<String, JsonElement> entry : object.entrySet())
			out.add(entry.getKey());
		return out;
	}

	/**
	 * Where a blockstate's model reference actually resolves to.
	 * <p>
	 * <strong>The {@code models/block/} prefix is implied.</strong> A blockstate saying
	 * {@code immersiveengineering:conduit/conduit_down_hub} loads
	 * {@code models/block/conduit/conduit_down_hub.json}. Writing the {@code block/} out is the
	 * mistake this whole file exists to catch and originally shipped with: every reference
	 * resolved to {@code models/block/block/conduit/...}, every model was missing, and every
	 * conduit was purple with nothing in the log. The test passed, because it was applying the
	 * same wrong rule as the code.
	 */
	private static String modelPath(String reference)
	{
		int colon = reference.indexOf(':');
		assertTrue(colon > 0, "model reference is not namespaced: "+reference);
		assertEquals("immersiveengineering", reference.substring(0, colon),
				"a conduit model points outside the mod: "+reference);
		String path = reference.substring(colon+1);
		assertFalse(path.startsWith("block/"),
				"\""+reference+"\" writes out the models/block/ prefix the loader adds itself, so it "
						+"resolves to models/block/block/... -- a purple block with nothing logged");
		return "models/block/"+path+".json";
	}

	/**
	 * One part of the junction box's blockstate, picked out by the plane the box is in and the flag
	 * that turns the part on.
	 * <p>
	 * Every part of that file is keyed on {@code facing} as well as on its own flag, because where
	 * the housing is decides where a plate sits and what a stub has to bridge. See
	 * {@code ConduitGeometry.junctionBoxMount} for where the facing comes from -- the box has none
	 * of its own.
	 *
	 * @param mount    the surface the box is bolted to
	 * @param property the connection flag the part waits on, or null for the housing itself
	 *
	 * @return the model that part draws, or null if the blockstate has no such part
	 */
	private static String junctionPart(EnumFacing mount, String property)
	{
		for(JsonElement element : read("blockstates/conduit_junction_box.json")
				.getAsJsonArray("multipart"))
		{
			JsonObject part = element.getAsJsonObject();
			if(!part.has("when"))
				continue;
			JsonObject when = part.getAsJsonObject("when");
			if(!when.has("facing")||!mount.getName().equals(when.get("facing").getAsString()))
				continue;
			if(property==null?keys(when).size()==1
					:when.has(property)&&"true".equals(when.get(property).getAsString()))
				return part.getAsJsonObject("apply").get("model").getAsString();
		}
		return null;
	}

	/**
	 * The junction box's own housing on that mount, as {minX, minY, minZ, maxX, maxY, maxZ} in
	 * pixels, read back out of the generated model rather than restated here.
	 */
	private static int[] junctionHousing(EnumFacing mount)
	{
		JsonObject element = read("models/block/conduit/"
				+ConduitGeometry.junctionBoxModelName(mount)+".json")
				.getAsJsonArray("elements").get(0).getAsJsonObject();
		JsonArray from = element.getAsJsonArray("from");
		JsonArray to = element.getAsJsonArray("to");
		return new int[]{from.get(0).getAsInt(), from.get(1).getAsInt(), from.get(2).getAsInt(),
				to.get(0).getAsInt(), to.get(1).getAsInt(), to.get(2).getAsInt()};
	}

	@Nested
	@DisplayName("the block's blockstate")
	class BlockState
	{
		@Test
		@DisplayName("it lives where the custom state mapper points")
		void fileMatchesTheMapper()
		{
			//BlockConduit.getCustomStateMapping returns "run" for the block, so IE looks for
			//conduit_run.json. A mapping with no file is a purple block and no error.
			assertTrue(new File(ASSETS+"blockstates/conduit_run.json").isFile(),
					"the block half of the blockstate is missing; the custom state mapper names it");
		}

		@Test
		@DisplayName("it is multipart, not variants")
		void isMultipart()
		{
			JsonObject state = read("blockstates/conduit_run.json");
			assertTrue(state.has("multipart"), "the block blockstate stopped being multipart");
			assertFalse(state.has("variants"),
					"a file cannot carry both; the inventory variant belongs in conduit.json");
		}

		@Test
		@DisplayName("there is a hub for every mount and an arm for every direction in its plane")
		void everyMountAndArmIsCovered()
		{
			JsonArray parts = read("blockstates/conduit_run.json").getAsJsonArray("multipart");
			Set<String> hubs = new HashSet<>();
			Set<String> arms = new HashSet<>();
			for(JsonElement element : parts)
			{
				JsonObject part = element.getAsJsonObject();
				JsonObject when = part.getAsJsonObject("when");
				assertNotNull(when, "a conduit part applies unconditionally and would always draw");
				String facing = when.get("facing").getAsString();
				Set<String> conditions = keys(when);
				conditions.remove("facing");
				if(conditions.isEmpty())
					hubs.add(facing);
				else
				{
					assertEquals(1, conditions.size(), "an arm should test one side, not "+conditions);
					String side = conditions.iterator().next();
					assertTrue(side.startsWith("sideconnection_"),
							"an arm keys off "+side+", which BlockConduit does not fill in");
					arms.add(facing+"/"+side.substring("sideconnection_".length()));
				}
			}
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				assertTrue(hubs.contains(mount.getName()), "no hub for a conduit mounted "+mount);
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					assertTrue(arms.contains(mount.getName()+"/"+dir.getName()),
							"no arm for "+mount+" running "+dir);
			}
			assertEquals(6, hubs.size());
			assertEquals(6*ConduitGeometry.ARMS, arms.size());
		}

		@Test
		@DisplayName("no arm is offered outside its own plane")
		void noArmLeavesThePlane()
		{
			//An arm along the mounting axis would be a conduit growing out of its own wall.
			JsonArray parts = read("blockstates/conduit_run.json").getAsJsonArray("multipart");
			for(JsonElement element : parts)
			{
				JsonObject when = element.getAsJsonObject().getAsJsonObject("when");
				EnumFacing mount = EnumFacing.byName(when.get("facing").getAsString());
				for(String key : keys(when))
				{
					if(!key.startsWith("sideconnection_"))
						continue;
					EnumFacing dir = EnumFacing.byName(key.substring("sideconnection_".length()));
					assertTrue(ConduitGeometry.isInPlane(mount, dir),
							mount+" offers an arm toward "+dir+", which is off its surface");
				}
			}
		}

		@Test
		@DisplayName("every model it names exists")
		void everyModelExists()
		{
			JsonArray parts = read("blockstates/conduit_run.json").getAsJsonArray("multipart");
			for(JsonElement element : parts)
			{
				String model = element.getAsJsonObject().getAsJsonObject("apply")
						.get("model").getAsString();
				assertTrue(new File(ASSETS+modelPath(model)).isFile(),
						"the blockstate names a model nobody wrote: "+model);
			}
		}
	}

	@Nested
	@DisplayName("the item's blockstate")
	class ItemState
	{
		@Test
		@DisplayName("it carries the inventory variant IE looks the item up through")
		void hasTheInventoryVariant()
		{
			//ClientProxy registers the item as conduit#inventory,type=conduit_run. Without this
			//exact key the item is purple even when the placed block is fine.
			JsonObject variants = read("blockstates/conduit.json").getAsJsonObject("variants");
			assertNotNull(variants, "conduit.json has no variants block at all");
			assertTrue(keys(variants).contains("inventory,type=conduit_run"),
					"no inventory variant for the conduit item; found "+keys(variants));
		}

		@Test
		@DisplayName("its model exists")
		void itemModelExists()
		{
			JsonObject defaults = read("blockstates/conduit.json").getAsJsonObject("defaults");
			assertNotNull(defaults, "conduit.json has no defaults block");
			String model = defaults.get("model").getAsString();
			assertTrue(new File(ASSETS+modelPath(model)).isFile(),
					"the item blockstate names a model nobody wrote: "+model);
		}

		@Test
		@DisplayName("the item shows a length of conduit, not a stub")
		void itemModelIsAStraightRun()
		{
			//Three boxes: the hub and two opposite arms. One box would be an unrecognisable
			//fragment in the creative tab.
			JsonObject model = read("models/block/conduit/conduit_item.json");
			assertEquals(3, model.getAsJsonArray("elements").size());
		}
	}

	@Nested
	@DisplayName("the models")
	class Models
	{
		private JsonArray element(String name)
		{
			JsonObject model = read("models/block/conduit/"+name+".json");
			JsonArray elements = model.getAsJsonArray("elements");
			assertEquals(1, elements.size(), name+" should be exactly one box");
			return elements;
		}

		private int[] span(String name)
		{
			JsonObject box = element(name).get(0).getAsJsonObject();
			JsonArray from = box.getAsJsonArray("from");
			JsonArray to = box.getAsJsonArray("to");
			return new int[]{from.get(0).getAsInt(), from.get(1).getAsInt(), from.get(2).getAsInt(),
					to.get(0).getAsInt(), to.get(1).getAsInt(), to.get(2).getAsInt()};
		}

		@Test
		@DisplayName("every generated model parses and has a texture")
		void everyModelIsWellFormed()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				JsonObject hub = read("models/block/conduit/"+ConduitGeometry.hubModelName(mount)+".json");
				assertTrue(hub.has("textures"), "a conduit model has no textures block");
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					read("models/block/conduit/"+ConduitGeometry.armModelName(mount, dir)+".json");
			}
		}

		@Test
		@DisplayName("the texture the models name exists")
		void textureExists()
		{
			assertTrue(new File(ASSETS+"textures/blocks/conduit.png").isFile());
		}

		/**
		 * The check this whole file is worth having for: the model and the hitbox are generated
		 * from the same two constants, and this is what notices if they stop being.
		 */
		@Test
		@DisplayName("hub and arms match the hitbox they are drawn inside")
		void modelsMatchTheBounds()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] hub = span(ConduitGeometry.hubModelName(mount));
				float[] bare = ConduitBounds.of(mount, 0);
				for(int i = 0; i < 6; i++)
					assertEquals(bare[i]*16, hub[i], 1e-4,
							"the hub for "+mount+" does not fill its own bounding box");

				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					int[] arm = span(ConduitGeometry.armModelName(mount, dir));
					int axis = dir.getAxis().ordinal();
					//The arm stops where the hub starts -- overlapping coplanar faces z-fight --
					//so it is checked against the hub rather than against the union box.
					if(dir.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
					{
						assertEquals(0, arm[axis], mount+"/"+dir+" does not reach the block edge");
						assertEquals(hub[axis], arm[axis+3], mount+"/"+dir+" overlaps or misses the hub");
					}
					else
					{
						assertEquals(hub[axis+3], arm[axis], mount+"/"+dir+" overlaps or misses the hub");
						assertEquals(16, arm[axis+3], mount+"/"+dir+" does not reach the block edge");
					}
					//Off its own axis the arm is exactly the hub, so a run has one width.
					for(int i = 0; i < 3; i++)
					{
						if(i==axis)
							continue;
						assertEquals(hub[i], arm[i], mount+"/"+dir+" is not the same width as the hub");
						assertEquals(hub[i+3], arm[i+3], mount+"/"+dir+" is not the same width as the hub");
					}
				}
			}
		}
	}

	@Nested
	@DisplayName("the junction box")
	class JunctionBox
	{
		@Test
		@DisplayName("its blockstate is where the state mapper points")
		void blockstateExists()
		{
			//A second meta means a second custom mapping, and a mapping with no file is silent.
			assertTrue(new File(ASSETS+"blockstates/conduit_junction_box.json").isFile(),
					"BlockConduit maps the box's meta to conduit_junction_box, which does not exist");
		}

		@Test
		@DisplayName("it is multipart, so it does not have to resolve the property string")
		void isMultipart()
		{
			//BlockConduit declares facing and six sideconnection flags for *every* meta, so the
			//state mapper hands the box the whole string. A `variants` file would need a submap per
			//property or the variant silently fails to resolve; multipart reads the state instead
			//and ignores the string.
			JsonObject state = read("blockstates/conduit_junction_box.json");
			assertTrue(state.has("multipart"),
					"the box uses a variants blockstate but BlockConduit gives it seven properties "
							+"to resolve, and it declares a submap for none of them");
		}

		@Test
		@DisplayName("its model and texture exist, one housing per surface it can be bolted to")
		void modelAndTextureExist()
		{
			//The housing is keyed on facing rather than drawn unconditionally, because a box that
			//does not hug the same surface as its run cannot meet it -- see RunStubs below. The six
			//are exhaustive, so a box always draws something whatever the state says.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				String model = housingModelFor(mount);
				assertNotNull(model, "no housing for a box mounted "+mount.getName()
						+", so a box in that plane would draw as nothing but its plates");
				assertTrue(new File(ASSETS+modelPath(model)).isFile(),
						"the box blockstate names a model nobody wrote: "+model);
				assertEquals(ConduitGeometry.junctionBoxModelName(mount),
						model.substring(model.lastIndexOf('/')+1),
						"the assets and ConduitGeometry disagree about what the housing is called");
			}
			assertTrue(new File(ASSETS+"textures/blocks/conduit_junction_box.png").isFile());
		}

		/** @return the model the box's own housing is drawn with on that mount, or null if none */
		private String housingModelFor(EnumFacing mount)
		{
			for(JsonElement element : read("blockstates/conduit_junction_box.json")
					.getAsJsonArray("multipart"))
			{
				JsonObject part = element.getAsJsonObject();
				if(!part.has("when"))
					continue;
				JsonObject when = part.getAsJsonObject("when");
				if(keys(when).size()==1&&when.has("facing")
						&&mount.getName().equals(when.get("facing").getAsString()))
					return part.getAsJsonObject("apply").get("model").getAsString();
			}
			return null;
		}

		@Test
		@DisplayName("it has its own item variant")
		void itemVariantExists()
		{
			//The item lives in conduit.json alongside the run's, since both are metas of one block.
			JsonObject variants = read("blockstates/conduit.json").getAsJsonObject("variants");
			assertTrue(keys(variants).contains("inventory,type=junction_box"),
					"no inventory variant for the junction box; found "+keys(variants));
		}

		@Test
		@DisplayName("its item model exists too")
		void itemModelExists()
		{
			JsonObject variants = read("blockstates/conduit.json").getAsJsonObject("variants");
			String model = variants.getAsJsonArray("inventory,type=junction_box").get(0)
					.getAsJsonObject().get("model").getAsString();
			assertTrue(new File(ASSETS+modelPath(model)).isFile(),
					"the box item names a model nobody wrote: "+model);
		}

		@Test
		@DisplayName("it looks different from a length of conduit")
		void hasItsOwnTexture()
		{
			//A player scanning a wall has to be able to pick the box out, because it is the only
			//part they can interact with.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				JsonObject model = read("models/block/conduit/"
						+ConduitGeometry.junctionBoxModelName(mount)+".json");
				String texture = model.getAsJsonObject("textures").get("box").getAsString();
				assertNotEquals("immersiveengineering:blocks/conduit", texture,
						"the box reuses the tube texture and would vanish into a run");
			}
		}

		@Test
		@DisplayName("it has a recipe, a name and a description")
		void resourcesExist()
		{
			JsonObject recipe = read("recipes/conduit/junction_box.json");
			JsonObject result = recipe.getAsJsonObject("result");
			assertEquals("immersiveengineering:conduit", result.get("item").getAsString());
			assertEquals(1, result.get("data").getAsInt(), "the recipe makes a run, not a box");
			assertTrue(lang().contains("tile.immersiveengineering.conduit.junction_box.name="));
			assertTrue(lang().contains("tile.immersiveengineering.conduit.junction_box.info="));
		}
	}

	@Nested
	@DisplayName("the ground feeder")
	class GroundFeeder
	{
		/** The one part of its blockstate, which is where the smart model is named. */
		private String smartModelReference()
		{
			JsonArray parts = read("blockstates/conduit_ground_feeder.json").getAsJsonArray("multipart");
			assertEquals(1, parts.size(), "the feeder should be one unconditional part and no more");
			JsonObject part = parts.get(0).getAsJsonObject();
			assertFalse(part.has("when"),
					"the feeder's only part is conditional, so a feeder would draw as nothing at all");
			return part.getAsJsonObject("apply").get("model").getAsString();
		}

		@Test
		@DisplayName("its blockstate is where the state mapper points")
		void blockstateExists()
		{
			//A third meta means a third custom mapping, and a mapping with no file is silent.
			assertTrue(new File(ASSETS+"blockstates/conduit_ground_feeder.json").isFile(),
					"BlockConduit maps the feeder's meta to conduit_ground_feeder, which does not exist");
		}

		@Test
		@DisplayName("it is multipart, so it does not have to resolve the property string")
		void isMultipart()
		{
			//Same reason as the junction box: BlockConduit hands every meta facing and six
			//sideconnection flags, and a `variants` file would need a submap for each or fail to
			//resolve -- silently, and as a purple block.
			assertTrue(read("blockstates/conduit_ground_feeder.json").has("multipart"));
		}

		@Test
		@DisplayName("the model it names is the one the loader claims")
		void blockstateAndLoaderAgree()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//
			// There is deliberately no file behind this reference: ConduitDisguiseLoader claims the
			// location and builds the model in code. That makes the usual "does the file exist" check
			// useless here and replaces it with a worse failure mode -- if the loader's path string
			// and the blockstate's reference ever drift apart, the loader simply never gets asked,
			// Minecraft looks for a file nobody wrote, and every feeder in the save is purple with
			// nothing in the log. So the two strings are compared directly.
			String claimed = grepConstant(
					"src/main/java/blusunrize/immersiveengineering/client/models/smart/"
							+"ConduitDisguiseLoader.java",
					"RESOURCE_LOCATION");
			assertEquals(claimed+".json", modelPath(smartModelReference()),
					"the blockstate and ConduitDisguiseLoader disagree about where the smart model "
							+"lives, so the loader will never be asked for it");
		}

		@Test
		@DisplayName("nobody has written a file over the smart model's location")
		void smartModelHasNoFile()
		{
			//The other half of the same trap. A real file at that path would be loaded in preference
			//to nothing at all and the disguise would quietly stop working, leaving a feeder that is
			//permanently whatever the file says.
			assertFalse(new File(ASSETS+modelPath(smartModelReference())).isFile(),
					"a file now exists where ConduitDisguiseLoader builds the model in code");
		}

		@Test
		@DisplayName("its bare model is a whole cube")
		void bareModelIsAFullCube()
		{
			//A feeder is part of a floor: it is solid, it blocks light and things stand on it. A
			//model smaller than its block would be a visible dent in the ground somebody walked into.
			JsonObject model = read("models/block/conduit/ground_feeder.json");
			JsonArray elements = model.getAsJsonArray("elements");
			assertEquals(1, elements.size());
			JsonObject box = elements.get(0).getAsJsonObject();
			for(int i = 0; i < 3; i++)
			{
				assertEquals(0, box.getAsJsonArray("from").get(i).getAsInt());
				assertEquals(16, box.getAsJsonArray("to").get(i).getAsInt());
			}
			assertEquals(6, keys(box.getAsJsonObject("faces")).size(),
					"a feeder can be seen from any side, so all six faces have to be textured");
		}

		@Test
		@DisplayName("its texture exists and is not the tube's")
		void hasItsOwnTexture()
		{
			//A bare feeder is a whole block; reusing the tube tile would stretch a thin stripe across
			//a full cube and read as a texture error rather than as hardware.
			JsonObject textures = read("models/block/conduit/ground_feeder.json")
					.getAsJsonObject("textures");
			String texture = textures.get("feeder").getAsString();
			assertNotEquals("immersiveengineering:blocks/conduit", texture);
			assertTrue(new File(ASSETS+"textures/blocks/conduit_ground_feeder.png").isFile());
		}

		@Test
		@DisplayName("its item shows the bare cube, not the smart model")
		void itemVariantExists()
		{
			//An item has no surroundings, so there is nothing for a disguise to be. Pointing the item
			//at the smart model would ask it what a feeder in a player's hand looks like, and the
			//answer would come back with no tile entity behind it.
			JsonObject variants = read("blockstates/conduit.json").getAsJsonObject("variants");
			assertTrue(keys(variants).contains("inventory,type=ground_feeder"),
					"no inventory variant for the ground feeder; found "+keys(variants));
			String model = variants.getAsJsonArray("inventory,type=ground_feeder").get(0)
					.getAsJsonObject().get("model").getAsString();
			assertTrue(new File(ASSETS+modelPath(model)).isFile(),
					"the feeder item names a model nobody wrote: "+model);
		}

		@Test
		@DisplayName("it has a recipe naming its own meta")
		void recipeExists()
		{
			JsonObject result = read("recipes/conduit/ground_feeder.json").getAsJsonObject("result");
			assertEquals("immersiveengineering:conduit", result.get("item").getAsString());
			assertTrue(result.has("data"),
					"the result does not state a meta, so Forge will drop the recipe");
			assertEquals(2, result.get("data").getAsInt(),
					"the recipe makes the wrong meta of conduit");
		}

		@Test
		@DisplayName("it has a name, a description and its status message")
		void langEntriesExist()
		{
			String lang = lang();
			assertTrue(lang.contains("tile.immersiveengineering.conduit.ground_feeder.name="),
					"an unnamed block shows its translation key in the creative tab");
			assertTrue(lang.contains("tile.immersiveengineering.conduit.ground_feeder.info="),
					"JEI would render a blank description page");
			//Sent when a player hands a pinned feeder back to the survey. A missing key here is a raw
			//translation string across the middle of the screen.
			assertTrue(lang.contains("chat.immersiveengineering.info.groundFeeder.auto="));
		}
	}

	/**
	 * Read a {@code public static final String} out of a source file.
	 * <p>
	 * Reading the source rather than loading the class, the same way the manual check reads
	 * ClientProxy: these tests run without Minecraft started, and the classes this needs to compare
	 * against are client-side model loaders.
	 */
	private static String grepConstant(String path, String name)
	{
		String source;
		try
		{
			source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
					java.nio.charset.StandardCharsets.UTF_8);
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+path, e);
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile(name+"\\s*=\\s*\"([^\"]*)\"").matcher(source);
		assertTrue(matcher.find(), "no constant called "+name+" in "+path);
		return matcher.group(1);
	}

	@Nested
	@DisplayName("the manual chapter")
	class Manual
	{
		@Test
		@DisplayName("it has a title, a subtitle and no gaps")
		void chapterIsComplete()
		{
			//A page whose lang key is missing renders the raw key at the reader, and the only way to
			//see it is to open the book at that exact page.
			String lang = lang();
			assertTrue(lang.contains("ie.manual.entry.conduits.name="));
			assertTrue(lang.contains("ie.manual.entry.conduits.subtext="));
			int page = 0;
			while(lang.contains("ie.manual.entry.conduits"+page+"="))
				page++;
			assertTrue(page > 0, "the chapter has no pages at all");
			assertFalse(lang.contains("ie.manual.entry.conduits"+(page+1)+"="),
					"page "+(page+1)+" has text but page "+page+" does not -- a gap in the chapter");
		}

		@Test
		@DisplayName("every page it registers has text")
		void everyRegisteredPageHasText()
		{
			//The other half of the same failure: a page registered in ClientProxy with no lang key
			//behind it. Counting the registrations is the only way to notice from here.
			String proxy;
			try
			{
				proxy = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
						"src/main/java/blusunrize/immersiveengineering/client/ClientProxy.java")),
						java.nio.charset.StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new AssertionError("could not read ClientProxy", e);
			}
			int registered = 0;
			while(proxy.contains("\"conduits"+registered+"\""))
				registered++;
			assertTrue(registered > 0, "the chapter is not registered at all");
			String lang = lang();
			for(int page = 0; page < registered; page++)
				assertTrue(lang.contains("ie.manual.entry.conduits"+page+"="),
						"page "+page+" is registered but has no text");
		}
	}

	private static String lang()
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(
					java.nio.file.Paths.get(ASSETS+"lang/en_us.lang")),
					java.nio.charset.StandardCharsets.UTF_8);
		} catch(IOException e)
		{
			throw new AssertionError("could not read en_us.lang", e);
		}
	}

	@Nested
	@DisplayName("the rest of the block's resources")
	class Rest
	{
		@Test
		@DisplayName("it has a recipe that produces conduit")
		void recipeExists()
		{
			JsonObject recipe = read("recipes/conduit/conduit.json");
			assertEquals("immersiveengineering:conduit",
					recipe.getAsJsonObject("result").get("item").getAsString());
		}

		@Test
		@DisplayName("every conduit recipe says which meta it means")
		void everyRecipeStatesItsMeta()
		{
			//Forge refuses a recipe naming a multi-meta item without `data`, and it refuses it by
			//logging a parse error and carrying on -- so the only symptom is a recipe that is
			//simply not in the game. Both conduit recipes were written without it once.
			for(String name : new String[]{"conduit", "junction_box"})
			{
				JsonObject result = read("recipes/conduit/"+name+".json").getAsJsonObject("result");
				assertTrue(result.has("data"),
						name+"'s result does not state a meta, so Forge will drop the recipe");
			}
		}

		@Test
		@DisplayName("it has a name and a description")
		void langEntriesExist()
		{
			String lang = lang();
			assertTrue(lang.contains("tile.immersiveengineering.conduit.conduit_run.name="),
					"an unnamed block shows its translation key in the creative tab");
			assertTrue(lang.contains("tile.immersiveengineering.conduit.conduit_run.info="),
					"JEI would render a blank description page");
		}
	}

	@Nested
	@DisplayName("the junction box's patch plates")
	class PatchPlates
	{
		/**
		 * @return the model a face's plate is drawn with on a box bolted to that mount, or null if
		 * the blockstate has no part for it
		 */
		private String plateModelFor(EnumFacing mount, EnumFacing face)
		{
			return junctionPart(mount, "sideconnection_"+face.getName());
		}

		@Test
		@DisplayName("the box itself is still drawn whatever plane it is in")
		void boxIsAlwaysDrawn()
		{
			//The six housing parts are exhaustive over facing, which is what makes them as
			//unconditional as the single part they replaced: a box always draws one of them.
			for(EnumFacing mount : EnumFacing.VALUES)
				assertNotNull(junctionPart(mount, null),
						"a box mounted "+mount.getName()+" draws no housing at all, only plates");
		}

		@Test
		@DisplayName("every face has a plate, in every plane the box can sit in")
		void everyFaceIsCovered()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
					assertNotNull(plateModelFor(mount, face),
							"no plate for "+face.getName()+" on a box mounted "+mount.getName()
									+": patching that face would change nothing you can see, which is "
									+"the bug this whole thing exists to fix");
		}

		@Test
		@DisplayName("every plate model exists")
		void everyPlateModelExists()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					String path = modelPath(plateModelFor(mount, face));
					assertTrue(new File(ASSETS+path).isFile(), "missing plate model: "+path);
					assertEquals(ConduitGeometry.junctionPatchModelName(mount, face),
							path.substring(path.lastIndexOf('/')+1).replace(".json", ""),
							"the assets and ConduitGeometry disagree about what a plate is called");
				}
		}

		@Test
		@DisplayName("a plate's tint index is its face's ordinal")
		void tintIndexMatchesFacingOrdinal()
		{
			//	=================================
			//	The one that matters.
			//	=================================
			//
			// BlockConduit.getRenderColour reads EnumFacing.byIndex(tintIndex) straight off, so the
			// generator's face order and EnumFacing's have to agree. If they ever drift, every plate
			// still draws and every plate is still coloured -- just the wrong colour on the wrong
			// face, with nothing logged and nothing to see but a box that lies about its wiring.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					JsonObject model = read(modelPath(plateModelFor(mount, face)));
					JsonObject faces = model.getAsJsonArray("elements").get(0).getAsJsonObject()
							.getAsJsonObject("faces");
					for(String key : keys(faces))
						assertEquals(face.ordinal(),
								faces.getAsJsonObject(key).get("tintindex").getAsInt(),
								"the plate for "+face.getName()+" on a box mounted "+mount.getName()
										+" is tinted as though it were another face");
				}
		}

		@Test
		@DisplayName("a plate stands proud of the face it marks and of no other")
		void plateSitsOnItsOwnFace()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] box = junctionHousing(mount);
				for(EnumFacing face : EnumFacing.VALUES)
				{
					JsonObject element = read(modelPath(plateModelFor(mount, face)))
							.getAsJsonArray("elements").get(0).getAsJsonObject();
					int axis = face.getAxis().ordinal();
					double from = element.getAsJsonArray("from").get(axis).getAsDouble();
					double to = element.getAsJsonArray("to").get(axis).getAsDouble();
					//Thin along its own axis -- a plate, not a second box. Anything thicker would
					//poke through the far side and mark two faces at once.
					assertTrue(to-from > 0&&to-from <= 1,
							"the plate for "+face.getName()+" is "+(to-from)+" pixels thick");
					//And outside the housing, so it is visible at all. Read off the housing rather
					//than written out, so a change to the box's size moves the plates with it.
					if(face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
						assertTrue(from < box[axis]+0.001,
								"the plate for "+face.getName()+" on a box mounted "+mount.getName()
										+" is buried inside the housing");
					else
						assertTrue(to > box[axis+3]-0.001,
								"the plate for "+face.getName()+" on a box mounted "+mount.getName()
										+" is buried inside the housing");
				}
			}
		}

		@Test
		@DisplayName("the texture the plates name exists")
		void plateTextureExists()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					JsonObject textures = read(modelPath(plateModelFor(mount, face)))
							.getAsJsonObject("textures");
					for(String key : keys(textures))
					{
						String reference = textures.get(key).getAsString();
						String path = "textures/"+reference.substring(reference.indexOf(':')+1)+".png";
						assertTrue(new File(ASSETS+path).isFile(), "missing plate texture: "+path);
					}
				}
		}
	}

	/**
	 * ISSUE 10: a box's model was inset from the block boundary on every face but the one it
	 * already sits flush against, so a run's flush arm always stopped short of the box's actual
	 * surface -- a visible gap that read as a run failing to terminate.
	 * <p>
	 * ISSUE 10a, found in a dev client with the first fix in: closing that gap along the box's own
	 * axes is not enough, because the box was modelled as a lump standing on the floor of its cell
	 * whichever surface it was bolted to. A conduit occupies the first three pixels off <em>its</em>
	 * surface and nothing else, so on a wall or a ceiling the stub grew past the arm rather than
	 * into it -- three pixels clear of the wall the run was on, in a part of the block the run never
	 * enters. On a floor run the two coincided, which is why it read as correct in every test here
	 * and wrong in the world. The box now hugs the same surface its runs do (see
	 * {@code ConduitGeometry.junctionBoxMount}, which derives that from the runs rather than storing
	 * it), and {@link #stubMeetsTheArmItIsFor} is the test that would have caught the difference:
	 * it measures the stub against {@link ConduitBounds}, the arm's own source of truth, rather than
	 * against the box alone.
	 * <p>
	 * The stubs are keyed by {@code runconnection_*} -- a second, unrelated set of properties from
	 * the patch plates' {@code sideconnection_*}, since {@link BlockConduit} already uses that name
	 * for "this face is patched" on a box -- and by {@code facing}, which is the plane. Nothing here
	 * is hardcoded to "down is already flush": every expectation is read back out of the box's own
	 * generated model, so a future change to the box's size moves these tests with it instead of
	 * leaving them to quietly stop meaning anything.
	 */
	@Nested
	@DisplayName("the junction box's run stubs")
	class RunStubs
	{
		/**
		 * @return the model a face's stub is drawn with on a box in that plane, or null if the
		 * blockstate has no part for it
		 */
		private String stubModelFor(EnumFacing mount, EnumFacing face)
		{
			return junctionPart(mount, "runconnection_"+face.getName());
		}

		/**
		 * @return true if the box's own housing already reaches the block boundary on that face, so
		 * there is nothing for a stub to bridge
		 */
		private boolean boxAlreadyTouches(EnumFacing face, int[] box)
		{
			int axis = face.getAxis().ordinal();
			return face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE
					?box[axis]==0
					:box[axis+3]==16;
		}

		/** @return the stub's element as {minX, minY, minZ, maxX, maxY, maxZ} in pixels */
		private int[] stubBounds(String model)
		{
			JsonObject element = read(modelPath(model)).getAsJsonArray("elements").get(0)
					.getAsJsonObject();
			JsonArray from = element.getAsJsonArray("from");
			JsonArray to = element.getAsJsonArray("to");
			return new int[]{from.get(0).getAsInt(), from.get(1).getAsInt(), from.get(2).getAsInt(),
					to.get(0).getAsInt(), to.get(1).getAsInt(), to.get(2).getAsInt()};
		}

		@Test
		@DisplayName("a stub is offered on every face the box does not already touch, and none it does")
		void stubsCoverExactlyTheGap()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] box = junctionHousing(mount);
				for(EnumFacing face : EnumFacing.VALUES)
				{
					boolean flush = boxAlreadyTouches(face, box);
					String model = stubModelFor(mount, face);
					if(flush)
						assertNull(model, "a box mounted "+mount.getName()+" already reaches the block "
								+"edge on "+face+"; a stub there is a seam nobody would see the point of");
					else
						assertNotNull(model, "no stub for "+face+" on a box mounted "+mount.getName()
								+": a run terminating there leaves the gap the playtest was about");
				}
			}
		}

		@Test
		@DisplayName("the face a box already touches is the one it is bolted to, and only that one")
		void theFlushFaceIsTheMount()
		{
			//The housing hugs its surface, so it is flush there and inset everywhere else. Stated as
			//a test because the stub set is derived from it: if the box ever stopped hugging, the
			//stubs would silently start bridging the wrong gaps again.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] box = junctionHousing(mount);
				for(EnumFacing face : EnumFacing.VALUES)
					assertEquals(face==mount, boxAlreadyTouches(face, box),
							"a box mounted "+mount.getName()+" is "
									+(boxAlreadyTouches(face, box)?"flush":"inset")+" on "+face);
			}
		}

		@Test
		@DisplayName("every stub model exists")
		void everyStubModelExists()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					String model = stubModelFor(mount, face);
					if(model==null)
						continue;
					assertTrue(new File(ASSETS+modelPath(model)).isFile(), "missing stub model: "+model);
					assertEquals(ConduitGeometry.junctionRunModelName(mount, face),
							model.substring(model.lastIndexOf('/')+1),
							"the assets and ConduitGeometry disagree about what a stub is called");
				}
		}

		@Test
		@DisplayName("a stub reaches from the box's own face to the block edge, at the box's own width")
		void stubBridgesTheGap()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] box = junctionHousing(mount);
				for(EnumFacing face : EnumFacing.VALUES)
				{
					String model = stubModelFor(mount, face);
					if(model==null)
						continue;
					int[] stub = stubBounds(model);
					int axis = face.getAxis().ordinal();
					if(face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
					{
						assertEquals(0, stub[axis], face+" stub does not reach the block edge");
						assertEquals(box[axis], stub[axis+3], face+" stub overlaps or misses the box");
					}
					else
					{
						assertEquals(box[axis+3], stub[axis], face+" stub overlaps or misses the box");
						assertEquals(16, stub[axis+3], face+" stub does not reach the block edge");
					}
					//Off its own axis the stub is exactly the box's own cross-section, so the housing
					//reads as one shape reaching out rather than a second box bolted to the first.
					for(int i = 0; i < 3; i++)
					{
						if(i==axis)
							continue;
						assertEquals(box[i], stub[i], face+" stub is not the box's own width");
						assertEquals(box[i+3], stub[i+3], face+" stub is not the box's own width");
					}
				}
			}
		}

		@Test
		@DisplayName("a stub actually meets the arm it is there for, in every plane")
		void stubMeetsTheArmItIsFor()
		{
			//	=================================
			//	The one that matters, and the one that was missing.
			//	=================================
			//
			// Every other test here measures the box against itself, which is exactly how a stub
			// that bridged nothing passed them all: the run's arm was never in the arithmetic. This
			// one measures against ConduitBounds -- where the arm's own shape comes from -- so the
			// two halves of the joint have to agree or the test fails.
			//
			// The arm arrives from a conduit clipped to the same surface the box is on, which is
			// the case a run ending in a box *is*. It reaches the boundary between the two blocks,
			// so its cross-section is what the stub has to cover on the far side of that boundary.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : ConduitGeometry.inPlane(mount))
				{
					String model = stubModelFor(mount, face);
					assertNotNull(model, "no stub for a run arriving on "+face.getName()
							+" of a box mounted "+mount.getName());
					int[] stub = stubBounds(model);
					int[] arm = armCrossSection(mount, face);
					int faceAxis = face.getAxis().ordinal();
					for(int axis = 0; axis < 3; axis++)
					{
						if(axis==faceAxis)
							continue;
						assertTrue(stub[axis] <= arm[axis]&&stub[axis+3] >= arm[axis+3],
								"the stub for a "+mount.getName()+"-mounted box, where a run arrives "
										+"on its "+face.getName()+" face, spans "+stub[axis]+".."
										+stub[axis+3]+" on "+"xyz".charAt(axis)+" while the run's arm "
										+"is at "+arm[axis]+".."+arm[axis+3]+" -- the two never touch, "
										+"so the gap stays open however far the stub reaches");
					}
				}
		}

		/**
		 * The cross-section of the arm a conduit draws toward a junction box, in pixels, taken from
		 * {@link ConduitBounds} rather than restated -- the same numbers the arm is modelled and
		 * hitboxed with.
		 *
		 * @param mount the surface both the conduit and the box are on
		 * @param face  the box's face the arm arrives at
		 */
		private int[] armCrossSection(EnumFacing mount, EnumFacing face)
		{
			//From the conduit's side, the box is the other way: it is that conduit's arm toward
			//face.getOpposite() that reaches the boundary between them.
			int arm = ConduitGeometry.armIndex(mount, face.getOpposite());
			assertTrue(arm >= 0, "a run cannot arrive on "+face+" of a "+mount+"-mounted box");
			float[] bounds = ConduitBounds.of(mount, 1 << arm);
			int[] out = new int[6];
			for(int i = 0; i < 6; i++)
				out[i] = Math.round(bounds[i]*16);
			return out;
		}

		@Test
		@DisplayName("a stub uses the box's own texture, not the conduit's")
		void stubLooksLikeTheBox()
		{
			//A stub is the box's housing reaching out to meet the run, not a length of tubing --
			//texturing it as tube would read as a conduit arm the box never grew, which is the
			//exact confusion this whole fix exists to remove.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					String model = stubModelFor(mount, face);
					if(model==null)
						continue;
					JsonObject textures = read(modelPath(model)).getAsJsonObject("textures");
					assertEquals("immersiveengineering:blocks/conduit_junction_box",
							textures.get("box").getAsString(),
							face+" stub does not reuse the box's texture");
				}
		}
	}
}
