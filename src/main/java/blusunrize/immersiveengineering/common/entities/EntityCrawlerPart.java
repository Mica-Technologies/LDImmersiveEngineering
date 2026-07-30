/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import net.minecraft.entity.MultiPartEntityPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;

/**
 * One hitbox of the Hydraulic Crawler's arm.
 * <p>
 * A 1.12 entity has exactly one collision box and it cannot rotate, so a folded arm sweeping across a
 * building is not describable by the machine's own box. These are the Ender Dragon's answer to the same
 * problem: sub-entities the parent repositions every tick, which {@code World}'s entity queries find by
 * walking {@link net.minecraft.entity.Entity#getParts()}. That is how an arrow hits a dragon's wing,
 * and it is how the arm will know what it is touching.
 * <p>
 * <strong>They are not movement colliders, and cannot sensibly be.</strong> Returning a collision box
 * here would make the arm something a player is blocked by -- and a blocking box that then sweeps into
 * somebody leaves them stuck inside geometry, because Minecraft resolves a moving entity against a
 * standing one by simply not letting the standing one in. So the arm passes through players visually
 * and its contact is detected rather than enforced. "Ride in the cab" is a promise; "stand on the boom
 * while it swings" is not.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public class EntityCrawlerPart extends MultiPartEntityPart
{
	public EntityCrawlerPart(EntityHydraulicCrawler parent, String partName, float width, float height)
	{
		super(parent, partName, width, height);
	}

	@Override
	public boolean processInitialInteract(EntityPlayer player, EnumHand hand)
	{
		//Forwarded, so right-clicking the boom is right-clicking the machine. Without this the arm is a
		//dead zone: it is solid to a ray trace, so it swallows the click, and the player concludes the
		//machine has stopped responding rather than that they missed it.
		return parent instanceof EntityHydraulicCrawler
				&&((EntityHydraulicCrawler)parent).processInitialInteract(player, hand);
	}

	@Nullable
	@Override
	public AxisAlignedBB getCollisionBoundingBox()
	{
		//Null on purpose. See the class comment: a moving blocking box is worse than no blocking box.
		return null;
	}

	@Override
	public boolean canBePushed()
	{
		return false;
	}
}
