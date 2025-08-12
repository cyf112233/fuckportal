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
import top.mrxiaom.pluginbase.func.AutoRegister;

import java.util.*;

/**
 * 不限制形状的下界传送门模块
 * 逻辑：
 * - 监听打火石点火（BlockIgniteEvent, cause=FLINT_AND_STEEL）
 * - 以被点燃位置为起点，在两个可能的平面（Axis.X 和 Axis.Z）各做一次二维洪水填充（仅在该平面内的4连通）
 * - 内部为空气/火/已有传送门方块，边界必须是黑曜石；若遇到其它方块或区域过大，视为无效
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

        Block target = event.getBlock(); // 将要被点燃为火的方块
        // 只在起点为空气/火/已有传送门时尝试（通常为空气）
        Material m0 = target.getType();
        if (!(m0 == Material.AIR || m0 == Material.FIRE || m0 == Material.NETHER_PORTAL)) return;

        int maxSize = Math.max(1, plugin.getConfig().getInt("max-portal-size", 2048));
        int perTick = Math.max(1, plugin.getConfig().getInt("flood-per-tick", 256));
        // 取消默认点火，改为启动渐进式填充任务，避免主线程长阻塞
        event.setCancelled(true);
        new PortalFillTask(target, maxSize, perTick).start();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() == EquipmentSlot.OFF_HAND) return; // 避免副手重复触发
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
        // 取消原版交互，改为启动渐进式填充任务
        event.setCancelled(true);
        new PortalFillTask(target, maxSize, perTick).start();
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
                    } else if (m == Material.OBSIDIAN) {
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

        PortalFillTask(Block start, int maxSize, int perTick) {
            this.start = start;
            this.maxSize = maxSize;
            this.perTick = perTick;
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
                    placePortal(interior, chosenAxis);
                    try {
                        World w = start.getWorld();
                        w.playSound(start.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1f, 1f);
                    } catch (Throwable ignored) {}
                }
            }
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
     * - 合法边界：OBSIDIAN
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
                } else if (m == Material.OBSIDIAN) {
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
