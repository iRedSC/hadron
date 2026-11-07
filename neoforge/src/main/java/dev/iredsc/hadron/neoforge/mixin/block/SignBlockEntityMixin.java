package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import java.util.List;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** A player writing a sign, with the lines editable by listeners. */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    @WrapMethod(method = "updateSignText")
    private void hadron$signChange(Player player, boolean front, List<FilteredText> text,
            Operation<Void> original) {
        List<FilteredText> edited = BlockHooks.signChange((SignBlockEntity) (Object) this, player, front, text);
        if (edited != null) original.call(player, front, edited);
    }
}
