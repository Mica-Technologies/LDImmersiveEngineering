/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.link;

import blusunrize.immersiveengineering.common.util.link.LinkerLogic.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a linking tool does with one click.
 * <p>
 * The interesting cases here are all permission cases, and they are the reason this decision was
 * pulled out of the two tools in the first place: a tool that quietly moved a box off somebody
 * else's locked segment would be a hole in the lock, and the two tools would have had to get that
 * right separately. Locks are checked on both ends of the move -- the segment being pasted and the
 * one being left -- and neither check is allowed to be skipped by any path.
 */
class LinkerLogicTest
{
	/**
	 * The ordinary case: a loaded tool, an unlinked box, nothing locked.
	 */
	private static Outcome plain()
	{
		return LinkerLogic.decide(true, true, true, true, true, false);
	}

	@Nested
	@DisplayName("the ordinary path")
	class Ordinary
	{
		@Test
		@DisplayName("a loaded tool on a free box assigns it")
		void loadedToolAssigns()
		{
			assertEquals(Outcome.ASSIGN, plain());
		}

		@Test
		@DisplayName("an empty tool asks which network rather than doing nothing")
		void emptyToolOpensTheChooser()
		{
			assertEquals(Outcome.OPEN_CHOOSER,
					LinkerLogic.decide(true, false, false, false, true, false));
		}

		@Test
		@DisplayName("a click on something that is not a device is left alone")
		void notADevicePassesThrough()
		{
			assertEquals(Outcome.NOT_A_DEVICE,
					LinkerLogic.decide(false, true, true, true, true, false));
			assertFalse(LinkerLogic.consumesClick(Outcome.NOT_A_DEVICE));
		}

		@Test
		@DisplayName("a box already on the selection is reported rather than reassigned")
		void alreadyLinkedIsANoOp()
		{
			assertEquals(Outcome.ALREADY_LINKED,
					LinkerLogic.decide(true, true, true, true, true, true));
		}
	}

	@Nested
	@DisplayName("locks")
	class Locks
	{
		@Test
		@DisplayName("a locked selection refuses before anything else is considered")
		void lockedSelectionRefuses()
		{
			assertEquals(Outcome.SELECTION_LOCKED,
					LinkerLogic.decide(true, true, true, false, true, false));
		}

		@Test
		@DisplayName("a box on somebody else's locked network cannot be taken off it")
		void lockedCurrentRefuses()
		{
			//The half that is easy to forget. Without it a tool loaded with your own segment would
			//be a way to strip devices off a neighbour's locked one.
			assertEquals(Outcome.CURRENT_LOCKED,
					LinkerLogic.decide(true, true, true, true, false, false));
		}

		@Test
		@DisplayName("a locked selection wins over a locked current, so the message names the tool")
		void selectionLockIsReportedFirst()
		{
			assertEquals(Outcome.SELECTION_LOCKED,
					LinkerLogic.decide(true, true, true, false, false, false));
		}

		@Test
		@DisplayName("a no-op on a locked network is a no-op, not a permission failure")
		void alreadyLinkedBeatsTheCurrentLock()
		{
			//Nothing is being moved, so sending the player to find an owner they do not need would
			//be a lie about what just happened.
			assertEquals(Outcome.ALREADY_LINKED,
					LinkerLogic.decide(true, true, true, true, false, true));
		}
	}

	@Nested
	@DisplayName("a selection that has gone away")
	class Stale
	{
		@Test
		@DisplayName("a deleted network is reported and empties the tool")
		void deletedNetworkClearsTheTool()
		{
			Outcome outcome = LinkerLogic.decide(true, true, false, false, true, false);
			assertEquals(Outcome.SELECTION_GONE, outcome);
			assertTrue(LinkerLogic.clearsTool(outcome));
		}

		@Test
		@DisplayName("nothing else empties the tool")
		void nothingElseClearsTheTool()
		{
			//A lock is somebody else's temporary refusal. Emptying the tool over one would mean
			//re-picking a segment every time a click landed on a neighbour's box.
			for(Outcome outcome : Outcome.values())
				if(outcome!=Outcome.SELECTION_GONE)
					assertFalse(LinkerLogic.clearsTool(outcome), outcome.name());
		}
	}

	@Nested
	@DisplayName("which clicks are consumed")
	class Consumption
	{
		@Test
		@DisplayName("every outcome but a miss claims the click")
		void everythingButAMissIsConsumed()
		{
			//A refusal that let the click through would open the box's own panel on top of the
			//message explaining why nothing happened.
			for(Outcome outcome : Outcome.values())
				assertEquals(outcome!=Outcome.NOT_A_DEVICE, LinkerLogic.consumesClick(outcome),
						outcome.name());
		}
	}
}
