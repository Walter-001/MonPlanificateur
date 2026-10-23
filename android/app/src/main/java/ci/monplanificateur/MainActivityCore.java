package ci.monplanificateur;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivityCore extends Activity {
    private static final int PICK_FILE = 1401;
    private static final int NOTIFICATION_PERMISSION = 1402;
    private static final int CREATE_BACKUP = 1403;
    private static final int RESTORE_BACKUP = 1404;
    private WebView webView;
    private String pendingDate;
    private String pendingEventId;
    private String pendingTime;
    private String pendingTitle;
    private boolean pendingSound = true;
    private String pendingBackup;
    private JSONArray pendingRestoredAlarms;
    private JSONArray pendingBatchAlarms;
    private boolean pageLoaded;
    private boolean awaitingExactAlarmSettings;
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBackPressed);
        }
        getWindow().setStatusBarColor(0xFFF3FAF6);
        getWindow().setNavigationBarColor(0xFFF3FAF6);
        AlarmReceiver.ensureChannel(this);
        AlarmReceiver.rescheduleAll(this);
        FrameLayout frame = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF3FAF6);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PlannerBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                processReminderIntent(getIntent());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !(url.startsWith("file:///android_asset/") || "about:blank".equals(url));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !(url.startsWith("file:///android_asset/") || "about:blank".equals(url));
            }
        });
        frame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onDestroy() {
        executorService.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingExactAlarmSettings) {
            awaitingExactAlarmSettings = false;
            if (pendingBatchAlarms != null) { JSONArray batch = pendingBatchAlarms; pendingBatchAlarms = null; scheduleAlarmBatch(batch, false); }
            else if (pendingDate != null && pendingEventId != null) {
                String d = pendingDate, id = pendingEventId, time = pendingTime, title = pendingTitle; boolean sound = pendingSound;
                pendingDate = pendingEventId = pendingTime = pendingTitle = null;
                scheduleExactOrApproximate(d, id, time, title, sound, false);
            }
            scheduleRestoredAlarms();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION && pendingBatchAlarms != null) {
            JSONArray batch = pendingBatchAlarms; pendingBatchAlarms = null;
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Autorise les notifications pour recevoir les rappels importés.", Toast.LENGTH_LONG).show();
            } else scheduleAlarmBatch(batch, true);
        } else if (requestCode == NOTIFICATION_PERMISSION && pendingDate != null && pendingEventId != null) {
            String date = pendingDate, id = pendingEventId, time = pendingTime, title = pendingTitle; boolean sound = pendingSound;
            pendingDate = pendingEventId = pendingTime = pendingTitle = null;
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Les rappels ne pourront pas afficher de notification tant que cette autorisation reste désactivée.", Toast.LENGTH_LONG).show();
            scheduleExactOrApproximate(date, id, time, title, sound, true);
        }
        if (requestCode == NOTIFICATION_PERMISSION) scheduleRestoredAlarms();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processReminderIntent(intent);
    }

    private void processReminderIntent(Intent intent) {
        if (intent == null || !pageLoaded) return;
        int notificationId = intent.getIntExtra("reminderNotificationId", Integer.MIN_VALUE);
        if (notificationId != Integer.MIN_VALUE) AlarmReceiver.cancelNotification(this, notificationId);
        if ("done".equals(intent.getStringExtra("plannerAction"))) {
            String date = intent.getStringExtra("reminderDate"), eventId = intent.getStringExtra("reminderEventId");
            if (date != null && eventId != null && webView != null)
                webView.evaluateJavascript("window.markEventDone && window.markEventDone(" + JSONObject.quote(date) + "," + JSONObject.quote(eventId) + ")", null);
        }
        intent.removeExtra("plannerAction");
        intent.removeExtra("reminderDate");
        intent.removeExtra("reminderEventId");
        intent.removeExtra("reminderNotificationId");
    }

    private class PlannerBridge {
        @JavascriptInterface public void scheduleAlarm(String date, String eventId, String time, String title, boolean sound) {
            runOnUiThread(() -> scheduleAlarmFromUi(date, eventId, time, title, sound));
        }
        @JavascriptInterface public void printPlanner() { runOnUiThread(MainActivityCore.this::printPlanner); }
        @JavascriptInterface public void scheduleAlarms(String json) {
            runOnUiThread(() -> {
                try { scheduleAlarmBatch(new JSONArray(json), true); }
                catch (Exception error) { Toast.makeText(MainActivityCore.this, "Impossible de programmer tous les rappels.", Toast.LENGTH_LONG).show(); }
            });
        }
        @JavascriptInterface public void cancelAlarm(String date, String eventId) {
            runOnUiThread(() -> AlarmReceiver.cancel(MainActivityCore.this, date, eventId));
        }
        @JavascriptInterface public void cancelLegacyAlarm(String date, int slot) {
            runOnUiThread(() -> AlarmReceiver.cancelLegacy(MainActivityCore.this, date, slot));
        }
        @JavascriptInterface public void restoreAlarms(String json) {
            runOnUiThread(() -> {
                try { pendingRestoredAlarms = new JSONArray(json); }
                catch (Exception e) { pendingRestoredAlarms = null; return; }
                if (pendingRestoredAlarms.length() == 0) { pendingRestoredAlarms = null; return; }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                } else scheduleRestoredAlarms();
            });
        }
        @JavascriptInterface public void exportBackup(String json) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("application/json");
                create.putExtra(Intent.EXTRA_TITLE, "mon-planificateur-sauvegarde.json");
                startActivityForResult(create, CREATE_BACKUP);
            });
        }
        @JavascriptInterface public void chooseBackupFile() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("application/json");
                startActivityForResult(pick, RESTORE_BACKUP);
            });
        }
        @JavascriptInterface public void chooseScheduleFile() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "image/*", "application/pdf", "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv", "text/plain", "application/rtf"
                });
                startActivityForResult(pick, PICK_FILE);
            });
        }
    }

    private void printPlanner() {
        if (webView == null) return;
        PrintManager manager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (manager == null) { Toast.makeText(this, "Impression indisponible sur cet appareil.", Toast.LENGTH_LONG).show(); return; }
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("Mon planificateur");
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("planner", "Mon planificateur", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
        manager.print("Mon planificateur", adapter, attributes);
    }

    private void scheduleAlarmBatch(JSONArray alarms, boolean askPermission) {
        if (alarms == null || alarms.length() == 0) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingBatchAlarms = alarms;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            return;
        }
        if (askPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (manager != null && !manager.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                        .setTitle("Autoriser les alarmes précises ?")
                        .setMessage("Autorise « Alarmes et rappels » pour programmer à l’heure prévue. Tu peux aussi choisir des rappels approximatifs.")
                        .setPositiveButton("Ouvrir les réglages", (dialog, which) -> {
                            pendingBatchAlarms = alarms; awaitingExactAlarmSettings = true;
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Rappels approximatifs", (dialog, which) -> scheduleAlarmBatch(alarms, false))
                        .show();
                return;
            }
        }
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.optJSONObject(i);
            if (alarm == null) continue;
            AlarmReceiver.scheduleQuiet(this, alarm.optString("date"), alarm.optString("id"), alarm.optString("time"), alarm.optString("title", "Rappel"), alarm.optBoolean("sound", true));
        }
    }

    private void scheduleAlarmFromUi(String date, String eventId, String time, String title, boolean sound) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDate = date; pendingEventId = eventId; pendingTime = time; pendingTitle = title; pendingSound = sound;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            return;
        }
        scheduleExactOrApproximate(date, eventId, time, title, sound, true);
    }

    private void scheduleExactOrApproximate(String date, String eventId, String time, String title, boolean sound, boolean showPrompt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (manager != null && !manager.canScheduleExactAlarms()) {
                if (!showPrompt) { AlarmReceiver.schedule(this, date, eventId, time, title, sound); return; }
                new AlertDialog.Builder(this)
                        .setTitle("Autoriser les alarmes précises ?")
                        .setMessage("Android peut retarder un rappel lorsque l’économie d’énergie est active. Autorise « Alarmes et rappels » pour être averti à l’heure prévue.")
                        .setPositiveButton("Ouvrir les réglages", (dialog, which) -> {
                            pendingDate = date; pendingEventId = eventId; pendingTime = time; pendingTitle = title; pendingSound = sound;
                            awaitingExactAlarmSettings = true;
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Rappel approximatif", (dialog, which) -> AlarmReceiver.schedule(this, date, eventId, time, title, sound))
                        .show();
                return;
            }
        }
        AlarmReceiver.schedule(this, date, eventId, time, title, sound);
    }

    private void scheduleRestoredAlarms() {
        if (pendingRestoredAlarms == null || Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        JSONArray alarms = pendingRestoredAlarms; pendingRestoredAlarms = null;
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.optJSONObject(i);
            if (alarm == null) continue;
            AlarmReceiver.scheduleQuiet(this, alarm.optString("date"), alarm.optString("id"), alarm.optString("time"), alarm.optString("title"), alarm.optBoolean("sound", true));
        }
        Toast.makeText(this, "Rappels restaurés. Vérifie l’autorisation « Alarmes et rappels » pour une heure précise.", Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_BACKUP) {
            Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            String payload = pendingBackup; pendingBackup = null;
            if (uri == null || payload == null) return;
            executorService.execute(() -> {
                String error = null;
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null) throw new IOException("Emplacement inaccessible");
                    stream.write(payload.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) { error = e.getMessage(); }
                final String resultError = error;
                runOnUiThread(() -> webView.evaluateJavascript("window.onBackupExported && window.onBackupExported(" + (resultError == null) + "," + JSONObject.quote(resultError == null ? "" : resultError) + ")", null));
            });
            return;
        }
        if (requestCode == RESTORE_BACKUP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            executorService.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    if (input == null) throw new IOException("Fichier inaccessible");
                    byte[] buffer = new byte[8192]; int count, total = 0;
                    while ((count = input.read(buffer)) != -1) { total += count; if (total > 20 * 1024 * 1024) throw new IOException("Sauvegarde trop volumineuse"); bytes.write(buffer, 0, count); }
                    JSONObject backup = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    if (!(backup.opt("days") instanceof JSONObject)) throw new IOException("Format de sauvegarde invalide");
                    String days = backup.getJSONObject("days").toString();
                    runOnUiThread(() -> webView.evaluateJavascript("window.onBackupRestored && window.onBackupRestored(" + JSONObject.quote(days) + ")", null));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Restauration impossible : fichier invalide ou illisible.", Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String name = displayName(uri);
            String ext = extension(name);
            if (ext.isEmpty()) ext = extensionFromMime(getContentResolver().getType(uri));
            if ("pdf".equals(ext)) extractPdf(uri, name);
            else if (isImage(ext, getContentResolver().getType(uri))) recognizeImage(uri, name);
            else if (isSupportedDocument(ext)) extractDocument(uri, name);
            else Toast.makeText(this, "Format non pris en charge. Choisis une image, un PDF, Word, Excel, CSV, TXT ou RTF.", Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String path = uri.getLastPathSegment();
        return path == null ? "programme" : path;
    }

    private String extensionFromMime(String mime) {
        if (mime == null) return "";
        if (mime.startsWith("image/")) return "image";
        if ("application/pdf".equals(mime)) return "pdf";
        if (mime.contains("wordprocessingml")) return "docx";
        if ("application/msword".equals(mime)) return "doc";
        if (mime.contains("spreadsheetml")) return "xlsx";
        if ("application/vnd.ms-excel".equals(mime)) return "xls";
        if (mime.equals("text/csv")) return "csv";
        if (mime.equals("text/plain")) return "txt";
        if (mime.equals("application/rtf") || mime.equals("text/rtf")) return "rtf";
        return "";
    }

    private String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isImage(String ext, String mime) { return "image".equals(ext) || (mime != null && mime.startsWith("image/")); }
    private boolean isSupportedDocument(String ext) {
        return "doc".equals(ext) || "docx".equals(ext) || "xls".equals(ext) || "xlsx".equals(ext)
                || "csv".equals(ext) || "txt".equals(ext) || "rtf".equals(ext);
    }

    private void recognizeImage(Uri uri, String name) {
        setLoading(true);
        Toast.makeText(this, "Lecture de l’image sur le téléphone…", Toast.LENGTH_SHORT).show();
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        deliverText(result.getText(), name);
                        recognizer.close();
                    })
                    .addOnFailureListener(error -> {
                        recognizer.close();
                        setLoading(false);
                        Toast.makeText(this, "Impossible de lire cette image. Essaie une image plus nette.", Toast.LENGTH_LONG).show();
                    });
        } catch (IOException error) {
            setLoading(false);
            Toast.makeText(this, "Impossible d’ouvrir cette image.", Toast.LENGTH_LONG).show();
        }
    }

    private void extractDocument(Uri uri, String name) {
        setLoading(true);
        Toast.makeText(this, "Lecture du document sur le téléphone…", Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            try {
                String text = ScheduleFileReader.extract(this, uri, name);
                runOnUiThread(() -> deliverText(text, name));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Lecture impossible : " + friendlyError(error), Toast.LENGTH_LONG).show();
                });
            } finally {
                setLoading(false);
            }
        });
    }

    private void extractPdf(Uri uri, String name) {
        setLoading(true);
        Toast.makeText(this, "Lecture du PDF sur le téléphone…", Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            StringBuilder allText = new StringBuilder();
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            File local = null;
            try {
                local = ScheduleFileReader.copyPdf(this, uri, name);
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(descriptor)) {
                    int pageCount = Math.min(renderer.getPageCount(), 20);
                    for (int i = 0; i < pageCount; i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        float scale = Math.min(2.4f, 1900f / Math.max(page.getWidth(), page.getHeight()));
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                        try {
                            String text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
                            allText.append(text).append('\n');
                        } finally { bitmap.recycle(); }
                    }
                }
                final String extracted = allText.toString();
                runOnUiThread(() -> deliverText(extracted, name));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Impossible de lire ce PDF. Essaie une image plus nette ou un autre fichier.", Toast.LENGTH_LONG).show();
                });
            } finally {
                recognizer.close();
                if (local != null) //noinspection ResultOfMethodCallIgnored
                    local.delete();
                setLoading(false);
            }
        });
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "vérifie que le fichier n’est pas protégé" : message;
    }

    private void deliverText(String text, String sourceName) {
        setLoading(false);
        JSONArray parsed = parseScheduleText(text);
        String payload = JSONObject.quote(parsed.toString());
        String source = JSONObject.quote(sourceName == null ? "fichier" : sourceName);
        if (parsed.length() == 0) {
            Toast.makeText(this, "Aucun horaire détecté. Essaie un fichier plus net ou contenant des heures comme 08:00.", Toast.LENGTH_LONG).show();
            return;
        }
        webView.evaluateJavascript("window.onScheduleExtracted && window.onScheduleExtracted(" + payload + "," + source + ")", null);
    }

    private void setLoading(boolean loading) {
        if (isFinishing() || isDestroyed() || webView == null) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && webView != null) {
                webView.evaluateJavascript("window.setLoading && window.setLoading(" + loading + ")", null);
            }
        });
    }

    private String parseDocumentDate(String line) {
        String[][] patterns = {
                {"(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", "dmy"},
                {"(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", "ymd"},
                {"(?i)(?<!\\d)([0-3]?\\d)(?:er)?\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+(20\\d{2})(?!\\d)", "french"}
        };
        for (String[] spec : patterns) {
            Matcher matcher = Pattern.compile(spec[0], Pattern.CASE_INSENSITIVE).matcher(line);
            if (!matcher.find()) continue;
            try {
                int day, month = 0, year;
                if ("dmy".equals(spec[1])) {
                    day = Integer.parseInt(matcher.group(1)); month = Integer.parseInt(matcher.group(2)); year = Integer.parseInt(matcher.group(3));
                } else if ("ymd".equals(spec[1])) {
                    year = Integer.parseInt(matcher.group(1)); month = Integer.parseInt(matcher.group(2)); day = Integer.parseInt(matcher.group(3));
                } else {
                    day = Integer.parseInt(matcher.group(1)); year = Integer.parseInt(matcher.group(3));
                    String name = matcher.group(2).toLowerCase(Locale.FRANCE);
                    if (name.startsWith("janv")) month = 1;
                    else if (name.startsWith("fév") || name.startsWith("fev")) month = 2;
                    else if (name.equals("mars")) month = 3;
                    else if (name.equals("avril")) month = 4;
                    else if (name.equals("mai")) month = 5;
                    else if (name.equals("juin")) month = 6;
                    else if (name.equals("juillet")) month = 7;
                    else if (name.startsWith("aoû") || name.startsWith("aou")) month = 8;
                    else if (name.equals("septembre")) month = 9;
                    else if (name.equals("octobre")) month = 10;
                    else if (name.equals("novembre")) month = 11;
                    else if (name.startsWith("déc") || name.startsWith("dec")) month = 12;
                }
                java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                formatter.setLenient(false);
                java.util.Date parsed = formatter.parse(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day));
                return formatter.format(parsed);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String cleanEventDescription(String line) {
        String value = line.replaceAll("(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", " ")
                .replaceAll("(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", " ")
                .replaceAll("(?i)(?<!\\d)([0-3]?\\d)(?:er)?\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+(20\\d{2})(?!\\d)", " ");
        value = Pattern.compile("(?<![\\p{L}])(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|mon|tue|wed|thu|fri|sat|sun|pdf|cycle|source|poste|horaires|appliqués)(?![\\p{L}])", Pattern.CASE_INSENSITIVE).matcher(value).replaceAll(" ");
        value = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)").matcher(value).replaceAll(" ");
        value = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*(?:h|:|\\.)(\\s*[0-5]?\\d)?(?!\\d)", Pattern.CASE_INSENSITIVE).matcher(value).replaceAll(" ");
        value = value.replaceAll("\\b[A-Z]{1,5}\\b", " ").replaceAll("(?i)\\b(?:pdf|cycle|source|poste|horaires|appliqués)\\b", " ").replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (value.matches("(?s)^[\\p{L}]{1,6}$")) return "";
        return value;
    }

    private String cleanDateOnlyDescription(String line) {
        return line.replaceAll("(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", " ")
                .replaceAll("(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", " ")
                .replaceAll("(?i)(?<!\\d)([0-3]?\\d)(?:er)?\\s+(?:janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\s+20\\d{2}(?!\\d)", " ")
                .replaceAll("(?i)(?<![\\p{L}])(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|mon|tue|wed|thu|fri|sat|sun)(?![\\p{L}])", " ")
                .replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    private JSONArray parseScheduleText(String text) {
        JSONArray out = new JSONArray();
        if (text == null) return out;
        text = text.replace('\u200b', ' ').replace('\ufeff', ' ')
                .replaceAll("(?i)([0-2]?[0-9])\\s*[h:]\\s*([oO])([0-9])", "$1:0$3")
                .replaceAll("(?i)([0-2]?[0-9])\\s*[h:]\\s*([0-9])([oO])", "$1:$20")
                .replaceAll("(?i)([0-2]?[0-9])\\s*[h:]\\s*([oO]{2})", "$1:00")
                .replaceAll("(?m)(\\d{1,2}:\\d{2})\\s*\\n\\s*[-–—]\\s*(\\d{1,2}:\\d{2})", "$1-$2");
        Pattern rangePattern = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
        Pattern timePattern = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*(?:h|:|\\.)(\\s*[0-5]?\\d)?(?!\\d)", Pattern.CASE_INSENSITIVE);
        String[] lines = text.replace('\r', '\n').split("\\n+");
        boolean documentHasDates = false;
        for (String candidate : lines) if (parseDocumentDate(candidate) != null) { documentHasDates = true; break; }
        String currentDate = null;
        LinkedHashSet<String> explicitDates = new LinkedHashSet<>();
        LinkedHashSet<String> importedDates = new LinkedHashSet<>();
        for (String sourceLine : lines) {
            String line = sourceLine.replace('\t', ' ').trim();
            if (line.isEmpty()) continue;
            String foundDate = parseDocumentDate(line);
            if (foundDate != null) { currentDate = foundDate; explicitDates.add(foundDate); }
            String timeLine = line.replaceAll("(?<!\\d)([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2})(?!\\d)", " ")
                    .replaceAll("(?<!\\d)(20\\d{2})[-/.]([01]?\\d)[-/.]([0-3]?\\d)(?!\\d)", " ");
            List<String[]> slots = new ArrayList<>();
            Matcher ranges = rangePattern.matcher(timeLine);
            while (ranges.find()) slots.add(new String[]{String.format(Locale.ROOT, "%02d:%02d", Integer.parseInt(ranges.group(1)), Integer.parseInt(ranges.group(2))), String.format(Locale.ROOT, "%02d:%02d", Integer.parseInt(ranges.group(3)), Integer.parseInt(ranges.group(4)))});
            if (slots.isEmpty()) {
                Matcher times = timePattern.matcher(timeLine);
                while (times.find()) {
                    int hour = Integer.parseInt(times.group(1));
                    int minute = times.group(2) == null ? 0 : Integer.parseInt(times.group(2).trim());
                    slots.add(new String[]{String.format(Locale.ROOT, "%02d:%02d", hour, minute), ""});
                }
            }
            if (slots.isEmpty()) {
                if (currentDate != null) {
                    String description = cleanDateOnlyDescription(line);
                    if (!description.isEmpty()) {
                        try {
                            JSONObject item = new JSONObject();
                            item.put("date", currentDate);
                            item.put("time", "");
                            item.put("text", description);
                            out.put(item);
                            importedDates.add(currentDate);
                        } catch (Exception ignored) { }
                    }
                }
                continue;
            }
            if (documentHasDates && currentDate == null) continue;
            String description = cleanEventDescription(line);
            for (String[] slot : slots) {
                String title = description.isEmpty() ? "Activité importée" : description;
                if (!slot[1].isEmpty()) title += String.format(Locale.ROOT, " (%s–%s)", slot[0], slot[1]);
                try {
                    JSONObject item = new JSONObject();
                    if (currentDate != null) { item.put("date", currentDate); importedDates.add(currentDate); }
                    item.put("hour", Integer.parseInt(slot[0].substring(0, 2)));
                    item.put("minute", Integer.parseInt(slot[0].substring(3, 5)));
                    item.put("time", slot[0]); item.put("text", title);
                    out.put(item);
                } catch (Exception ignored) { }
            }
        }
        for (String date : explicitDates) if (!importedDates.contains(date)) {
            try {
                JSONObject item = new JSONObject();
                item.put("date", date); item.put("time", ""); item.put("text", "Journée repérée dans le document");
                out.put(item);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static void toast(android.content.Context context, String message) {
        if (context instanceof Activity) ((Activity) context).runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        else Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private void handleBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false", value -> {
            if ("true".equals(value)) return;
            if (webView != null && webView.canGoBack()) webView.goBack();
            else MainActivityCore.super.onBackPressed();
        });
    }

    @Override public void onBackPressed() { handleBackPressed(); }
}
