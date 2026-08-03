/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import java.util.Locale;

/**
 * Reading and writing the numbers on the Grid and Fluid consoles.
 * <p>
 * <strong>Shared, because the two consoles are mirrors and were drifting.</strong> The grid console
 * compacted its lifetime meter so it would not run off the panel; the fluid console printed the
 * same kind of figure raw, so a main that had moved a few million millibuckets wrote its total
 * straight through the edge of the window. The grid console called its field parser
 * {@code parseInt} and the fluid console called an identical one {@code parseIntOr}.
 * <p>
 * Deliberately free of Minecraft: no {@code GuiScreen}, no {@code FontRenderer}, nothing that needs
 * a client. That is what lets the boundaries below be asserted rather than eyeballed at 1000, at
 * 999, and at the point where a long stops fitting in a double cleanly.
 *
 * @author LDImmersiveEngineering -- grid
 */
public final class ConsoleFormat
{
	private ConsoleFormat()
	{
	}

	/**
	 * A compact figure with a unit: {@code 999 IF}, {@code 1.0k IF}, {@code 2.5M IF}.
	 * <p>
	 * Thresholds are exclusive at the bottom and inclusive at the top of each band, so exactly 1000
	 * reads as {@code 1.0k} rather than as four digits. A meter is read at a glance and the
	 * magnitude is what is being read; the last two digits of a lifetime total are noise.
	 *
	 * @param unit the suffix, without a leading space -- "IF" or "mB"
	 */
	public static String compact(long value, String unit)
	{
		String suffix = unit==null||unit.isEmpty()?"": " "+unit;
		//Negatives are formatted by magnitude and signed back, so a meter that has somehow gone
		//negative reads as a number rather than as "-1.0k" turning into "-0.0k".
		//
		//Long.MIN_VALUE has no positive counterpart, so it is the one value that cannot be negated;
		//it is answered directly rather than recursing into an overflow.
		if(value==Long.MIN_VALUE)
			return "-9223.4P"+suffix;
		if(value < 0)
			return "-"+compact(-value, unit);
		if(value < 1000)
			return value+suffix;
		if(value < 1000000L)
			return String.format(Locale.ENGLISH, "%.1fk%s", value/1e3, suffix);
		if(value < 1000000000L)
			return String.format(Locale.ENGLISH, "%.1fM%s", value/1e6, suffix);
		if(value < 1000000000000L)
			return String.format(Locale.ENGLISH, "%.1fG%s", value/1e9, suffix);
		//	=================================
		//	The bands run all the way up.
		//	=================================
		//
		// G used to be the last one, which left the largest band unbounded: a long at its maximum
		// rendered as "9223372036.9G IF" -- sixteen characters, straight through the edge of the
		// panel this function exists to keep figures inside of. Nothing will realistically meter
		// that much, but "realistically" is not a bound, and the two bands below cost two lines.
		if(value < 1000000000000000L)
			return String.format(Locale.ENGLISH, "%.1fT%s", value/1e12, suffix);
		return String.format(Locale.ENGLISH, "%.1fP%s", value/1e15, suffix);
	}

	/** Flux, for the Grid Management Console. */
	public static String energy(long value)
	{
		return compact(value, "IF");
	}

	/** Millibuckets, for the Fluid Control Console. */
	public static String volume(long value)
	{
		return compact(value, "mB");
	}

	/**
	 * Reads a settings field, keeping the old value when what is in it is not a number.
	 * <p>
	 * The fallback is the point. These fields are edited in place and applied as a group, so a
	 * half-typed or emptied box must leave its setting alone rather than writing a zero into it --
	 * a console that silently set a segment's transfer cap to nothing because a field was blank
	 * would be a console nobody could trust to click Apply on.
	 */
	public static int parseIntOr(String text, int fallback)
	{
		if(text==null)
			return fallback;
		try
		{
			return Integer.parseInt(text.trim());
		} catch(NumberFormatException e)
		{
			return fallback;
		}
	}

	/**
	 * @see #parseIntOr
	 */
	public static double parseDoubleOr(String text, double fallback)
	{
		if(text==null)
			return fallback;
		try
		{
			return Double.parseDouble(text.trim());
		} catch(NumberFormatException e)
		{
			return fallback;
		}
	}
}
