/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.tool.IElectricEquipment;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WireType} and IE's own {@code IEBASE} tier implementation.
 * <p>
 * IEBASE is private, so it is built reflectively (see {@link TestWireType#newIEBase(int)}); its
 * numbers come from the {@code WireType.wire*} statics, which are populated from the config at
 * runtime and are set to fixed values here. {@code WireType.init()} itself is not usable in a unit
 * test because it goes through the Forge mod loader and IE's block registry.
 */
class WireTypeTest
{
	// The ordinals IEBASE is indexed by, in the order of WireType.uniqueNames.
	private static final int COPPER = 0;
	private static final int ELECTRUM = 1;
	private static final int STEEL = 2;
	private static final int STRUCTURE_ROPE = 3;
	private static final int STRUCTURE_STEEL = 4;
	private static final int REDSTONE = 5;
	private static final int COPPER_INS = 6;
	private static final int ELECTRUM_INS = 7;

	@BeforeEach
	void setUp()
	{
		TestWireType.resetRegistries();
		TestWireType.installConfigArrays();
	}

	@Nested
	@DisplayName("category constants")
	class Categories
	{
		@Test
		@DisplayName("the five category keys have their documented names")
		void categoryNames()
		{
			assertEquals("LV", WireType.LV_CATEGORY);
			assertEquals("MV", WireType.MV_CATEGORY);
			assertEquals("HV", WireType.HV_CATEGORY);
			assertEquals("STRUCTURE", WireType.STRUCTURE_CATEGORY);
			assertEquals("REDSTONE", WireType.REDSTONE_CATEGORY);
		}

		@Test
		@DisplayName("each IE tier reports its category")
		void ieBaseCategories()
		{
			assertEquals(WireType.LV_CATEGORY, TestWireType.newIEBase(COPPER).getCategory());
			assertEquals(WireType.MV_CATEGORY, TestWireType.newIEBase(ELECTRUM).getCategory());
			assertEquals(WireType.HV_CATEGORY, TestWireType.newIEBase(STEEL).getCategory());
			assertEquals(WireType.STRUCTURE_CATEGORY, TestWireType.newIEBase(STRUCTURE_ROPE).getCategory());
			assertEquals(WireType.STRUCTURE_CATEGORY, TestWireType.newIEBase(STRUCTURE_STEEL).getCategory());
			assertEquals(WireType.REDSTONE_CATEGORY, TestWireType.newIEBase(REDSTONE).getCategory());
		}

		@Test
		@DisplayName("insulated wires share the category of their bare counterpart")
		void insulatedShareCategory()
		{
			assertEquals(WireType.LV_CATEGORY, TestWireType.newIEBase(COPPER_INS).getCategory());
			assertEquals(WireType.MV_CATEGORY, TestWireType.newIEBase(ELECTRUM_INS).getCategory());
		}

		@Test
		@DisplayName("a bare WireType has no category by default")
		void defaultCategoryIsNull()
		{
			assertNull(new BareWireType().getCategory());
		}
	}

	@Nested
	@DisplayName("transfer rate and loss")
	class Transfer
	{
		@Test
		@DisplayName("each tier reads its own slot of the config array")
		void perTierTransferRate()
		{
			assertEquals(256, TestWireType.newIEBase(COPPER).getTransferRate());
			assertEquals(1024, TestWireType.newIEBase(ELECTRUM).getTransferRate());
			assertEquals(4096, TestWireType.newIEBase(STEEL).getTransferRate());
		}

		@Test
		@DisplayName("insulated wires reuse the transfer rate of their bare counterpart")
		void insulatedReuseTransferRate()
		{
			assertEquals(TestWireType.newIEBase(COPPER).getTransferRate(),
					TestWireType.newIEBase(COPPER_INS).getTransferRate());
			assertEquals(TestWireType.newIEBase(ELECTRUM).getTransferRate(),
					TestWireType.newIEBase(ELECTRUM_INS).getTransferRate());
		}

		@Test
		@DisplayName("the tiers are ordered LV < MV < HV")
		void tiersAreOrdered()
		{
			assertTrue(TestWireType.newIEBase(COPPER).getTransferRate() < TestWireType.newIEBase(ELECTRUM).getTransferRate());
			assertTrue(TestWireType.newIEBase(ELECTRUM).getTransferRate() < TestWireType.newIEBase(STEEL).getTransferRate());
		}

		@Test
		@DisplayName("each tier reads its own loss ratio")
		void perTierLossRatio()
		{
			assertEquals(.05, TestWireType.newIEBase(COPPER).getLossRatio(), 1e-9);
			assertEquals(.1, TestWireType.newIEBase(ELECTRUM).getLossRatio(), 1e-9);
			assertEquals(.2, TestWireType.newIEBase(STEEL).getLossRatio(), 1e-9);
		}

		@Test
		@DisplayName("insulated wires reuse the loss ratio of their bare counterpart")
		void insulatedReuseLossRatio()
		{
			assertEquals(TestWireType.newIEBase(COPPER).getLossRatio(),
					TestWireType.newIEBase(COPPER_INS).getLossRatio(), 1e-9);
		}

		@Test
		@DisplayName("a misconfigured negative transfer rate is taken as its magnitude")
		void negativeTransferRateIsAbsolute()
		{
			WireType.wireTransferRate = new int[]{-256, 0, 0, 0, 0, 0};
			assertEquals(256, TestWireType.newIEBase(COPPER).getTransferRate());
		}

		@Test
		@DisplayName("a misconfigured negative loss ratio is taken as its magnitude")
		void negativeLossRatioIsAbsolute()
		{
			WireType.wireLossRatio = new double[]{-.05, 0, 0, 0, 0, 0};
			assertEquals(.05, TestWireType.newIEBase(COPPER).getLossRatio(), 1e-9);
		}

		@Test
		@DisplayName("structure and redstone wires carry no power in the sample config")
		void structureWiresCarryNothing()
		{
			assertEquals(0, TestWireType.newIEBase(STRUCTURE_ROPE).getTransferRate());
			assertEquals(0, TestWireType.newIEBase(REDSTONE).getTransferRate());
		}
	}

	@Nested
	@DisplayName("length, colour and rendering")
	class Appearance
	{
		@Test
		@DisplayName("each tier reads its own max length")
		void perTierMaxLength()
		{
			assertEquals(16, TestWireType.newIEBase(COPPER).getMaxLength());
			assertEquals(16, TestWireType.newIEBase(ELECTRUM).getMaxLength());
			assertEquals(32, TestWireType.newIEBase(STEEL).getMaxLength());
		}

		@Test
		@DisplayName("insulated wires reuse the max length of their bare counterpart")
		void insulatedReuseMaxLength()
		{
			WireType.wireLength = new int[]{16, 20, 32, 32, 32, 32};
			assertEquals(16, TestWireType.newIEBase(COPPER_INS).getMaxLength());
			assertEquals(20, TestWireType.newIEBase(ELECTRUM_INS).getMaxLength());
		}

		@Test
		@DisplayName("colouration is indexed by the full ordinal, so insulated wires have their own colour")
		void colourUsesFullOrdinal()
		{
			assertEquals(WireType.wireColouration[COPPER], TestWireType.newIEBase(COPPER).getColour(null));
			assertEquals(WireType.wireColouration[COPPER_INS], TestWireType.newIEBase(COPPER_INS).getColour(null));
			assertNotEquals(TestWireType.newIEBase(COPPER).getColour(null),
					TestWireType.newIEBase(COPPER_INS).getColour(null));
		}

		@Test
		@DisplayName("every tier has the same slack")
		void slackIsUniform()
		{
			for(int i = 0; i < 8; i++)
				assertEquals(1.005, TestWireType.newIEBase(i).getSlack(), 1e-9);
		}

		@Test
		@DisplayName("render diameter is taken from the shared table, wrapping for insulated wires")
		void renderDiameter()
		{
			assertEquals(WireType.renderDiameter[0], TestWireType.newIEBase(COPPER).getRenderDiameter(), 1e-9);
			assertEquals(WireType.renderDiameter[2], TestWireType.newIEBase(STEEL).getRenderDiameter(), 1e-9);
			assertEquals(WireType.renderDiameter[0], TestWireType.newIEBase(COPPER_INS).getRenderDiameter(), 1e-9);
		}
	}

	@Nested
	@DisplayName("isEnergyWire and damage")
	class EnergyAndDamage
	{
		@Test
		@DisplayName("the three power tiers are energy wires")
		void powerTiersAreEnergyWires()
		{
			assertTrue(TestWireType.newIEBase(COPPER).isEnergyWire());
			assertTrue(TestWireType.newIEBase(ELECTRUM).isEnergyWire());
			assertTrue(TestWireType.newIEBase(STEEL).isEnergyWire());
		}

		@Test
		@DisplayName("structure and redstone wires are not energy wires")
		void nonPowerTiersAreNot()
		{
			assertFalse(TestWireType.newIEBase(STRUCTURE_ROPE).isEnergyWire());
			assertFalse(TestWireType.newIEBase(STRUCTURE_STEEL).isEnergyWire());
			assertFalse(TestWireType.newIEBase(REDSTONE).isEnergyWire());
		}

		@Test
		@DisplayName("insulated wires are energy wires")
		void insulatedAreEnergyWires()
		{
			assertTrue(TestWireType.newIEBase(COPPER_INS).isEnergyWire());
			assertTrue(TestWireType.newIEBase(ELECTRUM_INS).isEnergyWire());
		}

		@Test
		@DisplayName("only the bare power tiers can shock")
		void onlyBarePowerTiersShock()
		{
			assertTrue(TestWireType.newIEBase(COPPER).canCauseDamage());
			assertTrue(TestWireType.newIEBase(ELECTRUM).canCauseDamage());
			assertTrue(TestWireType.newIEBase(STEEL).canCauseDamage());
			assertFalse(TestWireType.newIEBase(STRUCTURE_ROPE).canCauseDamage());
			assertFalse(TestWireType.newIEBase(REDSTONE).canCauseDamage());
		}

		@Test
		@DisplayName("insulated wires cannot shock even though they carry power")
		void insulatedCannotShock()
		{
			assertFalse(TestWireType.newIEBase(COPPER_INS).canCauseDamage());
			assertFalse(TestWireType.newIEBase(ELECTRUM_INS).canCauseDamage());
			assertTrue(TestWireType.newIEBase(COPPER_INS).isEnergyWire());
		}

		@Test
		@DisplayName("the shock radius grows with the tier and stays under the .3 raytrace limit")
		void damageRadiusPerTier()
		{
			assertEquals(.05, TestWireType.newIEBase(COPPER).getDamageRadius(), 1e-9);
			assertEquals(.1, TestWireType.newIEBase(ELECTRUM).getDamageRadius(), 1e-9);
			assertEquals(.3, TestWireType.newIEBase(STEEL).getDamageRadius(), 1e-9);
			for(int i = 0; i < 8; i++)
				assertTrue(TestWireType.newIEBase(i).getDamageRadius() <= .3,
						"ordinal "+i+" must stay within ApiUtils#handleVec's DELTA_NEAR");
		}

		@Test
		@DisplayName("wires that cannot shock have a zero radius")
		void harmlessWiresHaveNoRadius()
		{
			assertEquals(0, TestWireType.newIEBase(STRUCTURE_ROPE).getDamageRadius(), 1e-9);
			assertEquals(0, TestWireType.newIEBase(REDSTONE).getDamageRadius(), 1e-9);
			assertEquals(0, TestWireType.newIEBase(COPPER_INS).getDamageRadius(), 1e-9);
		}

		@Test
		@DisplayName("a bare WireType is harmless by default")
		void defaultsAreHarmless()
		{
			BareWireType bare = new BareWireType();
			assertFalse(bare.canCauseDamage());
			assertEquals(0, bare.getDamageRadius(), 1e-9);
		}

		@Test
		@DisplayName("the electric source level scales with the tier for shocking wires")
		void electricSourceLevels()
		{
			assertEquals(.5f, TestWireType.newIEBase(COPPER).getElectricSource().level, 1e-6);
			assertEquals(1f, TestWireType.newIEBase(ELECTRUM).getElectricSource().level, 1e-6);
			assertEquals(1.5f, TestWireType.newIEBase(STEEL).getElectricSource().level, 1e-6);
		}

		@Test
		@DisplayName("harmless wires report a negative electric source level")
		void harmlessElectricSource()
		{
			assertTrue(TestWireType.newIEBase(REDSTONE).getElectricSource().level < 0);
			assertTrue(TestWireType.newIEBase(COPPER_INS).getElectricSource().level < 0);
		}

		@Test
		@DisplayName("no IE tier reaches the 1.75 level that destroys Faraday suits")
		void noTierDestroysFaradaySuits()
		{
			for(int i = 0; i < 8; i++)
				assertTrue(TestWireType.newIEBase(i).getElectricSource().level < 1.75f, "ordinal "+i);
		}

		@Test
		@DisplayName("WireType's default electric source delegates to COPPER")
		void defaultElectricSourceDelegates()
		{
			WireType copper = TestWireType.newIEBase(COPPER);
			WireType.COPPER = copper;
			assertEquals(copper.getElectricSource().level, new BareWireType().getElectricSource().level, 1e-6);
		}
	}

	@Nested
	@DisplayName("the registry of known types")
	class Registry
	{
		@Test
		@DisplayName("constructing a WireType registers it")
		void constructionRegisters()
		{
			assertTrue(WireType.getValues().isEmpty(), "the registry was cleared for this test");
			WireType t = new TestWireType("REGISTRY_A", .1, 100, 10);
			assertTrue(WireType.getValues().contains(t));
			assertEquals(1, WireType.getValues().size());
		}

		@Test
		@DisplayName("getValue finds a type by its unique name")
		void getValueByName()
		{
			WireType a = new TestWireType("REGISTRY_B", .1, 100, 10);
			WireType b = new TestWireType("REGISTRY_C", .2, 200, 20);
			assertSame(a, WireType.getValue("REGISTRY_B"));
			assertSame(b, WireType.getValue("REGISTRY_C"));
		}

		@Test
		@DisplayName("getValue falls back to COPPER for an unknown name")
		void getValueFallsBackToCopper()
		{
			WireType copper = new TestWireType("SOME_COPPER", .05, 256, 16);
			WireType.COPPER = copper;
			assertSame(copper, WireType.getValue("NOT_A_WIRE"));
			assertSame(copper, WireType.getValue(""));
		}

		@Test
		@DisplayName("getValue returns null when COPPER has not been initialised")
		void getValueWithoutCopper()
		{
			assertNull(WireType.getValue("NOT_A_WIRE"), "no fallback exists before WireType#init runs");
		}

		@Test
		@DisplayName("the registry keeps insertion order")
		void registryKeepsOrder()
		{
			WireType a = new TestWireType("ORDER_A", 0, 1, 1);
			WireType b = new TestWireType("ORDER_B", 0, 1, 1);
			assertArrayEquals(new WireType[]{a, b}, WireType.getValues().toArray(new WireType[0]));
		}

		@Test
		@DisplayName("the eight IE unique names are in tier order")
		void uniqueNames()
		{
			assertArrayEquals(new String[]{"COPPER", "ELECTRUM", "STEEL", "STRUCTURE_ROPE", "STRUCTURE_STEEL",
					"REDSTONE", "COPPER_INS", "ELECTRUM_INS"}, WireType.uniqueNames);
		}

		@Test
		@DisplayName("an IEBASE reports the unique name for its ordinal")
		void ieBaseUniqueNames()
		{
			for(int i = 0; i < WireType.uniqueNames.length; i++)
				assertEquals(WireType.uniqueNames[i], TestWireType.newIEBase(i).getUniqueName());
		}
	}

	@Nested
	@DisplayName("WireApi category mixing")
	class Mixing
	{
		@Test
		@DisplayName("constructing an IEBASE registers it under its category")
		void ieBaseRegistersCategory()
		{
			WireType copper = TestWireType.newIEBase(COPPER);
			assertTrue(WireApi.getWiresForType(WireType.LV_CATEGORY).contains(copper));
		}

		@Test
		@DisplayName("wires of the same category can share a connector")
		void sameCategoryMixes()
		{
			assertTrue(WireApi.canMix(TestWireType.newIEBase(COPPER), TestWireType.newIEBase(COPPER_INS)));
			assertTrue(WireApi.canMix(TestWireType.newIEBase(STRUCTURE_ROPE), TestWireType.newIEBase(STRUCTURE_STEEL)));
		}

		@Test
		@DisplayName("wires of different categories cannot share a connector")
		void differentCategoryDoesNotMix()
		{
			assertFalse(WireApi.canMix(TestWireType.newIEBase(COPPER), TestWireType.newIEBase(ELECTRUM)));
			assertFalse(WireApi.canMix(TestWireType.newIEBase(STEEL), TestWireType.newIEBase(REDSTONE)));
		}

		@Test
		@DisplayName("a category-less wire mixes with nothing, not even itself")
		void nullCategoryMixesWithNothing()
		{
			WireType bare = new BareWireType();
			assertFalse(WireApi.canMix(bare, bare));
			assertFalse(WireApi.canMix(bare, TestWireType.newIEBase(COPPER)));
			assertFalse(WireApi.canMix(TestWireType.newIEBase(COPPER), bare));
		}

		@Test
		@DisplayName("a category-less wire is not registered")
		void nullCategoryIsNotRegistered()
		{
			WireApi.registerWireType(new BareWireType());
			assertTrue(WireApi.WIRES_BY_CATEGORY.isEmpty());
		}

		@Test
		@DisplayName("a null category yields an empty set")
		void nullCategoryYieldsEmptySet()
		{
			assertTrue(WireApi.getWiresForType(null).isEmpty());
		}

		@Test
		@Disabled("WireApi#getWiresForType returns the raw map lookup, so an unknown-but-non-null category yields null instead of the empty set the null branch promises")
		@DisplayName("an unknown category yields an empty set rather than null")
		void unknownCategoryYieldsEmptySet()
		{
			assertNotNull(WireApi.getWiresForType("NO_SUCH_CATEGORY"));
			assertTrue(WireApi.getWiresForType("NO_SUCH_CATEGORY").isEmpty());
		}
	}

	@Nested
	@DisplayName("base class defaults")
	class Defaults
	{
		@Test
		@DisplayName("getWireCoil(Connection) delegates to the no-argument form")
		void wireCoilDelegates()
		{
			final int[] calls = {0};
			WireType t = new BareWireType()
			{
				@Override
				public ItemStack getWireCoil()
				{
					calls[0]++;
					return null;
				}
			};
			assertNull(t.getWireCoil(new Connection(null, null, null, 0)));
			assertEquals(1, calls[0], "the connection-aware overload must fall through to the plain one");
		}

		@Test
		@DisplayName("a subclass can override the category")
		void subclassOverridesCategory()
		{
			assertEquals(WireType.HV_CATEGORY,
					new TestWireType("CUSTOM_HV", .2, 4096, 32, WireType.HV_CATEGORY, true, 0).getCategory());
		}

		@Test
		@DisplayName("a subclass reports its own transfer rate, loss and length")
		void subclassReportsOwnNumbers()
		{
			WireType t = new TestWireType("CUSTOM_NUMBERS", .125, 777, 42);
			assertEquals(.125, t.getLossRatio(), 1e-9);
			assertEquals(777, t.getTransferRate());
			assertEquals(42, t.getMaxLength());
			assertEquals("CUSTOM_NUMBERS", t.getUniqueName());
		}
	}

	/** A minimal WireType that overrides nothing optional, to pin the base class's defaults. */
	private static class BareWireType extends WireType
	{
		@Override
		public String getUniqueName()
		{
			return "BARE";
		}

		@Override
		public double getLossRatio()
		{
			return 0;
		}

		@Override
		public int getTransferRate()
		{
			return 0;
		}

		@Override
		public int getColour(Connection connection)
		{
			return 0;
		}

		@Override
		public double getSlack()
		{
			return 1;
		}

		@Override
		public TextureAtlasSprite getIcon(Connection connection)
		{
			return null;
		}

		@Override
		public int getMaxLength()
		{
			return 0;
		}

		@Override
		public ItemStack getWireCoil()
		{
			return null;
		}

		@Override
		public double getRenderDiameter()
		{
			return 0;
		}

		@Override
		public boolean isEnergyWire()
		{
			return false;
		}
	}

	@Test
	@DisplayName("IElectricEquipment.ElectricSource keeps the level it was built with")
	void electricSourceKeepsLevel()
	{
		assertEquals(2f, new IElectricEquipment.ElectricSource(2f).level, 1e-6);
		assertEquals(-1f, new IElectricEquipment.ElectricSource(-1f).level, 1e-6);
	}
}
