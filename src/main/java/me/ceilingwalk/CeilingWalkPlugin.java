package me.ceilingwalk;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Collections;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class CeilingWalkPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final double VEHICLE_EYE_OFFSET = -0.84;
    private static final double MANNEQUIN_STANDING_EYE_OFFSET = -1.44;
    private static final double MANNEQUIN_CROUCHING_EYE_OFFSET = -1.14;
    private static final double MANNEQUIN_NAME_OFFSET_Y = -0.5;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Boolean> previousAllowFlight = new HashMap<>();
    private final Map<UUID, Boolean> previousInvisible = new HashMap<>();
    private final Map<UUID, ArmorStand> vehicles = new HashMap<>();
    private final Map<UUID, Mannequin> mannequins = new HashMap<>();
    private final Map<UUID, TextDisplay> mannequinNames = new HashMap<>();
    private final Map<UUID, Team> mannequinNameTeams = new HashMap<>();
    private final Map<UUID, Double> passengerOffsets = new HashMap<>();
    private final Map<UUID, Double> vehicleBaseY = new HashMap<>();
    private final Map<UUID, Double> verticalVelocities = new HashMap<>();
    private final Map<UUID, Vector> horizontalVelocities = new HashMap<>();
    private final Set<UUID> jumpHeld = new HashSet<>();
    private final Set<UUID> internalDismount = new HashSet<>();
    private final Map<UUID, Boolean> autoJump = new HashMap<>();
    private final Map<UUID, Boolean> f5Mode = new HashMap<>();
    private final Map<UUID, Boolean> mouseWarningHidden = new HashMap<>();
    private final Map<UUID, Boolean> sneaking = new HashMap<>();
    private final Set<UUID> interactionPending = new HashSet<>();
    private File playerDataFile;
    private YamlConfiguration playerData;
    private NamespacedKey flagKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        playerDataFile = new File(getDataFolder(), "data.yml");
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        flagKey = new NamespacedKey(this, "active");
        setupCraftEngineCompatibility();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("ceilingwalk");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getServer().getScheduler().runTaskTimer(this, this::tickVehicles, 1L, 1L);
    }

    private void setPlayerVisibility(Player target, boolean visible) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (visible) {
                viewer.showPlayer(this, target);
                viewer.showEntity(this, target);
            } else {
                viewer.hidePlayer(this, target);
                viewer.hideEntity(this, target);
            }
        }
    }

    private void applyHiddenPlayerEquipment(Player target) {
        ItemStack empty = new ItemStack(org.bukkit.Material.AIR);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                viewer.sendEquipmentChange(target, EquipmentSlot.HEAD,
                        hiddenPlayerArmor(target.getInventory().getHelmet(), EquipmentSlot.HEAD));
                viewer.sendEquipmentChange(target, EquipmentSlot.CHEST,
                        hiddenPlayerArmor(target.getInventory().getChestplate(), EquipmentSlot.CHEST));
                viewer.sendEquipmentChange(target, EquipmentSlot.LEGS,
                        hiddenPlayerArmor(target.getInventory().getLeggings(), EquipmentSlot.LEGS));
                viewer.sendEquipmentChange(target, EquipmentSlot.FEET,
                        hiddenPlayerArmor(target.getInventory().getBoots(), EquipmentSlot.FEET));
            } else {
                viewer.sendEquipmentChange(target, EquipmentSlot.HEAD, empty);
                viewer.sendEquipmentChange(target, EquipmentSlot.CHEST, empty);
                viewer.sendEquipmentChange(target, EquipmentSlot.LEGS, empty);
                viewer.sendEquipmentChange(target, EquipmentSlot.FEET, empty);
                viewer.sendEquipmentChange(target, EquipmentSlot.HAND, empty);
                viewer.sendEquipmentChange(target, EquipmentSlot.OFF_HAND, empty);
            }
        }
    }

    private void applyNormalPlayerEquipment(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendEquipmentChange(target, EquipmentSlot.HEAD, target.getInventory().getHelmet());
            viewer.sendEquipmentChange(target, EquipmentSlot.CHEST, target.getInventory().getChestplate());
            viewer.sendEquipmentChange(target, EquipmentSlot.LEGS, target.getInventory().getLeggings());
            viewer.sendEquipmentChange(target, EquipmentSlot.FEET, target.getInventory().getBoots());
            viewer.sendEquipmentChange(target, EquipmentSlot.HAND, target.getInventory().getItemInMainHand());
            viewer.sendEquipmentChange(target, EquipmentSlot.OFF_HAND, target.getInventory().getItemInOffHand());
        }
    }

    private ItemStack hiddenPlayerArmor(ItemStack armor, EquipmentSlot slot) {
        if (armor == null || armor.getType().isAir()) {
            return armor == null ? new ItemStack(Material.AIR) : armor.clone();
        }
        ItemStack copy = armor.clone();
        Equippable equippable = copy.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return copy;
        }
        Equippable rebuilt = Equippable.equippable(slot)
                .assetId(Key.key("ceilingwalk", "hide_armor"))
                .equipSound(equippable.equipSound())
                .cameraOverlay(equippable.cameraOverlay())
                .allowedEntities(equippable.allowedEntities())
                .dispensable(equippable.dispensable())
                .swappable(equippable.swappable())
                .damageOnHurt(equippable.damageOnHurt())
                .equipOnInteract(equippable.equipOnInteract())
                .canBeSheared(equippable.canBeSheared())
                .shearSound(equippable.shearSound())
                .build();
        copy.setData(DataComponentTypes.EQUIPPABLE, rebuilt);
        return copy;
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                saveEnabled(p.getUniqueId(), true);
                disable(p, true);
            }
        }
        for (ArmorStand vehicle : vehicles.values()) {
            if (vehicle.isValid()) {
                vehicle.remove();
            }
        }
        for (Mannequin mannequin : mannequins.values()) {
            if (mannequin.isValid()) {
                mannequin.remove();
            }
        }
        for (TextDisplay nameTag : mannequinNames.values()) {
            if (nameTag.isValid()) {
                nameTag.remove();
            }
        }
        for (Team nameTeam : mannequinNameTeams.values()) {
            nameTeam.unregister();
        }
        vehicles.clear();
        mannequins.clear();
        mannequinNames.clear();
        savePlayerData();
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("about")) {
            sender.sendMessage(message("messages.about", null));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("version")) {
            sender.sendMessage(message("messages.version", null)
                    .replace("%version%", getDescription().getVersion()));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("autojump")) {
            Player target;
            Boolean requested = null;
            int next = 1;
            if (args.length > next && isBoolean(args[next])) {
                requested = Boolean.parseBoolean(args[next++]);
                if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(message("messages.usage", null));
                    return true;
                }
            } else if (args.length > next) {
                if (!sender.hasPermission("ceilingwalk.others")) {
                    sender.sendMessage(message("messages.no-autojump-others-permission", null));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[next++]);
                if (target == null) {
                    sender.sendMessage(message("messages.player-not-found", null)
                            .replace("%player%", args[next - 1]));
                    return true;
                }
            } else if (sender instanceof Player p) {
                target = p;
            } else {
                sender.sendMessage(message("messages.usage", null));
                return true;
            }
            if (!(sender instanceof Player) && next == 1) {
                sender.sendMessage(message("messages.usage", null));
                return true;
            }
            if (next < args.length) {
                if (requested != null || !isBoolean(args[next]) || next + 1 != args.length) {
                    sender.sendMessage(message("messages.usage", null));
                    return true;
                }
                requested = Boolean.parseBoolean(args[next]);
            }
            if (requested == null) {
                requested = !autoJump.getOrDefault(target.getUniqueId(), autoJumpDefault());
            }
            boolean enabled = requested;
            autoJump.put(target.getUniqueId(), enabled);
            saveAutoJump(target.getUniqueId(), enabled);
            sender.sendMessage(statusMessage(target, true));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("f5-mode")
                || args[0].equalsIgnoreCase("f5mode") || args[0].equalsIgnoreCase("f5"))) {
            if (!(sender instanceof Player p) || args.length > 2
                    || (args.length == 2 && !isBoolean(args[1]))) {
                sender.sendMessage(message("messages.usage", null));
                return true;
            }
            boolean enabled = args.length == 2
                    ? Boolean.parseBoolean(args[1])
                    : !f5Mode.getOrDefault(p.getUniqueId(), f5ModeDefault());
            f5Mode.put(p.getUniqueId(), enabled);
            saveF5Mode(p.getUniqueId(), enabled);
            Mannequin mannequin = mannequins.get(p.getUniqueId());
            if (mannequin != null && mannequin.isValid()) {
                if (enabled) {
                    p.showEntity(this, mannequin);
                } else {
                    p.hideEntity(this, mannequin);
                }
            }
            TextDisplay nameTag = mannequinNames.get(p.getUniqueId());
            if (nameTag != null && nameTag.isValid()) {
                if (enabled) {
                    p.showEntity(this, nameTag);
                } else {
                    p.hideEntity(this, nameTag);
                }
            }
            sender.sendMessage(statusMessage(p, true));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(helpMessage());
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("hide-mouse-warning")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(message("messages.console-player-only", null));
                return true;
            }
            boolean hidden;
            if (args.length == 2 && isBoolean(args[1])) {
                hidden = Boolean.parseBoolean(args[1]);
            } else if (args.length == 1) {
                hidden = !mouseWarningHidden.getOrDefault(p.getUniqueId(), false);
            } else {
                sender.sendMessage(message("messages.usage", null));
                return true;
            }
            mouseWarningHidden.put(p.getUniqueId(), hidden);
            saveMouseWarningHidden(p.getUniqueId(), hidden);
            sender.sendMessage(message(hidden
                    ? "messages.mouse-warning-hidden"
                    : "messages.mouse-warning-shown", p));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("ceilingwalk.reload")) {
                sender.sendMessage(message("messages.no-reload-permission", null));
                return true;
            }
            reloadConfig();
            playerData = YamlConfiguration.loadConfiguration(playerDataFile);
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                autoJump.put(id, playerData.getBoolean(
                        "players." + id + ".autojump", autoJumpDefault()));
                f5Mode.put(id, playerData.getBoolean(
                        "players." + id + ".f5-mode", f5ModeDefault()));
                mouseWarningHidden.put(id, playerData.getBoolean(
                        "players." + id + ".hide-mouse-warning", false));
            }
            setupCraftEngineCompatibility();
            sender.sendMessage(message("messages.reload-success", null));
            return true;
        }

        int targetIndex = args.length >= 1 && args[0].equalsIgnoreCase("toggle") ? 1 : 0;
        Player target;
        Boolean requested = null;
        if (args.length > targetIndex) {
            if (isBoolean(args[targetIndex])) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(message("messages.usage", null));
                    return true;
                }
                target = p;
                requested = Boolean.parseBoolean(args[targetIndex]);
                targetIndex++;
            } else {
                if (!sender.hasPermission("ceilingwalk.others")) {
                    sender.sendMessage(message("messages.no-toggle-others-permission", null));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[targetIndex++]);
                if (target == null) {
                    sender.sendMessage(message("messages.player-not-found", null)
                            .replace("%player%", args[targetIndex - 1]));
                    return true;
                }
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(message("messages.usage", null));
            return true;
        }
        if (targetIndex < args.length) {
            if (requested != null || !isBoolean(args[targetIndex]) || targetIndex + 1 != args.length) {
                sender.sendMessage(message("messages.usage", null));
                return true;
            }
            requested = Boolean.parseBoolean(args[targetIndex]);
        }

        boolean enabled = requested != null
                ? requested
                : !active.contains(target.getUniqueId());
        if (enabled && !active.contains(target.getUniqueId())) {
            if (enable(target)) {
                saveEnabled(target.getUniqueId(), true);
                sender.sendMessage(statusMessage(target, true));
                if (target instanceof Player) {
                    sendToggleWarnings(target);
                }
            } else {
                sender.sendMessage(message("messages.enable-failed", target));
            }
        } else if (!enabled && active.contains(target.getUniqueId())) {
            disable(target, true);
            saveEnabled(target.getUniqueId(), false);
            sender.sendMessage(statusMessage(target, false));
            sendToggleWarnings(target);
        } else {
            sender.sendMessage(statusMessage(target, active.contains(target.getUniqueId())));
        }
        return true;
    }

    private boolean isBoolean(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    private Component statusMessage(Player target, boolean includeOptions) {
        boolean enabled = active.contains(target.getUniqueId());
        boolean f5Visible = f5Mode.getOrDefault(target.getUniqueId(), f5ModeDefault());
        boolean autoJumpEnabled = autoJump.getOrDefault(target.getUniqueId(), autoJumpDefault());
        Component result = Component.text(message("messages.status-prefix", target), NamedTextColor.GREEN)
                .append(Component.text(enabled
                                ? message("messages.status-on", target)
                                : message("messages.status-off", target),
                                enabled ? NamedTextColor.DARK_GREEN : NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .decorate(TextDecoration.UNDERLINED))
                .append(Component.text(message("messages.status-suffix", target), NamedTextColor.GREEN));
        if (!includeOptions) {
            return result;
        }
        result = result.append(Component.text("\n"));
        String f5Hover = message("messages.status-f5-hover-" + (f5Visible ? "off" : "on"), target);
        result = result.append(statusToggle(message("messages.status-f5-label", target),
                        f5Visible, "/cw f5-mode", f5Hover))
                .append(Component.text(message("messages.status-separator", target), NamedTextColor.GRAY))
                .append(Component.text(" "))
                .append(statusToggle(message("messages.status-autojump-label", target),
                        autoJumpEnabled, "/cw autojump",
                        message("messages.status-autojump-hover-" + (autoJumpEnabled ? "off" : "on"), target)))
                .append(Component.text("\n"));
        return result;
    }

    private Component statusToggle(String label, boolean enabled, String command, String hover) {
        Component clickTarget = Component.text(enabled
                        ? message("messages.status-on", null)
                        : message("messages.status-off", null),
                        enabled ? NamedTextColor.DARK_GREEN : NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
        return Component.text(label, NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)))
                .append(clickTarget);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("toggle", "autojump", "f5-mode",
                    "hide-mouse-warning", "reload", "help", "about", "version"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hide-mouse-warning")) {
            return partial(args[1], List.of("true", "false"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("f5-mode")
                || args[0].equalsIgnoreCase("f5mode") || args[0].equalsIgnoreCase("f5"))) {
            return partial(args[1], List.of("true", "false"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("autojump")) {
            List<String> names = new ArrayList<>();
            names.add("true");
            names.add("false");
            if (!sender.hasPermission("ceilingwalk.others")) {
                return partial(args[1], names);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return partial(args[1], names);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("toggle")
                || isBoolean(args[0]))) {
            return partial(args[1], List.of("true", "false"));
        }
        return Collections.emptyList();
    }

    private List<String> partial(String input, List<String> values) {
        return values.stream()
                .filter(value -> value.regionMatches(true, 0, input, 0, input.length()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private String message(String path, Player player) {
        String fallback = switch (path) {
            case "messages.on" -> "Ceiling walking ON for %player%";
            case "messages.off" -> "Ceiling walking OFF for %player%";
            case "messages.autojump-on" -> "Auto-jump ON for %player%";
            case "messages.autojump-off" -> "Auto-jump OFF for %player%";
            case "messages.f5-mode-on" -> "F5 mannequin mode ON";
            case "messages.f5-mode-off" -> "F5 mannequin mode OFF";
            case "messages.about" -> "CeilingWalk - inverted-gravity ceiling walking.";
            case "messages.version" -> "CeilingWalk version %version%";
            case "messages.usage" -> "Usage: /cw [toggle|autojump|f5-mode] [player] [true|false]";
            case "messages.console-player-only" -> "This command can only be used by players.";
            case "messages.mouse-warning-hidden" -> "Mouse inversion warning hidden.";
            case "messages.mouse-warning-shown" -> "Mouse inversion warning shown.";
            case "messages.reload-success" -> "CeilingWalk config and data.yml reloaded.";
            case "messages.no-reload-permission" -> "You don't have permission to reload CeilingWalk.";
            case "messages.enable-failed" -> "Could not enable ceiling walking (no gravity attribute?).";
            case "messages.no-autojump-others-permission" -> "You don't have permission to change other players' auto-jump.";
            case "messages.no-toggle-others-permission" -> "You don't have permission to toggle other players.";
            case "messages.player-not-found" -> "Player not found: %player%";
            case "messages.creative-spectator-warning" -> "You are using /cw in Creative or Spectator mode. If you experience issues, disable /cw, return to Survival mode, and enable /cw again.";
            case "messages.mouse-warning" -> "Enable \"Invert mouse X/Y\" in Options > Controls > Mouse Settings.";
            case "messages.creative-warning-label" -> "[Warning]:";
            case "messages.creative-warning-toggle" -> " Toggeling ";
            case "messages.creative-warning-command" -> "/cw";
            case "messages.creative-warning-while" -> " while in ";
            case "messages.creative-warning-mode" -> "creative or spectator";
            case "messages.creative-warning-end" -> " can have unforseen sideeffects!";
            case "messages.creative-warning-recovery" -> "If you get stuck or are having issues then try disabling /cw, switch to survival and then re-enable /cw.";
            case "messages.mouse-warning-label" -> "[Ceiling-walk TIP]: ";
            case "messages.mouse-warning-body" -> "Enable \"Invert mouse X/Y\" in control options for a smoother expirence.\nYou can find them in \"options\" > \"controls\" > \"mouse settings\" ";
            case "messages.mouse-warning-more-info" -> "MORE INFO";
            case "messages.mouse-warning-hide-button" -> "HIDE ME";
            case "messages.mouse-warning-hide-hover" -> "Hide this mouse warning permanently";
            case "messages.status-prefix" -> "Ceiling walk turned ";
            case "messages.status-suffix" -> " for %player%.";
            case "messages.status-on" -> "ON";
            case "messages.status-off" -> "OFF";
            case "messages.status-separator" -> ",";
            case "messages.status-f5-label" -> "F5-mode: ";
            case "messages.status-f5-hover-on" -> "Click to toggle F5-mode ON";
            case "messages.status-f5-hover-off" -> "Click to toggle F5-mode OFF";
            case "messages.status-autojump-label" -> "Auto-Jump: ";
            case "messages.status-autojump-hover-on" -> "Click to turn AutoJump ON";
            case "messages.status-autojump-hover-off" -> "Click to turn AutoJump OFF";
            case "messages.help-title" -> "======= CEILING WALK / REVERSE GRAVITY =======\n       ---- A plugin by: theTWIXhunter ----";
            case "messages.help-cw" -> "(toggle ceiling walk on and off)";
            case "messages.help-f5" -> "(toggle an upside-down manequin)";
            case "messages.help-autojump" -> "(toggle autojump on and off)";
            case "messages.help-help" -> "(show this help message)";
            case "messages.help-version" -> "(show version info)";
            case "messages.help-about" -> "(show about info)";
            case "messages.help-toggle" -> "(alias of the main /cw)";
            case "messages.help-mouse-warning" -> "(toggle the mouse inversion warning)";
            case "messages.help-reload" -> "(reload config.yml and data.yml)";
            case "messages.help-mouse-tip" -> "Mouse TIP: Enable \"Invert mouse X/Y\" in Options > Controls > Mouse Settings.";
            case "messages.help-download" -> "Download:";
            case "messages.help-download-link" -> "Modrinth.com/CeilingWalk";
            case "messages.help-more-info" -> "More info: ";
            case "messages.help-website" -> "theTWIXhunter.nekoweb.org";
            default -> path;
        };
        String result = getConfig().getString(path, fallback);
        return player == null ? result : result.replace("%player%", player.getName());
    }

    private List<String> helpMessages() {
        List<String> configured = getConfig().getStringList("messages.help");
        return configured.isEmpty() ? List.of(
                "/cw - Toggle ceiling walking",
                "/cw <player> - Toggle ceiling walking for another player",
                "/cw toggle - Toggle ceiling walking",
                "/cw autojump - Toggle automatic jumping up blocks",
                "/cw autojump <player> - Toggle another player's auto-jump",
                "/cw f5-mode - Show or hide your mannequin",
                "/cw help - Show this help",
                "/cw about - About CeilingWalk",
                "/cw version - Show the plugin version"
        ) : configured;
    }

    private Component helpMessage() {
        Component result = Component.text(
                        message("messages.help-title", null),
                        NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw", "/cw", message("messages.help-cw", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw f5-mode", "/cw f5-mode", message("messages.help-f5", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw autojump", "/cw autojump", message("messages.help-autojump", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw help", "/cw help", message("messages.help-help", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(Component.text("/cw version", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/cw version")))
                .append(Component.text(" " + message("messages.help-version", null), NamedTextColor.YELLOW))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw about", "/cw about", message("messages.help-about", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw toggle", "/cw toggle", message("messages.help-toggle", null)));
        result = result
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw hide-mouse-warning",
                        "/cw hide-mouse-warning", message("messages.help-mouse-warning", null)))
                .append(Component.text("\n"))
                .append(Component.text("- ", NamedTextColor.YELLOW))
                .append(helpCommand("/cw reload", "/cw reload", message("messages.help-reload", null)));
        result = result
                .append(Component.text("\n"))
                .append(Component.text(message("messages.help-mouse-tip", null),
                        NamedTextColor.AQUA));

        return result
                .append(Component.text("\n\n"))
                .append(Component.text(message("messages.help-download", null), NamedTextColor.YELLOW))
                .append(Component.text(" " + message("messages.help-download-link", null),
                        NamedTextColor.DARK_GREEN))
                .append(Component.text("\n"))
                .append(Component.text(message("messages.help-more-info", null), NamedTextColor.YELLOW))
                .append(Component.text(message("messages.help-website", null),
                                NamedTextColor.BLUE)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(
                                "https://theTWIXhunter.nekoweb.org/ceilingwalk/")));
    }

    private Component helpCommand(String text, String suggestion, String description) {
        return Component.text(text, NamedTextColor.GOLD)
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .append(Component.text(" (" + description + ")", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------- core logic

    private boolean enable(Player p) {
        boolean usesPhysics = usesGroundCheck(p);
        AttributeInstance gravity = p.getAttribute(Attribute.GRAVITY);
        if (usesPhysics && gravity == null) {
            return false;
        }
        if (usesPhysics) {
            gravity.setBaseValue(getConfig().getDouble("gravity", -0.08));
        }

        // Standing on a ceiling is "not on ground" for the server, which would kick
        // for flying after a few seconds. Allowing flight disables that check.
        if (usesPhysics) {
            previousAllowFlight.put(p.getUniqueId(), p.getAllowFlight());
            p.setAllowFlight(true);
        }

        p.getPersistentDataContainer().set(flagKey, PersistentDataType.BYTE, (byte) 1);
        previousInvisible.put(p.getUniqueId(), p.isInvisible());
        p.setInvisible(true);
        active.add(p.getUniqueId());
        setPlayerVisibility(p, false);
        applyHiddenPlayerEquipment(p);
        ArmorStand vehicle = spawnVehicle(p);
        if (vehicle == null) {
            active.remove(p.getUniqueId());
            p.getPersistentDataContainer().remove(flagKey);
            previousAllowFlight.remove(p.getUniqueId());
            Boolean wasInvisible = previousInvisible.remove(p.getUniqueId());
            if (wasInvisible != null) {
                p.setInvisible(wasInvisible);
            }
            setPlayerVisibility(p, true);
            gravity.setBaseValue(gravity.getDefaultValue());
            getLogger().warning("Could not activate the eye-height vehicle for " + p.getName());
            return false;
        }
        vehicles.put(p.getUniqueId(), vehicle);
        mannequins.put(p.getUniqueId(), spawnMannequin(p));
        if (!f5Mode.getOrDefault(p.getUniqueId(), f5ModeDefault())) {
            p.hideEntity(this, mannequins.get(p.getUniqueId()));
            TextDisplay nameTag = mannequinNames.get(p.getUniqueId());
            if (nameTag != null) {
                p.hideEntity(this, nameTag);
            }
        }
        passengerOffsets.put(p.getUniqueId(), p.getLocation().getY() - vehicle.getLocation().getY());
        vehicleBaseY.put(p.getUniqueId(), vehicle.getLocation().getY());
        verticalVelocities.put(p.getUniqueId(), 0.0);
        horizontalVelocities.put(p.getUniqueId(), new Vector());
        sneaking.put(p.getUniqueId(), p.isSneaking());
        p.setPose(Pose.STANDING);
        runPostEffect("add", p);
        return true;
    }

    private void disable(Player p, boolean removeEffect) {
        ArmorStand vehicle = vehicles.remove(p.getUniqueId());
        Mannequin mannequin = mannequins.remove(p.getUniqueId());
        passengerOffsets.remove(p.getUniqueId());
        vehicleBaseY.remove(p.getUniqueId());
        verticalVelocities.remove(p.getUniqueId());
        horizontalVelocities.remove(p.getUniqueId());
        jumpHeld.remove(p.getUniqueId());
        internalDismount.remove(p.getUniqueId());
        sneaking.remove(p.getUniqueId());
        interactionPending.remove(p.getUniqueId());
        if (vehicle != null && vehicle.isValid()) {
            if (p.getVehicle() == vehicle) {
                internalDismount.add(p.getUniqueId());
                p.leaveVehicle();
                internalDismount.remove(p.getUniqueId());
                sneaking.remove(p.getUniqueId());
                interactionPending.remove(p.getUniqueId());
            }
            vehicle.remove();
        }
        if (mannequin != null && mannequin.isValid()) {
            mannequin.remove();
        }
        TextDisplay nameTag = mannequinNames.remove(p.getUniqueId());
        if (nameTag != null && nameTag.isValid()) {
            nameTag.remove();
        }
        Team nameTeam = mannequinNameTeams.remove(p.getUniqueId());
        if (nameTeam != null) {
            nameTeam.unregister();
        }

        AttributeInstance gravity = p.getAttribute(Attribute.GRAVITY);
        if (gravity != null) {
            gravity.setBaseValue(gravity.getDefaultValue());
        }

        Boolean previous = previousAllowFlight.remove(p.getUniqueId());
        if (previous != null) {
            p.setAllowFlight(previous);
        }
        Boolean wasInvisible = previousInvisible.remove(p.getUniqueId());
        if (wasInvisible != null) {
            p.setInvisible(wasInvisible);
        }

        p.getPersistentDataContainer().remove(flagKey);
        active.remove(p.getUniqueId());
        setPlayerVisibility(p, true);
        applyNormalPlayerEquipment(p);
        p.setPose(Pose.STANDING);
        if (removeEffect) {
            runPostEffect("remove", p);
        }
    }

    private ArmorStand spawnVehicle(Player p) {
        Location location = p.getLocation().clone().add(0, VEHICLE_EYE_OFFSET, 0);
        ArmorStand vehicle = p.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            AttributeInstance maxHealth = stand.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(0.0);
            }
        });
        if (!vehicle.addPassenger(p)) {
            vehicle.remove();
            return null;
        }
        return vehicle;
    }

    private Mannequin spawnMannequin(Player p) {
        boolean isSneaking = sneaking.getOrDefault(p.getUniqueId(), p.isSneaking());
        Location mannequinLocation = getMannequinLocation(p, isSneaking);
        Mannequin mannequin = p.getWorld().spawn(mannequinLocation, Mannequin.class, entity -> {
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setInvulnerable(true);
            entity.setCollidable(false);
            entity.setImmovable(true);
            entity.setAI(false);
        });
        mannequin.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
        mannequin.setCustomName("Dinnerbone");
        mannequin.setCustomNameVisible(false);
        mannequin.setDescription(Component.empty());
        mannequin.setPose(Pose.STANDING);
        mannequin.getEquipment().setHelmet(p.getInventory().getHelmet());
        mannequin.getEquipment().setChestplate(p.getInventory().getChestplate());
        mannequin.getEquipment().setLeggings(p.getInventory().getLeggings());
        mannequin.getEquipment().setBoots(p.getInventory().getBoots());
        mannequin.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
        mannequin.getEquipment().setItemInOffHand(p.getInventory().getItemInOffHand());
        Location nameLocation = getMannequinNameLocation(mannequinLocation);
        TextDisplay nameTag = p.getWorld().spawn(nameLocation, TextDisplay.class, display -> {
            display.setText(p.getName());
            display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setTeleportDuration(2);
            display.setInterpolationDuration(2);
            display.setInterpolationDelay(0);
        });
        mannequinNames.put(p.getUniqueId(), nameTag);
        hideMannequinNametag(p.getUniqueId(), mannequin);
        return mannequin;
    }

    private void hideMannequinNametag(UUID id, Mannequin mannequin) {
        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "cw-manequin-" + id.toString().replace("-", "").substring(0, 12);
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.addEntry(mannequin.getScoreboardEntryName());
        mannequinNameTeams.put(id, team);
    }

    private void updateMannequin(Player p, Mannequin mannequin) {
        boolean isSneaking = sneaking.getOrDefault(p.getUniqueId(), p.isSneaking());
        Location location = getMannequinLocation(p, isSneaking);
        mannequin.teleport(location);
        mannequin.setPose(isSneaking ? Pose.SNEAKING : Pose.STANDING);
        mannequin.setDescription(Component.empty());
        TextDisplay nameTag = mannequinNames.get(p.getUniqueId());
        if (nameTag != null && nameTag.isValid()) {
            Location nameLocation = getMannequinNameLocation(location);
            nameTag.teleport(nameLocation);
            nameTag.setText(p.getName());
        }
        mannequin.getEquipment().setHelmet(p.getInventory().getHelmet());
        mannequin.getEquipment().setChestplate(p.getInventory().getChestplate());
        mannequin.getEquipment().setLeggings(p.getInventory().getLeggings());
        mannequin.getEquipment().setBoots(p.getInventory().getBoots());
        mannequin.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
        mannequin.getEquipment().setItemInOffHand(p.getInventory().getItemInOffHand());
    }

    private Location getMannequinLocation(Player p, boolean isSneaking) {
        double eyeOffset = isSneaking
                ? MANNEQUIN_CROUCHING_EYE_OFFSET
                : MANNEQUIN_STANDING_EYE_OFFSET;
        return p.getLocation().clone().subtract(0, eyeOffset, 0);
    }

    private Location getMannequinNameLocation(Location mannequinLocation) {
        return mannequinLocation.clone().add(0, MANNEQUIN_NAME_OFFSET_Y, 0);
    }

    private void tickVehicles() {
        for (UUID id : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(id);
            ArmorStand vehicle = vehicles.get(id);
            if (p == null || (vehicle != null && (!vehicle.isValid()
                    || vehicle.getWorld() != p.getWorld() || p.getVehicle() != vehicle))) {
                if (interactionPending.contains(id)) {
                    continue;
                }
                if (p != null) {
                    disable(p, true);
                } else {
                    active.remove(id);
                    vehicles.remove(id);
                    Mannequin mannequin = mannequins.remove(id);
                    if (mannequin != null && mannequin.isValid()) {
                        mannequin.remove();
                    }
                    passengerOffsets.remove(id);
                    vehicleBaseY.remove(id);
                    verticalVelocities.remove(id);
                    horizontalVelocities.remove(id);
                    jumpHeld.remove(id);
                    internalDismount.remove(id);
                }
                continue;
            }
            if (vehicle == null) {
                continue;
            }
            Mannequin mannequin = mannequins.get(id);
            if (mannequin != null && mannequin.isValid()) {
                updateMannequin(p, mannequin);
            }
            applyHiddenPlayerEquipment(p);
            if (interactionPending.contains(id)) {
                continue;
            }
            if (p.getVehicle() != vehicle) {
                continue;
            }
            moveVehicle(p, vehicle);
        }
    }

    private void moveVehicle(Player p, ArmorStand vehicle) {
        Input input = p.getCurrentInput();
        UUID id = p.getUniqueId();
        boolean isSneaking = sneaking.getOrDefault(id, input.isSneak());
        sneaking.put(id, isSneaking);
        p.setPose(Pose.STANDING);
        Location current = vehicle.getLocation();
        double crouchOffset = isSneaking ? 0.70 : 0.0;
        double baseY = vehicleBaseY.getOrDefault(id, vehicle.getLocation().getY() - crouchOffset);
        boolean crouchApplied = isSneaking;
        current.setY(baseY + crouchOffset);
        if (input.isJump()) {
            requestJump(p);
        } else {
            jumpHeld.remove(id);
        }
        float yaw = p.getLocation().getYaw();
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double leftX = -Math.cos(radians);
        double leftZ = -Math.sin(radians);

        double targetX = 0;
        double targetZ = 0;
        if (input.isForward()) {
            targetX += forwardX;
            targetZ += forwardZ;
        }
        if (input.isBackward()) {
            targetX -= forwardX;
            targetZ -= forwardZ;
        }
        if (input.isLeft()) {
            targetX += leftX;
            targetZ += leftZ;
        }
        if (input.isRight()) {
            targetX -= leftX;
            targetZ -= leftZ;
        }

        AttributeInstance gravity = p.getAttribute(Attribute.GRAVITY);
        double verticalVelocity = verticalVelocities.getOrDefault(id, 0.0);
        double movementY = crouchApplied ? 0.0 : verticalVelocity;
        if (!crouchApplied && gravity != null) {
            double candidateY = baseY + movementY + crouchOffset - VEHICLE_EYE_OFFSET;
            if (!isPlayerSpaceClear(current.getWorld(), current.getX(), candidateY,
                    current.getZ(), false)) {
                movementY = 0.0;
                verticalVelocity = 0.0;
            }
        }

        double movementLength = Math.hypot(targetX, targetZ);
        if (movementLength > 0.0) {
            double movementSpeed = crouchApplied ? 0.065 : 0.2;
            double deltaX = targetX / movementLength * movementSpeed;
            double deltaZ = targetZ / movementLength * movementSpeed;
            // The crouch offset adjusts the passenger camera, not the physical
            // position used for collision checks.
            double playerY = baseY - VEHICLE_EYE_OFFSET;
            boolean moved = false;
            double candidateX = current.getX() + deltaX;
            double candidateZ = current.getZ() + deltaZ;
            boolean xRequested = Math.abs(deltaX) > 0.000001;
            boolean zRequested = Math.abs(deltaZ) > 0.000001;
            boolean candidateSupported = !crouchApplied || isSupportedAt(current.getWorld(), candidateX,
                    current.getY() - crouchOffset, candidateZ, gravity == null ? 1.0 : gravity.getValue());
            if (candidateSupported && isPlayerSpaceClear(current.getWorld(), candidateX, playerY,
                    candidateZ, crouchApplied)) {
                current.setX(candidateX);
                current.setZ(candidateZ);
                moved = true;
            } else {
                double stepDirection = gravity == null ? 0.0 : Math.copySign(1.0, gravity.getValue());
                double stepY = current.getY() + stepDirection * 0.6;
                if (!crouchApplied && stepDirection != 0.0 && isPlayerSpaceClear(current.getWorld(), candidateX,
                        stepY - VEHICLE_EYE_OFFSET, candidateZ, crouchApplied)) {
                    current.setX(candidateX);
                    current.setZ(candidateZ);
                    current.setY(stepY);
                    baseY = stepY - crouchOffset;
                    movementY = 0.0;
                    verticalVelocity = 0.0;
                    moved = true;
                } else {
                    boolean xMoved = xRequested
                            && (!crouchApplied || isSupportedAt(current.getWorld(), candidateX,
                                    current.getY() - crouchOffset, current.getZ(),
                                    gravity == null ? 1.0 : gravity.getValue()))
                            && isPlayerSpaceClear(current.getWorld(), candidateX, playerY,
                                    current.getZ(), crouchApplied);
                    if (xMoved) {
                        current.setX(candidateX);
                        moved = xMoved;
                    }
                    boolean zMoved = zRequested
                            && (!crouchApplied || isSupportedAt(current.getWorld(), current.getX(),
                                    current.getY() - crouchOffset, candidateZ,
                                    gravity == null ? 1.0 : gravity.getValue()))
                            && isPlayerSpaceClear(current.getWorld(), current.getX(), playerY,
                                    candidateZ, crouchApplied);
                    if (zMoved) {
                        current.setZ(candidateZ);
                        moved |= zMoved;
                    }
                    boolean blockedMovement = (xRequested && !xMoved) || (zRequested && !zMoved);
                    if (blockedMovement && Boolean.TRUE.equals(autoJump.getOrDefault(id, autoJumpDefault()))
                            && !crouchApplied && Math.abs(verticalVelocity) < 0.001
                            && isSupported(p, gravity == null ? 1.0 : gravity.getValue())) {
                        requestJump(p);
                        verticalVelocity = verticalVelocities.getOrDefault(id, 0.0);
                    }
                }
            }
        }
        if (!crouchApplied && gravity != null) {
            verticalVelocity = (verticalVelocity - gravity.getValue()) * 0.98;
            verticalVelocity = Math.max(-1.0, Math.min(1.0, verticalVelocity));
        } else {
            verticalVelocity = 0.0;
        }
        if (crouchApplied) {
            movementY = 0.0;
            verticalVelocity = 0.0;
        }
        baseY += movementY;
        vehicleBaseY.put(id, baseY);
        verticalVelocities.put(id, verticalVelocity);
        current.setY(baseY + crouchOffset);
        vehicle.teleport(current);
    }

    private boolean isPlayerSpaceClear(World world, double x, double y, double z) {
        return isPlayerSpaceClear(world, x, y, z, false);
    }

    private boolean isPlayerSpaceClear(World world, double x, double y, double z, boolean crouched) {
        double playerHeight = crouched ? 1.49 : 1.7;
        BoundingBox playerBox = crouched
                ? new BoundingBox(x - 0.3, y, z - 0.3,
                        x + 0.3, y + playerHeight, z + 0.3)
                : new BoundingBox(x - 0.3, y + 0.1, z - 0.3,
                        x + 0.3, y + playerHeight, z + 0.3);
        int minX = (int) Math.floor(playerBox.getMinX());
        int maxX = (int) Math.floor(playerBox.getMaxX());
        int minY = (int) Math.floor(playerBox.getMinY());
        int maxY = (int) Math.floor(playerBox.getMaxY());
        int minZ = (int) Math.floor(playerBox.getMinZ());
        int maxZ = (int) Math.floor(playerBox.getMaxZ());
        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    for (BoundingBox shapeBox : block.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox absoluteShape = new BoundingBox(
                                shapeBox.getMinX() + blockX,
                                shapeBox.getMinY() + blockY,
                                shapeBox.getMinZ() + blockZ,
                                shapeBox.getMaxX() + blockX,
                                shapeBox.getMaxY() + blockY,
                                shapeBox.getMaxZ() + blockZ);
                        if (playerBox.overlaps(absoluteShape)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean isReplaceable(World world, double x, double y, double z) {
        Block block = world.getBlockAt(new Location(world, x, y, z));
        return block.isReplaceable();
    }

    private boolean usesGroundCheck(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private void runPostEffect(String action, Player p) {
        String effect = getConfig().getString("post-effect", "upsidedown:flip");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "posteffect " + action + " " + p.getName() + " " + effect);
    }

    private void saveEnabled(UUID id, boolean enabled) {
        playerData.set("players." + id + ".enabled", enabled);
        savePlayerData();
    }

    private void saveAutoJump(UUID id, boolean enabled) {
        playerData.set("players." + id + ".autojump", enabled);
        savePlayerData();
    }

    private void saveMouseWarningHidden(UUID id, boolean hidden) {
        playerData.set("players." + id + ".hide-mouse-warning", hidden);
        savePlayerData();
    }

    private void sendToggleWarnings(Player p) {
        if (getConfig().getBoolean("creative-warning.enabled", true)
                && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            p.sendMessage(creativeSpectatorWarning(p));
        }

        if (!getConfig().getBoolean("mouse-warning.enabled", true)
                || mouseWarningHidden.getOrDefault(p.getUniqueId(), false)) {
            return;
        }
        p.sendMessage(mouseWarning(p));
    }

    private Component creativeSpectatorWarning(Player p) {
        return Component.text(message("messages.creative-warning-label", p), NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD)
                .append(Component.text(message("messages.creative-warning-toggle", p),
                                NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(message("messages.creative-warning-command", p),
                                NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(message("messages.creative-warning-while", p),
                                NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(message("messages.creative-warning-mode", p),
                                NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(message("messages.creative-warning-end", p),
                                NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("\n"))
                .append(Component.text(message("messages.creative-warning-recovery", p),
                        NamedTextColor.YELLOW));
    }

    private Component mouseWarning(Player p) {
        String moreInfoUrl = "https://theTWIXhunter.nekoweb.org/ceilingwalk/guides/invertmouse.html";
        Component moreInfoBracket = Component.text("[", NamedTextColor.BLUE)
                .clickEvent(ClickEvent.openUrl(moreInfoUrl));
        Component moreInfo = Component.text(message("messages.mouse-warning-more-info", p),
                        NamedTextColor.BLUE)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(moreInfoUrl));
        Component moreInfoClose = Component.text("]", NamedTextColor.BLUE)
                .clickEvent(ClickEvent.openUrl(moreInfoUrl));

        Component hideBracket = Component.text("[", NamedTextColor.BLUE)
                .clickEvent(ClickEvent.runCommand("/cw hide-mouse-warning"));
        Component hide = Component.text(message("messages.mouse-warning-hide-button", p),
                        NamedTextColor.BLUE)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/cw hide-mouse-warning"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        message("messages.mouse-warning-hide-hover", p))));
        Component hideClose = Component.text("]", NamedTextColor.BLUE)
                .clickEvent(ClickEvent.runCommand("/cw hide-mouse-warning"));

        return Component.text(message("messages.mouse-warning-label", p), NamedTextColor.DARK_AQUA)
                .append(Component.text(message("messages.mouse-warning-body", p),
                        NamedTextColor.AQUA))
                .append(moreInfoBracket)
                .append(moreInfo)
                .append(moreInfoClose)
                .append(Component.text(" ", NamedTextColor.BLUE))
                .append(hideBracket)
                .append(hide)
                .append(hideClose);
    }

    private void setupCraftEngineCompatibility() {
        if (!getConfig().getBoolean("craft-engine-compat.enabled", true)) {
            return;
        }

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create the CeilingWalk data folder.");
            return;
        }
        File resourcePack = new File(getDataFolder(), "CeilingWalk-resourcepack.zip");
        if (!resourcePack.exists()) {
            try (InputStream input = getResource("CeilingWalk-resourcepack.zip")) {
                if (input == null) {
                    getLogger().warning("Bundled CeilingWalk resource pack was not found.");
                } else {
                    Files.copy(input, resourcePack.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().warning("Could not copy the CeilingWalk resource pack: " + e.getMessage());
            }
        }

        String craftEngineLocation = getConfig().getString(
                "craft-engine-compat.craft-engine-config-location",
                "plugins/CraftEngine/config.yml");
        File craftEngineConfigFile = new File(craftEngineLocation);
        if (!craftEngineConfigFile.isAbsolute()) {
            craftEngineConfigFile = new File(new File(".").getAbsoluteFile(), craftEngineLocation);
        }
        if (!craftEngineConfigFile.isFile()) {
            return;
        }

        YamlConfiguration craftEngineConfig =
                YamlConfiguration.loadConfiguration(craftEngineConfigFile);
        String packLocation = getConfig().getString(
                "craft-engine-compat.resource-pack-location",
                "CeilingWalk/CeilingWalk-resourcepack");
        List<String> mergedFiles = new ArrayList<>();
        List<?> existingFiles = craftEngineConfig.getList(
                "resource-pack.merge-external-zip-files");
        if (existingFiles != null) {
            for (Object entry : existingFiles) {
                if (entry != null) {
                    String value = String.valueOf(entry);
                    if (!mergedFiles.contains(value)) {
                        mergedFiles.add(value);
                    }
                }
            }
        }
        if (!mergedFiles.contains(packLocation)) {
            mergedFiles.add(packLocation);
            craftEngineConfig.set("resource-pack.merge-external-zip-files", mergedFiles);
            try {
                craftEngineConfig.save(craftEngineConfigFile);
            } catch (IOException e) {
                getLogger().warning("Could not update the CraftEngine config: " + e.getMessage());
            }
        }
    }

    private boolean autoJumpDefault() {
        return getConfig().getBoolean("auto-jump-default", true);
    }

    private boolean f5ModeDefault() {
        return getConfig().getBoolean("f5-mode-default", false);
    }

    private void saveF5Mode(UUID id, boolean enabled) {
        playerData.set("players." + id + ".f5-mode", enabled);
        savePlayerData();
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save player data: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- events

    /** Stop double-tap-jump from starting real flight while ceiling walking. */
    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (e.isFlying() && active.contains(p.getUniqueId()) && usesGroundCheck(p)) {
            e.setCancelled(true);
            requestJump(p);
        }
    }

    @EventHandler
    public void onJump(PlayerJumpEvent e) {
        if (active.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            requestJump(e.getPlayer());
        }
    }

    private void requestJump(Player p) {
        UUID id = p.getUniqueId();
        if (!jumpHeld.add(id)) {
            return;
        }
        AttributeInstance gravity = p.getAttribute(Attribute.GRAVITY);
        if (gravity != null) {
            verticalVelocities.put(id, Math.copySign(0.42, gravity.getValue()));
        }
    }

    private boolean isSupported(Player p, double gravity) {
        ArmorStand vehicle = vehicles.get(p.getUniqueId());
        if (vehicle == null || !vehicle.isValid()) {
            return false;
        }

        Location location = vehicle.getLocation();
        return isSupportedAt(location.getWorld(), location.getX(), location.getY(),
                location.getZ(), gravity);
    }

    private boolean isSupportedAt(World world, double vehicleX, double vehicleY,
            double vehicleZ, double gravity) {
        double playerY = vehicleY - VEHICLE_EYE_OFFSET;
        double supportY = gravity < 0 ? playerY + 1.81 : playerY - 0.11;
        int minX = (int) Math.floor(vehicleX - 0.3);
        int maxX = (int) Math.floor(vehicleX + 0.3);
        int minZ = (int) Math.floor(vehicleZ - 0.3);
        int maxZ = (int) Math.floor(vehicleZ + 0.3);
        int supportBlockY = (int) Math.floor(supportY);
        double localY = supportY - supportBlockY;

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                Block block = world.getBlockAt(blockX, supportBlockY, blockZ);
                for (BoundingBox shapeBox : block.getCollisionShape().getBoundingBoxes()) {
                    if (shapeBox.getMinY() <= localY && shapeBox.getMaxY() >= localY
                            && shapeBox.getMaxX() > vehicleX - 0.3 - blockX
                            && shapeBox.getMinX() < vehicleX + 0.3 - blockX
                            && shapeBox.getMaxZ() > vehicleZ - 0.3 - blockZ
                            && shapeBox.getMinZ() < vehicleZ + 0.3 - blockZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (e.getEntity() instanceof Player p
                && !internalDismount.contains(p.getUniqueId())
                && e.getDismounted() == vehicles.get(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        ArmorStand vehicle = vehicles.get(id);
        if (!active.contains(id) || vehicle == null
                || e.getAction() != Action.RIGHT_CLICK_BLOCK
                || p.getVehicle() != vehicle || interactionPending.contains(id)) {
            return;
        }

        interactionPending.add(id);
        internalDismount.add(id);
        p.leaveVehicle();
        internalDismount.remove(id);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            interactionPending.remove(id);
            if (active.contains(id) && vehicle.isValid() && p.isOnline()) {
                vehicle.addPassenger(p);
            }
        }, 2L);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Player p && active.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onAnimation(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            Mannequin mannequin = mannequins.get(e.getPlayer().getUniqueId());
            if (mannequin != null && mannequin.isValid()) {
                mannequin.swingMainHand();
            }
        }
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (active.contains(id)) {
            e.setCancelled(true);
            sneaking.put(id, e.isSneaking());
            verticalVelocities.put(id, e.isSneaking() ? 0.0 : -0.0784);
            jumpHeld.remove(id);
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (!active.contains(p.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (active.contains(p.getUniqueId())) {
                applyHiddenPlayerEquipment(p);
                Mannequin mannequin = mannequins.get(p.getUniqueId());
                if (mannequin != null && mannequin.isValid()) {
                    updateMannequin(p, mannequin);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        autoJump.putIfAbsent(p.getUniqueId(), playerData.getBoolean(
                "players." + p.getUniqueId() + ".autojump", autoJumpDefault()));
        mouseWarningHidden.remove(p.getUniqueId());
        if (active.contains(p.getUniqueId())) {
            saveEnabled(p.getUniqueId(), true);
            disable(p, false);
        }
    }

    /** Safety net: gravity is saved in player data, so undo it after a crash. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        autoJump.put(id, playerData.getBoolean("players." + id + ".autojump", autoJumpDefault()));
        f5Mode.put(id, playerData.getBoolean("players." + id + ".f5-mode", f5ModeDefault()));
        mouseWarningHidden.put(id, playerData.getBoolean(
                "players." + id + ".hide-mouse-warning", false));
        for (Player activePlayer : Bukkit.getOnlinePlayers()) {
            if (active.contains(activePlayer.getUniqueId()) && activePlayer != p) {
                p.hidePlayer(this, activePlayer);
                p.hideEntity(this, activePlayer);
            }
        }
        if (p.getPersistentDataContainer().has(flagKey, PersistentDataType.BYTE)
                && !active.contains(id)) {
            disable(p, false);
        }
        if (playerData.getBoolean("players." + id + ".enabled", false)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!active.contains(id) && !enable(p)) {
                    getLogger().warning("Could not restore ceiling walking for " + p.getName());
                } else if (active.contains(id)) {
                    p.sendMessage(statusMessage(p, true));
                }
            });
        }
    }
}
