/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which argument of a subcommand wants a name completed, and which names match what has been typed.
 * <p>
 * <strong>Shared by {@code /ie grid} and {@code /ie fluidnet}, which is the point.</strong> The two
 * commands are mirrors of one another -- segments and mains, links and links, the same shapes with
 * different nouns -- and the completion routing was written out twice. Two copies of an argument
 * index is exactly the sort of thing that drifts: one gains a subcommand exemption and the other
 * does not, and the symptom is a tab key that offers grid segment names where a fluid was wanted.
 * <p>
 * World-free, so the routing can be asserted against argument arrays directly rather than against a
 * running server.
 *
 * @author LDImmersiveEngineering -- grid
 */
public final class CommandCompletion
{
	private CommandCompletion()
	{
	}

	/**
	 * Case-insensitive prefix match, preserving each candidate's own capitalisation.
	 * <p>
	 * Insensitive because a segment called "Main Yard" is a name somebody typed and will not
	 * remember the case of; the completion still offers it as they wrote it.
	 */
	public static List<String> matchingPrefix(Iterable<String> candidates, String prefix)
	{
		List<String> out = new ArrayList<>();
		if(candidates==null)
			return out;
		String lower = prefix==null?"": prefix.toLowerCase(Locale.ENGLISH);
		for(String candidate : candidates)
			if(candidate!=null&&candidate.toLowerCase(Locale.ENGLISH).startsWith(lower))
				out.add(candidate);
		return out;
	}

	/**
	 * Whether the argument being typed is the subject's name -- the segment or the main that almost
	 * every subcommand takes first.
	 *
	 * @param args   the completion's argument array, subcommand at index 0
	 * @param exempt subcommands that do <em>not</em> take an existing name there. {@code create}
	 *               names something new, and {@code list} takes nothing at all; offering existing
	 *               names for either is offering an answer that is wrong by definition.
	 */
	public static boolean completesSubjectName(String[] args, String... exempt)
	{
		if(args==null||args.length!=2)
			return false;
		return !isOneOf(args[0], exempt);
	}

	/**
	 * Whether the argument being typed is a <em>second</em> name -- the far end of a failover link.
	 *
	 * @param subcommands the subcommands whose third argument is another subject's name
	 */
	public static boolean completesSecondName(String[] args, String... subcommands)
	{
		if(args==null||args.length!=3)
			return false;
		return isOneOf(args[0], subcommands);
	}

	/**
	 * Whether the argument being typed is the third argument of one named subcommand -- the fluid
	 * on {@code /ie fluidnet fluid}, which has no counterpart on the grid side.
	 */
	public static boolean completesThirdArgOf(String[] args, String subcommand)
	{
		return args!=null&&args.length==3&&subcommand!=null&&subcommand.equals(args[0]);
	}

	private static boolean isOneOf(String value, String... options)
	{
		return value!=null&&options!=null&&Arrays.asList(options).contains(value);
	}
}
