/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

/**
 * What is fitted to the end of a Hydraulic Crawler's arm.
 * <p>
 * Ordinals are persisted in the entity's NBT and sent over the network, so constants may only be
 * appended -- the same rule block metadata and entity registration ids live under, and the same
 * silence when it is broken: a reordering does not fail, it simply fits everybody's machine with a
 * different tool than it had yesterday.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public enum CrawlerAttachment
{
	/** Digs, and keeps what it digs. */
	BUCKET("bucket", true),
	/** Picks one block up and puts it down again, and carries whatever it catches. */
	GRAPPLE("grapple", false),
	/** Destroys what it touches, and drops it on the floor. */
	BREAKER("breaker", true);

	public static final CrawlerAttachment[] VALUES = values();

	private final String name;
	private final boolean breaksBlocks;

	CrawlerAttachment(String name, boolean breaksBlocks)
	{
		this.name = name;
		this.breaksBlocks = breaksBlocks;
	}

	public String getName()
	{
		return name;
	}

	/**
	 * @return whether pulling the trigger with this fitted destroys blocks
	 */
	public boolean breaksBlocks()
	{
		return breaksBlocks;
	}

	/**
	 * @return whether what it breaks is kept rather than dropped
	 * <p>
	 * The one difference between the Bucket and the Breaker, and it is the whole difference: both take
	 * a block out of the world, and only one of them expects you to want it. Somebody demolishing a
	 * house does not want its cobblestone; somebody digging a foundation does.
	 */
	public boolean keepsDrops()
	{
		return this==BUCKET;
	}

	public String getTranslationKey()
	{
		return "gui.immersiveengineering.crawler.attachment."+name;
	}

	/**
	 * @param ordinal off disk or off the network, and therefore not to be trusted
	 */
	public static CrawlerAttachment byOrdinal(int ordinal)
	{
		//Anything unrecognised comes back as the bucket rather than throwing: an ordinal from a future
		//version, or a corrupt tag, should leave somebody with a working machine and the wrong tool
		//fitted, not a crash on chunk load.
		return ordinal >= 0&&ordinal < VALUES.length?VALUES[ordinal]: BUCKET;
	}

	public CrawlerAttachment next()
	{
		return VALUES[(ordinal()+1)%VALUES.length];
	}
}
