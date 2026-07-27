/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;
import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsIE;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link Lib}, which holds the mod's identity strings, the translation-key
 * prefixes, the GUI ids used by the FML network wrapper and the metal name tables.
 * <p>
 * A drift in any of these is invisible at compile time and only shows up as an untranslated
 * tooltip, a mismatched metal name or -- worst -- a GUI id collision that opens the wrong screen.
 */
class LibTest
{
	@Test
	@DisplayName("the mod id is the lowercase, resource-safe identifier")
	void modIdIsResourceSafe()
	{
		assertEquals("immersiveengineering", Lib.MODID);
		assertEquals(Lib.MODID.toLowerCase(Locale.ENGLISH), Lib.MODID);
		assertTrue(Lib.MODID.matches("[a-z0-9_]+"), "the mod id must be safe for ResourceLocations");
	}

	@Test
	@DisplayName("METALS_IE lists the nine IE metals with no duplicates")
	void metalsIeIsTheNineIeMetals()
	{
		assertEquals(9, Lib.METALS_IE.length);
		assertEquals(new LinkedHashSet<>(Arrays.asList(Lib.METALS_IE)).size(), Lib.METALS_IE.length,
				"METALS_IE contains a duplicate");
		assertEquals("Copper", Lib.METALS_IE[0]);
		assertEquals("Steel", Lib.METALS_IE[8]);
	}

	@Test
	@DisplayName("METALS_ALL is METALS_IE plus vanilla iron and gold, in that order")
	void metalsAllExtendsMetalsIe()
	{
		assertEquals(Lib.METALS_IE.length+2, Lib.METALS_ALL.length);
		for(int i = 0; i < Lib.METALS_IE.length; i++)
			assertEquals(Lib.METALS_IE[i], Lib.METALS_ALL[i],
					"METALS_ALL must start with METALS_IE verbatim, index "+i+" differs");
		assertEquals("Iron", Lib.METALS_ALL[9]);
		assertEquals("Gold", Lib.METALS_ALL[10]);
		assertEquals(new HashSet<>(Arrays.asList(Lib.METALS_ALL)).size(), Lib.METALS_ALL.length,
				"METALS_ALL contains a duplicate");
	}

	@Test
	@DisplayName("METALS_IE lines up index-for-index with BlockTypes_MetalsIE")
	void metalsIeMatchesTheBlockEnum()
	{
		// the storage/sheetmetal blocks index this array by block metadata, so any drift between
		// the two silently renames a block variant
		assertEquals(BlockTypes_MetalsIE.values().length, Lib.METALS_IE.length);
		for(BlockTypes_MetalsIE type : BlockTypes_MetalsIE.values())
			assertEquals(type.getName(), Lib.METALS_IE[type.getMeta()].toLowerCase(Locale.ENGLISH),
					"METALS_IE and BlockTypes_MetalsIE disagree at meta "+type.getMeta());
	}

	@Test
	@DisplayName("METALS_ALL lines up index-for-index with BlockTypes_MetalsAll")
	void metalsAllMatchesTheBlockEnum()
	{
		assertEquals(BlockTypes_MetalsAll.values().length, Lib.METALS_ALL.length);
		for(BlockTypes_MetalsAll type : BlockTypes_MetalsAll.values())
			assertEquals(type.getName(), Lib.METALS_ALL[type.getMeta()].toLowerCase(Locale.ENGLISH),
					"METALS_ALL and BlockTypes_MetalsAll disagree at meta "+type.getMeta());
	}

	@Test
	@DisplayName("the tool class strings are distinct and non-empty")
	void toolClassesAreDistinct()
	{
		assertEquals("IE_HAMMER", Lib.TOOL_HAMMER);
		assertEquals("IE_WIRECUTTER", Lib.TOOL_WIRECUTTER);
		assertNotEquals(Lib.TOOL_HAMMER, Lib.TOOL_WIRECUTTER);
	}

	@Test
	@DisplayName("the chat translation prefixes nest under chat.<modid>. and end with a dot")
	void chatPrefixesAreWellFormed()
	{
		assertEquals("chat."+Lib.MODID+".", Lib.CHAT);
		assertTrue(Lib.CHAT.endsWith("."), "a key prefix must end with a dot");
		for(String sub : new String[]{Lib.CHAT_WARN, Lib.CHAT_INFO, Lib.CHAT_COMMAND})
		{
			assertTrue(sub.startsWith(Lib.CHAT), sub+" is not nested under "+Lib.CHAT);
			assertTrue(sub.endsWith("."), sub+" does not end with a dot");
		}
		assertEquals(Lib.CHAT+"warning.", Lib.CHAT_WARN);
		assertEquals(Lib.CHAT+"info.", Lib.CHAT_INFO);
		assertEquals(Lib.CHAT+"command.", Lib.CHAT_COMMAND);
	}

	@Test
	@DisplayName("the description translation prefixes nest under desc.<modid>. and end with a dot")
	void descPrefixesAreWellFormed()
	{
		assertEquals("desc."+Lib.MODID+".", Lib.DESC);
		assertEquals(Lib.DESC+"info.", Lib.DESC_INFO);
		assertEquals(Lib.DESC+"flavour.", Lib.DESC_FLAVOUR);
		for(String sub : new String[]{Lib.DESC, Lib.DESC_INFO, Lib.DESC_FLAVOUR})
			assertTrue(sub.endsWith("."), sub+" does not end with a dot");
	}

	@Test
	@DisplayName("the gui translation prefixes nest under gui.<modid>. and end with a dot")
	void guiPrefixesAreWellFormed()
	{
		assertEquals("gui."+Lib.MODID+".", Lib.GUI);
		assertEquals(Lib.GUI+"config.", Lib.GUI_CONFIG);
		assertTrue(Lib.GUI_CONFIG.startsWith(Lib.GUI));
		assertTrue(Lib.GUI.endsWith("."));
		assertTrue(Lib.GUI_CONFIG.endsWith("."));
	}

	@Test
	@DisplayName("the four prefix families do not shadow one another")
	void prefixFamiliesAreDisjoint()
	{
		String[] roots = {Lib.CHAT, Lib.DESC, Lib.GUI};
		for(int i = 0; i < roots.length; i++)
			for(int j = 0; j < roots.length; j++)
				if(i!=j)
					assertFalse(roots[i].startsWith(roots[j]),
							roots[i]+" is nested inside "+roots[j]);
	}

	@Test
	@DisplayName("the tile GUI ids are contiguous from GUIID_Base_Tile")
	void tileGuiIdsAreContiguous()
	{
		int[] tileIds = {
				Lib.GUIID_CokeOven, Lib.GUIID_AlloySmelter, Lib.GUIID_BlastFurnace, Lib.GUIID_WoodenCrate,
				Lib.GUIID_Workbench, Lib.GUIID_Assembler, Lib.GUIID_Sorter, Lib.GUIID_Squeezer,
				Lib.GUIID_Fermenter, Lib.GUIID_Refinery, Lib.GUIID_ArcFurnace, Lib.GUIID_AutoWorkbench,
				Lib.GUIID_Mixer, Lib.GUIID_Turret, Lib.GUIID_FluidSorter, Lib.GUIID_Belljar,
				Lib.GUIID_ToolboxBlock
		};
		assertEquals(0, Lib.GUIID_Base_Tile);
		for(int i = 0; i < tileIds.length; i++)
			assertEquals(Lib.GUIID_Base_Tile+i, tileIds[i], "tile GUI id "+i+" is out of order");
	}

	@Test
	@DisplayName("the item GUI ids are contiguous from GUIID_Base_Item")
	void itemGuiIdsAreContiguous()
	{
		int[] itemIds = {Lib.GUIID_Manual, Lib.GUIID_Revolver, Lib.GUIID_Toolbox, Lib.GUIID_MaintenanceKit};
		assertEquals(64, Lib.GUIID_Base_Item);
		for(int i = 0; i < itemIds.length; i++)
			assertEquals(Lib.GUIID_Base_Item+i, itemIds[i], "item GUI id "+i+" is out of order");
	}

	@Test
	@DisplayName("no two GUI ids collide")
	void guiIdsDoNotCollide()
	{
		int[] all = {
				Lib.GUIID_CokeOven, Lib.GUIID_AlloySmelter, Lib.GUIID_BlastFurnace, Lib.GUIID_WoodenCrate,
				Lib.GUIID_Workbench, Lib.GUIID_Assembler, Lib.GUIID_Sorter, Lib.GUIID_Squeezer,
				Lib.GUIID_Fermenter, Lib.GUIID_Refinery, Lib.GUIID_ArcFurnace, Lib.GUIID_AutoWorkbench,
				Lib.GUIID_Mixer, Lib.GUIID_Turret, Lib.GUIID_FluidSorter, Lib.GUIID_Belljar,
				Lib.GUIID_ToolboxBlock,
				Lib.GUIID_Manual, Lib.GUIID_Revolver, Lib.GUIID_Toolbox, Lib.GUIID_MaintenanceKit
		};
		Set<Integer> seen = new HashSet<>();
		for(int id : all)
			assertTrue(seen.add(id), "duplicate GUI id "+id);
		assertEquals(21, seen.size());
	}

	@Test
	@DisplayName("the tile GUI id block has not grown into the item GUI id block")
	void tileAndItemGuiRangesDoNotOverlap()
	{
		// there are 17 tile guis today; the item block starts at 64, so there is plenty of head-room,
		// but a new tile gui must never be numbered past the base of the item block
		assertTrue(Lib.GUIID_ToolboxBlock < Lib.GUIID_Base_Item,
				"the tile GUI id range has grown into the item GUI id range");
		assertTrue(Lib.GUIID_Base_Tile < Lib.GUIID_Base_Item);
	}

	@Test
	@DisplayName("the immersive orange int and float colours describe the same colour")
	void immersiveOrangeIsConsistent()
	{
		assertEquals(0xfff78034, Lib.COLOUR_I_ImmersiveOrange);
		assertEquals(0xff, (Lib.COLOUR_I_ImmersiveOrange >> 24)&0xff, "the orange must be fully opaque");

		assertEquals(3, Lib.COLOUR_F_ImmersiveOrange.length);
		assertEquals(((Lib.COLOUR_I_ImmersiveOrange >> 16)&0xff)/255f, Lib.COLOUR_F_ImmersiveOrange[0], 1e-6f);
		assertEquals(((Lib.COLOUR_I_ImmersiveOrange >> 8)&0xff)/255f, Lib.COLOUR_F_ImmersiveOrange[1], 1e-6f);
		assertEquals((Lib.COLOUR_I_ImmersiveOrange&0xff)/255f, Lib.COLOUR_F_ImmersiveOrange[2], 1e-6f);
	}

	@Test
	@DisplayName("the orange shadow is a darker, opaque variant of the orange")
	void immersiveOrangeShadowIsDarker()
	{
		assertEquals(0xff3e200d, Lib.COLOUR_I_ImmersiveOrangeShadow);
		assertEquals(0xff, (Lib.COLOUR_I_ImmersiveOrangeShadow >> 24)&0xff);
		for(int shift : new int[]{16, 8, 0})
			assertTrue(((Lib.COLOUR_I_ImmersiveOrangeShadow >> shift)&0xff) < ((Lib.COLOUR_I_ImmersiveOrange >> shift)&0xff),
					"the shadow must be darker than the base colour in every channel");
	}

	@Test
	@DisplayName("the nixie tube colour is an opaque-less RGB triplet")
	void nixieTubeColour()
	{
		assertEquals(0xff9900, Lib.colour_nixieTubeText);
		assertEquals(0, Lib.colour_nixieTubeText >>> 24, "this constant carries no alpha channel");
	}

	@Test
	@DisplayName("the NBT keys are distinct and namespaced")
	void nbtKeysAreDistinctAndNamespaced()
	{
		String[] keys = {Lib.NBT_Earmuffs, Lib.NBT_EarmuffColour, Lib.NBT_Powerpack};
		Set<String> seen = new HashSet<>();
		for(String key : keys)
		{
			assertTrue(key.startsWith("IE:"), key+" is not namespaced to IE");
			assertTrue(seen.add(key), "duplicate NBT key "+key);
		}
		assertEquals("Damage", Lib.NBT_DAMAGE);
		assertEquals("PreventRemoteMovement", Lib.MAGNET_PREVENT_NBT);
	}

	@Test
	@DisplayName("every damage source name is distinct")
	void damageSourceNamesAreDistinct()
	{
		String[] sources = {
				Lib.DMG_RevolverCasull, Lib.DMG_RevolverAP, Lib.DMG_RevolverBuck, Lib.DMG_RevolverDragon,
				Lib.DMG_RevolverHoming, Lib.DMG_RevolverWolfpack, Lib.DMG_RevolverSilver, Lib.DMG_RevolverPotion,
				Lib.DMG_Crusher, Lib.DMG_Tesla, Lib.DMG_Acid, Lib.DMG_Railgun, Lib.DMG_Tesla_prim,
				Lib.DMG_RazorWire, Lib.DMG_RazorShock, Lib.DMG_WireShock
		};
		Set<String> seen = new HashSet<>();
		for(String source : sources)
		{
			assertNotNull(source);
			assertFalse(source.isEmpty());
			assertTrue(seen.add(source), "duplicate damage source name "+source);
		}
		assertEquals(16, seen.size());
	}

	@Test
	@DisplayName("the revolver damage sources share the ieRevolver_ prefix")
	void revolverDamageSourcesSharePrefix()
	{
		String[] revolver = {
				Lib.DMG_RevolverCasull, Lib.DMG_RevolverAP, Lib.DMG_RevolverBuck, Lib.DMG_RevolverDragon,
				Lib.DMG_RevolverHoming, Lib.DMG_RevolverWolfpack, Lib.DMG_RevolverSilver, Lib.DMG_RevolverPotion
		};
		for(String source : revolver)
			assertTrue(source.startsWith("ieRevolver_"), source+" is missing the ieRevolver_ prefix");
	}

	@Test
	@DisplayName("the steel tool material keeps its stats")
	void steelToolMaterial()
	{
		assertNotNull(Lib.MATERIAL_Steel);
		assertEquals(2, Lib.MATERIAL_Steel.getHarvestLevel());
		assertEquals(641, Lib.MATERIAL_Steel.getMaxUses());
		assertEquals(7.0f, Lib.MATERIAL_Steel.getEfficiency(), 1e-6f);
		assertEquals(2.5f, Lib.MATERIAL_Steel.getAttackDamage(), 1e-6f);
		assertEquals(10, Lib.MATERIAL_Steel.getEnchantability());
	}

	@Test
	@DisplayName("the masterwork rarity is registered on top of the vanilla rarities")
	void masterworkRarity()
	{
		assertNotNull(Lib.RARITY_Masterwork);
		assertEquals("Masterwork", Lib.RARITY_Masterwork.rarityName);
		assertTrue(Lib.RARITY_Masterwork.ordinal() >= 4,
				"the masterwork rarity must sit after the four vanilla ones");
	}

	@Test
	@DisplayName("the compatibility flags default to off")
	void compatFlagsDefaultToOff()
	{
		// these are only flipped by the mod-loading hooks, so a unit run must see them all false
		assertFalse(Lib.BAUBLES);
		assertFalse(Lib.IC2);
		assertFalse(Lib.GREG);
	}
}
