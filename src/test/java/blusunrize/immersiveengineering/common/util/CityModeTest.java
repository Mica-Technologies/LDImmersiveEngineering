/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.common.Config.IEConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive truth table for {@link CityMode}.
 * <p>
 * The contract under test: a subsystem is simplified only when the master switch is on
 * <em>and</em> that subsystem has not been individually turned off; turning the master off
 * must always restore stock behaviour, whatever the per-subsystem flags say.
 * <p>
 * The config fields are plain public statics, so every test here mutates global state --
 * {@link #captureOriginals()} / {@link #restoreOriginals()} make sure nothing leaks into
 * another test class.
 */
class CityModeTest
{
	private boolean origMaster;
	private boolean origWires;
	private boolean origFloodlights;
	private boolean origGenerators;
	private boolean origMachines;
	private boolean origGrid;

	@BeforeEach
	void captureOriginals()
	{
		//Every assertion here is about the local config, so no server may be speaking over it.
		CityMode.clearServerOverride();
		origMaster = IEConfig.cityMode;
		origWires = IEConfig.cityModeWires;
		origFloodlights = IEConfig.cityModeFloodlights;
		origGenerators = IEConfig.cityModeGenerators;
		origMachines = IEConfig.cityModeMachines;
		origGrid = IEConfig.cityModeVirtualGrid;
	}

	@AfterEach
	void restoreOriginals()
	{
		CityMode.clearServerOverride();
		IEConfig.cityMode = origMaster;
		IEConfig.cityModeWires = origWires;
		IEConfig.cityModeFloodlights = origFloodlights;
		IEConfig.cityModeGenerators = origGenerators;
		IEConfig.cityModeMachines = origMachines;
		IEConfig.cityModeVirtualGrid = origGrid;
	}

	/**
	 * Sets the master switch and all five subsystem opt-outs in one go.
	 */
	private static void config(boolean master, boolean wires, boolean floodlights, boolean generators,
							   boolean machines, boolean grid)
	{
		IEConfig.cityMode = master;
		IEConfig.cityModeWires = wires;
		IEConfig.cityModeFloodlights = floodlights;
		IEConfig.cityModeGenerators = generators;
		IEConfig.cityModeMachines = machines;
		IEConfig.cityModeVirtualGrid = grid;
	}

	/**
	 * Sets every subsystem opt-out to the same value.
	 */
	private static void config(boolean master, boolean allSubsystems)
	{
		config(master, allSubsystems, allSubsystems, allSubsystems, allSubsystems, allSubsystems);
	}

	private static String describe()
	{
		return "master="+IEConfig.cityMode
				+" wires="+IEConfig.cityModeWires
				+" floodlights="+IEConfig.cityModeFloodlights
				+" generators="+IEConfig.cityModeGenerators
				+" machines="+IEConfig.cityModeMachines
				+" virtualGrid="+IEConfig.cityModeVirtualGrid
				+" petroleum="+IEConfig.cityModePetroleum
				+" pipes="+IEConfig.cityModePipes
				+" conduits="+IEConfig.cityModeConduits
				+" tanks="+IEConfig.cityModeTanks;
	}

	/**
	 * Asserts that no subsystem is simplified, whatever the per-subsystem flags are set to.
	 */
	private static void assertNothingSimplified()
	{
		assertFalse(CityMode.wires(), describe());
		assertFalse(CityMode.floodlights(), describe());
		assertFalse(CityMode.generators(), describe());
		assertFalse(CityMode.machines(), describe());
		assertFalse(CityMode.grid(), describe());
	}

	/**
	 * Asserts that every subsystem is simplified.
	 */
	private static void assertEverythingSimplified()
	{
		assertTrue(CityMode.wires(), describe());
		assertTrue(CityMode.floodlights(), describe());
		assertTrue(CityMode.generators(), describe());
		assertTrue(CityMode.machines(), describe());
		assertTrue(CityMode.grid(), describe());
	}

	// ---------------------------------------------------------------- defaults

	@Test
	@DisplayName("the shipped defaults are: master on, every subsystem opted in")
	void shippedDefaults()
	{
		// read from the freshly captured originals rather than the live fields, so this
		// still describes the shipped state even if a previous test misbehaved
		//
		// This fork ships city mode ON: it exists for a city pack, whose dedicated server runs it,
		// and a default of off is what made single-player worlds silently disagree with that
		// server. Flipping it back would reintroduce that bug for anyone without a config file.
		assertTrue(origMaster, "cityMode should ship enabled -- this is a city-pack fork");
		assertTrue(origWires, "cityModeWires should ship enabled (it only applies under the master)");
		assertTrue(origFloodlights, "cityModeFloodlights should ship enabled");
		assertTrue(origGenerators, "cityModeGenerators should ship enabled");
		assertTrue(origMachines, "cityModeMachines should ship enabled");
		assertTrue(origGrid, "cityModeVirtualGrid should ship enabled");
	}

	@Test
	@DisplayName("with the shipped defaults every subsystem is simplified")
	void shippedDefaultsSimplifyEverything()
	{
		config(true, true);
		assertTrue(CityMode.enabled());
		assertEverythingSimplified();
	}

	@Test
	@DisplayName("turning the master off in the config restores stock behaviour")
	void masterOffIsStock()
	{
		config(false, true);
		assertFalse(CityMode.enabled());
		assertNothingSimplified();
	}

	// ---------------------------------------------------------------- enabled()

	@Test
	@DisplayName("enabled() is true iff the master switch is on")
	void enabledFollowsMasterOnly()
	{
		config(true, true);
		assertTrue(CityMode.enabled());
		config(false, true);
		assertFalse(CityMode.enabled());
	}

	@Test
	@DisplayName("enabled() ignores every subsystem opt-out")
	void enabledIgnoresSubsystems()
	{
		config(true, false);
		assertTrue(CityMode.enabled(), "all subsystems opted out, but the master is still on");
	}

	@Test
	@DisplayName("enabled() stays false when only subsystems are on")
	void enabledFalseWithoutMaster()
	{
		config(false, true);
		assertFalse(CityMode.enabled());
	}

	@Test
	@DisplayName("enabled() is true even when a single subsystem is opted out")
	void enabledTrueWithOneSubsystemOff()
	{
		config(true, false, true, true, true, true);
		assertTrue(CityMode.enabled());
		config(true, true, false, true, true, true);
		assertTrue(CityMode.enabled());
		config(true, true, true, false, true, true);
		assertTrue(CityMode.enabled());
		config(true, true, true, true, false, true);
		assertTrue(CityMode.enabled());
		config(true, true, true, true, true, false);
		assertTrue(CityMode.enabled());
	}

	// ---------------------------------------------------------------- wires()

	@Test
	@DisplayName("wires(): master on + subsystem on -> simplified")
	void wiresMasterOnSubOn()
	{
		config(true, true, true, true, true, true);
		assertTrue(CityMode.wires());
	}

	@Test
	@DisplayName("wires(): master on + subsystem off -> stock")
	void wiresMasterOnSubOff()
	{
		config(true, false, true, true, true, true);
		assertFalse(CityMode.wires());
	}

	@Test
	@DisplayName("wires(): master off + subsystem on -> stock")
	void wiresMasterOffSubOn()
	{
		config(false, true, true, true, true, true);
		assertFalse(CityMode.wires());
	}

	@Test
	@DisplayName("wires(): master off + subsystem off -> stock")
	void wiresMasterOffSubOff()
	{
		config(false, false, true, true, true, true);
		assertFalse(CityMode.wires());
	}

	// ---------------------------------------------------------------- floodlights()

	@Test
	@DisplayName("floodlights(): master on + subsystem on -> simplified")
	void floodlightsMasterOnSubOn()
	{
		config(true, true, true, true, true, true);
		assertTrue(CityMode.floodlights());
	}

	@Test
	@DisplayName("floodlights(): master on + subsystem off -> stock")
	void floodlightsMasterOnSubOff()
	{
		config(true, true, false, true, true, true);
		assertFalse(CityMode.floodlights());
	}

	@Test
	@DisplayName("floodlights(): master off + subsystem on -> stock")
	void floodlightsMasterOffSubOn()
	{
		config(false, true, true, true, true, true);
		assertFalse(CityMode.floodlights());
	}

	@Test
	@DisplayName("floodlights(): master off + subsystem off -> stock")
	void floodlightsMasterOffSubOff()
	{
		config(false, true, false, true, true, true);
		assertFalse(CityMode.floodlights());
	}

	// ---------------------------------------------------------------- generators()

	@Test
	@DisplayName("generators(): master on + subsystem on -> simplified")
	void generatorsMasterOnSubOn()
	{
		config(true, true, true, true, true, true);
		assertTrue(CityMode.generators());
	}

	@Test
	@DisplayName("generators(): master on + subsystem off -> stock")
	void generatorsMasterOnSubOff()
	{
		config(true, true, true, false, true, true);
		assertFalse(CityMode.generators());
	}

	@Test
	@DisplayName("generators(): master off + subsystem on -> stock")
	void generatorsMasterOffSubOn()
	{
		config(false, true, true, true, true, true);
		assertFalse(CityMode.generators());
	}

	@Test
	@DisplayName("generators(): master off + subsystem off -> stock")
	void generatorsMasterOffSubOff()
	{
		config(false, true, true, false, true, true);
		assertFalse(CityMode.generators());
	}

	// ---------------------------------------------------------------- machines()

	@Test
	@DisplayName("machines(): master on + subsystem on -> simplified")
	void machinesMasterOnSubOn()
	{
		config(true, true, true, true, true, true);
		assertTrue(CityMode.machines());
	}

	@Test
	@DisplayName("machines(): master on + subsystem off -> stock")
	void machinesMasterOnSubOff()
	{
		config(true, true, true, true, false, true);
		assertFalse(CityMode.machines());
	}

	@Test
	@DisplayName("machines(): master off + subsystem on -> stock")
	void machinesMasterOffSubOn()
	{
		config(false, true, true, true, true, true);
		assertFalse(CityMode.machines());
	}

	@Test
	@DisplayName("machines(): master off + subsystem off -> stock")
	void machinesMasterOffSubOff()
	{
		config(false, true, true, true, false, true);
		assertFalse(CityMode.machines());
	}

	// ---------------------------------------------------------------- grid()

	@Test
	@DisplayName("grid(): master on + subsystem on -> simplified")
	void gridMasterOnSubOn()
	{
		config(true, true, true, true, true, true);
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("grid(): master on + subsystem off -> stock")
	void gridMasterOnSubOff()
	{
		config(true, true, true, true, true, false);
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("grid(): master off + subsystem on -> stock")
	void gridMasterOffSubOn()
	{
		config(false, true, true, true, true, true);
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("grid(): master off + subsystem off -> stock")
	void gridMasterOffSubOff()
	{
		config(false, true, true, true, true, false);
		assertFalse(CityMode.grid());
	}

	// ---------------------------------------------------------------- conduits()

	@Test
	@DisplayName("conduits(): master on + subsystem on -> simplified")
	void conduitsMasterOnSubOn()
	{
		IEConfig.cityMode = true;
		IEConfig.cityModeConduits = true;
		assertTrue(CityMode.conduits());
	}

	@Test
	@DisplayName("conduits(): master on + subsystem off -> stock")
	void conduitsMasterOnSubOff()
	{
		IEConfig.cityMode = true;
		IEConfig.cityModeConduits = false;
		assertFalse(CityMode.conduits());
	}

	@Test
	@DisplayName("conduits(): the master switch alone restores stock behaviour")
	void conduitsMasterOff()
	{
		//The property the whole class exists for: turning the master off is always sufficient,
		//whatever the per-subsystem flags say.
		IEConfig.cityMode = false;
		IEConfig.cityModeConduits = true;
		assertFalse(CityMode.conduits());
	}

	// ---------------------------------------------------------------- tanks()

	@Test
	@DisplayName("tanks(): master on + subsystem on -> simplified")
	void tanksMasterOnSubOn()
	{
		IEConfig.cityMode = true;
		IEConfig.cityModeTanks = true;
		assertTrue(CityMode.tanks());
	}

	@Test
	@DisplayName("tanks(): master on + subsystem off -> stock")
	void tanksMasterOnSubOff()
	{
		IEConfig.cityMode = true;
		IEConfig.cityModeTanks = false;
		assertFalse(CityMode.tanks());
	}

	@Test
	@DisplayName("tanks(): the master switch alone restores stock behaviour")
	void tanksMasterOff()
	{
		IEConfig.cityMode = false;
		IEConfig.cityModeTanks = true;
		assertFalse(CityMode.tanks());
	}

	@Test
	@DisplayName("every tank in the mod goes through CityModeTank")
	void everyTankIsCityModeAware()
	{
		//A tank left as a plain FluidTank is one the master switch does not reach, and the only
		//symptom is a depot that quietly drains in a world where nothing else does.
		for(String path : new String[]{
				"src/main/java/blusunrize/immersiveengineering/common/blocks/petroleum/TileEntityBuriedTank.java",
				"src/main/java/blusunrize/immersiveengineering/common/blocks/metal/TileEntitySheetmetalTank.java"})
		{
			String source;
			try
			{
				//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files CRLF,
				//and every pattern below is written with the bare newline the repository stores.
				source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
						java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
			} catch(java.io.IOException e)
			{
				throw new AssertionError("could not read "+path, e);
			}
			assertTrue(source.contains("new CityModeTank("),
					path+" builds a plain FluidTank, so city mode cannot reach it");
			assertFalse(source.contains("new FluidTank("),
					path+" still has a plain FluidTank alongside the city-mode one");
		}
	}

	// ---------------------------------------------------------------- master dominance

	@Test
	@DisplayName("master off forces every accessor false across all 32 subsystem combinations")
	void masterOffForcesEverythingFalse()
	{
		for(int mask = 0; mask < 32; mask++)
		{
			config(false, (mask&1)!=0, (mask&2)!=0, (mask&4)!=0, (mask&8)!=0, (mask&16)!=0);
			assertFalse(CityMode.enabled(), describe());
			assertNothingSimplified();
		}
	}

	@Test
	@DisplayName("master on: each accessor mirrors exactly its own subsystem flag, across all 32 combinations")
	void masterOnMirrorsEachSubsystem()
	{
		for(int mask = 0; mask < 32; mask++)
		{
			boolean w = (mask&1)!=0;
			boolean f = (mask&2)!=0;
			boolean g = (mask&4)!=0;
			boolean m = (mask&8)!=0;
			boolean v = (mask&16)!=0;
			config(true, w, f, g, m, v);
			assertTrue(CityMode.enabled(), describe());
			assertEquals(w, CityMode.wires(), describe());
			assertEquals(f, CityMode.floodlights(), describe());
			assertEquals(g, CityMode.generators(), describe());
			assertEquals(m, CityMode.machines(), describe());
			assertEquals(v, CityMode.grid(), describe());
		}
	}

	@Test
	@DisplayName("flipping the master off from a fully-on state restores stock behaviour")
	void masterOffRestoresStock()
	{
		config(true, true);
		assertEverythingSimplified();
		IEConfig.cityMode = false;
		assertNothingSimplified();
	}

	@Test
	@DisplayName("flipping the master on from a fully-on subsystem state simplifies everything")
	void masterOnSimplifiesEverything()
	{
		config(false, true);
		assertNothingSimplified();
		IEConfig.cityMode = true;
		assertEverythingSimplified();
	}

	@Test
	@DisplayName("master on with every subsystem opted out simplifies nothing but still reports enabled")
	void masterOnAllSubsystemsOff()
	{
		config(true, false);
		assertTrue(CityMode.enabled());
		assertNothingSimplified();
	}

	// ---------------------------------------------------------------- independence

	@Test
	@DisplayName("opting out of wires leaves the other subsystems simplified")
	void wiresOptOutIsIsolated()
	{
		config(true, false, true, true, true, true);
		assertFalse(CityMode.wires());
		assertTrue(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertTrue(CityMode.machines());
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("opting out of floodlights leaves the other subsystems simplified")
	void floodlightsOptOutIsIsolated()
	{
		config(true, true, false, true, true, true);
		assertTrue(CityMode.wires());
		assertFalse(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertTrue(CityMode.machines());
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("opting out of generators leaves the other subsystems simplified")
	void generatorsOptOutIsIsolated()
	{
		config(true, true, true, false, true, true);
		assertTrue(CityMode.wires());
		assertTrue(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertTrue(CityMode.machines());
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("opting out of machines leaves the other subsystems simplified")
	void machinesOptOutIsIsolated()
	{
		config(true, true, true, true, false, true);
		assertTrue(CityMode.wires());
		assertTrue(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertFalse(CityMode.machines());
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("opting out of the virtual grid leaves the other subsystems simplified")
	void gridOptOutIsIsolated()
	{
		config(true, true, true, true, true, false);
		assertTrue(CityMode.wires());
		assertTrue(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertTrue(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("wires and the virtual grid are independent, despite being the same trade")
	void wiresAndGridAreIndependent()
	{
		config(true, true, true, true, true, false);
		assertTrue(CityMode.wires());
		assertFalse(CityMode.grid());
		config(true, false, true, true, true, true);
		assertFalse(CityMode.wires());
		assertTrue(CityMode.grid());
	}

	@Test
	@DisplayName("only one subsystem left on simplifies only that subsystem")
	void onlyWiresOn()
	{
		config(true, true, false, false, false, false);
		assertTrue(CityMode.wires());
		assertFalse(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertFalse(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("only floodlights left on simplifies only floodlights")
	void onlyFloodlightsOn()
	{
		config(true, false, true, false, false, false);
		assertFalse(CityMode.wires());
		assertTrue(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertFalse(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("only generators left on simplifies only generators")
	void onlyGeneratorsOn()
	{
		config(true, false, false, true, false, false);
		assertFalse(CityMode.wires());
		assertFalse(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertFalse(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("only machines left on simplifies only machines")
	void onlyMachinesOn()
	{
		config(true, false, false, false, true, false);
		assertFalse(CityMode.wires());
		assertFalse(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertTrue(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	@Test
	@DisplayName("only the virtual grid left on simplifies only the grid")
	void onlyGridOn()
	{
		config(true, false, false, false, false, true);
		assertFalse(CityMode.wires());
		assertFalse(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertFalse(CityMode.machines());
		assertTrue(CityMode.grid());
	}

	// ---------------------------------------------------------------- misc

	@Test
	@DisplayName("the accessors are pure -- repeated calls do not change the config")
	void accessorsAreSideEffectFree()
	{
		config(true, true, false, true, false, true);
		for(int i = 0; i < 5; i++)
		{
			CityMode.enabled();
			CityMode.wires();
			CityMode.floodlights();
			CityMode.generators();
			CityMode.machines();
			CityMode.grid();
		}
		assertTrue(IEConfig.cityMode);
		assertTrue(IEConfig.cityModeWires);
		assertFalse(IEConfig.cityModeFloodlights);
		assertTrue(IEConfig.cityModeGenerators);
		assertFalse(IEConfig.cityModeMachines);
		assertTrue(IEConfig.cityModeVirtualGrid);
	}

	@Test
	@DisplayName("CityMode is a non-instantiable utility class")
	void utilityClassShape()
	{
		assertTrue(java.lang.reflect.Modifier.isFinal(CityMode.class.getModifiers()), "CityMode should be final");
		assertEquals(0, CityMode.class.getConstructors().length, "CityMode should expose no public constructor");
	}

	@Test
	@DisplayName("every accessor is a public static method returning boolean")
	void accessorShapes() throws Exception
	{
		for(String name : new String[]{"enabled", "wires", "floodlights", "generators", "machines", "grid"})
		{
			java.lang.reflect.Method m = CityMode.class.getMethod(name);
			assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()), name+"() should be static");
			assertEquals(boolean.class, m.getReturnType(), name+"() should return boolean");
		}
	}

	/**
	 * The truth table again, but written out one row per test so a regression names the exact
	 * row that broke rather than an index into a loop. The five digits after the name are the
	 * subsystem flags in order: wires, floodlights, generators, machines, virtualGrid.
	 */
	@Nested
	@DisplayName("full 2^6 truth table, row by row")
	class FullTruthTable
	{
		private void row(boolean master, boolean w, boolean f, boolean g, boolean m, boolean v)
		{
			config(master, w, f, g, m, v);
			assertEquals(master, CityMode.enabled(), describe());
			assertEquals(master&&w, CityMode.wires(), describe());
			assertEquals(master&&f, CityMode.floodlights(), describe());
			assertEquals(master&&g, CityMode.generators(), describe());
			assertEquals(master&&m, CityMode.machines(), describe());
			assertEquals(master&&v, CityMode.grid(), describe());
		}

		@Test
		void masterOff_00000()
		{
			row(false, false, false, false, false, false);
		}

		@Test
		void masterOff_10000()
		{
			row(false, true, false, false, false, false);
		}

		@Test
		void masterOff_01000()
		{
			row(false, false, true, false, false, false);
		}

		@Test
		void masterOff_11000()
		{
			row(false, true, true, false, false, false);
		}

		@Test
		void masterOff_00100()
		{
			row(false, false, false, true, false, false);
		}

		@Test
		void masterOff_10100()
		{
			row(false, true, false, true, false, false);
		}

		@Test
		void masterOff_01100()
		{
			row(false, false, true, true, false, false);
		}

		@Test
		void masterOff_11100()
		{
			row(false, true, true, true, false, false);
		}

		@Test
		void masterOff_00010()
		{
			row(false, false, false, false, true, false);
		}

		@Test
		void masterOff_10010()
		{
			row(false, true, false, false, true, false);
		}

		@Test
		void masterOff_01010()
		{
			row(false, false, true, false, true, false);
		}

		@Test
		void masterOff_11010()
		{
			row(false, true, true, false, true, false);
		}

		@Test
		void masterOff_00110()
		{
			row(false, false, false, true, true, false);
		}

		@Test
		void masterOff_10110()
		{
			row(false, true, false, true, true, false);
		}

		@Test
		void masterOff_01110()
		{
			row(false, false, true, true, true, false);
		}

		@Test
		void masterOff_11110()
		{
			row(false, true, true, true, true, false);
		}

		@Test
		void masterOff_00001()
		{
			row(false, false, false, false, false, true);
		}

		@Test
		void masterOff_10001()
		{
			row(false, true, false, false, false, true);
		}

		@Test
		void masterOff_01001()
		{
			row(false, false, true, false, false, true);
		}

		@Test
		void masterOff_11001()
		{
			row(false, true, true, false, false, true);
		}

		@Test
		void masterOff_00101()
		{
			row(false, false, false, true, false, true);
		}

		@Test
		void masterOff_10101()
		{
			row(false, true, false, true, false, true);
		}

		@Test
		void masterOff_01101()
		{
			row(false, false, true, true, false, true);
		}

		@Test
		void masterOff_11101()
		{
			row(false, true, true, true, false, true);
		}

		@Test
		void masterOff_00011()
		{
			row(false, false, false, false, true, true);
		}

		@Test
		void masterOff_10011()
		{
			row(false, true, false, false, true, true);
		}

		@Test
		void masterOff_01011()
		{
			row(false, false, true, false, true, true);
		}

		@Test
		void masterOff_11011()
		{
			row(false, true, true, false, true, true);
		}

		@Test
		void masterOff_00111()
		{
			row(false, false, false, true, true, true);
		}

		@Test
		void masterOff_10111()
		{
			row(false, true, false, true, true, true);
		}

		@Test
		void masterOff_01111()
		{
			row(false, false, true, true, true, true);
		}

		@Test
		void masterOff_11111()
		{
			row(false, true, true, true, true, true);
		}

		@Test
		void masterOn_00000()
		{
			row(true, false, false, false, false, false);
		}

		@Test
		void masterOn_10000()
		{
			row(true, true, false, false, false, false);
		}

		@Test
		void masterOn_01000()
		{
			row(true, false, true, false, false, false);
		}

		@Test
		void masterOn_11000()
		{
			row(true, true, true, false, false, false);
		}

		@Test
		void masterOn_00100()
		{
			row(true, false, false, true, false, false);
		}

		@Test
		void masterOn_10100()
		{
			row(true, true, false, true, false, false);
		}

		@Test
		void masterOn_01100()
		{
			row(true, false, true, true, false, false);
		}

		@Test
		void masterOn_11100()
		{
			row(true, true, true, true, false, false);
		}

		@Test
		void masterOn_00010()
		{
			row(true, false, false, false, true, false);
		}

		@Test
		void masterOn_10010()
		{
			row(true, true, false, false, true, false);
		}

		@Test
		void masterOn_01010()
		{
			row(true, false, true, false, true, false);
		}

		@Test
		void masterOn_11010()
		{
			row(true, true, true, false, true, false);
		}

		@Test
		void masterOn_00110()
		{
			row(true, false, false, true, true, false);
		}

		@Test
		void masterOn_10110()
		{
			row(true, true, false, true, true, false);
		}

		@Test
		void masterOn_01110()
		{
			row(true, false, true, true, true, false);
		}

		@Test
		void masterOn_11110()
		{
			row(true, true, true, true, true, false);
		}

		@Test
		void masterOn_00001()
		{
			row(true, false, false, false, false, true);
		}

		@Test
		void masterOn_10001()
		{
			row(true, true, false, false, false, true);
		}

		@Test
		void masterOn_01001()
		{
			row(true, false, true, false, false, true);
		}

		@Test
		void masterOn_11001()
		{
			row(true, true, true, false, false, true);
		}

		@Test
		void masterOn_00101()
		{
			row(true, false, false, true, false, true);
		}

		@Test
		void masterOn_10101()
		{
			row(true, true, false, true, false, true);
		}

		@Test
		void masterOn_01101()
		{
			row(true, false, true, true, false, true);
		}

		@Test
		void masterOn_11101()
		{
			row(true, true, true, true, false, true);
		}

		@Test
		void masterOn_00011()
		{
			row(true, false, false, false, true, true);
		}

		@Test
		void masterOn_10011()
		{
			row(true, true, false, false, true, true);
		}

		@Test
		void masterOn_01011()
		{
			row(true, false, true, false, true, true);
		}

		@Test
		void masterOn_11011()
		{
			row(true, true, true, false, true, true);
		}

		@Test
		void masterOn_00111()
		{
			row(true, false, false, true, true, true);
		}

		@Test
		void masterOn_10111()
		{
			row(true, true, false, true, true, true);
		}

		@Test
		void masterOn_01111()
		{
			row(true, false, true, true, true, true);
		}

		@Test
		void masterOn_11111()
		{
			row(true, true, true, true, true, true);
		}
	}
}
