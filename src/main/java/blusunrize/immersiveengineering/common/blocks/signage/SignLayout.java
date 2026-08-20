/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

/**
 * Where a line of text sits on a plate, and how big it is allowed to be.
 * <p>
 * <strong>"Resizable" is the requirement, and this is it.</strong> Every kind of tag in the set was
 * asked for with a line count and the words "resizable line of text", which is what somebody who
 * reads real ones means by it: the number is printed to fill the plate, not typeset at a fixed size
 * with whatever hangs off the end lost. So a line is scaled to whichever of the two limits it hits
 * first -- the length of the plate, or the share of its width one line of a stack gets -- and a
 * short number comes out big while a long one comes out small, exactly as they do on a pole.
 * <p>
 * Shared between the renderer and the editing window on purpose. The window shows a preview, and a
 * preview that lays text out by its own arithmetic is a preview that lies as soon as either side is
 * touched. Pure arithmetic in block pixels, so both can scale it into their own units and so it can
 * be tested without a game running.
 *
 * @author LDImmersiveEngineering -- signage
 */
public final class SignLayout
{
	private SignLayout()
	{
	}

	/** How much bare plate is left around the lettering, in block pixels, on each side. */
	public static final float MARGIN = 0.6f;

	/**
	 * The tallest a line may be drawn, in block pixels. Without a ceiling, a one-character line on a
	 * big diamond would be scaled until the letter filled the plate corner to corner, which reads as
	 * a mistake rather than as a sign.
	 */
	public static final float MAX_TEXT_HEIGHT = 5f;

	/** The height of one line of Minecraft's font, in its own pixels. */
	public static final int FONT_HEIGHT = 8;

	/**
	 * Where line {@code index} sits across the plate, measured from the plate's centre and positive
	 * in the direction the lines stack.
	 *
	 * @return the offset in block pixels, or 0 for a kind that carries no text
	 */
	public static float lineCentre(UtilitySignKind kind, int index)
	{
		int lines = kind.getLines();
		if(lines <= 0)
			return 0;
		float depth = kind.getTextDepth();
		return depth*(index+0.5f)/lines-depth/2f;
	}

	/**
	 * How big one line may be drawn, as block pixels per font pixel.
	 * <p>
	 * The smaller of what the plate's length allows and what one line's share of its width allows,
	 * capped so a short string does not grow without limit.
	 *
	 * @param kind        the plate the line is on
	 * @param stringWidth what the font makes of the string, in font pixels
	 *
	 * @return the scale, never zero -- an empty string is not drawn at all rather than divided by
	 */
	public static float scaleFor(UtilitySignKind kind, int stringWidth)
	{
		int lines = Math.max(1, kind.getLines());
		float alongPlate = kind.getTextSpan()-2*MARGIN;
		float perLine = (kind.getTextDepth()-2*MARGIN)/lines;
		float byHeight = Math.min(perLine, MAX_TEXT_HEIGHT)/FONT_HEIGHT;
		if(stringWidth <= 0)
			return byHeight;
		return Math.min(byHeight, alongPlate/stringWidth);
	}
}
