/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.automineralmine;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.util.ChatUtils;

public final class SellHandler
{
	private final Minecraft mc;
	
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
		VERIFY_PLACEMENT,
		CLOSE_FOR_CONFIRM,
		WAIT_CONFIRM_GUI,
		CONFIRM,
		VERIFY_DELIVERY,
		DONE
	}
	
	private Stage stage = Stage.IDLE;
	private long stageStartMs = 0L;
	private int screenAttempts = 0;
	
	private int maxSlotsToPull = 10;
	private int minFillPct = 0;
	private boolean filterByUsername = false;
	private String targetUsername = "";
	
	private Item targetItem = null;
	private int targetAmount = 0;
	private int soldAmount = 0;
	private int invCountBefore = 0;
	
	private int foundOrderSlot = -1;
	private int blastRetries = 0;
	private int deliveryRetries = 0;
	
	private static final long SCREEN_TIMEOUT_MS = 6000;
	private static final int MAX_SCREEN_ATTEMPTS = 30;
	private static final int MAX_BLAST_RETRIES = 3;
	private static final int MAX_DELIVERY_RETRIES = 3;
	private static final long GUI_DELAY_MS = 300L;
	
	public SellHandler(Minecraft mc)
	{
		this.mc = mc;
	}
	
	// =========================
	// PUBLIC API
	// =========================
	
	public void setSettings(int maxSlotsToPull, int minFillPct,
		boolean filterByUsername)
	{
		this.maxSlotsToPull = Math.max(1, maxSlotsToPull);
		this.minFillPct = minFillPct;
		this.filterByUsername = filterByUsername;
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
		deliveryRetries = 0;
		foundOrderSlot = -1;
		invCountBefore = countInInventory(targetItem);
		
		advanceTo(Stage.OPEN_ORDERS);
	}
	
	public void tick()
	{
		if(stage == Stage.IDLE || stage == Stage.DONE)
			return;
		
		long now = System.currentTimeMillis();
		
		if(stage != Stage.BLAST_ITEMS && stage != Stage.WAIT_AFTER_BLAST)
		{
			if(now - stageStartMs < GUI_DELAY_MS)
				return;
		}
		
		switch(stage)
		{
			case OPEN_ORDERS -> handleOpenOrders(now);
			case WAIT_MARKET_GUI -> handleWaitMarketGui(now);
			case FIND_ORDER -> handleFindOrder(now);
			case CLICK_ORDER -> handleClickOrder(now);
			case WAIT_CONTAINER_GUI -> handleWaitContainerGui(now);
			case BLAST_ITEMS -> handleBlastItems(now);
			case WAIT_AFTER_BLAST -> handleWaitAfterBlast(now);
			case VERIFY_PLACEMENT -> handleVerifyPlacement(now);
			case CLOSE_FOR_CONFIRM -> handleCloseForConfirm(now);
			case WAIT_CONFIRM_GUI -> handleWaitConfirmGui(now);
			case CONFIRM -> handleConfirm(now);
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
		deliveryRetries = 0;
		closeAnyOpenContainer();
	}
	
	// =========================
	// STAGES
	// =========================
	
	private void handleOpenOrders(long now)
	{
		if(filterByUsername && !targetUsername.isEmpty())
			mc.player.connection.sendCommand("orders " + targetUsername);
		else
		{
			String itemName =
				new ItemStack(targetItem).getHoverName().getString();
			mc.player.connection.sendCommand("orders " + itemName);
		}
		
		advanceTo(Stage.WAIT_MARKET_GUI);
	}
	
	private void handleWaitMarketGui(long now)
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			String title = screen.getTitle().getString().toLowerCase();
			if(title.contains("page"))
			{
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
			advanceTo(Stage.OPEN_ORDERS);
		}
	}
	
	private void handleFindOrder(long now)
	{
		if(!(mc.screen instanceof AbstractContainerScreen<?> screen))
		{
			advanceTo(Stage.DONE);
			return;
		}
		
		AbstractContainerMenu menu = screen.getMenu();
		foundOrderSlot = findSuitableOrderSlot(menu);
		
		if(foundOrderSlot == -1)
		{
			ChatUtils.warning("SellHandler: No order found.");
			closeAnyOpenContainer();
			advanceTo(Stage.DONE);
			return;
		}
		
		advanceTo(Stage.CLICK_ORDER);
	}
	
	private void handleClickOrder(long now)
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
	
	private void handleWaitContainerGui(long now)
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			if(screen.getMenu().slots.size() >= 36)
			{
				advanceTo(Stage.BLAST_ITEMS);
				return;
			}
		}
		
		screenAttempts++;
		if(screenAttempts >= MAX_SCREEN_ATTEMPTS)
			advanceTo(Stage.OPEN_ORDERS);
	}
	
	private void handleBlastItems(long now)
	{
		if(!(mc.screen instanceof AbstractContainerScreen<?> screen))
		{
			advanceTo(Stage.DONE);
			return;
		}
		
		AbstractContainerMenu menu = screen.getMenu();
		int containerSlots = getContainerSlotCount(menu);
		
		List<Integer> slots = getSellableSlots();
		
		int clicked = 0;
		for(int i = 0; i < slots.size() && clicked < maxSlotsToPull; i++)
		{
			int handlerSlot =
				inventoryToHandlerSlot(slots.get(i), containerSlots);
			
			mc.gameMode.handleInventoryMouseClick(menu.containerId, handlerSlot,
				0, ClickType.QUICK_MOVE, mc.player);
			
			clicked++;
		}
		
		advanceTo(Stage.WAIT_AFTER_BLAST);
	}
	
	private void handleWaitAfterBlast(long now)
	{
		if(now - stageStartMs < 600)
			return;
		
		advanceTo(Stage.VERIFY_PLACEMENT);
	}
	
	private void handleVerifyPlacement(long now)
	{
		advanceTo(Stage.CLOSE_FOR_CONFIRM);
	}
	
	private void handleCloseForConfirm(long now)
	{
		closeAnyOpenContainer();
		advanceTo(Stage.WAIT_CONFIRM_GUI);
	}
	
	private void handleWaitConfirmGui(long now)
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
		
		advanceTo(Stage.VERIFY_DELIVERY);
	}
	
	private void handleConfirm(long now)
	{
		if(mc.screen instanceof AbstractContainerScreen<?> screen)
		{
			mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,
				11, 0, ClickType.PICKUP, mc.player);
		}
		
		advanceTo(Stage.VERIFY_DELIVERY);
	}
	
	private void handleVerifyDelivery(long now)
	{
		if(now - stageStartMs < 1000)
			return;
		
		int invCountAfter = countInInventory(targetItem);
		int delta = invCountBefore - invCountAfter;
		
		if(delta > 0)
		{
			soldAmount = delta;
			ChatUtils.message("SellHandler: Sold " + delta + " item(s).");
		}else
		{
			ChatUtils.warning("SellHandler: Sell failed.");
		}
		
		advanceTo(Stage.DONE);
	}
	
	// =========================
	// HELPERS
	// =========================
	
	private int findSuitableOrderSlot(AbstractContainerMenu menu)
	{
		int containerSlots = getContainerSlotCount(menu);
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot slot = menu.slots.get(i);
			if(slot.hasItem() && slot.getItem().getItem() == targetItem)
				return i;
		}
		
		return -1;
	}
	
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
}
