/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.sound;

import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import net.minecraft.client.audio.ITickableSound;
import net.minecraft.client.audio.Sound;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.ToDoubleFunction;

/**
 * One of the Hydraulic Crawler's running loops: the engine, the tracks, or the hydraulics.
 * <p>
 * <strong>Three loops rather than one file, because they are three independent things.</strong> A
 * machine standing still with its arm out is an engine and a pump and no tracks; one crossing a
 * field with the arm parked is an engine and tracks and no pump. Baked into a single loop none of
 * that can be heard, and what a playtester asked for -- "hydraulic noises, track noises" -- is
 * exactly the ability to hear which of them the machine is doing.
 * <p>
 * <strong>They start once and run until the machine is gone.</strong> Each is a repeating stream
 * whose volume is recomputed every client tick from the machine itself, so a loop that is not
 * wanted is turned down rather than stopped and restarted. Stopping and restarting is audible --
 * the loop begins from its first sample each time, which puts a fresh combustion pulse or a fresh
 * clank wherever the machine happened to start moving -- and it is also a rattle of allocations on
 * something that changes state several times a second.
 * <p>
 * The volume never reaches exactly zero, and that is not an accident: {@code SoundManager} refuses
 * to start a sound whose volume is zero at the moment it is handed over, which for a machine sitting
 * still when it comes into view would mean silence until something restarted it. A thousandth is
 * inaudible at any distance and is still not zero.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public class CrawlerSound implements ITickableSound
{
	/** Quiet enough to be inaudible, loud enough that the sound engine will start it. See above. */
	private static final float FLOOR = 0.001F;

	private final EntityHydraulicCrawler crawler;
	private final ResourceLocation location;
	private final ToDoubleFunction<EntityHydraulicCrawler> volumeOf;
	private final ToDoubleFunction<EntityHydraulicCrawler> pitchOf;
	private Sound sound;
	private float volume = FLOOR;
	private float pitch = 1;

	public CrawlerSound(EntityHydraulicCrawler crawler, SoundEvent event,
						ToDoubleFunction<EntityHydraulicCrawler> volumeOf,
						ToDoubleFunction<EntityHydraulicCrawler> pitchOf)
	{
		this.crawler = crawler;
		this.location = event.getSoundName();
		this.volumeOf = volumeOf;
		this.pitchOf = pitchOf;
		//Sampled here as well as in update(), because the sound engine reads the volume once before
		//it will agree to play anything at all.
		update();
	}

	@Override
	public void update()
	{
		volume = Math.max(FLOOR, (float)volumeOf.applyAsDouble(crawler));
		pitch = (float)pitchOf.applyAsDouble(crawler);
	}

	@Override
	public boolean isDonePlaying()
	{
		//The one condition that ends it. A machine that has been packed back into its item is dead,
		//and a machine that has merely been parked and walked away from is not -- it is still idling,
		//and walking back towards it should find it doing so.
		return crawler.isDead;
	}

	@Nonnull
	@Override
	public ResourceLocation getSoundLocation()
	{
		return location;
	}

	@Nullable
	@Override
	public SoundEventAccessor createAccessor(@Nonnull SoundHandler handler)
	{
		SoundEventAccessor accessor = handler.getAccessor(location);
		sound = accessor==null?SoundHandler.MISSING_SOUND: accessor.cloneEntry();
		return accessor;
	}

	@Nonnull
	@Override
	public Sound getSound()
	{
		return sound;
	}

	@Nonnull
	@Override
	public SoundCategory getCategory()
	{
		//Neutral rather than blocks: this is a vehicle, and somebody who has turned the block volume
		//down to work in a factory has not asked to stop hearing the machine they are driving.
		return SoundCategory.NEUTRAL;
	}

	@Override
	public boolean canRepeat()
	{
		return true;
	}

	@Override
	public int getRepeatDelay()
	{
		//Nothing between one pass of the loop and the next. The files are generated to wrap without
		//a discontinuity, so any delay here would be an audible hole rather than a rest.
		return 0;
	}

	@Override
	public float getVolume()
	{
		return volume;
	}

	@Override
	public float getPitch()
	{
		return pitch;
	}

	@Override
	public float getXPosF()
	{
		return (float)crawler.posX;
	}

	@Override
	public float getYPosF()
	{
		return (float)crawler.posY;
	}

	@Override
	public float getZPosF()
	{
		return (float)crawler.posZ;
	}

	@Nonnull
	@Override
	public AttenuationType getAttenuationType()
	{
		return AttenuationType.LINEAR;
	}
}
