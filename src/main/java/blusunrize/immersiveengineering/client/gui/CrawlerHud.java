/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.gui;

import blusunrize.immersiveengineering.client.ClientProxy;
import blusunrize.immersiveengineering.client.ClientUtils;
import blusunrize.immersiveengineering.common.entities.CrawlerArm;
import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The operator's panel, drawn while riding a Hydraulic Crawler.
 * <p>
 * Two jobs, and they have opposite lifetimes. The <strong>gauge</strong> answers "where is the arm",
 * which a player wants continuously and cannot otherwise read -- the arm is behind and above the
 * camera at most aims. The <strong>legend</strong> answers "which keys work this thing", which a
 * player wants exactly once. So the legend fades out after a few seconds aboard and the gauge stays.
 * <p>
 * That split is the whole design. A permanent legend is clutter on every ride after the first; a
 * legend behind a help key is a legend nobody ever reads, and the controls here are not guessable --
 * nothing else in Minecraft is driven with WASD while a separate pair of keys works an arm.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class CrawlerHud
{
	/** How long the control legend stays up after climbing in, in ticks. */
	private static final int LEGEND_TICKS = 160;
	/** And how long it takes to fade once that is up. */
	private static final int FADE_TICKS = 40;

	private static final int PANEL = 0xC0101014;
	private static final int FRAME = 0xFF3A3A42;
	private static final int TEXT = 0xFFE0E0E0;
	private static final int DIM = 0xFF9A9AA2;
	private static final int GAUGE_TRACK = 0xFF26262C;
	private static final int GAUGE_MARK = 0xFFE9762B;
	private static final int GAUGE_LIMIT = 0xFFB0483A;

	/** Ticks since the operator climbed in, or -1 while not riding one. */
	private static int ticksAboard = -1;

	/**
	 * Count time aboard, so the legend knows when to get out of the way.
	 * <p>
	 * Reset by leaving rather than by a timer, so stepping out and back in shows the controls again --
	 * which is the behaviour somebody who has forgotten them will discover by accident.
	 */
	public static void tick(EntityPlayer player)
	{
		boolean riding = player!=null&&player.getRidingEntity() instanceof EntityHydraulicCrawler;
		if(!riding)
			ticksAboard = -1;
		else if(ticksAboard < Integer.MAX_VALUE-1)
			ticksAboard++;
	}

	public static void render(ScaledResolution resolution, EntityHydraulicCrawler crawler)
	{
		FontRenderer font = ClientUtils.mc().fontRenderer;
		int width = 108;
		int left = 6;
		//Anchored to the bottom left. The hotbar owns the bottom centre and status effects the top
		//right; this is the corner nothing else in vanilla claims.
		int lines = legendAlpha() > 0?4: 0;
		int height = 30+lines*10;
		int top = resolution.getScaledHeight()-height-6;

		Gui.drawRect(left, top, left+width, top+height, PANEL);
		drawFrame(left, top, left+width, top+height);

		font.drawString(I18n.format("gui.immersiveengineering.crawler.title"), left+6, top+6, TEXT);
		//The fitted tool, right-aligned against the title. An operator needs to know what is on the end
		//of the arm before they pull the trigger, not after -- one of the three destroys buildings.
		String tool = I18n.format(crawler.getAttachment().getTranslationKey());
		font.drawString(tool, left+width-6-font.getStringWidth(tool), top+6,
				crawler.getAttachment().breaksBlocks()?GAUGE_LIMIT: DIM);
		drawAimGauge(crawler, left+6, top+18, width-12);

		int alpha = legendAlpha();
		if(alpha > 0)
		{
			//Alpha in the high byte, so the legend dissolves rather than vanishing between two frames.
			int faded = (alpha << 24)|(DIM&0xFFFFFF);
			int y = top+32;
			font.drawString(legendLine("crawler.legendDrive", null), left+6, y, faded);
			font.drawString(legendLine("crawler.legendArm",
					ClientProxy.keybind_crawlerArmUp), left+6, y+10, faded);
			font.drawString(legendLine("crawler.legendSlew", null), left+6, y+20, faded);
			font.drawString(I18n.format("gui.immersiveengineering.crawler.legendTool",
					ClientProxy.keybind_crawlerAction.getDisplayName(),
					ClientProxy.keybind_crawlerSwap.getDisplayName()), left+6, y+30, faded);
		}
	}

	/**
	 * A legend line, with the key's <em>current</em> binding substituted rather than the default.
	 * <p>
	 * Because it will not be the default for everybody: R and F are free in vanilla but not in a
	 * modpack, and a panel confidently naming a key that does nothing is worse than no panel.
	 */
	private static String legendLine(String key, KeyBinding binding)
	{
		String name = binding==null?"": binding.getDisplayName();
		return I18n.format("gui.immersiveengineering."+key, name);
	}

	/**
	 * How far up or down the arm is aimed, as a bar with a marker.
	 * <p>
	 * The arm's aim is the one piece of state an operator cannot see: at most positions the boom is
	 * above and behind the camera, so "am I at full lift or nearly there" is unanswerable by looking.
	 * The ends of the track turn red at the limits, which is what stops somebody holding a key that
	 * has stopped doing anything and concluding the machine is stuck.
	 */
	private static void drawAimGauge(EntityHydraulicCrawler crawler, int x, int y, int width)
	{
		Gui.drawRect(x, y, x+width, y+6, GAUGE_TRACK);
		double span = CrawlerArm.MAX_DEPRESSION-CrawlerArm.MIN_DEPRESSION;
		//Inverted, so up on the machine is up... along a horizontal bar, which means left. Aim runs
		//from raised (negative) to dug in (positive), and the bar reads left-to-right as high-to-low.
		double fraction = (crawler.getArmAim()-CrawlerArm.MIN_DEPRESSION)/span;
		int mark = x+(int)Math.round(fraction*(width-3));

		boolean atLimit = crawler.getArmAim() <= CrawlerArm.MIN_DEPRESSION+0.01
				||crawler.getArmAim() >= CrawlerArm.MAX_DEPRESSION-0.01;
		Gui.drawRect(mark, y-1, mark+3, y+7, atLimit?GAUGE_LIMIT: GAUGE_MARK);
		//A tick at the middle, so level is findable without staring.
		int centre = x+(width-1)/2;
		Gui.drawRect(centre, y+2, centre+1, y+4, DIM);
	}

	private static void drawFrame(int left, int top, int right, int bottom)
	{
		Gui.drawRect(left, top, right, top+1, FRAME);
		Gui.drawRect(left, bottom-1, right, bottom, FRAME);
		Gui.drawRect(left, top, left+1, bottom, FRAME);
		Gui.drawRect(right-1, top, right, bottom, FRAME);
	}

	/**
	 * @return the legend's opacity, 0 once it has faded out entirely
	 */
	private static int legendAlpha()
	{
		if(ticksAboard < 0)
			return 0;
		if(ticksAboard < LEGEND_TICKS)
			return 0xFF;
		int fading = ticksAboard-LEGEND_TICKS;
		if(fading >= FADE_TICKS)
			return 0;
		//Floored at a value that is still visible, so the last frame of the fade is not a smear of
		//invisible text sitting in a panel that has already resized around it.
		return Math.max(8, 0xFF-(int)(0xFF*(fading/(double)FADE_TICKS)));
	}
}
