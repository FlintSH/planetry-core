package dev.flint.planetry.core.journal;

import dev.flint.planetry.core.Language;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Trusted templates, literal runtime data. Player names and item names never become markup. */
final class JournalText {
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    static Component render(String source, Map<String, String> values, Map<String, Component> components) {
        var matcher = TOKEN.matcher(Language.miniMessage(source));
        var result = new StringBuilder();
        var tags = TagResolver.builder();
        int index = 0;
        while (matcher.find()) {
            String key = matcher.group(1);
            Component value = components.get(key);
            if (value == null && values.containsKey(key)) value = Component.text(values.get(key));
            if (value == null) continue;
            String tag = "journal_value_" + index++;
            tags.resolver(Placeholder.component(tag, value));
            matcher.appendReplacement(result, "<" + tag + ">");
        }
        matcher.appendTail(result);
        return MINI.deserialize(result.toString(), tags.build());
    }
}
