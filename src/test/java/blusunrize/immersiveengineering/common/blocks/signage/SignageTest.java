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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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

	private static BufferedImage sprite(UtilitySignKind kind)
	{
		File file = new File(ASSETS+"textures/blocks/sign_"+kind.getName()+".png");
		assertTrue(file.isFile(), "missing sprite: "+file.getPath());
		try
		{
			return ImageIO.read(file);
		} catch(IOException e)
		{
			throw new AssertionError("could not read "+file.getPath(), e);
		}
	}

	private static int brightness(int argb)
	{
		return ((argb>>16)&0xFF)+((argb>>8)&0xFF)+(argb&0xFF);
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
		@DisplayName("a plate's text span is its width and its text depth is its height")
		void spanAndDepthAreWidthAndHeight()
		{
			//Unlike the strips' old rotation, which swapped the two, a stacked column does not turn
			//the plate on its side: each of its characters is limited by the width in turn, and the
			//column itself runs down the height, so both getters answer the same way for every kind.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				assertEquals(kind.getWidth(), kind.getTextSpan());
				assertEquals(kind.getHeight(), kind.getTextDepth());
			}
		}

		@Test
		@DisplayName("a stacked kind is a rectangle taller than it is wide, carrying one line")
		void stackedKindsAreTallRectangles()
		{
			//A strip six pixels wide and fourteen tall holds "M31390V" only one way round: a column
			//of upright characters running down the long side. Nothing about SignTextFlow.DOWN
			//requires a rectangle or a single line, so this pins down that every kind that actually
			//uses it is one.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				if(kind.isStacked())
				{
					assertEquals(SignShape.RECT, kind.getShape(),
							kind+" flows in a column but is not cut to a rectangle");
					assertTrue(kind.getHeight() > kind.getWidth(),
							kind+" flows in a column but is not taller than it is wide");
					assertEquals(1, kind.getLines(),
							kind+" flows in a column but carries more than the one line it is split from");
				}
		}

		@Test
		@DisplayName("only the three kinds with something printed across their middle claim a divider")
		void dividerTableMatchesTheKinds()
		{
			//One table now, on SignDivider -- this is what pins the table to the kinds it actually
			//belongs to, rather than trusting the constructor calls not to drift.
			assertEquals(SignDivider.TOWER_RULE, UtilitySignKind.TOWER_VERTICAL.getDivider());
			assertEquals(SignDivider.FRACTION_BAR, UtilitySignKind.OVAL_FRACTION.getDivider());
			assertEquals(SignDivider.NAIL, UtilitySignKind.INSPECTION_ROUND.getDivider());
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				if(kind!=UtilitySignKind.TOWER_VERTICAL&&kind!=UtilitySignKind.OVAL_FRACTION
						&&kind!=UtilitySignKind.INSPECTION_ROUND)
					assertEquals(SignDivider.NONE, kind.getDivider(),
							kind+" claims a divider nobody asked it for");
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
					float half = SignLayout.halfDepth(kind);
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
				for(int i = 0; i < kind.getLines(); i++)
				{
					//A wide string: what matters is that it comes out no wider than the paint
					//there is to print it on, which on a shape that narrows is not the plate's
					//full width.
					int wide = 200;
					float scale = SignLayout.scaleFor(kind, i, wide);
					assertTrue(scale*SignLayout.inkWidth(wide) <= SignLayout.lineSpan(kind, i)+1e-3,
							kind+" line "+i+" prints "+(scale*SignLayout.inkWidth(wide))
									+" pixels wide where the plate is only "
									+SignLayout.lineSpan(kind, i));
					//A single character: capped rather than grown until it fills the plate.
					float big = SignLayout.scaleFor(kind, i, 5);
					assertTrue(big*SignLayout.INK_HEIGHT <= SignLayout.MAX_TEXT_HEIGHT+0.001f,
							kind+" blows a short line up past the cap");
				}
		}

		@Test
		@DisplayName("every line of lettering lands on the paint, not on the border or off the plate")
		void textStaysInsideTheShape()
		{
			//This is the whole of what went wrong the first time round: a line was fitted to the
			//rectangle the sprite is drawn inside rather than to the plate cut out of it, so a
			//number sat on top of the border, and on an oval or a diamond hung off the paint
			//altogether. The box a line paints is checked against the shape at both of the two
			//limits it can hit -- width, and its share of the depth.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				for(int i = 0; i < kind.getLines(); i++)
					for(int stringWidth : new int[]{2, 6, 13, 25, 60, 200})
					{
						float scale = SignLayout.scaleFor(kind, i, stringWidth);
						float halfWide = scale*SignLayout.inkWidth(stringWidth)/2f;
						float halfTall = scale*SignLayout.INK_HEIGHT/2f;
						//The two corners furthest from the middle, which is where a line leaves
						//the paint first.
						float across = Math.abs(SignLayout.lineCentre(kind, i))+halfTall;
						float room = kind.getShape().spanAt(SignLayout.halfSpan(kind),
								SignLayout.halfDepth(kind), across)/2f;
						assertTrue(halfWide <= room+1e-3, kind+" line "+i+" of a "+stringWidth
								+"-pixel string reaches "+halfWide+" pixels out where the plate "
								+"has "+room);
						assertTrue(across <= SignLayout.halfDepth(kind)+1e-3, kind+" line "+i
								+" reaches "+across+" pixels across a plate with "
								+SignLayout.halfDepth(kind));
					}
		}

		@Test
		@DisplayName("lettering is sized by the ink, not by the empty row under it")
		void textIsSizedByItsInk()
		{
			//A glyph cell is eight pixels tall and a capital paints seven of them; a line scaled
			//and centred by the cell comes out half a pixel high and half a pixel taller than the
			//plate was measured for, which on a four-pixel strip is the border.
			assertEquals(7, SignLayout.INK_HEIGHT);
			assertEquals(8, SignLayout.FONT_HEIGHT);
			//getStringWidth counts the gap after the last character too.
			assertEquals(0f, SignLayout.inkWidth(0));
			assertEquals(0f, SignLayout.inkWidth(1));
			assertEquals(11f, SignLayout.inkWidth(12));
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				for(int i = 0; i < kind.getLines(); i++)
					assertEquals(SignLayout.lineHeight(kind, i),
							SignLayout.scaleFor(kind, i, 0)*SignLayout.INK_HEIGHT, 1e-3,
							kind+" does not fill the depth it gives line "+i);
		}

		@Test
		@DisplayName("a stack of lines never overlaps itself")
		void linesDoNotCollide()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
				for(int i = 1; i < kind.getLines(); i++)
				{
					float above = SignLayout.lineCentre(kind, i-1)
							+SignLayout.lineHeight(kind, i-1)/2f;
					float below = SignLayout.lineCentre(kind, i)-SignLayout.lineHeight(kind, i)/2f;
					assertTrue(below > above, kind+" draws line "+(i-1)+" down to "+above
							+" and line "+i+" from "+below+", so they meet");
				}
		}

		@Test
		@DisplayName("nothing is printed on a plate's divider, whichever one it has")
		void dividersAreLeftClear()
		{
			//The vertical tower tag has a rule two thirds of the way down and the receiving
			//station's initials go under it; the oval and the round tag split two lines the same
			//way, either side of a fraction bar or a nail. None of the three are even shares of the
			//plate -- laid out evenly a line's letters sit on top of whatever is actually there,
			//which is what they did before this had a name.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				SignDivider divider = kind.getDivider();
				if(divider.getAfterLine() < 0)
					continue;
				float near = SignLayout.ruleEdge(kind);
				float far = near+divider.getThickness();
				for(int i = 0; i < kind.getLines(); i++)
				{
					float top = SignLayout.lineCentre(kind, i)-SignLayout.lineHeight(kind, i)/2f;
					float bottom = SignLayout.lineCentre(kind, i)+SignLayout.lineHeight(kind, i)/2f;
					if(i <= divider.getAfterLine())
						assertTrue(bottom <= near+1e-3, kind+" line "+i+" reaches "+bottom
								+", onto a divider that starts at "+near);
					else
						assertTrue(top >= far-1e-3, kind+" line "+i+" starts at "+top
								+", onto a divider that ends at "+far);
				}
			}
		}

		@Test
		@DisplayName("a stacked column runs top to bottom, in order and without its rows overlapping")
		void stackedColumnsAreOrderedAndClear()
		{
			//The same shape as linesDoNotCollide, for a column of characters instead of a stack of
			//lines: however many characters somebody typed, the rows have to stay in reading order
			//and stay off each other, on every plate that flows this way and at more than one length.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				if(!kind.isStacked())
					continue;
				for(int count : new int[]{1, 2, 3, 7})
				{
					//The row height every character is actually capped to, read off stackedScale the
					//same way textIsSizedByItsInk reads lineHeight off scaleFor -- an ink width of
					//zero returns the height-only limit, which is the widest any row is ever drawn.
					float rowHeight = SignLayout.stackedScale(kind, count, 0)*SignLayout.INK_HEIGHT;
					float half = SignLayout.halfDepth(kind)*kind.getShape().getStackFactor();
					for(int i = 0; i < count; i++)
					{
						float centre = SignLayout.stackedCentre(kind, i, count);
						assertTrue(Math.abs(centre) <= half+1e-3, kind+" row "+i+" of "+count
								+" characters is centred off the plate at "+centre);
						if(i > 0)
							assertTrue(centre > SignLayout.stackedCentre(kind, i-1, count),
									kind+" prints its rows out of order with "+count+" characters");
					}
					for(int i = 1; i < count; i++)
					{
						float above = SignLayout.stackedCentre(kind, i-1, count)+rowHeight/2f;
						float below = SignLayout.stackedCentre(kind, i, count)-rowHeight/2f;
						assertTrue(below >= above-1e-3, kind+" draws row "+(i-1)+" down to "+above
								+" and row "+i+" from "+below+" with "+count+" characters, so they meet");
					}
				}
			}
		}

		@Test
		@DisplayName("a stacked column with one character or none answers rather than divides by zero")
		void stackedColumnsHandleTheEdges()
		{
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				if(!kind.isStacked())
					continue;
				//An empty line, once trimmed, is nothing to draw at all -- but the arithmetic behind
				//it still has to answer, the same way SignLayout.scaleFor does for a blank plate.
				assertTrue(SignLayout.stackedSlot(kind, 0) > 0,
						kind+" divides by zero on an empty column");
				assertTrue(SignLayout.stackedScale(kind, 0, 0) > 0,
						kind+" divides by zero on an empty column");
				//A single character sits dead centre of the plate rather than off to one side of an
				//empty row.
				assertEquals(0f, SignLayout.stackedCentre(kind, 0, 1), 1e-3,
						kind+" does not centre a one-character column");
				assertTrue(SignLayout.stackedScale(kind, 1, 40) > 0,
						kind+" blows up scaling a single wide character");
			}
		}

		@Test
		@DisplayName("the lettering is held off the border rather than stopping against it")
		void textIsHeldOffTheBorder()
		{
			//Text that stops exactly on the inside of the outline is on the plate and still reads
			//as a line that was cropped rather than as one somebody painted.
			assertTrue(SignLayout.PADDING > 0&&SignLayout.PADDING < 0.5f);
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				if(kind.getLines()==0)
					continue;
				float border = kind.getShape().getInset();
				assertTrue(SignLayout.halfSpan(kind) < kind.getTextSpan()/2f-border,
						kind+" prints right up to the inside of its border");
				assertTrue(SignLayout.halfDepth(kind) < kind.getTextDepth()/2f-border,
						kind+" prints right up to the inside of its border");
				//And there is still a plate left to print on afterwards.
				assertTrue(SignLayout.lineHeight(kind, 0) > 0.5f,
						kind+" has nothing left to print on: "+SignLayout.lineHeight(kind, 0));
				assertTrue(SignLayout.lineSpan(kind, 0) > 1f,
						kind+" has nothing left to print on: "+SignLayout.lineSpan(kind, 0));
			}
		}

		@Test
		@DisplayName("a kind with no text has a layout that answers rather than divides by zero")
		void noTextIsSafe()
		{
			UtilitySignKind blank = UtilitySignKind.LINE_CROSSING_DIAMOND;
			assertEquals(0, blank.getLines());
			assertEquals(0f, SignLayout.lineCentre(blank, 0));
			assertTrue(SignLayout.scaleFor(blank, 0, 40) > 0);
			assertTrue(SignLayout.scaleFor(blank, 0, 0) > 0);
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
		@DisplayName("a plate is cut to the shape its kind declares")
		void spriteMatchesTheShape()
		{
			//SignLayout fits lettering to the shape the kind declares, so a plate drawn as an
			//oval and declared a rectangle would have its text laid out to a rectangle it has
			//not got -- which is how the numbers came to be hanging off the paint. The corners
			//of the plate's own pixel rect tell the two apart: a rectangle paints them and
			//anything rounded or pointed leaves them clear.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				BufferedImage sprite = sprite(kind);
				int x0 = 8-kind.getWidth()/2, y0 = 8-kind.getHeight()/2;
				int x1 = x0+kind.getWidth()-1, y1 = y0+kind.getHeight()-1;
				boolean square = kind.getShape()==SignShape.RECT;
				for(int[] corner : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}})
				{
					boolean painted = (sprite.getRGB(corner[0], corner[1])>>>24) > 0;
					assertEquals(square, painted, kind+" declares "+kind.getShape()
							+" but its sprite "+(painted?"paints": "leaves clear")+" the corner at "
							+corner[0]+","+corner[1]);
				}
				//And the middle of every plate is painted, whatever it is cut to.
				assertTrue((sprite.getRGB(8, 8)>>>24) > 0, kind+" has a hole in the middle");
			}
		}

		@Test
		@DisplayName("every declared divider is actually painted on its sprite, where the layout leaves room for it")
		void dividerIsPaintedOnTheSprite()
		{
			//SignLayout divides a plate's lines either side of whatever this is, off arithmetic
			//the generator repeats in Python. Read it back off the sprite rather than trusting the
			//two to stay in step: a divider that moved a pixel would put letters on top of it, and
			//nothing anywhere would say so. The near and far samples are taken the divider's own
			//thickness apart, rather than one pixel either side, because the nail is two pixels
			//deep and a one-pixel neighbour would still be reading the nail rather than the plate.
			for(UtilitySignKind kind : UtilitySignKind.VALUES)
			{
				SignDivider divider = kind.getDivider();
				if(divider==SignDivider.NONE)
					continue;
				assertFalse(kind.isStacked(), kind+" flows in a column but claims a divider across it");
				BufferedImage sprite = sprite(kind);
				int row = Math.round(SignLayout.ruleEdge(kind)+kind.getTextDepth()/2f)
						+(8-kind.getHeight()/2);
				int thickness = Math.round(divider.getThickness());
				int centre = 8;
				int here = brightness(sprite.getRGB(centre, row));
				assertTrue(here < brightness(sprite.getRGB(centre, row-1)),
						kind+" has no visible "+divider+" at row "+row);
				assertTrue(here < brightness(sprite.getRGB(centre, row+thickness)),
						kind+" has no visible "+divider+" at row "+row);
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
