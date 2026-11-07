package com.awakening;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class AwakeningCutscene extends BukkitRunnable {

    // ===== Timeline (tick, 20 tick = 1 detik) =====
    static final int CHARGE_END = 70;    // energi ngumpul, retakan tanah
    static final int RISE_END = 250;     // player naik pelan -> KLIMAKS
    static final int HOLD_END = 315;     // melayang, aura penuh
    static final int TOTAL = 390;        // total ~19.5 detik
    static final int LAND_TICK = TOTAL - 15;
    private static final int[] BOLTS = {120, 160, 200, 232, 250, 266, 284};

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String DEF_START = "<gradient:{g1}:{g2}:{phase}>Sesuatu sedang terbangun...</gradient>";
    private static final String DEF_TITLE = "<bold><gradient:{g1}:{g2}:{phase}>{player}</gradient></bold>";
    private static final String DEF_SUB = "<gradient:{g2}:{g1}:{phase}>TELAH BER-EVOLUSI MENJADI GRAND {RACE}</gradient>";
    private static final String DEF_BROADCAST = "<gradient:{g1}:{g2}><bold>{player}</bold> TELAH BER-EVOLUSI MENJADI GRAND {RACE}!</gradient>";

    private record Saved(GameMode mode, Location loc, boolean allowFlight, boolean flying) {}

    private final AwakeningPlugin plugin;
    private final Race race;
    private final Player target;
    private final World world;
    private final double bx, by, bz;
    private final float baseYaw;
    private final double maxRise;
    private final int maxDebris;
    private final List<Player> viewers = new ArrayList<>();
    private final Map<UUID, Saved> saved = new HashMap<>();
    private final List<Debris> debris = new ArrayList<>();
    private final Random rnd = new Random();
    private final BlockData groundData;
    private final double[][][] cracks;
    private final DustOptions dustP, dustS, dustBig, dustCrack, dustElf;

    private final boolean targetWatches;
    private final boolean letterbox;
    private final double lbRatio, lbDist;
    private ArmorStand camera;
    private ArmorStand body;
    private BlockDisplay barTop, barBottom;
    private double lastEdge = Double.NaN;
    private int titleStart = -1000;
    private int tick = 0;
    private double camAngle;
    private double spin;
    private int shockStart = -1000;
    private boolean finished;
    private boolean completed;
    private boolean oldGravity, oldInvulnerable;

    public AwakeningCutscene(AwakeningPlugin plugin, Race race, Player target, Collection<Player> extraViewers) {
        this.plugin = plugin;
        this.race = race;
        this.target = target;
        Location l = target.getLocation();
        this.world = l.getWorld();
        this.bx = l.getX();
        this.by = l.getY();
        this.bz = l.getZ();
        this.baseYaw = l.getYaw();

        // tinggi maksimal naik (dibatasi atap)
        int free = 0;
        for (int i = 2; i <= 16; i++) {
            if (world.getBlockAt(l.getBlockX(), l.getBlockY() + i, l.getBlockZ()).isPassable()) free++;
            else break;
        }
        this.maxRise = Math.max(0, Math.min(plugin.getConfig().getDouble("max-rise", 9.0), free));
        this.maxDebris = plugin.getConfig().getInt("max-debris", 45);

        // viewer: player sekitar + yang ngetik command
        this.targetWatches = plugin.getConfig().getBoolean("target-watches", true);
        this.letterbox = plugin.getConfig().getBoolean("letterbox", true);
        this.lbRatio = plugin.getConfig().getDouble("letterbox-ratio", 0.74);
        this.lbDist = plugin.getConfig().getDouble("letterbox-distance", 0.6);
        double r = plugin.getConfig().getDouble("radius", 48);
        Set<Player> set = new LinkedHashSet<>(extraViewers);
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            if (r <= 0 || p.getLocation().distanceSquared(l) <= r * r) set.add(p);
        }
        set.remove(target);
        set.removeIf(p -> !p.getWorld().equals(l.getWorld()));
        viewers.addAll(set);

        Block under = world.getBlockAt(l.getBlockX(), (int) Math.floor(by) - 1, l.getBlockZ());
        this.groundData = (under.getType().isSolid() && !under.getType().isAir())
                ? under.getBlockData() : Material.STONE.createBlockData();

        this.dustP = new DustOptions(race.primary, 1.6f);
        this.dustS = new DustOptions(race.secondary, 1.3f);
        this.dustBig = new DustOptions(race.primary, 3.2f);
        this.dustCrack = new DustOptions(race.secondary, 1.1f);
        this.dustElf = new DustOptions(org.bukkit.Color.fromRGB(200, 255, 220), 1.2f);

        // retakan tanah acak
        int n = 10;
        this.cracks = new double[n][][];
        for (int i = 0; i < n; i++) {
            double dir = i * Math.PI * 2 / n + (rnd.nextDouble() - 0.5) * 0.4;
            double x = bx, z = bz, y = by;
            double[][] pts = new double[24][];
            for (int s = 0; s < pts.length; s++) {
                dir += (rnd.nextDouble() - 0.5) * 0.5;
                x += Math.cos(dir) * 0.4;
                z += Math.sin(dir) * 0.4;
                y = groundTop(x, z, y);
                pts[s] = new double[]{x, y, z};
            }
            cracks[i] = pts;
        }
    }

    // =====================================================================
    // Start / Finish
    // =====================================================================

    public void begin() {
        if (targetWatches) {
            // target ikut nonton: dia masuk spectator, badannya diganti "patung"
            body = spawnBody();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(target)) p.hideEntity(plugin, target);
            }
            viewers.add(target);
        } else {
            oldGravity = target.hasGravity();
            oldInvulnerable = target.isInvulnerable();
            target.leaveVehicle();
            target.setGravity(false);
            target.setInvulnerable(true);
        }

        double yaw = Math.toRadians(baseYaw);
        camAngle = Math.atan2(Math.cos(yaw), -Math.sin(yaw)); // mulai dari depan player
        Location camLoc = new Location(world, bx + Math.cos(camAngle) * 11, by + 0.7, bz + Math.sin(camAngle) * 11);
        camLoc.setDirection(new Vector(bx - camLoc.getX(), 0.3, bz - camLoc.getZ()));

        camera = world.spawn(camLoc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
        });

        if (letterbox) {
            barTop = makeBar(camLoc);
            barBottom = makeBar(camLoc);
            hideFromNonViewers(barTop);
            hideFromNonViewers(barBottom);
        }

        for (Player p : viewers) {
            p.leaveVehicle();
            saved.put(p.getUniqueId(), new Saved(p.getGameMode(), p.getLocation().clone(), p.getAllowFlight(), p.isFlying()));
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(camLoc);
        }
        runTaskTimer(plugin, 1L, 1L);
    }

    public void finish() {
        if (finished) return;
        finished = true;
        try {
            cancel();
        } catch (IllegalStateException ignored) {
        }
        for (Debris d : debris) d.remove();
        debris.clear();

        for (Player p : new ArrayList<>(viewers)) restore(p);
        viewers.clear();

        try {
            if (targetWatches) {
                for (Player p : Bukkit.getOnlinePlayers()) p.showEntity(plugin, target);
            }
            if (target.isOnline()) {
                if (!targetWatches) {
                    target.setGravity(oldGravity);
                    target.setInvulnerable(oldInvulnerable);
                    target.teleport(new Location(world, bx, by, bz, baseYaw + (float) spin, 0f));
                    target.setVelocity(new Vector(0, 0, 0));
                }
                target.setFallDistance(0f);
                if (completed) applyBuffs();
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Gagal restore target", t);
        }
        if (body != null) body.remove();
        if (barTop != null) barTop.remove();
        if (barBottom != null) barBottom.remove();
        if (camera != null) camera.remove();
        plugin.clear(this);
    }

    private void restore(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        if (s == null) return;
        try {
            p.setSpectatorTarget(null);
            p.setGameMode(s.mode());
            p.teleport(s.loc());
            p.setAllowFlight(s.allowFlight());
            if (s.allowFlight()) p.setFlying(s.flying());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Gagal restore " + p.getName(), t);
        }
    }

    public void handleJoin(Player p) {
        if (targetWatches && !p.equals(target)) p.hideEntity(plugin, target);
        if (barTop != null) {
            p.hideEntity(plugin, barTop);
            p.hideEntity(plugin, barBottom);
        }
    }

    public boolean isViewer(Player p) {
        return saved.containsKey(p.getUniqueId());
    }

    public void handleQuit(Player p) {
        if (p.equals(target)) {
            finish();
        } else if (saved.containsKey(p.getUniqueId())) {
            restore(p);
            viewers.remove(p);
        }
    }

    private void applyBuffs() {
        if (!plugin.getConfig().getBoolean("buffs.enabled", true)) return;
        int dur = plugin.getConfig().getInt("buffs.duration-seconds", 300) * 20;
        switch (race) {
            case BEAST -> {
                buff(PotionEffectType.STRENGTH, dur, 1);
                buff(PotionEffectType.SPEED, dur, 1);
                buff(PotionEffectType.JUMP_BOOST, dur, 0);
            }
            case DRAGON -> {
                buff(PotionEffectType.STRENGTH, dur, 1);
                buff(PotionEffectType.FIRE_RESISTANCE, dur, 0);
                buff(PotionEffectType.RESISTANCE, dur, 0);
            }
            case ELF -> {
                buff(PotionEffectType.SPEED, dur, 2);
                buff(PotionEffectType.JUMP_BOOST, dur, 1);
                buff(PotionEffectType.REGENERATION, dur, 0);
            }
            case HUMAN -> {
                buff(PotionEffectType.REGENERATION, dur, 1);
                buff(PotionEffectType.HEALTH_BOOST, dur, 2);
                buff(PotionEffectType.ABSORPTION, dur, 1);
            }
        }
    }

    private void buff(PotionEffectType type, int dur, int amp) {
        target.addPotionEffect(new PotionEffect(type, dur, amp));
    }

    // =====================================================================
    // Main loop
    // =====================================================================

    @Override
    public void run() {
        if (finished) return;
        try {
            step();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Error di cutscene", t);
            finish();
        }
    }

    private void step() {
        if (!target.isOnline() || target.isDead()) {
            finish();
            return;
        }
        int t = tick;
        double h = riseHeight(t);
        double cx = bx, cy = by + 1.0 + h, cz = bz;

        moveTarget(t, h);
        timeline(t, cx, cy, cz);
        effects(t, h, cx, cy, cz);
        debrisTick(t);
        updateCamera(t, cx, cy, cz);
        keepViewers();

        if (++tick >= TOTAL) {
            completed = true;
            finish();
        }
    }

    private double riseHeight(int t) {
        if (t < CHARGE_END) return 0;
        if (t < RISE_END) return maxRise * ease((t - CHARGE_END) / (double) (RISE_END - CHARGE_END));
        if (t < HOLD_END) return maxRise + Math.sin((t - RISE_END) * 0.12) * 0.12;
        if (t < LAND_TICK) return maxRise * (1.0 - ease((t - HOLD_END) / (double) (LAND_TICK - HOLD_END)));
        return 0;
    }

    private void moveTarget(int t, double h) {
        double speed = t < CHARGE_END ? 0 : (t < RISE_END ? 1.0 : (t < HOLD_END ? 0.5 : 0.7));
        spin += speed;
        double f = maxRise <= 0 ? 0 : Math.min(1, h / maxRise);
        if (targetWatches) {
            moveBody(t, h, f);
            return;
        }
        Location l = new Location(world, bx, by + h, bz, (float) (baseYaw + spin), (float) (-30 * f));
        target.teleport(l);
        target.setFallDistance(0f);
        target.setVelocity(new Vector(0, 0, 0));
    }

    /** "Patung" pengganti badan target (karena target lagi spectator). */
    private ArmorStand spawnBody() {
        Location l = new Location(world, bx, by, bz, baseYaw, 0f);
        PlayerInventory inv = target.getInventory();
        ItemStack helm = headOr(inv.getHelmet());
        ItemStack chest = armorOr(inv.getChestplate(), Material.LEATHER_CHESTPLATE);
        ItemStack legs = armorOr(inv.getLeggings(), Material.LEATHER_LEGGINGS);
        ItemStack boots = armorOr(inv.getBoots(), Material.LEATHER_BOOTS);
        return world.spawn(l, ArmorStand.class, as -> {
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
            as.setMarker(true);
            EntityEquipment eq = as.getEquipment();
            eq.setHelmet(helm);
            eq.setChestplate(chest);
            eq.setLeggings(legs);
            eq.setBoots(boots);
        });
    }

    private ItemStack headOr(ItemStack worn) {
        if (worn != null && !worn.getType().isAir()) return worn.clone();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) head.getItemMeta();
        m.setOwningPlayer(target);
        head.setItemMeta(m);
        return head;
    }

    private ItemStack armorOr(ItemStack worn, Material leather) {
        if (worn != null && !worn.getType().isAir()) return worn.clone();
        ItemStack it = new ItemStack(leather);
        LeatherArmorMeta m = (LeatherArmorMeta) it.getItemMeta();
        m.setColor(race.primary);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private void moveBody(int t, double h, double f) {
        body.teleport(new Location(world, bx, by + h, bz, (float) (baseYaw + spin), 0f));
        double in = ease((t - CHARGE_END) / 50.0);
        double out = t < LAND_TICK - 30 ? 1.0 : ease((LAND_TICK - t) / 30.0);
        double af = Math.min(in, out);                       // tangan terangkat
        double flap = Math.sin(t * 0.13) * 8 * af;
        double armZ = Math.toRadians(10 + 95 * af + flap);
        double armX = Math.toRadians(-10 - 20 * af);
        body.setRightArmPose(new EulerAngle(armX, 0, armZ));
        body.setLeftArmPose(new EulerAngle(armX, 0, -armZ));
        body.setHeadPose(new EulerAngle(Math.toRadians(-25 * f), 0, 0));
    }

    private void keepViewers() {
        viewers.removeIf(p -> !p.isOnline());
        for (Player p : viewers) {
            if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
            if (p.getSpectatorTarget() != camera) p.setSpectatorTarget(camera);
        }
    }

    // =====================================================================
    // Camera (freecam / b-roll)
    // =====================================================================

    private void updateCamera(int t, double cx, double cy, double cz) {
        double radius, vOff, angVel;
        if (t < CHARGE_END) {
            double p = ease(t / (double) CHARGE_END);
            radius = lerp(11, 8.5, p);
            vOff = lerp(-0.3, -0.9, p);       // low angle, ngedongak ke player
            angVel = 0.010;
        } else if (t < RISE_END) {
            double p = ease((t - CHARGE_END) / (double) (RISE_END - CHARGE_END));
            radius = lerp(8.5, 6.5, p);
            vOff = lerp(-0.9, 0.5, p);        // kamera naik ngikutin player
            angVel = 0.018;
        } else if (t < HOLD_END) {
            double p = ease((t - RISE_END) / (double) (HOLD_END - RISE_END));
            radius = lerp(6.5, 3.8, p);
            vOff = lerp(0.5, -1.1, p);        // push-in hero shot
            angVel = 0.030;
        } else {
            double p = ease(Math.min(1, (t - HOLD_END) / (double) (TOTAL - HOLD_END)));
            radius = lerp(3.8, 11, p);
            vOff = lerp(-1.1, 2.5, p);        // pull back & naik
            angVel = 0.014;
        }

        double shake = 0.02 + 0.03 * Math.min(1, t / (double) CHARGE_END);
        if (t >= RISE_END && t < RISE_END + 14) shake += 0.35 * (1 - (t - RISE_END) / 14.0);

        camAngle += angVel;
        double camX = cx + Math.cos(camAngle) * radius + (rnd.nextDouble() - 0.5) * 2 * shake;
        double camY = cy + vOff + (rnd.nextDouble() - 0.5) * 2 * shake;
        double camZ = cz + Math.sin(camAngle) * radius + (rnd.nextDouble() - 0.5) * 2 * shake;

        Location loc = new Location(world, camX, camY, camZ);
        loc.setDirection(new Vector(cx - camX, (cy + 0.2) - camY, cz - camZ));
        camera.teleport(loc);
        updateBars(t, loc);
    }

    // ----- Widescreen (letterbox) -----
    // Dua plat hitam nempel di depan kamera (billboard), cuma keliatan sama penonton.

    private BlockDisplay makeBar(Location at) {
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(Material.BLACK_CONCRETE.createBlockData());
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setTeleportDuration(3);
            d.setViewRange(1.0f);
        });
    }

    private void hideFromNonViewers(Entity e) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!viewers.contains(p)) p.hideEntity(plugin, e);
        }
    }

    private void updateBars(int t, Location cam) {
        if (barTop == null) return;
        double ratio = lbRatio;
        if (t < 20) ratio = lerp(1.25, lbRatio, ease(t / 20.0));                    // bar masuk
        else if (t > TOTAL - 20) ratio = lerp(lbRatio, 1.25, ease((t - (TOTAL - 20)) / 20.0)); // bar keluar

        Location pos = cam.clone().add(cam.getDirection().multiply(lbDist));
        barTop.teleport(pos);
        barBottom.teleport(pos);

        double edge = lbDist * Math.tan(Math.toRadians(35)) * ratio;
        if (Double.isNaN(lastEdge) || Math.abs(edge - lastEdge) > 1e-4) {
            lastEdge = edge;
            float w = 10f, hgt = 3f, th = 0.02f;
            applyBar(barTop, (float) edge, w, hgt, th);
            applyBar(barBottom, (float) (-edge - hgt), w, hgt, th);
        }
    }

    private void applyBar(BlockDisplay d, float y, float w, float h, float th) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(new Vector3f(-w / 2f, y, -th / 2f), new Quaternionf(),
                new Vector3f(w, h, th), new Quaternionf()));
    }

    // =====================================================================
    // Timeline: suara, title, petir
    // =====================================================================

    private void timeline(int t, double cx, double cy, double cz) {
        Location at = new Location(world, cx, cy, cz);

        if (t == 0) {
            showTitle(Component.empty(), comp("start", DEF_START, 0), 10, 50, 15, false);
            sfx(at, Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.5f);
        }
        if (t < CHARGE_END && t % 5 == 0) {
            Component ab = comp("start", DEF_START, Math.sin(t * 0.1));
            for (Player p : participants()) p.sendActionBar(ab);
        }
        if (t < RISE_END && t % 20 == 0) {
            sfx(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.8f, 0.7f + 0.6f * t / RISE_END);
        }
        if (t == CHARGE_END) sfx(at, Sound.BLOCK_BEACON_ACTIVATE, 1.6f, 0.6f);
        if (t == 150) sfx(at, race.sound1, 1.4f, 0.55f);
        for (int b : BOLTS) {
            if (t == b) bolt();
        }
        if (t == RISE_END) climax(at, cx, cy, cz);
        int ts = t - titleStart;
        if (ts >= 0 && ts <= 90 && ts % 3 == 0) shimmerTitle(ts);
        if (t == LAND_TICK) landing();

        if (t >= 100 && t < HOLD_END) {
            switch (race) {
                case DRAGON -> {
                    if (t % 32 == 0) sfx(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.6f, 0.9f);
                }
                case ELF -> {
                    if (t % 25 == 0) sfx(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.7f + rnd.nextFloat() * 0.6f);
                }
                case HUMAN -> {
                    if (t % 40 == 0) sfx(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.4f, 1.0f);
                }
                case BEAST -> {
                    if (t % 45 == 0) sfx(at, Sound.ENTITY_WOLF_GROWL, 1.4f, 0.6f);
                }
            }
        }
    }

    private void bolt() {
        double a = rnd.nextDouble() * Math.PI * 2;
        double r = 6 + rnd.nextDouble() * 5;
        double x = bx + Math.cos(a) * r, z = bz + Math.sin(a) * r;
        world.strikeLightningEffect(new Location(world, x, groundTop(x, z, by), z)); // visual saja, ga ada damage
    }

    private void climax(Location at, double cx, double cy, double cz) {
        shockStart = tick;
        sfx(at, race.sound1, 2.0f, 0.8f);
        sfx(at, race.sound2, 2.0f, 0.7f);
        sfx(at, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        sfx(at, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.7f);

        spawn(Particle.EXPLOSION_EMITTER, cx, cy, cz, 1, 0, 0, 0, 0, null);
        spawn(Particle.FIREWORK, cx, cy, cz, 140, 0.3, 0.3, 0.3, 0.6, null);
        spawn(Particle.DUST, cx, cy, cz, 220, 1.4, 1.4, 1.4, 0, dustBig);

        for (Debris d : debris) d.blast();

        titleStart = tick;   // title gradasi dianimasi di timeline()
        if (plugin.getConfig().getBoolean("broadcast-chat", true)) {
            Bukkit.broadcast(comp("broadcast", DEF_BROADCAST, 0));
        }
    }

    private void landing() {
        Location l = new Location(world, bx, by, bz);
        sfx(l, Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 0.7f);
        spawn(Particle.FIREWORK, bx, by + 0.3, bz, 60, 0.6, 0.1, 0.6, 0.25, null);
        dustRing(bx, by + 0.2, bz, 1.5, 28, dustP);
        dustRing(bx, by + 0.2, bz, 3.0, 44, dustS);
    }

    // =====================================================================
    // Partikel
    // =====================================================================

    private void effects(int t, double h, double cx, double cy, double cz) {
        // retakan tanah
        if (t < HOLD_END + 10 && t % 3 == 0) drawCracks(t);

        // cincin energi menyempit ke tengah
        if (t < RISE_END) {
            double rr = 9.0 * (1.0 - (t % 30) / 30.0) + 0.8;
            dustRing(bx, by + 0.2, bz, rr, (int) (rr * 6) + 12, dustP);
            spawn(race.ambient, bx, by + 0.1, bz, 5, 2.8, 0.1, 2.8, 0.03, null);
        }
        // serpihan tanah
        if (t < HOLD_END && t % 2 == 0) {
            spawn(Particle.BLOCK, bx + (rnd.nextDouble() - 0.5) * 9, by + 0.2, bz + (rnd.nextDouble() - 0.5) * 9,
                    6, 0.4, 0.2, 0.4, 0.1, groundData);
        }
        // gelombang cincin dari tanah pas naik
        if (t >= CHARGE_END && t < RISE_END) {
            double rr = ((t % 25) / 25.0) * 12.0;
            dustRing(bx, by + 0.15, bz, rr, (int) (rr * 5) + 10, dustS);
        }
        // pilar cahaya dari tanah ke player
        if (t >= CHARGE_END && t < HOLD_END && t % 2 == 0) {
            for (double y = by + 0.3; y < cy - 0.5; y += 0.55) {
                spawn(Particle.DUST, bx + (rnd.nextDouble() - 0.5) * 0.3, y, bz + (rnd.nextDouble() - 0.5) * 0.3,
                        1, 0, 0, 0, 0, dustS);
            }
        }
        // aura
        if (t >= 30 && t < LAND_TICK) helix(t, cx, cy, cz);
        if (t >= RISE_END && t < HOLD_END) {
            spawn(race.accent, cx, cy, cz, 10, 1.1, 1.3, 1.1, 0.07, null);
        } else if (t >= CHARGE_END && t < RISE_END) {
            spawn(race.accent, cx, cy, cz, 2, 0.9, 1.1, 0.9, 0.04, null);
        }

        // shockwave klimaks
        int s = t - shockStart;
        if (s >= 0 && s < 30) {
            double rr = s * 0.75 + 0.5;
            int pts = (int) (rr * 8) + 16;
            dustRing(bx, by + 0.3, bz, rr, pts, dustBig);
            dustRing(cx, cy, cz, rr, pts, dustP);
        }

        // ciri khas tiap ras
        double yaw = baseYaw + spin;
        double u = wingFactor(t);
        switch (race) {
            case DRAGON -> {
                if (t % 2 == 0) drawWings(cx, cy, cz, yaw, u);
                dragonFire(t);
            }
            case ELF -> {
                if (t % 2 == 0) drawWings(cx, cy, cz, yaw, u);
                if (u > 0.1) spawn(Particle.CHERRY_LEAVES, cx, cy + 3, cz, 4, 3.0, 1.0, 3.0, 0.0, null);
            }
            case HUMAN -> humanHalo(t, cx, cy, cz, u);
            case BEAST -> beastSpikes(t, cx, cy, cz, u);
        }
    }

    private void drawCracks(int t) {
        double prog = Math.min(1.0, t / 80.0);
        for (double[][] crack : cracks) {
            int n = (int) (crack.length * prog);
            for (int i = 0; i < n; i++) {
                spawn(Particle.DUST, crack[i][0], crack[i][1], crack[i][2], 1, 0, 0, 0, 0, dustCrack);
            }
        }
    }

    private void helix(int t, double cx, double cy, double cz) {
        for (int i = 0; i < 3; i++) {
            double a = t * 0.32 + i * 2.094;
            double yy = cy - 0.9 + ((t * 0.07 + i * 0.8) % 2.4);
            double rr = 0.95 + 0.15 * Math.sin(t * 0.2 + i);
            double x = cx + Math.cos(a) * rr, z = cz + Math.sin(a) * rr;
            spawn(race.accent, x, yy, z, 1, 0.02, 0.02, 0.02, 0.01, null);
            spawn(Particle.DUST, x, yy, z, 1, 0, 0, 0, 0, dustP);
        }
    }

    private double wingFactor(int t) {
        double in = ease((t - 100) / 60.0);
        double out = t < LAND_TICK - 25 ? 1.0 : ease((LAND_TICK - t) / 25.0);
        return Math.min(in, out);
    }

    /** Sayap dari partikel: Dragon = sayap kelelawar (ada membran), Elf = sayap bulu. */
    private void drawWings(double cx, double cy, double cz, double yawDeg, double u) {
        if (u < 0.02) return;
        boolean dragon = race == Race.DRAGON;
        int bones = dragon ? 5 : 7;
        double yaw = Math.toRadians(yawDeg);
        double rxv = -Math.cos(yaw), rzv = -Math.sin(yaw);   // kanan player
        double bxv = Math.sin(yaw), bzv = -Math.cos(yaw);    // belakang player
        double flap = Math.sin(tick * 0.13) * 0.32 * (0.4 + 0.6 * u);
        double scale = (dragon ? 1.0 : 0.85) * u;
        DustOptions main = dragon ? dustP : dustElf;
        DustOptions edge = dustS;

        for (int side = -1; side <= 1; side += 2) {
            double[][] tips = new double[bones][];
            for (int j = 0; j < bones; j++) {
                double f = j / (double) (bones - 1);
                double phi = Math.toRadians(75 - 115 * f) + flap;
                double len = (dragon ? (5.6 - 2.4 * f * f) : (4.4 - 1.8 * f)) * scale;
                double dirX = Math.cos(phi) * side;
                double dirY = Math.sin(phi);
                double dirZ = 0.45 * (0.3 + f);
                int samples = Math.max(2, (int) (len / 0.5));
                double wx = cx, wy = cy, wz = cz;
                for (int s = 1; s <= samples; s++) {
                    double k = s / (double) samples;
                    double lx = dirX * len * k;
                    double ly = dirY * len * k;
                    double lz = dirZ * len * k + 0.3;
                    wx = cx + rxv * lx + bxv * lz;
                    wy = cy + 0.4 + ly;
                    wz = cz + rzv * lx + bzv * lz;
                    spawn(Particle.DUST, wx, wy, wz, 1, 0, 0, 0, 0, k > 0.85 ? edge : main);
                }
                tips[j] = new double[]{wx, wy, wz};
                if (!dragon) spawn(Particle.END_ROD, wx, wy, wz, 1, 0, 0, 0, 0.01, null);
            }
            if (dragon) {
                // membran antar tulang sayap
                for (int j = 0; j < bones - 1; j++) {
                    for (int s = 0; s <= 5; s++) {
                        double k = s / 5.0;
                        spawn(Particle.DUST,
                                lerp(tips[j][0], tips[j + 1][0], k),
                                lerp(tips[j][1], tips[j + 1][1], k),
                                lerp(tips[j][2], tips[j + 1][2], k),
                                1, 0, 0, 0, 0, edge);
                    }
                }
            }
        }
    }

    private void dragonFire(int t) {
        if (t < 130 || t > HOLD_END) return;
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + t * 0.01;
            double x = bx + Math.cos(a) * 6.5, z = bz + Math.sin(a) * 6.5;
            double y = groundTop(x, z, by);
            spawn(Particle.SOUL_FIRE_FLAME, x, y, z, 0, 0.0, 1.0, 0.0, 0.35, null);
            spawn(Particle.SOUL_FIRE_FLAME, x, y, z, 0, 0.0, 1.0, 0.0, 0.25, null);
        }
    }

    private void humanHalo(int t, double cx, double cy, double cz, double u) {
        if (u < 0.05) return;
        if (t % 2 == 0) {
            dustRing(cx, cy + 1.2, cz, 0.7 * u + 0.1, 22, dustP);   // halo
            for (int i = 0; i < 8; i++) {                           // sinar
                double a = t * 0.05 + i * Math.PI / 4;
                for (double d = 1.2; d < 1.2 + 4.5 * u; d += 0.5) {
                    spawn(Particle.DUST, cx + Math.cos(a) * d, cy + 0.3, cz + Math.sin(a) * d, 1, 0, 0, 0, 0, dustS);
                }
            }
        }
        for (int i = 0; i < 2; i++) {
            spawn(Particle.END_ROD, cx + (rnd.nextDouble() - 0.5) * 0.9, cy - 1 + rnd.nextDouble() * 9,
                    cz + (rnd.nextDouble() - 0.5) * 0.9, 0, 0, 1, 0, 0.12, null);
        }
    }

    private void beastSpikes(int t, double cx, double cy, double cz, double u) {
        if (u < 0.05) return;
        if (t % 2 == 0) {
            for (int i = 0; i < 10; i++) {
                double a = t * 0.05 + i * Math.PI / 5;
                double len = (1.8 + 0.8 * Math.sin(t * 0.2 + i)) * u;
                double rise = (i % 2 == 0) ? 0.55 : 0.1;
                for (double d = 0.7; d < 0.7 + len; d += 0.35) {
                    spawn(Particle.DUST, cx + Math.cos(a) * d, cy + 0.3 + d * rise, cz + Math.sin(a) * d,
                            1, 0, 0, 0, 0, dustP);
                }
                double e = 0.7 + len;
                spawn(Particle.FLAME, cx + Math.cos(a) * e, cy + 0.3 + e * rise, cz + Math.sin(a) * e,
                        1, 0.02, 0.02, 0.02, 0.01, null);
            }
        }
        if (t % 5 == 0) {
            spawn(Particle.SWEEP_ATTACK, cx + (rnd.nextDouble() - 0.5) * 5, cy + (rnd.nextDouble() - 0.5) * 3,
                    cz + (rnd.nextDouble() - 0.5) * 5, 1, 0, 0, 0, 0, null);
        }
        if (t % 20 == 0) spawn(Particle.ANGRY_VILLAGER, cx, cy + 1.4, cz, 3, 0.4, 0.2, 0.4, 0, null);
    }

    // =====================================================================
    // Blok melayang
    // =====================================================================

    private void debrisTick(int t) {
        if (t >= 15 && t < 170 && debris.size() < maxDebris) {
            int n = 1 + rnd.nextInt(2);
            for (int i = 0; i < n; i++) spawnDebris(t);
        }
        Iterator<Debris> it = debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            if (!d.update(t)) {
                d.remove();
                it.remove();
            }
        }
    }

    private void spawnDebris(int t) {
        for (int attempt = 0; attempt < 4; attempt++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = 1.8 + rnd.nextDouble() * 7.5;
            int x = (int) Math.floor(bx + Math.cos(a) * r);
            int z = (int) Math.floor(bz + Math.sin(a) * r);
            Block ground = null;
            for (int y = (int) Math.floor(by) + 2; y >= (int) Math.floor(by) - 4; y--) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType().isOccluding() && b.getType() != Material.BEDROCK
                        && world.getBlockAt(x, y + 1, z).isPassable()) {
                    ground = b;
                    break;
                }
            }
            if (ground == null) continue;

            final float scale = 0.5f + rnd.nextFloat() * 0.6f;
            final BlockData data = ground.getBlockData();
            final boolean glow = rnd.nextInt(100) < 30;
            double cy0 = ground.getY() + 0.5;
            Location loc = new Location(world, x + 0.5, cy0, z + 0.5);
            BlockDisplay bd = world.spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setPersistent(false);
                d.setTeleportDuration(2);
                d.setViewRange(2.0f);
                d.setTransformation(new Transformation(
                        new Vector3f(-0.5f * scale, -0.5f * scale, -0.5f * scale),
                        new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
                if (glow) {
                    d.setGlowing(true);
                    d.setGlowColorOverride(race.primary);
                }
            });
            debris.add(new Debris(bd, x + 0.5, cy0, z + 0.5, t, scale));
            return;
        }
    }

    private final class Debris {
        final BlockDisplay e;
        final double y0, r0, a0, h, orbit, phase;
        final int born, delay, lift;
        final float scale;
        double px, py, pz, vx, vy, vz;
        double rx, ry, rz;
        final double spinX, spinY, spinZ;
        boolean blasting;
        int blastAge;

        Debris(BlockDisplay e, double x0, double y0, double z0, int born, float scale) {
            this.e = e;
            this.y0 = y0;
            this.born = born;
            this.scale = scale;
            double dx = x0 - bx, dz = z0 - bz;
            this.r0 = Math.max(0.5, Math.hypot(dx, dz));
            this.a0 = Math.atan2(dz, dx);
            this.h = 1.5 + rnd.nextDouble() * (maxRise + 1.0);
            this.orbit = 0.025 + rnd.nextDouble() * 0.04;
            this.phase = rnd.nextDouble() * 6.28;
            this.delay = rnd.nextInt(30);
            this.lift = 90 + rnd.nextInt(50);
            this.spinX = (rnd.nextDouble() - 0.5) * 0.12;
            this.spinY = (rnd.nextDouble() - 0.5) * 0.12;
            this.spinZ = (rnd.nextDouble() - 0.5) * 0.12;
            this.px = x0;
            this.py = y0;
            this.pz = z0;
        }

        void blast() {
            if (blasting) return;
            blasting = true;
            double dx = px - bx, dz = pz - bz;
            double d = Math.max(0.5, Math.hypot(dx, dz));
            double sp = 0.45 + rnd.nextDouble() * 0.9;
            vx = dx / d * sp - dz / d * 0.15;
            vz = dz / d * sp + dx / d * 0.15;
            vy = 0.2 + rnd.nextDouble() * 0.5;
        }

        boolean update(int t) {
            if (!blasting) {
                int age = t - born - delay;
                if (age <= 0) return true;
                double ez = ease(Math.min(1.0, age / (double) lift));
                double ang = a0 + age * orbit;
                double rad = r0 * (1.0 - 0.3 * ez);
                px = bx + Math.cos(ang) * rad;
                pz = bz + Math.sin(ang) * rad;
                py = y0 + ez * h + Math.sin(age * 0.08 + phase) * 0.25 * ez;
            } else {
                blastAge++;
                vy -= 0.05;
                vx *= 0.995;
                vz *= 0.995;
                px += vx;
                py += vy;
                pz += vz;
                if ((py <= y0 && vy < 0) || blastAge > 90) {
                    impact();
                    return false;
                }
            }
            rx += spinX;
            ry += spinY;
            rz += spinZ;
            e.teleport(new Location(world, px, py, pz));
            if (t % 2 == 0) applyTransform();
            return true;
        }

        void applyTransform() {
            Quaternionf q = new Quaternionf().rotationXYZ((float) rx, (float) ry, (float) rz);
            Vector3f c = new Vector3f(0.5f * scale, 0.5f * scale, 0.5f * scale);
            q.transform(c);
            c.negate();
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(2);
            e.setTransformation(new Transformation(c, q, new Vector3f(scale, scale, scale), new Quaternionf()));
        }

        void impact() {
            BlockData data = e.getBlock();
            spawn(Particle.BLOCK, px, py, pz, 12, 0.3, 0.3, 0.3, 0.1, data);
            if (rnd.nextInt(100) < 40) {
                world.playSound(new Location(world, px, py, pz), data.getSoundGroup().getBreakSound(), 1f, 0.8f);
            }
        }

        void remove() {
            if (e.isValid()) e.remove();
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private List<Player> participants() {
        List<Player> l = new ArrayList<>(viewers);
        if (!l.contains(target)) l.add(target);
        return l;
    }

    private void sfx(Location at, Sound s, float vol, float pitch) {
        for (Player p : participants()) p.playSound(at, s, SoundCategory.MASTER, vol, pitch);
    }

    private void showTitle(Component t, Component sub, int in, int stay, int out, boolean global) {
        Title title = Title.title(t, sub,
                Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        Set<Player> sent = new HashSet<>(participants());
        for (Player p : sent) p.showTitle(title);
        if (global) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!sent.contains(p)) p.showTitle(title);
            }
        }
    }

    /** Title klimaks: dikirim ulang tiap 3 tick dengan phase gradasi geser -> efek berkilau. */
    private void shimmerTitle(int ts) {
        double phase = Math.sin(ts * 0.12);
        boolean last = ts >= 90;
        showTitle(comp("title", DEF_TITLE, phase), comp("subtitle", DEF_SUB, phase),
                ts == 0 ? 5 : 0, last ? 25 : 8, last ? 25 : 0,
                plugin.getConfig().getBoolean("global-title", true));
    }

    private Component comp(String key, String def, double phase) {
        String raw = plugin.raw(race, key, def);
        return MM.deserialize(plugin.fmt(raw, race, target.getName(), phase));
    }

    /** Semua dust otomatis jadi gradasi warna (primary <-> secondary ras). */
    private void spawn(Particle p, double x, double y, double z, int count,
                       double dx, double dy, double dz, double speed, Object data) {
        if (p == Particle.DUST && data instanceof DustOptions dust) {
            Color to = dust.getColor().equals(race.primary) ? race.secondary : race.primary;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, count, dx, dy, dz, speed,
                    new Particle.DustTransition(dust.getColor(), to, dust.getSize()), true);
            return;
        }
        world.spawnParticle(p, x, y, z, count, dx, dy, dz, speed, data, true);
    }

    private void dustRing(double x, double y, double z, double r, int pts, DustOptions d) {
        for (int i = 0; i < pts; i++) {
            double a = i * 2 * Math.PI / pts;
            spawn(Particle.DUST, x + Math.cos(a) * r, y, z + Math.sin(a) * r, 1, 0, 0, 0, 0, d);
        }
    }

    private double groundTop(double x, double z, double fallback) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        int top = (int) Math.floor(by) + 2;
        for (int y = top; y >= (int) Math.floor(by) - 4; y--) {
            Block b = world.getBlockAt(xi, y, zi);
            if (!b.isPassable() && world.getBlockAt(xi, y + 1, zi).isPassable()) {
                return y + 1.05;
            }
        }
        return fallback;
    }

    private static double ease(double p) {
        p = Math.max(0, Math.min(1, p));
        return p * p * (3 - 2 * p);
    }

    private static double lerp(double a, double b, double p) {
        return a + (b - a) * p;
    }
}
