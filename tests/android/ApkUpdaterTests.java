package de.kolbasa.apkupdater;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import org.junit.jupiter.api.*;

import de.kolbasa.apkupdater.downloader.exceptions.AlreadyRunningException;
import de.kolbasa.apkupdater.downloader.progress.DownloadProgress;
import de.kolbasa.apkupdater.downloader.progress.UnzipProgress;
import de.kolbasa.apkupdater.downloader.exceptions.WrongChecksumException;
import de.kolbasa.apkupdater.downloader.manifest.Manifest;
import de.kolbasa.apkupdater.downloader.update.UpdateDownloadEvent;

import static org.junit.jupiter.api.Assertions.*;

class ApkUpdaterTests {

    private static final String SERVER_URL = "https://github.com/kolbasa/cordova-plugin-apkupdater/raw/master/tests/updates";
    private static final String UPDATE_1_0_0 = "/update-compressed/1.0.0";
    private static final String REMOTE_UPDATE_1_0_0 = SERVER_URL + UPDATE_1_0_0;
    private static final String REMOTE_UPDATE_1_0_0_WITH_CORRUPTION = SERVER_URL + UPDATE_1_0_0 + "-corrupted-chunk";
    private static final String REMOTE_UPDATE_1_0_0_WITH_MISSING_CHUNK = SERVER_URL + UPDATE_1_0_0 + "-missing-chunk";
    private static final String RESOURCES = "../../../plugins/cordova-plugin-apkupdater/tests/updates";

    private static final String TEMP_DIR = "cordova.apk.updater";

    private static final String VERSION = "1.0.0";
    private static final String APK_NAME = "example.apk";
    private static final String MANIFEST = "manifest.json";
    private static final String MD5_HASH = "35d9fd2d688156e45b89707f650a61ac";

    private static final String UPDATE = RESOURCES + "/update-compressed/1.0.0";
    private static final String UPDATE_CORRUPTED = RESOURCES + "/update-compressed/1.0.0-corrupted-chunk";

    private static final String APK = RESOURCES + "/update/1.0.0/example.apk";
    private static final String APK_CORRUPTED = RESOURCES + "/update/1.0.0-corrupted-apk/example.apk";

    private static final String PART_01 = "update.zip.001";
    private static final String PART_02 = "update.zip.002";
    private static final String PART_03 = "update.zip.003";

    private static final String TIMEOUT_IP = "http://example.com:81/";

    private static final int DOWNLOAD_INTERVAL_IN_MS = 500;
    private static final int MAX_DOWNLOAD_TIME = DOWNLOAD_INTERVAL_IN_MS - 100;

    private static class Events {
        private ArrayList<UpdateDownloadEvent> events = new ArrayList<>();
        private ArrayList<Exception> exceptions = new ArrayList<>();
        private ArrayList<UnzipProgress> unzipProgress = new ArrayList<>();
        private ArrayList<DownloadProgress> downloadProgress = new ArrayList<>();

        ArrayList<DownloadProgress> getDownloadProgress() {
            return downloadProgress;
        }

        ArrayList<UnzipProgress> getUnzipProgress() {
            return unzipProgress;
        }

        ArrayList<Exception> getExceptions() {
            return exceptions;
        }

        ArrayList<UpdateDownloadEvent> getEvents() {
            return events;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void copyFile(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists()) {
                destination.mkdir();
            }
            for (String file : source.list()) {
                copyFile(new File(source, file), new File(destination, file));
            }
        } else {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        directory.delete();
    }

    private static String createDownloadDirectory() {
        String path = null;
        try {
            path = Files.createTempDirectory(TEMP_DIR + ".").toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return path;
    }

    private void copyUpdateToDevice(String apkPath) throws IOException {
        // noinspection ResultOfMethodCallIgnored
        new File(downloadDirectory, MD5_HASH).mkdir();
        copyFile(new File(apkPath), new File(updateDirectory, APK_NAME));
    }

    private File copyUpdateChunksToDevice(String hash) throws IOException {
        File dir = new File(downloadDirectory, hash);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        copyFile(new File(UPDATE), dir);
        copyFile(new File(UPDATE, MANIFEST), new File(downloadDirectory, MANIFEST));
        return dir;
    }

    private Events observe(UpdateManager updater) {
        Events events = new Events();

        updater.addObserver((o, arg) -> {
            if (arg instanceof DownloadProgress) {
                events.getDownloadProgress().add(new DownloadProgress((DownloadProgress) arg));
            }

            if (arg instanceof UnzipProgress) {
                events.getUnzipProgress().add(new UnzipProgress((UnzipProgress) arg));
            }

            if (arg instanceof Exception) {
                events.getExceptions().add((Exception) arg);
            }

            if (arg instanceof UpdateDownloadEvent) {
                events.getEvents().add((UpdateDownloadEvent) arg);
            }
        });

        return events;
    }

    private String downloadDirectory;
    private String updateDirectory;

    @BeforeEach
    void setUp() {
        this.downloadDirectory = createDownloadDirectory();
        this.updateDirectory = this.downloadDirectory + File.separator + MD5_HASH;
    }

    @AfterEach
    void tearDown() {
        deleteDirectory(new File(this.downloadDirectory));
    }

    @Nested
    @DisplayName("Looking for new update")
    class ManifestLogic {

        @Test
        @DisplayName("Writing manifest file to file system")
        void writing() throws Exception {
            new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertTrue(new File(downloadDirectory, "manifest.json").exists());
        }

        @Test
        @DisplayName("Reading manifest file")
        void reading() throws Exception {
            Manifest manifest = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertNotNull(manifest);
        }

        @Test
        @DisplayName("Reading version in manifest")
        void readingVersion() throws Exception {
            Manifest manifest = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertEquals(VERSION, manifest.getVersion());
        }

        @Test
        @DisplayName("Mark update as ready if already downloaded")
        void markIfAlreadyDownloaded() throws Exception {
            System.out.println(System.getProperty("user.dir"));
            copyUpdateToDevice(APK);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertNotNull(manifest.getUpdateFile());
        }

        @Test
        @DisplayName("Do not mark as ready if update corrupted")
        void doNotMarkAsReadyIfApkCorrupted() throws Exception {
            copyUpdateToDevice(APK_CORRUPTED);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertNull(manifest.getUpdateFile());
        }

        @Test
        @DisplayName("Do not mark as ready if only chunks are downloaded")
        void doNotMarkAsReadyIfOnlyChunksAreDownloaded() throws Exception {
            copyUpdateChunksToDevice(MD5_HASH);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory).check();
            assertNull(manifest.getUpdateFile());
        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("Manifest file is missing on server")
            void missing() {
                UpdateManager updater = new UpdateManager(SERVER_URL, downloadDirectory);
                assertThrows(java.io.FileNotFoundException.class, updater::check);
            }

            @Test
            @DisplayName("Socket timeout")
            void socketTimeout() {
                UpdateManager updater = new UpdateManager(TIMEOUT_IP, downloadDirectory, 100);
                assertThrows(java.net.SocketTimeoutException.class, updater::check);
            }
        }
    }

    @Nested
    @DisplayName("Downloading update")
    class Download {

        @Test
        @DisplayName("Download all chunks")
        void downloadAllChunks() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
            updater.check();
            updater.download();
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertTrue(new File(updateDirectory, PART_03).exists());
        }

        @Test
        @DisplayName("Extract update")
        void extractUpdate() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
            updater.check();
            updater.download();
            assertTrue(new File(updateDirectory, APK_NAME).exists());
        }

        @Test
        @DisplayName("Extract only if download complete")
        void extractButNotDownload() throws Exception {
            copyUpdateChunksToDevice(MD5_HASH);
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

            Events events = observe(updater);
            ArrayList<UnzipProgress> unzipProgress = events.getUnzipProgress();
            ArrayList<DownloadProgress> downloadProgress = events.getDownloadProgress();

            updater.check();
            updater.download();

            assertEquals(0, downloadProgress.size());
            assertTrue(unzipProgress.size() > 0);
            assertTrue(new File(updateDirectory, APK_NAME).exists());
        }

        @Nested
        class Removing {
            @Test
            @DisplayName("Manually removing")
            void clearingUpdates() throws Exception {
                copyUpdateChunksToDevice(MD5_HASH);

                assertTrue(new File(downloadDirectory, MANIFEST).exists());
                assertTrue(new File(downloadDirectory, MD5_HASH).exists());

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.removeUpdates();

                assertFalse(new File(downloadDirectory, MANIFEST).exists());
                assertFalse(new File(downloadDirectory, MD5_HASH).exists());
            }

            @Test
            @DisplayName("Old update files")
            void removeOldUpdate() throws Exception {
                // old update
                File dir = copyUpdateChunksToDevice("0.9.0");
                assertTrue(dir.exists());

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

                assertFalse(new File(updateDirectory).exists());

                updater.check();
                updater.download();

                assertFalse(dir.exists());
                assertTrue(new File(updateDirectory).exists());
            }
        }

        @Nested
        class Paths {
            @Test
            @DisplayName("Create new update directory")
            void createNewUpdateDirectory() throws Exception {
                assertFalse(new File(updateDirectory).exists());
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();
                updater.download();
                assertTrue(new File(updateDirectory).exists());
            }

            @Test
            @DisplayName("Manifest should return correct update path")
            void updateFile() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

                Manifest manifest = updater.check();
                assertNull(manifest.getUpdateFile());
                updater.download();
                assertEquals(manifest.getUpdateFile(), new File(updateDirectory, APK_NAME));
            }
        }

        @Nested
        class Skipping {
            @Test
            @DisplayName("Extraction if already extracted")
            void apkAlreadyExtracted() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

                assertNull(updater.check().getUpdateFile());

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();

                // noinspection ResultOfMethodCallIgnored
                new File(downloadDirectory, MD5_HASH).mkdir();
                copyFile(new File(APK), new File(updateDirectory, APK_NAME));

                updater.download();

                assertEquals(0, events.size());
            }

            @Test
            @DisplayName("Already downloaded chunks")
            void shouldSkipAlreadyDownloaded() throws Exception {
                // noinspection ResultOfMethodCallIgnored
                new File(updateDirectory).mkdir();
                copyFile(new File(RESOURCES + UPDATE_1_0_0, PART_01), new File(updateDirectory, PART_01));

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                updater.download();

                assertEquals(3, events.size());
                assertEquals(3, events.get(0).getChunks());

                // TODO: Better check file-hash
                assertEquals(0, events.get(0).getChunksDownloaded());
                assertEquals(2, events.get(1).getChunksDownloaded());
                assertEquals(3, events.get(2).getChunksDownloaded());
            }
        }

        @Nested
        class Corruption {

            @Test
            @DisplayName("Replace apk")
            void replaceApk() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();

                // noinspection ResultOfMethodCallIgnored
                new File(downloadDirectory, MD5_HASH).mkdir();
                copyFile(new File(APK_CORRUPTED), new File(updateDirectory, APK_NAME));

                updater.download();

                assertTrue(events.size() > 0);
            }

//            @Test
//            @DisplayName("Replace chunk")
//            void replaceChunk() throws Exception {
//                // noinspection ResultOfMethodCallIgnored
//                new File(updateDirectory).mkdir();
//                copyFile(new File(UPDATE_CORRUPTED, PART_01), new File(updateDirectory, PART_01));
//
//                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
//                updater.check();
//
//                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();
//
//                updater.download();
//
//                assertEquals(4, events.size());
//                assertEquals(3, events.get(0).getChunks());
//
//                assertEquals(0, events.get(0).getChunksDownloaded());
//                assertEquals(1, events.get(1).getChunksDownloaded());
//                assertEquals(2, events.get(2).getChunksDownloaded());
//                assertEquals(3, events.get(3).getChunksDownloaded());
//            }

        }

        @Nested
        class Broadcasting {

            @Test
            @DisplayName("Extraction")
            void extraction() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();
                updater.download();
                assertEquals(2, events.size());
                assertEquals(0, events.get(0).getPercent());
                assertEquals(100f, events.get(1).getPercent());
            }

            @Test
            @DisplayName("Downloading")
            void downloading() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();
                updater.download();
                assertEquals(4, events.size());
                assertEquals(0, events.get(0).getPercent());
                assertEquals(42.74f, events.get(1).getPercent());
                assertEquals(85.47f, events.get(2).getPercent());
                assertEquals(100f, events.get(3).getPercent());
            }

            @Test
            @DisplayName("Update ready")
            void updateReady() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();
                updater.download();
                assertTrue(events.contains(UpdateDownloadEvent.UPDATE_READY));
            }

            @Test
            @DisplayName("Finished download")
            void finishedDownload() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();
                updater.download();
                assertTrue(events.contains(UpdateDownloadEvent.STOPPED));
            }

            @Test
            @DisplayName("Individual downloaded chunks")
            void individualDownloadedChunks() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                updater.download();

                assertEquals(4, events.size());
                assertEquals(3, events.get(0).getChunks());

                assertEquals(0, events.get(0).getChunksDownloaded());
                assertEquals(1, events.get(1).getChunksDownloaded());
                assertEquals(2, events.get(2).getChunksDownloaded());
                assertEquals(3, events.get(3).getChunksDownloaded());
            }

        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("UpdateChunk has wrong checksum")
            void wrongChecksum() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0_WITH_CORRUPTION, downloadDirectory);
                updater.check();
                assertThrows(WrongChecksumException.class, updater::download);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());
            }

            @Test
            @DisplayName("UpdateChunk is missing on server")
            void partMissingOnServer() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0_WITH_MISSING_CHUNK, downloadDirectory);
                updater.check();

                assertThrows(FileNotFoundException.class, updater::download);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());
            }
        }

    }

    @Nested
    @DisplayName("Gradually downloading update")
    class GradualDownload {

        int waitForFile(String part) throws InterruptedException {
            return waitForFile(part, MAX_DOWNLOAD_TIME);
        }

        int waitForFile(String part, Integer waitTime) throws InterruptedException {
            for (int i = 0; i < waitTime / 5; i++) {
                Thread.sleep(5);
                if (new File(updateDirectory, part).exists()) {
                    System.out.println("foo: " + (i * 5));
                    return i * 5;
                }
            }
            return waitTime;
        }

        @Test
        @DisplayName("Download all chunks")
        void delayedDownload() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
            updater.check();

            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            assertFalse(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_01);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_02);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_03);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertTrue(new File(updateDirectory, PART_03).exists());
        }

        @Test
        @DisplayName("Extract update")
        void extractUpdate() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
            updater.check();

            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_01);
            assertFalse(new File(updateDirectory, APK_NAME).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_02);
            assertFalse(new File(updateDirectory, APK_NAME).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_03);
            assertFalse(new File(updateDirectory, APK_NAME).exists());

            waitForFile(APK_NAME, 500);
            assertTrue(new File(updateDirectory, APK_NAME).exists());
        }

//        @Test
//        @DisplayName("Stop download if new version is available")
//        void shouldStopOnNewVersion() throws Exception {
//            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
//            updater.check();
//
//            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);
//
//            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS + 50);
//            assertTrue(updater.isDownloading());
//            assertTrue(new File(updateDirectory, PART_01).exists());
//            assertFalse(new File(updateDirectory, PART_02).exists());
//            assertFalse(new File(updateDirectory, PART_03).exists());
//            assertFalse(new File(updateDirectory, APK_NAME).exists());
//
//            deleteDirectory(new File(SERVER_PATH));
//            copyFile(new File(UPDATE_2), new File(SERVER_PATH));
//
//            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS);
//            assertFalse(updater.isDownloading());
//        }

        @Test
        @DisplayName("Remove corrupted chunk")
        void removeCorruptedChunk() throws Exception {

            UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

            updater.check();
            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            assertTrue(updater.isDownloading());
            shift = waitForFile(PART_01);
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());

            // Corrupting first chunk
            copyFile(new File(UPDATE_CORRUPTED, PART_01), new File(updateDirectory, PART_01));

            // Replacing corrupted chunk
            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = 100;
            Thread.sleep(shift);
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_02);
            assertTrue(updater.isDownloading());
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            updater.stop();
        }

        @Nested
        class Broadcasting {
            @Test
            @DisplayName("Update ready")
            void broadcastFinishedDownload() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();

                Manifest manifest = updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);
                events.clear(); // we don't need the start event

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertEquals(0, events.size());
                assertNull(manifest.getUpdateFile());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_02);
                assertEquals(0, events.size());
                assertNull(manifest.getUpdateFile());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_03);
                waitForFile(APK_NAME);
                Thread.sleep(100);
                assertEquals(2, events.size());

                assertEquals(UpdateDownloadEvent.UPDATE_READY, events.get(0));
                assertEquals(UpdateDownloadEvent.STOPPED, events.get(1));

                assertNotNull(manifest.getUpdateFile());
            }

            @Test
            @DisplayName("AbstractProgress")
            void progress() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);

                DownloadProgress event;

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                Thread.sleep(100); // TODO: should not be necessary
                assertEquals(2, events.size());

                // there is one event at the start of the download
                event = events.get(0);
                assertEquals(0, event.getBytesWritten());
                assertEquals(0, event.getChunksDownloaded());

                event = events.get(1);
                assertEquals(1, event.getChunksDownloaded());
                assertEquals(150, event.getBytesWritten());
                assertEquals(42.74f, event.getPercent());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_02);
                Thread.sleep(100);

                assertEquals(3, events.size());
                event = events.get(2);
                assertEquals(2, event.getChunksDownloaded());
                assertEquals(300, event.getBytesWritten());
                assertEquals(85.47f, event.getPercent());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_03);
                Thread.sleep(100);

                assertEquals(4, events.size());
                event = events.get(3);
                assertEquals(3, event.getChunksDownloaded());
                assertEquals(event.getBytes(), event.getBytesWritten());
                assertEquals(100f, event.getPercent());
            }

        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("UpdateChunk missing")
            void chunkMissing() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0_WITH_MISSING_CHUNK, downloadDirectory);
                ArrayList<Exception> exceptions = observe(updater).getExceptions();
                System.out.println(REMOTE_UPDATE_1_0_0_WITH_MISSING_CHUNK);

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertTrue(updater.isDownloading());
                assertTrue(new File(updateDirectory, PART_01).exists());
                assertEquals(0, exceptions.size());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                Thread.sleep(500);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof FileNotFoundException);
                assertTrue(exceptions.get(0).getMessage().endsWith(PART_02));
            }

            @Test
            @DisplayName("Wrong checksum")
            void wrongChecksum() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0_WITH_CORRUPTION, downloadDirectory);
                ArrayList<Exception> exceptions = observe(updater).getExceptions();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertTrue(updater.isDownloading());
                assertEquals(0, exceptions.size());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                Thread.sleep(500);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof WrongChecksumException);

                updater.stop();
            }

            @Test
            @DisplayName("Stop second download request")
            void stopSecondDownloadRequest() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_1_0_0, downloadDirectory);
                updater.check();

                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());

                assertThrows(AlreadyRunningException.class, () ->
                        updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS));

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_02);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertTrue(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());

                updater.stop();
            }
        }

    }
}
