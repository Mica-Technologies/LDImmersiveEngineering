/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.tool.BulletHandler.DamagingBullet;
import blusunrize.immersiveengineering.api.tool.BulletHandler.IBullet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link BulletHandler}'s name-keyed cartridge registry, the legacy name mapping and
 * the plain arithmetic/defaults on {@link DamagingBullet}.
 */
class BulletHandlerTest
{
	private HashMap<String, IBullet> savedRegistry;
	private List<String> savedHoming;

	@BeforeEach
	void isolateRegistry()
	{
		savedRegistry = BulletHandler.registry;
		savedHoming = BulletHandler.homingCartridges;
		BulletHandler.registry = new LinkedHashMap<>();
		BulletHandler.homingCartridges = new ArrayList<>();
	}

	@AfterEach
	void restoreRegistry()
	{
		BulletHandler.registry = savedRegistry;
		BulletHandler.homingCartridges = savedHoming;
	}

	private static IBullet bullet()
	{
		return new IBullet()
		{
			@Override
			public void onHitTarget(World world, RayTraceResult target, EntityLivingBase shooter, Entity projectile,
									boolean headshot)
			{
			}

			@Override
			public ItemStack getCasing(ItemStack stack)
			{
				return ItemStack.EMPTY;
			}

			@Override
			public ResourceLocation[] getTextures()
			{
				return new ResourceLocation[0];
			}

			@Override
			public int getColour(ItemStack stack, int layer)
			{
				return 0;
			}
		};
	}

	@Test
	@DisplayName("a registered cartridge comes back under its name")
	void registerAndLookUp()
	{
		IBullet b = bullet();
		BulletHandler.registerBullet("casull", b);

		assertSame(b, BulletHandler.getBullet("casull"));
	}

	@Test
	@DisplayName("an unregistered name yields null")
	void unknownNameYieldsNull()
	{
		assertNull(BulletHandler.getBullet("not_a_bullet"));
	}

	@Test
	@DisplayName("re-registering a name replaces the previous cartridge")
	void reRegistrationReplaces()
	{
		IBullet first = bullet();
		IBullet second = bullet();
		BulletHandler.registerBullet("casull", first);
		BulletHandler.registerBullet("casull", second);

		assertEquals(1, BulletHandler.registry.size());
		assertSame(second, BulletHandler.getBullet("casull"));
	}

	@Test
	@DisplayName("the registry keeps registration order")
	void registryKeepsOrder()
	{
		BulletHandler.registerBullet("a", bullet());
		BulletHandler.registerBullet("b", bullet());
		BulletHandler.registerBullet("c", bullet());

		assertArrayEquals(new String[]{"a", "b", "c"}, BulletHandler.registry.keySet().toArray(new String[0]));
	}

	@Test
	@DisplayName("the legacy camel-case names are translated")
	void legacyNamesAreTranslated()
	{
		assertEquals("armor_piercing", BulletHandler.handleLeagcyNames("armorPiercing"));
		assertEquals("he", BulletHandler.handleLeagcyNames("HE"));
	}

	@Test
	@DisplayName("a modern name passes through the legacy mapping untouched")
	void modernNamesPassThrough()
	{
		assertEquals("armor_piercing", BulletHandler.handleLeagcyNames("armor_piercing"));
		assertEquals("casull", BulletHandler.handleLeagcyNames("casull"));
		assertEquals("", BulletHandler.handleLeagcyNames(""));
	}

	@Test
	@DisplayName("the legacy mapping is case sensitive")
	void legacyMappingIsCaseSensitive()
	{
		assertEquals("armorpiercing", BulletHandler.handleLeagcyNames("armorpiercing"));
		assertEquals("he", BulletHandler.handleLeagcyNames("he"));
	}

	@Test
	@DisplayName("getBullet resolves a legacy name before the lookup")
	void getBulletResolvesLegacyNames()
	{
		IBullet b = bullet();
		BulletHandler.registerBullet("armor_piercing", b);

		assertSame(b, BulletHandler.getBullet("armorPiercing"));
		assertSame(b, BulletHandler.getBullet("armor_piercing"));
	}

	@Test
	@DisplayName("findRegistryName is the inverse of registerBullet")
	void findRegistryNameRoundTrip()
	{
		IBullet a = bullet();
		IBullet b = bullet();
		BulletHandler.registerBullet("a", a);
		BulletHandler.registerBullet("b", b);

		assertEquals("a", BulletHandler.findRegistryName(a));
		assertEquals("b", BulletHandler.findRegistryName(b));
	}

	@Test
	@DisplayName("findRegistryName copes with null and with unknown cartridges")
	void findRegistryNameEdgeCases()
	{
		BulletHandler.registerBullet("a", bullet());

		assertNull(BulletHandler.findRegistryName(null));
		assertNull(BulletHandler.findRegistryName(bullet()));
	}

	@Test
	@DisplayName("IBullet's defaults describe an ordinary, non-turret cartridge")
	void bulletDefaults()
	{
		IBullet b = bullet();

		assertTrue(b.isProperCartridge());
		assertFalse(b.isValidForTurret());
		assertEquals(1, b.getProjectileCount(null));
		assertNull(b.getSound());
		assertEquals("base", b.getTranslationKey(ItemStack.EMPTY, "base"));
	}

	@Test
	@DisplayName("the default projectile is handed straight back")
	void defaultProjectileIsUnchanged()
	{
		assertNull(bullet().getProjectile(null, ItemStack.EMPTY, null, false));
	}

	@Test
	@DisplayName("DamagingBullet reports its base damage on a body shot")
	void damagingBulletBodyShot()
	{
		DamagingBullet b = new DamagingBullet(nullSource(), 10f, ItemStack.EMPTY);

		assertEquals(10f, b.getDamage(false), 1e-6);
	}

	@Test
	@DisplayName("a headshot is worth one and a half times the damage")
	void damagingBulletHeadshot()
	{
		DamagingBullet b = new DamagingBullet(nullSource(), 10f, ItemStack.EMPTY);

		assertEquals(15f, b.getDamage(true), 1e-6);
	}

	@Test
	@DisplayName("the headshot multiplier holds at zero and for fractional damage")
	void damagingBulletDamageBoundaries()
	{
		assertEquals(0f, new DamagingBullet(nullSource(), 0f, ItemStack.EMPTY).getDamage(true), 1e-6);
		assertEquals(0.75f, new DamagingBullet(nullSource(), 0.5f, ItemStack.EMPTY).getDamage(true), 1e-6);
	}

	@Test
	@DisplayName("DamagingBullet hands back the casing and textures it was built with")
	void damagingBulletCarriesCasingAndTextures()
	{
		ResourceLocation[] textures = {new ResourceLocation("test:a"), new ResourceLocation("test:b")};
		DamagingBullet b = new DamagingBullet(nullSource(), 1f, ItemStack.EMPTY, textures);

		assertSame(ItemStack.EMPTY, b.getCasing(ItemStack.EMPTY));
		assertArrayEquals(textures, b.getTextures());
	}

	@Test
	@DisplayName("DamagingBullet renders untinted and is turret-safe")
	void damagingBulletDefaults()
	{
		DamagingBullet b = new DamagingBullet(nullSource(), 1f, ItemStack.EMPTY);

		assertEquals(0xffffffff, b.getColour(ItemStack.EMPTY, 0));
		assertEquals(0xffffffff, b.getColour(ItemStack.EMPTY, 3));
		assertTrue(b.isValidForTurret());
		assertTrue(b.isProperCartridge());
	}

	private static Function<Entity[], DamageSource> nullSource()
	{
		return e -> null;
	}
}
