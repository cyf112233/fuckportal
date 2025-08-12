package byd.cxkcxkckx.fuckportal;
        
import top.mrxiaom.pluginbase.BukkitPlugin;

public class fuckportal extends BukkitPlugin {
    public static fuckportal getInstance() {
        return (fuckportal) BukkitPlugin.getInstance();
    }

    public fuckportal() {
        super(options()
                .bungee(false)
                .adventure(false)
                .database(false)
                .reconnectDatabaseWhenReloadConfig(false)
                .scanIgnore("top.mrxiaom.example.libs")
        );
        // this.scheduler = new FoliaLibScheduler(this);
    }

    @Override
    protected void afterEnable() {
        // 确保把默认配置写出到插件数据目录（包含 max-portal-size）
        saveDefaultConfig();
        // 显式注册监听器，避免仅依赖注解扫描导致未注册
        getServer().getPluginManager().registerEvents(new IrregularPortalModule(this), this);
        getLogger().info("fuckportal 加载完毕");
    }
}
