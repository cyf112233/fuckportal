package byd.cxkcxkckx.fuckportal;

import byd.cxkcxkckx.fuckportal.func.AbstractModule;
import byd.cxkcxkckx.fuckportal.fuckportal;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.ChatColor;
import top.mrxiaom.pluginbase.func.AutoRegister;

import java.util.*;

/**
 * 不限制形状的下界传送门模块
 * 逻辑：
 * - 监听打火石点火（BlockIgniteEvent, cause=FLINT_AND_STEEL）
 * - 以被点燃位置为起点，在两个可能的平面（Axis.X 和 Axis.Z）各做一次二维洪水填充（仅在该平面内的4连通）
 * - 内部为空气/火/已有传送门方块，边界必须是黑曜石或哭泣的黑曜石；若遇到其它方块或区域过大，视为无效
 * - 若某个平面填充成功，则将该连通区域全部替换为NETHER_PORTAL，并设置对应轴向
 */
@AutoRegister
public class IrregularPortalModule extends AbstractModule implements Listener {
    public IrregularPortalModule(fuckportal plugin) {
        super(plugin);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) return;
        if (!event.getPlayer().hasPermission("byd.fuckportal.create") && !event.getPlayer().isOp()) {
            return;
        }
        // 如果是玩家手持打火石造成的点火，让 onInteract 处理，避免重复询问
        if (event.getPlayer() != null) {
            try {
                org.bukkit.entity.Player p0 = event.getPlayer();
                if (p0.getInventory() != null && p0.getInventory().getItemInMainHand() != null && p0.getInventory().getItemInMainHand().getType() == Material.FLINT_AND_STEEL) {
                    return;
                }
            } catch (Throwable ignored) {}
        }
        Block target = event.getBlock(); // 将要被点燃为火的方块
        // 只在起点为空气/火/已有传送门时尝试（通常为空气）
        Material m0 = target.getType();
        if (!(m0 == Material.AIR || m0 == Material.FIRE || m0 == Material.NETHER_PORTAL)) return;

        int maxSize = Math.max(1, plugin.getConfig().getInt("max-portal-size", 2048));
        int perTick = Math.max(1, plugin.getConfig().getInt("flood-per-tick", 256));
        // 保留原版点火/点燃行为，同时异步尝试生成传送门
    new PortalFillTask(target, maxSize, perTick, event.getPlayer()).start();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() == EquipmentSlot.OFF_HAND) return; // 避免副手重复触发
        // 在onInteract方法开头添加
        if (!event.getPlayer().hasPermission("byd.fuckportal.create") && !event.getPlayer().isOp()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player == null) return;
        if (player.getInventory().getItemInMainHand() == null || player.getInventory().getItemInMainHand().getType() != Material.FLINT_AND_STEEL) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() == null) return;
        Block target = clicked.getRelative(event.getBlockFace());

        Material m0 = target.getType();
        if (!(m0 == Material.AIR || m0 == Material.FIRE || m0 == Material.NETHER_PORTAL)) return;

        int maxSize = Math.max(1, plugin.getConfig().getInt("max-portal-size", 2048));
        int perTick = Math.max(1, plugin.getConfig().getInt("flood-per-tick", 256));
        // 保留原版交互（点火/TNT等），同时异步尝试生成传送门
        new PortalFillTask(target, maxSize, perTick, player).start();
    }

    // pending confirmations: player UUID -> pending payment
    private static final Map<UUID, PendingPayment> pendingPayments = new HashMap<>();

    private static class PendingPayment {
        final Set<Block> interior;
        final Axis axis;
        final double cost;
        final BukkitRunnable timeoutTask;

        PendingPayment(Set<Block> interior, Axis axis, double cost, BukkitRunnable timeoutTask) {
            this.interior = interior;
            this.axis = axis;
            this.cost = cost;
            this.timeoutTask = timeoutTask;
        }
    }

    private class BFSState {
        final Axis axis;
        final int fixed;
        final World w;
        final Deque<Block> queue = new ArrayDeque<>();
        final Set<Long> visited = new HashSet<>();
        final Set<Block> interior = new HashSet<>();
        boolean valid = true;
        int processed = 0;

        BFSState(Block start, Axis axis) {
            this.axis = axis;
            this.w = start.getWorld();
            this.fixed = (axis == Axis.X) ? start.getX() : start.getZ();
            if (isFillable(start.getType())) {
                queue.add(start);
                visited.add(key(start));
                interior.add(start);
            } else {
                valid = false;
            }
        }

        void step(int budget, int maxSize) {
            while (budget > 0 && valid && !queue.isEmpty()) {
                Block b = queue.poll();
                processed++;
                if (processed > maxSize) {
                    valid = false;
                    break;
                }
                int x = b.getX(), y = b.getY(), z = b.getZ();
                Block[] neigh;
                if (axis == Axis.X) {
                    neigh = new Block[]{
                            w.getBlockAt(x, y + 1, z),
                            w.getBlockAt(x, y - 1, z),
                            w.getBlockAt(x, y, z + 1),
                            w.getBlockAt(x, y, z - 1)
                    };
                } else {
                    neigh = new Block[]{
                            w.getBlockAt(x, y + 1, z),
                            w.getBlockAt(x, y - 1, z),
                            w.getBlockAt(x + 1, y, z),
                            w.getBlockAt(x - 1, y, z)
                    };
                }
                for (Block nb : neigh) {
                    if ((axis == Axis.X && nb.getX() != fixed) || (axis == Axis.Z && nb.getZ() != fixed)) continue;
                    Material m = nb.getType();
                    if (isFillable(m)) {
                        long k = key(nb);
                        if (!visited.contains(k)) {
                            visited.add(k);
                            interior.add(nb);
                            queue.add(nb);
                        }
                    } else if (m == Material.OBSIDIAN || "CRYING_OBSIDIAN".equals(m.name())) {
                        // 边界OK
                    } else {
                        valid = false;
                    }
                }
                budget--;
            }
        }

        boolean done() {
            return !valid || queue.isEmpty();
        }
    }

    private class PortalFillTask extends BukkitRunnable {
    private final Block start;
        private final int maxSize;
        private final int perTick;
    private BFSState sx, sz;
    private final Player owner;

        PortalFillTask(Block start, int maxSize, int perTick, Player owner) {
            this.start = start;
            this.maxSize = maxSize;
            this.perTick = perTick;
            this.owner = owner;
        }

        void start() {
            this.runTaskTimer(plugin, 1L, 1L);
        }

        @Override
        public void run() {
            if (sx == null) sx = new BFSState(start, Axis.X);
            if (sz == null) sz = new BFSState(start, Axis.Z);

            int left = perTick;
            while (left > 0) {
                boolean progressed = false;
                if (sx.valid && !sx.queue.isEmpty()) {
                    sx.step(1, maxSize);
                    left--;
                    progressed = true;
                }
                if (left > 0 && sz.valid && !sz.queue.isEmpty()) {
                    sz.step(1, maxSize);
                    left--;
                    progressed = true;
                }
                if (!progressed) break;
            }

            if ((sx.done() || !sx.valid) && (sz.done() || !sz.valid)) {
                this.cancel();
                Axis chosenAxis = null;
                Set<Block> interior = null;
                if (sx.valid && !sz.valid) {
                    chosenAxis = Axis.X; interior = sx.interior;
                } else if (sz.valid && !sx.valid) {
                    chosenAxis = Axis.Z; interior = sz.interior;
                } else if (sx.valid && sz.valid) {
                    if (sx.interior.size() <= sz.interior.size()) {
                        chosenAxis = Axis.X; interior = sx.interior;
                    } else {
                        chosenAxis = Axis.Z; interior = sz.interior;
                    }
                }
                if (chosenAxis != null && interior != null && !interior.isEmpty()) {
                    // Payment / confirmation flow
                    Player p = owner;
                    if (p != null && p.hasPermission("byd.fuckportal.free")) {
                        placePortal(interior, chosenAxis);
                        try { start.getWorld().playSound(start.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f); } catch (Throwable ignored) {}
                    } else if (p != null) {
                        // compute cost
                        double per = plugin.getConfig().getDouble("portal-cost-per-block", 10.0);
                        double cost = per * interior.size();
                        // Check economy available
                        Economy econ = null;
                        try {
                            org.bukkit.plugin.RegisteredServiceProvider<Economy> reg = plugin.getServer().getServicesManager().getRegistration(Economy.class);
                            if (reg != null) econ = reg.getProvider();
                        } catch (Throwable ignored) {}
                        if (econ == null) {
                            // no economy plugin -> silently place portal
                            placePortal(interior, chosenAxis);
                            try { start.getWorld().playSound(start.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f); } catch (Throwable ignored) {}
                        } else {
                            // put pending and ask for confirmation
                            p.sendMessage(ChatColor.LIGHT_PURPLE + "\n\n==========[" + ChatColor.YELLOW + "FuckPortal" + ChatColor.LIGHT_PURPLE + "]===========\n" + ChatColor.LIGHT_PURPLE + "===============================\n" + ChatColor.YELLOW + "检测到传送门将使用 " + ChatColor.LIGHT_PURPLE + interior.size() + ChatColor.YELLOW + " 个方块，费用: " + ChatColor.LIGHT_PURPLE + cost + ChatColor.YELLOW + "。\n" + ChatColor.YELLOW + "输入 /ccb 在 " + plugin.getConfig().getInt("portal-confirm-timeout", 10) + " 秒内确认扣费并生成传送门。\n" + ChatColor.LIGHT_PURPLE + "===============================\n" + ChatColor.YELLOW + "如果你制作的是原版传送门，请忽略此消息。\n" + ChatColor.YELLOW + "原版传送门会自动激活\n" + ChatColor.LIGHT_PURPLE + "===============================");
                            UUID uid = p.getUniqueId();
                            // cancel existing pending if any
                            PendingPayment old = pendingPayments.remove(uid);
                            if (old != null) {
                                try { old.timeoutTask.cancel(); } catch (Throwable ignored) {}
                            }
                            int timeoutSec = Math.max(1, plugin.getConfig().getInt("portal-confirm-timeout", 10));
                            BukkitRunnable task = new BukkitRunnable() {
                                @Override
                                public void run() {
                                    pendingPayments.remove(uid);
                                    p.sendMessage(ChatColor.YELLOW + "[FuckPortal] 传送门付费确认已超时。");
                                }
                            };
                            task.runTaskLater(plugin, timeoutSec * 20L);
                            pendingPayments.put(uid, new PendingPayment(new HashSet<>(interior), chosenAxis, cost, task));
                        }
                    } else {
                        // no player reference -> just place
                        placePortal(interior, chosenAxis);
                        try { start.getWorld().playSound(start.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f); } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    // Called by /ccb command
    public static void confirmPendingByPlayer(Player p) {
        PendingPayment pending = pendingPayments.remove(p.getUniqueId());
        if (pending == null) {
            p.sendMessage(ChatColor.YELLOW + "[FuckPortal] 你没有待确认的传送门生成请求。");
            return;
        }
        // cancel timeout
        try { pending.timeoutTask.cancel(); } catch (Throwable ignored) {}
        // economy check & withdraw
        Economy econ = null;
        try {
            org.bukkit.plugin.RegisteredServiceProvider<Economy> reg = fuckportal.getInstance().getServer().getServicesManager().getRegistration(Economy.class);
            if (reg != null) econ = reg.getProvider();
        } catch (Throwable ignored) {}
        if (econ == null) {
            // economy not available -> silently place portal for pending
            try {
                World w = p.getWorld();
                for (Block b : pending.interior) {
                    BlockData data = Bukkit.createBlockData(Material.NETHER_PORTAL);
                    if (data instanceof Orientable) {
                        Axis blockAxis = (pending.axis == Axis.X ? Axis.Z : Axis.X);
                        ((Orientable) data).setAxis(blockAxis);
                    }
                    b.setBlockData(data, false);
                }
                try { w.playSound(p.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f); } catch (Throwable ignored) {}
            } catch (Throwable ex) {
                // silently ignore
            }
            return;
        }
        double cost = pending.cost;
    org.bukkit.OfflinePlayer offline = fuckportal.getInstance().getServer().getOfflinePlayer(p.getUniqueId());
    EconomyResponse resp = econ.withdrawPlayer(offline, cost);
        if (resp.transactionSuccess()) {
            // place portal
            try {
                World w = p.getWorld();
                for (Block b : pending.interior) {
                    BlockData data = Bukkit.createBlockData(Material.NETHER_PORTAL);
                    if (data instanceof Orientable) {
                        Axis blockAxis = (pending.axis == Axis.X ? Axis.Z : Axis.X);
                        ((Orientable) data).setAxis(blockAxis);
                    }
                    b.setBlockData(data, false);
                }
                p.sendMessage(ChatColor.YELLOW + "[FuckPortal] 已扣款 " + cost + " 并生成传送门。");
                try { w.playSound(p.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f); } catch (Throwable ignored) {}
            } catch (Throwable ex) {
                p.sendMessage(ChatColor.YELLOW + "[FuckPortal] 生成传送门时出错: " + ex.getMessage());
            }
        } else {
            p.sendMessage(ChatColor.YELLOW + "[FuckPortal] 扣费失败: " + resp.errorMessage);
        }
    }

    static class FillResult {
        final boolean valid;
        final Set<Block> interior;
        FillResult(boolean valid, Set<Block> interior) {
            this.valid = valid;
            this.interior = interior;
        }
    }

    /**
     * 在给定轴向的平面内做二维洪水填充。
     * Axis.X 表示固定 X（平面为 Y-Z），只在 (y±1, z±1) 方向扩张；
     * Axis.Z 表示固定 Z（平面为 Y-X），只在 (y±1, x±1) 方向扩张。
     *
     * 规则：
     * - 可填充：AIR / FIRE / NETHER_PORTAL
     * - 合法边界：OBSIDIAN / CRYING_OBSIDIAN
     * - 触碰到除 OBSIDIAN 外的其它方块作为边界，或区域超过配置上限，则判定为无效
     */
    private FillResult floodFillInPlane(Block start, Axis axis, int maxSize) {
        World w = start.getWorld();
        int fixed = (axis == Axis.X) ? start.getX() : start.getZ();

        Set<Block> interior = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        if (!isFillable(start.getType())) {
            return new FillResult(false, Collections.emptySet());
        }
        queue.add(start);
        visited.add(key(start));
        interior.add(start);

        boolean valid = true;
        int processed = 0;

        while (!queue.isEmpty()) {
            Block b = queue.poll();
            processed++;
            if (processed > maxSize) {
                valid = false;
                break;
            }

            int x = b.getX(), y = b.getY(), z = b.getZ();

            // 四邻域（仅在该平面内）
            Block[] neigh;
            if (axis == Axis.X) {
                // 固定 X；在 Y,Z 平面扩张
                neigh = new Block[] {
                        w.getBlockAt(x, y + 1, z),
                        w.getBlockAt(x, y - 1, z),
                        w.getBlockAt(x, y, z + 1),
                        w.getBlockAt(x, y, z - 1)
                };
            } else {
                // 固定 Z；在 Y,X 平面扩张
                neigh = new Block[] {
                        w.getBlockAt(x, y + 1, z),
                        w.getBlockAt(x, y - 1, z),
                        w.getBlockAt(x + 1, y, z),
                        w.getBlockAt(x - 1, y, z)
                };
            }

            for (Block nb : neigh) {
                // 保持在同一平面
                if ((axis == Axis.X && nb.getX() != fixed) || (axis == Axis.Z && nb.getZ() != fixed)) continue;

                Material m = nb.getType();
                if (isFillable(m)) {
                    long k = key(nb);
                    if (!visited.contains(k)) {
                        visited.add(k);
                        interior.add(nb);
                        queue.add(nb);
                    }
                } else if (m == Material.OBSIDIAN || "CRYING_OBSIDIAN".equals(m.name())) {
                    // 合法边界，忽略
                } else {
                    // 非法边界，判定失败（例如石头、玻璃等）
                    valid = false;
                }
            }
        }

        if (!valid || interior.isEmpty()) {
            return new FillResult(false, Collections.emptySet());
        }
        return new FillResult(true, interior);
    }

    private boolean isFillable(Material m) {
        return m == Material.AIR || m == Material.FIRE || m == Material.NETHER_PORTAL;
    }

    private long key(Block b) {
        // 压缩坐标到一个 long（避免频繁拼接字符串，提升性能）
        // 21 位给每个坐标，足够典型世界范围；简化实现（不处理超大坐标）
        long x = (long) b.getX() & 0x1FFFFF;
        long y = (long) b.getY() & 0x1FFFFF;
        long z = (long) b.getZ() & 0x1FFFFF;
        return (x << 42) | (y << 21) | z;
    }

    private void placePortal(Set<Block> interior, Axis axis) {
        for (Block b : interior) {
            BlockData data = Bukkit.createBlockData(Material.NETHER_PORTAL);
            if (data instanceof Orientable) {
                Axis blockAxis = (axis == Axis.X ? Axis.Z : Axis.X);
                ((Orientable) data).setAxis(blockAxis);
            }
            b.setBlockData(data, false);
        }
    }
}
