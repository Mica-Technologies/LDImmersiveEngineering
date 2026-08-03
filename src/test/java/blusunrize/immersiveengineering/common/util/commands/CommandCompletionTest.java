/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which argument wants a name, and which names match what has been typed.
 * <p>
 * {@code /ie grid} and {@code /ie fluidnet} are mirrors, and their completion routing was written
 * out twice. Two copies of an argument index drift without anything failing -- one gains a
 * subcommand exemption and the other does not -- and the symptom is a tab key offering the wrong
 * kind of name. Both now route through here, and here is asserted.
 */
class CommandCompletionTest
{
	private static final List<String> SEGMENTS =
			Arrays.asList("Main Yard", "main hall", "Workshop", "quarry");

	@Nested
	@DisplayName("prefix matching")
	class Matching
	{
		@Test
		@DisplayName("an empty prefix offers everything")
		void emptyOffersEverything()
		{
			assertEquals(SEGMENTS, CommandCompletion.matchingPrefix(SEGMENTS, ""));
		}

		@Test
		@DisplayName("matching ignores case, because a name is something somebody typed")
		void matchingIgnoresCase()
		{
			//"Main Yard" and "main hall" both match "MAIN" -- nobody remembers the case of a name
			//they gave a segment three weeks ago.
			List<String> hits = CommandCompletion.matchingPrefix(SEGMENTS, "MAIN");
			assertEquals(Arrays.asList("Main Yard", "main hall"), hits);
		}

		@Test
		@DisplayName("but each candidate keeps its own capitalisation")
		void capitalisationIsPreserved()
		{
			//Offering a lowercased name would complete to something that does not exist.
			assertEquals(Collections.singletonList("Workshop"),
					CommandCompletion.matchingPrefix(SEGMENTS, "work"));
		}

		@Test
		@DisplayName("a prefix nothing starts with offers nothing")
		void noMatchOffersNothing()
		{
			assertTrue(CommandCompletion.matchingPrefix(SEGMENTS, "zzz").isEmpty());
		}

		@Test
		@DisplayName("a name is matched on its start, not anywhere inside it")
		void matchesOnlyAtTheStart()
		{
			//"Yard" appears in "Main Yard" but not at its start, so it must not be offered --
			//otherwise completing turns a typo into a different segment.
			assertTrue(CommandCompletion.matchingPrefix(SEGMENTS, "Yard").isEmpty());
		}

		@Test
		@DisplayName("a null prefix behaves as an empty one rather than throwing")
		void nullPrefixIsEmpty()
		{
			assertEquals(SEGMENTS.size(), CommandCompletion.matchingPrefix(SEGMENTS, null).size());
		}

		@Test
		@DisplayName("no candidates at all completes to nothing")
		void noCandidates()
		{
			assertTrue(CommandCompletion.matchingPrefix(Collections.emptyList(), "a").isEmpty());
			assertTrue(CommandCompletion.matchingPrefix(null, "a").isEmpty());
		}
	}

	@Nested
	@DisplayName("the subject's name")
	class SubjectName
	{
		@Test
		@DisplayName("the second argument of an ordinary subcommand is a name")
		void secondArgumentIsAName()
		{
			assertTrue(CommandCompletion.completesSubjectName(args("info", ""), "create", "list"));
			assertTrue(CommandCompletion.completesSubjectName(args("on", "ma"), "create", "list"));
			assertTrue(CommandCompletion.completesSubjectName(args("delete", ""), "create", "list"));
		}

		@Test
		@DisplayName("create names something new, so existing names are not offered")
		void createIsExempt()
		{
			//Completing an existing name into "create" offers an answer that is wrong by
			//definition: the command would fail on the name it just suggested.
			assertFalse(CommandCompletion.completesSubjectName(args("create", "Ma"), "create", "list"));
		}

		@Test
		@DisplayName("list takes nothing, so nothing is offered")
		void listIsExempt()
		{
			assertFalse(CommandCompletion.completesSubjectName(args("list", ""), "create", "list"));
		}

		@Test
		@DisplayName("the subcommand itself is not a name")
		void firstArgumentIsNotAName()
		{
			assertFalse(CommandCompletion.completesSubjectName(args("inf"), "create", "list"));
		}

		@Test
		@DisplayName("nor is anything past the second argument")
		void laterArgumentsAreNotTheSubject()
		{
			assertFalse(CommandCompletion.completesSubjectName(args("link", "a", "b"), "create", "list"));
		}

		@Test
		@DisplayName("an empty or absent argument array completes nothing")
		void degenerateArgs()
		{
			assertFalse(CommandCompletion.completesSubjectName(args(), "create", "list"));
			assertFalse(CommandCompletion.completesSubjectName(null, "create", "list"));
		}
	}

	@Nested
	@DisplayName("the far end of a link")
	class SecondName
	{
		@Test
		@DisplayName("the third argument of link and unlink is another name")
		void linkTakesTwoNames()
		{
			assertTrue(CommandCompletion.completesSecondName(args("link", "a", ""), "link", "unlink"));
			assertTrue(CommandCompletion.completesSecondName(args("unlink", "a", "b"), "link", "unlink"));
		}

		@Test
		@DisplayName("no other subcommand takes a second name")
		void othersDoNot()
		{
			assertFalse(CommandCompletion.completesSecondName(args("info", "a", ""), "link", "unlink"));
			assertFalse(CommandCompletion.completesSecondName(args("assign", "a", "b"), "link", "unlink"));
		}

		@Test
		@DisplayName("the second argument of link is the near end, handled as the subject")
		void secondArgumentIsTheSubject()
		{
			assertFalse(CommandCompletion.completesSecondName(args("link", "a"), "link", "unlink"));
			assertTrue(CommandCompletion.completesSubjectName(args("link", "a"), "create", "list"));
		}
	}

	@Nested
	@DisplayName("the fluid argument, which the grid has no counterpart for")
	class ThirdArg
	{
		@Test
		@DisplayName("the third argument of fluid is a fluid name")
		void fluidTakesAFluid()
		{
			assertTrue(CommandCompletion.completesThirdArgOf(args("fluid", "a", "wat"), "fluid"));
		}

		@Test
		@DisplayName("only that subcommand, and only that argument")
		void nothingElse()
		{
			assertFalse(CommandCompletion.completesThirdArgOf(args("info", "a", "wat"), "fluid"));
			assertFalse(CommandCompletion.completesThirdArgOf(args("fluid", "a"), "fluid"));
			assertFalse(CommandCompletion.completesThirdArgOf(args("fluid", "a", "b", "c"), "fluid"));
		}

		@Test
		@DisplayName("a null subcommand matches nothing rather than throwing")
		void nullSubcommand()
		{
			assertFalse(CommandCompletion.completesThirdArgOf(args("fluid", "a", "b"), null));
		}
	}

	@Nested
	@DisplayName("the two commands route identically where they mirror each other")
	class MirrorParity
	{
		@Test
		@DisplayName("every subcommand both share completes at the same argument")
		void sharedSubcommandsAgree()
		{
			//The grid and the fluid network answer the same shapes with different nouns. Anywhere
			//they both have a subcommand, the tab key must behave the same in both.
			for(String sub : new String[]{"info", "on", "off", "delete", "assign", "unassign",
					"link", "unlink", "devices", "schedule"})
			{
				assertTrue(CommandCompletion.completesSubjectName(args(sub, ""), "create", "list"),
						sub+" should complete a name at argument two on both commands");
			}
			for(String sub : new String[]{"link", "unlink"})
				assertTrue(CommandCompletion.completesSecondName(args(sub, "a", ""), "link", "unlink"),
						sub+" should complete a second name on both commands");
		}
	}

	private static String[] args(String... values)
	{
		return values;
	}
}
