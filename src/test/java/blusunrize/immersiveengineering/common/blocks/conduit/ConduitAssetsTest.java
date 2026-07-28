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

	private static String modelPath(String reference)
	{
		//"immersiveengineering:block/conduit/conduit_down_hub" -> "models/block/conduit/...json"
		int colon = reference.indexOf(':');
		assertTrue(colon > 0, "model reference is not namespaced: "+reference);
		assertEquals("immersiveengineering", reference.substring(0, colon),
				"a conduit model points outside the mod: "+reference);
		return "models/"+reference.substring(colon+1)+".json";
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
		@DisplayName("it has a name and a description")
		void langEntriesExist()
		{
			String lang;
			try
			{
				lang = new String(java.nio.file.Files.readAllBytes(
						java.nio.file.Paths.get(ASSETS+"lang/en_us.lang")),
						java.nio.charset.StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new AssertionError("could not read en_us.lang", e);
			}
			assertTrue(lang.contains("tile.immersiveengineering.conduit.conduit_run.name="),
					"an unnamed block shows its translation key in the creative tab");
			assertTrue(lang.contains("tile.immersiveengineering.conduit.conduit_run.info="),
					"JEI would render a blank description page");
		}
	}
}
