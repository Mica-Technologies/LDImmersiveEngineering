/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

/**
 * Where the demo boulevard's stations stand, and what their signs say.
 * <p>
 * World-free on purpose. Everything {@code /ie demo build} places is arithmetic on an origin plus a
 * table of strings, and both of those can be wrong in ways a compiler never notices: two stations
 * that overlap build into each other, and a sign line of sixteen characters is simply truncated by
 * the game with nothing logged. Keeping the arithmetic and the text here lets {@code DemoLayoutTest}
 * assert both without a Minecraft bootstrap -- which is the only kind of test this repository can
 * run.
 * <p>
 * The boulevard runs along <strong>+X</strong>. Each station is centred on its own X, the signs
 * stand in a row on the south side of it ({@link #SIGN_ROW_Z}) facing the walkway, and the hardware
 * is built to the north of that row. A station may use {@code dx} from -5 to +5 and {@code dz} from
 * -8 to +2; anything wider would reach into its neighbour, which is what {@link #SPACING} and the
 * test that guards it are for.
 *
 * @author LDImmersiveEngineering -- demo command
 */
public final class DemoLayout
{
	private DemoLayout()
	{
	}

	/** Blocks between one station's centre and the next, along +X. */
	public static final int SPACING = 12;

	/** How far the stone platform reaches past the first and last station. */
	public static final int MARGIN = 6;

	/** How far the platform reaches either side of the boulevard's centre line. */
	public static final int HALF_WIDTH = 8;

	/** Solid layers below the floor, so a station may carve a pit or a shaft into it. */
	public static final int DEPTH = 4;

	/** Air layers above the floor. The tallest station is nine high; the rest is headroom. */
	public static final int HEADROOM = 14;

	/** The sign row's Z, relative to the boulevard's centre line. Positive is south. */
	public static final int SIGN_ROW_Z = 3;

	/** The X of a station's leftmost sign, relative to the station's centre. */
	public static final int SIGN_ROW_START_X = -3;

	/**
	 * Signs one station may carry. Six is what fits between {@link #SIGN_ROW_START_X} and the
	 * point at which the row would run into the next station's.
	 */
	public static final int MAX_SIGNS = 6;

	/** Lines on one sign. Vanilla's, and not negotiable. */
	public static final int SIGN_LINES = 4;

	/**
	 * Characters that fit on a sign line at the default font.
	 * <p>
	 * Vanilla's editor allows more and the renderer silently squeezes them, so a line written too
	 * long does not fail -- it just becomes unreadable in the one place the whole feature is
	 * explained. Hence a test rather than a comment.
	 */
	public static final int SIGN_LINE_LENGTH = 15;

	/**
	 * One stop on the boulevard, in the order they are walked.
	 * <p>
	 * The first sign of each is its title board; the rest explain what to look at. Order matters:
	 * {@code ordinal()} is the station's position along +X, so inserting one in the middle moves
	 * everything after it.
	 */
	public enum Station
	{
		CONDUIT_BASICS("Conduit basics", new String[][]{
				{"[ 1 ]", "CONDUIT", "BASICS", ""},
				{"Creative cap ->", "box -> run ->", "box -> LV cap.", "The cap fills."},
				{"A patched face", "wears a plate", "in its channel", "colour."},
				{"Dye a face to", "break a channel", "out to it.", ""}}),
		CONDUIT_CORNERS("Conduit corners", new String[][]{
				{"[ 2 ]", "CONDUIT", "CORNERS", ""},
				{"Left: inner.", "A floor run", "meets a wall", "and climbs it."},
				{"It needs a", "block in the", "corner to turn", "around."},
				{"Right: outer.", "A run leaves", "the shelf edge", "and turns down."},
				{"Both without", "a junction box", "anywhere in", "the corner."}}),
		ADJACENT_BOXES("Junction boxes side by side", new String[][]{
				{"[ 3 ]", "BOXES SIDE", "BY SIDE", ""},
				{"Three boxes", "touching, with", "a comparator", "and a hopper."},
				{"This used to", "hang the whole", "server inside", "one tick."},
				{"Now they idle.", "Break and", "replace them:", "still smooth."}}),
		GROUND_FEEDER("Ground feeder", new String[][]{
				{"[ 4 ]", "GROUND", "FEEDER", ""},
				{"A run drops", "through the", "floor with no", "box showing."},
				{"The feeder", "wears whatever", "is around it.", "See the pit."},
				{"A hammer turns", "its axis.", "Right-click a", "block to pin."}}),
		WIRE_TO_BOX("Wire straight to a box", new String[][]{
				{"[ 5 ]", "WIRE TO A", "JUNCTION BOX", ""},
				{"An LV wire", "goes straight", "to a box face.", "No connector."},
				{"One wire per", "face, so six", "faces are six", "circuits."},
				{"West face is", "fed; east face", "feeds the LV", "capacitor."}}),
		CITY_MODE_MACHINE("City-mode squeezer", new String[][]{
				{"[ 6 ]", "CITY MODE", "SQUEEZER", ""},
				{"Portable gen ->", "LV wire ->", "squeezer, with", "seeds hoppered."},
				{"City mode: any", "supply runs it", "at full speed.", ""},
				{"The lever cuts", "it. Watch the", "buffer follow", "the lever."}}),
		WIRED_BEFORE_FORMED("Fermenter wired before forming", new String[][]{
				{"[ 7 ]", "WIRED BEFORE", "IT FORMED", ""},
				{"The wire was", "strung first,", "then the rig", "was formed."},
				{"That used to", "leave it dead", "once its buffer", "ran out."},
				{"Now a connector", "re-checks, and", "the reeds turn", "into ethanol."}}),
		VIRTUAL_GRID("Virtual power grid", new String[][]{
				{"[ 8 ]", "VIRTUAL", "POWER GRID", ""},
				{"Console on the", "wall. Feed unit", "on the creative", "capacitor."},
				{"Service unit", "fills the LV", "capacitor with", "no wire at all."},
				{"Signal unit", "bridges the", "segment to", "redstone."},
				{"Segment: demo", "Try /ie grid", "list, info,", "off and on."}}),
		FLUID_NETWORK("Virtual fluid network", new String[][]{
				{"[ 9 ]", "VIRTUAL", "FLUID NET", ""},
				{"Barrel -> inlet", "-> main ->", "outlet -> keg,", "with no pipe."},
				{"A main carries", "one fluid. It", "takes it from", "its 1st inlet."},
				{"Main:", "demo water", "Try /ie", "fluidnet list."}}),
		PETROLEUM("Petroleum row", new String[][]{
				{"[ 10 ]", "PETROLEUM", "", ""},
				{"Portable gen,", "gas pump,", "propane tanks", "and a wellhead."},
				{"Hammer the gas", "pump's lower", "block to", "assemble it."},
				{"The wellhead", "needs an oil", "reservoir; see", "/ie reservoir."}}),
		CRAWLER("Hydraulic crawler", new String[][]{
				{"[ 11 ]", "HYDRAULIC", "CRAWLER", ""},
				{"Right-click to", "climb in.", "W/S throttle,", "A/D steer."},
				{"The mouse slews", "the house. R/C", "raise, X/Z", "extend the arm."},
				{"V works the", "tool, sneak+V", "tips it, G", "swaps it."},
				{"Sneak + hammer", "puts it back", "in your pocket,", "diesel and all."}}),
		FIXES("What was fixed", new String[][]{
				{"[ 12 ]", "WHAT WAS", "FIXED", ""},
				{"Everything on", "this street is", "new or newly", "repaired."},
				{"The board", "behind lists", "what changed", "since the last"},
				{"build. Each", "line has a", "station here", "to match it."}});

		private final String title;
		private final String[][] signs;

		Station(String title, String[][] signs)
		{
			this.title = title;
			this.signs = signs;
		}

		/** A human name for the chat report. Not what goes on the sign. */
		public String getTitle()
		{
			return title;
		}

		public String[][] getSigns()
		{
			return signs;
		}

		public static final Station[] VALUES = values();
	}

	/**
	 * The wall board at the last station: six wall signs, two rows of three, on a stone backing.
	 * <p>
	 * Separate from the station's own row because it is a different piece of hardware -- wall signs
	 * on a wall rather than standing signs in the ground -- and because six more entries in the
	 * station's list would push the row into the next station.
	 */
	public static final String[][] FIXES_BOARD = {
			{"CONDUIT FREEZE", "Two boxes side", "by side looped", "a server tick."},
			{"BLOCK STATES", "73,728 -> 18", "by moving shape", "to the tile."},
			{"STALE ARMS", "A run reloaded", "with a stub in", "it. Now heals."},
			{"WIRE, THEN", "FORM. A source", "cached as dead", "re-checks now."},
			{"CITY MODE", "Presence, not", "accounting; see", "stations 6-7."},
			{"READOUTS", "Once a second,", "not once a", "frame."}};

	/**
	 * @return the X a station is centred on
	 */
	public static int stationX(int originX, int index)
	{
		return originX+index*SPACING;
	}

	public static int stationCount()
	{
		return Station.VALUES.length;
	}

	public static int minX(int originX)
	{
		return originX-MARGIN;
	}

	public static int maxX(int originX)
	{
		return stationX(originX, stationCount()-1)+MARGIN;
	}

	public static int minZ(int originZ)
	{
		return originZ-HALF_WIDTH;
	}

	public static int maxZ(int originZ)
	{
		return originZ+HALF_WIDTH;
	}

	/**
	 * @param groundY the floor's top solid layer
	 */
	public static int minY(int groundY)
	{
		return groundY-DEPTH;
	}

	public static int maxY(int groundY)
	{
		return groundY+HEADROOM;
	}
}
