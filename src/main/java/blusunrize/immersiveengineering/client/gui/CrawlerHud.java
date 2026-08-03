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
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * The operator's panel, drawn while riding a Hydraulic Crawler.
 * <p>
 * Two gauges and a list of controls. The gauges answer questions the world cannot: at most aims the
 * boom is above and behind the camera, so "how high is the arm" and "how far out" are unanswerable by
 * looking, and an empty fuel tank is the reason the trigger does nothing.
 * <p>
 * <strong>The panel measures itself against its text.</strong> Every string here is translated and
 * carries a key name that a player may have rebound, so its width is not knowable in advance -- a
 * fixed panel is one that fits in English with the default bindings and overflows everywhere else,
 * which is exactly how the first version behaved.
 * <p>
 * <strong>The controls do not fade.</strong> They did, on the theory that a legend is read once and
 * is clutter thereafter. In use it read as the panel breaking: the controls were there, and then they
 * were not, with nothing having been pressed. Six keys is more than anybody holds in their head
 * between sessions, and a machine is a thing you come back to.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class CrawlerHud
{
	private static final int PANEL = 0xC0101014;
	private static final int FRAME = 0xFF3A3A42;
	private static final int TEXT = 0xFFE0E0E0;
	private static final int DIM = 0xFF9A9AA2;
	private static final int GAUGE_TRACK = 0xFF26262C;
	private static final int GAUGE_MARK = 0xFFE9762B;
	private static final int GAUGE_LIMIT = 0xFFB0483A;
	private static final int FUEL = 0xFF6F8F3E;

	private static final int MARGIN = 6;
	private static final int LINE = 10;
	private static final int GAUGE_HEIGHT = 6;
	/**
	 * Room for a gauge's name before its track starts.
	 * <p>
	 * One number for all three, so the tracks line up down the panel. Measured against the longest of
	 * the labels at render time rather than guessed, for the same reason the panel is: these are
	 * translated strings.
	 */
	private static int LABEL_WIDTH = 30;

	/** Kept so a machine left running still shows a panel; nothing depends on it any more. */
	public static void tick(net.minecraft.entity.player.EntityPlayer player)
	{
	}

	public static void render(ScaledResolution resolution, EntityHydraulicCrawler crawler)
	{
		FontRenderer font = ClientUtils.mc().fontRenderer;
		String title = I18n.format("gui.immersiveengineering.crawler.title");
		String tool = I18n.format(crawler.getAttachment().getTranslationKey());
		List<String> legend = legend();

		//	=================================
		//	Sized to what is in it.
		//	=================================
		//
		// The title and the tool share a line with a gap between them, so that pair sets one minimum
		// and the longest control line sets another. Taking the larger is what stops the text running
		// out through the frame -- which it did, because the panel was a number somebody typed and the
		// strings are translated and name rebindable keys.
		//The gauge labels set where every track starts, so they are measured too -- otherwise a
		//translation with a longer word for "fuel" pushes its own track off the end of the panel.
		LABEL_WIDTH = 4;
		for(String key : new String[]{"gaugeArm", "gaugeReach", "gaugeFuel", "noFuel", "reserve",
				"gaugeBucket", "bucketFull"})
			LABEL_WIDTH = Math.max(LABEL_WIDTH,
					font.getStringWidth(I18n.format("gui.immersiveengineering.crawler."+key))+4);

		//Only when the Bucket is fitted: the Breaker drops everything and the Grapple carries nothing,
		//so on those two a load gauge would be a row that never moved.
		boolean showBucket = crawler.getAttachment().keepsDrops();

		int content = font.getStringWidth(title)+MARGIN+font.getStringWidth(tool);
		for(String line : legend)
			content = Math.max(content, font.getStringWidth(line));
		//A gauge needs its label plus a track worth looking at; a panel sized only by the text could
		//otherwise leave three pixels of track.
		content = Math.max(content, LABEL_WIDTH+60);
		int width = content+MARGIN*2;
		int gauges = showBucket?4: 3;
		int height = MARGIN+LINE+gauges*LINE+legend.size()*LINE+MARGIN;

		int left = MARGIN;
		//Anchored to the bottom left: the hotbar owns the bottom centre and status effects the top
		//right, and that corner is the one vanilla leaves alone.
		int top = resolution.getScaledHeight()-height-MARGIN;

		Gui.drawRect(left, top, left+width, top+height, PANEL);
		drawFrame(left, top, left+width, top+height);

		int y = top+MARGIN;
		font.drawString(title, left+MARGIN, y, TEXT);
		//Right-aligned against the panel's own edge, so it moves with the width instead of assuming it.
		font.drawString(tool, left+width-MARGIN-font.getStringWidth(tool), y,
				crawler.getAttachment().breaksBlocks()?GAUGE_LIMIT: DIM);
		y += LINE;

		int gaugeWidth = width-MARGIN*2;
		drawAimGauge(crawler, left+MARGIN, y, gaugeWidth);
		y += LINE;
		drawReachGauge(crawler, left+MARGIN, y, gaugeWidth);
		y += LINE;
		drawFuelGauge(crawler, left+MARGIN, y, gaugeWidth);
		y += LINE;
		if(showBucket)
		{
			drawBucketGauge(crawler, left+MARGIN, y, gaugeWidth);
			y += LINE;
		}

		for(String line : legend)
		{
			font.drawString(line, left+MARGIN, y, DIM);
			y += LINE;
		}
	}

	/**
	 * The control list, with each key's <em>current</em> binding.
	 * <p>
	 * Because it will not be the default for everybody: these keys are free in vanilla but not in
	 * every modpack, and a panel confidently naming a key that does nothing is worse than no panel.
	 */
	private static List<String> legend()
	{
		List<String> lines = new ArrayList<>();
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendDrive"));
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendSlew"));
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendArm",
				ClientProxy.keybind_crawlerArmUp.getDisplayName(),
				ClientProxy.keybind_crawlerArmDown.getDisplayName()));
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendReach",
				ClientProxy.keybind_crawlerExtend.getDisplayName(),
				ClientProxy.keybind_crawlerRetract.getDisplayName()));
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendTool",
				ClientProxy.keybind_crawlerAction.getDisplayName(),
				ClientProxy.keybind_crawlerSwap.getDisplayName()));
		//The one gesture nothing else could tell you. There is no window on the bucket and no other
		//way to unload it by hand, so an operator who has not been told this has a machine that
		//fills up and then starts dropping its spoil on the floor for no reason they can see.
		lines.add(I18n.format("gui.immersiveengineering.crawler.legendDump",
				ClientProxy.keybind_crawlerAction.getDisplayName()));
		return lines;
	}

	/**
	 * How high the arm is aimed. Left is raised, right is dug in.
	 */
	private static void drawAimGauge(EntityHydraulicCrawler crawler, int x, int y, int width)
	{
		double span = CrawlerArm.MAX_DEPRESSION-CrawlerArm.MIN_DEPRESSION;
		double fraction = (crawler.getArmAim()-CrawlerArm.MIN_DEPRESSION)/span;
		boolean atLimit = crawler.getArmAim() <= CrawlerArm.MIN_DEPRESSION+0.01
				||crawler.getArmAim() >= CrawlerArm.MAX_DEPRESSION-0.01;
		drawMarkedGauge(x, y, width, fraction, atLimit,
				I18n.format("gui.immersiveengineering.crawler.gaugeArm"));
	}

	/**
	 * How far the arm is extended -- the second aiming axis, and the one with no other readout at all:
	 * from the cab, an arm reaching four blocks and one reaching five look much the same.
	 */
	private static void drawReachGauge(EntityHydraulicCrawler crawler, int x, int y, int width)
	{
		double span = CrawlerArm.MAX_REACH-CrawlerArm.MIN_REACH;
		double fraction = (crawler.getArmReach()-CrawlerArm.MIN_REACH)/span;
		boolean atLimit = crawler.getArmReach() <= CrawlerArm.MIN_REACH+0.01
				||crawler.getArmReach() >= CrawlerArm.MAX_REACH-0.01;
		drawMarkedGauge(x, y, width, fraction, atLimit,
				I18n.format("gui.immersiveengineering.crawler.gaugeReach"));
	}

	/**
	 * A track with a slider on it, and its name in the track.
	 * <p>
	 * The ends turn red at the limits: without that, an operator holding a key that has quietly
	 * stopped doing anything concludes the machine is stuck.
	 */
	private static void drawMarkedGauge(int x, int y, int width, double fraction, boolean atLimit,
										String label)
	{
		FontRenderer font = ClientUtils.mc().fontRenderer;
		//Label beside the track rather than over it. Six pixels of track is shorter than the font, so
		//text drawn inside one sits half outside it and reads as a rendering fault.
		font.drawString(label, x, y, DIM);
		int trackLeft = x+LABEL_WIDTH;
		int trackWidth = width-LABEL_WIDTH;
		int trackTop = y+1;
		Gui.drawRect(trackLeft, trackTop, trackLeft+trackWidth, trackTop+GAUGE_HEIGHT, GAUGE_TRACK);
		int mark = trackLeft+(int)Math.round(clamp01(fraction)*(trackWidth-3));
		Gui.drawRect(mark, trackTop-1, mark+3, trackTop+GAUGE_HEIGHT+1,
				atLimit?GAUGE_LIMIT: GAUGE_MARK);
	}

	/** A clamp, so a value slightly outside its range cannot draw the marker outside the panel. */
	private static double clamp01(double fraction)
	{
		return fraction < 0?0: fraction > 1?1: fraction;
	}

	/**
	 * Diesel remaining.
	 * <p>
	 * Labelled when it is low or gone, because an unlabelled empty bar is a dark rectangle
	 * indistinguishable from a panel line -- and on a machine that has never been fuelled it is the
	 * reason the attachment does nothing.
	 */
	private static void drawFuelGauge(EntityHydraulicCrawler crawler, int x, int y, int width)
	{
		FontRenderer font = ClientUtils.mc().fontRenderer;
		boolean healthy = crawler.hasWorkingFuel();
		String label = healthy
				?I18n.format("gui.immersiveengineering.crawler.gaugeFuel")
				: I18n.format(crawler.getFuel() <= 0
				?"gui.immersiveengineering.crawler.noFuel"
				: "gui.immersiveengineering.crawler.reserve");
		font.drawString(label, x, y, healthy?DIM: GAUGE_LIMIT);

		int trackLeft = x+LABEL_WIDTH;
		int trackWidth = width-LABEL_WIDTH;
		int trackTop = y+1;
		Gui.drawRect(trackLeft, trackTop, trackLeft+trackWidth, trackTop+GAUGE_HEIGHT, GAUGE_TRACK);
		int filled = (int)Math.round(trackWidth*clamp01(
				crawler.getFuel()/(double)crawler.getFuelCapacity()));
		if(filled > 0)
			Gui.drawRect(trackLeft, trackTop, trackLeft+filled, trackTop+GAUGE_HEIGHT,
					healthy?FUEL: GAUGE_LIMIT);
	}

	/**
	 * How much the Bucket is holding.
	 * <p>
	 * There is no window on it and no way to look inside, so without this the first sign that it is
	 * full is spoil appearing on the ground -- which reads as the machine having stopped keeping
	 * what it digs rather than as a bucket that needs tipping out. Labelled when full for the same
	 * reason the fuel gauge is labelled when empty: that is the reading that needs an action.
	 */
	private static void drawBucketGauge(EntityHydraulicCrawler crawler, int x, int y, int width)
	{
		FontRenderer font = ClientUtils.mc().fontRenderer;
		int slots = crawler.getBucketSize();
		int used = crawler.getBucketFill();
		boolean full = slots > 0&&used >= slots;
		font.drawString(I18n.format(full?"gui.immersiveengineering.crawler.bucketFull"
				: "gui.immersiveengineering.crawler.gaugeBucket"), x, y, full?GAUGE_LIMIT: DIM);

		int trackLeft = x+LABEL_WIDTH;
		int trackWidth = width-LABEL_WIDTH;
		int trackTop = y+1;
		Gui.drawRect(trackLeft, trackTop, trackLeft+trackWidth, trackTop+GAUGE_HEIGHT, GAUGE_TRACK);
		int filled = slots <= 0?0
				: (int)Math.round(trackWidth*clamp01(used/(double)slots));
		if(filled > 0)
			Gui.drawRect(trackLeft, trackTop, trackLeft+filled, trackTop+GAUGE_HEIGHT,
					full?GAUGE_LIMIT: GAUGE_MARK);
	}

	private static void drawFrame(int left, int top, int right, int bottom)
	{
		Gui.drawRect(left, top, right, top+1, FRAME);
		Gui.drawRect(left, bottom-1, right, bottom, FRAME);
		Gui.drawRect(left, top, left+1, bottom, FRAME);
		Gui.drawRect(right-1, top, right, bottom, FRAME);
	}
}
