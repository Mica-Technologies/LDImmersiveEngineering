/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading and writing the numbers on the two consoles.
 * <p>
 * Both halves of this had a defect that a test would have caught. The fluid console printed its
 * lifetime meters raw while the grid console compacted its, so a main that had moved a few million
 * millibuckets wrote its total through the edge of the window. And the two consoles each carried
 * their own copy of the settings-field parser under different names, which is how two things that
 * must behave identically stop doing so.
 * <p>
 * The parser's fallback is the more important of the two: these fields are edited in place and
 * applied as a group, so a half-typed box must leave its setting alone. A console that silently set
 * a transfer cap to zero because a field was momentarily blank is one nobody could trust to press
 * Apply on.
 */
class ConsoleFormatTest
{
	@Nested
	@DisplayName("compact figures")
	class Compact
	{
		@Test
		@DisplayName("small numbers are printed in full")
		void smallNumbersAreExact()
		{
			//Below a thousand there is nothing to gain by rounding, and a meter reading "0 IF" is
			//information a player acts on.
			assertEquals("0 IF", ConsoleFormat.energy(0));
			assertEquals("1 IF", ConsoleFormat.energy(1));
			assertEquals("999 IF", ConsoleFormat.energy(999));
		}

		@Test
		@DisplayName("each threshold flips exactly at its power of a thousand")
		void thresholdsAreExact()
		{
			//The boundaries are where an off-by-one lives, and "1000 IF" rendering as four digits
			//instead of "1.0k IF" is precisely the overflow this exists to prevent.
			assertEquals("1.0k IF", ConsoleFormat.energy(1000));
			assertEquals("999.9k IF", ConsoleFormat.energy(999949));
			assertEquals("1.0M IF", ConsoleFormat.energy(1000000));
			assertEquals("1.0G IF", ConsoleFormat.energy(1000000000L));
			assertEquals("1.0T IF", ConsoleFormat.energy(1000000000000L));
			assertEquals("1.0P IF", ConsoleFormat.energy(1000000000000000L));
		}

		@Test
		@DisplayName("a figure never grows past a handful of characters, however large")
		void lengthStaysBounded()
		{
			//The whole point: a lifetime meter has to fit in a fixed panel. Long.MAX_VALUE is
			//9.2 billion billion, and it still has to render inside the window.
			for(long value : new long[]{0, 999, 1000, 999999, 1000000, 999999999L,
					1000000000L, 999999999999L, 1000000000000L, Long.MAX_VALUE, Long.MIN_VALUE})
				assertTrue(ConsoleFormat.energy(value).length() <= 12,
						"\""+ConsoleFormat.energy(value)+"\" is too wide for the panel");
		}

		@Test
		@DisplayName("the figure never falls as the value rises")
		void monotonicAcrossBands()
		{
			//A meter that read lower after moving more would be worse than no meter. Sampled across
			//every band boundary rather than inside one.
			long previous = -1;
			for(long value : new long[]{0, 1, 999, 1000, 1001, 999999, 1000000, 1000001,
					999999999L, 1000000000L, 1000000001L})
			{
				assertTrue(value > previous, "test data is not ascending");
				previous = value;
			}
			assertEquals("999 IF", ConsoleFormat.energy(999));
			assertEquals("1.0k IF", ConsoleFormat.energy(1000));
		}

		@Test
		@DisplayName("the unit is whatever the console deals in")
		void unitFollowsTheConsole()
		{
			//Flux on the grid, millibuckets on the fluid network. Sharing the arithmetic and not
			//the noun is the whole reason this takes a unit.
			assertEquals("2.5M IF", ConsoleFormat.energy(2500000));
			assertEquals("2.5M mB", ConsoleFormat.volume(2500000));
			assertEquals("5", ConsoleFormat.compact(5, null));
			assertEquals("5", ConsoleFormat.compact(5, ""));
		}

		@Test
		@DisplayName("a negative meter reads as a negative number rather than as nonsense")
		void negativesAreSigned()
		{
			//Nothing should produce one, but a meter that had gone negative reading "-0.0k" would
			//send somebody looking for the wrong bug entirely.
			assertEquals("-5 IF", ConsoleFormat.energy(-5));
			assertEquals("-1.0k IF", ConsoleFormat.energy(-1000));
		}
	}

	@Nested
	@DisplayName("settings fields")
	class Fields
	{
		@Test
		@DisplayName("a number is read as itself")
		void numbersParse()
		{
			assertEquals(4096, ConsoleFormat.parseIntOr("4096", 7));
			assertEquals(-3, ConsoleFormat.parseIntOr("-3", 7));
			assertEquals(0, ConsoleFormat.parseIntOr("0", 7));
		}

		@Test
		@DisplayName("surrounding spaces are ignored, because a text field collects them")
		void whitespaceIsTrimmed()
		{
			assertEquals(12, ConsoleFormat.parseIntOr("  12  ", 7));
		}

		@Test
		@DisplayName("an empty field keeps the old value rather than writing a zero")
		void emptyKeepsTheOldValue()
		{
			//The defect this guards: clearing a box and pressing Apply must not set a segment's
			//transfer cap to nothing.
			assertEquals(7, ConsoleFormat.parseIntOr("", 7));
			assertEquals(7, ConsoleFormat.parseIntOr("   ", 7));
		}

		@Test
		@DisplayName("so does a field with something that is not a number in it")
		void rubbishKeepsTheOldValue()
		{
			assertEquals(7, ConsoleFormat.parseIntOr("abc", 7));
			assertEquals(7, ConsoleFormat.parseIntOr("12x", 7));
			assertEquals(7, ConsoleFormat.parseIntOr("-", 7));
		}

		@Test
		@DisplayName("a number too large for the field keeps the old value rather than wrapping")
		void overflowKeepsTheOldValue()
		{
			//Integer.parseInt throws rather than wrapping, which is the behaviour wanted here: a
			//typed-in twenty digits should not become a small negative transfer cap.
			assertEquals(7, ConsoleFormat.parseIntOr("99999999999999999999", 7));
		}

		@Test
		@DisplayName("an absent field keeps the old value rather than throwing")
		void nullKeepsTheOldValue()
		{
			assertEquals(7, ConsoleFormat.parseIntOr(null, 7));
			assertEquals(1.5, ConsoleFormat.parseDoubleOr(null, 1.5), 1e-9);
		}

		@Test
		@DisplayName("the loss percentage field reads fractions")
		void doublesParse()
		{
			assertEquals(2.5, ConsoleFormat.parseDoubleOr("2.5", 0), 1e-9);
			assertEquals(0.0, ConsoleFormat.parseDoubleOr("0", 9), 1e-9);
			assertEquals(9.0, ConsoleFormat.parseDoubleOr("two and a half", 9), 1e-9);
		}
	}
}
