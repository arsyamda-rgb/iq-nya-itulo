package com.awakening;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;

public enum Race {
    BEAST("Beast", "#FF9500", "#FF2A00", Color.fromRGB(255, 130, 20), Color.fromRGB(255, 60, 0),
            Particle.FLAME, Particle.CRIT,
            Sound.ENTITY_WOLF_HOWL, Sound.ENTITY_RAVAGER_ROAR),
    DRAGON("Dragon", "#C13BFF", "#FF2D6F", Color.fromRGB(170, 40, 255), Color.fromRGB(255, 50, 90),
            Particle.SOUL_FIRE_FLAME, Particle.PORTAL,
            Sound.ENTITY_ENDER_DRAGON_GROWL, Sound.ENTITY_ENDER_DRAGON_DEATH),
    ELF("Elf", "#3CFF9A", "#D9FFE9", Color.fromRGB(70, 255, 140), Color.fromRGB(230, 255, 240),
            Particle.HAPPY_VILLAGER, Particle.END_ROD,
            Sound.ITEM_TOTEM_USE, Sound.BLOCK_AMETHYST_BLOCK_CHIME),
    HUMAN("Human", "#FFC800", "#FFF6C2", Color.fromRGB(255, 215, 70), Color.fromRGB(255, 255, 255),
            Particle.ELECTRIC_SPARK, Particle.END_ROD,
            Sound.UI_TOAST_CHALLENGE_COMPLETE, Sound.ENTITY_PLAYER_LEVELUP);

    public final String display;
    public final String g1;         // warna gradasi teks (hex)
    public final String g2;
    public final Color primary;
    public final Color secondary;
    public final Particle ambient;  // keluar dari tanah
    public final Particle accent;   // aura di badan
    public final Sound sound1;
    public final Sound sound2;

    Race(String display, String g1, String g2, Color primary, Color secondary,
         Particle ambient, Particle accent, Sound sound1, Sound sound2) {
        this.display = display;
        this.g1 = g1;
        this.g2 = g2;
        this.primary = primary;
        this.secondary = secondary;
        this.ambient = ambient;
        this.accent = accent;
        this.sound1 = sound1;
        this.sound2 = sound2;
    }

    public static Race parse(String s) {
        if (s == null) return null;
        for (Race r : values()) {
            if (r.name().equalsIgnoreCase(s)) return r;
        }
        return null;
    }
}
