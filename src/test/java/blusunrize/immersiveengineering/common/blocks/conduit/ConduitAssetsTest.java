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
	 * How a generated conduit part is named, in the spelling a blockstate would have used.
	 * <p>
	 * <strong>Nothing names these files any more.</strong> Both blockstates are a single
	 * unconditional part pointing at a smart model, and the Java picks the pieces off the tile
	 * entity -- so the old check, "every model the blockstate names exists", has nothing left to
	 * walk. What replaces it is this: enumerate every name the Java composer can produce, through
	 * the same {@link ConduitGeometry} methods it uses, and insist the file is there.
	 * <p>
	 * That is the same guard, aimed at the same failure. A part the composer names and nobody wrote
	 * is a hole in a conduit or a box drawn with a piece missing, with nothing in the log.
	 */
	private static String partReference(String name)
	{
		return "immersiveengineering:conduit/"+name;
	}

	/**
	 * The one part of a blockstate that is now all there is to it.
	 *
	 * @return the smart model it names
	 */
	private static String onlyPart(String blockstate)
	{
		JsonObject state = read("blockstates/"+blockstate);
		assertTrue(state.has("multipart"), blockstate+" stopped being multipart");
		assertFalse(state.has("variants"),
				"a file cannot carry both; the inventory variant belongs in conduit.json");
		JsonArray parts = state.getAsJsonArray("multipart");
		assertEquals(1, parts.size(), blockstate+" should be one unconditional part and no more");
		JsonObject part = parts.get(0).getAsJsonObject();
		assertFalse(part.has("when"),
				blockstate+"'s only part is conditional, so the block would draw as nothing at all");
		return part.getAsJsonObject("apply").get("model").getAsString();
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
		@DisplayName("it is one unconditional part naming the smart model, and nothing else")
		void isOneSmartModelPart()
		{
			//	=================================
			//	This file used to be seventy-eight selectors.
			//	=================================
			//A hub per facing plus each arm in each of its three forms, chosen by twelve boolean
			//block properties -- and Forge builds the cartesian product of every listed property at
			//startup and hands each resulting state a ModelResourceLocation of its own, so those
			//twelve cost BlockConduit 73,728 states and 73,728 model references for seventy-eight
			//little boxes. The same boxes are assembled by ConduitRunModel from the tile entity now.
			//
			//Still multipart with one unconditional part rather than `variants`, for the reason the
			//ground feeder's file has always been: a `variants` file has to resolve the whole
			//property string the state mapper hands it, and that means a submap per property or the
			//variant silently fails to resolve -- as a purple block.
			assertEquals("immersiveengineering:smartmodel/conduit_run", onlyPart("conduit_run.json"));
		}

		@Test
		@DisplayName("the model it names is the one the loader claims")
		void blockstateAndLoaderAgree()
		{
			//The same trap ConduitDisguiseLoader's own check is about, and it is the one that matters
			//here: there is deliberately no file behind this reference, so if the loader's path
			//string and the blockstate's reference ever drift apart the loader is simply never
			//asked, Minecraft looks for a file nobody wrote, and every length of conduit in the save
			//is purple with nothing in the log.
			String claimed = grepConstant(
					"src/main/java/blusunrize/immersiveengineering/client/models/smart/"
							+"ConduitRunLoader.java", "RESOURCE_LOCATION");
			assertEquals(claimed+".json", modelPath(onlyPart("conduit_run.json")),
					"the blockstate and ConduitRunLoader disagree about where the smart model lives");
		}

		@Test
		@DisplayName("nobody has written a file over the smart model's location")
		void smartModelHasNoFile()
		{
			//The other half of the same trap. A real file at that path would be loaded in preference
			//to the loader, and every conduit in the world would draw as whatever the file said.
			assertFalse(new File(ASSETS+modelPath(onlyPart("conduit_run.json"))).isFile(),
					"a file now exists where ConduitRunLoader builds the model in code");
		}

		@Test
		@DisplayName("every part the composer can name exists: six hubs and all seventy-two arms")
		void everyPartTheComposerNamesExists()
		{
			//	=================================
			//	What the blockstate check turned into.
			//	=================================
			//Nothing references these files any more, so "every model the blockstate names exists"
			//has nothing to walk. The failure it guarded against is unchanged: ConduitRunModel asks
			//for a part by name, and a name nobody wrote is a hole in a run with nothing in the log.
			//So the names are enumerated here through the very methods the composer uses.
			int found = 0;
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				assertTrue(new File(ASSETS+modelPath(partReference(
								ConduitGeometry.hubModelName(mount)))).isFile(),
						"no hub for a conduit mounted "+mount);
				found++;
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					for(String name : new String[]{ConduitGeometry.armModelName(mount, dir),
							ConduitGeometry.riserModelName(mount, dir),
							ConduitGeometry.wrapModelName(mount, dir)})
					{
						assertTrue(new File(ASSETS+modelPath(partReference(name))).isFile(),
								"the run's composer names a model nobody wrote: "+name);
						found++;
					}
			}
			//Six hubs and three forms of each of four arms on each of six mounts. The count is
			//asserted as well as the files, so an arm quietly dropped from ConduitGeometry.inPlane
			//would fail here rather than pass by checking less.
			assertEquals(6+3*6*ConduitGeometry.ARMS, found);
		}

		@Test
		@DisplayName("no arm is offered outside its own plane")
		void noArmLeavesThePlane()
		{
			//An arm along the mounting axis would be a conduit growing out of its own wall. The
			//composer walks inPlane, so this is a check on inPlane itself.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				assertEquals(ConduitGeometry.ARMS, ConduitGeometry.inPlane(mount).length);
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					assertTrue(ConduitGeometry.isInPlane(mount, dir),
							mount+" offers an arm toward "+dir+", which is off its surface");
			}
		}

		@Test
		@DisplayName("the block no longer declares the twelve per-face properties")
		void blockDeclaresOnlyTypeAndFacing()
		{
			//	=================================
			//	The state count, guarded as text.
			//	=================================
			//There is no way to count the real thing from here -- constructing a Block needs
			//Minecraft bootstrapped, and these tests run world-free -- so the guard is on the source.
			//BlockConduit's listed properties are type (3) and facing (6): eighteen states. It used
			//to declare twelve booleans besides, which multiplied that by 4096.
			//
			//Re-adding one would not fail anything else in this suite: the block would still work,
			//every model would still be found, and the only symptom would be startup cost and
			//memory nobody attributes to it.
			String block = source("src/main/java/blusunrize/immersiveengineering/common/blocks/"
					+"conduit/BlockConduit.java");
			assertFalse(block.contains("IEProperties.SIDECONNECTION["),
					"BlockConduit declares SIDECONNECTION again: six booleans is a 64x block state "
							+"count, and the smart model already reads the same thing off the tile");
			assertFalse(block.contains("IEProperties.RUNCONNECTION["),
					"BlockConduit declares RUNCONNECTION again: see above");
			//The two that are left, and the two unlisted ones the smart models arrive through.
			for(String declared : new String[]{"IEProperties.FACING_ALL",
					"IEProperties.TILEENTITY_PASSTHROUGH", "IEProperties.CONNECTIONS"})
				assertTrue(block.contains(declared),
						"BlockConduit no longer declares "+declared);
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

		/** One box of a model that has more than one, as {minX..maxZ} in pixels. */
		private int[] spanOf(String name, int index)
		{
			JsonArray elements = read("models/block/conduit/"+name+".json")
					.getAsJsonArray("elements");
			assertEquals(2, elements.size(), name+" should be an arm and one piece more");
			JsonObject box = elements.get(index).getAsJsonObject();
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
				{
					read("models/block/conduit/"+ConduitGeometry.armModelName(mount, dir)+".json");
					read("models/block/conduit/"+ConduitGeometry.riserModelName(mount, dir)+".json");
					read("models/block/conduit/"+ConduitGeometry.wrapModelName(mount, dir)+".json");
				}
			}
		}

		@Test
		@DisplayName("a riser climbs to the far face and matches the hitbox that follows it")
		void riserMatchesTheBounds()
		{
			//The inner corner, drawn: the arm along the surface, then the same tubing stood on end
			//against the block it points at, running out to the boundary the piece on the wall comes
			//down to. Checked against ConduitBounds because that is the box you click.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					String name = ConduitGeometry.riserModelName(mount, dir);
					int[] hub = span(ConduitGeometry.hubModelName(mount));
					int[] arm = spanOf(name, 0);
					int[] climb = spanOf(name, 1);
					int mountAxis = mount.getAxis().ordinal();
					int armAxis = dir.getAxis().ordinal();
					int bit = 1 << ConduitGeometry.armIndex(mount, dir);
					float[] bounds = ConduitBounds.of(mount, bit, bit);
					//Against the hub as well as the two new boxes: an arm stops where the hub starts
					//rather than spanning the block, so the piece nearest the middle of the run is
					//always the hub's own model.
					for(int i = 0; i < 3; i++)
					{
						int min = Math.min(hub[i], Math.min(arm[i], climb[i]));
						int max = Math.max(hub[i+3], Math.max(arm[i+3], climb[i+3]));
						assertEquals(bounds[i]*16, min, 1e-4,
								mount+"/"+dir+" riser starts outside its own hitbox on axis "+i);
						assertEquals(bounds[i+3]*16, max, 1e-4,
								mount+"/"+dir+" riser ends outside its own hitbox on axis "+i);
					}
					//It starts where the arm stops rather than overlapping it: two boxes sharing a
					//corner put two coplanar faces in the same place, and a corner that z-fights
					//reads as a broken model rather than as a corner.
					if(mount.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
						assertEquals(arm[mountAxis+3], climb[mountAxis],
								mount+"/"+dir+" riser overlaps the arm it grows out of");
					else
						assertEquals(arm[mountAxis], climb[mountAxis+3],
								mount+"/"+dir+" riser overlaps the arm it grows out of");
					//And it hugs the block it is climbing, one tubing depth thick.
					assertEquals(ConduitBounds.DEPTH, climb[armAxis+3]-climb[armAxis],
							mount+"/"+dir+" riser is not the thickness of the tubing");
				}
		}

		@Test
		@DisplayName("a wrap caps the corner outside its own block, which is the only piece that does")
		void wrapCapsTheCorner()
		{
			//The cap is deliberately outside the block: it fills the cube where two arms reach the
			//same edge from two sides, and that cube belongs to neither cell. It is also the one
			//piece not in ConduitBounds -- 1.12 gives a block one bounding box, and growing this one
			//past the boundary would make a conduit breakable from a cell it does not occupy.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
				{
					String name = ConduitGeometry.wrapModelName(mount, dir);
					int[] arm = spanOf(name, 0);
					int[] cap = spanOf(name, 1);
					int armAxis = dir.getAxis().ordinal();
					if(dir.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE)
					{
						assertEquals(0, cap[armAxis+3], mount+"/"+dir+" cap is not against the edge");
						assertEquals(-ConduitBounds.DEPTH, cap[armAxis],
								mount+"/"+dir+" cap is not one tubing depth deep");
					}
					else
					{
						assertEquals(16, cap[armAxis], mount+"/"+dir+" cap is not against the edge");
						assertEquals(16+ConduitBounds.DEPTH, cap[armAxis+3],
								mount+"/"+dir+" cap is not one tubing depth deep");
					}
					//Off the arm's axis the cap is exactly the arm, so the corner has the run's own
					//cross-section rather than a lump on the end of it.
					for(int i = 0; i < 3; i++)
						if(i!=armAxis)
						{
							assertEquals(arm[i], cap[i], mount+"/"+dir+" cap is not the run's width");
							assertEquals(arm[i+3], cap[i+3], mount+"/"+dir+" cap is not the run's width");
						}
				}
		}

		@Test
		@DisplayName("every face's UVs stay inside the sprite, cap included")
		void uvsStayInsideTheSprite()
		{
			//The trap the cap sets: faces here are given explicit UVs taken from their own
			//coordinates, and the cap's coordinates run past 16. A UV outside 0..16 samples past the
			//edge of the sprite, which on a stitched atlas is whatever texture was placed next to
			//this one -- a corner wearing a piece of some other block, with nothing in the log.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing dir : ConduitGeometry.inPlane(mount))
					for(String name : new String[]{ConduitGeometry.armModelName(mount, dir),
							ConduitGeometry.riserModelName(mount, dir),
							ConduitGeometry.wrapModelName(mount, dir)})
						for(JsonElement element : read("models/block/conduit/"+name+".json")
								.getAsJsonArray("elements"))
						{
							JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
							for(Map.Entry<String, JsonElement> face : faces.entrySet())
								for(JsonElement uv : face.getValue().getAsJsonObject()
										.getAsJsonArray("uv"))
								{
									float value = uv.getAsFloat();
									assertTrue(value >= 0f&&value <= 16f,
											name+"'s "+face.getKey()+" face samples outside the "
													+"sprite at "+value);
								}
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
			//BlockConduit still declares type and facing for every meta, so the state mapper hands
			//the box a property string. A `variants` file would need a submap per property or the
			//variant silently fails to resolve; multipart ignores the string and reads the state.
			JsonObject state = read("blockstates/conduit_junction_box.json");
			assertTrue(state.has("multipart"),
					"the box uses a variants blockstate but BlockConduit gives it properties to "
							+"resolve, and it declares a submap for none of them");
		}

		@Test
		@DisplayName("its housing models and texture exist, one per surface it can be bolted to")
		void modelAndTextureExist()
		{
			//A box that does not hug the same surface as its run cannot meet it -- see RunStubs
			//below -- so there is a housing per plane and ConduitJunctionModel picks between them
			//from the tile entity. The six are exhaustive, so a box always draws something.
			for(EnumFacing mount : EnumFacing.VALUES)
				assertTrue(new File(ASSETS+modelPath(partReference(
								ConduitGeometry.junctionBoxModelName(mount)))).isFile(),
						"no housing for a box mounted "+mount.getName()
								+", so a box in that plane would draw as nothing but its plates");
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

	/** A source file, read as text. These tests run without Minecraft started. */
	private static String source(String path)
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
					java.nio.charset.StandardCharsets.UTF_8);
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+path, e);
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
		String source = source(path);
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
		 * @return the model a face's plate is drawn with on a box bolted to that mount, named the
		 * way {@code ConduitJunctionModel} names it
		 */
		private String plateModelFor(EnumFacing mount, EnumFacing face)
		{
			return partReference(ConduitGeometry.junctionPatchModelName(mount, face));
		}

		@Test
		@DisplayName("every face has a plate, in every plane the box can sit in")
		void everyFaceIsCovered()
		{
			//Thirty-six plates, all of them nameable by the composer and none of them referenced by
			//anything else. A face with no plate would mean patching it changed nothing you can see,
			//which is the bug this whole thing exists to fix.
			for(EnumFacing mount : EnumFacing.VALUES)
				for(EnumFacing face : EnumFacing.VALUES)
				{
					String path = modelPath(plateModelFor(mount, face));
					assertTrue(new File(ASSETS+path).isFile(),
							"no plate for "+face.getName()+" on a box mounted "+mount.getName()
									+": "+path+" is missing");
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
		 * @return the model a face's stub is drawn with on a box in that plane, or null on the face
		 * the housing already reaches by itself -- which is the rule
		 * {@code ConduitJunctionLoader.stubName} applies and the one the generator writes files by
		 */
		private String stubModelFor(EnumFacing mount, EnumFacing face)
		{
			return face==mount?null
					:partReference(ConduitGeometry.junctionRunModelName(mount, face));
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
			//Three things have to agree about which faces get a stub, and this is where they are
			//held together: the generator writes a file for a face only if there is a gap there, the
			//loader bakes one only if there is a file, and the composer draws one only if it baked
			//one. A face the composer names with no file behind it is a box drawn with a piece
			//missing; a file nobody names is a stub that never appears.
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
					{
						assertNotNull(model, "no stub for "+face+" on a box mounted "+mount.getName()
								+": a run terminating there leaves the gap the playtest was about");
						assertTrue(new File(ASSETS+modelPath(model)).isFile(),
								"the box's composer names a stub nobody wrote: "+model);
					}
					//One-directional on purpose. Five stub files for a box's own mounting face are
					//still on disk from before the housing learned to hug its surface, when only
					//`down` was flush and the generator wrote the other five; it does not write them
					//any more and it does not delete anything, so they are dead files rather than a
					//disagreement. Nothing names them: the composer skips the mount face, which is
					//the assertion above.
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

	@Nested
	@DisplayName("the junction box as a wire endpoint")
	class WireEndpoint
	{
		private static final String LOADER =
				"src/main/java/blusunrize/immersiveengineering/client/models/smart/"
						+"ConduitJunctionLoader.java";

		@Test
		@DisplayName("the box is drawn through the connection smart model")
		void housingGoesThroughTheConnModel()
		{
			//	=================================
			//	The one that makes wires visible.
			//	=================================
			//
			// A wire strung to a box is drawn by the box's own baked model, and only ConnModelReal
			// draws one. Point the part at a plain model and the box still looks perfect, the wire
			// still exists, energy still flows -- and the catenary's near half is simply not there,
			// with nothing in the log. There is no way to notice that except by looking.
			//
			// One part now rather than six: the plane the box is in is picked by
			// ConduitJunctionModel from the tile entity rather than by a `facing` selector, which is
			// most of what took this block from 73,728 states to eighteen.
			String model = onlyPart("conduit_junction_box.json");
			assertTrue(model.startsWith("immersiveengineering:smartmodel/conn_"),
					"the box is drawn by a model that cannot draw a wire: "+model);
			//The name after `conn_` is the key ClientProxy registers the assembling model against,
			//which is what ConnLoader looks up. A key with nothing registered under it resolves to
			//the missing model -- a purple box, and every wire on it gone.
			assertEquals(grepConstant(LOADER, "CONNECTOR_KEY"),
					model.substring(model.indexOf("conn_")+"conn_".length()),
					"the blockstate and ConduitJunctionLoader disagree about the connector key");
			assertTrue(source("src/main/java/blusunrize/immersiveengineering/client/ClientProxy.java")
							.contains("registerConnectorForRender(ConduitJunctionLoader.CONNECTOR_KEY"),
					"nothing registers a base model for the box's connector key");
		}

		@Test
		@DisplayName("the location ConnLoader is pointed at is the one the loader claims")
		void connectorBaseAndLoaderAgree()
		{
			//WireApi is handed a plain ResourceLocation, which ModelLoaderRegistry prefixes with
			//`models/` and offers to every loader in turn. If ConduitJunctionLoader's accepts() and
			//that location ever drift apart, the box resolves to a file nobody wrote -- purple, and
			//silent.
			String claimed = grepConstant(LOADER, "RESOURCE_LOCATION");
			assertTrue(claimed.startsWith("models/"),
					"a loader's accepted path always carries the models/ prefix: "+claimed);
			assertTrue(source(LOADER).contains("\""+claimed.substring("models/".length())+"\""),
					"ConduitJunctionLoader.LOCATION is not the path its accepts() matches");
			assertFalse(new File(ASSETS+claimed+".json").isFile(),
					"a file now exists where ConduitJunctionLoader builds the model in code");
		}

		@Test
		@DisplayName("the models the smart model assembles are the ones that were generated")
		void wrappedHousingModelExists()
		{
			//ConnLoader resolves its key through ClientProxy rather than through the filesystem, and
			//ConduitJunctionModel names its parts in Java, so nothing in the resource pack points at
			//these any more. They still have to be there.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				String path = "models/block/conduit/"+ConduitGeometry.junctionBoxModelName(mount)
						+".json";
				assertTrue(new File(ASSETS+path).isFile(),
						"the smart model's base is missing: "+path);
			}
		}

		@Test
		@DisplayName("the housing the models draw is the housing the Java measures")
		void housingMatchesTheJavaConstants()
		{
			//ConduitBounds.junctionBox is what ConduitGeometry.junctionTerminal derives a wire's
			//attachment point from, and the generator draws the housing from the same constants.
			//If the two ever drift, a wire starts a pixel or two off the box it is attached to --
			//which is exactly the kind of thing that is obvious in a screenshot and invisible here.
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] drawn = junctionHousing(mount);
				int[] measured = ConduitBounds.junctionBox(mount);
				assertArrayEquals(measured, drawn,
						"the model and ConduitBounds disagree about a box mounted "+mount.getName());
			}
		}

		@Test
		@DisplayName("a wire lands on the middle of the face's plate")
		void terminalLandsOnThePlate()
		{
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				int[] box = junctionHousing(mount);
				for(EnumFacing face : EnumFacing.VALUES)
				{
					float[] point = ConduitGeometry.junctionTerminal(mount, face);
					int faceAxis = face.getAxis().ordinal();
					for(int axis = 0; axis < 3; axis++)
					{
						float pixels = point[axis]*16;
						if(axis==faceAxis)
							//On the surface of the housing, not inside it: a catenary that started
							//in the middle of the block would visibly begin inside the wall on any
							//box that is bolted to one.
							assertEquals(face.getAxisDirection()==EnumFacing.AxisDirection.NEGATIVE
											?box[axis]: box[axis+3], pixels, 1e-4,
									mount+"/"+face+" terminal is not on the housing's surface");
						else
							//And centred across it, which is where the plate is.
							assertEquals((box[axis]+box[axis+3])/2f, pixels, 1e-4,
									mount+"/"+face+" terminal is not centred on the plate");
					}
				}
			}
		}
	}
}
