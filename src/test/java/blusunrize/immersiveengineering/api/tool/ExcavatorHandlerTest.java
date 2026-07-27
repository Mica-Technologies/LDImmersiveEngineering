/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.DimensionChunkCoords;
import blusunrize.immersiveengineering.api.tool.ExcavatorHandler.MineralMix;
import blusunrize.immersiveengineering.api.tool.ExcavatorHandler.MineralSelection;
import blusunrize.immersiveengineering.api.tool.ExcavatorHandler.MineralWorldInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the parts of {@link ExcavatorHandler} that are pure data/maths: the mineral mix
 * NBT round trip, dimension white/blacklisting, the weighted vein selection and the
 * per-chunk world info bookkeeping.
 * <p>
 * Anything that touches the ore dictionary ({@code recalculateChances}) or a live
 * {@code World} ({@code getMineralWorldInfo}, {@code depleteMinerals}) is deliberately
 * left out -- those need a Forge bootstrap.
 */
class ExcavatorHandlerTest
{
	private int savedVeinCapacity;
	private double savedChance;
	private int[] savedDefaultBlacklist;
	private boolean savedAllowPackets;

	@BeforeEach
	void saveStatics()
	{
		savedVeinCapacity = ExcavatorHandler.mineralVeinCapacity;
		savedChance = ExcavatorHandler.mineralChance;
		savedDefaultBlacklist = ExcavatorHandler.defaultDimensionBlacklist;
		savedAllowPackets = ExcavatorHandler.allowPackets;
		ExcavatorHandler.mineralList.clear();
		ExcavatorHandler.mineralCache.clear();
		ExcavatorHandler.defaultDimensionBlacklist = new int[0];
	}

	@AfterEach
	void restoreStatics()
	{
		ExcavatorHandler.mineralList.clear();
		ExcavatorHandler.mineralCache.clear();
		ExcavatorHandler.mineralVeinCapacity = savedVeinCapacity;
		ExcavatorHandler.mineralChance = savedChance;
		ExcavatorHandler.defaultDimensionBlacklist = savedDefaultBlacklist;
		ExcavatorHandler.allowPackets = savedAllowPackets;
	}

	private static MineralMix mix(String name, String... ores)
	{
		float[] chances = new float[ores.length];
		for(int i = 0; i < chances.length; i++)
			chances[i] = 1;
		MineralMix m = new MineralMix(name, 0, ores, chances);
		m.recalculatedChances = new float[0];
		return m;
	}

	/**
	 * A NonNullList that remembers which index was last read, so the weighted pick in
	 * {@code getRandomOre} can be verified even though every element has to be
	 * {@code ItemStack.EMPTY} (real ItemStacks need a bootstrapped item registry).
	 */
	private static final class IndexSpyList extends NonNullList<ItemStack>
	{
		int lastIndex = -1;

		IndexSpyList(int size)
		{
			super(new ArrayList<>(Collections.nCopies(size, ItemStack.EMPTY)), ItemStack.EMPTY);
		}

		@Override
		public ItemStack get(int index)
		{
			lastIndex = index;
			return super.get(index);
		}
	}

	/** A Random whose nextFloat/nextInt are fully scripted. */
	private static final class ScriptedRandom extends Random
	{
		private final float nextFloat;
		private final int nextInt;

		ScriptedRandom(float nextFloat, int nextInt)
		{
			this.nextFloat = nextFloat;
			this.nextInt = nextInt;
		}

		@Override
		public float nextFloat()
		{
			return nextFloat;
		}

		@Override
		public int nextInt()
		{
			return nextInt;
		}
	}

	@Nested
	@DisplayName("MineralMix")
	class MineralMixTest
	{
		@Test
		@DisplayName("constructor stores name, fail chance, ores and chances verbatim")
		void constructorStoresFields()
		{
			String[] ores = {"oreIron", "oreGold"};
			float[] chances = {.75f, .25f};
			MineralMix m = new MineralMix("iron_gold", .5f, ores, chances);

			assertEquals("iron_gold", m.name);
			assertEquals(.5f, m.failChance, 0);
			assertSame(ores, m.ores);
			assertSame(chances, m.chances);
			assertFalse(m.isValid(), "a mix is invalid until recalculateChances() has run");
		}

		@Test
		@DisplayName("constructor takes a defensive copy of the default dimension blacklist")
		void constructorClonesDefaultBlacklist()
		{
			ExcavatorHandler.defaultDimensionBlacklist = new int[]{-1, 1};
			MineralMix m = mix("a", "oreIron");

			assertArrayEquals(new int[]{-1, 1}, m.dimensionBlacklist);
			assertNotSame(ExcavatorHandler.defaultDimensionBlacklist, m.dimensionBlacklist);

			// mutating the shared default must not reach through into an existing mix
			ExcavatorHandler.defaultDimensionBlacklist[0] = 99;
			assertArrayEquals(new int[]{-1, 1}, m.dimensionBlacklist);
		}

		@Test
		@DisplayName("addReplacement creates the map lazily and is chainable")
		void addReplacementIsLazyAndChainable()
		{
			MineralMix m = mix("a", "oreIron");
			assertNull(m.replacementOres);

			MineralMix returned = m.addReplacement("oreIron", "oreCopper");
			assertSame(m, returned);
			assertNotNull(m.replacementOres);
			assertEquals("oreCopper", m.replacementOres.get("oreIron"));
		}

		@Test
		@DisplayName("addReplacement overwrites an existing mapping for the same ore")
		void addReplacementOverwrites()
		{
			MineralMix m = mix("a", "oreIron");
			m.addReplacement("oreIron", "oreCopper");
			m.addReplacement("oreIron", "oreLead");

			assertEquals(1, m.replacementOres.size());
			assertEquals("oreLead", m.replacementOres.get("oreIron"));
		}

		@Test
		@DisplayName("validDimension allows every dimension when neither list is set")
		void validDimensionDefaultsToAll()
		{
			MineralMix m = mix("a", "oreIron");
			assertTrue(m.validDimension(0));
			assertTrue(m.validDimension(-1));
			assertTrue(m.validDimension(Integer.MAX_VALUE));
			assertTrue(m.validDimension(Integer.MIN_VALUE));
		}

		@Test
		@DisplayName("validDimension honours a whitelist")
		void validDimensionWhitelist()
		{
			MineralMix m = mix("a", "oreIron");
			m.dimensionWhitelist = new int[]{0, 7};

			assertTrue(m.validDimension(0));
			assertTrue(m.validDimension(7));
			assertFalse(m.validDimension(1));
			assertFalse(m.validDimension(-1));
		}

		@Test
		@DisplayName("validDimension honours a blacklist")
		void validDimensionBlacklist()
		{
			MineralMix m = mix("a", "oreIron");
			m.dimensionBlacklist = new int[]{-1, 1};

			assertFalse(m.validDimension(-1));
			assertFalse(m.validDimension(1));
			assertTrue(m.validDimension(0));
		}

		@Test
		@DisplayName("a whitelist wins over a blacklist")
		void whitelistTakesPrecedenceOverBlacklist()
		{
			MineralMix m = mix("a", "oreIron");
			m.dimensionWhitelist = new int[]{1};
			m.dimensionBlacklist = new int[]{1};

			assertTrue(m.validDimension(1), "dim 1 is whitelisted, the blacklist must not be consulted");
			assertFalse(m.validDimension(0), "dim 0 is not whitelisted");
		}

		@Test
		@DisplayName("empty white/blacklists behave like no list at all")
		void emptyListsAllowEverything()
		{
			MineralMix m = mix("a", "oreIron");
			m.dimensionWhitelist = new int[0];
			m.dimensionBlacklist = new int[0];

			assertTrue(m.validDimension(0));
			assertTrue(m.validDimension(42));
		}

		@Test
		@DisplayName("null white/blacklists are tolerated")
		void nullListsAllowEverything()
		{
			MineralMix m = mix("a", "oreIron");
			m.dimensionWhitelist = null;
			m.dimensionBlacklist = null;

			assertTrue(m.validDimension(0));
		}

		@Test
		@DisplayName("NBT round trip preserves every serialised field")
		void nbtRoundTrip()
		{
			MineralMix m = new MineralMix("deep_iron", .25f,
					new String[]{"oreIron", "oreGold", "oreCoal"}, new float[]{3, 2, 1});
			m.recalculatedChances = new float[]{.5f, .3333f, .1667f};
			m.isValid = true;
			m.dimensionWhitelist = new int[]{0};
			m.dimensionBlacklist = new int[]{-1, 1};

			MineralMix read = MineralMix.readFromNBT(m.writeToNBT());

			assertEquals("deep_iron", read.name);
			assertEquals(.25f, read.failChance, 0);
			assertArrayEquals(new String[]{"oreIron", "oreGold", "oreCoal"}, read.ores);
			assertArrayEquals(new float[]{3, 2, 1}, read.chances, 0);
			assertArrayEquals(new float[]{.5f, .3333f, .1667f}, read.recalculatedChances, 1e-6f);
			assertTrue(read.isValid());
			assertArrayEquals(new int[]{0}, read.dimensionWhitelist);
			assertArrayEquals(new int[]{-1, 1}, read.dimensionBlacklist);
		}

		@Test
		@DisplayName("NBT round trip of a mix with no ores at all")
		void nbtRoundTripEmpty()
		{
			MineralMix m = mix("nothing");
			MineralMix read = MineralMix.readFromNBT(m.writeToNBT());

			assertEquals("nothing", read.name);
			assertEquals(0, read.ores.length);
			assertEquals(0, read.chances.length);
			assertEquals(0, read.recalculatedChances.length);
			assertFalse(read.isValid());
		}

		@Test
		@DisplayName("NBT round trip survives extreme fail chances")
		void nbtRoundTripExtremeFailChance()
		{
			for(float f : new float[]{-1f, 0f, 1f, Float.MAX_VALUE})
			{
				MineralMix m = new MineralMix("x", f, new String[0], new float[0]);
				m.recalculatedChances = new float[0];
				assertEquals(f, MineralMix.readFromNBT(m.writeToNBT()).failChance, 0, "failChance "+f);
			}
		}

		@Test
		@DisplayName("readFromNBT of an empty tag yields harmless defaults rather than nulls")
		void readFromEmptyTag()
		{
			MineralMix read = MineralMix.readFromNBT(new NBTTagCompound());

			assertEquals("", read.name);
			assertEquals(0, read.failChance, 0);
			assertNotNull(read.ores);
			assertEquals(0, read.ores.length);
			assertNotNull(read.recalculatedChances);
			assertEquals(0, read.recalculatedChances.length);
			assertFalse(read.isValid());
		}

		@Test
		@DisplayName("writeToNBT tolerates a null oreOutput")
		void writeToNbtWithNullOreOutput()
		{
			MineralMix m = mix("a", "oreIron");
			assertNull(m.oreOutput);

			NBTTagCompound tag = assertDoesNotThrow(m::writeToNBT);
			assertEquals(0, tag.getTagList("oreOutput", 10).tagCount());
		}

		@Test
		@DisplayName("getRandomOre returns EMPTY when there is nothing to pick from")
		void getRandomOreWithNoChances()
		{
			MineralMix m = mix("a");
			m.oreOutput = NonNullList.create();

			assertSame(ItemStack.EMPTY, m.getRandomOre(new ScriptedRandom(0f, 0)));
			assertSame(ItemStack.EMPTY, m.getRandomOre(new ScriptedRandom(.999f, 0)));
		}

		@Test
		@DisplayName("getRandomOre picks the first entry for a low roll")
		void getRandomOrePicksFirst()
		{
			MineralMix m = mix("a", "oreIron", "oreGold");
			IndexSpyList out = new IndexSpyList(2);
			m.oreOutput = out;
			m.recalculatedChances = new float[]{.4f, .6f};

			m.getRandomOre(new ScriptedRandom(.0f, 0));
			assertEquals(0, out.lastIndex);
		}

		@Test
		@DisplayName("getRandomOre picks the second entry once the first weight is exhausted")
		void getRandomOrePicksSecond()
		{
			MineralMix m = mix("a", "oreIron", "oreGold");
			IndexSpyList out = new IndexSpyList(2);
			m.oreOutput = out;
			m.recalculatedChances = new float[]{.4f, .6f};

			m.getRandomOre(new ScriptedRandom(.5f, 0));
			assertEquals(1, out.lastIndex);
		}

		@Test
		@DisplayName("getRandomOre falls through to EMPTY when the weights do not add up to 1")
		void getRandomOreFallsThrough()
		{
			MineralMix m = mix("a", "oreIron");
			IndexSpyList out = new IndexSpyList(1);
			m.oreOutput = out;
			m.recalculatedChances = new float[]{.25f};

			assertSame(ItemStack.EMPTY, m.getRandomOre(new ScriptedRandom(.9f, 0)));
			assertEquals(-1, out.lastIndex, "nothing should have been read out of the output list");
		}
	}

	@Nested
	@DisplayName("MineralWorldInfo")
	class MineralWorldInfoTest
	{
		@Test
		@DisplayName("a fresh info has no vein and no depletion")
		void defaults()
		{
			MineralWorldInfo info = new MineralWorldInfo();
			assertNull(info.mineral);
			assertNull(info.mineralOverride);
			assertEquals(0, info.depletion);
		}

		@Test
		@DisplayName("writeToNBT omits the mineral keys when there is no vein")
		void writeOmitsAbsentMinerals()
		{
			NBTTagCompound tag = new MineralWorldInfo().writeToNBT();

			assertFalse(tag.hasKey("mineral"));
			assertFalse(tag.hasKey("mineralOverride"));
			assertTrue(tag.hasKey("depletion"));
		}

		@Test
		@DisplayName("the vein is resolved back out of the registered mineral list")
		void roundTripResolvesMineral()
		{
			MineralMix iron = ExcavatorHandler.addMineral("iron", 10, 0, new String[]{"oreIron"}, new float[]{1});

			MineralWorldInfo info = new MineralWorldInfo();
			info.mineral = iron;
			info.depletion = 17;

			MineralWorldInfo read = MineralWorldInfo.readFromNBT(info.writeToNBT());
			assertSame(iron, read.mineral);
			assertNull(read.mineralOverride);
			assertEquals(17, read.depletion);
		}

		@Test
		@DisplayName("the vein name is matched case-insensitively")
		void roundTripIsCaseInsensitive()
		{
			MineralMix mixed = ExcavatorHandler.addMineral("MiXeD", 10, 0, new String[]{"oreIron"}, new float[]{1});

			NBTTagCompound tag = new NBTTagCompound();
			tag.setString("mineral", "mixed");
			tag.setInteger("depletion", 0);

			assertSame(mixed, MineralWorldInfo.readFromNBT(tag).mineral);
		}

		@Test
		@DisplayName("an unknown vein name reads back as no vein instead of throwing")
		void unknownMineralNameYieldsNull()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString("mineral", "does_not_exist");

			MineralWorldInfo read = MineralWorldInfo.readFromNBT(tag);
			assertNull(read.mineral);
			assertEquals(0, read.depletion);
		}

		@Test
		@DisplayName("the override vein round trips independently of the base vein")
		void overrideRoundTrip()
		{
			MineralMix base = ExcavatorHandler.addMineral("base", 1, 0, new String[]{"oreIron"}, new float[]{1});
			MineralMix over = ExcavatorHandler.addMineral("over", 1, 0, new String[]{"oreGold"}, new float[]{1});

			MineralWorldInfo info = new MineralWorldInfo();
			info.mineral = base;
			info.mineralOverride = over;

			MineralWorldInfo read = MineralWorldInfo.readFromNBT(info.writeToNBT());
			assertSame(base, read.mineral);
			assertSame(over, read.mineralOverride);
		}

		@Test
		@DisplayName("depletion round trips at its boundaries")
		void depletionBoundaries()
		{
			for(int d : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
			{
				MineralWorldInfo info = new MineralWorldInfo();
				info.depletion = d;
				assertEquals(d, MineralWorldInfo.readFromNBT(info.writeToNBT()).depletion, "depletion "+d);
			}
		}
	}

	@Nested
	@DisplayName("addMineral")
	class AddMineralTest
	{
		@Test
		@DisplayName("registers the mix with its weight and hands it back")
		void registersAndReturns()
		{
			MineralMix m = ExcavatorHandler.addMineral("iron", 25, .1f, new String[]{"oreIron"}, new float[]{1});

			assertNotNull(m);
			assertEquals("iron", m.name);
			assertEquals(.1f, m.failChance, 0);
			assertEquals(1, ExcavatorHandler.mineralList.size());
			assertEquals(Integer.valueOf(25), ExcavatorHandler.mineralList.get(m));
		}

		@Test
		@DisplayName("keeps registration order -- vein picking walks the list in order")
		void keepsInsertionOrder()
		{
			MineralMix a = ExcavatorHandler.addMineral("a", 1, 0, new String[]{"oreA"}, new float[]{1});
			MineralMix b = ExcavatorHandler.addMineral("b", 2, 0, new String[]{"oreB"}, new float[]{1});
			MineralMix c = ExcavatorHandler.addMineral("c", 3, 0, new String[]{"oreC"}, new float[]{1});

			List<MineralMix> order = new ArrayList<>(ExcavatorHandler.mineralList.keySet());
			assertEquals(3, order.size());
			assertSame(a, order.get(0));
			assertSame(b, order.get(1));
			assertSame(c, order.get(2));
		}

		@Test
		@DisplayName("applies the configured default dimension blacklist")
		void appliesDefaultBlacklist()
		{
			ExcavatorHandler.defaultDimensionBlacklist = new int[]{-1, 1};
			MineralMix m = ExcavatorHandler.addMineral("a", 1, 0, new String[]{"oreA"}, new float[]{1});

			assertArrayEquals(new int[]{-1, 1}, m.dimensionBlacklist);
			assertFalse(m.validDimension(1));
			assertTrue(m.validDimension(0));
		}
	}

	@Nested
	@DisplayName("MineralSelection")
	class MineralSelectionTest
	{
		private final DimensionChunkCoords origin = new DimensionChunkCoords(0, 10, 10);

		private MineralMix registerValid(String name, int weight)
		{
			MineralMix m = ExcavatorHandler.addMineral(name, weight, 0, new String[]{"ore"+name}, new float[]{1});
			m.isValid = true;
			return m;
		}

		@Test
		@DisplayName("total weight is the sum of every eligible mineral's weight")
		void totalWeightSumsEligible()
		{
			registerValid("a", 10);
			registerValid("b", 15);
			registerValid("c", 5);

			assertEquals(30, new MineralSelection(null, origin, 2).getTotalWeight());
		}

		@Test
		@DisplayName("minerals that never resolved to a real ore are skipped")
		void invalidMineralsAreSkipped()
		{
			registerValid("a", 10);
			ExcavatorHandler.addMineral("b", 100, 0, new String[]{"oreB"}, new float[]{1}); // isValid stays false

			MineralSelection sel = new MineralSelection(null, origin, 2);
			assertEquals(10, sel.getTotalWeight());
			assertEquals(1, sel.getMinerals().size());
		}

		@Test
		@DisplayName("minerals barred from the dimension are skipped")
		void dimensionFilteringIsApplied()
		{
			registerValid("a", 10);
			MineralMix b = registerValid("b", 100);
			b.dimensionBlacklist = new int[]{0};

			assertEquals(10, new MineralSelection(null, origin, 2).getTotalWeight());
			// ... but the same mineral is eligible in another dimension
			assertEquals(110, new MineralSelection(null, new DimensionChunkCoords(1, 10, 10), 2).getTotalWeight());
		}

		@Test
		@DisplayName("a mineral already present in a neighbouring chunk is excluded")
		void surroundingVeinsAreExcluded()
		{
			registerValid("a", 10);
			MineralMix b = registerValid("b", 100);

			MineralWorldInfo neighbour = new MineralWorldInfo();
			neighbour.mineral = b;
			ExcavatorHandler.mineralCache.put(origin.withOffset(1, 0), neighbour);

			assertEquals(10, new MineralSelection(null, origin, 2).getTotalWeight());
		}

		@Test
		@DisplayName("the exclusion radius is respected")
		void exclusionRadiusIsRespected()
		{
			registerValid("a", 10);
			MineralMix b = registerValid("b", 100);

			MineralWorldInfo far = new MineralWorldInfo();
			far.mineral = b;
			ExcavatorHandler.mineralCache.put(origin.withOffset(3, 0), far);

			assertEquals(110, new MineralSelection(null, origin, 2).getTotalWeight(),
					"a vein 3 chunks away is outside a radius of 2");
			assertEquals(10, new MineralSelection(null, origin, 3).getTotalWeight(),
					"the same vein is inside a radius of 3");
		}

		@Test
		@DisplayName("the chunk's own cached vein does not exclude itself")
		void ownChunkDoesNotExcludeItself()
		{
			MineralMix a = registerValid("a", 10);

			MineralWorldInfo self = new MineralWorldInfo();
			self.mineral = a;
			ExcavatorHandler.mineralCache.put(origin, self);

			assertEquals(10, new MineralSelection(null, origin, 2).getTotalWeight());
		}

		@Test
		@DisplayName("a cached neighbour with no vein excludes nothing")
		void emptyNeighbourExcludesNothing()
		{
			registerValid("a", 10);
			ExcavatorHandler.mineralCache.put(origin.withOffset(1, 1), new MineralWorldInfo());

			assertEquals(10, new MineralSelection(null, origin, 2).getTotalWeight());
		}

		@Test
		@DisplayName("candidates keep the deterministic order of the registration list")
		void candidateOrderMatchesRegistrationOrder()
		{
			MineralMix a = registerValid("a", 1);
			MineralMix b = registerValid("b", 2);
			MineralMix c = registerValid("c", 3);

			List<MineralMix> seen = new ArrayList<>();
			for(Map.Entry<MineralMix, Integer> e : new MineralSelection(null, origin, 2).getMinerals())
				seen.add(e.getKey());

			assertEquals(3, seen.size());
			assertSame(a, seen.get(0));
			assertSame(b, seen.get(1));
			assertSame(c, seen.get(2));
		}

		@Test
		@DisplayName("with nothing eligible the total weight is zero and the candidate set is empty")
		void nothingEligible()
		{
			ExcavatorHandler.addMineral("a", 10, 0, new String[]{"oreA"}, new float[]{1}); // invalid

			MineralSelection sel = new MineralSelection(null, origin, 2);
			assertEquals(0, sel.getTotalWeight());
			assertTrue(sel.getMinerals().isEmpty());
		}

		@Test
		@DisplayName("getRandomWeight always lands inside [0, totalWeight)")
		void randomWeightIsInRange()
		{
			registerValid("a", 7);
			registerValid("b", 13);
			MineralSelection sel = new MineralSelection(null, origin, 2);

			Random rand = new Random(0xC0FFEE);
			for(int i = 0; i < 500; i++)
			{
				int w = sel.getRandomWeight(rand);
				assertTrue(w >= 0&&w < 20, "weight out of range: "+w);
			}
		}

		@Test
		@DisplayName("getRandomWeight copes with Integer.MIN_VALUE coming out of the RNG")
		void randomWeightHandlesMinValue()
		{
			registerValid("a", 100);
			MineralSelection sel = new MineralSelection(null, origin, 2);

			int w = sel.getRandomWeight(new ScriptedRandom(0, Integer.MIN_VALUE));
			assertTrue(w >= 0&&w < 100, "weight out of range: "+w);
		}

		@Test
		@DisplayName("walking the candidate weights selects the expected vein")
		void weightWalkSelectsExpectedVein()
		{
			MineralMix a = registerValid("a", 10);
			MineralMix b = registerValid("b", 20);
			MineralMix c = registerValid("c", 30);
			MineralSelection sel = new MineralSelection(null, origin, 2);

			// this mirrors the loop in getMineralWorldInfo
			assertSame(a, pick(sel, 0));
			assertSame(a, pick(sel, 9));
			assertSame(b, pick(sel, 10));
			assertSame(b, pick(sel, 29));
			assertSame(c, pick(sel, 30));
			assertSame(c, pick(sel, 59));
			assertNull(pick(sel, 60), "a weight at or past the total falls off the end");
		}

		private MineralMix pick(MineralSelection sel, int weight)
		{
			for(Map.Entry<MineralMix, Integer> e : sel.getMinerals())
			{
				weight -= e.getValue();
				if(weight < 0)
					return e.getKey();
			}
			return null;
		}
	}
}
