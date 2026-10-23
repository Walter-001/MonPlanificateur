package ci.monplanificateur;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class AlarmReceiver extends BroadcastReceiver {
    static final String PREFS = "planner_alarms";
    static final String CHANNEL = "daily_planner_reminders";
    static final String CHANNEL_SOUND = "daily_planner_reminders_sound";
    static final String CHANNEL_SILENT = "daily_planner_reminders_silent";
    static final String ACTION_SNOOZE = "ci.monplanificateur.SNOOZE";

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_SNOOZE.equals(intent.getAction())) {
            int id = intent.getIntExtra("id", 0);
            cancelNotification(context, id);
            String date = intent.getStringExtra("date"), eventId = intent.getStringExtra("eventId");
            String time = intent.getStringExtra("time"), title = intent.getStringExtra("title");
            if (date != null && eventId != null && time != null) {
                scheduleAt(context, date, eventId, time, title, intent.getBooleanExtra("sound", true), System.currentTimeMillis() + 10 * 60 * 1000L, true);
                MainActivity.toast(context, "Rappel reporté de 10 minutes.");
            }
            return;
        }
        int id = intent.getIntExtra("id", 0);
        String title = intent.getStringExtra("title");
        String date = intent.getStringExtra("date"), eventId = intent.getStringExtra("eventId");
        String time = intent.getStringExtra("time");
        showNotification(context, id, date, eventId, time, title == null || title.isEmpty() ? "Une activité t’attend" : title, intent.getBooleanExtra("sound", true));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("alarm_" + id).apply();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel audible = new NotificationChannel(CHANNEL_SOUND, "Rappels avec son", NotificationManager.IMPORTANCE_HIGH);
                audible.setDescription("Rappels qui font sonner le téléphone");
                audible.enableVibration(true);
                NotificationChannel silent = new NotificationChannel(CHANNEL_SILENT, "Rappels silencieux", NotificationManager.IMPORTANCE_HIGH);
                silent.setDescription("Rappels sans son");
                silent.setSound(null, null);
                silent.enableVibration(false);
                manager.createNotificationChannel(audible);
                manager.createNotificationChannel(silent);
            }
        }
    }

    static void showNotification(Context context, int id, String date, String eventId, String time, String title, boolean sound) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("reminderDate", date).putExtra("reminderEventId", eventId).putExtra("reminderNotificationId", id);
        PendingIntent tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent done = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plannerAction", "done").putExtra("reminderDate", date).putExtra("reminderEventId", eventId).putExtra("reminderNotificationId", id);
        PendingIntent doneAction = PendingIntent.getActivity(context, id ^ 0x31A4, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent snooze = new Intent(context, AlarmReceiver.class).setAction(ACTION_SNOOZE)
                .putExtra("id", id).putExtra("date", date).putExtra("eventId", eventId).putExtra("time", time).putExtra("title", title).putExtra("sound", sound);
        PendingIntent snoozeAction = PendingIntent.getBroadcast(context, id ^ 0x10A4, snooze, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, sound ? CHANNEL_SOUND : CHANNEL_SILENT) : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_stat_plan)
                .setContentTitle("Mon planificateur")
                .setContentText(title)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setDefaults(Build.VERSION.SDK_INT < Build.VERSION_CODES.O && sound ? Notification.DEFAULT_ALL : 0)
                .addAction(0, "Fait", doneAction)
                .addAction(0, "+10 min", snoozeAction);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(id, builder.build());
    }

    static void cancelNotification(Context context, int id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(id);
    }

    static int alarmId(String date, String eventId) { return Objects.hash(date, eventId); }
    static int legacyAlarmId(String date, int slot) { return Objects.hash(date, slot); }

    static void schedule(Context context, String date, String eventId, String time, String title) { schedule(context, date, eventId, time, title, true); }
    static void schedule(Context context, String date, String eventId, String time, String title, boolean sound) {
        long trigger = triggerAt(date, time);
        scheduleAt(context, date, eventId, time, title, sound, trigger, true);
    }

    static void scheduleQuiet(Context context, String date, String eventId, String time, String title) { scheduleQuiet(context, date, eventId, time, title, true); }
    static void scheduleQuiet(Context context, String date, String eventId, String time, String title, boolean sound) {
        try { scheduleAt(context, date, eventId, time, title, sound, triggerAt(date, time), false); }
        catch (Exception ignored) { }
    }

    private static void scheduleAt(Context context, String date, String eventId, String time, String title, boolean sound, long trigger, boolean notify) {
        if (trigger <= System.currentTimeMillis()) {
            if (notify) MainActivity.toast(context, "Choisis une heure future pour programmer ce rappel.");
            return;
        }
        int id = alarmId(date, eventId);
        Intent intent = new Intent(context, AlarmReceiver.class).putExtra("id", id).putExtra("date", date)
                .putExtra("eventId", eventId).putExtra("time", time).putExtra("title", title).putExtra("sound", sound);
        PendingIntent operation = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms();
        if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
        String value = date + "|" + eventId + "|" + time + "|" + (title == null ? "" : title) + "|" + sound;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alarm_" + id, value).apply();
        if (notify) MainActivity.toast(context, exact ? "Rappel programmé à " + time : "Rappel programmé; Android peut décaler son heure.");
    }

    static void cancel(Context context, String date, String eventId) {
        int id = alarmId(date, eventId);
        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent operation = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (operation != null && manager != null) manager.cancel(operation);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("alarm_" + id).apply();
        cancelNotification(context, id);
    }

    static void cancelLegacy(Context context, String date, int slot) {
        int id = legacyAlarmId(date, slot);
        PendingIntent operation = PendingIntent.getBroadcast(context, id, new Intent(context, AlarmReceiver.class), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (operation != null && manager != null) manager.cancel(operation);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("alarm_" + id).apply();
    }

    private static long triggerAt(String date, String time) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            Date parsed = format.parse(date);
            if (parsed == null) throw new IllegalArgumentException("Date invalide");
            String[] parts = time.split(":");
            if (parts.length != 2) throw new IllegalArgumentException("Heure invalide");
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(parsed);
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
            calendar.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (ParseException error) { throw new IllegalArgumentException("Date invalide", error); }
    }

    static void rescheduleAll(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object raw = entry.getValue();
            if (!(raw instanceof String)) continue;
            String[] parts = ((String) raw).split("\\|", 5);
            try {
                if (parts.length >= 4) scheduleAt(context, parts[0], parts[1], parts[2], parts[3], parts.length < 5 || Boolean.parseBoolean(parts[4]), triggerAt(parts[0], parts[2]), false);
                else if (parts.length == 3) {
                    int slot = Integer.parseInt(parts[1]);
                    if (slot >= 0 && slot <= 15) scheduleLegacy(context, parts[0], slot, parts[2]);
                }
            } catch (Exception ignored) { preferences.edit().remove(entry.getKey()).apply(); }
        }
    }

    private static void scheduleLegacy(Context context, String date, int slot, String title) {
        String time = String.format(Locale.US, "%02d:00", slot + 6);
        long trigger = triggerAt(date, time);
        if (trigger <= System.currentTimeMillis()) return;
        int id = legacyAlarmId(date, slot);
        Intent intent = new Intent(context, AlarmReceiver.class).putExtra("id", id).putExtra("date", date)
                .putExtra("eventId", "legacy-" + slot).putExtra("time", time).putExtra("title", title);
        PendingIntent operation = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
    }
}
