package net.tunamods.customglint.module.recipe;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.function.Function;

/** Serializer for the id-only smithing recipes: no JSON fields, no wire payload - just rebuild from the id. */
public class SimpleSmithingSerializer<T extends Recipe<?>> implements RecipeSerializer<T> {
    private final Function<ResourceLocation, T> factory;

    public SimpleSmithingSerializer(Function<ResourceLocation, T> factory) {
        this.factory = factory;
    }

    @Override
    public T fromJson(ResourceLocation id, JsonObject json) {
        return factory.apply(id);
    }

    @Override
    public T fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        return factory.apply(id);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, T recipe) {
    }
}
