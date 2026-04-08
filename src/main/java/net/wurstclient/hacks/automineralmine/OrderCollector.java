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
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.wurstclient.util.ChatUtils;

public class OrderCollector
{
	private final Minecraft MC;
	
	private enum Stage
	{
		IDLE,
		OPEN_ORDERS,
		WAIT_ORDERS_GUI,
		CLICK_SLOT_51,
		WAIT_YOUR_ORDERS,
		CLICK_TARGET_ORDER_SLOT,
		WAIT_EDIT_ORDER_GUI,
		CLICK_COLLECT_BUTTON,
		WAIT_COLLECTION_GUI,
		SCAN_AND_COLLECT,
		QUICKMOVE_SLOT,
		VERIFY_QUICKMOVE,
		FINISH_CYCLE
	}
	
	private Stage stage = Stage.IDLE;
	private int delay = 0;
	
	public static class CollectTask
	{
		public final List<Item> candidates;
		public final int amount;
		
		public CollectTask(List<Item> candidates, int amount)
		{
			this.candidates = candidates == null ? new ArrayList<>()
				: new ArrayList<>(candidates);
			this.amount = amount;
		}
	}
	
	private final Queue<CollectTask> taskQueue = new ArrayDeque<>();
	private CollectTask activeTask;
	private int collectedThisTask;
	
	private Item targetItem;
	private int maxSlotsToPull = 10;
	private int minFillThreshold = 75;
	private boolean collectPartial = true;
	private int collectedAmount = 0;
	
	private int nextScanIndex = 0;
	private int lastQuickMoveSlot = -1;
	private int quickMoveVerifyPrevCount = 0;
	private int quickMoveRetries = 0;
	private int pulledSlots = 0;
	
	private final List<Item> foundValidOres = new ArrayList<>();
	
	public OrderCollector(Minecraft mc)
	{
		this.MC = mc;
	}
	
	public void setSettings(int maxSlotsToPull, int minFillThreshold,
		boolean collectPartial)
	{
		this.maxSlotsToPull = maxSlotsToPull;
		this.minFillThreshold = minFillThreshold;
		this.collectPartial = collectPartial;
	}
	
	public int getCollectedAmount()
	{
		return collectedAmount;
	}
	
	public List<Item> getFoundValidOres()
	{
		return new ArrayList<>(foundValidOres);
	}
	
	public void queueCollect(Item item, int amount)
	{
		if(item == null || amount <= 0)
			return;
		
		List<Item> candidates = new ArrayList<>();
		candidates.add(item);
		taskQueue.add(new CollectTask(candidates, amount));
	}
	
	public void queueCollectAny(List<Item> candidates, int amount)
	{
		if(candidates == null || candidates.isEmpty() || amount <= 0)
			return;
		
		taskQueue.add(new CollectTask(candidates, amount));
	}
	
	public void start()
	{
		if(stage != Stage.IDLE || activeTask != null)
			return;
		
		beginNextTask();
	}
	
	public void reset()
	{
		stage = Stage.IDLE;
		delay = 0;
		taskQueue.clear();
		activeTask = null;
		targetItem = null;
		nextScanIndex = 0;
		lastQuickMoveSlot = -1;
		quickMoveVerifyPrevCount = 0;
		quickMoveRetries = 0;
		pulledSlots = 0;
		collectedThisTask = 0;
		collectedAmount = 0;
		foundValidOres.clear();
	}
	
	public void abort()
	{
		if(MC.player != null && MC.screen instanceof AbstractContainerScreen<?>)
			MC.player.closeContainer();
		
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
			case CLICK_SLOT_51 -> clickSlot51();
			case WAIT_YOUR_ORDERS -> waitYourOrders();
			case CLICK_TARGET_ORDER_SLOT -> clickTargetOrderSlot();
			case WAIT_EDIT_ORDER_GUI -> waitEditOrderGUI();
			case CLICK_COLLECT_BUTTON -> clickCollectButton();
			case WAIT_COLLECTION_GUI -> waitCollectionGUI();
			case SCAN_AND_COLLECT -> scanAndCollect();
			case QUICKMOVE_SLOT -> quickMoveSlot();
			case VERIFY_QUICKMOVE -> verifyQuickMove();
			case FINISH_CYCLE -> finishCycle();
			case IDLE ->
				{
				}
		}
	}
	
	private void beginNextTask()
	{
		activeTask = taskQueue.poll();
		collectedThisTask = 0;
		pulledSlots = 0;
		nextScanIndex = 0;
		lastQuickMoveSlot = -1;
		quickMoveVerifyPrevCount = 0;
		quickMoveRetries = 0;
		targetItem = null;
		
		if(activeTask == null)
		{
			stage = Stage.IDLE;
			return;
		}
		
		stage = Stage.OPEN_ORDERS;
		delay = 10;
		
		String label = activeTask.candidates.isEmpty() ? "unknown"
			: activeTask.candidates.get(0)
				.getName(new ItemStack(activeTask.candidates.get(0)))
				.getString();
		ChatUtils
			.message("Collector: Starting " + activeTask.amount + "x " + label);
	}
	
	private void openOrders()
	{
		if(MC.player == null)
			return;
		
		MC.player.connection.sendCommand("orders");
		stage = Stage.WAIT_ORDERS_GUI;
		delay = 20;
	}
	
	private void waitOrdersGUI()
	{
		if(MC.screen instanceof AbstractContainerScreen<?>)
		{
			stage = Stage.CLICK_SLOT_51;
			delay = 5;
		}else
			retry();
	}
	
	private void clickSlot51()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
			return;
		
		if(menu.slots.size() > 51)
		{
			click(menu, 51, ClickType.PICKUP);
			stage = Stage.WAIT_YOUR_ORDERS;
			delay = 10;
		}else
		{
			ChatUtils.error("Collect: Unexpected GUI layout.");
			stage = Stage.FINISH_CYCLE;
			delay = 0;
		}
	}
	
	private void waitYourOrders()
	{
		if(isScreen("Your Orders"))
		{
			stage = Stage.CLICK_TARGET_ORDER_SLOT;
			delay = 5;
		}else
			retry();
	}
	
	private void clickTargetOrderSlot()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
			return;
		
		int containerSlots = getContainerSlotCount(menu);
		List<Item> availableItems = new ArrayList<>();
		
		for(int i = 0; i < containerSlots; i++)
		{
			Slot s = menu.slots.get(i);
			if(s == null || !s.hasItem())
				continue;
			
			ItemStack st = s.getItem();
			if(st == null || st.isEmpty())
				continue;
			
			if(shouldCollectOrder(st))
			{
				Item item = st.getItem();
				if(activeTask == null || activeTask.candidates.isEmpty())
				{
					if(!availableItems.contains(item))
						availableItems.add(item);
					continue;
				}
				
				if(activeTask.candidates.contains(item)
					&& !availableItems.contains(item))
				{
					availableItems.add(item);
				}
			}
		}
		
		if(availableItems.isEmpty())
		{
			ChatUtils.message("Collect: No matching orders found.");
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		Item chosenItem = null;
		if(activeTask != null && !activeTask.candidates.isEmpty())
		{
			for(Item candidate : activeTask.candidates)
			{
				if(availableItems.contains(candidate))
				{
					chosenItem = candidate;
					break;
				}
			}
		}else
			chosenItem = availableItems.get(0);
		
		if(chosenItem == null)
		{
			ChatUtils.message("Collect: No matching orders found.");
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		targetItem = chosenItem;
		
		int foundIndex = -1;
		for(int i = 0; i < containerSlots; i++)
		{
			Slot s = menu.slots.get(i);
			if(s == null || !s.hasItem())
				continue;
			
			ItemStack st = s.getItem();
			if(st.getItem() == targetItem && shouldCollectOrder(st))
			{
				foundIndex = i;
				break;
			}
		}
		
		if(foundIndex == -1)
		{
			ChatUtils.message("Collect: No matching orders found.");
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		click(menu, foundIndex, ClickType.PICKUP);
		stage = Stage.WAIT_EDIT_ORDER_GUI;
		delay = 10;
	}
	
	private void waitEditOrderGUI()
	{
		if(isScreen("Edit Order"))
		{
			stage = Stage.CLICK_COLLECT_BUTTON;
			delay = 5;
		}else
			retry();
	}
	
	private void clickCollectButton()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
			return;
		
		if(menu.slots.size() > 13)
		{
			Slot slot13 = menu.slots.get(13);
			if(slot13 != null && slot13.hasItem()
				&& slot13.getItem().getItem() == Items.CHEST)
			{
				click(menu, 13, ClickType.PICKUP);
				stage = Stage.WAIT_COLLECTION_GUI;
				delay = 10;
				return;
			}
		}
		
		if(menu.slots.size() > 15)
		{
			click(menu, 15, ClickType.PICKUP);
			stage = Stage.WAIT_COLLECTION_GUI;
			delay = 10;
		}else
		{
			stage = Stage.OPEN_ORDERS;
			delay = 10;
		}
	}
	
	private void waitCollectionGUI()
	{
		if(isScreen("Collect Items"))
		{
			nextScanIndex = 0;
			stage = Stage.SCAN_AND_COLLECT;
			delay = 5;
		}else
			retry();
	}
	
	private void scanAndCollect()
	{
		AbstractContainerMenu menu = getMenu();
		if(menu == null)
			return;
		
		if(pulledSlots >= maxSlotsToPull)
		{
			ChatUtils.message("Collect: Reached pull limit.");
			if(MC.player != null)
				MC.player.closeContainer();
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		if(collectedThisTask >= activeTask.amount)
		{
			if(MC.player != null)
				MC.player.closeContainer();
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		if(nextScanIndex >= menu.slots.size())
			nextScanIndex = 0;
		
		int found = -1;
		for(int i = nextScanIndex; i < menu.slots.size(); i++)
		{
			Slot s = menu.slots.get(i);
			if(s != null && s.hasItem() && s.getItem().getItem() == targetItem)
			{
				found = i;
				break;
			}
		}
		
		if(found == -1)
		{
			if(nextScanIndex > 0)
			{
				nextScanIndex = 0;
				return;
			}
			
			ChatUtils.message("Collect: No more items in collection GUI.");
			if(MC.player != null)
				MC.player.closeContainer();
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		ItemStack toMove = menu.slots.get(found).getItem();
		if(!canAcceptStack(toMove))
		{
			ChatUtils.warning("Collect: Inventory full.");
			if(MC.player != null)
				MC.player.closeContainer();
			stage = Stage.FINISH_CYCLE;
			delay = 0;
			return;
		}
		
		lastQuickMoveSlot = found;
		quickMoveVerifyPrevCount = countInInventory(targetItem);
		quickMoveRetries = 0;
		
		click(menu, found, ClickType.QUICK_MOVE);
		stage = Stage.QUICKMOVE_SLOT;
		delay = 5;
	}
	
	private void quickMoveSlot()
	{
		stage = Stage.VERIFY_QUICKMOVE;
		delay = 5;
	}
	
	private void verifyQuickMove()
	{
		if(activeTask == null || targetItem == null)
		{
			beginNextTask();
			return;
		}
		
		int newCount = countInInventory(targetItem);
		if(newCount > quickMoveVerifyPrevCount)
		{
			int moved = newCount - quickMoveVerifyPrevCount;
			pulledSlots++;
			collectedAmount++;
			collectedThisTask += moved;
			nextScanIndex = lastQuickMoveSlot + 1;
			lastQuickMoveSlot = -1;
			
			if(collectedThisTask >= activeTask.amount)
			{
				if(MC.player != null
					&& MC.screen instanceof AbstractContainerScreen<?>)
					MC.player.closeContainer();
				
				stage = Stage.FINISH_CYCLE;
				delay = 0;
				return;
			}
			
			stage = Stage.SCAN_AND_COLLECT;
			delay = 5;
		}else
		{
			quickMoveRetries++;
			if(quickMoveRetries <= 2)
			{
				if(MC.screen instanceof AbstractContainerScreen<?> screen)
				{
					click(screen.getMenu(), lastQuickMoveSlot,
						ClickType.QUICK_MOVE);
					stage = Stage.QUICKMOVE_SLOT;
					delay = 5;
				}else
				{
					stage = Stage.FINISH_CYCLE;
					delay = 0;
				}
			}else
			{
				nextScanIndex = lastQuickMoveSlot + 1;
				lastQuickMoveSlot = -1;
				stage = Stage.SCAN_AND_COLLECT;
				delay = 5;
			}
		}
	}
	
	private void finishCycle()
	{
		if(MC.player != null && MC.screen instanceof AbstractContainerScreen<?>)
			MC.player.closeContainer();
		
		activeTask = null;
		targetItem = null;
		
		if(taskQueue.isEmpty())
		{
			stage = Stage.IDLE;
			return;
		}
		
		beginNextTask();
	}
	
	private AbstractContainerMenu getMenu()
	{
		if(!(MC.screen instanceof AbstractContainerScreen<?> screen))
			return null;
		
		return screen.getMenu();
	}
	
	private boolean isScreen(String name)
	{
		if(!(MC.screen instanceof AbstractContainerScreen<?> screen))
			return false;
		
		return screen.getTitle().getString().contains(name);
	}
	
	private void click(AbstractContainerMenu menu, int slot, ClickType type)
	{
		MC.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type,
			MC.player);
	}
	
	private void retry()
	{
		stage = Stage.OPEN_ORDERS;
		delay = 20;
	}
	
	private static int getContainerSlotCount(AbstractContainerMenu menu)
	{
		return Math.max(0, menu.slots.size() - 36);
	}
	
	private boolean shouldCollectOrder(ItemStack orderStack)
	{
		if(orderStack == null || orderStack.isEmpty())
			return false;
		
		try
		{
			List<Component> tooltip =
				orderStack.getTooltipLines(Item.TooltipContext.EMPTY, MC.player,
					TooltipFlag.Default.NORMAL);
			
			for(Component line : tooltip)
			{
				String text = line.getString();
				
				if(text.contains("Completed"))
					return true;
				
				if(collectPartial && text.contains("/"))
				{
					try
					{
						String[] parts =
							text.replaceAll("[^0-9/]", "").split("/");
						if(parts.length == 2)
						{
							int delivered = Integer.parseInt(parts[0].trim());
							int total = Integer.parseInt(parts[1].trim());
							if(total > 0 && (delivered * 100.0
								/ total) >= minFillThreshold)
								return true;
						}
					}catch(NumberFormatException ignored)
					{}
				}
			}
		}catch(Exception ignored)
		{}
		
		return false;
	}
	
	private boolean canAcceptStack(ItemStack stack)
	{
		if(stack == null || stack.isEmpty() || MC.player == null)
			return false;
		
		for(int i = 0; i < MC.player.getInventory().getContainerSize(); i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(s.isEmpty())
				return true;
			
			if(s.getItem() == stack.getItem()
				&& s.getCount() < s.getMaxStackSize())
				return true;
		}
		
		return false;
	}
	
	private int countInInventory(Item item)
	{
		if(MC.player == null || item == null)
			return 0;
		
		int count = 0;
		for(int i = 0; i < MC.player.getInventory().getContainerSize(); i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(!s.isEmpty() && s.getItem() == item)
				count += s.getCount();
		}
		return count;
	}
}
