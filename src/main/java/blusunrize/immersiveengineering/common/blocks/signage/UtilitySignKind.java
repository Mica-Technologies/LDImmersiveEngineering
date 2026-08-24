/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * The thirteen tags a utility pole wears, and what each of them is.
 * <p>
 * <strong>Every one of these is a real sign on a real pole.</strong> The list came from a
 * playtester who reads them for a living -- the shapes, the colours and what each one means are the
 * Los Angeles Department of Water and Power's and Southern California Edison's, not invented. That
 * is the whole argument for building thirteen of them rather than one blank plate: a pole with a
 * yellow strip on it says something different from a pole with a red one, and a grid that is
 * legible from the ground is the point of putting tags on it at all.
 * <p>
 * A kind is <em>geometry plus a text layout</em> and nothing else. It knows how big its plate is,
 * how many lines it carries, what colour they are and which way round they run; it does not know
 * how to draw itself, which is {@code TileRenderUtilitySign}'s job, and it does not know what it is
 * called, which is the lang file's. Keeping it to that is what lets the plate models, the atlas
 * textures, the renderer and the editing window all be generated or driven from one table instead
 * of agreeing with each other by hand.
 * <p>
 * <strong>Constants may only be appended.</strong> The ordinal is what a sign saves.
 *
 * @author LDImmersiveEngineering -- signage
 */
public enum UtilitySignKind implements IStringSerializable
{
	/**
	 * The horizontal red strip. Parallel generation is feeding the distribution station this pole
	 * hangs off -- several sources into one station, or a loop across the area. Mainly a LADWP
	 * sign, and the only one in the set that carries white text.
	 */
	PARALLEL_GENERATION(14, 6, 2, 0xFFFFFF, SignTextFlow.ACROSS, SignShape.RECT, SignDivider.NONE),
	/**
	 * The yellow vertical strip: general pole identification, and the one every utility uses.
	 */
	YELLOW_VERTICAL(6, 14, 1, 0x1A1A1A, SignTextFlow.DOWN, SignShape.RECT, SignDivider.NONE),
	/**
	 * The white vertical strip. SCE hangs these for street lighting; so do privately owned poles,
	 * the City of Long Beach's among them.
	 */
	WHITE_VERTICAL(6, 14, 1, 0x1A1A1A, SignTextFlow.DOWN, SignShape.RECT, SignDivider.NONE),
	/**
	 * The bare metal vertical strip. Mostly replaced by something more legible, and still on
	 * plenty of poles.
	 */
	SILVER_VERTICAL(6, 14, 1, 0x4A4A4A, SignTextFlow.DOWN, SignShape.RECT, SignDivider.NONE),
	/**
	 * The painted white oval, as the City of Lakewood and its neighbours hang on series-wired
	 * street lights: the series number on top and the pole number underneath, over a fraction bar
	 * -- the plate grew two pixels taller than the first cut so the bar has room to sit between
	 * the two without either line losing the height it needs to stay legible.
	 */
	OVAL_FRACTION(12, 10, 2, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.ELLIPSE, SignDivider.FRACTION_BAR),
	/**
	 * The horizontal yellow strip. For the LADWP this is the distribution station, the feeder off
	 * it and how many conduit or transformer bank connections are on that connection; for a light
	 * pole it is the capacitor bank or the phase cutoff. Everybody else uses it for pole numbers.
	 */
	YELLOW_HORIZONTAL(14, 4, 1, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.RECT, SignDivider.NONE),
	/** The horizontal orange strip: fibre and cable runs, at almost every utility. */
	ORANGE_HORIZONTAL(14, 4, 1, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.RECT, SignDivider.NONE),
	/** The same, hung the other way up. */
	ORANGE_VERTICAL(6, 14, 1, 0x1A1A1A, SignTextFlow.DOWN, SignShape.RECT, SignDivider.NONE),
	/**
	 * The round bolt-on inspection tag: who inspected the pole on top, the year underneath, either
	 * side of the nail it is actually hung by -- which sits exactly where the text would otherwise
	 * go, so the plate grew from ten pixels square to twelve to give the two lines room beside it.
	 */
	INSPECTION_ROUND(12, 12, 2, 0x4A4A4A, SignTextFlow.ACROSS, SignShape.ELLIPSE, SignDivider.NAIL),
	/**
	 * The plain yellow diamond the LADWP numbers transmission towers with, roughly every fourth
	 * tower. No border -- the number is painted straight onto it.
	 */
	TOWER_DIAMOND(12, 12, 1, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.DIAMOND, SignDivider.NONE),
	/**
	 * The line crossing diamond: a black outline and a black cross, marking where a line crosses
	 * another, or a span nobody wants to walk -- a valley or a ravine. It carries no text at all,
	 * which is why it is the one kind the editing window opens empty.
	 */
	LINE_CROSSING_DIAMOND(12, 12, 0, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.DIAMOND, SignDivider.NONE),
	/**
	 * The vertical tower identifier, in three parts reading downwards: the initials of the plant
	 * the run starts at, the tower's number, then -- under a rule printed on the plate -- the
	 * initials of the station it ends at.
	 */
	TOWER_VERTICAL(8, 14, 3, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.RECT, SignDivider.TOWER_RULE),
	/**
	 * The horizontal tower identifier, hung on the DC towers of the Pacific Intertie among others.
	 */
	TOWER_HORIZONTAL(14, 4, 1, 0x1A1A1A, SignTextFlow.ACROSS, SignShape.RECT, SignDivider.NONE);

	/**
	 * Cached because {@code values()} allocates, and this is read once per sign per frame by the
	 * renderer.
	 */
	public static final UtilitySignKind[] VALUES = values();

	/** The most lines any kind carries, and therefore how many fields the editor has to offer. */
	public static final int MAX_LINES = 3;

	/** How many characters one line holds. Long enough for "PARALLEL GENERATION", short enough to read. */
	public static final int MAX_LENGTH = 24;

	//Every plate is an even number of pixels across and down, so it lands on whole texture pixels:
	//a half-pixel edge samples between two texels and comes out of the atlas as a blurred fringe,
	//which on a four-pixel strip is most of the sign.
	private final int width;
	private final int height;
	private final int lines;
	private final int textColour;
	private final SignTextFlow flow;
	private final SignShape shape;
	private final SignDivider divider;

	UtilitySignKind(int width, int height, int lines, int textColour, SignTextFlow flow,
					SignShape shape, SignDivider divider)
	{
		this.width = width;
		this.height = height;
		this.lines = lines;
		this.textColour = textColour;
		this.flow = flow;
		this.shape = shape;
		this.divider = divider;
	}

	@Override
	public String getName()
	{
		return name().toLowerCase(Locale.ENGLISH);
	}

	/** @return how wide the plate is, in block pixels */
	public int getWidth()
	{
		return width;
	}

	/** @return how tall the plate is, in block pixels */
	public int getHeight()
	{
		return height;
	}

	/** @return how many lines of text this kind carries, which is 0 for the line crossing diamond */
	public int getLines()
	{
		return lines;
	}

	/** @return what colour those lines are drawn in */
	public int getTextColour()
	{
		return textColour;
	}

	/**
	 * @return which way this plate's lettering runs -- across it, one row per line, or down it, one
	 * upright character per row. See {@link SignTextFlow}.
	 */
	public SignTextFlow getFlow()
	{
		return flow;
	}

	/**
	 * @return true if the text is a column of upright characters read downwards rather than a stack
	 * of lines read across -- worth a name of its own because it is what every draw checks first.
	 * See {@link SignTextFlow#DOWN}.
	 */
	public boolean isStacked()
	{
		return flow.isStacked();
	}

	/**
	 * @return what outline the plate is cut to, which is what says where its lettering may go --
	 * a plate is not the rectangle it is drawn inside. See {@link SignShape}.
	 */
	public SignShape getShape()
	{
		return shape;
	}

	/**
	 * @return what is printed across this plate's middle, if anything at all -- a rule, a fraction
	 * bar, or the nail the round tag is actually hung by. See {@link SignDivider}.
	 */
	public SignDivider getDivider()
	{
		return divider;
	}

	/**
	 * Which line the divider is printed after, for the three kinds that have one.
	 * <p>
	 * The vertical tower tag has a rule two thirds of the way down and the receiving station's
	 * initials go <em>under</em> it; the oval and the round tag split two lines the same way,
	 * either side of a fraction bar or the nail. None of the three are two even shares of the
	 * plate -- laying them out evenly puts a line's letters on top of whatever is actually there.
	 *
	 * @return the index of the last line above the divider, or -1 for a plain plate. There is only
	 * the one table now, on {@link #divider} -- see {@link SignDivider#getAfterLine()}.
	 */
	public int getRuleAfterLine()
	{
		return divider.getAfterLine();
	}

	/**
	 * @return the plate's width, in block pixels. A line that reads across is fitted to this
	 * directly; a stacked column is not -- each of its characters is fitted to this in turn, one
	 * row at a time, rather than the whole run of them being fitted to it at once.
	 */
	public int getTextSpan()
	{
		return width;
	}

	/**
	 * @return the plate's height, in block pixels -- what a stack of lines, or a column of
	 * characters, fits into. Unlike the strips' old rotation, this no longer swaps with
	 * {@link #getTextSpan()}: turning a whole line ninety degrees put its length along the height
	 * and its depth along the width, but a column of upright characters does not turn at all -- it
	 * is still limited by the width and still runs down the height.
	 */
	public int getTextDepth()
	{
		return height;
	}

	/**
	 * @return the kind {@code ordinal} names, or {@link #YELLOW_VERTICAL} for anything out of range
	 * -- which is what a sign whose saved kind was removed comes back as, rather than a crash on
	 * world load
	 */
	public static UtilitySignKind byIndex(int ordinal)
	{
		return ordinal >= 0&&ordinal < VALUES.length?VALUES[ordinal]: YELLOW_VERTICAL;
	}

	/** @return the next kind in the cycle an Engineer's Hammer walks */
	public UtilitySignKind next()
	{
		return VALUES[(ordinal()+1)%VALUES.length];
	}

	/** @return the previous one, which is what a sneaking rotation would have given */
	public UtilitySignKind previous()
	{
		return VALUES[(ordinal()+VALUES.length-1)%VALUES.length];
	}
}
