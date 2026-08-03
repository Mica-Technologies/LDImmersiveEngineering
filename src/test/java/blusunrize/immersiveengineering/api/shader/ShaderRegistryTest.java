/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.shader;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.shader.ShaderCase.ShaderLayer;
import blusunrize.immersiveengineering.api.shader.ShaderRegistry.ShaderRegistryEntry;
import com.google.common.collect.ArrayListMultimap;
import net.minecraft.item.EnumRarity;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the pure bookkeeping half of {@link ShaderRegistry}: the registry maps, the
 * rarity/weight arithmetic that drives shader bags and crate loot, and the layer assembly done by
 * the {@code registerShader_*} helpers.
 * <p>
 * Everything here writes to static state, so the whole registry is snapshotted before each test and
 * put back afterwards.
 */
class ShaderRegistryTest
{
	private LinkedHashMap<String, ShaderRegistryEntry> savedRegistry;
	private ArrayList<String> savedChestLoot;
	private ArrayList<EnumRarity> savedSortedRarities;
	private HashMap<EnumRarity, Integer> savedTotalWeight;
	private HashMap<String, HashMap<EnumRarity, Integer>> savedPlayerWeight;
	private ArrayListMultimap<String, String> savedReceived;
	private Set<ShaderRegistry.IShaderRegistryMethod> savedMethods;
	private HashMap<ResourceLocation, double[]> savedLayerBounds;

	@BeforeEach
	void snapshotRegistry()
	{
		savedRegistry = new LinkedHashMap<>(ShaderRegistry.shaderRegistry);
		savedChestLoot = new ArrayList<>(ShaderRegistry.chestLootShaders);
		savedSortedRarities = new ArrayList<>(ShaderRegistry.sortedRarityMap);
		savedTotalWeight = new HashMap<>(ShaderRegistry.totalWeight);
		savedPlayerWeight = new HashMap<>(ShaderRegistry.playerTotalWeight);
		savedReceived = ArrayListMultimap.create(ShaderRegistry.receivedShaders);
		savedMethods = new HashSet<>(ShaderRegistry.shaderRegistrationMethods);
		savedLayerBounds = new HashMap<>(ShaderRegistry.defaultLayerBounds);

		ShaderRegistry.shaderRegistry.clear();
		ShaderRegistry.chestLootShaders.clear();
		ShaderRegistry.totalWeight.clear();
		ShaderRegistry.playerTotalWeight.clear();
		ShaderRegistry.receivedShaders.clear();
	}

	@AfterEach
	void restoreRegistry()
	{
		ShaderRegistry.shaderRegistry.clear();
		ShaderRegistry.shaderRegistry.putAll(savedRegistry);
		ShaderRegistry.chestLootShaders.clear();
		ShaderRegistry.chestLootShaders.addAll(savedChestLoot);
		ShaderRegistry.sortedRarityMap.clear();
		ShaderRegistry.sortedRarityMap.addAll(savedSortedRarities);
		ShaderRegistry.totalWeight.clear();
		ShaderRegistry.totalWeight.putAll(savedTotalWeight);
		ShaderRegistry.playerTotalWeight.clear();
		ShaderRegistry.playerTotalWeight.putAll(savedPlayerWeight);
		ShaderRegistry.receivedShaders.clear();
		ShaderRegistry.receivedShaders.putAll(savedReceived);
		ShaderRegistry.shaderRegistrationMethods.clear();
		ShaderRegistry.shaderRegistrationMethods.addAll(savedMethods);
		ShaderRegistry.defaultLayerBounds.clear();
		ShaderRegistry.defaultLayerBounds.putAll(savedLayerBounds);
	}

	private static ShaderCaseItem dummyCase()
	{
		return new ShaderCaseItem(new ShaderLayer(new ResourceLocation("immersiveengineering:items/shader_0"), 0xffffffff));
	}

	// ---------------------------------------------------------------- rarity weights

	@Test
	@DisplayName("the rarity weight table covers exactly the five shader rarities")
	void rarityWeightTableIsComplete()
	{
		assertEquals(5, ShaderRegistry.rarityWeightMap.size());
		assertAll(
				() -> assertEquals(9, (int)ShaderRegistry.rarityWeightMap.get(EnumRarity.COMMON)),
				() -> assertEquals(7, (int)ShaderRegistry.rarityWeightMap.get(EnumRarity.UNCOMMON)),
				() -> assertEquals(5, (int)ShaderRegistry.rarityWeightMap.get(EnumRarity.RARE)),
				() -> assertEquals(3, (int)ShaderRegistry.rarityWeightMap.get(EnumRarity.EPIC)),
				() -> assertEquals(1, (int)ShaderRegistry.rarityWeightMap.get(Lib.RARITY_Masterwork))
		);
	}

	@Test
	@DisplayName("rarer shaders carry a strictly smaller weight")
	void weightsFallAsRarityRises()
	{
		EnumRarity[] fromCommon = {EnumRarity.COMMON, EnumRarity.UNCOMMON, EnumRarity.RARE, EnumRarity.EPIC,
				Lib.RARITY_Masterwork};
		for(int i = 1; i < fromCommon.length; i++)
			assertTrue(ShaderRegistry.rarityWeightMap.get(fromCommon[i]) < ShaderRegistry.rarityWeightMap.get(fromCommon[i-1]),
					fromCommon[i]+" is not rarer than "+fromCommon[i-1]);
	}

	@Test
	@DisplayName("every rarity weight leaves room for the 10-weight replication multiplier")
	void replicationMultiplierStaysPositive()
	{
		// registerShader() prices replication at defaultReplicationCost * (10 - rarityWeight)
		for(EnumRarity rarity : ShaderRegistry.rarityWeightMap.keySet())
		{
			int multiplier = 10-ShaderRegistry.rarityWeightMap.get(rarity);
			assertTrue(multiplier >= 1, "replication multiplier for "+rarity+" would be "+multiplier);
			assertTrue(multiplier <= 9, "replication multiplier for "+rarity+" would be "+multiplier);
		}
		assertEquals(1, 10-ShaderRegistry.rarityWeightMap.get(EnumRarity.COMMON));
		assertEquals(9, 10-ShaderRegistry.rarityWeightMap.get(Lib.RARITY_Masterwork));
	}

	@Test
	@DisplayName("the default replication cost is a single silver dust")
	void defaultReplicationCost()
	{
		assertNotNull(ShaderRegistry.defaultReplicationCost);
		assertEquals("dustSilver", ShaderRegistry.defaultReplicationCost.oreName);
		assertEquals(1, ShaderRegistry.defaultReplicationCost.inputSize);
		assertEquals(9, ShaderRegistry.defaultReplicationCost.copyWithMultipliedSize(9).inputSize,
				"a masterwork shader must cost nine dusts to replicate");
	}

	// ---------------------------------------------------------------- registry map

	@Test
	@DisplayName("registerShaderCase creates an entry keyed by name")
	void registerShaderCaseCreatesAnEntry()
	{
		ShaderCaseItem shader = ShaderRegistry.registerShaderCase("test:alpha", dummyCase(), EnumRarity.RARE);

		assertTrue(ShaderRegistry.shaderRegistry.containsKey("test:alpha"));
		ShaderRegistryEntry entry = ShaderRegistry.shaderRegistry.get("test:alpha");
		assertEquals("test:alpha", entry.getName());
		assertEquals(EnumRarity.RARE, entry.getRarity());
		assertSame(shader, entry.getCase("immersiveengineering:item"));
	}

	@Test
	@DisplayName("registerShaderCase returns the shader it was handed")
	void registerShaderCaseIsPassThrough()
	{
		ShaderCaseItem shader = dummyCase();
		assertSame(shader, ShaderRegistry.registerShaderCase("test:passthrough", shader, EnumRarity.COMMON));
	}

	@Test
	@DisplayName("a second case for the same name is added, not replaced")
	void secondCaseIsAddedToTheSameEntry()
	{
		ShaderRegistry.registerShaderCase("test:multi", dummyCase(), EnumRarity.COMMON);
		ShaderCaseRevolver revolver = new ShaderCaseRevolver(
				new ShaderLayer(new ResourceLocation("immersiveengineering:revolvers/shaders/revolver_grip"), 0));
		ShaderRegistry.registerShaderCase("test:multi", revolver, EnumRarity.EPIC);

		assertEquals(1, ShaderRegistry.shaderRegistry.size(), "a second case must not create a second entry");
		ShaderRegistryEntry entry = ShaderRegistry.shaderRegistry.get("test:multi");
		assertEquals(2, entry.getCases().size());
		assertSame(revolver, entry.getCase("immersiveengineering:revolver"));
		assertEquals(EnumRarity.COMMON, entry.getRarity(),
				"the rarity of the first registration wins; a later case must not silently re-rank the shader");
	}

	@Test
	@DisplayName("getShader resolves by name and type, and returns null for anything unknown")
	void getShaderResolvesNameAndType()
	{
		ShaderCaseItem shader = ShaderRegistry.registerShaderCase("test:lookup", dummyCase(), EnumRarity.COMMON);

		assertSame(shader, ShaderRegistry.getShader("test:lookup", "immersiveengineering:item"));
		assertNull(ShaderRegistry.getShader("test:lookup", "immersiveengineering:revolver"),
				"an unregistered type must not fall back to another case");
		assertNull(ShaderRegistry.getShader("test:does-not-exist", "immersiveengineering:item"));
	}

	@Test
	@DisplayName("the registry preserves insertion order")
	void registryPreservesInsertionOrder()
	{
		ShaderRegistry.registerShaderCase("test:one", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.registerShaderCase("test:two", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.registerShaderCase("test:three", dummyCase(), EnumRarity.COMMON);

		assertEquals(Arrays.asList("test:one", "test:two", "test:three"),
				new ArrayList<>(ShaderRegistry.shaderRegistry.keySet()));
	}

	// ---------------------------------------------------------------- registry entry

	@Test
	@DisplayName("a fresh entry takes its weight from its rarity and defaults to lower bags")
	void entryDefaults()
	{
		ShaderRegistryEntry entry = new ShaderRegistryEntry("test:defaults", EnumRarity.EPIC, dummyCase());

		assertEquals(3, entry.getWeight(), "the weight must come from the rarity table");
		assertTrue(entry.getIsInLowerBags(), "shaders drop from lower bags unless opted out");
		assertFalse(entry.getIsCrateLoot());
		assertFalse(entry.getIsBagLoot());
		assertNotNull(entry.getEffectFunction(), "a shader without an effect must still return the no-op");
	}

	@Test
	@DisplayName("the entry setters are fluent and stick")
	void entrySettersAreFluent()
	{
		ShaderRegistryEntry entry = new ShaderRegistryEntry("test:fluent", EnumRarity.COMMON, dummyCase());

		assertSame(entry, entry.setWeight(42));
		assertSame(entry, entry.setCrateLoot(true));
		assertSame(entry, entry.setBagLoot(true));
		assertSame(entry, entry.setInLowerBags(false));
		assertSame(entry, entry.setInfo("set", "reference", "details"));

		assertEquals(42, entry.getWeight());
		assertTrue(entry.getIsCrateLoot());
		assertTrue(entry.getIsBagLoot());
		assertFalse(entry.getIsInLowerBags());
		assertEquals("set", entry.info_set);
		assertEquals("reference", entry.info_reference);
		assertEquals("details", entry.info_details);
	}

	@Test
	@DisplayName("addCase keys the case by its own shader type")
	void addCaseKeysByShaderType()
	{
		ShaderRegistryEntry entry = new ShaderRegistryEntry("test:addcase", EnumRarity.COMMON,
				Collections.<ShaderCase>emptyList());
		assertTrue(entry.getCases().isEmpty());

		ShaderCaseItem item = dummyCase();
		assertSame(entry, entry.addCase(item));
		assertSame(item, entry.getCase(item.getShaderType()));
		assertEquals(1, entry.getCases().size());
	}

	@Test
	@DisplayName("a custom effect function replaces the no-op default")
	void customEffectFunctionWins()
	{
		ShaderRegistryEntry entry = new ShaderRegistryEntry("test:effect", EnumRarity.COMMON, dummyCase());
		IShaderEffectFunction custom = (world, shader, item, shaderType, pos, dir, scale) -> {
		};

		assertNotSame(custom, entry.getEffectFunction());
		entry.setEffectFunction(custom);
		assertSame(custom, entry.getEffectFunction());
	}

	// ---------------------------------------------------------------- compileWeight

	@Test
	@DisplayName("compileWeight fills the crate loot list once per weight point")
	void compileWeightFillsCrateLoot()
	{
		ShaderRegistry.registerShaderCase("test:crate", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:crate").setCrateLoot(true).setWeight(3);
		ShaderRegistry.registerShaderCase("test:nocrate", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:nocrate").setCrateLoot(false).setWeight(5);

		ShaderRegistry.compileWeight();

		assertEquals(3, Collections.frequency(ShaderRegistry.chestLootShaders, "test:crate"));
		assertEquals(0, Collections.frequency(ShaderRegistry.chestLootShaders, "test:nocrate"));
		assertEquals(3, ShaderRegistry.chestLootShaders.size());
	}

	@Test
	@DisplayName("compileWeight starts from scratch every time")
	void compileWeightIsIdempotent()
	{
		ShaderRegistry.registerShaderCase("test:crate", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:crate").setCrateLoot(true).setWeight(2);

		ShaderRegistry.compileWeight();
		int first = ShaderRegistry.chestLootShaders.size();
		ShaderRegistry.compileWeight();

		assertEquals(first, ShaderRegistry.chestLootShaders.size(),
				"compileWeight must clear its output before rebuilding it");
	}

	@Test
	@DisplayName("a lower-bag shader adds its weight to every bag at or below its own rarity")
	void lowerBagShaderCountsTowardsEveryCommonerBag()
	{
		ShaderRegistry.registerShaderCase("test:epic", dummyCase(), EnumRarity.EPIC);
		ShaderRegistry.shaderRegistry.get("test:epic").setBagLoot(true).setInLowerBags(true).setWeight(4);

		ShaderRegistry.compileWeight();

		// weight >= 3 covers EPIC, RARE, UNCOMMON and COMMON, but not the masterwork bag
		assertEquals(4, (int)ShaderRegistry.totalWeight.get(EnumRarity.EPIC));
		assertEquals(4, (int)ShaderRegistry.totalWeight.get(EnumRarity.RARE));
		assertEquals(4, (int)ShaderRegistry.totalWeight.get(EnumRarity.UNCOMMON));
		assertEquals(4, (int)ShaderRegistry.totalWeight.get(EnumRarity.COMMON));
		assertNull(ShaderRegistry.totalWeight.get(Lib.RARITY_Masterwork),
				"an epic shader must not be reachable from a masterwork bag");
	}

	@Test
	@DisplayName("a shader opted out of lower bags only counts towards its own rarity")
	void optedOutShaderOnlyCountsForItsOwnRarity()
	{
		ShaderRegistry.registerShaderCase("test:exclusive", dummyCase(), EnumRarity.EPIC);
		ShaderRegistry.shaderRegistry.get("test:exclusive").setBagLoot(true).setInLowerBags(false).setWeight(4);

		ShaderRegistry.compileWeight();

		assertEquals(4, (int)ShaderRegistry.totalWeight.get(EnumRarity.EPIC));
		assertNull(ShaderRegistry.totalWeight.get(EnumRarity.COMMON));
		assertNull(ShaderRegistry.totalWeight.get(EnumRarity.RARE));
	}

	@Test
	@DisplayName("a shader that is neither crate nor bag loot contributes nothing")
	void nonLootShaderContributesNothing()
	{
		ShaderRegistry.registerShaderCase("test:hidden", dummyCase(), EnumRarity.COMMON);

		ShaderRegistry.compileWeight();

		assertTrue(ShaderRegistry.chestLootShaders.isEmpty());
		assertTrue(ShaderRegistry.totalWeight.isEmpty());
	}

	@Test
	@DisplayName("compileWeight sorts the rarities from rarest to commonest")
	void compileWeightSortsRarities()
	{
		ShaderRegistry.compileWeight();

		assertEquals(ShaderRegistry.rarityWeightMap.size(), ShaderRegistry.sortedRarityMap.size());
		for(int i = 1; i < ShaderRegistry.sortedRarityMap.size(); i++)
			assertTrue(ShaderRegistry.rarityWeightMap.get(ShaderRegistry.sortedRarityMap.get(i-1))
							<= ShaderRegistry.rarityWeightMap.get(ShaderRegistry.sortedRarityMap.get(i)),
					"sortedRarityMap is not ordered by ascending weight");
		assertEquals(Lib.RARITY_Masterwork, ShaderRegistry.sortedRarityMap.get(0));
		assertEquals(EnumRarity.COMMON, ShaderRegistry.sortedRarityMap.get(ShaderRegistry.sortedRarityMap.size()-1));
	}

	// ---------------------------------------------------------------- rarity walking

	@Test
	@DisplayName("getLowerRarity steps down exactly one tier and stops at COMMON")
	void getLowerRarityStepsDownOneTier()
	{
		ShaderRegistry.compileWeight();

		assertEquals(EnumRarity.EPIC, ShaderRegistry.getLowerRarity(Lib.RARITY_Masterwork));
		assertEquals(EnumRarity.RARE, ShaderRegistry.getLowerRarity(EnumRarity.EPIC));
		assertEquals(EnumRarity.UNCOMMON, ShaderRegistry.getLowerRarity(EnumRarity.RARE));
		assertEquals(EnumRarity.COMMON, ShaderRegistry.getLowerRarity(EnumRarity.UNCOMMON));
		assertNull(ShaderRegistry.getLowerRarity(EnumRarity.COMMON), "there is nothing below common");
	}

	@Test
	@DisplayName("getAllLowerRarities lists every commoner tier in order")
	void getAllLowerRaritiesListsEveryTierBelow()
	{
		ShaderRegistry.compileWeight();

		assertEquals(Arrays.asList(EnumRarity.RARE, EnumRarity.UNCOMMON, EnumRarity.COMMON),
				ShaderRegistry.getAllLowerRarities(EnumRarity.EPIC));
		assertTrue(ShaderRegistry.getAllLowerRarities(EnumRarity.COMMON).isEmpty());
		assertEquals(4, ShaderRegistry.getAllLowerRarities(Lib.RARITY_Masterwork).size());
	}

	@Test
	@DisplayName("getHigherRarities returns only the next tier up -- it feeds the bag upgrade recipe")
	void getHigherRaritiesReturnsOnlyTheNextTier()
	{
		ShaderRegistry.compileWeight();

		assertEquals(Collections.singletonList(EnumRarity.EPIC), ShaderRegistry.getHigherRarities(EnumRarity.RARE));
		assertEquals(Collections.singletonList(EnumRarity.UNCOMMON), ShaderRegistry.getHigherRarities(EnumRarity.COMMON));
		assertTrue(ShaderRegistry.getHigherRarities(Lib.RARITY_Masterwork).isEmpty(),
				"nothing sits above the masterwork rarity");
	}

	@Test
	@DisplayName("getAllHigherRarities lists every rarer tier")
	void getAllHigherRaritiesListsEveryTierAbove()
	{
		ShaderRegistry.compileWeight();

		assertEquals(Arrays.asList(EnumRarity.EPIC, Lib.RARITY_Masterwork),
				ShaderRegistry.getAllHigherRarities(EnumRarity.RARE));
		assertEquals(4, ShaderRegistry.getAllHigherRarities(EnumRarity.COMMON).size());
		assertTrue(ShaderRegistry.getAllHigherRarities(Lib.RARITY_Masterwork).isEmpty());
	}

	@Test
	@DisplayName("the lower and higher walks are inverses of each other")
	void rarityWalksAreInverses()
	{
		ShaderRegistry.compileWeight();

		for(EnumRarity rarity : ShaderRegistry.sortedRarityMap)
		{
			EnumRarity lower = ShaderRegistry.getLowerRarity(rarity);
			if(lower!=null)
				assertEquals(Collections.singletonList(rarity), ShaderRegistry.getHigherRarities(lower),
						"stepping down to "+lower+" and back up did not return "+rarity);
		}
	}

	// ---------------------------------------------------------------- player weights

	@Test
	@DisplayName("recalculatePlayerTotalWeight mirrors the global weights for a fresh player")
	void freshPlayerMatchesTheGlobalWeights()
	{
		ShaderRegistry.registerShaderCase("test:bag", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:bag").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		ShaderRegistry.recalculatePlayerTotalWeight("tester");

		assertEquals(ShaderRegistry.totalWeight.get(EnumRarity.COMMON),
				ShaderRegistry.playerTotalWeight.get("tester").get(EnumRarity.COMMON));
	}

	@Test
	@DisplayName("an already-received shader drops to weight one for that player")
	void receivedShaderDropsToWeightOne()
	{
		ShaderRegistry.registerShaderCase("test:bag", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:bag").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		ShaderRegistry.receivedShaders.put("tester", "test:bag");
		ShaderRegistry.recalculatePlayerTotalWeight("tester");

		assertEquals(1, (int)ShaderRegistry.playerTotalWeight.get("tester").get(EnumRarity.COMMON),
				"a duplicate must become almost unreachable, not stay at full weight");
	}

	@Test
	@DisplayName("recalculating twice does not double-count")
	void recalculatingDoesNotDoubleCount()
	{
		ShaderRegistry.registerShaderCase("test:bag", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:bag").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		ShaderRegistry.recalculatePlayerTotalWeight("tester");
		int first = ShaderRegistry.playerTotalWeight.get("tester").get(EnumRarity.COMMON);
		ShaderRegistry.recalculatePlayerTotalWeight("tester");

		assertEquals(first, (int)ShaderRegistry.playerTotalWeight.get("tester").get(EnumRarity.COMMON));
	}

	@Test
	@DisplayName("getRandomShader only ever hands out a registered shader")
	void getRandomShaderStaysInsideTheRegistry()
	{
		ShaderRegistry.registerShaderCase("test:a", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:a").setBagLoot(true).setWeight(9);
		ShaderRegistry.registerShaderCase("test:b", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:b").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		Random rand = new Random(1234);
		Set<String> handedOut = new HashSet<>();
		for(int i = 0; i < 200; i++)
		{
			String shader = ShaderRegistry.getRandomShader("tester", rand, EnumRarity.COMMON, false);
			assertNotNull(shader, "the bag came up empty even though two shaders are registered");
			assertTrue(ShaderRegistry.shaderRegistry.containsKey(shader), "unknown shader "+shader);
			handedOut.add(shader);
		}
		assertEquals(2, handedOut.size(), "both equally weighted shaders should show up over 200 draws");
	}

	@Test
	@DisplayName("getRandomShader records the draw when asked to")
	void getRandomShaderRecordsTheDraw()
	{
		ShaderRegistry.registerShaderCase("test:only", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:only").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		String shader = ShaderRegistry.getRandomShader("tester", new Random(7), EnumRarity.COMMON, true);

		assertEquals("test:only", shader);
		assertTrue(ShaderRegistry.receivedShaders.get("tester").contains("test:only"));
		assertEquals(1, ShaderRegistry.receivedShaders.get("tester").size(),
				"the same shader must not be recorded twice");
	}

	@Test
	@DisplayName("drawing a shader for one player leaves the global weight table alone")
	void drawingAShaderDoesNotCorruptTheGlobalWeights()
	{
		// getRandomShader used to store the *same* totalWeight instance under the player's key when
		// the player was unknown, and then call recalculatePlayerTotalWeight, which clears and
		// refills that very map. The first bag anybody opened therefore destroyed the global table.
		ShaderRegistry.registerShaderCase("test:only", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:only").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();
		assertEquals(9, (int)ShaderRegistry.totalWeight.get(EnumRarity.COMMON));

		ShaderRegistry.getRandomShader("tester", new Random(7), EnumRarity.COMMON, true);

		assertEquals(9, (int)ShaderRegistry.totalWeight.get(EnumRarity.COMMON),
				"the global weight table must not track a single player's draws");
		assertNotSame(ShaderRegistry.totalWeight, ShaderRegistry.playerTotalWeight.get("tester"),
				"a player's weight table must be its own map");
	}

	@Test
	@DisplayName("two players draw against their own weight tables, not a shared one")
	void playersDoNotShareAWeightTable()
	{
		//	=================================
		//	The damaging half of the same bug.
		//	=================================
		//
		// Every unknown player was handed the *same* global map instance, so after the first draw
		// they all pointed at one table -- and each draw cleared and refilled it with that player's
		// duplicate-adjusted weights. The per-player de-weighting this whole mechanism exists for
		// was therefore shared: your odds moved when somebody else opened a bag.
		ShaderRegistry.registerShaderCase("test:only", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:only").setBagLoot(true).setWeight(9);
		ShaderRegistry.compileWeight();

		ShaderRegistry.getRandomShader("alice", new Random(1), EnumRarity.COMMON, true);
		ShaderRegistry.getRandomShader("bob", new Random(2), EnumRarity.COMMON, true);

		assertNotSame(ShaderRegistry.playerTotalWeight.get("alice"),
				ShaderRegistry.playerTotalWeight.get("bob"),
				"two players must not share one weight table");
		//Alice has received the only shader there is, so her weight for it drops to 1 while the
		//global table still reads 9. If those two numbers are equal, her draw never happened or it
		//was written somewhere shared.
		assertEquals(1, (int)ShaderRegistry.playerTotalWeight.get("alice").get(EnumRarity.COMMON),
				"a received shader must be de-weighted for the player who received it");
		assertEquals(9, (int)ShaderRegistry.totalWeight.get(EnumRarity.COMMON),
				"and that must not touch the global table");
	}

	@Test
	@DisplayName("a rarity with no bag loot draws nothing rather than throwing")
	void rarityWithNoBagLootIsSafe()
	{
		//getRandomShader unboxed the rarity's total straight out of the map. A pack that removes
		//every shader of a rarity leaves no entry for it, so that was a null unbox on a bag the
		//player had legitimately obtained.
		ShaderRegistry.registerShaderCase("test:common", dummyCase(), EnumRarity.COMMON);
		ShaderRegistry.shaderRegistry.get("test:common").setBagLoot(true).setWeight(5);
		ShaderRegistry.compileWeight();

		assertNull(ShaderRegistry.getRandomShader("tester", new Random(3), EnumRarity.EPIC, true),
				"a rarity nothing is registered for should draw nothing");
	}

	// ---------------------------------------------------------------- layer assembly

	@Test
	@DisplayName("registerShader_Item builds the three item layers in grip/base/design order")
	void itemShaderLayers()
	{
		ShaderCaseItem shader = ShaderRegistry.registerShader_Item("test:item", EnumRarity.COMMON,
				0x111111, 0x222222, 0x333333);

		ShaderLayer[] layers = shader.getLayers();
		assertEquals(3, layers.length);
		assertEquals(new ResourceLocation("immersiveengineering:items/shader_0"), layers[0].getTexture());
		assertEquals(new ResourceLocation("immersiveengineering:items/shader_1"), layers[1].getTexture());
		assertEquals(new ResourceLocation("immersiveengineering:items/shader_2"), layers[2].getTexture());
		assertEquals(0x111111, layers[0].getColour());
		assertEquals(0x222222, layers[1].getColour());
		assertEquals(0x333333, layers[2].getColour());
		assertEquals("immersiveengineering:item", shader.getShaderType());
	}

	@Test
	@DisplayName("registerShader_Revolver ends on an uncoloured pass")
	void revolverShaderEndsUncoloured()
	{
		ShaderCaseRevolver shader = ShaderRegistry.registerShader_Revolver("test:revolver", "0",
				EnumRarity.COMMON, 1, 2, 3, 4, null, 0);

		ShaderLayer[] layers = shader.getLayers();
		assertEquals(5, layers.length, "grip, base, blade, overlay and the uncoloured pass");
		assertEquals(new ResourceLocation("immersiveengineering:revolvers/shaders/revolver_uncoloured"),
				layers[layers.length-1].getTexture());
		assertEquals(0xffffffff, layers[layers.length-1].getColour(),
				"the final pass must be untinted white");
		assertEquals(new ResourceLocation("immersiveengineering:revolvers/shaders/revolver_1_0"),
				layers[3].getTexture(), "the overlay type must be appended to the overlay texture");
	}

	@Test
	@DisplayName("an additional texture is inserted before the uncoloured pass")
	void additionalTextureIsInsertedBeforeTheUncolouredPass()
	{
		ShaderCaseRevolver shader = ShaderRegistry.registerShader_Revolver("test:revolver-extra", "0",
				EnumRarity.COMMON, 1, 2, 3, 4, "sponsor", 0xdeadbeef);

		ShaderLayer[] layers = shader.getLayers();
		assertEquals(6, layers.length);
		assertEquals(new ResourceLocation("immersiveengineering:revolvers/shaders/revolver_sponsor"),
				layers[4].getTexture());
		assertEquals(0xdeadbeef, layers[4].getColour());
		assertEquals(new ResourceLocation("immersiveengineering:revolvers/shaders/revolver_uncoloured"),
				layers[5].getTexture());
	}

	@Test
	@DisplayName("a namespaced additional texture is taken verbatim rather than prefixed")
	void namespacedAdditionalTextureIsTakenVerbatim()
	{
		ShaderCaseRevolver shader = ShaderRegistry.registerShader_Revolver("test:revolver-foreign", "0",
				EnumRarity.COMMON, 1, 2, 3, 4, "othermod:custom/decal", 0);

		assertEquals(new ResourceLocation("othermod", "custom/decal"), shader.getLayers()[4].getTexture());
	}

	@Test
	@DisplayName("the minecart shader points at raw .png paths and is not stitched")
	void minecartShaderIsNotStitched()
	{
		ShaderCaseMinecart shader = ShaderRegistry.registerShader_Minecart("test:minecart", "0",
				EnumRarity.COMMON, 1, 2, null, 0);

		assertFalse(shader.stitchIntoSheet(), "minecart textures are bound directly, not stitched");
		for(ShaderLayer layer : shader.getLayers())
			assertTrue(layer.getTexture().getPath().endsWith(".png"),
					"minecart layers must reference a real file: "+layer.getTexture());
	}

	@Test
	@DisplayName("every default shader case reports a distinct, namespaced shader type")
	void shaderTypesAreDistinct()
	{
		List<ShaderCase> cases = Arrays.asList(
				ShaderRegistry.registerShader_Item("test:all", EnumRarity.COMMON, 0, 0, 0),
				ShaderRegistry.registerShader_Revolver("test:all", "0", EnumRarity.COMMON, 0, 0, 0, 0, null, 0),
				ShaderRegistry.registerShader_Chemthrower("test:all", "0", EnumRarity.COMMON, 0, 0, 0, null, 0),
				ShaderRegistry.registerShader_Drill("test:all", "0", EnumRarity.COMMON, 0, 0, 0, null, 0),
				ShaderRegistry.registerShader_Railgun("test:all", "0", EnumRarity.COMMON, 0, 0, 0, null, 0),
				ShaderRegistry.registerShader_Shield("test:all", "0", EnumRarity.COMMON, 0, 0, null, 0),
				ShaderRegistry.registerShader_Minecart("test:all", "0", EnumRarity.COMMON, 0, 0, null, 0),
				ShaderRegistry.registerShader_Balloon("test:all", "0", EnumRarity.COMMON, 0, 0, null, 0),
				ShaderRegistry.registerShader_Banner("test:all", "0", EnumRarity.COMMON, 0, 0, null, 0));

		Set<String> types = new HashSet<>();
		for(ShaderCase shaderCase : cases)
		{
			String type = shaderCase.getShaderType();
			assertTrue(type.startsWith("immersiveengineering:"), type+" is not namespaced");
			assertTrue(types.add(type), "duplicate shader type "+type);
		}
		assertEquals(9, types.size());
		assertEquals(9, ShaderRegistry.shaderRegistry.get("test:all").getCases().size(),
				"all nine cases must land on the one registry entry");
	}

	@Test
	@DisplayName("the drill shader keeps a trailing null layer for the head and augers")
	void drillShaderKeepsTheDynamicHeadLayer()
	{
		ShaderCaseDrill shader = ShaderRegistry.registerShader_Drill("test:drill", "0",
				EnumRarity.COMMON, 1, 2, 3, null, 0);

		ShaderLayer[] layers = shader.getLayers();
		assertNull(layers[layers.length-1].getTexture(),
				"the last drill layer is a placeholder for the head and must stay null");
		assertEquals(0xffffffff, layers[layers.length-1].getColour());
	}

	@Test
	@DisplayName("default layer bounds are applied to any layer built for that texture")
	void defaultLayerBoundsAreApplied()
	{
		ResourceLocation texture = new ResourceLocation("immersiveengineering:items/shader_0");
		ShaderRegistry.defaultLayerBounds.put(texture, new double[]{0, 0, 0.5, 0.5});

		ShaderLayer layer = new ShaderLayer(texture, 0xffffffff);

		assertArrayEquals(new double[]{0, 0, 0.5, 0.5}, layer.getTextureBounds(), 0d);
		assertNull(new ShaderLayer(new ResourceLocation("immersiveengineering:items/other"), 0).getTextureBounds());
	}

	// ---------------------------------------------------------------- registration methods

	@Test
	@DisplayName("registration methods are held in a set, so the same one cannot be added twice")
	void registrationMethodsAreDeduplicated()
	{
		int before = ShaderRegistry.shaderRegistrationMethods.size();
		ShaderRegistry.IShaderRegistryMethod<ShaderCaseItem> method =
				(name, overlayType, rarity, c0, c1, c2, c3, additionalTexture, colourAdditional) -> null;

		ShaderRegistry.addRegistrationMethod(method);
		ShaderRegistry.addRegistrationMethod(method);

		assertEquals(before+1, ShaderRegistry.shaderRegistrationMethods.size());
		assertTrue(ShaderRegistry.shaderRegistrationMethods.contains(method));
	}
}
