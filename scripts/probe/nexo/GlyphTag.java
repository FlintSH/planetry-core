package com.nexomc.nexo.glyphs;

import dev.flint.planetrycore.probe.NexoFixture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Controlled native Nexo tag API shape, including the older player-aware resolver. */
public final class GlyphTag {
    public static final GlyphTag INSTANCE = new GlyphTag();

    public TagResolver getRESOLVER() { return resolver(null); }
    public TagResolver getResolverForPlayer(Player player) { return resolver(player); }

    private TagResolver resolver(Player player) {
        return TagResolver.resolver("glyph", (arguments, context) -> {
            if (!arguments.hasNext() || !arguments.pop().value().equals("probe"))
                throw context.newException("Unknown controlled glyph");
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Nexo glyph resolved off main thread");
            NexoFixture fixture = JavaPlugin.getPlugin(NexoFixture.class);
            if (player == null) fixture.glyphGlobalUses++;
            else {
                fixture.glyphPlayerUses++;
                fixture.lastGlyphPlayer = player.getName();
            }
            // A font proves the complete native resolver component survives integration.
            return Tag.selfClosingInserting(Component.text("\uE741").font(Key.key("nexo:probe")));
        });
    }
}
