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
 * first -- the plate it has to fit along, or the share of the plate's depth one line of a stack gets
 * -- and a short number comes out big while a long one comes out small, exactly as they do on a
 * pole.
 * <p>
 * <strong>Filling the plate means filling the paint, not the sprite.</strong> Three things this has
 * to know, and each of them was a line of text sitting on top of a border in a screenshot:
 * <ul>
 * <li>the painted outline is not printable plate, so every fit starts by insetting the plate by
 * {@link SignShape#getInset()};</li>
 * <li>a plate is not the rectangle it is drawn inside -- an oval or a diamond narrows away from its
 * middle, so a line is fitted to how much plate is still there at the height its tallest letter
 * reaches, which is {@link SignShape#spanAt};</li>
 * <li>a glyph cell is eight pixels tall and a capital paints seven of them, so a line centred and
 * scaled by the cell sits half a pixel high and stands taller than it looks. Everything here is in
 * {@link #INK_HEIGHT}, and both things that draw a line offset it by that too.</li>
 * </ul>
 * The result is a box of lettering inscribed in the plate and held {@link #PADDING} off it, so that
 * on the round and pointed kinds its corners come near the paint's edge and on the rectangular ones
 * its sides come near the inside of the outline, and neither ever reaches it.
 * <p>
 * A plate with a rule printed across it -- the vertical tower tag -- divides its lines either side
 * of the rule rather than laying them over it.
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

	/**
	 * The tallest a line may be drawn, in block pixels of painted letter. Without a ceiling, a
	 * one-character line on a big diamond would be scaled until the letter filled the plate corner
	 * to corner, which reads as a mistake rather than as a sign.
	 */
	public static final float MAX_TEXT_HEIGHT = 5f;

	/**
	 * How much of the plate inside its border the lettering is held off it by, as a fraction of
	 * each half-extent.
	 * <p>
	 * Text that stops exactly on the inside of the outline is on the plate and still looks wrong:
	 * it reads as a line that was too big and got cropped rather than as one somebody painted. A
	 * tenth of the way in on every side is the gap, and taking it as a fraction rather than as a
	 * number of pixels is what keeps it from eating a four-pixel strip, which has two pixels of
	 * plate inside its border and no more.
	 */
	public static final float PADDING = 0.1f;

	/**
	 * How much of its share of the plate a line of a stack actually paints.
	 * <p>
	 * Without it a stack fills its plate exactly and the lines meet: the bottom row of one
	 * capital against the top row of the next, which reads as one smudged block of ink rather
	 * than as two numbers. A tenth of the slot is the gap, and it costs a tenth of the letter.
	 */
	public static final float LEADING = 0.9f;

	/** The height of one glyph cell of Minecraft's font, in its own pixels. */
	public static final int FONT_HEIGHT = 8;

	/**
	 * How many of those a capital or a digit actually paints.
	 * <p>
	 * The cell's eighth row is the descender, empty for everything a utility tag is lettered in, so
	 * a line scaled and centred by the cell comes out half a pixel high and half a pixel taller than
	 * the plate was measured for. Sizing by the ink instead is why these fit.
	 */
	public static final int INK_HEIGHT = 7;

	/**
	 * What a string actually paints, given what the font makes of it.
	 * <p>
	 * {@code getStringWidth} counts the one-pixel gap that follows the last character as well, which
	 * on a plate fitted to the pixel shows up as text pushed left of centre.
	 *
	 * @return the painted width in font pixels, never below zero
	 */
	public static float inkWidth(int stringWidth)
	{
		return Math.max(0f, stringWidth-1);
	}

	/** @return half the plate's printable extent along the text, in block pixels */
	public static float halfSpan(UtilitySignKind kind)
	{
		return printable(kind.getTextSpan(), kind.getShape().getInset());
	}

	/** @return half its printable extent across the text */
	public static float halfDepth(UtilitySignKind kind)
	{
		return printable(kind.getTextDepth(), kind.getShape().getInset());
	}

	/** Half a plate extent, less its painted border and less the gap the paint is held off it by. */
	private static float printable(int extent, float border)
	{
		return Math.max(0f, (extent/2f-border)*(1-PADDING));
	}

	/**
	 * The middle of a plate's divider, measured from the plate's centre and positive in the
	 * direction the lines stack.
	 * <p>
	 * Exact rather than rounded to the pixel, and that is the point of it. The lines are divided
	 * around the <em>middle</em> of whatever is printed there and not around its top edge: dividing
	 * around the top gave the line below the divider the divider's whole thickness less room than
	 * the line above it, which on a fraction bar meant a series number printed half again the size
	 * of the pole number under it. A fraction is two numbers the same size, and a plate whose
	 * divider is in the middle should have two even halves.
	 *
	 * @return the offset in block pixels, or {@link Float#NaN} for a plate that has none
	 */
	public static float dividerCentre(UtilitySignKind kind)
	{
		SignDivider divider = kind.getDivider();
		if(divider.getAfterLine() < 0)
			return Float.NaN;
		int depth = kind.getTextDepth();
		return depth*divider.getFraction()-depth/2f;
	}

	/**
	 * Which row of the sprite the divider's first pixel is painted on, counted from the top of the
	 * plate.
	 * <p>
	 * <strong>Stated here because the generator has to agree with it, and because everything else
	 * about a divider is measured from it.</strong> The plate artwork is drawn in Python and the
	 * lettering is laid out here; both round {@code floor(depth*fraction - thickness/2 + 0.5)} to
	 * the same pixel, and a test reads the row back off the finished sprite rather than trusting
	 * either of them. Rounding the divider's <em>middle</em> to a whole row and then measuring the
	 * band off that row -- rather than reserving a band about the exact fraction and painting near
	 * it -- is what keeps the paint and the gap in the paint the same thing to within nothing at
	 * all. Half a pixel of disagreement is a bar with a letter resting on its lower edge.
	 *
	 * @return the row, or -1 for a plate with no divider
	 */
	public static int dividerRow(UtilitySignKind kind)
	{
		SignDivider divider = kind.getDivider();
		if(divider.getAfterLine() < 0)
			return -1;
		int depth = kind.getTextDepth();
		return (int)Math.floor(depth*divider.getFraction()-divider.getThickness()/2f+0.5f);
	}

	/**
	 * Where a plate's divider begins, measured from the plate's centre and positive in the
	 * direction the lines stack: the top edge of the row it is actually painted on.
	 *
	 * @return the near edge of the divider, or {@link Float#NaN} for a plate that has none
	 */
	public static float ruleEdge(UtilitySignKind kind)
	{
		int row = dividerRow(kind);
		return row < 0?Float.NaN: row-kind.getTextDepth()/2f;
	}

	/**
	 * The band of plate line {@code index} is laid out in, as {@code {start, end}} from the plate's
	 * centre.
	 * <p>
	 * One band for most kinds -- the whole printable depth, or the inscribed box's share of it on a
	 * shape that narrows away from its middle. Two for a plate with a rule printed across it, whose
	 * lines are divided either side of the rule rather than laid over it.
	 */
	private static float[] band(UtilitySignKind kind, int index)
	{
		float half = halfDepth(kind)*kind.getShape().getStackFactor();
		SignDivider divider = kind.getDivider();
		int rule = divider.getAfterLine();
		if(rule < 0)
			return new float[]{-half, half};
		float edge = ruleEdge(kind);
		return index <= rule?new float[]{-half, edge}: new float[]{edge+divider.getThickness(), half};
	}

	/** Which line of its own band {@code index} is, and how many lines that band holds. */
	private static int slotOf(UtilitySignKind kind, int index)
	{
		int rule = kind.getRuleAfterLine();
		return rule < 0||index <= rule?index: index-rule-1;
	}

	private static int slotsIn(UtilitySignKind kind, int index)
	{
		int rule = kind.getRuleAfterLine();
		if(rule < 0)
			return Math.max(1, kind.getLines());
		return Math.max(1, index <= rule?rule+1: kind.getLines()-rule-1);
	}

	/**
	 * Where line {@code index} sits across the plate, measured from the plate's centre and positive
	 * in the direction the lines stack.
	 *
	 * @return the offset in block pixels, or 0 for a kind that carries no text
	 */
	public static float lineCentre(UtilitySignKind kind, int index)
	{
		if(kind.getLines() <= 0)
			return 0;
		float[] band = band(kind, index);
		return band[0]+(band[1]-band[0])*(slotOf(kind, index)+0.5f)/slotsIn(kind, index);
	}

	/**
	 * How tall line {@code index}'s lettering may be drawn, in block pixels -- its share of the
	 * band it sits in, less the gap that keeps a stack of lines from meeting, capped so a short
	 * string does not grow without limit.
	 */
	public static float lineHeight(UtilitySignKind kind, int index)
	{
		float[] band = band(kind, index);
		float slot = (band[1]-band[0])/slotsIn(kind, index);
		if(kind.getLines() > 1)
			slot *= LEADING;
		return Math.max(0f, Math.min(slot, MAX_TEXT_HEIGHT));
	}

	/**
	 * How wide line {@code index} may be drawn, in block pixels: how much plate is left where that
	 * line's letters reach furthest from the middle. On a rectangle that is the whole width; on an
	 * oval or a diamond it is the width at the top of the line rather than at its centre, which is
	 * the difference between a number that fits and one that hangs off the paint.
	 */
	public static float lineSpan(UtilitySignKind kind, int index)
	{
		float reach = Math.abs(lineCentre(kind, index))+lineHeight(kind, index)/2f;
		return kind.getShape().spanAt(halfSpan(kind), halfDepth(kind), reach);
	}

	/**
	 * How big line {@code index} may be drawn, as block pixels per font pixel.
	 * <p>
	 * The smaller of what that line's share of the plate's depth allows and what the plate's width
	 * allows there.
	 *
	 * @param kind        the plate the line is on
	 * @param index       which line of the stack it is
	 * @param stringWidth what the font makes of the string, in font pixels
	 *
	 * @return the scale, never zero -- an empty string is not drawn at all rather than divided by
	 */
	public static float scaleFor(UtilitySignKind kind, int index, int stringWidth)
	{
		float byHeight = lineHeight(kind, index)/INK_HEIGHT;
		float ink = inkWidth(stringWidth);
		if(ink <= 0)
			return byHeight;
		return Math.min(byHeight, lineSpan(kind, index)/ink);
	}

	/**
	 * How much depth one row of a stacked column gets, in block pixels.
	 * <p>
	 * The same idea as a line's share of a stack, and for the same reason: the printable depth,
	 * narrowed by the shape's stack factor so a column pushed to the top of an oval or a diamond
	 * still has plate beside it, divided evenly between however many characters somebody typed.
	 * A stacked kind carries no divider, so there is no rule to split the band around -- one row
	 * gets a plain, even share.
	 *
	 * @return the slot's depth, never zero -- an empty column divides by one row rather than by
	 * zero characters
	 */
	public static float stackedSlot(UtilitySignKind kind, int count)
	{
		return 2*halfDepth(kind)*kind.getShape().getStackFactor()/Math.max(1, count);
	}

	/**
	 * Where row {@code index} of a stacked column of {@code count} characters sits, measured from
	 * the plate's centre and positive in the direction the rows stack -- downwards, on every kind
	 * that flows this way. The same arithmetic as {@link #lineCentre}, without a divider to split
	 * around.
	 */
	public static float stackedCentre(UtilitySignKind kind, int index, int count)
	{
		float half = halfDepth(kind)*kind.getShape().getStackFactor();
		return -half+stackedSlot(kind, count)*(index+0.5f);
	}

	/**
	 * The one scale every character of a stacked column is drawn at.
	 * <p>
	 * A printed column is uniform -- every digit of a pole number the same size as its neighbours
	 * -- so unlike a line, which is scaled to its own string, a stacked column is scaled once and
	 * every character drawn at that. It is the smaller of what a row's depth allows and what the
	 * plate's width allows at the row that reaches furthest from the middle, which by symmetry is
	 * row 0 (and equally row {@code count-1}): the same two limits {@link #scaleFor} weighs, read
	 * off the row rather than off the whole line.
	 *
	 * @param kind             the plate the column is on
	 * @param count            how many characters are in it
	 * @param widestCharWidth  the widest any one of them is, in font pixels -- not the width of the
	 *                         whole string, because each character is centred and scaled on its own
	 *
	 * @return the scale, never zero
	 */
	public static float stackedScale(UtilitySignKind kind, int count, int widestCharWidth)
	{
		float rowHeight = Math.min(stackedSlot(kind, count)*LEADING, MAX_TEXT_HEIGHT);
		float byHeight = rowHeight/INK_HEIGHT;
		float ink = inkWidth(widestCharWidth);
		if(ink <= 0)
			return byHeight;
		float reach = Math.abs(stackedCentre(kind, 0, count))+rowHeight/2f;
		float span = kind.getShape().spanAt(halfSpan(kind), halfDepth(kind), reach);
		return Math.min(byHeight, span/ink);
	}
}
