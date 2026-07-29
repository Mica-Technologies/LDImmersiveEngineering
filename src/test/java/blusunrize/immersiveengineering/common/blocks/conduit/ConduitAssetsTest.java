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
		@DisplayName("its model and texture exist")
		void modelAndTextureExist()
		{
			JsonArray parts = read("blockstates/conduit_junction_box.json").getAsJsonArray("multipart");
			//The unconditional one, rather than the only one: the box also carries a conditional
			//plate per patched face, which PatchPlates below covers.
			String model = null;
			for(JsonElement element : parts)
				if(!element.getAsJsonObject().has("when"))
					model = element.getAsJsonObject().getAsJsonObject("apply").get("model").getAsString();
			assertNotNull(model, "the box has no unconditional part, so an unpatched one draws nothing");
			assertTrue(new File(ASSETS+modelPath(model)).isFile(),
					"the box blockstate names a model nobody wrote: "+model);
			assertTrue(new File(ASSETS+"textures/blocks/conduit_junction_box.png").isFile());
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
			JsonObject model = read("models/block/conduit/junction_box.json");
			String texture = model.getAsJsonObject("textures").get("box").getAsString();
			assertNotEquals("immersiveengineering:blocks/conduit", texture,
					"the box reuses the tube texture and would vanish into a run");
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
		private JsonArray parts()
		{
			return read("blockstates/conduit_junction_box.json").getAsJsonArray("multipart");
		}

		/**
		 * @return the model a face's plate is drawn with, or null if the blockstate has no part for it
		 */
		private String plateModelFor(EnumFacing face)
		{
			String property = "sideconnection_"+face.getName();
			for(JsonElement element : parts())
			{
				JsonObject part = element.getAsJsonObject();
				if(!part.has("when"))
					continue;
				JsonObject when = part.getAsJsonObject("when");
				if(when.has(property)&&"true".equals(when.get(property).getAsString()))
					return part.getAsJsonObject("apply").get("model").getAsString();
			}
			return null;
		}

		@Test
		@DisplayName("the box itself is still drawn unconditionally")
		void boxIsAlwaysDrawn()
		{
			boolean unconditional = false;
			for(JsonElement element : parts())
				if(!element.getAsJsonObject().has("when"))
					unconditional = true;
			assertTrue(unconditional,
					"every part is conditional, so an unpatched box would draw as nothing at all");
		}

		@Test
		@DisplayName("every face has a plate")
		void everyFaceIsCovered()
		{
			for(EnumFacing face : EnumFacing.VALUES)
				assertNotNull(plateModelFor(face),
						"no plate for "+face.getName()+": patching that face would change nothing you "
								+"can see, which is the bug this whole thing exists to fix");
		}

		@Test
		@DisplayName("every plate model exists")
		void everyPlateModelExists()
		{
			for(EnumFacing face : EnumFacing.VALUES)
			{
				String path = modelPath(plateModelFor(face));
				assertTrue(new File(ASSETS+path).isFile(), "missing plate model: "+path);
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
			for(EnumFacing face : EnumFacing.VALUES)
			{
				JsonObject model = read(modelPath(plateModelFor(face)));
				JsonObject faces = model.getAsJsonArray("elements").get(0).getAsJsonObject()
						.getAsJsonObject("faces");
				for(String key : keys(faces))
					assertEquals(face.ordinal(), faces.getAsJsonObject(key).get("tintindex").getAsInt(),
							"the plate for "+face.getName()+" is tinted as though it were "
									+"EnumFacing.byIndex("+faces.getAsJsonObject(key).get("tintindex")
											.getAsInt()+")");
			}
		}

		@Test
		@DisplayName("a plate stands proud of the face it marks and of no other")
		void plateSitsOnItsOwnFace()
		{
			for(EnumFacing face : EnumFacing.VALUES)
			{
				JsonObject element = read(modelPath(plateModelFor(face)))
						.getAsJsonArray("elements").get(0).getAsJsonObject();
				int axis = face.getAxis().ordinal();
				double from = element.getAsJsonArray("from").get(axis).getAsDouble();
				double to = element.getAsJsonArray("to").get(axis).getAsDouble();
				//Thin along its own axis -- a plate, not a second box. Anything thicker would poke
				//through the far side and mark two faces at once.
				assertTrue(to-from > 0&&to-from <= 1,
						"the plate for "+face.getName()+" is "+(to-from)+" pixels thick");
				//And outside the box, so it is visible at all. The box runs 3..13 horizontally and
				//0..8 vertically, so a plate on a negative face sits at or below its lower bound and
				//one on a positive face at or above its upper.
				if(face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
					assertTrue(from < (face.getAxis()==EnumFacing.Axis.Y?0: 3)+0.001,
							"the plate for "+face.getName()+" is buried inside the box");
				else
					assertTrue(to > (face.getAxis()==EnumFacing.Axis.Y?8: 13)-0.001,
							"the plate for "+face.getName()+" is buried inside the box");
			}
		}

		@Test
		@DisplayName("the texture the plates name exists")
		void plateTextureExists()
		{
			for(EnumFacing face : EnumFacing.VALUES)
			{
				JsonObject textures = read(modelPath(plateModelFor(face))).getAsJsonObject("textures");
				for(String key : keys(textures))
				{
					String reference = textures.get(key).getAsString();
					String path = "textures/"+reference.substring(reference.indexOf(':')+1)+".png";
					assertTrue(new File(ASSETS+path).isFile(), "missing plate texture: "+path);
				}
			}
		}
	}
}
