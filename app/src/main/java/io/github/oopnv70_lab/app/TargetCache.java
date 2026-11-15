package io.github.oopnv70_lab.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 定位结果的本地持久化。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么不用 SharedPreferences
 *   DexKit 官方文档明确写了：SharedPreferences 不适合存序列化的
 *   批量数据，推荐 MMKV / DataStore / SQLite / 文件型存储。
 *   我们的数据量很小（6 个目标 × 3 个字段），但形态是「按版本
 *   分组的键值对」，用文件最直接，也不引入任何新依赖。
 *
 * 存在哪、为什么
 *   存 /data/local_ota/targets.conf，和开关文件放同一个目录。
 *   这个目录的 SELinux 标签 local_ota_file 恰好对 OUC 进程
 *   （platform_app_all 域）开放读写，而我们的 hook 就跑在 OUC 进程里，
 *   所以能直接读写，不需要 root、不需要额外授权。
 *   这是本模块已经验证过的通道，复用它是成本最低的选择。
 *
 * 文件格式（一行一条，故意做成能直接 cat 出来看的样子）
 *   # 注释行
 *   <appTag>|<targetId>|<className>|<methodName>|<paramTypes逗号分隔>
 *   例：
 *   180004120|check_sum|l5.b|p|
 *
 * appTag 放在第一列而不是分文件，是因为：
 *   · 一个文件能同时保留多个版本的缓存，降级回旧版本时能直接复用
 *   · 单文件读写比多文件简单，也不会有目录残留
 *   · 文件很小（十几行），全量重写代价可以忽略
 *
 * 写失败怎么办
 *   缓存只是加速手段，不是正确性前提。读不到就当成没缓存，
 *   写失败就下次再写。任何异常都不许冒泡影响 hook 挂载——
 *   这是硬要求：缓存坏了不能让功能一起坏。
 */
final class TargetCache {

    private static final String TAG = "HonorLocalOTA";

    private final File file;

    /** 一次性把文件读进内存，避免每个目标都做一次 IO。 */
    private Map<String, String[]> snapshot;
    private boolean loaded;

    TargetCache(File file) {
        this.file = file;
    }

    /**
     * 取一条缓存。
     *
     * @return {className, methodName, paramTypes}；没有则 null
     */
    String[] get(String appTag, String targetId) {
        ensureLoaded();
        return snapshot.get(key(appTag, targetId));
    }

    /** 写一条缓存。失败只记日志，不影响调用方。 */
    void put(String appTag, String targetId, String className,
             String methodName, String paramTypes) {
        ensureLoaded();
        snapshot.put(key(appTag, targetId),
                new String[]{className, methodName, paramTypes == null ? "" : paramTypes});
        flush();
    }

    /** 清掉某个版本的全部缓存。调试或手动重扫时用。 */
    void clearVersion(String appTag) {
        ensureLoaded();
        String prefix = appTag + "|";
        snapshot.keySet().removeIf(k -> k.startsWith(prefix));
        flush();
    }

    /** 当前缓存覆盖了哪些版本，写日志用。 */
    String[] versions() {
        ensureLoaded();
        java.util.Set<String> vs = new java.util.LinkedHashSet<>();
        for (String k : snapshot.keySet()) {
            int i = k.indexOf('|');
            if (i > 0) {
                vs.add(k.substring(0, i));
            }
        }
        return vs.toArray(new String[0]);
    }

    // ──────────────────────────────────────────────────────────────

    private static String key(String appTag, String targetId) {
        return appTag + "|" + targetId;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        snapshot = new HashMap<>();
        if (file == null || !file.isFile() || !file.canRead()) {
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // 最多 5 段，paramTypes 里可能含逗号所以只切前 4 个分隔点
                String[] p = line.split("\\|", 5);
                if (p.length < 4) {
                    continue;
                }
                String k = key(p[0], p[1]);
                snapshot.put(k, new String[]{p[2], p[3], p.length > 4 ? p[4] : ""});
            }
        } catch (Throwable t) {
            Log.w(TAG, "读定位缓存失败（忽略，按无缓存处理）: " + t);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 全量重写。先写临时文件再改名，避免写到一半断电留下半个文件。 */
    private void flush() {
        if (file == null) {
            return;
        }
        File tmp = null;
        FileOutputStream out = null;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            tmp = new File(file.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            StringBuilder sb = new StringBuilder();
            sb.append("# 软件更新增强：动态定位结果缓存。删掉这个文件会触发重新扫描。\n");
            sb.append("# 格式：<版本标识>|<目标id>|<类名>|<方法名>|<参数类型>\n");
            for (Map.Entry<String, String[]> e : snapshot.entrySet()) {
                String[] v = e.getValue();
                sb.append(e.getKey()).append('|')
                        .append(v[0]).append('|')
                        .append(v[1]).append('|')
                        .append(v[2]).append('\n');
            }
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                // 某些实现下 rename 覆盖已存在文件会失败，退化为直接写。
                try (FileOutputStream direct = new FileOutputStream(file)) {
                    direct.write(sb.toString().getBytes("UTF-8"));
                    direct.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "写定位缓存失败（忽略，下次再试）: " + t);
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}