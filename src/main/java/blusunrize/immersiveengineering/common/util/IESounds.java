/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * @author BluSunrize - 03.07.2016
 */
public class IESounds
{
	static Set<SoundEvent> registeredEvents = new HashSet();
	public static SoundEvent metalpress_piston = registerSound("metalPressPiston");
	public static SoundEvent metalpress_smash = registerSound("metalPressSmash");
	public static SoundEvent birthdayParty = registerSound("birthdayParty");
	public static SoundEvent revolverFire = registerSound("revolverFire");
	public static SoundEvent revolverFireThump = registerSound("revolverFireThump");
	public static SoundEvent revolverReload = registerSound("revolverReload");
	public static SoundEvent spray = registerSound("spray");
	public static SoundEvent sprayFire = registerSound("spray_fire");
	public static SoundEvent chargeFast = registerSound("chargeFast");
	public static SoundEvent chargeSlow = registerSound("chargeSlow");
	public static SoundEvent spark = registerSound("spark");
	public static SoundEvent railgunFire = registerSound("railgunFire");
	public static SoundEvent tesla = registerSound("tesla");
	public static SoundEvent crusher = registerSound("crusher");
	public static SoundEvent dieselGenerator = registerSound("dieselGenerator");
	public static SoundEvent direSwitch = registerSound("direSwitch");
	public static SoundEvent chute = registerSound("chute");
	//	=================================
	//		The Hydraulic Crawler
	//	=================================
	//
	// The first three are loops, held open for as long as the machine exists and turned up and down
	// rather than started and stopped -- see CrawlerSound. The last two are single events: one beep
	// of a backup alarm, which the machine repeats on a timer, and one bucket-load going on the
	// floor. All five are generated rather than recorded; docs/tools/make_crawler_sounds.py is the
	// synthesiser and the only thing that has ever written them.
	public static SoundEvent crawlerEngine = registerSound("crawlerEngine");
	public static SoundEvent crawlerTracks = registerSound("crawlerTracks");
	public static SoundEvent crawlerHydraulic = registerSound("crawlerHydraulic");
	public static SoundEvent crawlerBeeper = registerSound("crawlerBeeper");
	public static SoundEvent crawlerDump = registerSound("crawlerDump");

	private static SoundEvent registerSound(String name)
	{
		ResourceLocation location = new ResourceLocation(ImmersiveEngineering.MODID, name);
		SoundEvent event = new SoundEvent(location);
		registeredEvents.add(event.setRegistryName(location));
		return event;
	}

	public static void init()
	{
		for(SoundEvent event : registeredEvents)
			ForgeRegistries.SOUND_EVENTS.register(event);
	}

	public static void PlaySoundForPlayer(Entity player, SoundEvent sound, float volume, float pitch)
	{
		if(player instanceof EntityPlayerMP)
			((EntityPlayerMP)player).connection.sendPacket(new SPacketSoundEffect(sound, player.getSoundCategory(), player.posX, player.posY, player.posZ, volume, pitch));
	}
}
