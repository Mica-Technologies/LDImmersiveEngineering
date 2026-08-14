/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.render;

import blusunrize.immersiveengineering.client.models.ModelHydraulicCrawler;
import blusunrize.immersiveengineering.common.entities.CrawlerGeometry;
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
 * <strong>Every angle here is interpolated between ticks, and none of them may be snapped.</strong>
 * The pose arrives over the network as four synced floats that change once a tick, so anything drawn
 * straight from them moves at twenty frames a second on a screen refreshing at several times that --
 * which is not a subtle effect on an arm whose joints step four degrees at a time. The machine's own
 * heading is interpolated for us, by the render manager, and arrives as {@code entityYaw}. So are the
 * two track distances, which are not angles and are interpolated as the plain numbers they are.
 * <p>
 * The model itself is an OBJ read once and drawn a group at a time -- see
 * {@link blusunrize.immersiveengineering.client.models.EntityOBJModel} -- so what used to be a pose
 * set on a box hierarchy is now a single call with everything that moves in its arguments.
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
		//	Sixteen units to the block, upside down, and half again as big.
		//	=================================
		//
		// The model is authored in pixels with -Y as up, which is the convention every vanilla mob
		// model uses and which the OBJ that replaced the box hierarchy kept for exactly this reason.
		// Scaling by -1/16 on Y and Z converts both at once; without it the machine is sixteen blocks
		// tall and standing on its head. CrawlerGeometry.SCALE is the enlargement, and it is shared
		// with the collision box and the seat so the three cannot drift apart.
		//
		// The scale is also a rotation and not a reflection -- its determinant is +1 -- so a face
		// wound counter-clockwise from outside in the model's space is still wound that way in the
		// world, and nothing has to be drawn with culling off to make the machine solid.
		float unit = (float)CrawlerGeometry.UNIT;
		GlStateManager.scale(unit, -unit, -unit);

		//	=================================
		//	The heading, and why it is not 180 minus the yaw.
		//	=================================
		//
		// Vanilla mob renderers use 180-yaw, and copying that here was wrong, because they also negate
		// X where this negates Z. Working the transform through: the arm is model -Z, and with a
		// rotation of A this scale sends it to (-sin A, cos A) in the world -- which is exactly
		// CrawlerGeometry.heading(A). The machine drives along heading(rotationYaw). So A has to be
		// rotationYaw, full stop.
		//
		// With 180-yaw it was heading(180-yaw) = (-sin y, -cos y): the same as the true heading in X
		// and opposite in Z. Not a rotation of the machine -- a reflection of it. Every symptom
		// reported followed from that one line:
		//
		//   * the arm and cab pointed the wrong way, so a seated operator had their back to the arm;
		//   * W and S "did not respect the angle", because the machine drove along its real heading
		//     while being drawn along a reflected one, agreeing at some yaws and opposing at others;
		//   * A and D felt inverted, because a reflection reverses the apparent direction of a turn.
		//
		// It was also worse than a constant error: the house's own slew is applied inside the model
		// relative to this, so the total came to 180 + slew - 2*yaw. The yaw appeared twice, and the
		// arm pointed somewhere unrelated to either the tracks or the operator's view.
		GlStateManager.rotate(entityYaw, 0, 1, 0);

		bindEntityTexture(crawler);
		model.render(
				//	=================================
				//	Relative to the tracks, and to the tracks as they are being drawn.
				//	=================================
				//
				// The entity's yaw is already in the matrix above, so feeding the absolute slew in here
				// would apply the heading twice and the house would counter-rotate as the machine
				// turned. What has to be taken back out of it is therefore exactly what was put in --
				// entityYaw, which the render manager has already interpolated between ticks.
				//
				// It used to subtract crawler.rotationYaw: this tick's heading, against an interpolated
				// one. The difference between the two is a whole tick of steering, so while the machine
				// was turning the house was drawn swinging back and forth across the tracks by up to
				// the turn rate, every frame. Standing still it was exact, which is why it read as the
				// cab shaking only while driving.
				MathHelper.wrapDegrees(interpolate(crawler.prevSlew, crawler.getSlew(), partialTicks)
						-entityYaw),
				//The joints step once a tick, by up to four degrees. Drawn straight from the synced
				//values the arm judders at twenty frames a second; interpolated, it moves.
				interpolate(crawler.prevBoom, crawler.getBoomAngle(), partialTicks),
				interpolate(crawler.prevStick, crawler.getStickAngle(), partialTicks),
				interpolate(crawler.prevTool, crawler.getToolAngle(), partialTicks),
				//The model draws only the attachment that is fitted, and the three are three groups
				//of one file. Nothing else about the machine changes with the tool.
				crawler.getAttachment(),
				//	=================================
				//	The tracks, and why these are not interpolated as angles.
				//	=================================
				//
				// They are distances, not headings: they never wrap, so plain linear interpolation is
				// exactly right and the shortest-way-round version above would be wrong -- it would
				// take any track that had rolled more than half a turn in a tick the other way.
				crawler.prevTrackLeft+(crawler.trackLeft-crawler.prevTrackLeft)*partialTicks,
				crawler.prevTrackRight+(crawler.trackRight-crawler.prevTrackRight)*partialTicks);

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
