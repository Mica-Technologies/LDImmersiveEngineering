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
				String path = model.split(":", 2)[1];
				//An .obj reference names the file itself; everything else gets .json added,
				//because that suffix is the loader's and is never written in a blockstate.
				File file = new File(ASSETS+"models/block/"+path
						+(path.endsWith(".obj")?"": ".json"));
				assertTrue(file.isFile(), "missing model: "+file.getPath());
			}
		}

		@Test
		@DisplayName("grid models parse and reference only declared textures")
		void modelsParseAndResolveTextures()
		{
			//Collected from the blockstates rather than listed here. A hand-written list silently
			//stops covering the model you just added, which is precisely how the terminal-post
			//variant of the utility box arrived untested.
			Set<String> models = new HashSet<>(), textures = new HashSet<>();
			for(String name : BLOCKSTATES)
				collectRefs(blockstate(name), models, textures);
			Set<String> ours = new HashSet<>();
			for(String reference : models)
				//The console is an OBJ and has no JSON to parse; TerminalModel below is what
				//checks it, and it checks rather more than this could.
				if(reference.startsWith("immersiveengineering:")&&!reference.endsWith(".obj"))
					ours.add(reference.split(":", 2)[1]);
			assertTrue(ours.contains("grid/utility_box_terminal"),
					"the Feed and Service Units should be drawn with the terminal-post model, which is "
							+"where a player is meant to see that wiring attaches");

			for(String model : ours)
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

		private boolean isScreenHalf(File file)
		{
			return file.getName().endsWith("_screen_left.png")||file.getName().endsWith("_screen_right.png");
		}

		/**
		 * The console's textures are not block faces.
		 * <p>
		 * Everything else under {@code textures/blocks} is painted onto one side of one cube
		 * and answers to the 16-wide, one-pixel-border rules below. The formed console is an
		 * OBJ: {@code grid_terminal} is an atlas of nine regions laid out by
		 * {@code docs/tools/make_terminal_assets.py}, and the two screen sprites are mapped
		 * onto a quad rather than onto a face. Holding them to a rule they were never drawn
		 * for would mean either a failing test or a worse-looking console; TerminalTextures
		 * below is the set of rules they *do* answer to.
		 */
		private boolean isObjTexture(File file)
		{
			return file.getName().startsWith("grid_terminal");
		}

		/**
		 * The console's display is one screen across two blocks, and the two halves have to join
		 * without a seam.
		 * <p>
		 * Worth asserting rather than eyeballing, because getting it wrong does not look broken --
		 * it looks like a console with a line down the middle, which is precisely what the previous
		 * version was: the whole 16x16 screen painted on both upper blocks, borders and all, so a
		 * playtester photographed it and asked why there were two terminals.
		 * <p>
		 * Glued back together, the pair must satisfy the same uniform-border rule every other
		 * texture does. That single assertion catches a missing outer edge, a stray border along the
		 * seam, and the two halves being generated at different sizes.
		 */
		@Test
		@DisplayName("the two screen halves glue back into one bordered display")
		void seamedScreenHalvesFormOneDisplay()
		{
			java.awt.image.BufferedImage left = load(new File(ASSETS+"textures/blocks/grid_console_screen_left.png"));
			java.awt.image.BufferedImage right = load(new File(ASSETS+"textures/blocks/grid_console_screen_right.png"));
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
			//Outer edges only: the left half's left column and the right half's right column. The
			//two columns either side of the seam are meant to be glass and are not looked at.
			for(int y = 0; y < h; y++)
			{
				ring.add(left.getRGB(0, y));
				ring.add(right.getRGB(w-1, y));
			}
			assertEquals(1, ring.size(),
					"the reassembled display does not have a single-colour border: found "+ring.size()
							+" colours around its outside");

			//And the seam itself must not be a border, or the join draws as two screens again.
			int frame = ring.iterator().next();
			boolean seamIsClear = false;
			for(int y = 1; y < h-1; y++)
				if(left.getRGB(w-1, y)!=frame||right.getRGB(0, y)!=frame)
					seamIsClear = true;
			assertTrue(seamIsClear,
					"both columns either side of the seam are the frame colour, so the display still "
							+"draws with a line down its middle");
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
				if(isObjTexture(file))
					continue;
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
				//The two halves of the console display are the deliberate exception: each carries a
				//border on three sides and bare glass on the fourth, because the fourth is the seam
				//down the middle of one screen. seamedScreenHalvesFormOneDisplay below is the rule
				//that replaces this one for them, and it is the stronger of the two.
				if(isScreenHalf(file)||isObjTexture(file))
					continue;
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

	/**
	 * The formed console: one OBJ model, drawn by the master, with the other three blocks
	 * rendering nothing.
	 * <p>
	 * All of it is generated by {@code docs/tools/make_terminal_assets.py}, and none of it is
	 * checked by anything at runtime -- a model with a hole in it, a quad wound inside out or
	 * a UV that has walked off its sprite all render as something wrong-looking and silent.
	 */
	@Nested
	@DisplayName("the console model")
	class TerminalModel
	{
		private static final String MODEL = "models/block/grid/terminal.obj";

		private List<String> lines(String relativePath)
		{
			File file = new File(ASSETS+relativePath);
			assertTrue(file.isFile(), "missing resource: "+file.getPath());
			try
			{
				return java.nio.file.Files.readAllLines(file.toPath(),
						java.nio.charset.StandardCharsets.UTF_8);
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+file.getPath(), e);
			}
		}

		/**
		 * Positions, texture coordinates, and every face as (position, uv) pairs plus the
		 * material in force when it was declared.
		 */
		private class Obj
		{
			final List<double[]> positions = new ArrayList<>();
			final List<double[]> uvs = new ArrayList<>();
			final List<int[][]> faces = new ArrayList<>();
			final List<String> faceMaterials = new ArrayList<>();
			final List<String> materials = new ArrayList<>();

			Obj()
			{
				String material = null;
				for(String raw : lines(MODEL))
				{
					String line = raw.trim();
					if(line.isEmpty()||line.startsWith("#"))
						continue;
					String[] parts = line.split("\\s+");
					switch(parts[0])
					{
						case "mtllib":
						case "o":
							break;
						case "usemtl":
							material = parts[1];
							materials.add(material);
							break;
						case "v":
							positions.add(new double[]{Double.parseDouble(parts[1]),
									Double.parseDouble(parts[2]), Double.parseDouble(parts[3])});
							break;
						case "vt":
							uvs.add(new double[]{Double.parseDouble(parts[1]),
									Double.parseDouble(parts[2])});
							break;
						case "f":
						{
							int[][] face = new int[parts.length-1][];
							for(int i = 1; i < parts.length; i++)
							{
								String[] indices = parts[i].split("/");
								face[i-1] = new int[]{Integer.parseInt(indices[0]),
										Integer.parseInt(indices[1])};
							}
							faces.add(face);
							faceMaterials.add(material);
							break;
						}
						default:
							fail(MODEL+": unexpected directive \""+parts[0]
									+"\"; Forge's OBJ parser ignores what it does not know, so "
									+"anything new here is silently dropped");
					}
				}
			}

			double[] position(int oneBased)
			{
				return positions.get(oneBased-1);
			}

			double[] uv(int oneBased)
			{
				return uvs.get(oneBased-1);
			}
		}

		/**
		 * Regression: Forge's {@code OBJModel.Face.getNormal} takes the cross product of the
		 * two diagonals and indexes {@code verts[3]} to do it. A triangle in this file is not
		 * a smaller face, it is an exception during baking.
		 */
		@Test
		@DisplayName("every face is a quad")
		void facesAreQuads()
		{
			Obj obj = new Obj();
			assertFalse(obj.faces.isEmpty(), "the model has no faces at all");
			for(int[][] face : obj.faces)
				assertEquals(4, face.length, "Forge's OBJ loader wants quads");
		}

		/**
		 * The model is the whole multiblock, drawn from the master's corner: two blocks wide,
		 * two tall, and inside its own block in depth. Overshooting the depth would push the
		 * console's face into whatever is standing in front of it.
		 */
		@Test
		@DisplayName("the model fills its four blocks and stays inside them")
		void modelSpansTheStructure()
		{
			Obj obj = new Obj();
			double[] min = {99, 99, 99}, max = {-99, -99, -99};
			for(double[] p : obj.positions)
				for(int axis = 0; axis < 3; axis++)
				{
					min[axis] = Math.min(min[axis], p[axis]);
					max[axis] = Math.max(max[axis], p[axis]);
				}
			assertEquals(0, min[0], 1e-6, "the model must start at the master's corner in x");
			assertEquals(GridConsoleGeometry.WIDTH, max[0], 1e-6, "the model must span both columns");
			assertEquals(0, min[1], 1e-6, "the model must start at the master's corner in y");
			assertEquals(GridConsoleGeometry.HEIGHT, max[1], 1e-6, "the model must span both rows");
			assertEquals(0, min[2], 1e-6, "the back of the console sits on the block boundary");
			assertTrue(max[2] <= GridConsoleGeometry.DEPTH+1e-6,
					"the console is one block deep, but the model reaches z="+max[2]);
		}

		@Test
		@DisplayName("no texture coordinate leaves its sprite")
		void uvsAreInRange()
		{
			Obj obj = new Obj();
			for(double[] uv : obj.uvs)
				for(double coordinate : uv)
					assertTrue(coordinate >= -1e-6&&coordinate <= 1+1e-6,
							"uv "+coordinate+" is outside 0..1 and samples the atlas next door");
		}

		/**
		 * The divergence theorem, standing in for an eyeball.
		 * <p>
		 * Every face in the generator declares which way it faces and is checked one at a
		 * time; that says nothing about a face left out by mistake or one left inside the
		 * solid. Integrating the surface says both: a shell with a hole in it or wound inside
		 * out does not enclose the volume it is supposed to.
		 */
		@Test
		@DisplayName("the shell is closed and wound outwards")
		void shellIsClosed()
		{
			Obj obj = new Obj();
			double volume = 0;
			for(int[][] face : obj.faces)
			{
				double[] normal = new double[3];
				double[] centre = new double[3];
				for(int i = 0; i < face.length; i++)
				{
					double[] a = obj.position(face[i][0]);
					double[] b = obj.position(face[(i+1)%face.length][0]);
					normal[0] += (a[1]-b[1])*(a[2]+b[2]);
					normal[1] += (a[2]-b[2])*(a[0]+b[0]);
					normal[2] += (a[0]-b[0])*(a[1]+b[1]);
					for(int axis = 0; axis < 3; axis++)
						centre[axis] += a[axis]/face.length;
				}
				for(int axis = 0; axis < 3; axis++)
					volume += centre[axis]*normal[axis]/2/3;
			}
			assertTrue(volume > 0,
					"the model encloses "+volume+" blocks: it is inside out, or open");
		}

		/**
		 * The blockstate darkens the screen by replacing the texture of the material called
		 * {@code screen} -- Forge matches "#" plus the material's name. Rename the material
		 * and nothing breaks loudly; the console simply never goes dark.
		 */
		@Test
		@DisplayName("the screen is its own material, and the blockstate overrides it by name")
		void screenMaterialIsSwappable()
		{
			Obj obj = new Obj();
			assertTrue(obj.materials.contains("screen"),
					"the display needs its own material so its texture can be swapped");
			assertTrue(obj.faceMaterials.contains("screen"), "no face uses the screen material");

			Set<String> mtlNames = new HashSet<>();
			Set<String> mtlTextures = new HashSet<>();
			for(String raw : lines("models/block/grid/terminal.mtl"))
			{
				String line = raw.trim();
				if(line.startsWith("newmtl "))
					mtlNames.add(line.substring("newmtl ".length()).trim());
				else if(line.startsWith("map_Ka ")||line.startsWith("map_Kd "))
					mtlTextures.add(line.substring("map_Ka ".length()).trim());
			}
			for(String material : obj.materials)
				assertTrue(mtlNames.contains(material),
						"the model uses material \""+material+"\" but terminal.mtl does not declare it");
			for(String texture : mtlTextures)
			{
				assertTrue(texture.startsWith("immersiveengineering:"), "odd texture domain: "+texture);
				File file = new File(ASSETS+"textures/"+texture.split(":", 2)[1]+".png");
				assertTrue(file.isFile(), "missing texture: "+file.getPath());
			}

			JsonObject booleans = blockstate("grid_multiblock").getAsJsonObject("variants")
					.getAsJsonObject("boolean0");
			assertTrue(booleans.getAsJsonObject("false").getAsJsonObject("textures").has("#screen"),
					"an unpowered console should swap the screen texture, keyed on \"#screen\"");
		}

		/**
		 * Regression, and the whole point of the rework: the dummy blocks must draw nothing.
		 * If they draw the model too, four copies of the console are stacked on top of each
		 * other and every surface z-fights with itself.
		 */
		@Test
		@DisplayName("only the master draws the console")
		void slavesRenderNothing()
		{
			JsonObject slave = blockstate("grid_multiblock").getAsJsonObject("variants")
					.getAsJsonObject("_0multiblockslave");
			assertEquals("immersiveengineering:ie_empty",
					slave.getAsJsonObject("true").get("model").getAsString(),
					"a formed console's dummy blocks have to render an empty model");
			assertFalse(slave.getAsJsonObject("false").has("model"),
					"the master keeps the model from the defaults");
		}

		/**
		 * The display's art occupies a band at the top of a square sprite -- square because
		 * Minecraft's animation format assumes it -- and the model maps exactly that band. If
		 * the two ever disagree the screen shows part of its own dead space, which looks like
		 * a monitor with a black bar across it.
		 */
		@Test
		@DisplayName("the screen quad maps exactly the band the sprite is drawn in")
		void screenUvMatchesTheArtBand()
		{
			Obj obj = new Obj();
			double lowest = 1;
			for(int i = 0; i < obj.faces.size(); i++)
				if("screen".equals(obj.faceMaterials.get(i)))
					for(int[] vertex : obj.faces.get(i))
						lowest = Math.min(lowest, obj.uv(vertex[1])[1]);

			java.awt.image.BufferedImage sheet = read(new File(
					ASSETS+"textures/blocks/grid_terminal_screen.png"));
			int frame = sheet.getWidth();
			int dead = sheet.getRGB(0, frame-1);
			int band = 0;
			for(int y = 0; y < frame; y++)
			{
				boolean drawn = false;
				for(int x = 0; x < frame; x++)
					drawn |= sheet.getRGB(x, y)!=dead;
				if(drawn)
					band = y+1;
			}
			assertTrue(band > 0&&band < frame, "the display's art band is "+band+" rows of "+frame);
			assertEquals(1.0-(double)band/frame, lowest, 1e-6,
					"the glass maps v down to "+lowest+" but the art stops after "+band+" rows");
		}

		private java.awt.image.BufferedImage read(File file)
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

		/**
		 * The complaint this whole rework answers, written down as an assertion.
		 * <p>
		 * The console's display used to be two block textures side by side, so its scanlines
		 * and its graph stopped in the middle of the monitor. It is one sprite now, and the
		 * things that are supposed to cross it have to actually cross it: the baseline is one
		 * colour from edge to edge, several rows are single-colour full-width scanlines, and
		 * the bar graph puts a bar in the first column and in the last.
		 */
		@Test
		@DisplayName("the display's scanlines and bar graph run the full width")
		void displayRunsEdgeToEdge()
		{
			java.awt.image.BufferedImage sheet = read(new File(
					ASSETS+"textures/blocks/grid_terminal_screen.png"));
			int frame = sheet.getWidth();
			int dead = sheet.getRGB(0, frame-1);
			int band = 0;
			for(int y = 0; y < frame; y++)
				for(int x = 0; x < frame; x++)
					if(sheet.getRGB(x, y)!=dead)
					{
						band = y+1;
						break;
					}

			for(int f = 0; f*frame < sheet.getHeight(); f++)
			{
				int top = f*frame;
				//Rows that are one colour all the way across: the scanlines, the sweep, and
				//the baseline the graph stands on.
				Set<Integer> flatColours = new HashSet<>();
				List<Integer> flatRows = new ArrayList<>();
				for(int y = 0; y < band; y++)
				{
					int first = sheet.getRGB(0, top+y);
					boolean flat = true;
					for(int x = 1; x < frame; x++)
						flat &= sheet.getRGB(x, top+y)==first;
					if(flat)
					{
						flatRows.add(y);
						flatColours.add(first);
					}
				}
				assertTrue(flatRows.contains(band-1),
						"frame "+f+": the graph's baseline does not cross the whole display");
				assertTrue(flatRows.size() >= 3,
						"frame "+f+": only "+flatRows.size()+" rows run the full width; the "
								+"scanlines are supposed to");
				assertTrue(flatColours.size() >= 2,
						"frame "+f+": the full-width rows are all one colour, so there are no "
								+"scanlines to see");

				//And the graph reaches both ends. Above the tallest bar the display is nothing
				//but glass and scanlines, so the colours of those rows -- the flat ones, minus
				//the baseline the bars stand on -- are exactly "empty screen". Anything else in
				//a column is the graph. A graph that stopped halfway would leave one of the two
				//outermost columns empty, which is the fault this whole rework answers.
				Set<Integer> empty = new HashSet<>();
				for(int y : flatRows)
					if(y!=band-1)
						empty.add(sheet.getRGB(0, top+y));
				for(int x : new int[]{0, frame-1})
				{
					int drawn = 0;
					for(int y = 0; y < band-1; y++)
						if(!empty.contains(sheet.getRGB(x, top+y)))
							drawn++;
					assertTrue(drawn >= 2,
							"frame "+f+": column "+x+" is empty glass, so the bar graph stops "
									+"short of the edge of the screen");
				}
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
