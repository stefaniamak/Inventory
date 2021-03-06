/*
 * Copyright 2015 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.logic.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.input.ButtonState;
import org.terasology.input.binds.inventory.InventoryButton;
import org.terasology.logic.characters.CharacterComponent;
import org.terasology.logic.characters.interactions.InteractionUtil;
import org.terasology.logic.players.LocalPlayer;
import org.terasology.network.ClientComponent;
import org.terasology.registry.In;
import org.terasology.rendering.nui.ControlWidget;
import org.terasology.rendering.nui.NUIManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RegisterSystem(RegisterMode.CLIENT)
public class InventoryUIClientSystem extends BaseComponentSystem {

    EntityRef movingItemItem = EntityRef.NULL;

    int movingItemCount = 0;

    private static final Logger logger = LoggerFactory.getLogger(InventoryUIClientSystem.class);

    @In
    private NUIManager nuiManager;

    @In
    private InventoryManager inventoryManager;

    @In
    private LocalPlayer localPlayer;

    @Override
    public void initialise() {
        nuiManager.getHUD().addHUDElement("inventoryHud");
        nuiManager.addOverlay("Inventory:transferItemCursor", ControlWidget.class);
    }

    @ReceiveEvent(components = ClientComponent.class)
    public void onToggleInventory(InventoryButton event, EntityRef entity) {
        if (event.getState() == ButtonState.DOWN) {
            nuiManager.toggleScreen("Inventory:inventoryScreen");
            event.consume();
        }
    }

    /*
     * At the activation of the inventory the current dialog needs to be closed instantly.
     *
     * The close of the dialog triggers {@link #onScreenLayerClosed} which resets the
     * interactionTarget.
     */
    @ReceiveEvent(components = ClientComponent.class, priority = EventPriority.PRIORITY_HIGH)
    public void onToggleInventory(InventoryButton event, EntityRef entity, ClientComponent clientComponent) {
        if (event.getState() != ButtonState.DOWN) {
            return;
        }

        EntityRef character = clientComponent.character;
        ResourceUrn activeInteractionScreenUri = InteractionUtil.getActiveInteractionScreenUri(character);
        if (activeInteractionScreenUri != null) {
            InteractionUtil.cancelInteractionAsClient(character);
            // do not consume the event, so that the inventory will still open
        }
    }

    private EntityRef getTransferEntity() {
        return localPlayer.getCharacterEntity().getComponent(CharacterComponent.class).movingItem;
    }

    @Override
    public void preAutoSave() {
        /*
          The code below was originally taken from moveItemSmartly() in
          InventoryCell.class and slightly modified to work here.

          The way items are being moved to and from the hotbar is really
          similar to what was needed here to take them out of the transfer
          slot and sort them into the inventory.
        */
        EntityRef playerEntity = localPlayer.getCharacterEntity();
        EntityRef movingItemSlot = playerEntity.getComponent(CharacterComponent.class).movingItem;

        movingItemItem = inventoryManager.getItemInSlot(movingItemSlot, 0);

        movingItemCount = inventoryManager.getStackSize(movingItemItem);

        EntityRef fromEntity = movingItemSlot;
        int fromSlot = 0;

        InventoryComponent playerInventory = playerEntity.getComponent(InventoryComponent.class);

        if (movingItemItem != EntityRef.NULL) {

            if (playerInventory == null) {
                return;
            }
            CharacterComponent characterComponent = playerEntity.getComponent(CharacterComponent.class);
            if (characterComponent == null) {
                logger.error("Character entity of player had no character component");
                return;
            }
            int totalSlotCount = playerInventory.itemSlots.size();

            EntityRef interactionTarget = characterComponent.predictedInteractionTarget;
            InventoryComponent interactionTargetInventory = interactionTarget.getComponent(InventoryComponent.class);

            EntityRef targetEntity;
            List<Integer> toSlots = new ArrayList<>(totalSlotCount);
            if (fromEntity.equals(playerEntity) && interactionTarget.exists() && interactionTargetInventory != null) {
                targetEntity = interactionTarget;
                toSlots = IntStream.range(0, interactionTargetInventory.itemSlots.size()).boxed().collect(Collectors.toList());
            } else {
                targetEntity = playerEntity;
                toSlots = IntStream.range(0, totalSlotCount).boxed().collect(Collectors.toList());
            }

            inventoryManager.moveItemToSlots(getTransferEntity(), fromEntity, fromSlot, targetEntity, toSlots);
        }
    }

    @Override
    public void postAutoSave() {
        if (movingItemItem != EntityRef.NULL) {
            EntityRef playerEntity = localPlayer.getCharacterEntity();
            EntityRef movingItem = playerEntity.getComponent(CharacterComponent.class).movingItem;

            EntityRef targetEntity = movingItem;
            EntityRef fromEntity = playerEntity;

            int currentSlot = playerEntity.getComponent(InventoryComponent.class).itemSlots.size() - 1;

            while (currentSlot >= 0 && movingItemCount > 0) {

                EntityRef currentItem = inventoryManager.getItemInSlot(playerEntity, currentSlot);
                int currentItemCount = inventoryManager.getStackSize(currentItem);
                boolean correctItem = (currentItem == movingItemItem);

                if (correctItem) {
                    int count = Math.min(movingItemCount, currentItemCount);
                    inventoryManager.moveItem(fromEntity, getTransferEntity(), currentSlot, targetEntity, 0, count);
                    movingItemCount -= count;
                }

                currentSlot--;
            }
        }

        movingItemItem = EntityRef.NULL;
    }
}
