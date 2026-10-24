package dev.evolutionz.phantomshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PhantomShieldPlugin extends JavaPlugin implements Listener, TabCompleter {

    // --- State ---
    private final Map<UUID, Long>         expirations = new HashMap<>();
    /** Tracks which threshold warnings (seconds) have already been sent per player. */
    private final Map<UUID, Set<Integer>> warningSent = new HashMap<>();

    // --- PDC keys ---
    private NamespacedKey keyMarker;   // marks an item as a shield key
    private NamespacedKey keyDuration; // stores duration (minutes) baked into the item

    private BukkitTask timerTask;

    /** Warning thresholds in seconds. */
    private static final int WARN_5MIN = 300;
    private static final int WARN_1MIN = 60;

    /** Highest duration a key/command may grant, in minutes (~10 years). Prevents overflow. */
    private static final long MAX_DURATION_MINUTES = 5_256_000L;

    /** Serializer used to turn legacy '&'-coded config strings into Adventure components. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        injectMissingConfigKeys(); // ensure new keys exist even in old config files
        keyMarker   = new NamespacedKey(this, "phantom_shield_key");
        keyDuration = new NamespacedKey(this, "phantom_shield_duration");
        loadExpirations();
        Bukkit.getPluginManager().registerEvents(this, this);
        timerTask = Bukkit.getScheduler().runTaskTimer(this, this::tickProtection, 20L, 20L);
        getLogger().info("PhantomShield enabled. Active protections: " + expirations.size());
    }

    @Override
    public void onDisable() {
        if (timerTask != null) timerTask.cancel();
        saveExpirations();
    }

    /**
     * Writes any config keys that are missing from the on-disk config.yml
     * (happens when a user upgrades from an older plugin version).
     */
    private void injectMissingConfigKeys() {
        Map<String, Object> defaults = Map.of(
            "settings.action-bar-timer",    true,
            "messages.action-bar",          "&b\u26a1 &f%time% &7remaining",
            "messages.warning-5min",        "&eYour Phantom Shield will expire in &f5 minutes&e!",
            "messages.warning-1min",        "&c\u26a0 Your Phantom Shield expires in &f1 minute&c!",
            "messages.list-header",         "&8&m----------&r &cActive Protections &8&m----------",
            "messages.list-entry",          "  &f%player% &8-> &a%time% remaining",
            "messages.list-empty",          "&7There are no active protections.",
            "messages.usage",               "&eUsage: &f/phantomshield <give|activate|status|revoke|list|reload>"
        );
        boolean dirty = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!getConfig().contains(entry.getKey())) {
                getConfig().set(entry.getKey(), entry.getValue());
                dirty = true;
            }
        }
        if (dirty) saveConfig();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadExpirations() {
        expirations.clear();
        if (!getConfig().getBoolean("settings.persist-after-restart", true)) return;
        var section = getConfig().getConfigurationSection("active-protections");
        if (section == null) return;
        long now = System.currentTimeMillis();
        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID uuid  = UUID.fromString(rawUuid);
                long expiry = section.getLong(rawUuid);
                if (expiry > now) expirations.put(uuid, expiry);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid UUID in saved protections: " + rawUuid);
            }
        }
        saveExpirations();
    }

    private void saveExpirations() {
        getConfig().set("active-protections", null);
        if (getConfig().getBoolean("settings.persist-after-restart", true)) {
            expirations.forEach((uuid, expiry) ->
                    getConfig().set("active-protections." + uuid, expiry));
        }
        saveConfig();
    }

    // -------------------------------------------------------------------------
    // Tick (every second)
    // -------------------------------------------------------------------------

    private void tickProtection() {
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : expirations.entrySet()) {
            UUID uuid      = entry.getKey();
            long remaining = entry.getValue() - now;

            if (remaining <= 0) {
                expired.add(uuid);
                continue;
            }

            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            // Action bar countdown
            if (getConfig().getBoolean("settings.action-bar-timer", true)) {
                String raw = rawMessage("action-bar", "&b\u26a1 &f%time% &7remaining")
                        .replace("%time%", formatDuration(remaining));
                player.sendActionBar(colorize(raw));
            }

            // Warning messages (fire once per threshold per activation)
            long secs = remaining / 1000;
            Set<Integer> sent = warningSent.computeIfAbsent(uuid, k -> new HashSet<>());
            if (secs <= WARN_5MIN && sent.add(WARN_5MIN)) send(player, "warning-5min");
            if (secs <= WARN_1MIN && sent.add(WARN_1MIN)) send(player, "warning-1min");
        }

        for (UUID uuid : expired) {
            expirations.remove(uuid);
            warningSent.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) send(p, "expired");
        }

        // Forcibly un-target protected players every tick as an extra guard
        if (getConfig().getBoolean("settings.phantom-protection.prevent-targeting", true)) {
            for (World world : Bukkit.getWorlds()) {
                for (Phantom ph : world.getEntitiesByClass(Phantom.class)) {
                    if (ph.getTarget() instanceof Player t && isProtected(t.getUniqueId()))
                        ph.setTarget(null);
                }
            }
        }

        if (!expired.isEmpty()) saveExpirations();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || !isShieldKey(item)) return;
        if (!event.getAction().isRightClick()) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("phantomshield.use")) {
            send(player, "no-permission");
            return;
        }
        long now = System.currentTimeMillis();
        Long current = expirations.get(player.getUniqueId());
        if (current != null && current > now) {
            send(player, "already-active");
            return;
        }

        long minutes = getItemDuration(item);
        expirations.put(player.getUniqueId(), now + minutes * 60_000L);
        warningSent.remove(player.getUniqueId());

        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);

        saveExpirations();
        send(player, "activated", "%duration%", Long.toString(minutes));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!getConfig().getBoolean("settings.phantom-protection.prevent-targeting", true)) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        if (event.getTarget() instanceof Player p && isProtected(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!getConfig().getBoolean("settings.phantom-protection.prevent-attacks", true)) return;
        if (event.getDamager() instanceof Phantom
                && event.getEntity() instanceof Player p
                && isProtected(p.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!getConfig().getBoolean("settings.phantom-protection.suppress-natural-spawning-near-player", true)) return;
        if (!(event.getEntity() instanceof Phantom phantom)) return;
        double r2 = Math.pow(Math.max(0, getConfig().getDouble("settings.phantom-protection.spawn-suppression-radius", 32.0)), 2);
        for (Player p : phantom.getWorld().getPlayers()) {
            if (!isProtected(p.getUniqueId())) continue;
            if (p.getLocation().distanceSquared(phantom.getLocation()) <= r2) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendRaw(sender, "usage"); return true; }

        switch (args[0].toLowerCase()) {

            // /pshield give <player> [amount] [minutes]
            case "give" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sendRaw(sender, "usage"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sendRaw(sender, "player-not-found"); return true; }

                int  amount  = parseIntArg(args, 2, 1, 1, 64);
                long minutes = parseLongArg(args, 3,
                        getConfig().getLong("settings.default-duration-minutes", 30), 1, MAX_DURATION_MINUTES);

                Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createKey(amount, minutes));
                leftovers.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
                send(target, "key-received", "%amount%", String.valueOf(amount));
                sender.sendMessage(prefix().append(colorize("&aGave &f" + amount
                        + " &aPhantom Shield Key(s) to &f" + target.getName()
                        + " &7(" + minutes + "m each)&a.")));
                return true;
            }

            // /pshield activate <player> [minutes]
            case "activate" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sendRaw(sender, "usage"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sendRaw(sender, "player-not-found"); return true; }
                long minutes = parseLongArg(args, 2,
                        getConfig().getLong("settings.default-duration-minutes", 30), 1, MAX_DURATION_MINUTES);
                expirations.put(target.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L);
                warningSent.remove(target.getUniqueId());
                saveExpirations();
                send(target, "activated", "%duration%", String.valueOf(minutes));
                sender.sendMessage(prefix().append(colorize("&aProtection activated for &f"
                        + target.getName() + " &7(" + minutes + "m)&a.")));
                return true;
            }

            // /pshield status [player]
            case "status" -> {
                UUID uuid;
                String displayName;
                if (args.length >= 2) {
                    if (!requireAdmin(sender)) return true;
                    OfflinePlayer op = resolveOfflinePlayer(args[1]);
                    uuid        = op.getUniqueId();
                    displayName = args[1];
                } else if (sender instanceof Player p) {
                    uuid        = p.getUniqueId();
                    displayName = p.getName();
                } else {
                    sendRaw(sender, "usage");
                    return true;
                }
                Long expiry = expirations.get(uuid);
                String nameLabel = args.length >= 2 ? "&e" + displayName + ": " : "";
                if (expiry == null || expiry <= System.currentTimeMillis()) {
                    sender.sendMessage(prefix().append(colorize(nameLabel
                            + rawMessage("protection-inactive", "&cInactive"))));
                } else {
                    sender.sendMessage(prefix().append(colorize(nameLabel
                            + rawMessage("protection-active", "&aActive &8- &f%time% remaining")
                                    .replace("%time%", formatDuration(expiry - System.currentTimeMillis())))));
                }
                return true;
            }

            // /pshield list
            case "list" -> {
                if (!requireAdmin(sender)) return true;
                long now = System.currentTimeMillis();
                List<Map.Entry<UUID, Long>> active = expirations.entrySet().stream()
                        .filter(e -> e.getValue() > now)
                        .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                        .collect(Collectors.toList());
                if (active.isEmpty()) {
                    sender.sendMessage(prefix().append(colorize(
                            rawMessage("list-empty", "&7There are no active protections."))));
                    return true;
                }
                sender.sendMessage(colorize(rawMessage("list-header",
                        "&8&m----------&r &cActive Protections &8&m----------")));
                for (Map.Entry<UUID, Long> e : active) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                    String name      = (op.getName() != null) ? op.getName() : e.getKey().toString();
                    String time      = formatDuration(e.getValue() - now);
                    sender.sendMessage(colorize(
                            rawMessage("list-entry", "  &f%player% &8-> &a%time% remaining")
                                    .replace("%player%", name)
                                    .replace("%time%", time)));
                }
                return true;
            }

            // /pshield revoke <player>
            case "revoke" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sendRaw(sender, "usage"); return true; }
                Player target         = Bukkit.getPlayerExact(args[1]);
                OfflinePlayer offline = (target != null) ? target : resolveOfflinePlayer(args[1]);
                UUID uuid             = offline.getUniqueId();
                expirations.remove(uuid);
                warningSent.remove(uuid);
                saveExpirations();
                if (target != null) send(target, "protection-revoked");
                sender.sendMessage(prefix().append(colorize(rawMessage("protection-revoked-admin",
                        "&aProtection revoked for &f%player%&a.").replace("%player%", args[1]))));
                return true;
            }

            // /pshield reload
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                reloadConfig();
                injectMissingConfigKeys();
                sendRaw(sender, "reloaded");
                return true;
            }

            default -> { sendRaw(sender, "usage"); return true; }
        }
    }

    // -------------------------------------------------------------------------
    // Tab Completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            // All players can check their own status
            if (sender.hasPermission("phantomshield.use") || sender.hasPermission("phantomshield.admin"))
                subs.add("status");
            // Admin-only subcommands
            if (sender.hasPermission("phantomshield.admin"))
                subs.addAll(List.of("give", "activate", "revoke", "list", "reload"));
            return filterPrefix(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2 && List.of("give", "activate", "revoke", "status").contains(sub)) {
            // Suggest online player names
            return filterPrefix(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                    args[1]);
        }

        if (args.length == 3 && sub.equals("give")) {
            return filterPrefix(List.of("1", "2", "5", "10", "32", "64"), args[2]);
        }

        if (args.length == 4 && sub.equals("give") || args.length == 3 && sub.equals("activate")) {
            // Suggest common durations in minutes
            return filterPrefix(List.of("5", "10", "15", "30", "60", "120", "1440"), args[args.length - 1]);
        }

        return List.of();
    }

    private List<String> filterPrefix(List<String> options, String input) {
        String lower = input.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("phantomshield.admin")) return true;
        sendRaw(sender, "no-permission");
        return false;
    }

    private boolean isProtected(UUID uuid) {
        Long expiry = expirations.get(uuid);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    private boolean isShieldKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyMarker, PersistentDataType.BYTE);
    }

    /** Returns the duration (minutes) baked into a key at give-time, or the config default. */
    private long getItemDuration(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.getPersistentDataContainer().has(keyDuration, PersistentDataType.LONG))
                return clampMinutes(meta.getPersistentDataContainer().get(keyDuration, PersistentDataType.LONG));
        }
        return clampMinutes(getConfig().getLong("settings.default-duration-minutes", 30));
    }

    private ItemStack createKey(int amount, long minutes) {
        Material mat = Material.matchMaterial(getConfig().getString("item.material", "TRIPWIRE_HOOK"));
        if (mat == null || mat.isAir()) mat = Material.TRIPWIRE_HOOK;
        ItemStack item = new ItemStack(mat, Math.max(1, Math.min(amount, mat.getMaxStackSize())));
        ItemMeta  meta = item.getItemMeta();
        meta.displayName(colorize(getConfig().getString("item.name", "&cPhantom Shield Key")));
        List<String> lore = getConfig().getStringList("item.lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(l -> colorize(l.replace("%duration%", Long.toString(minutes)))
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        if (getConfig().getBoolean("item.glowing", true)) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyMarker,   PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(keyDuration, PersistentDataType.LONG, minutes);
        item.setItemMeta(meta);
        return item;
    }

    /** Parses an int from args[index], defaulting to def, then clamped to [min, max]. */
    private int parseIntArg(String[] args, int index, int def, int min, int max) {
        int value = def;
        if (args.length > index) {
            try { value = Integer.parseInt(args[index]); }
            catch (NumberFormatException ignored) { value = def; }
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Parses a long from args[index], defaulting to def, then clamped to [min, max]. */
    private long parseLongArg(String[] args, int index, long def, long min, long max) {
        long value = def;
        if (args.length > index) {
            try { value = Long.parseLong(args[index]); }
            catch (NumberFormatException ignored) { value = def; }
        }
        return Math.max(min, Math.min(max, value));
    }

    private String formatDuration(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0)   return h + "h " + m + "m";
        if (m > 0)   return m + "m " + sec + "s";
        return sec + "s";
    }

    private Component prefix() {
        return colorize(getConfig().getString("messages.prefix", "&8[&cPhantom Shield&8] "));
    }

    /** Gets a raw (still '&'-coded) message from config, with an in-code fallback if missing. */
    private String rawMessage(String path, String fallback) {
        return getConfig().getString("messages." + path, fallback);
    }

    /** Gets a raw message from config (empty string if missing). */
    private String rawMessage(String path) {
        return rawMessage(path, "");
    }

    private void send(Player player, String path) {
        player.sendMessage(prefix().append(colorize(rawMessage(path))));
    }

    private void send(Player player, String path, String placeholder, String value) {
        player.sendMessage(prefix().append(colorize(rawMessage(path).replace(placeholder, value))));
    }

    private void sendRaw(CommandSender sender, String path) {
        sender.sendMessage(prefix().append(colorize(rawMessage(path))));
    }

    /** Clamps a duration (minutes) to a sane, non-overflowing range. */
    private long clampMinutes(long minutes) {
        return Math.max(1L, Math.min(MAX_DURATION_MINUTES, minutes));
    }

    /** Resolves an offline player, avoiding a blocking web lookup whenever the name is cached. */
    @SuppressWarnings("deprecation")
    private OfflinePlayer resolveOfflinePlayer(String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null ? cached : Bukkit.getOfflinePlayer(name);
    }

    /** Converts a legacy '&'-coded string into an Adventure component. */
    private Component colorize(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
    }
}
