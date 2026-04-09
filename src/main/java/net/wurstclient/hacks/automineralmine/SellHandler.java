/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.automineralmine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.util.ChatUtils;

/**
 * Handles selling items on the mineral mine market server.
 *
 * <h3>Actual server order tooltip format:</h3>
 *
 * <pre>
 *   Diamonds
 *   $10 each
 *   1.06k/20k Delivered
 * </pre>
 *
 * <h3>Bugs fixed vs previous version:</h3>
 * <ol>
 * <li><b>Item type mismatch</b> — Market GUI slots are custom placeholder
 * items (a named paper, skull, etc.), NOT {@code Items.DIAMOND}. The old
 * {@code stack.getItem() == targetItem} check always returned false and
 * found nothing. We now match by checking the slot's display name
 * against a known label string.</li>
 * <li><b>k-suffix in delivered counts</b> — Real orders show
 * {@code 1.06k/20k Delivered}. The old regex only handled plain
 * integers. {@link #parseCount(String)} now resolves k-suffixes on
 * both sides.</li>
 * <li><b>Ambiguous /orders search</b> — {@code /orders diamond} returns
 * diamond armor and tools too, because the server does a substring
 * search. We use a hardcoded plural name map so we send
 * {@code /orders diamonds} instead.</li>
 * </ol>
 */
public final class SellHandler
{
	// =========================================================================
	// Server search-name map (used in /orders <name> command)
	// =========================================================================
	
	/**
	 * Maps each sellable item to the exact search string the server expects.
	 * Using {@code "diamond"} returns armor/tools too; {@code "diamonds"}
	 * narrows results to just the raw material.
	 */
	private static final Map<Item, String> ORDER_SEARCH_NAMES = new HashMap<>();
	static
	{
		ORDER_SEARCH_NAMES.put(Items.DIAMOND, "diamonds");
		ORDER_SEARCH_NAMES.put(Items.RAW_GOLD, "raw gold");
		ORDER_SEARCH_NAMES.put(Items.REDSTONE, "redstone dust");
	}
	
	/**
	 * Substring expected inside a market GUI slot's display name when the slot
	 * belongs to an order for {@code targetItem}. Case-insensitive match.
	 * The server may show "Diamonds" or "Diamond" — both contain "diamond".
	 */
	private static final Map<Item, String> ORDER_DISPLAY_LABELS =
		new HashMap<>();
	static
	{
		ORDER_DISPLAY_LABELS.put(Items.DIAMOND, "diamond");
		ORDER_DISPLAY_LABELS.put(Items.RAW_GOLD, "raw gold");
		ORDER_DISPLAY_LABELS.put(Items.REDSTONE, "redstone dust");
	}
	
	// =========================================================================
	// Tooltip parsing patterns
	// =========================================================================
	
	/**
	 * Matches price lines like {@code $10 each}, {@code $5.01k each}.
	 * Group 1 = numeric (may have commas), Group 2 = optional k.
	 */
	private static final Pattern PRICE_PATTERN = Pattern
		.compile("\\$([\\d,]+(?:\\.\\d+)?)(k?)", Pattern.CASE_INSENSITIVE);
	
	/**
	 * Matches delivered lines like {@code 0/64 Delivered} or
	 * {@code 1.06k/20k Delivered}. Each side is parsed with
	 * {@link #parseCount(String)}.
	 * Group 1 = left (delivered so far), Group 2 = right (total requested).
	 */
	private static final Pattern DELIVERED_PATTERN = Pattern.compile(
		"([\\d,]+(?:\\.\\d+)?k?)\\s*/\\s*([\\d,]+(?:\\.\\d+)?k?)\\s+delivered",
		Pattern.CASE_INSENSITIVE);
	
	// =========================================================================
	// Constants
	// =========================================================================
	
	private static final long SCREEN_TIMEOUT_MS = 8_000L;
	private static final int MAX_SCREEN_ATTEMPTS = 40;
	private static final int MAX_BLAST_RETRIES = 3;
	private static final int MAX_RESELL_CYCLES = 10;
	private static final long DEFAULT_GUI_DELAY_MS = 300L;
	
	// =========================================================================
	// Stage enum
	// =========================================================================
	
	private enum Stage
	{
		IDLE,
		OPEN_ORDERS,
		WAIT_MARKET_GUI,
		FIND_ORDER,
		CLICK_ORDER,
		WAIT_CONTAINER_GUI,
		BLAST_ITEMS,
		WAIT_AFTER_BLAST,
		CLOSE_FOR_CONFIRM,
		WAIT_CONFIRM_GUI,
		CONFIRM,
		VERIFY_DELIVERY,
		DONE
	}
	
	// =========================================================================
	// Fields
	// =========================================================================
	
	private final Minecraft mc;
	
	private Stage stage = Stage.IDLE;
	private long stageStartMs = 0L;
	private int screenAttempts = 0;
	
	// Settings
	private int maxSlotsToPull = 10;
	private int minFillPct = 0;
	private boolean filterByUsername = false;
	private String targetUsername = "";
	private double minSellThreshold = 0.0;
	private int sellTransitionDelayTicks = 6;
	
	// Queue
	private Item targetItem = null;
	private int targetAmount = 0;
	
	// Per-run tracking
	private int soldAmount = 0;
	private int invCountBefore = 0;
	private int foundOrderSlot = -1;
	private int blastRetries = 0;
	private int resellCycles = 0;
	
	public SellHandler(Minecraft mc)
	{
		this.mc = mc;
	}
	
	// =========================================================================
	// PUBLIC API
	// =========================================================================
	
	public void setSettings(int maxSlotsToPull, int minFillPct,
		boolean filterByUsername)
	{
		this.maxSlotsToPull = Math.max(1, maxSlotsToPull);
		this.minFillPct = minFillPct;
		this.filterByUsername = filterByUsername;
	}
	
	/** Minimum price per item (raw $) to accept an order. 0 = no filter. */
	public void setMinSellThreshold(double minPrice)
	{
		this.minSellThreshold = Math.max(0.0, minPrice);
	}
	
	/** Delay between GUI stage transitions (1 tick = 50 ms). */
	public void setSellTransitionDelayTicks(int ticks)
	{
		this.sellTransitionDelayTicks = Math.max(1, ticks);
	}
	
	public void setUsernameFilter(String username)
	{
		this.targetUsername = (username == null) ? "" : username.trim();
	}
	
	public void queueSell(Item item, int amount)
	{
		this.targetItem = item;
		this.targetAmount = Math.max(1, amount);
	}
	
	public void start()
	{
		if(targetItem == null)
		{
			ChatUtils.error("SellHandler: No item queued.");
			return;
		}
		
		soldAmount = 0;
		blastRetries = 0;
		resellCycles = 0;
		foundOrderSlot = -1;
		invCountBefore = countInInventory(targetItem);
		
		advanceTo(Stage.OPEN_ORDERS);
	}
	
	public void tick()
	{
		if(stage == Stage.IDLE || stage == Stage.DONE)
			return;
		
		long now = System.currentTimeMillis();
		
		// Transition delay applies to all stages except the blast/wait pair
		if(stage != Stage.BLAST_ITEMS && stage != Stage.WAIT_AFTER_BLAST)
		{
			long delayMs = ticksToMs(sellTransitionDelayTicks);
			if(now - stageStartMs < delayMs)
				return;
		}
		
		switch(stage)
		{
			case OPEN_ORDERS -> handleOpenOrders();
			case WAIT_MARKET_GUI -> handleWaitMarketGui(now);
			case FIND_ORDER -> handleFindOrder();
			case CLICK_ORDER -> handleClickOrder();
			case WAIT_CONTAINER_GUI -> handleWaitContainerGui();
			case BLAST_ITEMS -> handleBlastItems();
			case WAIT_AFTER_BLAST -> handleWaitAfterBlast(now);
			case CLOSE_FOR_CONFIRM -> handleCloseForConfirm();
			case WAIT_CONFIRM_GUI -> handleWaitConfirmGui();
			case CONFIRM -> handleConfirm();
			case VERIFY_DELIVERY -> handleVerifyDelivery(now);
			default ->
				{
				}
		}
	}
	
	public boolean isFinished()
	{
		return stage == Stage.IDLE || stage == Stage.DONE;
	}
	
	public int getSoldAmount()
	{
		return soldAmount;
	}
	
	public void reset()
	{
		stage = Stage.IDLE;
		stageStartMs = 0;
		screenAttempts = 0;
		targetItem = null;
		targetAmount = 0;
		soldAmount = 0;
		invCountBefore = 0;
		foundOrderSlot = -1;
		blastRetries = 0;
		resellCycles = 0;
		closeAnyOpenContainer();
	}
	
	// =========================================================================
	// STAGES
	// =========================================================================
	
	private void handleOpenOrders()
	{
		String cmd;
		if(filterByUsername && !targetUsername.isEmpty())
		{
			cmd = "orders " + targetUsername;
		}else
		{
			// Use the explicit plural search name to avoid the server returning
			// armor/tools that share the same material keyword.
			String searchName = ORDER_SEARCH_NAMES.getOrDefault(targetItem,
				new ItemStack(targetItem).getHoverName().getString());
			cmd = "orders " + searchName;
		}
		
		mc.player.connection.sendCommand(cmd);
		advanceTo(Stage.WAIT_MARKET_GUI);
	}
	
	private void handleWaitMarketGui(long now)
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			String title = screen.getTitle().getString().toLowerCase();
			if(title.contains("page"))
			{
				screenAttempts = 0;
				advanceTo(Stage.FIND_ORDER);
				return;
			}
		}
		
		screenAttempts++;
		if(screenAttempts >= MAX_SCREEN_ATTEMPTS
			|| now - stageStartMs > SCREEN_TIMEOUT_MS)
		{
			ChatUtils.warning("SellHandler: Market GUI timeout, retrying...");
			closeAnyOpenContainer();
			screenAttempts = 0;
			advanceTo(Stage.OPEN_ORDERS);
		}
	}
	
	private void handleFindOrder()
	{
		if(!(mc.screen instanceof AbstractContainerScreen<?> screen))
		{
			advanceTo(Stage.DONE);
			return;
		}
		
		AbstractContainerMenu menu = screen.getMenu();
		int currentInv = countInInventory(targetItem);
		foundOrderSlot = findBestOrderSlot(menu, currentInv);
		
		if(foundOrderSlot == -1)
		{
			String extra = minSellThreshold > 0
				? " (min price $" + (int)minSellThreshold + " not met)" : "";
			ChatUtils
				.warning("SellHandler: No suitable order found" + extra + ".");
			closeAnyOpenContainer();
			advanceTo(Stage.DONE);
			return;
		}
		
		advanceTo(Stage.CLICK_ORDER);
	}
	
	private void handleClickOrder()
	{
		if(!(mc.screen instanceof AbstractContainerScreen<?> screen))
		{
			advanceTo(Stage.DONE);
			return;
		}
		
		mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,
			foundOrderSlot, 0, ClickType.PICKUP, mc.player);
		
		advanceTo(Stage.WAIT_CONTAINER_GUI);
	}
	
	private void handleWaitContainerGui()
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			if(screen.getMenu().slots.size() >= 36)
			{
				screenAttempts = 0;
				advanceTo(Stage.BLAST_ITEMS);
				return;
			}
		}
		
		screenAttempts++;
		if(screenAttempts >= MAX_SCREEN_ATTEMPTS)
		{
			screenAttempts = 0;
			advanceTo(Stage.OPEN_ORDERS);
		}
	}
	
	private void handleBlastItems()
	{
		if(!(mc.screen instanceof AbstractContainerScreen<?> screen))
		{
			advanceTo(Stage.DONE);
			return;
		}
		
		AbstractContainerMenu menu = screen.getMenu();
		int containerSlots = getContainerSlotCount(menu);
		List<Integer> slots = getSellableSlots();
		
		if(slots.isEmpty())
		{
			if(blastRetries++ < MAX_BLAST_RETRIES)
			{
				advanceTo(Stage.BLAST_ITEMS);
				return;
			}
			ChatUtils.warning("SellHandler: Nothing to sell.");
			closeAnyOpenContainer();
			advanceTo(Stage.DONE);
			return;
		}
		
		blastRetries = 0;
		
		// Shift-click ALL matching stacks into the order container at once.
		// The server will accept as many as the order allows and return the
		// rest to inventory — the re-sell loop in VERIFY_DELIVERY handles
		// whatever comes back, so there is no reason to throttle here.
		for(int invSlot : slots)
		{
			int handlerSlot = inventoryToHandlerSlot(invSlot, containerSlots);
			mc.gameMode.handleInventoryMouseClick(menu.containerId, handlerSlot,
				0, ClickType.QUICK_MOVE, mc.player);
		}
		
		advanceTo(Stage.WAIT_AFTER_BLAST);
	}
	
	private void handleWaitAfterBlast(long now)
	{
		if(now - stageStartMs < 600)
			return;
		
		advanceTo(Stage.CLOSE_FOR_CONFIRM);
	}
	
	private void handleCloseForConfirm()
	{
		closeAnyOpenContainer();
		advanceTo(Stage.WAIT_CONFIRM_GUI);
	}
	
	private void handleWaitConfirmGui()
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			String title = screen.getTitle().getString().toLowerCase();
			if(title.contains("confirm") || title.contains("deliver"))
			{
				advanceTo(Stage.CONFIRM);
				return;
			}
		}
		
		// No confirmation screen — jump straight to delivery verification
		advanceTo(Stage.VERIFY_DELIVERY);
	}
	
	private void handleConfirm()
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,
				15, 0, ClickType.PICKUP, mc.player);
		}
		
		advanceTo(Stage.VERIFY_DELIVERY);
	}
	
	private void handleVerifyDelivery(long now)
	{
		if(now - stageStartMs < 1_200)
			return;
		
		int invCountAfter = countInInventory(targetItem);
		int delta = invCountBefore - invCountAfter;
		
		if(delta > 0)
		{
			soldAmount += delta;
			invCountBefore = invCountAfter;
			ChatUtils.message(
				"SellHandler: Sold " + delta + "x. Total sold: " + soldAmount);
		}else
		{
			ChatUtils.warning("SellHandler: Sell may have failed (no change).");
		}
		
		closeAnyOpenContainer();
		
		// Re-sell: items came back to inventory — try to find another order
		if(invCountAfter > 0 && resellCycles < MAX_RESELL_CYCLES)
		{
			resellCycles++;
			ChatUtils.message("SellHandler: " + invCountAfter
				+ " item(s) remain — re-sell cycle " + resellCycles + "/"
				+ MAX_RESELL_CYCLES + "...");
			advanceTo(Stage.OPEN_ORDERS);
		}else
		{
			if(invCountAfter > 0)
				ChatUtils.warning("SellHandler: Max re-sell cycles reached. "
					+ invCountAfter + " item(s) unsold.");
			advanceTo(Stage.DONE);
		}
	}
	
	// =========================================================================
	// ORDER SELECTION
	// =========================================================================
	
	/**
	 * Finds the best-fit order slot in the market container.
	 *
	 * <p>
	 * <b>Key design note:</b> The market GUI represents every order as a
	 * custom-named placeholder item (often paper or a skull). We cannot match
	 * by {@code Item} type — we must match by the slot's hover name.
	 *
	 * <p>
	 * Priority:
	 * <ol>
	 * <li>Full-absorb order (remaining &ge; inventoryCount), highest price
	 * first.</li>
	 * <li>Fallback: largest remaining-capacity order above the price
	 * floor.</li>
	 * </ol>
	 */
	private int findBestOrderSlot(AbstractContainerMenu menu,
		int inventoryCount)
	{
		int containerSlots = getContainerSlotCount(menu);
		String expectedLabel = getExpectedLabel();
		
		int bestFullSlot = -1;
		int bestPartialSlot = -1;
		int bestPartialCap = -1;
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot slot = menu.slots.get(i);
			if(!slot.hasItem())
				continue;
			
			ItemStack stack = slot.getItem();
			
			// Match by display name, not Item type
			if(!slotMatchesTarget(stack, expectedLabel))
				continue;
			
			OrderInfo info = parseOrderTooltip(stack);
			if(info == null)
				continue;
			
			if(minSellThreshold > 0 && info.priceEach < minSellThreshold)
				continue;
			
			int remaining = info.totalRequested - info.delivered;
			if(remaining <= 0)
				continue;
			
			if(remaining >= inventoryCount)
			{
				// Server sorts highest-price first — take the first one we see
				if(bestFullSlot == -1)
					bestFullSlot = i;
			}else
			{
				if(remaining > bestPartialCap)
				{
					bestPartialCap = remaining;
					bestPartialSlot = i;
				}
			}
		}
		
		return (bestFullSlot != -1) ? bestFullSlot : bestPartialSlot;
	}
	
	/** Returns the expected label for the current target item, lower-cased. */
	private String getExpectedLabel()
	{
		String label = ORDER_DISPLAY_LABELS.get(targetItem);
		if(label != null)
			return label.toLowerCase();
		
		return new ItemStack(targetItem).getHoverName().getString()
			.toLowerCase();
	}
	
	/**
	 * Returns true if the slot's display name contains the expected label.
	 * Intentionally lenient so "Diamond" and "Diamonds" both match "diamond".
	 */
	private static boolean slotMatchesTarget(ItemStack stack,
		String expectedLabel)
	{
		return stack.getHoverName().getString().toLowerCase()
			.contains(expectedLabel);
	}
	
	// =========================================================================
	// TOOLTIP PARSING
	// =========================================================================
	
	/**
	 * Parses price and delivery progress from a slot's tooltip.
	 *
	 * @return {@link OrderInfo}, or {@code null} if the delivered line is
	 *         absent
	 */
	private OrderInfo parseOrderTooltip(ItemStack stack)
	{
		List<Component> tooltip = getTooltipLines(stack);
		double priceEach = 0.0;
		int delivered = 0;
		int totalRequested = 0;
		boolean foundPrice = false;
		boolean foundDelivered = false;
		
		for(Component line : tooltip)
		{
			String text = line.getString();
			
			if(!foundPrice)
			{
				Matcher pm = PRICE_PATTERN.matcher(text);
				if(pm.find())
				{
					String numStr = pm.group(1).replace(",", "");
					try
					{
						double num = Double.parseDouble(numStr);
						boolean isK = !pm.group(2).isEmpty();
						priceEach = isK ? num * 1_000.0 : num;
						foundPrice = true;
					}catch(NumberFormatException ignored)
					{}
				}
			}
			
			if(!foundDelivered)
			{
				Matcher dm = DELIVERED_PATTERN.matcher(text);
				if(dm.find())
				{
					int left = parseCount(dm.group(1));
					int right = parseCount(dm.group(2));
					if(left >= 0 && right > 0)
					{
						delivered = left;
						totalRequested = right;
						foundDelivered = true;
					}
				}
			}
			
			if(foundPrice && foundDelivered)
				break;
		}
		
		if(!foundDelivered)
			return null;
		
		return new OrderInfo(priceEach, delivered, totalRequested);
	}
	
	/**
	 * Parses a count token that may have commas and/or a k-suffix.
	 * <ul>
	 * <li>{@code "64"} → 64</li>
	 * <li>{@code "1.06k"} → 1060</li>
	 * <li>{@code "20k"} → 20000</li>
	 * <li>{@code "1,234"} → 1234</li>
	 * </ul>
	 *
	 * @return parsed integer, or -1 on failure
	 */
	private static int parseCount(String raw)
	{
		if(raw == null || raw.isBlank())
			return -1;
		
		String s = raw.trim().replace(",", "");
		boolean isK = s.toLowerCase().endsWith("k");
		if(isK)
			s = s.substring(0, s.length() - 1);
		
		try
		{
			double val = Double.parseDouble(s);
			return (int)(isK ? val * 1_000.0 : val);
		}catch(NumberFormatException e)
		{
			return -1;
		}
	}
	
	@SuppressWarnings("deprecation")
	private List<Component> getTooltipLines(ItemStack stack)
	{
		if(mc.player == null)
			return List.of();
		
		try
		{
			return stack.getTooltipLines(
				net.minecraft.world.item.Item.TooltipContext.EMPTY, mc.player,
				net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		}catch(Exception e)
		{
			return List.of();
		}
	}
	
	private static final class OrderInfo
	{
		final double priceEach;
		final int delivered;
		final int totalRequested;
		
		OrderInfo(double priceEach, int delivered, int totalRequested)
		{
			this.priceEach = priceEach;
			this.delivered = delivered;
			this.totalRequested = totalRequested;
		}
	}
	
	// =========================================================================
	// INVENTORY HELPERS
	// =========================================================================
	
	private List<Integer> getSellableSlots()
	{
		List<Integer> slots = new ArrayList<>();
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = mc.player.getInventory().getItem(i);
			if(!stack.isEmpty() && stack.getItem() == targetItem)
				slots.add(i);
		}
		return slots;
	}
	
	private static int inventoryToHandlerSlot(int invSlot, int containerSlots)
	{
		return (invSlot < 9) ? (containerSlots + 27 + invSlot)
			: (containerSlots + (invSlot - 9));
	}
	
	private static int getContainerSlotCount(AbstractContainerMenu menu)
	{
		return Math.max(0, menu.slots.size() - 36);
	}
	
	private int countInInventory(Item item)
	{
		if(mc.player == null || item == null)
			return 0;
		
		int count = 0;
		for(int i = 0; i < mc.player.getInventory().getContainerSize(); i++)
		{
			ItemStack s = mc.player.getInventory().getItem(i);
			if(!s.isEmpty() && s.getItem() == item)
				count += s.getCount();
		}
		return count;
	}
	
	private void advanceTo(Stage next)
	{
		stage = next;
		stageStartMs = System.currentTimeMillis();
		screenAttempts = 0;
	}
	
	private void closeAnyOpenContainer()
	{
		if(mc.screen instanceof AbstractContainerScreen<?>)
			mc.player.closeContainer();
	}
	
	private static long ticksToMs(int ticks)
	{
		return Math.max(DEFAULT_GUI_DELAY_MS, ticks * 50L);
	}
}
