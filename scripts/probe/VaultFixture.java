package dev.flint.planetrycore.probe;

import java.lang.reflect.Proxy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only Vault service fixture backed by the actual provided Vault Economy API. */
public final class VaultFixture extends JavaPlugin {
    public double balance;
    public int deposits;
    public int attempts;
    public boolean decline;
    private Economy provider;

    @Override public void onEnable() {
        provider = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled", "hasAccount", "createPlayerAccount" -> true;
                    case "hasBankSupport" -> false;
                    case "getName" -> "PlanetrySmokeEconomy";
                    case "currencyNamePlural", "currencyNameSingular" -> "coins";
                    case "fractionalDigits" -> 2;
                    case "getBalance" -> balance;
                    case "depositPlayer" -> {
                        double amount = ((Number) args[args.length - 1]).doubleValue();
                        attempts++;
                        if (decline) yield new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, "Test provider declined the payment");
                        balance += amount;
                        deposits++;
                        yield new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    case "format" -> String.valueOf(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PlanetrySmokeEconomy";
                    default -> defaultValue(method.getReturnType());
                });
        setAvailable(true);
    }

    public void setAvailable(boolean available) {
        if (available) getServer().getServicesManager().register(Economy.class, provider, this, ServicePriority.Normal);
        else getServer().getServicesManager().unregister(Economy.class, provider);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return null;
    }
}
