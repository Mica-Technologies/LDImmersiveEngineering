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
	PARALLEL_GENERATION(14, 6, 2, 0xFFFFFF, false),
	/**
	 * The yellow vertical strip: general pole identification, and the one every utility uses.
	 */
	YELLOW_VERTICAL(6, 14, 1, 0x1A1A1A, true),
	/**
	 * The white vertical strip. SCE hangs these for street lighting; so do privately owned poles,
	 * the City of Long Beach's among them.
	 */
	WHITE_VERTICAL(6, 14, 1, 0x1A1A1A, true),
	/**
	 * The bare metal vertical strip. Mostly replaced by something more legible, and still on
	 * plenty of poles.
	 */
	SILVER_VERTICAL(6, 14, 1, 0x4A4A4A, true),
	/**
	 * The painted white oval, as the City of Lakewood and its neighbours hang on series-wired
	 * street lights: the series number on top and the pole number underneath.
	 */
	OVAL_FRACTION(12, 8, 2, 0x1A1A1A, false),
	/**
	 * The horizontal yellow strip. For the LADWP this is the distribution station, the feeder off
	 * it and how many conduit or transformer bank connections are on that connection; for a light
	 * pole it is the capacitor bank or the phase cutoff. Everybody else uses it for pole numbers.
	 */
	YELLOW_HORIZONTAL(14, 4, 1, 0x1A1A1A, false),
	/** The horizontal orange strip: fibre and cable runs, at almost every utility. */
	ORANGE_HORIZONTAL(14, 4, 1, 0x1A1A1A, false),
	/** The same, hung the other way up. */
	ORANGE_VERTICAL(6, 14, 1, 0x1A1A1A, true),
	/**
	 * The round bolt-on inspection tag: who inspected the pole on top, the year underneath. It also
	 * does duty as the tag saying where a wooden pole was logged.
	 */
	INSPECTION_ROUND(10, 10, 2, 0x4A4A4A, false),
	/**
	 * The plain yellow diamond the LADWP numbers transmission towers with, roughly every fourth
	 * tower. No border -- the number is painted straight onto it.
	 */
	TOWER_DIAMOND(12, 12, 1, 0x1A1A1A, false),
	/**
	 * The line crossing diamond: a black outline and a black cross, marking where a line crosses
	 * another, or a span nobody wants to walk -- a valley or a ravine. It carries no text at all,
	 * which is why it is the one kind the editing window opens empty.
	 */
	LINE_CROSSING_DIAMOND(12, 12, 0, 0x1A1A1A, false),
	/**
	 * The vertical tower identifier, in three parts reading downwards: the initials of the plant
	 * the run starts at, the tower's number, then -- under a rule printed on the plate -- the
	 * initials of the station it ends at.
	 */
	TOWER_VERTICAL(8, 14, 3, 0x1A1A1A, false),
	/**
	 * The horizontal tower identifier, hung on the DC towers of the Pacific Intertie among others.
	 */
	TOWER_HORIZONTAL(14, 4, 1, 0x1A1A1A, false);

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
	private final boolean rotated;

	UtilitySignKind(int width, int height, int lines, int textColour, boolean rotated)
	{
		this.width = width;
		this.height = height;
		this.lines = lines;
		this.textColour = textColour;
		this.rotated = rotated;
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
	 * @return true if the text runs along the plate rather than across it -- which is what a strip
	 * six pixels wide and fourteen tall has to do to hold "M31390V" at all, and is how the real
	 * ones are printed
	 */
	public boolean isRotated()
	{
		return rotated;
	}

	/**
	 * @return the length of the plate along the text's own direction, in block pixels. The renderer
	 * fits a line to this, and the editor uses it for the same reason.
	 */
	public int getTextSpan()
	{
		return rotated?height: width;
	}

	/** @return the plate's extent across the text's direction -- what the stack of lines fits into */
	public int getTextDepth()
	{
		return rotated?width: height;
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
