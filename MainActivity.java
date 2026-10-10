package com.msdl.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вводишь ссылку на приложение из Microsoft Store, приложение получает
 * прямые ссылки на пакеты (appx / msix) и скачивает выбранный файл.
 * Ссылки генерирует сервис store.rg-adguard.net.
 */
public class MainActivity extends Activity {

    private static class Item {
        String name;
        String url;
        String size;
    }

    private static final String API = "https://store.rg-adguard.net/api/GetFiles";
    private static final String[] RING_NAMES = {"Retail (обычный)", "RP (Release Preview)", "WIS (Slow)", "WIF (Fast)"};
    private static final String[] RING_VALUES = {"Retail", "RP", "WIS", "WIF"};
    private static final Pattern PKG_EXT =
            Pattern.compile("\\.e?(appx|msix)(bundle)?$", Pattern.CASE_INSENSITIVE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();

    private EditText input;
    private Spinner ring;
    private TextView status;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView hint = new TextView(this);
        hint.setText("Ссылка на приложение в Microsoft Store");
        hint.setTextSize(16);
        root.addView(hint);

        input = new EditText(this);
        input.setHint("https://apps.microsoft.com/detail/...");
        input.setSingleLine(true);
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button paste = new Button(this);
        paste.setText("Вставить");
        paste.setOnClickListener(v -> pasteFromClipboard());
        row.addView(paste, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button find = new Button(this);
        find.setText("Найти файлы");
        find.setOnClickListener(v -> search());
        row.addView(find, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row);

        ring = new Spinner(this);
        ring.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RING_NAMES));
        root.addView(ring);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setText("Вставьте ссылку и нажмите «Найти файлы». Нажатие на файл в списке запускает скачивание.");
        root.addView(status);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) {
                download(items.get(position));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) {
            input.setText(m.group());
            search();
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null) input.setText(t.toString().trim());
            }
        }
    }

    private void search() {
        final String link = input.getText().toString().trim();
        if (link.isEmpty()) {
            status.setText("Сначала вставьте ссылку.");
            return;
        }
        final String ringValue = RING_VALUES[ring.getSelectedItemPosition()];
        status.setText("Ищу файлы…");
        items.clear();
        adapter.clear();

        new Thread(() -> {
            try {
                String html = post(link, ringValue);
                List<Item> found = parse(html);
                ui.post(() -> showResults(found));
            } catch (Exception e) {
                ui.post(() -> status.setText("Ошибка: " + e.getMessage()));
            }
        }).start();
    }

    private String post(String link, String ringValue) throws Exception {
        String body = "type=url&url=" + URLEncoder.encode(link, "UTF-8")
                + "&ring=" + ringValue + "&lang=ru-RU";
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("сервер ответил кодом " + code);
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private List<Item> parse(String html) {
        List<Item> out = new ArrayList<>();
        Pattern a = Pattern.compile("<a\\s+href=\"([^\"]+)\"[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE);
        Pattern td = Pattern.compile("<td[^>]*>([^<]*)</td>", Pattern.CASE_INSENSITIVE);
        Matcher m = a.matcher(html);
        while (m.find()) {
            String name = m.group(2).trim();
            if (!PKG_EXT.matcher(name).find()) continue;

            Item it = new Item();
            it.name = name;
            it.url = m.group(1).replace("&amp;", "&");

            int end = html.indexOf("</tr>", m.end());
            if (end < 0) end = Math.min(html.length(), m.end() + 800);
            Matcher t = td.matcher(html.substring(m.end(), end));
            String last = "";
            while (t.find()) last = t.group(1).trim();
            it.size = last;
            out.add(it);
        }
        return out;
    }

    private void showResults(List<Item> found) {
        items.clear();
        items.addAll(found);
        adapter.clear();
        for (Item it : found) {
            adapter.add(it.size.isEmpty() ? it.name : it.name + "\n" + it.size);
        }
        adapter.notifyDataSetChanged();
        if (found.isEmpty()) {
            status.setText("Файлов не найдено. Проверьте ссылку (платные приложения и приложения без прямых пакетов не поддерживаются) или попробуйте другой канал.");
        } else {
            status.setText("Найдено файлов: " + found.size()
                    + ". В списке есть и зависимости (VCLibs, .NET и т.п.). Основной файл — с именем самого приложения. Нажмите на файл для скачивания.");
        }
    }

    private void download(Item it) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(it.url));
            req.setTitle(it.name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, it.name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "Скачивание началось: Загрузки/" + it.name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            status.setText("Не удалось начать скачивание: " + e.getMessage());
        }
    }
}
