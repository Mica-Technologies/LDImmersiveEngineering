/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.compat.mts;

import blusunrize.immersiveengineering.common.util.compat.mts.MTSFuelTable.Family;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What Immersive Vehicles is told about this fork's fuels.
 * <p>
 * The whole integration is one decision repeated: given a fuel type name invented by a content pack
 * and a set of fluids that did or did not register, which of ours does that engine take and at what
 * potency. In the game that decision is buried under reflection into another mod's static config,
 * which is untestable and, more to the point, unverifiable -- MTS is not in this dev environment.
 * Pulled out into {@link MTSFuelTable} it is arithmetic and string matching, and this is the only
 * place the mapping is checked at all.
 */
class MTSFuelTableTest
{
	/** How the fork's fluids actually register on a plain install. */
	private static Map<String, String> ourFluids()
	{
		Map<String, String> names = new LinkedHashMap<>();
		names.put(MTSFuelTable.GASOLINE, "ie_gasoline");
		names.put(MTSFuelTable.DIESEL, "ie_diesel");
		names.put(MTSFuelTable.NAPHTHA, "ie_naphtha");
		names.put(MTSFuelTable.ETHANOL, "ethanol");
		names.put(MTSFuelTable.BIODIESEL, "biodiesel");
		names.put(MTSFuelTable.CREOSOTE, "creosote");
		names.put(MTSFuelTable.PROPANE, "propane");
		names.put(MTSFuelTable.PLANTOIL, "plantoil");
		return names;
	}

	/** MTS's own defaults, as its ConfigFuel builds them for a pack with petrol and diesel engines. */
	private static Map<String, Map<String, Double>> mtsDefaults()
	{
		Map<String, Map<String, Double>> fuels = new LinkedHashMap<>();
		Map<String, Double> gasoline = new LinkedHashMap<>();
		gasoline.put("lava", 1.0);
		gasoline.put("gasoline", 1.0);
		gasoline.put("ethanol", 0.85);
		fuels.put("gasoline", gasoline);
		Map<String, Double> diesel = new LinkedHashMap<>();
		diesel.put("lava", 1.0);
		diesel.put("diesel", 1.0);
		diesel.put("biodiesel", 0.8);
		diesel.put("creosote", 0.7);
		diesel.put("oil", 0.5);
		fuels.put("diesel", diesel);
		Map<String, Double> avgas = new LinkedHashMap<>();
		avgas.put("lava", 1.0);
		avgas.put("gasoline", 1.0);
		fuels.put("avgas", avgas);
		Map<String, Double> redstone = new LinkedHashMap<>();
		redstone.put("lava", 1.0);
		redstone.put("redstone", 1.0);
		fuels.put("redstone", redstone);
		return fuels;
	}

	@Nested
	@DisplayName("reading a pack's fuel type name")
	class Classification
	{
		@Test
		@DisplayName("the four names Immersive Vehicles ships with land where they should")
		void mtsOwnTypes()
		{
			assertEquals(Family.GASOLINE, MTSFuelTable.classify("gasoline"));
			assertEquals(Family.DIESEL, MTSFuelTable.classify("diesel"));
			assertEquals(Family.AVGAS, MTSFuelTable.classify("avgas"));
			//Not petroleum, and not ours to answer.
			assertNull(MTSFuelTable.classify("redstone"));
		}

		@Test
		@DisplayName("aviation gasoline is read as aviation, not as petrol")
		void avgasWinsOverGasoline()
		{
			//"avgas" contains "gas". Getting the order wrong here fuels a light aircraft with ethanol,
			//which is the one substitution this table refuses to make.
			assertEquals(Family.AVGAS, MTSFuelTable.classify("avgas"));
			assertEquals(Family.AVGAS, MTSFuelTable.classify("avgas100LL"));
			assertEquals(Family.AVGAS, MTSFuelTable.classify("aviation_fuel"));
			assertFalse(MTSFuelTable.offeringsFor(Family.AVGAS).containsKey(MTSFuelTable.ETHANOL));
		}

		@Test
		@DisplayName("a pack's invented fuel type still lands somewhere sensible")
		void packInventedTypes()
		{
			//The reason this is substring matching and not a lookup table: UNU and DKZ name their own
			//fuel types, and this code will never have seen them.
			assertEquals(Family.DIESEL, MTSFuelTable.classify("Diesel"));
			assertEquals(Family.DIESEL, MTSFuelTable.classify("biodiesel"));
			assertEquals(Family.DIESEL, MTSFuelTable.classify("heavy_diesel"));
			assertEquals(Family.GASOLINE, MTSFuelTable.classify("high_octane"));
			assertEquals(Family.GASOLINE, MTSFuelTable.classify("petrol"));
			assertEquals(Family.GASOLINE, MTSFuelTable.classify("gasoline_leaded"));
			assertEquals(Family.JET, MTSFuelTable.classify("jetfuel"));
			assertEquals(Family.JET, MTSFuelTable.classify("kerosene"));
		}

		@Test
		@DisplayName("natural gas is not petrol just because it contains 'gas'")
		void bareGasIsNotEnough()
		{
			assertNull(MTSFuelTable.classify("natural_gas"));
			assertNull(MTSFuelTable.classify("biogas"));
			//But the fuel type actually called "gas" is what a car takes.
			assertEquals(Family.GASOLINE, MTSFuelTable.classify("gas"));
		}

		@Test
		@DisplayName("the non-petroleum fuel types are left alone")
		void nonFuels()
		{
			for(String type : Arrays.asList("redstone", "water", "nothing", "electricity", "steam",
					"lava", "coal", "furnace", "brewing"))
				assertNull(MTSFuelTable.classify(type), type+" should not be answered");
		}

		@Test
		@DisplayName("nothing, whitespace and an empty name are answered with nothing")
		void degenerateNames()
		{
			assertNull(MTSFuelTable.classify(null));
			assertNull(MTSFuelTable.classify(""));
			assertNull(MTSFuelTable.classify("   "));
			//Leading and trailing space is a pack author's typo, not a different fuel.
			assertEquals(Family.DIESEL, MTSFuelTable.classify("  diesel  "));
		}
	}

	@Nested
	@DisplayName("what each family is offered")
	class Offerings
	{
		@Test
		@DisplayName("a petrol engine gets gasoline at full value and the substitutes at a discount")
		void gasolineFamily()
		{
			Map<String, Double> offered = MTSFuelTable.offeringsFor(Family.GASOLINE);
			assertEquals(1.0, offered.get(MTSFuelTable.GASOLINE));
			//MTS's own number for ethanol; a pack that already tuned it sees no change.
			assertEquals(0.85, offered.get(MTSFuelTable.ETHANOL));
			assertEquals(0.60, offered.get(MTSFuelTable.NAPHTHA));
			assertEquals(0.70, offered.get(MTSFuelTable.PROPANE));
			//A petrol engine does not run on diesel, whatever else it will tolerate.
			assertFalse(offered.containsKey(MTSFuelTable.DIESEL));
		}

		@Test
		@DisplayName("a diesel gets diesel at full value, and MTS's own numbers for the rest")
		void dieselFamily()
		{
			Map<String, Double> offered = MTSFuelTable.offeringsFor(Family.DIESEL);
			assertEquals(1.0, offered.get(MTSFuelTable.DIESEL));
			assertEquals(0.80, offered.get(MTSFuelTable.BIODIESEL));
			assertEquals(0.70, offered.get(MTSFuelTable.CREOSOTE));
			assertEquals(0.50, offered.get(MTSFuelTable.PLANTOIL));
			assertFalse(offered.containsKey(MTSFuelTable.GASOLINE));
		}

		@Test
		@DisplayName("a jet gets diesel and naphtha, because this fork has no kerosene")
		void jetFamily()
		{
			Map<String, Double> offered = MTSFuelTable.offeringsFor(Family.JET);
			assertEquals(0.90, offered.get(MTSFuelTable.DIESEL));
			assertEquals(0.70, offered.get(MTSFuelTable.NAPHTHA));
			assertFalse(offered.containsKey(MTSFuelTable.GASOLINE));
		}

		@Test
		@DisplayName("no potency is zero, negative or better than the reference fuel")
		void potenciesAreSane()
		{
			//A zero potency divides into fuel consumption in MTS. It would not be a bad fuel, it would
			//be a crash.
			for(Family family : Family.values())
				for(Map.Entry<String, Double> e : MTSFuelTable.offeringsFor(family).entrySet())
				{
					assertTrue(e.getValue() > 0, family+"/"+e.getKey()+" must be above zero");
					assertTrue(e.getValue() <= 1.0, family+"/"+e.getKey()+" must not beat the reference fuel");
				}
		}

		@Test
		@DisplayName("the fluids that are not engine fuel are offered to nobody")
		void nonFuelsAreNeverOffered()
		{
			List<String> tokens = MTSFuelTable.allOfferedTokens();
			//Crude oil, the residues and the gases each exist to give one fork machine its reason to
			//be built. None of them is a token at all, so none can leak into a vehicle.
			for(String absent : Arrays.asList("crude_oil", "heavy_fuel_oil", "lubricant", "bitumen",
					"asphalt", "sour_gas", "natural_gas", "steam", "concrete"))
				assertFalse(tokens.contains(absent), absent+" is not a vehicle fuel");
		}

		@Test
		@DisplayName("an unrecognised family is answered with an empty offering, not a crash")
		void nullFamily()
		{
			assertTrue(MTSFuelTable.offeringsFor(null).isEmpty());
		}
	}

	@Nested
	@DisplayName("building the injection")
	class Injection
	{
		@Test
		@DisplayName("only fuel types Immersive Vehicles actually has are answered")
		void onlyPresentTypes()
		{
			Map<String, Map<String, Double>> injection =
					MTSFuelTable.injectionFor(mtsDefaults().keySet(), ourFluids());
			assertTrue(injection.containsKey("gasoline"));
			assertTrue(injection.containsKey("diesel"));
			assertTrue(injection.containsKey("avgas"));
			//Present in MTS, but not ours to answer.
			assertFalse(injection.containsKey("redstone"));
			//Not present in MTS, so no engine asked for it and adding it would be noise.
			assertFalse(injection.containsKey("jetfuel"));
		}

		@Test
		@DisplayName("the fluids are named by their registry name, prefix and all")
		void registryNamesAreUsed()
		{
			Map<String, Map<String, Double>> injection =
					MTSFuelTable.injectionFor(Collections.singletonList("gasoline"), ourFluids());
			Map<String, Double> gasoline = injection.get("gasoline");
			//The bug in one assertion: MTS looks the tank's fluid up by Fluid.getName(), and ours is
			//prefixed.
			assertEquals(1.0, gasoline.get("ie_gasoline"));
			assertFalse(gasoline.containsKey(MTSFuelTable.GASOLINE));
		}

		@Test
		@DisplayName("a fluid that lost its registry name to another mod is offered under that name")
		void yieldedNamesAreFollowed()
		{
			//IEContent.setupFluid yields to whoever registered a name first, so on some packs
			//IEContent.fluidEthanol is another mod's fluid entirely. What matters is the name MTS will
			//see in the tank.
			Map<String, String> names = ourFluids();
			names.put(MTSFuelTable.ETHANOL, "somebody_elses_ethanol");
			Map<String, Double> gasoline =
					MTSFuelTable.injectionFor(Collections.singletonList("gasoline"), names).get("gasoline");
			assertEquals(0.85, gasoline.get("somebody_elses_ethanol"));
			assertFalse(gasoline.containsKey("ethanol"));
		}

		@Test
		@DisplayName("a fluid that never registered is simply not offered")
		void missingFluidsAreSkipped()
		{
			Map<String, String> names = new HashMap<>();
			names.put(MTSFuelTable.GASOLINE, "ie_gasoline");
			Map<String, Double> gasoline =
					MTSFuelTable.injectionFor(Collections.singletonList("gasoline"), names).get("gasoline");
			assertEquals(1, gasoline.size());
			assertEquals(1.0, gasoline.get("ie_gasoline"));
		}

		@Test
		@DisplayName("a fuel type we recognise but have nothing for is left out entirely")
		void emptyOfferingsAreDropped()
		{
			//A jet engine with no diesel and no naphtha registered has nothing to be told.
			Map<String, String> names = new HashMap<>();
			names.put(MTSFuelTable.GASOLINE, "ie_gasoline");
			assertFalse(MTSFuelTable.injectionFor(Collections.singletonList("jetfuel"), names)
					.containsKey("jetfuel"));
		}

		@Test
		@DisplayName("nothing in, nothing out")
		void degenerateInput()
		{
			assertTrue(MTSFuelTable.injectionFor(null, ourFluids()).isEmpty());
			assertTrue(MTSFuelTable.injectionFor(mtsDefaults().keySet(), null).isEmpty());
			assertTrue(MTSFuelTable.injectionFor(Collections.<String>emptyList(), ourFluids()).isEmpty());
		}
	}

	@Nested
	@DisplayName("folding it into the live config")
	class Merge
	{
		@Test
		@DisplayName("the reported bug: a pump takes this fork's gasoline afterwards and did not before")
		void theReportedBug()
		{
			Map<String, Map<String, Double>> fuels = mtsDefaults();
			assertFalse(fuels.get("gasoline").containsKey("ie_gasoline"));
			assertFalse(fuels.get("diesel").containsKey("ie_diesel"));

			MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids()));

			assertEquals(1.0, fuels.get("gasoline").get("ie_gasoline"));
			assertEquals(1.0, fuels.get("diesel").get("ie_diesel"));
			assertEquals(1.0, fuels.get("avgas").get("ie_gasoline"));
		}

		@Test
		@DisplayName("what was added is reported, and only what was added")
		void reportsOnlyChanges()
		{
			Map<String, Map<String, Double>> fuels = mtsDefaults();
			Map<String, Map<String, Double>> added =
					MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids()));

			//ethanol, biodiesel and creosote are already in MTS's defaults under those exact names, so
			//they are folded in without being claimed as new.
			assertFalse(added.get("gasoline").containsKey("ethanol"));
			assertFalse(added.get("diesel").containsKey("biodiesel"));
			assertFalse(added.get("diesel").containsKey("creosote"));
			assertTrue(added.get("gasoline").containsKey("ie_gasoline"));
			assertTrue(added.get("gasoline").containsKey("propane"));
			assertTrue(added.get("diesel").containsKey("plantoil"));
			assertFalse(added.containsKey("redstone"));
		}

		@Test
		@DisplayName("a potency somebody edited by hand is not overwritten")
		void handEditsSurvive()
		{
			//mtsconfig.json is a file modpack authors tune. Silently reverting their numbers on every
			//launch would be worse than not integrating at all.
			Map<String, Map<String, Double>> fuels = mtsDefaults();
			fuels.get("gasoline").put("ie_gasoline", 0.25);

			Map<String, Map<String, Double>> added =
					MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids()));

			assertEquals(0.25, fuels.get("gasoline").get("ie_gasoline"));
			assertFalse(added.get("gasoline").containsKey("ie_gasoline"));
		}

		@Test
		@DisplayName("running it twice changes nothing the second time")
		void idempotent()
		{
			Map<String, Map<String, Double>> fuels = mtsDefaults();
			MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids()));
			Map<String, Map<String, Double>> secondRun =
					MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids()));
			assertTrue(secondRun.isEmpty());
		}

		@Test
		@DisplayName("no fuel type is invented that no engine asked for")
		void neverAddsFuelTypes()
		{
			Map<String, Map<String, Double>> fuels = mtsDefaults();
			int before = fuels.size();

			Map<String, Map<String, Double>> injection = new LinkedHashMap<>();
			Map<String, Double> invented = new LinkedHashMap<>();
			invented.put("ie_diesel", 1.0);
			injection.put("a_fuel_type_no_pack_has", invented);

			assertTrue(MTSFuelTable.merge(fuels, injection).isEmpty());
			assertEquals(before, fuels.size());
		}

		@Test
		@DisplayName("a null on either side is survived")
		void degenerateMerge()
		{
			assertTrue(MTSFuelTable.merge(null, mtsDefaults()).isEmpty());
			assertTrue(MTSFuelTable.merge(mtsDefaults(), null).isEmpty());
		}

		@Test
		@DisplayName("a fuel type with a null fluid map is skipped rather than crashed on")
		void nullFluidMap()
		{
			Map<String, Map<String, Double>> fuels = new LinkedHashMap<>();
			fuels.put("gasoline", null);
			assertTrue(MTSFuelTable.merge(fuels, MTSFuelTable.injectionFor(fuels.keySet(), ourFluids())).isEmpty());
		}
	}
}
