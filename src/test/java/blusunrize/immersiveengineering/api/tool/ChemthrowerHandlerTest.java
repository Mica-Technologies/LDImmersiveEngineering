/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.tool.ChemthrowerHandler.ChemthrowerEffect;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ChemthrowerHandler}'s three fluid-name registries. Only the name-keyed and
 * {@link Fluid}-keyed entry points are exercised -- applying an effect needs a live entity
 * or world.
 */
class ChemthrowerHandlerTest
{
	private HashMap<String, ChemthrowerEffect> savedEffects;
	private HashSet<String> savedFlammable;
	private HashSet<String> savedGas;

	@BeforeEach
	void isolateRegistries()
	{
		savedEffects = ChemthrowerHandler.effectMap;
		savedFlammable = ChemthrowerHandler.flammableList;
		savedGas = ChemthrowerHandler.gasList;
		ChemthrowerHandler.effectMap = new HashMap<>();
		ChemthrowerHandler.flammableList = new HashSet<>();
		ChemthrowerHandler.gasList = new HashSet<>();
	}

	@AfterEach
	void restoreRegistries()
	{
		ChemthrowerHandler.effectMap = savedEffects;
		ChemthrowerHandler.flammableList = savedFlammable;
		ChemthrowerHandler.gasList = savedGas;
	}

	private static Fluid fluid(String name)
	{
		return new Fluid(name, new ResourceLocation("test:still"), new ResourceLocation("test:flowing"));
	}

	private static ChemthrowerEffect effect()
	{
		return new ChemthrowerEffect()
		{
			@Override
			public void applyToEntity(EntityLivingBase target, EntityPlayer shooter, ItemStack thrower, Fluid f)
			{
			}

			@Override
			public void applyToBlock(World world, RayTraceResult mop, EntityPlayer shooter, ItemStack thrower, Fluid f)
			{
			}
		};
	}

	@Test
	@DisplayName("an effect registered by name comes back by name")
	void effectByName()
	{
		ChemthrowerEffect e = effect();
		ChemthrowerHandler.registerEffect("creosote", e);

		assertSame(e, ChemthrowerHandler.getEffect("creosote"));
	}

	@Test
	@DisplayName("an effect registered for a fluid is keyed by that fluid's name")
	void effectByFluid()
	{
		Fluid f = fluid("creosote");
		ChemthrowerEffect e = effect();
		ChemthrowerHandler.registerEffect(f, e);

		assertSame(e, ChemthrowerHandler.getEffect(f));
		assertSame(e, ChemthrowerHandler.getEffect(f.getName()));
	}

	@Test
	@DisplayName("an unregistered fluid has no effect")
	void unregisteredFluidHasNoEffect()
	{
		assertNull(ChemthrowerHandler.getEffect("nothing_here"));
		assertNull(ChemthrowerHandler.getEffect(fluid("nothing_here")));
	}

	@Test
	@DisplayName("a null fluid is tolerated on both ends")
	void nullFluidIsTolerated()
	{
		assertNull(ChemthrowerHandler.getEffect((Fluid)null));

		ChemthrowerHandler.registerEffect((Fluid)null, effect());
		assertTrue(ChemthrowerHandler.effectMap.isEmpty(), "a null fluid must not register anything");
	}

	@Test
	@DisplayName("registering a second effect for the same fluid replaces the first")
	void effectRegistrationReplaces()
	{
		ChemthrowerEffect first = effect();
		ChemthrowerEffect second = effect();
		ChemthrowerHandler.registerEffect("creosote", first);
		ChemthrowerHandler.registerEffect("creosote", second);

		assertEquals(1, ChemthrowerHandler.effectMap.size());
		assertSame(second, ChemthrowerHandler.getEffect("creosote"));
	}

	@Test
	@DisplayName("flammability is tracked by fluid name")
	void flammableByName()
	{
		ChemthrowerHandler.registerFlammable("ethanol");

		assertTrue(ChemthrowerHandler.isFlammable("ethanol"));
		assertFalse(ChemthrowerHandler.isFlammable("water"));
	}

	@Test
	@DisplayName("flammability can be registered from a Fluid")
	void flammableByFluid()
	{
		Fluid f = fluid("ethanol");
		ChemthrowerHandler.registerFlammable(f);

		assertTrue(ChemthrowerHandler.isFlammable(f));
		assertTrue(ChemthrowerHandler.isFlammable(f.getName()));
	}

	@Test
	@DisplayName("a null fluid is never flammable and registers nothing")
	void nullFluidIsNotFlammable()
	{
		assertFalse(ChemthrowerHandler.isFlammable((Fluid)null));

		ChemthrowerHandler.registerFlammable((Fluid)null);
		assertTrue(ChemthrowerHandler.flammableList.isEmpty());
	}

	@Test
	@DisplayName("registering the same flammable fluid twice does not duplicate it")
	void flammableIsASet()
	{
		ChemthrowerHandler.registerFlammable("ethanol");
		ChemthrowerHandler.registerFlammable("ethanol");

		assertEquals(1, ChemthrowerHandler.flammableList.size());
	}

	@Test
	@DisplayName("gas dispersal is tracked by fluid name")
	void gasByName()
	{
		ChemthrowerHandler.registerGas("chlorine");

		assertTrue(ChemthrowerHandler.isGas("chlorine"));
		assertFalse(ChemthrowerHandler.isGas("water"));
	}

	@Test
	@DisplayName("gas dispersal can be registered from a Fluid")
	void gasByFluid()
	{
		Fluid f = fluid("chlorine");
		ChemthrowerHandler.registerGas(f);

		assertTrue(ChemthrowerHandler.isGas(f));
	}

	@Test
	@DisplayName("a null fluid is never a gas and registers nothing")
	void nullFluidIsNotGas()
	{
		assertFalse(ChemthrowerHandler.isGas((Fluid)null));

		ChemthrowerHandler.registerGas((Fluid)null);
		assertTrue(ChemthrowerHandler.gasList.isEmpty());
	}

	@Test
	@DisplayName("the three registries are independent of each other")
	void registriesAreIndependent()
	{
		ChemthrowerHandler.registerFlammable("ethanol");

		assertTrue(ChemthrowerHandler.isFlammable("ethanol"));
		assertFalse(ChemthrowerHandler.isGas("ethanol"));
		assertNull(ChemthrowerHandler.getEffect("ethanol"));
	}

	@Test
	@DisplayName("a fluid can be both flammable and a gas")
	void aFluidCanBeBoth()
	{
		ChemthrowerHandler.registerFlammable("hydrogen");
		ChemthrowerHandler.registerGas("hydrogen");

		assertTrue(ChemthrowerHandler.isFlammable("hydrogen"));
		assertTrue(ChemthrowerHandler.isGas("hydrogen"));
	}
}
