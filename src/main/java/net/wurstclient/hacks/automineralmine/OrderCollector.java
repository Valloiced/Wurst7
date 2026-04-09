/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.automineralmine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.util.ChatUtils;

public class OrderCollector
{
	// -------------------------------------------------------------------------
	// Stage machine — one linear path, no branches that loop back mid-collect
	// -------------------------------------------------------------------------
	private enum Stage
	{
		IDLE,
		OPEN_ORDERS, // send /orders command
		WAIT_ORDERS_GUI, // wait for any container screen to appear
		CLICK_NAV_SLOT, // click slot 51 to navigate to "Your Orders"
		WAIT_YOUR_ORDERS, // wait until title contains "Your Orders"
		FIND_ORDER_SLOT, // scan container for the target item order
		CLICK_ORDER_SLOT, // click the found order slot
		WAIT_EDIT_ORDER, // wait until title contains "Edit Order"
		CLICK_COLLECT_BUTTON, // click slot 13 (CHEST) or slot 15 (fallback)
		WAIT_COLLECT_GUI, // wait until title contains "Collect Items"
		COLLECT_ITEMS, // shift-click every matching stack
		FINISH_TASK // close screen, begin next task or go IDLE
	}
	
	private final Minecraft MC;
	
	private Stage stage = Stage.IDLE;
	
	/**
	 * Ticks to wait before executing the current stage. Decremented each tick.
	 */
	private int delay = 0;
	
	/**
	 * How long (ticks) to wait for a screen before giving up and restarting
	 * the current task from OPEN_ORDERS.
	 */
	private static final int SCREEN_TIMEOUT = 60; // 3 s at 20 tps
	private int waitTicks = 0;
	
	// -------------------------------------------------------------------------
	// Task queue
	// -------------------------------------------------------------------------
	public static class CollectTask
	{
		/** Items that satisfy this task (any one of them). */
		public final List<Item> candidates;
		/**
		 * How many total items to collect (informational; not hard-enforced).
		 */
		public final int amount;
		
		public CollectTask(List<Item> candidates, int amount)
		{
			this.candidates = candidates != null ? new ArrayList<>(candidates)
				: new ArrayList<>();
			this.amount = amount;
		}
	}
	
	private final Queue<CollectTask> taskQueue = new ArrayDeque<>();
	private CollectTask activeTask = null;
	
	// Which item we resolved to collect for this task
	private Item targetItem = null;
	// Slot index of the order we clicked on
	private int foundOrderSlot = -1;
	
	// Running totals
	private int collectedAmount = 0;
	private int slotsCollectedThisTask = 0;
	private final List<Item> foundValidOres = new ArrayList<>();
	
	// -------------------------------------------------------------------------
	// Settings (passed in from the parent hack via setSettings)
	// -------------------------------------------------------------------------
	
	/**
	 * Max number of individual slot shift-clicks per collect task.
	 * Prevents pulling far more than needed when an order has many stacks.
	 */
	private int maxSlotsToPull = 10;
	
	// -------------------------------------------------------------------------
	// Constructor
	// -------------------------------------------------------------------------
	public OrderCollector(Minecraft mc)
	{
		this.MC = mc;
	}
	
	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------
	
	/**
	 * Called by the parent hack to configure per-cycle settings.
	 *
	 * @param maxSlotsToPull
	 *            max shift-click operations per task
	 * @param minFillPct
	 *            unused in simplified flow (kept for API compat)
	 * @param collectPartial
	 *            unused in simplified flow (kept for API compat)
	 */
	public void setSettings(int maxSlotsToPull, int minFillPct,
		boolean collectPartial)
	{
		this.maxSlotsToPull = Math.max(1, maxSlotsToPull);
	}
	
	/** Queue a single-item collect task. */
	public void queueCollect(Item item, int amount)
	{
		if(item == null || amount <= 0)
			return;
		List<Item> candidates = new ArrayList<>();
		candidates.add(item);
		taskQueue.add(new CollectTask(candidates, amount));
	}
	
	/** Queue a task that accepts any item from the candidate list. */
	public void queueCollectAny(List<Item> candidates, int amount)
	{
		if(candidates == null || candidates.isEmpty() || amount <= 0)
			return;
		taskQueue.add(new CollectTask(new ArrayList<>(candidates), amount));
	}
	
	/** Begin processing queued tasks. No-op if already running. */
	public void start()
	{
		if(stage != Stage.IDLE || activeTask != null)
			return;
		beginNextTask();
	}
	
	/** Hard-reset everything. */
	public void reset()
	{
		closeScreenIfOpen();
		stage = Stage.IDLE;
		delay = 0;
		waitTicks = 0;
		taskQueue.clear();
		activeTask = null;
		targetItem = null;
		foundOrderSlot = -1;
		collectedAmount = 0;
		slotsCollectedThisTask = 0;
		foundValidOres.clear();
	}
	
	/** Gracefully abort and close any open screen. */
	public void abort()
	{
		reset();
	}
	
	public boolean isFinished()
	{
		return stage == Stage.IDLE && activeTask == null && taskQueue.isEmpty();
	}
	
	public boolean isRunning()
	{
		return !isFinished();
	}
	
	public int getCollectedAmount()
	{
		return collectedAmount;
	}
	
	public List<Item> getFoundValidOres()
	{
		return new ArrayList<>(foundValidOres);
	}
	
	// -------------------------------------------------------------------------
	// Tick — call every game tick
	// -------------------------------------------------------------------------
	public void tick()
	{
		if(stage == Stage.IDLE)
			return;
		if(delay > 0)
		{
			delay--;
			return;
		}
		if(MC.player == null || MC.gameMode == null)
			return;
		
		switch(stage)
		{
			case OPEN_ORDERS -> openOrders();
			case WAIT_ORDERS_GUI -> waitOrdersGUI();
			case CLICK_NAV_SLOT -> clickNavSlot();
			case WAIT_YOUR_ORDERS -> waitYourOrders();
			case FIND_ORDER_SLOT -> findOrderSlot();
			case CLICK_ORDER_SLOT -> clickOrderSlot();
			case WAIT_EDIT_ORDER -> waitEditOrder();
			case CLICK_COLLECT_BUTTON -> clickCollectButton();
			case WAIT_COLLECT_GUI -> waitCollectGUI();
			case COLLECT_ITEMS -> collectItems();
			case FINISH_TASK -> finishTask();
			default ->
				{
				}
		}
	}
	
	// -------------------------------------------------------------------------
	// Stage implementations
	// -------------------------------------------------------------------------
	
	private void openOrders()
	{
		if(MC.player == null)
			return;
		MC.player.connection.sendCommand("orders");
		stage = Stage.WAIT_ORDERS_GUI;
		waitTicks = 0;
		delay = 10; // ~0.5 s for the server to respond
	}
	
	/** Wait for any container GUI to appear. */
	private void waitOrdersGUI()
	{
		if(MC.screen instanceof AbstractContainerScreen<?>)
		{
			stage = Stage.CLICK_NAV_SLOT;
			delay = 3;
			return;
		}
		if(++waitTicks > SCREEN_TIMEOUT)
			timeout("orders GUI");
	}
	
	/**
	 * Click slot 51, which is the "Your Orders" navigation button.
	 * Verified that the container has enough slots before clicking.
	 */
	private void clickNavSlot()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
		{
			restart();
			return;
		}
		
		if(menu.slots.size() <= 51)
		{
			ChatUtils.error(
				"OrderCollector: GUI too small for slot 51 — unexpected layout.");
			finishCurrentTask();
			return;
		}
		
		click(menu, 51, ClickType.PICKUP);
		stage = Stage.WAIT_YOUR_ORDERS;
		waitTicks = 0;
		delay = 5;
	}
	
	/** Wait until the screen title contains "Your Orders". */
	private void waitYourOrders()
	{
		if(isScreen("Your Orders"))
		{
			stage = Stage.FIND_ORDER_SLOT;
			waitTicks = 0;
			delay = 3;
			return;
		}
		if(++waitTicks > SCREEN_TIMEOUT)
			timeout("Your Orders screen");
	}
	
	/**
	 * Scan all container slots (everything above the player hotbar) for any
	 * item matching the active task's candidate list.
	 *
	 * No tooltip parsing. We just look at the ItemStack's Item type.
	 */
	private void findOrderSlot()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
		{
			restart();
			return;
		}
		
		if(!isScreen("Your Orders"))
		{
			// Screen changed under us — restart
			restart();
			return;
		}
		
		int containerSlots = containerSlotCount(menu);
		foundOrderSlot = -1;
		targetItem = null;
		
		outer: for(int i = 0; i < containerSlots; i++)
		{
			Slot s = menu.slots.get(i);
			if(s == null || !s.hasItem())
				continue;
			
			ItemStack st = s.getItem();
			if(st.isEmpty())
				continue;
			
			for(Item candidate : activeTask.candidates)
			{
				if(st.getItem() == candidate)
				{
					foundOrderSlot = i;
					targetItem = candidate;
					break outer;
				}
			}
		}
		
		if(foundOrderSlot == -1)
		{
			ChatUtils
				.message("OrderCollector: No matching order found for task.");
			finishCurrentTask();
			return;
		}
		
		ChatUtils.message("OrderCollector: Found order at slot "
			+ foundOrderSlot + " ("
			+ targetItem.getName(new ItemStack(targetItem)).getString() + ")");
		
		stage = Stage.CLICK_ORDER_SLOT;
		delay = 3;
	}
	
	private void clickOrderSlot()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
		{
			restart();
			return;
		}
		
		if(foundOrderSlot < 0 || foundOrderSlot >= menu.slots.size())
		{
			ChatUtils.error("OrderCollector: foundOrderSlot out of range.");
			finishCurrentTask();
			return;
		}
		
		// Verify the slot still has our target item (server might have updated)
		ItemStack st = menu.slots.get(foundOrderSlot).getItem();
		if(st.isEmpty() || st.getItem() != targetItem)
		{
			ChatUtils.message(
				"OrderCollector: Slot changed before click — re-scanning.");
			stage = Stage.FIND_ORDER_SLOT;
			waitTicks = 0;
			delay = 5;
			return;
		}
		
		click(menu, foundOrderSlot, ClickType.PICKUP);
		stage = Stage.WAIT_EDIT_ORDER;
		waitTicks = 0;
		delay = 5;
	}
	
	/** Wait for "Edit Order" screen. */
	private void waitEditOrder()
	{
		if(isScreen("Edit Order"))
		{
			stage = Stage.CLICK_COLLECT_BUTTON;
			waitTicks = 0;
			delay = 3;
			return;
		}
		if(++waitTicks > SCREEN_TIMEOUT)
			timeout("Edit Order screen");
	}
	
	/**
	 * Click the collect button.
	 * Prefer slot 13 if it holds a CHEST item (some AH plugins use this),
	 * otherwise fall back to slot 15.
	 */
	private void clickCollectButton()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
		{
			restart();
			return;
		}
		
		if(!isScreen("Edit Order"))
		{
			restart();
			return;
		}
		
		int buttonSlot = -1;
		
		if(menu.slots.size() > 13)
		{
			Slot s13 = menu.slots.get(13);
			if(s13 != null && s13.hasItem()
				&& s13.getItem().getItem() == Items.CHEST)
				buttonSlot = 13;
		}
		
		if(buttonSlot == -1 && menu.slots.size() > 15)
			buttonSlot = 15;
		
		if(buttonSlot == -1)
		{
			ChatUtils.error(
				"OrderCollector: Cannot find collect button in Edit Order GUI.");
			finishCurrentTask();
			return;
		}
		
		click(menu, buttonSlot, ClickType.PICKUP);
		stage = Stage.WAIT_COLLECT_GUI;
		waitTicks = 0;
		delay = 5;
	}
	
	/** Wait for "Collect Items" screen. */
	private void waitCollectGUI()
	{
		if(isScreen("Collect Items"))
		{
			stage = Stage.COLLECT_ITEMS;
			waitTicks = 0;
			delay = 3;
			return;
		}
		if(++waitTicks > SCREEN_TIMEOUT)
			timeout("Collect Items screen");
	}
	
	/**
	 * Shift-click every stack of the target item in the container half of the
	 * GUI into the player's inventory. One stack per tick for reliability.
	 * Stops early if maxSlotsToPull is reached.
	 */
	private void collectItems()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
		{
			finishCurrentTask();
			return;
		}
		
		if(!isScreen("Collect Items"))
		{
			finishCurrentTask();
			return;
		}
		
		if(inventoryFull())
		{
			ChatUtils.warning(
				"OrderCollector: Inventory full — stopping collection.");
			finishCurrentTask();
			return;
		}
		
		if(slotsCollectedThisTask >= maxSlotsToPull)
		{
			ChatUtils.message("OrderCollector: Reached pull limit ("
				+ maxSlotsToPull + " slots).");
			finishCurrentTask();
			return;
		}
		
		int containerSlots = containerSlotCount(menu);
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot s = menu.slots.get(i);
			if(s == null || !s.hasItem())
				continue;
			
			ItemStack st = s.getItem();
			if(st.isEmpty() || st.getItem() != targetItem)
				continue;
			
			// Found a stack — shift-click it, then return and wait 2 ticks
			click(menu, i, ClickType.QUICK_MOVE);
			collectedAmount++;
			slotsCollectedThisTask++;
			
			if(!foundValidOres.contains(targetItem))
				foundValidOres.add(targetItem);
			
			delay = 2; // 2-tick gap between each shift-click
			return; // come back next tick for the next stack
		}
		
		// No more stacks found — done collecting
		ChatUtils.message("OrderCollector: Collection complete for this task.");
		finishCurrentTask();
	}
	
	/**
	 * Close the screen (if open) and move on to the next queued task,
	 * or go IDLE if the queue is empty.
	 */
	private void finishTask()
	{
		closeScreenIfOpen();
		beginNextTask();
	}
	
	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------
	
	private void beginNextTask()
	{
		activeTask = taskQueue.poll();
		targetItem = null;
		foundOrderSlot = -1;
		slotsCollectedThisTask = 0;
		
		if(activeTask == null)
		{
			stage = Stage.IDLE;
			return;
		}
		
		String label = activeTask.candidates.isEmpty() ? "unknown"
			: activeTask.candidates.get(0)
				.getName(new ItemStack(activeTask.candidates.get(0)))
				.getString();
		
		ChatUtils.message("OrderCollector: Starting task — " + activeTask.amount
			+ "x " + label);
		
		stage = Stage.OPEN_ORDERS;
		delay = 5;
	}
	
	/**
	 * Called when a screen-wait times out.
	 * Restarts the current task from OPEN_ORDERS rather than aborting entirely.
	 */
	private void timeout(String screenName)
	{
		ChatUtils.warning("OrderCollector: Timed out waiting for " + screenName
			+ " — restarting task.");
		closeScreenIfOpen();
		restart();
	}
	
	/**
	 * Restart the current task from the top (OPEN_ORDERS).
	 * Does NOT discard the task — useful for transient GUI failures.
	 */
	private void restart()
	{
		closeScreenIfOpen();
		stage = Stage.OPEN_ORDERS;
		waitTicks = 0;
		delay = 10;
	}
	
	/**
	 * Discard the current task and begin the next one.
	 */
	private void finishCurrentTask()
	{
		closeScreenIfOpen();
		activeTask = null;
		targetItem = null;
		foundOrderSlot = -1;
		slotsCollectedThisTask = 0;
		stage = Stage.FINISH_TASK;
		delay = 5;
	}
	
	private void closeScreenIfOpen()
	{
		if(MC.player != null && MC.screen instanceof AbstractContainerScreen<?>)
			MC.player.closeContainer();
	}
	
	private AbstractContainerMenu getMenu()
	{
		if(!(MC.screen instanceof AbstractContainerScreen<?> screen))
			return null;
		return screen.getMenu();
	}
	
	private boolean isScreen(String titleFragment)
	{
		if(!(MC.screen instanceof AbstractContainerScreen<?> screen))
			return false;
		return screen.getTitle().getString().contains(titleFragment);
	}
	
	private void click(AbstractContainerMenu menu, int slot, ClickType type)
	{
		MC.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type,
			MC.player);
	}
	
	private static int containerSlotCount(AbstractContainerMenu menu)
	{
		return Math.max(0, menu.slots.size() - 36);
	}
	
	private boolean inventoryFull()
	{
		if(MC.player == null)
			return true;
		for(int i = 0; i < MC.player.getInventory().getContainerSize(); i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(s.isEmpty())
				return false;
			if(s.getItem() == targetItem && s.getCount() < s.getMaxStackSize())
				return false;
		}
		return true;
	}
}
