/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.compat.mts;

import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.common.util.compat.IECompatModule;
import net.minecraftforge.fluids.Fluid;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immersive Vehicles (Minecraft Transport Simulator, modid {@code mts}): teaches its engines and
 * fuel pumps to accept this fork's fuels.
 * <p>
 * <b>The problem.</b> MTS decides what an engine will burn from
 * {@code ConfigSystem.settings.fuel.fuels}, a {@code Map<fuelType, Map<fluidRegistryName, potency>>}
 * that {@code PackParser.parsePacks} seeds during MTS's own pre-init from a hardcoded default table
 * -- and only for fuel types not already in the file. Its gasoline entry is {@code lava},
 * {@code gasoline}, {@code ethanol}; its diesel entry {@code lava}, {@code diesel},
 * {@code biodiesel}, {@code creosote}, {@code oil}. All bare names. This fork registers its
 * distillation cuts as {@code ie_gasoline}, {@code ie_diesel} and so on, precisely so it does not
 * inherit another mod's fluid, which means MTS has never heard of any of them.
 * {@code AEntityVehicleE_Powered.checkFuelTankCompatibility} looks the tank's fluid up in that map,
 * finds nothing, and returns {@code INVALID} -- the pump rejecting the fuel on contact, which is the
 * bug as reported.
 * <p>
 * <b>The fix.</b> Fold the fork's fluids into that live map, once, at post-init. Post-init is late
 * enough that MTS's pre-init has certainly run and the map exists and is populated, and early enough
 * that no vehicle has been ticked. Nothing is written to {@code mtsconfig.json}: MTS saves its config
 * during its own init, before this runs, so the injection is re-applied from scratch every launch and
 * leaves no trace in a file the player may have hand-edited. It is applied identically on both
 * logical sides, which matters -- MTS throws outright if a client and server disagree about a fuel.
 * <p>
 * <b>What is not offered.</b> Crude oil, heavy fuel oil, lubricant, bitumen, asphalt, sour gas,
 * natural gas, steam and concrete are all left out. Sour gas and steam are not fuels at all; crude
 * is deliberately awful even in the machines that will take it; and heavy fuel oil, lubricant,
 * bitumen and natural gas each exist to give one fork machine a reason to be built -- the Industrial
 * Burner, the Lubrication Manifold, the asphalt line, the Gas Turbine. Handing them to a car would
 * dissolve the choice each of those blocks poses.
 * <p>
 * No MTS class is imported or named at compile time; the whole conversation is reflective, and the
 * module is only instantiated at all when {@code Loader.isModLoaded("mts")} and the {@code compat}
 * config entry for {@code mts} is true. With MTS absent this file does nothing and is never loaded.
 *
 * @see MTSFuelTable
 */
public class MTSHelper extends IECompatModule
{
	private static final String CONFIG_SYSTEM = "minecrafttransportsimulator.systems.ConfigSystem";
	/**
	 * The static field on {@code ConfigSystem} holding the parsed settings. {@code settings} is what
	 * every version that ever shipped for 1.12.2 calls it; {@code configObject} is the older name,
	 * kept as a fallback because guessing wrong here is the difference between working and silently
	 * not.
	 */
	private static final String[] SETTINGS_FIELDS = {"settings", "configObject"};

	@Override
	public void preInit()
	{
	}

	@Override
	public void registerRecipes()
	{
	}

	@Override
	public void init()
	{
	}

	@Override
	public void postInit()
	{
		Map<String, Map<String, Double>> fuels = findFuelMap();
		if(fuels==null)
			return;
		if(fuels.isEmpty())
		{
			IELogger.warn("Immersive Vehicles compat: its fuel table is empty, so no engine fuel types were found to extend. No fuels were added.");
			return;
		}

		Map<String, String> ourFluids = ourFluidNames();
		Map<String, Map<String, Double>> injection = MTSFuelTable.injectionFor(new ArrayList<>(fuels.keySet()), ourFluids);
		Map<String, Map<String, Double>> added = MTSFuelTable.merge(fuels, injection);

		if(added.isEmpty())
		{
			IELogger.info("Immersive Vehicles compat: nothing to add. Its fuel types are "+fuels.keySet()+", and every fuel this fork offers them was already listed.");
			return;
		}
		for(Map.Entry<String, Map<String, Double>> entry : added.entrySet())
			IELogger.info("Immersive Vehicles compat: fuel type '"+entry.getKey()+"' now also accepts "+entry.getValue());
		IELogger.info("Immersive Vehicles compat: extended "+added.size()+" of its "+fuels.size()+" fuel types ("+fuels.keySet()+"). Fuel pumps and jerrycans should now take these fluids.");
	}

	/**
	 * The fork's fuel fluids, by table token, under the registry names they actually ended up with.
	 * <p>
	 * Read off the live {@link Fluid} objects rather than hardcoded, because {@code IEContent
	 * .setupFluid} yields to whoever registered a name first: on a pack where another mod owns
	 * {@code ethanol} or {@code creosote}, {@code IEContent.fluidEthanol} is that mod's fluid, and its
	 * name is the one MTS will see in the tank.
	 */
	private static Map<String, String> ourFluidNames()
	{
		Map<String, String> names = new LinkedHashMap<>();
		put(names, MTSFuelTable.GASOLINE, IEContent.fluidGasoline);
		put(names, MTSFuelTable.DIESEL, IEContent.fluidDiesel);
		put(names, MTSFuelTable.NAPHTHA, IEContent.fluidNaphtha);
		put(names, MTSFuelTable.ETHANOL, IEContent.fluidEthanol);
		put(names, MTSFuelTable.BIODIESEL, IEContent.fluidBiodiesel);
		put(names, MTSFuelTable.CREOSOTE, IEContent.fluidCreosote);
		put(names, MTSFuelTable.PROPANE, IEContent.fluidPropane);
		put(names, MTSFuelTable.PLANTOIL, IEContent.fluidPlantoil);
		return names;
	}

	private static void put(Map<String, String> names, String token, Fluid fluid)
	{
		if(fluid!=null&&fluid.getName()!=null)
			names.put(token, fluid.getName());
	}

	/**
	 * Walks {@code ConfigSystem.settings.fuel.fuels} by reflection. Every failure along the way is
	 * logged with the step that failed, because the only way anyone finds out this integration went
	 * wrong is by reading the log.
	 *
	 * @return the live, mutable map, or null if MTS's config could not be reached
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Double>> findFuelMap()
	{
		try
		{
			Class<?> configSystem = Class.forName(CONFIG_SYSTEM);

			Object settings = null;
			for(String fieldName : SETTINGS_FIELDS)
				try
				{
					settings = configSystem.getField(fieldName).get(null);
					if(settings!=null)
						break;
				} catch(NoSuchFieldException ignored)
				{
				}
			if(settings==null)
			{
				IELogger.warn("Immersive Vehicles compat: found "+CONFIG_SYSTEM+" but none of its settings fields were readable or populated. This fork's fuels will not work in vehicles.");
				return null;
			}

			Object fuelConfig = settings.getClass().getField("fuel").get(settings);
			if(fuelConfig==null)
			{
				IELogger.warn("Immersive Vehicles compat: its settings object has no populated 'fuel' section. This fork's fuels will not work in vehicles.");
				return null;
			}

			Field fuelsField = fuelConfig.getClass().getField("fuels");
			Object fuels = fuelsField.get(fuelConfig);
			if(fuels==null)
			{
				//MTS populates this during its own pre-init; null here means it never got that far.
				IELogger.warn("Immersive Vehicles compat: its fuel table had not been built by the time this ran. This fork's fuels will not work in vehicles.");
				return null;
			}
			if(!(fuels instanceof Map))
			{
				IELogger.warn("Immersive Vehicles compat: its fuel table is a "+fuels.getClass().getName()+", not a Map. This is a version of the mod this integration does not understand; no fuels were added.");
				return null;
			}
			return (Map<String, Map<String, Double>>)fuels;
		} catch(ClassNotFoundException e)
		{
			IELogger.warn("Immersive Vehicles compat: '"+CONFIG_SYSTEM+"' is missing, so this is not a version of the mod this integration understands. No fuels were added.");
			return null;
		} catch(Exception e)
		{
			IELogger.logger.warn("Immersive Vehicles compat: could not read its fuel table, so this fork's fuels will not work in vehicles.", e);
			return null;
		}
	}
}
