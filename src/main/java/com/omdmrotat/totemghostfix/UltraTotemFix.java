package com.omdmrotat.totemghostfix;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UltraTotemFix v3.0
 *
 * AMAÇ: Sadece "ghost totem"i engellemek. Auto-totem DEĞİLDİR.
 *
 * Ghost totem nedir: Oyuncunun ekranında totem elinde görünür, ama "totemi elime aldım"
 * paketi sunucuya henüz ulaşmamıştır / işlenmemiştir (ping, paket gecikmesi, aynı tick'te sıralama).
 * Ölümcül hasar sunucuda totemsiz yakalanır ve oyuncu ölür.
 *
 * Çözüm:
 *  1) Ölümcül hasar geldiğinde elde (ana el / yan el) totem VARSA hiçbir şeye karışmaz, vanilla patlatır.
 *  2) Elde totem YOK ama envanterde / imlecte totem VARSA (yani paket yoldadır olabilir) hasar
 *     iptal edilip oyuncunun pingine göre kısa süre bekletilir.
 *       - Bu sürede totem ele geçerse hasar AYNEN tekrar uygulanır ve totemi vanilla kendisi tüketip patlatır.
 *       - Gelmezse hasar yine AYNEN uygulanır, oyuncu normal şekilde ölür (envanterdeki totem kurtarmaz).
 *  3) Hiç totem yoksa hiçbir gecikme olmaz, normal ölüm.
 *
 * Ölüm iptal edilmez, oyuncu öldürülüp geri diriltilmez, totem envanterden otomatik alınıp ele konmaz.
 */
public final class UltraTotemFix extends JavaPlugin implements Listener {

    /** Bekleme sırasında sıraya alınabilecek en fazla hasar sayısı (güvenlik sınırı). */
    private static final int MAX_QUEUED_HITS = 24;
    /** Ne olursa olsun bekleme bu kadar tick'i (2 sn) geçmez. */
    private static final int HARD_MAX_TICKS = 40;

    /** Ertelenen tek bir hasar. */
    private static final class Hit {
        final double damage;
        final DamageSource source;

        Hit(double damage, DamageSource source) {
            this.damage = damage;
            this.source = source;
        }
    }

    /** Bir oyuncu için devam eden "totem paketini bekle" durumu. */
    private static final class Deferred {
        final long deadlineNanos;
        final List<Hit> hits = new ArrayList<>(4);
        int ticks;
        BukkitTask task;

        Deferred(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Map<UUID, Deferred> deferred = new HashMap<>();
    /** Eklentinin kendi tekrar uyguladığı hasarlar tekrar ertelenmesin diye. */
    private final Set<UUID> replaying = new HashSet<>();

    private double pingMultiplier;
    private long extraWaitMs;
    private long minWaitMs;
    private long maxWaitMs;
    private int minWaitTicks;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("UltraTotemFix v3.0 aktif (sadece ghost totem engeli, auto-totem yok).");
    }

    @Override
    public void onDisable() {
        for (Deferred d : deferred.values()) {
            if (d.task != null) {
                d.task.cancel();
            }
        }
        deferred.clear();
        replaying.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        pingMultiplier = Math.max(0.0D, cfg.getDouble("ping-multiplier", 1.0D));
        extraWaitMs = Math.max(0L, cfg.getLong("extra-wait-ms", 100L));
        minWaitMs = Math.max(0L, cfg.getLong("min-wait-ms", 100L));
        maxWaitMs = Math.max(minWaitMs, cfg.getLong("max-wait-ms", 600L));
        minWaitTicks = Math.max(1, cfg.getInt("min-wait-ticks", 2));
        debug = cfg.getBoolean("debug", false);
    }

    // ------------------------------------------------------------------ olaylar

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        UUID id = player.getUniqueId();
        if (replaying.contains(id)) {
            return; // kendi tekrar uyguladığımız hasar
        }
        if (bypassesTotem(event.getCause())) {
            return; // void / kill: totem zaten çalışmaz
        }

        // Bekleme sürerken gelen her hasar sıraya girer ve sırası bozulmadan tekrar uygulanır.
        Deferred state = deferred.get(id);
        if (state != null) {
            if (state.hits.size() < MAX_QUEUED_HITS) {
                state.hits.add(new Hit(event.getDamage(), event.getDamageSource()));
                event.setCancelled(true);
            }
            return;
        }

        // getFinalDamage() absorption dahil, sağlığa inecek gerçek hasardır (absorption tekrar düşülmez).
        float health = (float) player.getHealth();
        float finalDamage = (float) event.getFinalDamage();
        boolean lethal = health - finalDamage <= 0.0F;
        if (!lethal) {
            return;
        }

        if (hasHandTotem(player)) {
            return; // totem sunucuda elde: vanilla zaten patlatır
        }
        if (!hasSpareTotem(player)) {
            return; // hiç totem yok: gerçek ölüm, gecikme yok
        }

        long waitMs = computeWaitMillis(player);
        Deferred created = new Deferred(System.nanoTime() + waitMs * 1_000_000L);
        created.hits.add(new Hit(event.getDamage(), event.getDamageSource()));
        deferred.put(id, created);
        created.task = getServer().getScheduler().runTaskTimer(this, () -> tick(id, created), 1L, 1L);
        event.setCancelled(true);

        if (debug) {
            getLogger().info(player.getName() + ": ölümcül hasar ertelendi, totem paketi bekleniyor (" + waitMs + " ms).");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        drop(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ bekleme mantığı

    private void tick(UUID id, Deferred state) {
        if (deferred.get(id) != state) {
            if (state.task != null) {
                state.task.cancel();
            }
            return;
        }

        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline() || player.isDead()) {
            drop(id);
            return;
        }

        state.ticks++;
        boolean totemArrived = hasHandTotem(player);
        // Süre dolsa bile en az minWaitTicks tick geçmeli: TPS düşükse paketler önce işlensin.
        boolean timeUp = state.ticks >= HARD_MAX_TICKS
                || (state.ticks >= minWaitTicks && System.nanoTime() - state.deadlineNanos >= 0L);

        if (totemArrived || timeUp) {
            finish(player, state, totemArrived);
        }
    }

    private void finish(Player player, Deferred state, boolean totemArrived) {
        UUID id = player.getUniqueId();
        deferred.remove(id);
        if (state.task != null) {
            state.task.cancel();
        }

        if (debug) {
            getLogger().info(player.getName() + ": " + (totemArrived
                    ? "totem ele geçti, hasar geri veriliyor (totemi vanilla patlatacak)."
                    : "totem gelmedi, hasar normal şekilde uygulanıyor."));
        }

        replaying.add(id);
        try {
            boolean first = true;
            for (Hit hit : state.hits) {
                if (player.isDead() || !player.isOnline()) {
                    break;
                }
                if (first) {
                    // Önceki vuruşların hasar-bağışıklığı yüzünden ertelenen hasarın yutulmasını önler.
                    player.setNoDamageTicks(0);
                    first = false;
                }
                replay(player, hit);
            }
        } finally {
            replaying.remove(id);
        }
    }

    private void replay(Player player, Hit hit) {
        try {
            player.damage(hit.damage, hit.source);
        } catch (Exception ex) {
            getLogger().warning("Hasar kaynağıyla uygulanamadı (" + ex.getMessage() + "), genel hasar deneniyor.");
            try {
                player.damage(hit.damage);
            } catch (Exception ignored) {
                // yapılacak başka bir şey yok
            }
        }
    }

    private void drop(UUID id) {
        Deferred state = deferred.remove(id);
        if (state != null && state.task != null) {
            state.task.cancel();
        }
    }

    private long computeWaitMillis(Player player) {
        int ping;
        try {
            ping = Math.max(0, player.getPing());
        } catch (Exception ex) {
            ping = 0;
        }
        double wait = ping * pingMultiplier + extraWaitMs;
        return (long) Math.min((double) maxWaitMs, Math.max((double) minWaitMs, wait));
    }

    // ------------------------------------------------------------------ yardımcılar

    /** Vanilla'da totemin çalışmadığı hasar türleri. */
    private static boolean bypassesTotem(DamageCause cause) {
        return cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE;
    }

    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING && item.getAmount() > 0;
    }

    /** Vanilla gibi: sadece ana el ve yan el sayılır. */
    private static boolean hasHandTotem(Player player) {
        PlayerInventory inv = player.getInventory();
        return isTotem(inv.getItemInMainHand()) || isTotem(inv.getItemInOffHand());
    }

    /** Elde değil ama envanterde / imlecte totem var mı (yoldaki paket ihtimali). */
    private static boolean hasSpareTotem(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isTotem(item)) {
                return true;
            }
        }
        return isTotem(player.getItemOnCursor());
    }
}
