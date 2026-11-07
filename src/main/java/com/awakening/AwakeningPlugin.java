package com.awakening;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AwakeningPlugin extends JavaPlugin implements Listener, TabExecutor {

    private AwakeningCutscene current;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("awakening");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (current != null) current.finish();
    }

    void clear(AwakeningCutscene c) {
        if (current == c) current = null;
    }

    /** Ganti placeholder pada teks config (hasilnya format MiniMessage). */
    public String fmt(String raw, Race race, String player, double phase) {
        return raw.replace("{player}", player)
                .replace("{race}", race.display)
                .replace("{RACE}", race.display.toUpperCase(Locale.ROOT))
                .replace("{g1}", race.g1)
                .replace("{g2}", race.g2)
                .replace("{phase}", String.format(Locale.ROOT, "%.2f", phase));
    }

    /** Ambil teks: override per ras dulu (messages.human.subtitle), lalu default (messages.subtitle). */
    public String raw(Race race, String key, String def) {
        String k = "messages." + race.name().toLowerCase(Locale.ROOT) + "." + key;
        if (getConfig().isString(k)) return getConfig().getString(k, def);
        return getConfig().getString("messages." + key, def);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aConfig direload.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            if (current != null) {
                current.finish();
                sender.sendMessage("§eCutscene dihentikan.");
            } else {
                sender.sendMessage("§7Ga ada cutscene yang jalan.");
            }
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cPakai: /" + label + " <beast|dragon|elf|human> <player>");
            return true;
        }
        Race race = Race.parse(args[0]);
        if (race == null) {
            sender.sendMessage("§cRas ga dikenal. Pilih: beast, dragon, elf, human.");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer '" + args[1] + "' ga online.");
            return true;
        }
        if (current != null) {
            sender.sendMessage("§cMasih ada cutscene yang jalan. Tunggu selesai atau /" + label + " stop");
            return true;
        }

        List<Player> extra = new ArrayList<>();
        if (sender instanceof Player p && !p.equals(target)) extra.add(p);

        try {
            current = new AwakeningCutscene(this, race, target, extra);
            current.begin();
        } catch (Throwable t) {
            getLogger().severe("Gagal mulai cutscene: " + t);
            if (current != null) current.finish();
            current = null;
            sender.sendMessage("§cGagal mulai cutscene, cek console.");
            return true;
        }
        sender.sendMessage("§aAwakening " + race.display + " dimulai untuk " + target.getName() + ".");
        if (sender.equals(target) && !getConfig().getBoolean("target-watches", true)) {
            sender.sendMessage("§7Catatan: target ga liat freecam sendiri (yang nonton itu player di sekitar). Tes pakai akun kedua / ajak temen.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Race r : Race.values()) out.add(r.name().toLowerCase(Locale.ROOT));
            out.add("stop");
            out.add("reload");
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ---- Listener ----

    @EventHandler
    public void onStopSpectate(PlayerStopSpectatingEntityEvent e) {
        if (current != null && current.isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (current != null) current.handleJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (current != null) current.handleQuit(e.getPlayer());
    }
}
