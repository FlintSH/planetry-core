package dev.aurelium.auraskills.api.registry;
public record NamespacedId(String namespace, String key) {
    public static NamespacedId of(String namespace,String key) { return new NamespacedId(namespace,key); }
}
