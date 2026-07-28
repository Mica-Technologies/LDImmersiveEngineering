/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks;

import java.util.List;

/**
 * A block that can describe its own state in a few lines of already-formatted text.
 * <p>
 * The grid and fluid network fittings all built such a list already, for the paragraph they print
 * when a player right-clicks them with an Engineer's Hammer. That list is the honest answer to
 * "what is this thing doing" -- it is assembled server-side from the live network rather than from
 * whatever the client last happened to be told -- so the WAILA overlay shows exactly it rather than
 * a second, subtly different summary that can drift away from the first.
 * <p>
 * Implementations are called on the <strong>server</strong>. That is the point: the client only
 * knows about a segment while a console or terminal window is open, and a tooltip that went blank
 * the moment you closed the console would be worse than no tooltip.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public interface IStatusLineProvider
{
	/**
	 * @return the lines to show, outermost first. May be empty, never null. Colour codes are
	 * allowed and are passed through untouched.
	 */
	List<String> getStatusLines();
}
