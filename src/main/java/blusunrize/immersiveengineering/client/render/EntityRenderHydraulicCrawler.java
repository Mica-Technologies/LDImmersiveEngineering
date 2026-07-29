/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.render;

import blusunrize.immersiveengineering.client.models.ModelHydraulicCrawler;
import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * Draws the Hydraulic Crawler.
 * <p>
 * Two rotations, not one, and that is the whole of what makes it read as an excavator: the
 * undercarriage is drawn at the entity's own yaw -- the direction it drives -- and the house is drawn
 * at the slew on top of that. The model owns the second rotation, so this only has to hand over four
 * angles.
 * <p>
 * The slew is interpolated between ticks rather than snapped. It arrives over the network as a synced
 * float, so a machine turning under a distant player would otherwise step round in visible jumps.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class EntityRenderHydraulicCrawler extends Render<EntityHydraulicCrawler>
{
	private static final ResourceLocation TEXTURE =
			new ResourceLocation("immersiveengineering:textures/entity/hydraulic_crawler.png");

	private final ModelHydraulicCrawler model = new ModelHydraulicCrawler();

	public EntityRenderHydraulicCrawler(RenderManager renderManager)
	{
		super(renderManager);
		//A machine this size casts a shadow the size of a house otherwise.
		shadowSize = 1.2F;
	}

	@Override
	public void doRender(EntityHydraulicCrawler crawler, double x, double y, double z,
						 float entityYaw, float partialTicks)
	{
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, z);

		//	=================================
		//	Sixteen units to the block, upside down.
		//	=================================
		//
		// A ModelBase is authored in pixels with -Y as up, which is the convention every vanilla mob
		// model uses. Scaling by -1/16 on Y and Z converts both at once; without it the machine is
		// sixteen blocks tall and standing on its head.
		GlStateManager.scale(0.0625F, -0.0625F, -0.0625F);
		//The undercarriage's heading. The house's slew is applied inside the model, relative to this.
		GlStateManager.rotate(180F-entityYaw, 0, 1, 0);

		bindEntityTexture(crawler);
		model.setPose(
				//Relative to the tracks: the entity's yaw is already in the matrix above, so feeding
				//the absolute slew in here would apply the heading twice and the house would counter-
				//rotate as the machine turned.
				MathHelper.wrapDegrees(interpolate(crawler.prevSlew, crawler.getSlew(), partialTicks)
						-MathHelper.wrapDegrees(crawler.rotationYaw)),
				crawler.getBoomAngle(), crawler.getStickAngle(), crawler.getToolAngle());
		model.render(crawler, 0, 0, 0, 0, 0, 1F);

		GlStateManager.popMatrix();
		super.doRender(crawler, x, y, z, entityYaw, partialTicks);
	}

	/**
	 * Shortest-way-round interpolation between two angles.
	 * <p>
	 * Plain linear interpolation takes the long way when the value wraps -- a house slewing past
	 * north from 179 to -179 degrees would spin all the way round the other way, once per wrap.
	 */
	private float interpolate(float from, float to, float partialTicks)
	{
		return from+MathHelper.wrapDegrees(to-from)*partialTicks;
	}

	@Nullable
	@Override
	protected ResourceLocation getEntityTexture(EntityHydraulicCrawler entity)
	{
		return TEXTURE;
	}
}
