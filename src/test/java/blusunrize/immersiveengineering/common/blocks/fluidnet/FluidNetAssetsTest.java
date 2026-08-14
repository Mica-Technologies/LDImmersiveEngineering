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

		/**
		 * BlockIEMultiblock contributes FACING_HORIZONTAL and MULTIBLOCKSLAVE, and the console adds
		 * boolean0 -- "the screen is lit". A property the block declares but the blockstate never
		 * mentions is a state that renders as the missing model, and nothing says so at load time.
		 */
		@Test
		@DisplayName("fluidnet_multiblock covers every property it declares")
		void multiblockCoversItsProperties()
		{
			JsonObject variants = blockstate("fluidnet_multiblock").getAsJsonObject("variants");
			assertEquals(new HashSet<>(Arrays.asList("north", "east", "south", "west")),
					keys(variants.getAsJsonObject("facing")));
			assertEquals(new HashSet<>(Arrays.asList("true", "false")),
					keys(variants.getAsJsonObject("_0multiblockslave")));
			assertEquals(new HashSet<>(Arrays.asList("true", "false")),
					keys(variants.getAsJsonObject("boolean0")));
		}

		/**
		 * The rotations that turn a NORTH-authored OBJ onto the other three facings, about the
		 * block centre. Verified in game on the grid console, which is drawn from the same model
		 * and the same blockstate shape; an even-width model only lands back on its own cells for
		 * these four values.
		 */
		@Test
		@DisplayName("the console's facings use the verified rotation mapping")
		void consoleFacingRotationsMatchTheModel()
		{
			JsonObject facing = blockstate("fluidnet_multiblock").getAsJsonObject("variants")
					.getAsJsonObject("facing");
			int[] expected = {0, 180, 90, -90};
			String[] names = {"north", "south", "west", "east"};
			for(int i = 0; i < names.length; i++)
				assertEquals(expected[i], facing.getAsJsonObject(names[i]).getAsJsonObject("transform")
								.getAsJsonObject("rotation").get("y").getAsInt(),
						names[i]+" turns the model the wrong way");
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
				String path = model.split(":", 2)[1];
				//An .obj reference names the file itself; everything else gets .json added, because
				//that suffix is the loader's and is never written in a blockstate.
				File file = new File(ASSETS+"models/block/"+path+(path.endsWith(".obj")?"": ".json"));
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
		 * The formed console's textures are not block faces.
		 * <p>
		 * Everything else here is painted onto one side of one cube and answers to the 16-wide
		 * rules below. The formed console is an OBJ: {@code fluidnet_terminal} is a 64x64 atlas of
		 * nine regions laid out by {@code docs/tools/make_fluid_console_assets.py}, and its two
		 * screen sprites are mapped onto a quad rather than onto a face. ConsoleModel below is the
		 * set of rules they do answer to.
		 */
		private boolean isObjTexture(File file)
		{
			return file.getName().startsWith("fluidnet_terminal");
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
				if(isObjTexture(file))
					continue;
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

	/**
	 * The formed console: one OBJ model, drawn by the master, with the other three blocks
	 * rendering nothing.
	 * <p>
	 * All of it is generated by {@code docs/tools/make_fluid_console_assets.py}, and none of it is
	 * checked by anything at runtime -- a model with a hole in it, a quad wound inside out or a UV
	 * that has walked off its sprite all render as something wrong-looking and silent. The mirror
	 * of {@code GridAssetsTest.TerminalModel}, because the two consoles are one cabinet with two
	 * paint jobs and the shell is literally the same generator function.
	 */
	@Nested
	@DisplayName("the console model")
	class ConsoleModel
	{
		private static final String MODEL = "models/block/fluidnet/terminal.obj";

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

		private BufferedImage image(String relativePath)
		{
			File file = new File(ASSETS+relativePath);
			assertTrue(file.isFile(), "missing resource: "+file.getPath());
			try
			{
				BufferedImage image = javax.imageio.ImageIO.read(file);
				assertNotNull(image, "unreadable png: "+file.getPath());
				return image;
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+file.getPath(), e);
			}
		}

		/**
		 * Positions, texture coordinates, and every face as (position, uv) pairs plus the material
		 * in force when it was declared.
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
		 * Regression: Forge's {@code OBJModel.Face.getNormal} takes the cross product of the two
		 * diagonals and indexes {@code verts[3]} to do it. A triangle in this file is not a smaller
		 * face, it is an exception during baking.
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
		 * The model is the whole multiblock, drawn from the master's corner: two blocks wide, two
		 * tall, and inside its own block in depth. Overshooting the depth would push the console's
		 * face into whatever is standing in front of it.
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
			assertEquals(FluidConsoleGeometry.WIDTH, max[0], 1e-6, "the model must span both columns");
			assertEquals(0, min[1], 1e-6, "the model must start at the master's corner in y");
			assertEquals(FluidConsoleGeometry.HEIGHT, max[1], 1e-6, "the model must span both rows");
			assertEquals(0, min[2], 1e-6, "the back of the console sits on the block boundary");
			assertTrue(max[2] <= FluidConsoleGeometry.DEPTH+1e-6,
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
		 * The divergence theorem, standing in for an eyeball: a shell with a hole in it, or with an
		 * interior face left in by accident, does not enclose the volume it is supposed to.
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
			assertTrue(volume > 0, "the model encloses "+volume+" blocks: it is inside out, or open");
		}

		/**
		 * The blockstate darkens the screen by replacing the texture of the material called
		 * {@code screen} -- Forge matches "#" plus the material's name. Rename the material and
		 * nothing breaks loudly; the console simply never goes dark.
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
			for(String raw : lines("models/block/fluidnet/terminal.mtl"))
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

			assertTrue(blockstate("fluidnet_multiblock").getAsJsonObject("variants")
							.getAsJsonObject("boolean0").getAsJsonObject("false")
							.getAsJsonObject("textures").has("#screen"),
					"an unpowered console should swap the screen texture, keyed on \"#screen\"");
		}

		/**
		 * Regression, and half the point of the rework: the dummy blocks must draw nothing. If they
		 * draw the model too, four copies of the console are stacked on top of each other and every
		 * surface z-fights with itself.
		 */
		@Test
		@DisplayName("only the master draws the console")
		void slavesRenderNothing()
		{
			JsonObject slave = blockstate("fluidnet_multiblock").getAsJsonObject("variants")
					.getAsJsonObject("_0multiblockslave");
			assertEquals("immersiveengineering:ie_empty",
					slave.getAsJsonObject("true").get("model").getAsString(),
					"a formed console's dummy blocks have to render an empty model");
			assertFalse(slave.getAsJsonObject("false").has("model"),
					"the master keeps the model from the defaults");
		}

		/**
		 * The display's art occupies a band at the top of a square sprite -- square because
		 * Minecraft's animation format assumes it -- and the model maps exactly that band. If the
		 * two ever disagree the screen shows part of its own dead space, which looks like a monitor
		 * with a black bar across it.
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

			BufferedImage sheet = image("textures/blocks/fluidnet_terminal_screen.png");
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

		/**
		 * The other half of the point, written down as an assertion.
		 * <p>
		 * The console's display used to be two block textures side by side, so its scanlines and
		 * its readouts stopped in the middle of the monitor. It is one sprite now, and the things
		 * that are supposed to cross it have to actually cross it: the delivery main runs edge to
		 * edge, several rows are single-colour full-width scanlines, and the level gauges put a
		 * gauge in the first column and in the last.
		 */
		@Test
		@DisplayName("the display's scanlines and level gauges run the full width")
		void displayRunsEdgeToEdge()
		{
			BufferedImage sheet = image("textures/blocks/fluidnet_terminal_screen.png");
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
				//Rows that are one colour all the way across: the scanlines, the sweep, the header
				//main and the delivery main the gauges stand on.
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
						"frame "+f+": the delivery main does not cross the whole display");
				assertTrue(flatRows.size() >= 3,
						"frame "+f+": only "+flatRows.size()+" rows run the full width; the "
								+"scanlines are supposed to");
				assertTrue(flatColours.size() >= 2,
						"frame "+f+": the full-width rows are all one colour, so there is nothing "
								+"to read across the glass");

				//And the gauges reach both ends. The flat rows other than the main are exactly
				//"empty screen", so anything else in a column is a readout. A display that stopped
				//halfway would leave one of the two outermost columns empty, which is the fault
				//this rework answers.
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
							"frame "+f+": column "+x+" is empty glass, so the readout stops short "
									+"of the edge of the screen");
				}
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
			//The Fluid Linker is an item rather than a fitting, but it belongs to this network and
			//its recipe belongs beside the network's -- the same arrangement the grid folder has
			//with the Network Terminal. Meta 1 is the fluid variant; meta 0 is the Grid Linker and
			//is deliberately not craftable from here.
			valid.put("immersiveengineering:network_linker", new HashSet<>(Collections.singletonList(1)));

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
