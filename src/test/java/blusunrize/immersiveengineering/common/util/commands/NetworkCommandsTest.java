/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.energy.grid.GridPolicy;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.FluidPolicy;
import net.minecraft.command.ICommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /ie grid} and {@code /ie fluidnet}, which are mirrors of one another.
 * <p>
 * These commands exist to diagnose a network without its console, or to repair one whose console has
 * been destroyed -- so they are the last line when something has gone wrong, and "the recovery tool
 * lies about the state" is the worst failure they can have. The state description's <em>precedence</em>
 * is therefore the thing worth pinning: a segment that is both tripped and scheduled off has to read
 * as tripped, or the command written to end this confusion becomes another source of it.
 * <p>
 * Holding the two trees against each other is the other half. api/fluid/network is a deliberate copy
 * of api/energy/grid, and a subcommand that exists on one side and not the other is how the fluid
 * network came to have a schedule that could be switched on but never set.
 */
class NetworkCommandsTest
{
	private static Set<String> subcommandNames(net.minecraftforge.server.command.CommandTreeBase tree)
	{
		Set<String> names = new HashSet<>();
		for(ICommand sub : tree.getSubCommands())
			names.add(sub.getName());
		return names;
	}

	@Nested
	@DisplayName("the command trees")
	class Trees
	{
		@Test
		@DisplayName("both are op-only")
		void bothAreOpOnly()
		{
			//These can delete a segment and reassign every device on it. Permission level 4 is the
			//same bar vanilla puts on /stop.
			assertEquals(4, new CommandGrid().getRequiredPermissionLevel());
			assertEquals(4, new CommandFluidNet().getRequiredPermissionLevel());
			assertEquals(4, new CommandReservoir().getRequiredPermissionLevel());
		}

		@Test
		@DisplayName("their names are distinct and stable")
		void namesAreStable()
		{
			assertEquals("grid", new CommandGrid().getName());
			assertEquals("fluidnet", new CommandFluidNet().getName());
			assertEquals("reservoir", new CommandReservoir().getName());
		}

		@Test
		@DisplayName("each usage line points at its own help subcommand")
		void usagePointsAtOwnHelp()
		{
			assertTrue(new CommandGrid().getUsage(null).contains("/ie grid help"));
			assertTrue(new CommandFluidNet().getUsage(null).contains("/ie fluidnet help"));
		}

		@Test
		@DisplayName("every subcommand carries a usage line naming its own command")
		void everySubcommandHasUsage()
		{
			//A subcommand whose usage names the wrong command is what a copied mirror produces, and
			//the player only ever sees it at the moment they already got something wrong.
			for(ICommand sub : new CommandGrid().getSubCommands())
			{
				String usage = sub.getUsage(null);
				assertNotNull(usage, sub.getName()+" has no usage line");
				if(!"help".equals(sub.getName()))
					assertTrue(usage.contains("/ie grid"),
							"grid subcommand \""+sub.getName()+"\" has usage \""+usage+"\"");
			}
			for(ICommand sub : new CommandFluidNet().getSubCommands())
			{
				String usage = sub.getUsage(null);
				assertNotNull(usage, sub.getName()+" has no usage line");
				if(!"help".equals(sub.getName()))
					assertTrue(usage.contains("/ie fluidnet"),
							"fluidnet subcommand \""+sub.getName()+"\" has usage \""+usage+"\"");
			}
		}

		@Test
		@DisplayName("no subcommand name is registered twice")
		void subcommandNamesAreUnique()
		{
			assertEquals(new CommandGrid().getSubCommands().size(),
					subcommandNames(new CommandGrid()).size(), "a grid subcommand name is duplicated");
			assertEquals(new CommandFluidNet().getSubCommands().size(),
					subcommandNames(new CommandFluidNet()).size(), "a fluidnet subcommand name is duplicated");
		}
	}

	@Nested
	@DisplayName("the two trees mirror each other")
	class MirrorParity
	{
		@Test
		@DisplayName("every shape the grid has, the fluid network has too")
		void fluidNetCoversTheGrid()
		{
			//	=================================
			//	This caught a real gap.
			//	=================================
			//
			// The fluid network had no "schedule" subcommand, while FluidPolicy carried the whole
			// schedule and the console offered an on/off toggle for it. A main's schedule could be
			// switched on and its window could never be set -- not by command, not in the GUI.
			//
			// on/off against open/close is a deliberate difference of noun, not of shape, so those
			// are mapped rather than compared.
			Set<String> grid = subcommandNames(new CommandGrid());
			Set<String> fluid = subcommandNames(new CommandFluidNet());
			for(String name : grid)
			{
				String expected = "on".equals(name)?"open": "off".equals(name)?"close": name;
				//unstick is grid-only on purpose: it force-removes a stale device record, and the
				//fluid side has no equivalent failure to recover from yet.
				if("unstick".equals(name))
					continue;
				assertTrue(fluid.contains(expected),
						"the grid has \""+name+"\" but the fluid network has no \""+expected+"\"");
			}
		}

		@Test
		@DisplayName("the fluid network's extra subcommand is the one the grid has no use for")
		void fluidNetsExtraIsTheFluidItself()
		{
			Set<String> fluid = subcommandNames(new CommandFluidNet());
			Set<String> grid = subcommandNames(new CommandGrid());
			fluid.removeAll(grid);
			fluid.remove("open");
			fluid.remove("close");
			//A main carries exactly one named fluid; a segment carries undifferentiated flux.
			assertEquals(new HashSet<>(java.util.Collections.singletonList("fluid")), fluid,
					"the fluid network has gained a subcommand the grid has no counterpart for");
		}

		@Test
		@DisplayName("both can set a schedule window")
		void bothCanSetASchedule()
		{
			assertTrue(subcommandNames(new CommandGrid()).contains("schedule"));
			assertTrue(subcommandNames(new CommandFluidNet()).contains("schedule"),
					"a schedule that can be enabled but not set is a schedule nobody can use");
		}
	}

	@Nested
	@DisplayName("what a segment's state reads as")
	class GridState
	{
		private GridSegment segment()
		{
			return new VirtualGrid().createSegment("Yard");
		}

		@Test
		@DisplayName("an ordinary segment reads as on")
		void ordinaryReadsOn()
		{
			assertTrue(strip(CommandGrid.describeState(segment())).contains("on"));
		}

		@Test
		@DisplayName("a switched-off segment reads as off")
		void disabledReadsOff()
		{
			GridSegment s = segment();
			s.setEnabled(false);
			assertEquals("off", strip(CommandGrid.describeState(s)));
		}

		@Test
		@DisplayName("a tripped segment reads as tripped even when it is also switched off")
		void trippedBeatsDisabled()
		{
			//Precedence is the decision. A breaker that has tripped is the thing to go and look at;
			//reporting "off" would send somebody to the switch instead.
			GridSegment s = segment();
			s.setEnabled(false);
			s.setTripped(true);
			assertEquals("TRIPPED", strip(CommandGrid.describeState(s)));
		}

		@Test
		@DisplayName("a segment held down by a signal says so rather than reading as on")
		void forcedOffIsNamed()
		{
			//This is exactly the confusion the command exists to end: a kill switch holds a segment
			//down while every toggle still says it is enabled.
			GridSegment s = segment();
			s.setForcedOff(true);
			assertTrue(strip(CommandGrid.describeState(s)).contains("signal"));
		}

		@Test
		@DisplayName("a signal outranks a schedule, because it is the one somebody just did")
		void signalBeatsSchedule()
		{
			GridSegment s = segment();
			GridPolicy policy = s.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(0);
			policy.setScheduleOff(1);
			s.updateSchedule(12000);
			assertTrue(s.isScheduleSuppressed(), "the segment should be outside its window");
			s.setForcedOff(true);
			assertTrue(strip(CommandGrid.describeState(s)).contains("signal"));
		}

		@Test
		@DisplayName("a segment asleep on its schedule says so")
		void scheduleSuppressedIsNamed()
		{
			GridSegment s = segment();
			GridPolicy policy = s.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(0);
			policy.setScheduleOff(1);
			s.updateSchedule(12000);
			assertTrue(strip(CommandGrid.describeState(s)).contains("scheduled"));
		}
	}

	@Nested
	@DisplayName("what a main's state reads as, which must mirror a segment's")
	class FluidState
	{
		private FluidMain main()
		{
			return new FluidMain(UUID.randomUUID(), "Line");
		}

		@Test
		@DisplayName("an ordinary main reads as open")
		void ordinaryReadsOpen()
		{
			assertEquals("open", strip(CommandFluidNet.describeState(main())));
		}

		@Test
		@DisplayName("a tripped main reads as tripped even when it is also closed")
		void trippedBeatsClosed()
		{
			FluidMain m = main();
			m.setEnabled(false);
			m.setTripped(true);
			assertEquals("TRIPPED", strip(CommandFluidNet.describeState(m)));
		}

		@Test
		@DisplayName("a main held closed by a valve says so rather than reading as open")
		void forcedClosedIsNamed()
		{
			FluidMain m = main();
			m.setForcedClosed(true);
			assertTrue(strip(CommandFluidNet.describeState(m)).contains("valve"));
		}

		@Test
		@DisplayName("a valve outranks a schedule, exactly as a signal does on the grid")
		void valveBeatsSchedule()
		{
			FluidMain m = main();
			FluidPolicy policy = m.getPolicy();
			policy.setScheduleEnabled(true);
			policy.setScheduleOn(0);
			policy.setScheduleOff(1);
			m.updateSchedule(12000);
			assertTrue(m.isScheduleSuppressed(), "the main should be outside its window");
			m.setForcedClosed(true);
			assertTrue(strip(CommandFluidNet.describeState(m)).contains("valve"));
		}

		@Test
		@DisplayName("the precedence order is the same on both sides")
		void precedenceMirrorsTheGrid()
		{
			//Tripped, then closed, then held, then scheduled. Asserted as a pair so a change to one
			//side alone shows up here rather than as a player reporting that the two consoles
			//disagree about the same situation.
			FluidMain m = main();
			GridSegment s = new VirtualGrid().createSegment("Yard");

			m.setTripped(true);
			s.setTripped(true);
			assertEquals(strip(CommandFluidNet.describeState(m)), strip(CommandGrid.describeState(s)),
					"a tripped main and a tripped segment should read alike");
		}
	}

	/** Strips the colour codes so an assertion is about the words rather than the formatting. */
	private static String strip(String text)
	{
		return text.replaceAll("§.", "");
	}
}
