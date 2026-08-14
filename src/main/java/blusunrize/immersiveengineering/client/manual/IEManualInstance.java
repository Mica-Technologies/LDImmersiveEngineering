/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.manual;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.ManualHelper;
import blusunrize.immersiveengineering.client.ClientUtils;
import blusunrize.immersiveengineering.client.IEItemFontRender;
import blusunrize.immersiveengineering.common.Config;
import blusunrize.immersiveengineering.common.Config.IEConfig;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.network.MessageShaderManual;
import blusunrize.immersiveengineering.common.util.network.MessageShaderManual.MessageType;
import blusunrize.lib.manual.IManualPage;
import blusunrize.lib.manual.ManualInstance;
import blusunrize.lib.manual.ManualUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class IEManualInstance extends ManualInstance
{
	private final Set<String> hiddenEntries = new HashSet<>();

	//	=================================
	//	Two palettes, because the page is white now.
	//	=================================
	//
	// The manual's own §-codes were picked against tan paper: §6 is IE orange, §7 is light grey, §e is
	// yellow. On a white page they wash out, and there are over a hundred §6 headers in the lang file
	// alone. Rather than rewrite the text, the page is drawn with the colours darkened and the screen
	// palette is put back for anything drawn on a dark background -- tooltips, chiefly.
	private final int[] screenColours;
	private final int[] paperColours;

	public IEManualInstance()
	{
		super(new IEItemFontRender(), "immersiveengineering:textures/gui/manual.png");
		this.fontRenderer.colorCode[0+6] = Lib.COLOUR_I_ImmersiveOrange;
		this.fontRenderer.colorCode[16+6] = Lib.COLOUR_I_ImmersiveOrangeShadow;
		((IEItemFontRender)this.fontRenderer).createColourBackup();
		this.screenColours = Arrays.copyOf(this.fontRenderer.colorCode, 32);
		this.paperColours = Arrays.copyOf(this.screenColours, 32);
		int[] darkened = {
				0x000000, 0x0000aa, 0x006622, 0x006680, 0x992316, 0x77257c, 0xb25a12, 0x6e6e6e,
				0x555555, 0x2a2ad6, 0x2e8b2e, 0x1e8a8a, 0xc42b1c, 0xa020a0, 0x9a7b00, 0x2a2a2a
		};
		System.arraycopy(darkened, 0, this.paperColours, 0, darkened.length);
		if(Minecraft.getMinecraft().gameSettings.language!=null)
		{
			this.fontRenderer.setUnicodeFlag(ClientUtils.mc().getLanguageManager().isCurrentLocaleUnicode());
			this.fontRenderer.setBidiFlag(ClientUtils.mc().getLanguageManager().isCurrentLanguageBidirectional());
		}
		((IReloadableResourceManager)ClientUtils.mc().getResourceManager()).registerReloadListener(this.fontRenderer);
	}

	@Override
	public String formatText(String s)
	{
		if(!s.contains(" "))//if it contains spaces, it's probably already translated.
		{
			s = ManualUtils.attemptStringTranslation("ie.manual.entry.%s", s);
//			String translKey =  + s;
//			String translated = I18n.format(translKey);
//			if(!translKey.equals(translated))
//				s = translated;
		}
		String splitKey = ";";

		s = s.replaceAll("<br>", "\n");
		int start;
		int overflow = 0;
		while((start = s.indexOf("<config")) >= 0&&overflow < 50)
		{
			overflow++;
			int end = s.indexOf(">", start);
			String rep = s.substring(start, end+1);
			String[] segment = rep.substring(0, rep.length()-1).split(splitKey);
			if(segment.length < 3)
				break;
			String result = "";
			if(segment[1].equalsIgnoreCase("b"))
			{
				if(segment.length > 3)
					result = (Config.manual_bool.get(segment[2])?segment[3]: segment.length > 4?segment[4]: "");
				else
					result = ""+Config.manual_bool.get(segment[2]);
			}
			else if(segment[1].equalsIgnoreCase("i"))
				result = ""+Config.manual_int.get(segment[2]);
			else if(segment[1].equalsIgnoreCase("iA"))
			{
				int[] iA = Config.manual_intA.get(segment[2]);
				if(segment.length > 3)
					try
					{
						if(segment[3].startsWith("l"))
						{
							int limiter = Integer.parseInt(segment[3].substring(1));
							for(int i = 0; i < limiter; i++)
								result += (i > 0?", ": "")+iA[i];
						}
						else
						{
							int idx = Integer.parseInt(segment[3]);
							result = ""+iA[idx];
						}
					} catch(Exception ex)
					{
						break;
					}
				else
					for(int i = 0; i < iA.length; i++)
						result += (i > 0?", ": "")+iA[i];
			}
			else if(segment[1].equalsIgnoreCase("d"))
				result = ""+Config.manual_double.get(segment[2]);
			else if(segment[1].equalsIgnoreCase("dA"))
			{
				double[] iD = Config.manual_doubleA.get(segment[2]);
				if(segment.length > 3)
					try
					{
						int idx = Integer.parseInt(segment[3]);
						result = ""+Utils.formatDouble(iD[idx], "##0.0##");
					} catch(Exception ex)
					{
						break;
					}
				else
					for(int i = 0; i < iD.length; i++)
						result += (i > 0?", ": "")+Utils.formatDouble(iD[i], "##0.0##");
			}

			s = s.replaceFirst(rep, result);
		}
		overflow = 0;
		while((start = s.indexOf("<dim")) >= 0&&overflow < 50)
		{
			overflow++;
			int end = s.indexOf(">", start);
			String rep = s.substring(start, end+1);
			String[] segment = rep.substring(0, rep.length()-1).split(splitKey);
			if(segment.length < 2)
				break;
			String result = "";
			try
			{
				int dim = Integer.parseInt(segment[1]);
				World world = DimensionManager.getWorld(dim);
				if(world!=null&&world.provider!=null)
				{
					String name = world.provider.getDimensionType().getName();
					if(name.toLowerCase(Locale.ENGLISH).startsWith("the ")||name.toLowerCase(Locale.ENGLISH).startsWith("the_"))
						name = name.substring(4, 5).toUpperCase()+name.substring(5);
					result = name;
				}
				else
					result = "Dimension "+dim;
			} catch(Exception ex)
			{
				ex.printStackTrace();
			}
			s = s.replaceFirst(rep, result);
		}

		overflow = 0;
		while((start = s.indexOf("<keybind")) >= 0&&overflow < 50)
		{
			overflow++;
			int end = s.indexOf(">", start);
			String rep = s.substring(start, end+1);
			String[] segment = rep.substring(0, rep.length()-1).split(splitKey);
			if(segment.length < 2)
				break;
			String result = "";
			for(KeyBinding kb : ClientUtils.mc().gameSettings.keyBindings)
				if(segment[1].equalsIgnoreCase(kb.getKeyDescription()))
				{
					result = Utils.toCamelCase(Keyboard.getKeyName(kb.getKeyCode()));
					break;
				}
			s = s.replaceFirst(rep, result);
		}

		if(improveReadability())
		{
			overflow = 0;
			int end = 0;
			while((start = s.indexOf(TextFormatting.RESET.toString(), end)) >= 0&&overflow < 50)
			{
				overflow++;
				end = start+TextFormatting.RESET.toString().length();
				s = s.substring(0, end)+TextFormatting.BOLD.toString()+s.substring(end);
			}
			s = TextFormatting.BOLD+s;
		}
		return s;
	}

	@Override
	public void openManual()
	{
		if(improveReadability())
		{
			((IEItemFontRender)this.fontRenderer).spacingModifier = -.5f;
			((IEItemFontRender)this.fontRenderer).customSpaceWidth = 1f;
		}
	}

	@Override
	public void titleRenderPre()
	{
		if(improveReadability())
		{
			((IEItemFontRender)this.fontRenderer).spacingModifier = .5f;
			((IEItemFontRender)this.fontRenderer).customSpaceWidth = 4f;
		}
	}

	@Override
	public void titleRenderPost()
	{
		if(improveReadability())
		{
			((IEItemFontRender)this.fontRenderer).spacingModifier = -.5f;
			((IEItemFontRender)this.fontRenderer).customSpaceWidth = 1f;
		}
	}

	private void useColours(int[] colours)
	{
		System.arraycopy(colours, 0, this.fontRenderer.colorCode, 0, colours.length);
		((IEItemFontRender)this.fontRenderer).createColourBackup();
	}

	@Override
	public void entryRenderPre()
	{
		useColours(paperColours);
		if(improveReadability())
			((IEItemFontRender)this.fontRenderer).verticalBoldness = true;
	}

	@Override
	public void entryRenderPost()
	{
		useColours(screenColours);
		if(improveReadability())
			((IEItemFontRender)this.fontRenderer).verticalBoldness = false;
	}

	@Override
	public void tooltipRenderPre()
	{
		useColours(screenColours);
		if(improveReadability())
		{
			((IEItemFontRender)this.fontRenderer).spacingModifier = 0f;
			((IEItemFontRender)this.fontRenderer).customSpaceWidth = 4f;
			((IEItemFontRender)this.fontRenderer).verticalBoldness = false;
		}
	}

	@Override
	public void tooltipRenderPost()
	{
		useColours(paperColours);
		if(improveReadability())
		{
			((IEItemFontRender)this.fontRenderer).spacingModifier = -.5f;
			((IEItemFontRender)this.fontRenderer).customSpaceWidth = 1f;
			((IEItemFontRender)this.fontRenderer).verticalBoldness = true;
		}
	}


	@Override
	public String getManualName()
	{
		//The book on the shelf keeps the item's name; the title bar gets the fork's own if one is set.
		String key = "ie.manual.title";
		String title = I18n.format(key);
		return key.equals(title)?I18n.format("item.immersiveengineering.tool.manual.name"): title;
	}

	@Override
	public String getIndexHint()
	{
		return I18n.format("ie.manual.indexHint");
	}

	@Override
	public String getSearchLabel()
	{
		return I18n.format("ie.manual.search");
	}

	@Override
	public void addEntry(String name, String category, IManualPage... pages)
	{
		super.addEntry(name, category, pages);
		categorySet.add(category);
	}

	LinkedHashSet<String> categorySet = new LinkedHashSet<String>();

	@Override
	public String[] getSortedCategoryList()
	{
		return categorySet.toArray(new String[categorySet.size()]);
	}

	@Override
	public String formatCategoryName(String s)
	{
		return (improveReadability()?TextFormatting.BOLD: "")+I18n.format("ie.manual.category."+s+".name");
	}

	@Override
	public String formatEntryName(String s)
	{
		String unformatted = "ie.manual.entry."+s+".name";
		String formatted = I18n.format(unformatted);
//		return "\uD83D\uDCBB";
		return (improveReadability()?TextFormatting.BOLD: "")+(unformatted.equals(formatted)?s: formatted);
	}

	@Override
	public String formatEntrySubtext(String s)
	{
		String unformatted = "ie.manual.entry."+s+".subtext";
		String formatted = I18n.format(unformatted);
		return unformatted.equals(formatted)?"": formatted;
	}

	public void hideEntry(String name)
	{
		this.hiddenEntries.add(name.toLowerCase());
	}

	@Override
	public boolean showEntryInList(ManualEntry entry)
	{
		if(entry!=null&&ManualHelper.CAT_UPDATE.equalsIgnoreCase(entry.getCategory()))
			return IEConfig.showUpdateNews;
		return !(entry!=null&&hiddenEntries.contains(entry.getName().toLowerCase()));
	}

	@Override
	public boolean showCategoryInList(String category)
	{
		return true;
	}

	@Override
	public String formatLink(ManualLink link)
	{
		return TextFormatting.GOLD+"  -> "+formatEntryName(link.getKey())+", "+(link.getPage()+1);
	}

	@Override
	public void openEntry(String entry)
	{
		if("shaderList".equalsIgnoreCase(entry))
			ImmersiveEngineering.packetHandler.sendToServer(new MessageShaderManual(MessageType.SYNC));
	}

	//Everything below is drawn on the white page, so it is toned for white -- IE orange at 0xf78034
	//has barely more contrast against paper than the paper does.
	@Override
	public int getTitleColour()
	{
		return 0xa34f12;
	}

	@Override
	public int getSubTitleColour()
	{
		return 0x6b6156;
	}

	@Override
	public int getTextColour()
	{
		return improveReadability()?0: 0x1a1a1a;
	}

	@Override
	public int getHighlightColour()
	{
		return 0xb35a18;
	}

	@Override
	public int getPagenumberColour()
	{
		//On the frame under the page, not on the page.
		return 0x9c917c;
	}

	@Override
	public boolean allowGuiRescale()
	{
		return IEConfig.adjustManualScale;
	}

	@Override
	public boolean improveReadability()
	{
		return IEConfig.badEyesight;
	}
}