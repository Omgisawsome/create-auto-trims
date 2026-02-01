package revilo.createautoarm.block;

import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import revilo.createautoarm.CreateAutoArmour;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("rawtypes")
public class AutoSmithingTableBlockEntity extends SmartBlockEntity {

    // Slot 0: Template, Slot 1: Base, Slot 2: Addition
    public final SingleVariantStorage<ItemVariant>[] inventory = new SingleVariantStorage[3];

    public AutoSmithingTableBlockEntity(BlockPos pos, BlockState state) {
        super(CreateAutoArmour.SMITHING_TABLE_BE, pos, state);
        for (int i = 0; i < 3; i++) {
            inventory[i] = new SingleVariantStorage<>() {
                @Override
                protected ItemVariant getBlankVariant() {
                    return ItemVariant.blank();
                }

                @Override
                protected long getCapacity(ItemVariant variant) {
                    return 1;
                }

                @Override
                protected void onFinalCommit() {
                    setChanged();
                    sendData();
                }
            };
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // No kinetic behaviours needed for the table itself
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;

        // Custom logic to detect if a Press is coming down on us
        // We check for the Mechanical Press BE above us
        // Presses are usually 2 blocks up when retracted, but the head moves
        BlockEntity beAbove = level.getBlockEntity(worldPosition.above());

        // Sometimes the press head is physically in the block above
        if (beAbove instanceof MechanicalPressBlockEntity press) {
            checkPressing(press);
        } else {
            BlockEntity beAbove2 = level.getBlockEntity(worldPosition.above(2));
            if (beAbove2 instanceof MechanicalPressBlockEntity press) {
                checkPressing(press);
            }
        }
    }

    private void checkPressing(MechanicalPressBlockEntity press) {
        // We check if the press is currently extending and hitting our hitbox
        // The method getRenderedHeadOffset needs to be called on the behaviour, not the BE
        PressingBehaviour pressingBehaviour = press.getPressingBehaviour();
        if (pressingBehaviour == null) return;

        float progress = pressingBehaviour.getRenderedHeadOffset(0); // 0 partial ticks

        // If the press is fully extended (approx value), try to craft
        if (progress > 0.5f && !inventory[0].isResourceBlank() && !inventory[1].isResourceBlank() && !inventory[2].isResourceBlank()) {
            attemptCraft();
        }
    }

    public boolean onUse(Player player, InteractionHand hand) {
        if (level == null || level.isClientSide) return true;
        ItemStack held = player.getItemInHand(hand);

        // Try to insert into the first available empty slot
        if (!held.isEmpty()) {
            for (SingleVariantStorage<ItemVariant> slot : inventory) {
                if (slot.isResourceBlank()) {
                    slot.variant = ItemVariant.of(held);
                    slot.amount = 1;
                    if (!player.isCreative()) held.shrink(1);
                    notifyUpdate();
                    return true;
                }
            }
        } else {
            // Take items out in reverse order (LIFO)
            for (int i = 2; i >= 0; i--) {
                if (!inventory[i].isResourceBlank()) {
                    player.setItemInHand(hand, inventory[i].variant.toStack());
                    inventory[i].variant = ItemVariant.blank();
                    inventory[i].amount = 0;
                    notifyUpdate();
                    return true;
                }
            }
        }
        return true;
    }

    public void attemptCraft() {
        if (level == null) return;

        SimpleContainer tempInv = new SimpleContainer(3);
        for (int i = 0; i < 3; i++) {
            if (inventory[i].isResourceBlank()) return; // Not full
            tempInv.setItem(i, inventory[i].variant.toStack((int) inventory[i].amount));
        }

        Optional<SmithingRecipe> match = level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, tempInv, level);

        if (match.isPresent()) {
            ItemStack result = match.get().assemble(tempInv, level.registryAccess());

            // Consume ingredients
            for (SingleVariantStorage<ItemVariant> slot : inventory) {
                slot.amount = 0;
                slot.variant = ItemVariant.blank();
            }

            // Place result in the first slot
            inventory[0].variant = ItemVariant.of(result);
            inventory[0].amount = 1;

            notifyUpdate();

            // Optional: Spawn particles or sound here to indicate success
            level.levelEvent(1044, worldPosition, 0); // Smithing Table sound
        }
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        for (int i = 0; i < 3; i++) {
            CompoundTag tag = new CompoundTag();
            tag.put("variant", inventory[i].variant.toNbt());
            tag.putLong("amount", inventory[i].amount);
            compound.put("Slot" + i, tag);
        }
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        for (int i = 0; i < 3; i++) {
            if (compound.contains("Slot" + i)) {
                CompoundTag tag = compound.getCompound("Slot" + i);
                if (tag.contains("variant")) {
                    inventory[i].variant = ItemVariant.fromNbt(tag.getCompound("variant"));
                    inventory[i].amount = tag.getLong("amount");
                }
            }
        }
    }
}