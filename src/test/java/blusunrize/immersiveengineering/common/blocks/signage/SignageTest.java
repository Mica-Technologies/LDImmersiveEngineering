/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The utility pole signage: thirteen kinds, and the four things that have to agree about each of
 * them.
 * <p>
 * The kind table is the single source for the plate models, the atlas sprites, the blockstate and
 * the way the renderer lays text out, and all four are generated or driven from it -- so what these
 * check is that the generation actually ran and that the numbers it produced are the numbers the
 * game will use. A plate whose model and whose selection box disagree, or a kind with no model
 * behind it, is a purple block or an unclickable sign and neither logs anything.
 */
class SignageTest
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

	private static String modelName(UtilitySignKind kind)
	{
		return "signage/sign_"+kind.getName();
	}

	private static JsonObject element(UtilitySignKind kind)
	{
		return read("models/block/"+modelName(kind)+".json")
				.getAsJsonArray("elements").get(0).getAsJsonObject();
	}

	@Nested
	@DisplayName("the kind table")
	class Kinds
	{
		@Test
		@DisplayName("thirteen kinds, which is what the report asked for")
		void thirteenOfThem()
		{
			assertEquals(13, UtilitySignKind.VALUES.length);
		}

		@Test
		@DisplayName("every plate is an even number of pixels across and down")
		void evenSizes()
		{
			//Odd would put the plate's edge on a half-pixel, which samples between two texels and
			//comes out of the atlas as a blurred fringe -- on a six-pixel strip, most of the sign.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				assertEquals(0, kind.getWidth()%2, kind+" is an odd number of pixels wide");
				assertEquals(0, kind.getHeight()%2, kind+" is an odd number of pixels tall");
				assertTrue(kind.getWidth() > 0&&kind.getWidth() <= 16, kind+" does not fit a block");
				assertTrue(kind.getHeight() > 0&&kind.getHeight() <= 16, kind+" does not fit a block");
			}
		}

		@Test
		@DisplayName("no kind carries more lines than the editor has boxes")
		void linesFitTheEditor()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				assertTrue(kind.getLines() >= 0&&kind.getLines() <= UtilitySignKind.MAX_LINES,
						kind+" wants "+kind.getLines()+" lines");
		}

		@Test
		@DisplayName("text runs along the longer side of a plate that rotates it")
		void rotationFollowsTheShape()
		{
			//A strip is tagged rotated because it is taller than it is wide; the span the renderer
			//fits a line to has to be that longer side or the text is fitted to the wrong number.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				assertEquals(kind.isRotated()?kind.getHeight(): kind.getWidth(), kind.getTextSpan());
				assertEquals(kind.isRotated()?kind.getWidth(): kind.getHeight(), kind.getTextDepth());
				if(kind.isRotated())
					assertTrue(kind.getHeight() > kind.getWidth(),
							kind+" turns its text along its shorter side");
			}
		}

		@Test
		@DisplayName("a saved kind out of range comes back as a sign rather than a crash")
		void byIndexIsTotal()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				assertSame(kind, UtilitySignKind.byIndex(kind.ordinal()));
			assertNotNull(UtilitySignKind.byIndex(-1));
			assertNotNull(UtilitySignKind.byIndex(UtilitySignKind.VALUES.length));
			assertNotNull(UtilitySignKind.byIndex(9999));
		}

		@Test
		@DisplayName("the hammer's cycle walks every kind and comes back round")
		void theCycleIsWhole()
		{
			UtilitySignKind at = UtilitySignKind.VALUES[0];
			for(int i = 1; i < UtilitySignKind.VALUES.length; i++)
			{
				at = at.next();
				assertSame(UtilitySignKind.VALUES[i], at);
			}
			assertSame(UtilitySignKind.VALUES[0], at.next());
			assertSame(UtilitySignKind.VALUES[UtilitySignKind.VALUES.length-1],
					UtilitySignKind.VALUES[0].previous());
		}
	}

	@Nested
	@DisplayName("where the text goes")
	class Layout
	{
		@Test
		@DisplayName("lines are stacked evenly and stay on the plate")
		void linesStayOnThePlate()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				for(int i = 0; i < kind.getLines(); i++)
				{
					float centre = SignLayout.lineCentre(kind, i);
					float half = kind.getTextDepth()/2f;
					assertTrue(Math.abs(centre) < half,
							kind+" line "+i+" is centred off the plate at "+centre);
				}
		}

		@Test
		@DisplayName("a stack of lines is in order, top to bottom")
		void linesAreInOrder()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				for(int i = 1; i < kind.getLines(); i++)
					assertTrue(SignLayout.lineCentre(kind, i) > SignLayout.lineCentre(kind, i-1),
							kind+" prints its lines out of order");
		}

		@Test
		@DisplayName("a long line is shrunk to fit and a short one is not blown up")
		void textIsFittedBothWays()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				if(kind.getLines()==0)
					continue;
				//A wide string: what matters is that it comes out no wider than the plate.
				int wide = 200;
				float scale = SignLayout.scaleFor(kind, wide);
				assertTrue(scale*wide <= kind.getTextSpan(),
						kind+" prints a long line "+(scale*wide)+" pixels wide on a "
								+kind.getTextSpan()+" pixel plate");
				//A single character: capped rather than grown until it fills the plate.
				float big = SignLayout.scaleFor(kind, 5);
				assertTrue(big*SignLayout.FONT_HEIGHT <= SignLayout.MAX_TEXT_HEIGHT+0.001f,
						kind+" blows a short line up past the cap");
			}
		}

		@Test
		@DisplayName("a stack of lines never overlaps itself")
		void linesDoNotCollide()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				if(kind.getLines() < 2)
					continue;
				float height = SignLayout.scaleFor(kind, 200)*SignLayout.FONT_HEIGHT;
				float gap = SignLayout.lineCentre(kind, 1)-SignLayout.lineCentre(kind, 0);
				assertTrue(gap >= height, kind+" stacks its lines "+gap
						+" pixels apart and draws them "+height+" pixels tall");
			}
		}

		@Test
		@DisplayName("a kind with no text has a layout that answers rather than divides by zero")
		void noTextIsSafe()
		{
			UtilitySignKind blank = UtilitySignKind.LINE_CROSSING_DIAMOND;
			assertEquals(0, blank.getLines());
			assertEquals(0f, SignLayout.lineCentre(blank, 0));
			assertTrue(SignLayout.scaleFor(blank, 40) > 0);
			assertTrue(SignLayout.scaleFor(blank, 0) > 0);
		}
	}

	@Nested
	@DisplayName("the generated assets")
	class Assets
	{
		@Test
		@DisplayName("every kind has a sprite and a model")
		void everyKindIsDrawn()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				assertTrue(new File(ASSETS+"textures/blocks/sign_"+kind.getName()+".png").isFile(),
						"no sprite for "+kind);
				assertTrue(new File(ASSETS+"models/block/"+modelName(kind)+".json").isFile(),
						"no model for "+kind);
			}
		}

		@Test
		@DisplayName("a plate model is the size its kind says it is")
		void modelMatchesTheKind()
		{
			//The selection box is derived from the same numbers -- see
			//TileEntityUtilitySign.plateBounds -- so a model that drifted would be a sign you cannot
			//click where you can see it.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				JsonArray from = element(kind).getAsJsonArray("from");
				JsonArray to = element(kind).getAsJsonArray("to");
				assertEquals(kind.getWidth(), to.get(0).getAsInt()-from.get(0).getAsInt(),
						kind+" is drawn a different width from the one it declares");
				assertEquals(kind.getHeight(), to.get(1).getAsInt()-from.get(1).getAsInt(),
						kind+" is drawn a different height from the one it declares");
				assertEquals(TileEntityUtilitySign.THICKNESS,
						to.get(2).getAsInt()-from.get(2).getAsInt(), 0.001,
						kind+" is not one pixel thick");
				//Centred, and hard against the face it is bolted to: the model is authored for a
				//sign whose back is against the block to the north, and the blockstate turns it.
				assertEquals(0, from.get(2).getAsInt(), kind+" does not sit against its support");
				assertEquals(16-to.get(0).getAsInt(), from.get(0).getAsInt(), kind+" is off centre");
				assertEquals(16-to.get(1).getAsInt(), from.get(1).getAsInt(), kind+" is off centre");
			}
		}

		@Test
		@DisplayName("a plate takes its sprite from its own kind")
		void modelUsesItsOwnTexture()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				assertEquals("immersiveengineering:blocks/sign_"+kind.getName(),
						read("models/block/"+modelName(kind)+".json")
								.getAsJsonObject("textures").get("sign").getAsString(),
						kind+" wears somebody else's sprite");
		}

		@Test
		@DisplayName("the blockstate names a model for every kind and a rotation for every facing")
		void blockstateCoversEverything()
		{
			//Both files have to exist and every listed property has to appear: Forge takes the
			//cartesian product of the submaps, and a variant string it cannot resolve is a purple
			//block with nothing in the log.
			JsonObject variants = read("blockstates/signage_utility_sign.json")
					.getAsJsonObject("variants");
			assertTrue(variants.has("facing"), "the blockstate does not select on facing");
			assertTrue(variants.has("kind"), "the blockstate does not select on kind");
			assertTrue(variants.has("type"), "the blockstate does not select on type");
			JsonObject facings = variants.getAsJsonObject("facing");
			for(String facing : new String[]{"north", "east", "south", "west"})
				assertTrue(facings.has(facing), "no variant for facing="+facing);
			assertEquals(4, facings.entrySet().size(), "a sign has four facings and no more");
			JsonObject kinds = variants.getAsJsonObject("kind");
			assertEquals(UtilitySignKind.VALUES.length, kinds.entrySet().size(),
					"the blockstate and the kind table disagree about how many kinds there are");
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				String key = Integer.toString(kind.ordinal());
				assertTrue(kinds.has(key), "no variant for kind="+key+" ("+kind+")");
				assertEquals("immersiveengineering:"+modelName(kind),
						kinds.getAsJsonObject(key).get("model").getAsString(),
						"kind="+key+" draws the wrong plate");
			}
		}

		@Test
		@DisplayName("the item blockstate exists, because a custom mapping needs both halves")
		void theItemHalfExists()
		{
			JsonObject variants = read("blockstates/signage.json").getAsJsonObject("variants");
			assertTrue(variants.has("inventory,type=utility_sign"),
					"nothing for the item to resolve against");
		}

		@Test
		@DisplayName("every model a blockstate names is on disk")
		void namedModelsExist()
		{
			JsonObject kinds = read("blockstates/signage_utility_sign.json")
					.getAsJsonObject("variants").getAsJsonObject("kind");
			for(java.util.Map.Entry<String, com.google.gson.JsonElement> entry : kinds.entrySet())
			{
				String reference = entry.getValue().getAsJsonObject().get("model").getAsString();
				assertFalse(reference.substring(reference.indexOf(':')+1).startsWith("block/"),
						"\""+reference+"\" writes out the models/block/ prefix the loader adds "
								+"itself, which resolves to models/block/block/... -- a purple block "
								+"with nothing in the log");
				String path = "models/block/"+reference.substring(reference.indexOf(':')+1)+".json";
				assertTrue(new File(ASSETS+path).isFile(), "the blockstate names a model nobody "
						+"wrote: "+reference);
			}
		}
	}
}
