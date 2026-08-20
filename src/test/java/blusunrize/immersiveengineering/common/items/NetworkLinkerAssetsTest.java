/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two linkers' resource files.
 * <p>
 * A sub-item's model lives at {@code models/item/&lt;item&gt;/&lt;subname&gt;.json} and is bound by
 * {@code ClientProxy} walking {@code getSubNames()}. Nothing checks that the file is there: a
 * missing one is a purple-and-black item in a hotbar and a line in a log nobody reads. The same is
 * true of a recipe that forgets {@code data} on a multi-meta result, which Forge rejects by logging
 * and carrying on -- leaving a tool that simply cannot be crafted.
 */
class NetworkLinkerAssetsTest
{
	private static final String ASSETS = "src/main/resources/assets/immersiveengineering/";
	private static final String ITEM = "network_linker";
	private static final String[] VARIANTS = {"grid", "fluid"};

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

	private static String lang()
	{
		try
		{
			//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files CRLF,
			//and every pattern below is written with the bare newline the repository stores.
			return new String(Files.readAllBytes(Paths.get(ASSETS+"lang/en_us.lang")),
					StandardCharsets.UTF_8).replace("\r\n", "\n");
		} catch(IOException e)
		{
			throw new AssertionError("could not read en_us.lang", e);
		}
	}

	@Nested
	@DisplayName("models and textures")
	class Art
	{
		@Test
		@DisplayName("each variant has a model where the sub-item loader looks for it")
		void modelsExist()
		{
			for(String variant : VARIANTS)
			{
				JsonObject model = read("models/item/"+ITEM+"/"+variant+".json");
				assertTrue(model.has("textures"), variant+" model declares no textures");
			}
		}

		@Test
		@DisplayName("every texture a model names is on disk")
		void texturesExist()
		{
			for(String variant : VARIANTS)
			{
				JsonObject textures = read("models/item/"+ITEM+"/"+variant+".json")
						.getAsJsonObject("textures");
				String layer0 = textures.get("layer0").getAsString();
				assertTrue(layer0.startsWith("immersiveengineering:"),
						variant+" points outside the mod: "+layer0);
				File file = new File(ASSETS+"textures/"+layer0.substring("immersiveengineering:".length())+".png");
				assertTrue(file.isFile(), "missing texture: "+file.getPath());
			}
		}

		@Test
		@DisplayName("both icons are 16 by 16")
		void iconsAreSixteenSquare() throws IOException
		{
			for(String variant : VARIANTS)
			{
				BufferedImage image = ImageIO.read(new File(ASSETS+"textures/items/"+ITEM+"_"+variant+".png"));
				assertEquals(16, image.getWidth(), variant);
				assertEquals(16, image.getHeight(), variant);
			}
		}

		@Test
		@DisplayName("the two icons differ, so the tools are told apart in a hotbar")
		void iconsAreDistinguishable() throws IOException
		{
			//They share a body on purpose -- it is the same tool pointed at different
			//infrastructure -- which is exactly why something has to assert that the accent
			//survived. Two identical icons would be a bug nobody would report as one.
			BufferedImage grid = ImageIO.read(new File(ASSETS+"textures/items/"+ITEM+"_grid.png"));
			BufferedImage fluid = ImageIO.read(new File(ASSETS+"textures/items/"+ITEM+"_fluid.png"));
			boolean differs = false;
			for(int y = 0; y < 16&&!differs; y++)
				for(int x = 0; x < 16; x++)
					if(grid.getRGB(x, y)!=fluid.getRGB(x, y))
					{
						differs = true;
						break;
					}
			assertTrue(differs, "the grid and fluid linkers are drawn identically");
		}
	}

	@Nested
	@DisplayName("recipes")
	class Recipes
	{
		@Test
		@DisplayName("each variant has a recipe that names its own metadata")
		void recipesStateTheirMeta()
		{
			//Forge refuses a recipe naming a multi-meta item without `data`, and refuses it by
			//logging a parse error and carrying on. The only symptom is a tool that cannot be made.
			String[] paths = {"recipes/grid/grid_linker.json", "recipes/fluidnet/fluid_linker.json"};
			for(int meta = 0; meta < paths.length; meta++)
			{
				JsonObject result = read(paths[meta]).getAsJsonObject("result");
				assertEquals("immersiveengineering:"+ITEM, result.get("item").getAsString(), paths[meta]);
				assertTrue(result.has("data"), paths[meta]+" does not say which variant it makes");
				assertEquals(meta, result.get("data").getAsInt(), paths[meta]);
			}
		}

		@Test
		@DisplayName("both recipes are ore-dictionary shaped, as the rest of the fork's are")
		void recipesUseTheForkStyle()
		{
			for(String path : new String[]{"recipes/grid/grid_linker.json",
					"recipes/fluidnet/fluid_linker.json"})
				assertEquals("forge:ore_shaped", read(path).get("type").getAsString(), path);
		}
	}

	@Nested
	@DisplayName("language")
	class Language
	{
		@Test
		@DisplayName("each variant has a name and a JEI page")
		void namesAndInfoExist()
		{
			String lang = lang();
			for(String variant : VARIANTS)
			{
				assertTrue(lang.contains("item.immersiveengineering."+ITEM+"."+variant+".name="),
						variant+" has no name");
				//Registered with JEIHelper.addInfo, which skips a missing key silently rather than
				//rendering it raw -- so a missing page is an absent page, not a broken one.
				assertTrue(lang.contains("item.immersiveengineering."+ITEM+"."+variant+".info="),
						variant+" has no JEI description");
			}
		}

		@Test
		@DisplayName("the manual pages the two chapters now end on have text")
		void manualPagesExist()
		{
			String lang = lang();
			assertTrue(lang.contains("ie.manual.entry.virtualGrid11="), "the Grid Linker page");
			assertTrue(lang.contains("ie.manual.entry.fluidNetwork7="), "the Fluid Linker page");
		}
	}
}
