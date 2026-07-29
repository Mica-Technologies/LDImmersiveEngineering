/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hydraulic Crawler's resources.
 * <p>
 * An entity with a missing texture is not an error Minecraft reports. It draws the missing-texture
 * chequer, or worse, whatever happens to be at those coordinates on a sheet that is too small -- and
 * the nastiest version of that is silent: two model boxes given overlapping texture offsets both
 * render, both are painted, and one of them is wearing the other's paint.
 * <p>
 * The last test here is the one worth having. It reads the offsets out of the model's Java and out of
 * the generator's table and asserts the two agree, because they are two hand-maintained copies of one
 * layout in two languages, and nothing else would ever notice them drifting apart.
 */
class CrawlerAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";
	private static final String MODEL_SRC =
			"src/main/java/blusunrize/immersiveengineering/client/models/ModelHydraulicCrawler.java";
	private static final String GENERATOR = "docs/tools/make_crawler_textures.py";

	private static String read(String path)
	{
		try
		{
			return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+path, e);
		}
	}

	private static BufferedImage image(String relativePath)
	{
		File file = new File(ASSETS+relativePath);
		assertTrue(file.isFile(), "missing texture: "+file.getPath());
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

	@Nested
	@DisplayName("the carried item")
	class Item
	{
		@Test
		@DisplayName("its model exists and points at a texture that exists")
		void modelAndTexture()
		{
			File model = new File(ASSETS+"models/item/hydraulic_crawler.json");
			assertTrue(model.isFile(), "missing item model: "+model.getPath());
			try(FileReader reader = new FileReader(model))
			{
				JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
				String layer = json.getAsJsonObject("textures").get("layer0").getAsString();
				String path = "textures/"+layer.substring(layer.indexOf(':')+1)+".png";
				assertTrue(new File(ASSETS+path).isFile(), "missing item texture: "+path);
			} catch(IOException e)
			{
				throw new AssertionError("could not read "+model.getPath(), e);
			}
		}

		@Test
		@DisplayName("it has a name, or the creative tab shows a translation key")
		void hasAName()
		{
			assertTrue(read(ASSETS+"lang/en_us.lang")
							.contains("item.immersiveengineering.hydraulic_crawler.name="),
					"no name for the crawler item");
		}

		@Test
		@DisplayName("the no-room message it sends is translated")
		void chatKeyExists()
		{
			//An untranslated status message shows the raw key over the hotbar, which reads as a crash
			//rather than as a refusal.
			assertTrue(read(ASSETS+"lang/en_us.lang")
							.contains("chat.immersiveengineering.info.crawlerNoRoom="),
					"ItemHydraulicCrawler sends crawlerNoRoom but nothing translates it");
		}

		@Test
		@DisplayName("it is craftable")
		void isCraftable()
		{
			File recipe = new File(ASSETS+"recipes/vehicles/hydraulic_crawler.json");
			assertTrue(recipe.isFile(), "no recipe, so the machine is creative-only");
		}
	}

	@Nested
	@DisplayName("the entity sheet")
	class Sheet
	{
		@Test
		@DisplayName("it exists and is the size the model declares")
		void matchesDeclaredSize()
		{
			BufferedImage sheet = image("textures/entity/hydraulic_crawler.png");
			String source = read(MODEL_SRC);
			assertEquals(dimensionFrom(source, "textureWidth"), sheet.getWidth(),
					"the sheet is not as wide as ModelHydraulicCrawler says it is, so every UV is "
							+"scaled wrongly and the whole machine is painted with the wrong pixels");
			assertEquals(dimensionFrom(source, "textureHeight"), sheet.getHeight(),
					"the sheet is not as tall as ModelHydraulicCrawler says it is");
		}

		private int dimensionFrom(String source, String field)
		{
			Matcher m = Pattern.compile(field+"\\s*=\\s*(\\d+)").matcher(source);
			assertTrue(m.find(), "ModelHydraulicCrawler does not set "+field);
			return Integer.parseInt(m.group(1));
		}
	}

	@Nested
	@DisplayName("the UV layout")
	class Layout
	{
		/**
		 * @return every {@code new ModelRenderer(this, u, v)} offset in the model, in declaration
		 * order. The two-argument form only -- the bare {@code new ModelRenderer(this)} parents carry
		 * no boxes and so no texture.
		 */
		private List<int[]> modelOffsets()
		{
			List<int[]> out = new ArrayList<>();
			Matcher m = Pattern.compile("new ModelRenderer\\(this,\\s*(\\d+),\\s*(\\d+)\\)")
					.matcher(read(MODEL_SRC));
			while(m.find())
				out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
			return out;
		}

		/**
		 * @return every offset in the generator's REGIONS table, in declaration order
		 */
		private List<int[]> generatorOffsets()
		{
			List<int[]> out = new ArrayList<>();
			Matcher m = Pattern.compile("\\(\"[a-z_]+\",\\s*(\\d+),\\s*(\\d+),")
					.matcher(read(GENERATOR));
			while(m.find())
				out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
			return out;
		}

		private java.util.Set<String> asKeys(List<int[]> offsets)
		{
			java.util.Set<String> keys = new java.util.LinkedHashSet<>();
			for(int[] offset : offsets)
				keys.add(offset[0]+","+offset[1]);
			return keys;
		}

		@Test
		@DisplayName("the model and the generator agree on where every part is painted")
		void modelAgreesWithGenerator()
		{
			//	=================================
			//	Two copies of one layout, in two languages.
			//	=================================
			//
			// The generator paints regions at offsets; the model reads them at offsets. Nothing at
			// runtime checks that those are the same numbers -- a mismatch renders perfectly happily
			// with the tracks wearing the cab's glazing, and the only symptom is a machine that looks
			// slightly wrong in a way nobody can point at.
			//
			// Compared as sets rather than pairwise, because parts are *allowed* to share a region and
			// the two tracks deliberately do: they are the same object twice. Both directions are
			// checked, so this still catches a part reading paint nobody applied and a region painted
			// for a part that no longer exists.
			List<int[]> model = modelOffsets(), generator = generatorOffsets();
			assertTrue(model.size() >= 8, "found only "+model.size()+" textured parts; the regex "
					+"has probably stopped matching the model's constructor");
			assertTrue(generator.size() >= 8, "found only "+generator.size()+" painted regions; the "
					+"regex has probably stopped matching the generator's REGIONS table");

			java.util.Set<String> painted = asKeys(generator), read = asKeys(model);
			for(String offset : read)
				assertTrue(painted.contains(offset),
						"the model reads a part from ("+offset+") but the generator paints nothing "
								+"there, so that part wears whatever happens to be at those pixels");
			for(String offset : painted)
				assertTrue(read.contains(offset),
						"the generator paints a region at ("+offset+") that no part reads -- either a "
								+"part was removed and the table not updated, or one is misplaced");
		}

		@Test
		@DisplayName("every declared offset is inside the sheet")
		void offsetsAreInBounds()
		{
			BufferedImage sheet = image("textures/entity/hydraulic_crawler.png");
			for(int[] offset : modelOffsets())
			{
				assertTrue(offset[0] < sheet.getWidth(),
						"a part is read from u="+offset[0]+", past the right edge of the sheet");
				assertTrue(offset[1] < sheet.getHeight(),
						"a part is read from v="+offset[1]+", past the bottom of the sheet");
			}
		}
	}
}
