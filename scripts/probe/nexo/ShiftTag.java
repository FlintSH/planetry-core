package com.nexomc.nexo.glyphs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Controlled shape of Nexo's companion native shift resolver. */
public final class ShiftTag {
    public static final ShiftTag INSTANCE = new ShiftTag();

    public TagResolver getRESOLVER() {
        return TagResolver.resolver("shift", (arguments, context) -> {
            if (!arguments.hasNext() || !arguments.pop().value().equals("3"))
                throw context.newException("Unknown controlled shift");
            return Tag.selfClosingInserting(Component.text("\uE748").font(Key.key("nexo:shift")));
        });
    }
}
