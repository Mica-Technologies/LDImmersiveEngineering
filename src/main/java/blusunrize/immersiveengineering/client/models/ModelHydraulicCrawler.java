/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models;

import blusunrize.immersiveengineering.common.entities.CrawlerAttachment;
import blusunrize.immersiveengineering.common.entities.CrawlerGeometry;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The Hydraulic Crawler's body: two tracks with the wheels turning inside them, a house that
 * slews on top, a three-piece arm, and whichever of the three attachments is fitted.
 * <p>
 * <strong>An OBJ read by {@link EntityOBJModel}, not a hierarchy of boxes, and the reason is
 * the playtest report that asked for this.</strong> The old model was nine
 * {@code ModelRenderer} cuboids -- "crude and rudimentary at least", which was fair. A machine
 * with a drive sprocket, an idler, four road wheels a side, a cab you can see through, a
 * counterweight and a toothed bucket is upwards of a thousand faces, and nobody writes a
 * thousand faces of {@code addBox} by hand. It is generated, by
 * {@code docs/tools/make_crawler_obj.py}, which is also where its texture comes from.
 * <p>
 * <strong>Every pivot below is the number the old model used.</strong> That is the whole of
 * what makes this a change of appearance and not a change of behaviour: the arm's hitboxes are
 * placed by {@code CrawlerArm.alongArm} from the boom's pivot, in model units, along each
 * section in turn, and the server decides which blocks the machine may destroy from the same
 * arithmetic. Move a pivot and the visible steel walks away from the boxes that decide what
 * the machine can touch -- and it would still look perfectly fine while doing it.
 * <p>
 * The pivots are duplicated in the generator, because a Python script cannot read a Java
 * constant, and {@code CrawlerAssetsTest} asserts the two copies agree.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class ModelHydraulicCrawler
{
	/**
	 * The sheet the OBJ's UVs are cut from. The generator owns the layout; this is here so a
	 * test can assert the file on disk is the size the UVs assume, which is the one way the
	 * two can disagree without anything at runtime saying so.
	 */
	public static final int SHEET_WIDTH = 256;
	public static final int SHEET_HEIGHT = 256;

	/**
	 * The arm's section lengths, <strong>read from {@link CrawlerGeometry} rather than declared
	 * here</strong>.
	 * <p>
	 * The solver that decides where the bucket <em>is</em> lives on the common side, because the
	 * server is what destroys blocks and it cannot see a client-only class. So the numbers live
	 * there and the picture reads them. Two copies would eventually be two lengths, and an arm
	 * drawn shorter than it reaches would destroy blocks the operator is not pointing at -- the
	 * one failure in this feature nobody would forgive.
	 */
	public static final float BOOM_LENGTH = (float)CrawlerGeometry.BOOM_LENGTH;
	public static final float STICK_LENGTH = (float)CrawlerGeometry.STICK_LENGTH;
	public static final float TOOL_LENGTH = (float)CrawlerGeometry.TOOL_LENGTH;

	/** Where the boom is pinned to the house. */
	public static final float BOOM_PIVOT_X = -5F;
	public static final float BOOM_PIVOT_Y = -10F;
	public static final float BOOM_PIVOT_Z = -16F;

	/** Height of the slew ring above the entity's origin, in pixels. The house turns about this. */
	public static final float SLEW_HEIGHT = -14F;

	/**
	 * Every wheel on one side: {@code {z, y, radius}} about its own axle, in model units.
	 * <p>
	 * The first two are the drive sprocket and the idler, at the back and the front; the rest
	 * are road wheels. They are drawn as their own groups so each can spin at its own rate --
	 * a small wheel turns further for the same ground covered, and a machine whose wheels all
	 * turned together would read as a printed pattern rather than as running gear.
	 * <p>
	 * No x: a wheel turns about the x axis, so where it sits along that axis never changes and
	 * is baked into the group. Which side of the machine a group is on is in its name.
	 */
	public static final float[][] WHEELS = {
			{19F, -6F, 4F},
			{-19F, -6F, 4F},
			{-11F, -5F, 3F},
			{-4F, -5F, 3F},
			{4F, -5F, 3F},
			{11F, -5F, 3F},
	};

	/**
	 * How far down the belt strip one repeat of the tread is, in sheet pixels.
	 * <p>
	 * The tracks are animated by scrolling the belt group's texture coordinates, and this is
	 * what makes that possible without tearing: the strip is painted so that a shift of exactly
	 * one period is invisible, so any distance the machine has driven can be reduced into one
	 * period and the belt still looks continuous. It is also the only offset the generator has
	 * left room for -- see its {@code check_belt}.
	 */
	public static final float BELT_PERIOD = 8F;

	private static final ResourceLocation MODEL = new ResourceLocation(
			"immersiveengineering:models/entity/hydraulic_crawler.obj");

	private static final String UNDERCARRIAGE = "undercarriage";
	private static final String[] SIDES = {"left", "right"};

	private final EntityOBJModel model = new EntityOBJModel(MODEL);

	/**
	 * @return the group holding the given attachment's steel
	 */
	public static String groupFor(CrawlerAttachment attachment)
	{
		//Off the enum's own name, so adding a fourth attachment is a group in the generator and
		//nothing here. The names are the enum's, lower case, which is what getName already is.
		return "tool_"+attachment.getName();
	}

	/**
	 * Draw the machine, posed.
	 *
	 * @param slew       degrees the house is turned <em>relative to the tracks</em>
	 * @param boomAngle  degrees the boom is raised
	 * @param stickAngle degrees the stick is folded against the boom
	 * @param toolAngle  degrees the attachment is curled
	 * @param attachment which tool is fitted
	 * @param leftRoll   how far the left track has rolled, in model units of belt travel
	 * @param rightRoll  and the right, which differs from it whenever the machine is turning
	 */
	public void render(float slew, float boomAngle, float stickAngle, float toolAngle,
					   CrawlerAttachment attachment, float leftRoll, float rightRoll)
	{
		model.render(UNDERCARRIAGE);
		for(int side = 0; side < SIDES.length; side++)
			renderTrack(SIDES[side], side==0?leftRoll: rightRoll);

		GlStateManager.pushMatrix();
		GlStateManager.translate(0, SLEW_HEIGHT, 0);
		GlStateManager.rotate(slew, 0, 1, 0);
		model.render("house");

		//Each section is pinned at the end of the one before, so an angle on a joint sweeps
		//everything downstream of it -- which is what steel does and is why the renderer only
		//has to hand over four angles.
		GlStateManager.pushMatrix();
		GlStateManager.translate(BOOM_PIVOT_X, BOOM_PIVOT_Y, BOOM_PIVOT_Z);
		GlStateManager.rotate(boomAngle, 1, 0, 0);
		model.render("boom");

		GlStateManager.pushMatrix();
		GlStateManager.translate(0, 0, -BOOM_LENGTH);
		GlStateManager.rotate(stickAngle, 1, 0, 0);
		model.render("stick");

		GlStateManager.pushMatrix();
		GlStateManager.translate(0, 0, -STICK_LENGTH);
		GlStateManager.rotate(toolAngle, 1, 0, 0);
		model.render(groupFor(attachment));
		GlStateManager.popMatrix();

		GlStateManager.popMatrix();
		GlStateManager.popMatrix();
		GlStateManager.popMatrix();
	}

	/**
	 * One track: the belt, scrolled, and the wheels inside it, turned.
	 *
	 * @param roll how far this track has travelled over the ground, in model units
	 */
	private void renderTrack(String side, float roll)
	{
		//	=================================
		//	Why the offset is the negative of the distance driven.
		//	=================================
		//
		// The belt's texture runs along the direction the tread travels, so a feature painted
		// at v appears at the point on the belt where the sampled coordinate reaches v -- which
		// moves *backwards* along the belt as the offset grows. Negated, the tread travels
		// forwards with the machine. Wrapped into one period first, because the strip only has
		// room for one period of slack; anything else walks the UVs off the bottom of the
		// region and paints the tracks with whatever is underneath it.
		float wrapped = roll%BELT_PERIOD;
		if(wrapped < 0)
			wrapped += BELT_PERIOD;
		model.render("track_"+side, 0, (BELT_PERIOD-wrapped)/SHEET_HEIGHT);

		for(int i = 0; i < WHEELS.length; i++)
		{
			float[] wheel = WHEELS[i];
			GlStateManager.pushMatrix();
			GlStateManager.translate(0, wheel[1], wheel[0]);
			//Rolling, not spinning at a speed: the angle is the distance travelled divided by
			//the radius, so every wheel stays in step with the ground and with the others no
			//matter what the machine has been doing.
			GlStateManager.rotate((float)Math.toDegrees(roll/wheel[2]), 1, 0, 0);
			model.render("wheel_"+side+"_"+i);
			GlStateManager.popMatrix();
		}
	}
}
