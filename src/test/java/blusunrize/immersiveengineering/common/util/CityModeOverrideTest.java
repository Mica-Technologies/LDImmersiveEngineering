/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.common.Config.IEConfig;
import blusunrize.immersiveengineering.common.util.CityMode.Flags;
import blusunrize.immersiveengineering.common.util.CityMode.Subsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server-override half of {@link CityMode}: which set of flags wins, and how a set of flags
 * resolves to a per-subsystem answer.
 * <p>
 * The bug this guards against is the one that produced it. City mode's machine rules are split
 * across the two sides -- the server runs the buffer and the scan throttle, the client decides
 * whether the machine animates -- and the config is per-installation, so on a pack that ships no
 * config file a city-mode server and its stock-default clients each did half the job. Nothing
 * here needs Minecraft: {@link Flags#simplifies} is a pure function and the override is a single
 * reference, so the whole resolution rule is testable flat.
 */
class CityModeOverrideTest
{
	private boolean origMaster;
	private boolean origMachines;
	private boolean origWires;

	@BeforeEach
	void setUp()
	{
		origMaster = IEConfig.cityMode;
		origMachines = IEConfig.cityModeMachines;
		origWires = IEConfig.cityModeWires;
		CityMode.clearServerOverride();
	}

	@AfterEach
	void tearDown()
	{
		CityMode.clearServerOverride();
		IEConfig.cityMode = origMaster;
		IEConfig.cityModeMachines = origMachines;
		IEConfig.cityModeWires = origWires;
	}

	private static int maskOf(Subsystem... subsystems)
	{
		int mask = 0;
		for(Subsystem s : subsystems)
			mask |= s.bit();
		return mask;
	}

	private static int allSubsystems()
	{
		return maskOf(Subsystem.VALUES);
	}

	// ---------------------------------------------------------------- Flags as a pure function

	@Test
	@DisplayName("Flags.simplifies() is the master AND the subsystem bit, for every subsystem")
	void flagsTruthTable()
	{
		for(Subsystem s : Subsystem.VALUES)
			for(boolean master : new boolean[]{false, true})
				for(boolean sub : new boolean[]{false, true})
				{
					Flags f = new Flags(master, sub?s.bit(): 0);
					assertEquals(master&&sub, f.simplifies(s), "master="+master+" "+s+"="+sub);
					assertEquals(sub, f.subsystemEnabled(s), "subsystemEnabled should ignore the master");
					assertEquals(master, f.master());
				}
	}

	@Test
	@DisplayName("Flags.STOCK simplifies nothing")
	void stockSimplifiesNothing()
	{
		assertFalse(Flags.STOCK.master());
		for(Subsystem s : Subsystem.VALUES)
			assertFalse(Flags.STOCK.simplifies(s), s.name());
	}

	@Test
	@DisplayName("a subsystem's bit is unique to it")
	void subsystemBitsAreDistinct()
	{
		int seen = 0;
		for(Subsystem s : Subsystem.VALUES)
		{
			assertEquals(0, seen&s.bit(), s+" shares a bit with an earlier subsystem");
			seen |= s.bit();
		}
	}

	@Test
	@DisplayName("Flags round-trip through master + mask, which is the wire format")
	void flagsRoundTrip()
	{
		//The packet carries exactly these two values and nothing else, so if this holds the
		//packet cannot lose a subsystem.
		for(int mask = 0; mask < (1<<Subsystem.VALUES.length); mask++)
			for(boolean master : new boolean[]{false, true})
			{
				Flags original = new Flags(master, mask);
				Flags rebuilt = new Flags(original.master(), original.subsystemMask());
				assertEquals(original, rebuilt);
				assertEquals(original.hashCode(), rebuilt.hashCode());
				for(Subsystem s : Subsystem.VALUES)
					assertEquals(original.simplifies(s), rebuilt.simplifies(s), s.name());
			}
	}

	// ---------------------------------------------------------------- override wins over config

	@Test
	@DisplayName("with no override, the accessors read the local config")
	void noOverrideReadsConfig()
	{
		assertFalse(CityMode.hasServerOverride());
		IEConfig.cityMode = true;
		IEConfig.cityModeMachines = true;
		assertTrue(CityMode.machines());
		IEConfig.cityMode = false;
		assertFalse(CityMode.machines());
	}

	@Test
	@DisplayName("a city-mode server overrides a stock-default client -- the bug this exists for")
	void serverOnClientOffGivesOn()
	{
		//The reported symptom: the pack ships no config, so the client defaults apply and its
		//single-player world -- and its view of the server's machines -- behaves like stock.
		IEConfig.cityMode = false;
		IEConfig.cityModeMachines = true;
		assertFalse(CityMode.machines(), "precondition: the local config says stock");

		CityMode.applyServerOverride(new Flags(true, allSubsystems()));
		assertTrue(CityMode.hasServerOverride());
		assertTrue(CityMode.enabled());
		assertTrue(CityMode.machines(), "the server runs city mode, so the client must too");
	}

	@Test
	@DisplayName("a stock server overrides a city-mode client")
	void serverOffClientOnGivesOff()
	{
		//The override has to win in both directions, or a client with city mode configured would
		//animate machines a stock server is not running.
		IEConfig.cityMode = true;
		IEConfig.cityModeMachines = true;
		assertTrue(CityMode.machines(), "precondition: the local config says city mode");

		CityMode.applyServerOverride(Flags.STOCK);
		assertFalse(CityMode.enabled());
		assertFalse(CityMode.machines());
	}

	@Test
	@DisplayName("the override carries per-subsystem opt-outs, not just the master")
	void overrideCarriesSubsystems()
	{
		IEConfig.cityMode = false;
		//A server running city mode with only machines turned back off.
		int mask = allSubsystems()&~Subsystem.MACHINES.bit();
		CityMode.applyServerOverride(new Flags(true, mask));

		assertTrue(CityMode.enabled());
		assertFalse(CityMode.machines(), "the server opted machines out");
		assertTrue(CityMode.wires());
		assertTrue(CityMode.grid());
		assertTrue(CityMode.petroleum());
	}

	@Test
	@DisplayName("every accessor honours the override, none is left reading the config")
	void everyAccessorHonoursTheOverride()
	{
		//A missed accessor is invisible until the one subsystem it guards misbehaves on a server,
		//so check all nine against both directions of disagreement.
		IEConfig.cityMode = false;
		CityMode.applyServerOverride(new Flags(true, allSubsystems()));
		assertTrue(CityMode.wires());
		assertTrue(CityMode.pipes());
		assertTrue(CityMode.conduits());
		assertTrue(CityMode.tanks());
		assertTrue(CityMode.floodlights());
		assertTrue(CityMode.generators());
		assertTrue(CityMode.machines());
		assertTrue(CityMode.grid());
		assertTrue(CityMode.petroleum());

		IEConfig.cityMode = true;
		CityMode.applyServerOverride(Flags.STOCK);
		assertFalse(CityMode.wires());
		assertFalse(CityMode.pipes());
		assertFalse(CityMode.conduits());
		assertFalse(CityMode.tanks());
		assertFalse(CityMode.floodlights());
		assertFalse(CityMode.generators());
		assertFalse(CityMode.machines());
		assertFalse(CityMode.grid());
		assertFalse(CityMode.petroleum());
	}

	@Test
	@DisplayName("an override with the master off wins even over every subsystem bit set")
	void overrideMasterDominates()
	{
		IEConfig.cityMode = true;
		CityMode.applyServerOverride(new Flags(false, allSubsystems()));
		assertFalse(CityMode.enabled());
		for(Subsystem s : Subsystem.VALUES)
			assertFalse(new Flags(false, allSubsystems()).simplifies(s), s.name());
		assertFalse(CityMode.machines());
	}

	// ---------------------------------------------------------------- clearing it again

	@Test
	@DisplayName("clearing the override returns control to the local config")
	void clearingRestoresConfig()
	{
		//Leaving a city server and opening your own world must not carry that server's rules over.
		IEConfig.cityMode = false;
		CityMode.applyServerOverride(new Flags(true, allSubsystems()));
		assertTrue(CityMode.machines());

		CityMode.clearServerOverride();
		assertFalse(CityMode.hasServerOverride());
		assertFalse(CityMode.machines());
	}

	@Test
	@DisplayName("clearing an override that was never set is harmless")
	void clearingTwiceIsHarmless()
	{
		CityMode.clearServerOverride();
		CityMode.clearServerOverride();
		assertFalse(CityMode.hasServerOverride());
	}

	@Test
	@DisplayName("a later override replaces the earlier one outright")
	void overrideReplacesRatherThanMerges()
	{
		//The config-reload re-push sends a whole new snapshot; nothing may survive from the last.
		CityMode.applyServerOverride(new Flags(true, allSubsystems()));
		CityMode.applyServerOverride(new Flags(true, Subsystem.WIRES.bit()));
		assertTrue(CityMode.wires());
		assertFalse(CityMode.machines());
		assertFalse(CityMode.grid());
	}

	// ---------------------------------------------------------------- snapshots

	@Test
	@DisplayName("fromConfig() mirrors the config and ignores any override")
	void fromConfigIgnoresOverride()
	{
		IEConfig.cityMode = false;
		IEConfig.cityModeMachines = true;
		CityMode.applyServerOverride(new Flags(true, allSubsystems()));

		Flags local = CityMode.fromConfig();
		assertFalse(local.master(), "fromConfig() is what a server sends -- it must be the config");
		assertTrue(local.subsystemEnabled(Subsystem.MACHINES));
		assertFalse(local.simplifies(Subsystem.MACHINES));
	}

	@Test
	@DisplayName("fromConfig() picks up every subsystem flag individually")
	void fromConfigCoversEverySubsystem()
	{
		IEConfig.cityMode = true;
		IEConfig.cityModeMachines = false;
		IEConfig.cityModeWires = true;
		Flags local = CityMode.fromConfig();
		assertFalse(local.subsystemEnabled(Subsystem.MACHINES));
		assertTrue(local.subsystemEnabled(Subsystem.WIRES));
		//and the accessors agree with the snapshot they were built from
		assertEquals(local.simplifies(Subsystem.MACHINES), CityMode.machines());
		assertEquals(local.simplifies(Subsystem.WIRES), CityMode.wires());
	}

	@Test
	@DisplayName("effective() returns the override when there is one and the config when there is not")
	void effectiveFollowsTheOverride()
	{
		IEConfig.cityMode = false;
		assertEquals(CityMode.fromConfig(), CityMode.effective());

		Flags fromServer = new Flags(true, allSubsystems());
		CityMode.applyServerOverride(fromServer);
		assertEquals(fromServer, CityMode.effective());

		CityMode.clearServerOverride();
		assertEquals(CityMode.fromConfig(), CityMode.effective());
	}

	@Test
	@DisplayName("effective() agrees with every accessor, whichever source is in force")
	void effectiveAgreesWithAccessors()
	{
		for(boolean overridden : new boolean[]{false, true})
		{
			IEConfig.cityMode = true;
			IEConfig.cityModeMachines = false;
			if(overridden)
				CityMode.applyServerOverride(new Flags(true, Subsystem.MACHINES.bit()));
			else
				CityMode.clearServerOverride();

			Flags eff = CityMode.effective();
			assertEquals(eff.master(), CityMode.enabled(), "overridden="+overridden);
			assertEquals(eff.simplifies(Subsystem.MACHINES), CityMode.machines(), "overridden="+overridden);
			assertEquals(eff.simplifies(Subsystem.WIRES), CityMode.wires(), "overridden="+overridden);
		}
	}

	// ---------------------------------------------------------------- single player

	@Test
	@DisplayName("in single player the override is a copy of the config, so it changes nothing")
	void singlePlayerOverrideIsANoOp()
	{
		//The integrated server sends the packet to its one player just as a dedicated server does.
		//Both sides read the same statics, so the override installed is the config it was built
		//from and every accessor must answer exactly as it did before the packet arrived.
		for(boolean master : new boolean[]{false, true})
		{
			IEConfig.cityMode = master;
			IEConfig.cityModeMachines = true;
			IEConfig.cityModeWires = false;
			CityMode.clearServerOverride();
			boolean machinesBefore = CityMode.machines();
			boolean wiresBefore = CityMode.wires();

			CityMode.applyServerOverride(CityMode.fromConfig());
			assertEquals(machinesBefore, CityMode.machines(), "master="+master);
			assertEquals(wiresBefore, CityMode.wires(), "master="+master);
			assertEquals(master, CityMode.enabled(), "master="+master);
		}
	}

	// ---------------------------------------------------------------- shape

	@Test
	@DisplayName("Subsystem ordinals are the wire format, so their order is fixed")
	void subsystemOrderIsFixed()
	{
		//Reordering these would make an old client read a new server's mask as a different set of
		//subsystems, which is the kind of bug that looks like "floodlights broke on the server".
		assertArrayEquals(new String[]{
						"WIRES", "PIPES", "CONDUITS", "TANKS", "FLOODLIGHTS",
						"GENERATORS", "MACHINES", "VIRTUAL_GRID", "PETROLEUM"},
				java.util.Arrays.stream(Subsystem.VALUES).map(Enum::name).toArray(String[]::new));
	}

	@Test
	@DisplayName("VALUES is the same content as values(), so callers may share it")
	void cachedValuesMatch()
	{
		assertArrayEquals(Subsystem.values(), Subsystem.VALUES);
	}
}
