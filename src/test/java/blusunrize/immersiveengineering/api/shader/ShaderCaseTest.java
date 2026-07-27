/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.shader;

import blusunrize.immersiveengineering.api.shader.ShaderCase.DynamicShaderLayer;
import blusunrize.immersiveengineering.api.shader.ShaderCase.ShaderLayer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the {@link ShaderCase} layer stack and the {@link ShaderLayer} value class.
 * <p>
 * Layer order is render order, and the index handed to {@code getReplacementSprite} is the render
 * pass, so an off-by-one in the insertion arithmetic shows up as a shader painting the wrong part
 * of a model.
 */
class ShaderCaseTest
{
	private static ShaderLayer layer(String path, int colour)
	{
		return new ShaderLayer(new ResourceLocation("immersiveengineering", path), colour);
	}

	private static ShaderCaseItem threeLayerCase()
	{
		return new ShaderCaseItem(layer("a", 0x11), layer("b", 0x22), layer("c", 0x33));
	}

	@Test
	@DisplayName("a shader case keeps its layers in the order they were given")
	void layersKeepTheirOrder()
	{
		ShaderCaseItem shader = threeLayerCase();

		assertEquals(3, shader.getLayers().length);
		assertEquals(0x11, shader.getLayers()[0].getColour());
		assertEquals(0x22, shader.getLayers()[1].getColour());
		assertEquals(0x33, shader.getLayers()[2].getColour());
	}

	@Test
	@DisplayName("a shader case built from a collection matches one built from varargs")
	void collectionAndVarargsConstructorsAgree()
	{
		ShaderLayer[] layers = {layer("a", 1), layer("b", 2)};
		ShaderCaseItem fromVarargs = new ShaderCaseItem(layers[0], layers[1]);
		ShaderCaseItem fromCollection = new ShaderCaseItem(Arrays.asList(layers));

		assertArrayEquals(fromVarargs.getLayers(), fromCollection.getLayers());
	}

	@Test
	@DisplayName("the insertion index sits just before the final uncoloured pass")
	void insertionIndexIsBeforeTheLastLayer()
	{
		assertEquals(2, threeLayerCase().getLayerInsertionIndex());
	}

	@Test
	@DisplayName("addLayers slots new layers in front of the final pass")
	void addLayersInsertsBeforeTheFinalPass()
	{
		ShaderCaseItem shader = threeLayerCase();
		shader.addLayers(layer("inserted", 0x44));

		ShaderLayer[] layers = shader.getLayers();
		assertEquals(4, layers.length);
		assertEquals(0x11, layers[0].getColour());
		assertEquals(0x22, layers[1].getColour());
		assertEquals(0x44, layers[2].getColour(), "the new layer must land before the final pass");
		assertEquals(0x33, layers[3].getColour(), "the final pass must stay last");
	}

	@Test
	@DisplayName("addLayers is fluent and accepts several layers at once")
	void addLayersIsFluentAndVariadic()
	{
		ShaderCaseItem shader = threeLayerCase();

		assertSame(shader, shader.addLayers(layer("x", 0x44), layer("y", 0x55)));
		assertEquals(5, shader.getLayers().length);
		assertEquals(0x44, shader.getLayers()[2].getColour());
		assertEquals(0x55, shader.getLayers()[3].getColour());
		assertEquals(0x33, shader.getLayers()[4].getColour());
	}

	@Test
	@DisplayName("addLayers at an explicit index inserts exactly there")
	void addLayersAtAnExplicitIndex()
	{
		ShaderCaseItem shader = threeLayerCase();
		shader.addLayers(0, layer("first", 0x99));

		assertEquals(0x99, shader.getLayers()[0].getColour());
		assertEquals(0x11, shader.getLayers()[1].getColour());
		assertEquals(4, shader.getLayers().length);
	}

	@Test
	@DisplayName("adding no layers leaves the stack untouched")
	void addingNothingChangesNothing()
	{
		ShaderCaseItem shader = threeLayerCase();
		ShaderLayer[] before = shader.getLayers().clone();
		shader.addLayers();

		assertArrayEquals(before, shader.getLayers());
	}

	@Test
	@DisplayName("the replacement sprite and colour are read off the layer for that pass")
	void replacementSpriteAndColourFollowThePass()
	{
		ShaderCaseItem shader = threeLayerCase();

		for(int pass = 0; pass < shader.getLayers().length; pass++)
		{
			assertEquals(shader.getLayers()[pass].getTexture(),
					shader.getReplacementSprite(null, null, "part", pass));
			assertEquals(shader.getLayers()[pass].getColour(),
					shader.getARGBColourModifier(null, null, "part", pass));
		}
	}

	@Test
	@DisplayName("item shaders stitch into the main texture sheet")
	void itemShadersAreStitched()
	{
		assertTrue(threeLayerCase().stitchIntoSheet());
	}

	@Test
	@DisplayName("a ShaderLayer keeps the texture and colour it was built with")
	void shaderLayerIsAValueHolder()
	{
		ResourceLocation texture = new ResourceLocation("immersiveengineering", "items/shader_0");
		ShaderLayer layer = new ShaderLayer(texture, 0xdeadbeef);

		assertSame(texture, layer.getTexture());
		assertEquals(0xdeadbeef, layer.getColour());
	}

	@Test
	@DisplayName("a ShaderLayer starts with no texture or cutout bounds")
	void shaderLayerBoundsDefaultToNull()
	{
		ShaderLayer layer = layer("plain", 0);

		assertNull(layer.getTextureBounds());
		assertNull(layer.getCutoutBounds());
	}

	@Test
	@DisplayName("the bounds setters are fluent and store the array")
	void shaderLayerBoundsSetters()
	{
		ShaderLayer layer = layer("bounded", 0);

		assertSame(layer, layer.setTextureBounds(0, 0, 0.5, 0.5));
		assertSame(layer, layer.setCutoutBounds(0.25, 0.25, 0.75, 0.75));
		assertArrayEquals(new double[]{0, 0, 0.5, 0.5}, layer.getTextureBounds(), 0d);
		assertArrayEquals(new double[]{0.25, 0.25, 0.75, 0.75}, layer.getCutoutBounds(), 0d);
	}

	@Test
	@DisplayName("passing null bounds is a no-op rather than a wipe")
	void nullBoundsAreIgnored()
	{
		ShaderLayer layer = layer("bounded", 0);
		layer.setTextureBounds(0, 0, 1, 1);
		layer.setTextureBounds((double[])null);

		assertArrayEquals(new double[]{0, 0, 1, 1}, layer.getTextureBounds(), 0d);
	}

	@Test
	@DisplayName("only the dynamic layer subclass flags itself as dynamic")
	void onlyDynamicLayersAreDynamic()
	{
		assertFalse(layer("static", 0).isDynamicLayer());
		assertTrue(new DynamicShaderLayer(new ResourceLocation("immersiveengineering", "dyn"), 0).isDynamicLayer());
	}

	@Test
	@DisplayName("every stock shader case type is a distinct, namespaced string")
	void stockShaderTypes()
	{
		ShaderLayer single = layer("x", 0);
		assertEquals("immersiveengineering:item", new ShaderCaseItem(single).getShaderType());
		assertEquals("immersiveengineering:revolver", new ShaderCaseRevolver(single).getShaderType());
		assertEquals("immersiveengineering:chemthrower", new ShaderCaseChemthrower(single).getShaderType());
		assertEquals("immersiveengineering:drill", new ShaderCaseDrill(single).getShaderType());
		assertEquals("immersiveengineering:railgun", new ShaderCaseRailgun(single).getShaderType());
		assertEquals("immersiveengineering:shield", new ShaderCaseShield(single).getShaderType());
		assertEquals("immersiveengineering:balloon", new ShaderCaseBalloon(single).getShaderType());
		assertEquals("immersiveengineering:banner", new ShaderCaseBanner(single).getShaderType());
		assertEquals("immersiveengineering:minecart", new ShaderCaseMinecart(single).getShaderType());
	}

	@Test
	@DisplayName("minecart shaders are bound directly and start with every side visible")
	void minecartShaderDefaults()
	{
		ShaderCaseMinecart shader = new ShaderCaseMinecart(layer("a", 0), layer("b", 0));

		assertFalse(shader.stitchIntoSheet());
		assertEquals(2, shader.renderSides.length, "one row per layer");
		for(boolean[] row : shader.renderSides)
		{
			assertEquals(7, row.length, "six sides plus the inside");
			for(boolean side : row)
				assertTrue(side, "every side starts visible");
		}
	}

	@Test
	@DisplayName("adding a layer to a minecart shader grows its side table with it")
	void minecartSideTableTracksTheLayers()
	{
		ShaderCaseMinecart shader = new ShaderCaseMinecart(layer("a", 0), layer("b", 0));
		shader.addLayers(layer("c", 0));

		assertEquals(3, shader.getLayers().length);
		assertEquals(shader.getLayers().length, shader.renderSides.length,
				"the side table must stay the same length as the layer stack");
	}
}
