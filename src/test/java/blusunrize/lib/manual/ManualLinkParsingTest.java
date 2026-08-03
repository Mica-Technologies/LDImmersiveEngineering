/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.lib.manual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsing {@code <link;entry;text;page>} out of a manual page.
 * <p>
 * <strong>Every input here is lang-file content.</strong> Pack authors write it and translators
 * rewrite it, and neither can be expected to know that a stray bracket was being compiled as a
 * regular expression or that a missing {@code >} would take the page down. Both of those were real,
 * and both are the kind of thing that only shows up once somebody translates the manual.
 */
class ManualLinkParsingTest
{
	private static List<String[]> links;

	private static String parse(String text)
	{
		links = new ArrayList<>();
		return ManualPages.extractLinks(text, links);
	}

	@Nested
	@DisplayName("ordinary links")
	class Ordinary
	{
		@Test
		@DisplayName("a link is replaced and its destination recorded")
		void linkIsRecorded()
		{
			String out = parse("see the <link;mixer;Mixer;0> for details");
			assertFalse(out.contains("<link"), "the tag should be gone from the text");
			assertEquals(1, links.size());
			assertEquals("mixer", links.get(0)[1], "the entry key");
			assertEquals("0", links.get(0)[2], "the page");
		}

		@Test
		@DisplayName("an omitted page defaults to the first")
		void pageDefaultsToZero()
		{
			parse("<link;mixer;Mixer>");
			assertEquals("0", links.get(0)[2]);
		}

		@Test
		@DisplayName("a multi-word label becomes one marked run per word")
		void multiWordLabel()
		{
			//Each word is marked separately so the renderer can wrap the label across lines and
			//still know every piece belongs to the same link.
			parse("<link;mixer;the big mixer;0>");
			assertEquals(3, links.size());
			for(String[] link : links)
				assertEquals("mixer", link[1], "every word must point at the same entry");
		}

		@Test
		@DisplayName("several links on one page are all found")
		void severalLinks()
		{
			parse("<link;a;A;0> and <link;b;B;1> and <link;c;C;2>");
			assertEquals(3, links.size());
			assertEquals("a", links.get(0)[1]);
			assertEquals("b", links.get(1)[1]);
			assertEquals("c", links.get(2)[1]);
		}

		@Test
		@DisplayName("text with no links is returned untouched")
		void noLinks()
		{
			String text = "just some ordinary prose";
			assertEquals(text, parse(text));
			assertTrue(links.isEmpty());
		}
	}

	@Nested
	@DisplayName("text that used to break the parser")
	class Malformed
	{
		@Test
		@DisplayName("a link with no closing bracket does not throw")
		void unclosedTagIsSurvivable()
		{
			//	=================================
			//	indexOf answers -1.
			//	=================================
			//
			// substring(start, -1+1) is substring(start, 0), which throws whenever the tag is not at
			// the very beginning. One dropped '>' anywhere in a translated manual took the page out.
			assertDoesNotThrow(() -> parse("some prose <link;mixer;Mixer;0 and then more"));
		}

		@Test
		@DisplayName("nor does one at the very start of the page")
		void unclosedAtStart()
		{
			assertDoesNotThrow(() -> parse("<link;mixer;Mixer;0"));
		}

		@Test
		@DisplayName("a label containing brackets is consumed, not left on the page")
		void bracketsInLabelAreLiteral()
		{
			//	=================================
			//	replaceFirst takes a regex, and this fails quietly.
			//	=================================
			//
			// "Mixer (see below)" is a *valid* pattern -- (see below) is just a group -- so nothing
			// throws. It simply does not match the literal text it came from, so the replacement
			// never happens, the loop finds the same tag again, and it spins to the overflow guard
			// leaving raw "<link;...>" markup on the rendered page.
			//
			// Asserting "does not throw" would pass against the bug. The property is that the tag
			// is gone.
			assertFalse(parse("<link;mixer;Mixer (see below);0>").contains("<link"),
					"the tag must be consumed, not left in the text");
			assertEquals("mixer", links.get(0)[1]);
		}

		@Test
		@DisplayName("a label containing a dollar sign is consumed")
		void dollarInLabelIsLiteral()
		{
			//'$' is end-of-input as a pattern and a group reference as a replacement, so this one
			//could fail either way depending on what followed it.
			assertFalse(parse("<link;shop;Cost $5;0>").contains("<link"),
					"the tag must be consumed, not left in the text");
			assertEquals("shop", links.get(0)[1]);
		}

		@Test
		@DisplayName("a label containing a backslash is consumed")
		void backslashInLabelIsLiteral()
		{
			assertFalse(parse("<link;path;A\\B;0>").contains("<link"),
					"the tag must be consumed, not left in the text");
			assertEquals("path", links.get(0)[1]);
		}

		@Test
		@DisplayName("regex metacharacters in the entry key are consumed too")
		void metacharactersInKey()
		{
			assertFalse(parse("<link;odd.key*;Label;0>").contains("<link"),
					"the tag must be consumed, not left in the text");
			assertEquals("odd.key*", links.get(0)[1]);
		}

		@Test
		@DisplayName("a bracketed label does not spin the loop against its own overflow guard")
		void bracketsDoNotExhaustTheGuard()
		{
			//The visible symptom is raw markup; the invisible one is fifty wasted passes over the
			//string every time the page is opened. A one-word label should be recorded exactly once
			//-- a multi-word one legitimately produces an entry per word, which is why this uses a
			//label with brackets but no space in it.
			parse("<link;mixer;Mixer(below);0>");
			assertEquals(1, links.size(),
					"a single one-word link should be recorded once, not once per retry");
		}

		@Test
		@DisplayName("a tag with too few segments stops parsing rather than throwing")
		void tooFewSegments()
		{
			assertDoesNotThrow(() -> parse("<link;onlyone>"));
			assertTrue(links.isEmpty());
		}

		@Test
		@DisplayName("a page full of links terminates rather than looping forever")
		void terminates()
		{
			//The loop rewrites the text it is scanning, so a tag it failed to consume would spin.
			//The overflow guard caps it; this asserts the cap is reached rather than exceeded.
			StringBuilder page = new StringBuilder();
			for(int i = 0; i < 200; i++)
				page.append("<link;e").append(i).append(";L;0> ");
			assertDoesNotThrow(() -> parse(page.toString()));
			assertTrue(links.size() <= 200, "the guard should stop it running away");
			assertFalse(links.isEmpty(), "but it should still have parsed something");
		}
	}
}
