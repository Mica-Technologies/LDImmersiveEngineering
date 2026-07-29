/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The Hydraulic Crawler's body: tracks, a house that slews on them, and a three-piece arm.
 * <p>
 * A hand-built {@link ModelBase} rather than an OBJ, and not for want of preferring the nicer option:
 * the arm has to <em>move</em>, and a hierarchy of {@link ModelRenderer} boxes is the only thing in
 * 1.12 that animates per part without writing a bespoke renderer. IE's OBJ support is for blocks.
 * <p>
 * <strong>The hierarchy is the machine.</strong> Each joint is a child of the piece it is bolted to,
 * so rotating the boom carries the stick and the tool with it, exactly as steel would. Getting that
 * wrong gives an arm whose sections come apart in mid-air; getting it right means the only thing the
 * renderer has to supply is four angles.
 * <p>
 * Built at sixteen units to the block, as every Minecraft model is, so the numbers read as pixels:
 * the machine is 48 across, 56 long over the tracks, and reaches some seven blocks with the arm out.
 * <p>
 * <strong>Every texture offset here is owned by {@code docs/tools/make_crawler_textures.py}.</strong>
 * Its {@code REGIONS} table is this list, and it asserts the regions neither overlap nor run off the
 * sheet -- which is worth having, because nothing at runtime complains when two boxes share pixels.
 * It simply paints the tracks with the cab's glazing.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class ModelHydraulicCrawler extends ModelBase
{
	/**
	 * The arm's section lengths.
	 * <p>
	 * Public because the geometry that decides where the bucket <em>is</em> has to agree with the
	 * picture of where it looks. A solver working from different lengths than the model draws would
	 * destroy blocks the player is not pointing at, which is the one failure in this feature nobody
	 * would forgive.
	 */
	public static final float BOOM_LENGTH = 26F;
	public static final float STICK_LENGTH = 20F;
	public static final float TOOL_LENGTH = 10F;

	/** Where the boom is pinned to the house. */
	public static final float BOOM_PIVOT_X = -5F;
	public static final float BOOM_PIVOT_Y = -10F;
	public static final float BOOM_PIVOT_Z = -16F;

	/** Height of the slew ring above the entity's origin, in pixels. The house turns about this. */
	public static final float SLEW_HEIGHT = -14F;

	/** Never moves relative to the entity: the tracks and what sits between them. */
	private final ModelRenderer undercarriage;
	/** Everything that slews: engine deck, counterweight, cab, and the arm hanging off the front. */
	private final ModelRenderer house;
	private final ModelRenderer boom;
	private final ModelRenderer stick;
	private final ModelRenderer tool;

	public ModelHydraulicCrawler()
	{
		textureWidth = 256;
		textureHeight = 256;

		//	=================================
		//		UNDERCARRIAGE
		//	=================================
		//An empty parent, so the whole machine has one root to rotate and the tracks are siblings of
		//the house rather than its owner.
		undercarriage = new ModelRenderer(this);
		undercarriage.setRotationPoint(0, 0, 0);

		//Both tracks share one texture region: they are the same object twice, and painting it twice
		//would only be somewhere for the two to differ.
		ModelRenderer trackLeft = new ModelRenderer(this, 0, 0);
		trackLeft.addBox(-24F, -12F, -28F, 12, 12, 56);
		undercarriage.addChild(trackLeft);

		ModelRenderer trackRight = new ModelRenderer(this, 0, 0);
		trackRight.addBox(12F, -12F, -28F, 12, 12, 56);
		undercarriage.addChild(trackRight);

		//The belly joining them, held up off the ground so the tracks carry the machine.
		ModelRenderer belly = new ModelRenderer(this, 0, 70);
		belly.addBox(-12F, -8F, -20F, 24, 6, 40);
		undercarriage.addChild(belly);

		//The ring the house turns on.
		ModelRenderer slewRing = new ModelRenderer(this, 136, 0);
		slewRing.addBox(-14F, -14F, -14F, 28, 3, 28);
		undercarriage.addChild(slewRing);

		//	=================================
		//		HOUSE
		//	=================================
		house = new ModelRenderer(this);
		house.setRotationPoint(0, SLEW_HEIGHT, 0);

		ModelRenderer deck = new ModelRenderer(this, 136, 34);
		deck.addBox(-14F, -14F, -18F, 28, 14, 30);
		house.addChild(deck);

		//The counterweight, hung off the back. It is why a real one does not tip over, and the fastest
		//way to make a silhouette read as an excavator rather than a tractor.
		ModelRenderer counterweight = new ModelRenderer(this, 0, 118);
		counterweight.addBox(-13F, -16F, 12F, 26, 16, 10);
		house.addChild(counterweight);

		//The cab, offset to one side, the way every excavator's is.
		ModelRenderer cab = new ModelRenderer(this, 74, 118);
		cab.addBox(1F, -32F, -14F, 14, 18, 18);
		house.addChild(cab);

		ModelRenderer exhaust = new ModelRenderer(this, 140, 118);
		exhaust.addBox(-10F, -22F, 2F, 4, 8, 4);
		house.addChild(exhaust);

		undercarriage.addChild(house);

		//	=================================
		//		ARM
		//	=================================
		//Each section is pinned at the end of the one before and drawn forward from its own pin, so an
		//angle on a joint sweeps everything downstream of it.
		boom = new ModelRenderer(this, 0, 158);
		boom.setRotationPoint(BOOM_PIVOT_X, BOOM_PIVOT_Y, BOOM_PIVOT_Z);
		boom.addBox(-3F, -3F, -BOOM_LENGTH, 6, 7, (int)BOOM_LENGTH);
		house.addChild(boom);

		stick = new ModelRenderer(this, 66, 158);
		stick.setRotationPoint(0, 0, -BOOM_LENGTH);
		stick.addBox(-2F, -2F, -STICK_LENGTH, 5, 6, (int)STICK_LENGTH);
		boom.addChild(stick);

		//A plain stub on purpose. The attachments replace what hangs here, and drawing a bucket now
		//would mean drawing one the machine might not have fitted.
		tool = new ModelRenderer(this, 118, 158);
		tool.setRotationPoint(0, 0, -STICK_LENGTH);
		tool.addBox(-3F, -2F, -TOOL_LENGTH, 7, 7, (int)TOOL_LENGTH);
		stick.addChild(tool);
	}

	/**
	 * Pose the machine.
	 *
	 * @param slew       degrees the house is turned <em>relative to the tracks</em>
	 * @param boomAngle  degrees the boom is raised
	 * @param stickAngle degrees the stick is folded against the boom
	 * @param toolAngle  degrees the attachment is curled
	 */
	public void setPose(float slew, float boomAngle, float stickAngle, float toolAngle)
	{
		house.rotateAngleY = (float)Math.toRadians(slew);
		boom.rotateAngleX = (float)Math.toRadians(boomAngle);
		stick.rotateAngleX = (float)Math.toRadians(stickAngle);
		tool.rotateAngleX = (float)Math.toRadians(toolAngle);
	}

	@Override
	public void render(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
					   float netHeadYaw, float headPitch, float scale)
	{
		//Only the root: everything else is a child and is drawn by it.
		undercarriage.render(scale);
	}
}
