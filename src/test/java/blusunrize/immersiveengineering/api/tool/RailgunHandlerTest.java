/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.api.tool.RailgunHandler.RailgunProjectileProperties;
import net.minecraft.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link RailgunHandler}'s projectile registry and the plain data held by
 * {@link RailgunProjectileProperties}. Looking a projectile up needs a real ItemStack, so
 * only the empty-stack path of {@code getProjectileProperties} is exercised.
 */
class RailgunHandlerTest
{
	private ArrayList<Pair<IngredientStack, RailgunProjectileProperties>> savedMap;

	@BeforeEach
	void isolateRegistry()
	{
		savedMap = RailgunHandler.projectilePropertyMap;
		RailgunHandler.projectilePropertyMap = new ArrayList<>();
	}

	@AfterEach
	void restoreRegistry()
	{
		RailgunHandler.projectilePropertyMap = savedMap;
	}

	@Test
	@DisplayName("registering returns properties carrying the requested damage and gravity")
	void registrationReturnsProperties()
	{
		RailgunProjectileProperties p = RailgunHandler.registerProjectileProperties(
				new IngredientStack("stickIron", 1), 20, .05);

		assertNotNull(p);
		assertEquals(20, p.damage, 1e-9);
		assertEquals(.05, p.gravity, 1e-9);
	}

	@Test
	@DisplayName("registering files the ingredient and its properties together")
	void registrationFilesThePair()
	{
		IngredientStack ingr = new IngredientStack("stickIron", 1);
		RailgunProjectileProperties p = RailgunHandler.registerProjectileProperties(ingr, 20, .05);

		assertEquals(1, RailgunHandler.projectilePropertyMap.size());
		assertSame(ingr, RailgunHandler.projectilePropertyMap.get(0).getLeft());
		assertSame(p, RailgunHandler.projectilePropertyMap.get(0).getRight());
	}

	@Test
	@DisplayName("registrations accumulate in order")
	void registrationsAccumulateInOrder()
	{
		RailgunProjectileProperties a = RailgunHandler.registerProjectileProperties(
				new IngredientStack("stickIron", 1), 10, .01);
		RailgunProjectileProperties b = RailgunHandler.registerProjectileProperties(
				new IngredientStack("stickSteel", 1), 20, .02);

		assertEquals(2, RailgunHandler.projectilePropertyMap.size());
		assertSame(a, RailgunHandler.projectilePropertyMap.get(0).getRight());
		assertSame(b, RailgunHandler.projectilePropertyMap.get(1).getRight());
	}

	@Test
	@DisplayName("the same ingredient can be registered twice; both entries are kept")
	void duplicateRegistrationsAreKept()
	{
		RailgunHandler.registerProjectileProperties(new IngredientStack("stickIron", 1), 10, .01);
		RailgunHandler.registerProjectileProperties(new IngredientStack("stickIron", 1), 20, .02);

		assertEquals(2, RailgunHandler.projectilePropertyMap.size(),
				"the registry is a list, not a map -- the first match wins at lookup time");
	}

	@Test
	@DisplayName("an empty stack never matches a registered projectile")
	void emptyStackHasNoProperties()
	{
		RailgunHandler.registerProjectileProperties(new IngredientStack("stickIron", 1), 20, .05);

		assertNull(RailgunHandler.getProjectileProperties(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("an empty registry yields no properties for anything")
	void emptyRegistryYieldsNull()
	{
		assertNull(RailgunHandler.getProjectileProperties(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("negative damage and gravity are stored verbatim")
	void extremeValuesAreStoredVerbatim()
	{
		RailgunProjectileProperties p = RailgunHandler.registerProjectileProperties(
				new IngredientStack("stickIron", 1), -5, -1.5);

		assertEquals(-5, p.damage, 1e-9);
		assertEquals(-1.5, p.gravity, 1e-9);
	}

	@Test
	@DisplayName("the default colour map is a single five-wide grey row")
	void defaultColourMap()
	{
		RailgunProjectileProperties p = new RailgunProjectileProperties(1, 1);

		assertEquals(1, p.colourMap.length);
		assertEquals(5, p.colourMap[0].length);
		assertEquals(0x686868, p.colourMap[0][0]);
		assertEquals(0xa4a4a4, p.colourMap[0][2]);
		assertEquals(0x686868, p.colourMap[0][4]);
	}

	@Test
	@DisplayName("setColourMap replaces the map and is chainable")
	void setColourMapIsChainable()
	{
		RailgunProjectileProperties p = new RailgunProjectileProperties(1, 1);
		int[][] map = {{1, 2}, {3, 4}};

		assertSame(p, p.setColourMap(map));
		assertSame(map, p.colourMap);
	}

	@Test
	@DisplayName("a plain projectile does not override hit handling")
	void overrideHitEntityDefaultsToFalse()
	{
		assertFalse(new RailgunProjectileProperties(1, 1).overrideHitEntity(null, null));
	}
}
