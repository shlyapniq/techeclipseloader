package com.shlyapniq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Управляет проверкой и установкой обновлений модпака.
 * Все тяжёлые операции выполняются в фоновом потоке (не в render thread).
 *
 * Как работает:
 * 1. Читает локальную версию из techeclipse-version.json
 * 2. Скачивает manifest.json с сервера
 * 3. Сравнивает версии
 * 4. Если есть обновление — скачивает zip, распаковывает файлы
 * 5. Обновляет techeclipse-managed-files.json (список установленных файлов)
 * 6. Обновляет techeclipse-version.json (текущая версия)
 */
public class UpdateManager {

    // =====================================================================
    // НАСТРОЙКИ — ЗАМЕНИ URL НА СВОЙ СЕРВЕР!
    // manifest.json должен лежать по этому адресу
    // =====================================================================
    private static final String MANIFEST_URL = "https://www.dropbox.com/scl/fi/h87hit85hiqwpes5qejb8/version.json?rlkey=oygshbqvvuholfvtjl6epmz8y&st=6uufzta1&dl=1";

    // Имя bootstrap мода — файлы с этим именем в архиве будут пропущены,
    // чтобы мод не пытался обновить сам себя
    private static final String BOOTSTRAP_MOD_NAME = "techeclipse-loader";

    // Запрещённые папки — эти НИКОГДА не трогаем (сохранения, скриншоты и т.д.)
    private static final Set<String> BLOCKED_DIRS = Set.of("saves/", "screenshots/", "logs/", "crash-reports/");

    // Запрещённые файлы в корне — пользовательские настройки, которые нельзя перезаписывать
    private static final Set<String> BLOCKED_FILES = Set.of("options.txt", "servers.dat", "realms_persistence.json");

    // Таймаут для HTTP-запросов
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    // =====================================================================
    // СОСТОЯНИЕ (volatile — безопасно читать из render thread)
    // =====================================================================
    private volatile String status = "Initializing...";
    private volatile boolean finished = false;
    private volatile boolean updateInstalled = false;
    private volatile float progress = -1f; // -1 = не показывать, 0..1 = прогресс
    private volatile boolean upToDate = false;
    private static volatile String news = null;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path gameDir;
    private final HttpClient httpClient;

    /**
     * @param gameDir путь к папке игры (.minecraft)
     */
    public UpdateManager(Path gameDir) {
        this.gameDir = gameDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // Геттеры для UpdateScreen (вызываются из render thread)
    public String getStatus() { return status; }
    public boolean isFinished() { return finished; }
    public boolean isUpdateInstalled() { return updateInstalled; }
    public float getProgress() { return progress; }
    public boolean isUpToDate() { return upToDate; }
    public static String getNews() { return news; }

    // =================================================================
    // ГЛАВНЫЙ МЕТОД — вызывается из фонового потока
    // =================================================================

    /**
     * Проверяет наличие обновлений и устанавливает их.
     * Этот метод БЛОКИРУЮЩИЙ — вызывай только из фонового потока!
     */
    public void checkAndUpdate() {
        try {
            // Шаг 1: Читаем локальную версию
            status = "Проверка локальной версии...";
            String localVersion = loadLocalVersion();
            TechEclipseLoader.LOGGER.info("Local modpack version: {}", localVersion);

            // Шаг 2: Скачиваем manifest.json с сервера
            status = "Проверка обновлений...";
            JsonObject manifest = downloadManifest();
            String serverVersion = manifest.get("version").getAsString();
            String downloadUrl = manifest.get("downloadUrl").getAsString();
            if (manifest.has("news")) {
                news = manifest.get("news").getAsString();
            }
            TechEclipseLoader.LOGGER.info("Server modpack version: {}", serverVersion);

            // Шаг 3: Сравниваем версии
            if (serverVersion.equals(localVersion)) {
                status = "Сборка актуальна! (v" + localVersion + ")";
                upToDate = true;
                finished = true;
                return; // Обновление не требуется
            }
            
            // Шаг 4: Скачиваем zip-архив с обновлением
            status = "Скачивание v" + serverVersion + "...";
            Path zipFile = downloadFile(downloadUrl);
            TechEclipseLoader.LOGGER.info("Update downloaded: {}", zipFile);

            // Шаг 5: Удаляем старые файлы (из предыдущего обновления)
            status = "Удаление старых файлов...";
            removeOldManagedFiles();

            // Шаг 6: Распаковываем новые файлы
            status = "Установка файлов...";
            List<String> installedFiles = extractZip(zipFile);
            TechEclipseLoader.LOGGER.info("Installed {} files", installedFiles.size());

            // Шаг 7: Сохраняем метаданные
            saveManagedFiles(installedFiles);
            saveLocalVersion(serverVersion);

            // Шаг 8: Удаляем временный zip
            Files.deleteIfExists(zipFile);

            status = "Сборка обновлена до v" + serverVersion + "! Перезапустите игру.";
            updateInstalled = true;
            finished = true;

        } catch (Exception e) {
            TechEclipseLoader.LOGGER.error("Update check failed!", e);
            status = "Ошибка: " + e.getMessage();
            finished = true;
        }
    }

    // =================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =================================================================

    /**
     * Читает локальную версию модпака из файла techeclipse-version.json.
     * Если файла нет (первый запуск) — возвращает "0.0.0".
     *
     * Формат файла:
     * { "version": "1.0.0" }
     */
    private String loadLocalVersion() {
        Path path = gameDir.resolve("techeclipse-version.json");
        if (!Files.exists(path)) {
            return "0.0.0"; // Первый запуск
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            return json.has("version") ? json.get("version").getAsString() : "0.0.0";
        } catch (Exception e) {
            TechEclipseLoader.LOGGER.warn("Can't read version file, treating as first launch", e);
            return "0.0.0";
        }
    }   

    public String getLocalVersion() {
        return loadLocalVersion();
    }

    /**
     * Скачивает manifest.json с сервера.
     *
     * Ожидаемый формат manifest.json:
     * {
     *   "version": "1.0.1",
     *   "downloadUrl": "https://example.com/modpack/update-1.0.1.zip"
     * }
     */
    private JsonObject downloadManifest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MANIFEST_URL))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Server returned HTTP " + response.statusCode());
        }

        String body = response.body();
        TechEclipseLoader.LOGGER.info("Manifest response: {}", body);

        return gson.fromJson(body, JsonObject.class);
    }

    /**
     * Скачивает файл по URL и сохраняет как временный файл в папке игры.
     * Обновляет progress (0..1) по мере скачивания.
     */
    private Path downloadFile(String url) throws Exception {
        Path tempFile = gameDir.resolve("techeclipse-update.zip.tmp");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }

        // Общий размер файла (из заголовка Content-Length, если есть)
        long totalBytes = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);

        progress = 0f;
        long downloadedBytes = 0;
        long startTime = System.currentTimeMillis();
        byte[] buffer = new byte[8192];

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(tempFile)) {

            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;

                // Обновляем прогресс и статус
                if (totalBytes > 0) {
                    progress = (float) downloadedBytes / totalBytes;
                    String dl = formatBytes(downloadedBytes);
                    String total = formatBytes(totalBytes);

                    // Считаем оставшееся время
                    long elapsed = System.currentTimeMillis() - startTime;
                    String eta = "";
                    if (elapsed > 1000 && downloadedBytes > 0) {
                        double speed = (double) downloadedBytes / elapsed; // bytes/ms
                        long remaining = (long) ((totalBytes - downloadedBytes) / speed);
                        eta = " — " + formatTime(remaining);
                    }

                    status = "Скачивание... " + dl + " / " + total + eta;
                }
            }
        }

        progress = -1f; // Скрываем полосу после скачивания
        return tempFile;
    }

    /**
     * Форматирует байты в читаемый вид (KB, MB, GB).
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Форматирует миллисекунды в читаемое время (мин, сек).
     */
    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        if (seconds < 5) return "< 5 сек.";
        if (seconds < 60) return seconds + " сек.";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + " мин. " + seconds + " сек.";
    }

    /**
     * Удаляет файлы, которые были установлены предыдущим обновлением.
     * Список берётся из techeclipse-managed-files.json.
     *
     * Если файл заблокирован (например, .jar мода загружен JVM на Windows),
     * ошибка игнорируется — новая версия файла перезапишет старую при извлечении.
     */
    private void removeOldManagedFiles() {
        Path path = gameDir.resolve("techeclipse-managed-files.json");
        if (!Files.exists(path)) {
            return; // Первая установка — удалять нечего
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            JsonArray files = json.getAsJsonArray("files");

            for (int i = 0; i < files.size(); i++) {
                String relativePath = files.get(i).getAsString();
                Path filePath = gameDir.resolve(relativePath);

                try {
                    if (Files.deleteIfExists(filePath)) {
                        TechEclipseLoader.LOGGER.info("Deleted old file: {}", relativePath);
                    }
                } catch (IOException e) {
                    // На Windows .jar файлы модов могут быть заблокированы JVM.
                    // Это нормально — новая версия перезапишет при извлечении.
                    TechEclipseLoader.LOGGER.warn("Can't delete (file may be locked): {}", relativePath);
                }
            }
        } catch (Exception e) {
            TechEclipseLoader.LOGGER.warn("Can't read managed files list", e);
        }
    }

    /**
     * Распаковывает zip-архив в папку игры.
     *
     * Безопасность:
     * - Разрешены ТОЛЬКО файлы в папках mods/ и config/
     * - Файлы bootstrap мода (techeclipse-loader) пропускаются
     * - Защита от zip path traversal атаки (../)
     * - Существующие файлы перезаписываются
     *
     * @return список относительных путей установленных файлов
     */
    private List<String> extractZip(Path zipFile) throws IOException {
        List<String> installed = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Нормализуем разделители пути
                String name = entry.getName().replace("\\", "/");

                // Пропускаем папки (нас интересуют только файлы)
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // Проверка: файл НЕ в запрещённой папке и не запрещённый файл?
                boolean blocked = false;
                for (String dir : BLOCKED_DIRS) {
                    if (name.startsWith(dir)) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked && BLOCKED_FILES.contains(name)) {
                    blocked = true;
                }
                if (blocked) {
                    TechEclipseLoader.LOGGER.warn("Skipped (blocked): {}", name);
                    zis.closeEntry();
                    continue;
                }

                // Проверка: это не bootstrap мод?
                if (name.toLowerCase().contains(BOOTSTRAP_MOD_NAME)) {
                    TechEclipseLoader.LOGGER.info("Skipped (bootstrap mod): {}", name);
                    zis.closeEntry();
                    continue;
                }

                // Защита от path traversal: убеждаемся что путь не выходит за gameDir
                Path target = gameDir.resolve(name).normalize();
                if (!target.startsWith(gameDir)) {
                    TechEclipseLoader.LOGGER.warn("Skipped (path traversal attempt): {}", name);
                    zis.closeEntry();
                    continue;
                }

                // Создаём родительские папки и записываем файл
                Files.createDirectories(target.getParent());
                try {
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    installed.add(name);
                    TechEclipseLoader.LOGGER.info("Installed: {}", name);
                } catch (IOException e) {
                    // Файл может быть заблокирован на Windows
                    TechEclipseLoader.LOGGER.error("Can't write file: {} ({})", name, e.getMessage());
                }

                zis.closeEntry();
            }
        }

        return installed;
    }

    /**
     * Сохраняет список установленных файлов в techeclipse-managed-files.json.
     * При следующем обновлении эти файлы будут удалены перед установкой новых.
     *
     * Формат:
     * { "files": ["mods/mod1.jar", "config/mymod.json"] }
     */
    private void saveManagedFiles(List<String> files) throws IOException {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        files.forEach(array::add);
        json.add("files", array);

        Path path = gameDir.resolve("techeclipse-managed-files.json");
        Files.writeString(path, gson.toJson(json));
    }

    /**
     * Сохраняет текущую версию модпака в techeclipse-version.json.
     *
     * Формат:
     * { "version": "1.0.1" }
     */
    private void saveLocalVersion(String version) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);

        Path path = gameDir.resolve("techeclipse-version.json");
        Files.writeString(path, gson.toJson(json));
    }
}
