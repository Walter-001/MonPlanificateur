package ci.monplanificateur;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts local text from common schedule formats before time/task matching. */
final class ScheduleFileReader {
    private ScheduleFileReader() {}

    static String extract(Context context, Uri uri, String name) throws Exception {
        String extension = extension(name);
        File local = copyToCache(context, uri, name);
        try {
            switch (extension) {
                case "doc": return readDoc(local);
                case "docx": return readDocx(local);
                case "xls": return readXls(local);
                case "xlsx": return readXlsx(local);
                case "csv": case "txt": return readUtf8(local);
                case "rtf": return stripRtf(readUtf8(local));
                default: throw new IllegalArgumentException("Format non pris en charge : " + extension);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            local.delete();
        }
    }

    static File copyPdf(Context context, Uri uri, String name) throws Exception {
        return copyToCache(context, uri, name);
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static File copyToCache(Context context, Uri uri, String name) throws Exception {
        String safe = name == null ? "import" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        File file = File.createTempFile("schedule_", "_" + safe, context.getCacheDir());
        try (InputStream in = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IllegalArgumentException("Impossible d’ouvrir le fichier.");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 100L * 1024 * 1024) throw new IllegalArgumentException("Le fichier dépasse la limite de 100 Mo.");
                out.write(buffer, 0, read);
            }
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw error;
        }
        return file;
    }

    private static String readUtf8(File file) throws Exception {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file)); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read; int total = 0;
            while ((read = in.read(buffer)) != -1 && total < 20 * 1024 * 1024) { out.write(buffer, 0, Math.min(read, 20 * 1024 * 1024 - total)); total += read; }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static String stripRtf(String raw) {
        return raw.replaceAll("\\\\'[0-9a-fA-F]{2}", " ")
                .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", " ")
                .replaceAll("[{}]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String readDoc(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file));
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String readDocx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry document = zip.getEntry("word/document.xml");
            if (document == null) throw new IllegalArgumentException("Le document Word ne contient pas de texte lisible.");
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(zip.getInputStream(document), "UTF-8");
            StringBuilder out = new StringBuilder();
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "t".equals(parser.getName())) out.append(parser.nextText());
                else if (event == XmlPullParser.END_TAG && "p".equals(parser.getName())) out.append('\n');
            }
            return out.toString();
        }
    }

    private static String readXls(File file) throws Exception {
        StringBuilder out = new StringBuilder();
        DataFormatter formatter = new DataFormatter(Locale.FRANCE);
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file)); HSSFWorkbook workbook = new HSSFWorkbook(input)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                for (Row row : sheet) {
                    boolean first = true;
                    for (Cell cell : row) {
                        if (!first) out.append(" | ");
                        out.append(formatter.formatCellValue(cell));
                        first = false;
                    }
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String readXlsx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            List<String> shared = readSharedStrings(zip);
            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry candidate = entries.nextElement();
                    if (candidate.getName().matches("xl/worksheets/sheet[0-9]+\\.xml")) { sheet = candidate; break; }
                }
            }
            if (sheet == null) throw new IllegalArgumentException("Le classeur Excel ne contient pas de feuille lisible.");
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(zip.getInputStream(sheet), "UTF-8");
            StringBuilder out = new StringBuilder();
            List<String> row = null;
            String type = "", value = "";
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("row".equals(tag)) row = new ArrayList<>();
                    else if ("c".equals(tag)) { type = parser.getAttributeValue(null, "t"); value = ""; }
                    else if (row != null && ("v".equals(tag) || ("inlineStr".equals(type) && "t".equals(tag)))) value = parser.nextText();
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("c".equals(tag) && row != null) {
                        String cell = value;
                        if ("s".equals(type)) {
                            try { cell = shared.get(Integer.parseInt(value)); } catch (Exception ignored) { }
                        }
                        row.add(cell == null ? "" : cell);
                    } else if ("row".equals(tag) && row != null) {
                        for (int i = 0; i < row.size(); i++) {
                            if (i > 0) out.append(" | ");
                            String cell = row.get(i);
                            if (i == 0) cell = excelTime(cell);
                            out.append(cell);
                        }
                        out.append('\n'); row = null;
                    }
                }
            }
            return out.toString();
        }
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        List<String> result = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return result;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(zip.getInputStream(entry), "UTF-8");
        StringBuilder current = null;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "si".equals(parser.getName())) current = new StringBuilder();
            else if (event == XmlPullParser.START_TAG && current != null && "t".equals(parser.getName())) current.append(parser.nextText());
            else if (event == XmlPullParser.END_TAG && "si".equals(parser.getName()) && current != null) { result.add(current.toString()); current = null; }
        }
        return result;
    }

    private static String excelTime(String value) {
        try {
            double fraction = Double.parseDouble(value);
            if (fraction >= 0 && fraction < 1) {
                int minutes = (int) Math.round(fraction * 24 * 60) % (24 * 60);
                return String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60);
            }
        } catch (Exception ignored) { }
        return value;
    }
}
