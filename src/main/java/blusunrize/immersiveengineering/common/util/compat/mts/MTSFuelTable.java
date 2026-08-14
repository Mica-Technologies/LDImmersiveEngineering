/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.compat.mts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which of this fork's fluids Immersive Vehicles should accept, for which of its engine fuel types,
 * and at what potency.
 * <p>
 * MTS keeps its fuels as {@code Map<fuelType, Map<fluidRegistryName, potency>>} in
 * {@code ConfigSystem.settings.fuel.fuels}. That map is seeded once, from a hardcoded default table
 * keyed on the fuel types the loaded packs actually ask for, and a fuel type already present is
 * never revisited. Its gasoline default is {@code lava/gasoline/ethanol}, its diesel default
 * {@code lava/diesel/biodiesel/creosote/oil} -- all bare names. This fork prefixes its distillation
 * cuts {@code ie_} to avoid inheriting another mod's fluid, so {@code ie_gasoline} and
 * {@code ie_diesel} are absent from every entry, and a fuel pump rejects them on the spot. This
 * table is what closes that gap.
 * <p>
 * Deliberately free of Minecraft and Forge: fluids are named here by <em>token</em>, and the caller
 * supplies the token-to-registry-name mapping it read off the live {@code Fluid} objects. That keeps
 * the whole decision -- what counts as a petrol engine, what a diesel will tolerate, what each fluid
 * is worth -- testable without a game.
 * <p>
 * Potencies divide fuel consumption in MTS ({@code consumption / potency}), so 1.0 is a full-value
 * fuel and 0.5 is one that burns twice as fast. The values here follow two rules: match MTS's own
 * defaults wherever we are offering the same substance under a different name, and otherwise scale
 * by volumetric energy content against the reference fuel for that family.
 *
 * @see MTSHelper
 */
public class MTSFuelTable
{
	/**
	 * Tokens for the fork's fluids. The caller resolves each to a Forge registry name; a token with
	 * no mapping is simply not offered, so a fluid that failed to register cannot poison the merge.
	 */
	public static final String GASOLINE = "gasoline";
	public static final String DIESEL = "diesel";
	public static final String NAPHTHA = "naphtha";
	public static final String ETHANOL = "ethanol";
	public static final String BIODIESEL = "biodiesel";
	public static final String CREOSOTE = "creosote";
	public static final String PROPANE = "propane";
	public static final String PLANTOIL = "plantoil";

	/**
	 * The kinds of engine this fork has something to offer. MTS's own enum is
	 * {@code GASOLINE/DIESEL/AVGAS/REDSTONE/WATER/NOTHING}; the last three are not petroleum and are
	 * left alone. JET is not one of MTS's defaults at all -- packs that define a kerosene or jet
	 * fuel type get nothing but lava from MTS -- which is exactly why it is worth answering.
	 */
	public enum Family
	{
		GASOLINE,
		AVGAS,
		DIESEL,
		JET
	}

	private static final Map<Family, Map<String, Double>> OFFERINGS = new EnumMap<>(Family.class);

	static
	{
		//Spark ignition. Gasoline is the reference; ethanol at 0.85 is MTS's own value for it, so a
		//pack that has already tuned ethanol sees no change. Naphtha runs a petrol engine but knocks
		//badly -- it is a blendstock, not a finished fuel -- and the fork already prices it as the cut
		//worth cracking rather than burning. Propane is an LPG conversion: real, common on small
		//engines, and about seven tenths of gasoline by the litre.
		Map<String, Double> gasoline = new LinkedHashMap<>();
		gasoline.put(MTSFuelTable.GASOLINE, 1.0);
		gasoline.put(MTSFuelTable.ETHANOL, 0.85);
		gasoline.put(MTSFuelTable.NAPHTHA, 0.60);
		gasoline.put(MTSFuelTable.PROPANE, 0.70);
		OFFERINGS.put(Family.GASOLINE, Collections.unmodifiableMap(gasoline));

		//Aviation gasoline. MTS's own avgas default is lava and gasoline, nothing else -- no ethanol,
		//no substitutes -- and there is no reason to be more generous than the mod is with itself.
		Map<String, Double> avgas = new LinkedHashMap<>();
		avgas.put(MTSFuelTable.GASOLINE, 1.0);
		OFFERINGS.put(Family.AVGAS, Collections.unmodifiableMap(avgas));

		//Compression ignition. Biodiesel at 0.80 and creosote at 0.70 are MTS's own values; plant oil
		//at 0.50 matches what MTS gives its generic "oil", which is the same bargain -- a straight
		//vegetable oil will run a diesel, poorly.
		Map<String, Double> diesel = new LinkedHashMap<>();
		diesel.put(MTSFuelTable.DIESEL, 1.0);
		diesel.put(MTSFuelTable.BIODIESEL, 0.80);
		diesel.put(MTSFuelTable.CREOSOTE, 0.70);
		diesel.put(MTSFuelTable.PLANTOIL, 0.50);
		OFFERINGS.put(Family.DIESEL, Collections.unmodifiableMap(diesel));

		//Turbines. The fork has no kerosene -- the design folded its yield into diesel -- so diesel is
		//what a jet gets, at 0.90 for the cetane-vs-kerosene mismatch. Naphtha is offered too because
		//a naphtha-kerosene blend is a real wide-cut jet fuel, at the same discount it takes elsewhere.
		Map<String, Double> jet = new LinkedHashMap<>();
		jet.put(MTSFuelTable.DIESEL, 0.90);
		jet.put(MTSFuelTable.NAPHTHA, 0.70);
		OFFERINGS.put(Family.JET, Collections.unmodifiableMap(jet));
	}

	/**
	 * Fuel types we refuse to touch however their name reads. Redstone, water and "nothing" are MTS's
	 * own non-petroleum defaults; the rest are the fuel types crafters, furnaces and electric drives
	 * register, none of which should start taking diesel because the word happened to match.
	 */
	private static final String[] NEVER = {
			"redstone", "water", "nothing", "electric", "steam", "lava", "coal", "furnace", "brewer",
			"brewing", "milk", "creative"
	};

	/**
	 * What family of engine a pack's fuel type name describes, or null for one this fork has nothing
	 * to offer.
	 * <p>
	 * MTS's own types are matched exactly; the substring rules exist for the packs, which invent
	 * their own freely -- UNU and DKZ engines are the reason a fuel type this code has never heard of
	 * still has to land somewhere sensible rather than nowhere.
	 */
	public static Family classify(String fuelType)
	{
		if(fuelType==null)
			return null;
		String name = fuelType.trim().toLowerCase(Locale.ROOT);
		if(name.isEmpty())
			return null;
		for(String never : NEVER)
			if(name.contains(never))
				return null;
		//Aviation before the general petrol rule: "avgas" contains "gas" and would otherwise be read
		//as a car engine, which would hand a light aircraft a tank of ethanol.
		if(name.contains("avgas")||name.contains("aviation")||name.contains("100ll"))
			return Family.AVGAS;
		if(name.contains("jet")||name.contains("kerosene")||name.contains("kerosine")||name.contains("jp8")
				||name.contains("jp-8")||name.contains("turbine"))
			return Family.JET;
		if(name.contains("diesel"))
			return Family.DIESEL;
		//Never a bare "gas" substring: "natural_gas" is a fuel type this fork would be wrong to answer
		//as though it were petrol.
		if(name.contains("gasoline")||name.contains("petrol")||name.contains("octane")||"gas".equals(name))
			return Family.GASOLINE;
		return null;
	}

	/**
	 * What this fork would like to add, for the fuel types MTS actually has.
	 *
	 * @param fuelTypes     the keys of MTS's live fuel map -- the fuel types the loaded packs' engines
	 *                      ask for, and the only ones worth answering
	 * @param registryNames token to Forge registry name, for the fork fluids that registered
	 * @return fuel type to (registry name to potency), carrying only fuel types we recognised and
	 * only fluids that exist
	 */
	public static Map<String, Map<String, Double>> injectionFor(Collection<String> fuelTypes, Map<String, String> registryNames)
	{
		Map<String, Map<String, Double>> injection = new LinkedHashMap<>();
		if(fuelTypes==null||registryNames==null)
			return injection;
		for(String fuelType : fuelTypes)
		{
			Family family = classify(fuelType);
			if(family==null)
				continue;
			Map<String, Double> offered = new LinkedHashMap<>();
			for(Map.Entry<String, Double> e : OFFERINGS.get(family).entrySet())
			{
				String registryName = registryNames.get(e.getKey());
				if(registryName!=null&&!registryName.isEmpty())
					offered.put(registryName, e.getValue());
			}
			if(!offered.isEmpty())
				injection.put(fuelType, offered);
		}
		return injection;
	}

	/**
	 * Folds an injection into MTS's live fuel map, and reports what it actually changed.
	 * <p>
	 * Never overwrites: a fluid already listed for a fuel type keeps whatever potency it has, because
	 * that value may be a modpack author's deliberate edit to {@code mtsconfig.json} and this
	 * integration has no business silently undoing it. Never adds a fuel type either -- a type no
	 * engine asked for is noise in somebody's config file.
	 *
	 * @return the subset that was added, in the same shape, so a caller can log exactly what it did
	 */
	public static Map<String, Map<String, Double>> merge(Map<String, Map<String, Double>> target, Map<String, Map<String, Double>> injection)
	{
		Map<String, Map<String, Double>> added = new LinkedHashMap<>();
		if(target==null||injection==null)
			return added;
		for(Map.Entry<String, Map<String, Double>> entry : injection.entrySet())
		{
			Map<String, Double> existing = target.get(entry.getKey());
			if(existing==null)
				continue;
			Map<String, Double> addedHere = new LinkedHashMap<>();
			for(Map.Entry<String, Double> fluid : entry.getValue().entrySet())
				if(!existing.containsKey(fluid.getKey()))
				{
					existing.put(fluid.getKey(), fluid.getValue());
					addedHere.put(fluid.getKey(), fluid.getValue());
				}
			if(!addedHere.isEmpty())
				added.put(entry.getKey(), addedHere);
		}
		return added;
	}

	/**
	 * The tokens this fork offers for a given family, for documentation and tests. Empty for a null
	 * family, so callers need not special-case an unrecognised fuel type.
	 */
	public static Map<String, Double> offeringsFor(Family family)
	{
		if(family==null)
			return Collections.emptyMap();
		return OFFERINGS.get(family);
	}

	/**
	 * Every token any family offers, in a stable order. The fork's other fluids -- crude oil, sour
	 * gas, heavy fuel oil, lubricant, bitumen, asphalt, natural gas, steam, concrete -- are absent on
	 * purpose; see the class documentation of {@link MTSHelper}.
	 */
	public static List<String> allOfferedTokens()
	{
		List<String> tokens = new ArrayList<>();
		for(Family family : Family.values())
			for(String token : OFFERINGS.get(family).keySet())
				if(!tokens.contains(token))
					tokens.add(token);
		return tokens;
	}
}
