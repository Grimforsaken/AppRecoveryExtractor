package com.grimforsaken.apprecovery;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int CREATE_ZIP = 2001;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppRow> allApps = new ArrayList<>();
    private final List<AppRow> visibleApps = new ArrayList<>();

    private PackageManager pm;
    private ArrayAdapter<String> listAdapter;
    private CheckBox showSystem;
    private TextView selectedInfo;
    private TextView status;
    private Button exportButton;
    private AppRow selected;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("APP RECOVERY EXTRACTOR");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, fullWrap());

        TextView intro = new TextView(this);
        intro.setText("Select an installed app. The recovery ZIP preserves its base APK, split APKs, metadata, permissions, components, signing fingerprints, checksums, and raw manifests.");
        intro.setPadding(0, dp(6), 0, dp(8));
        root.addView(intro, fullWrap());

        showSystem = new CheckBox(this);
        showSystem.setText("Show system apps");
        showSystem.setOnCheckedChangeListener((b, checked) -> refreshVisible());
        root.addView(showSystem, fullWrap());

        ListView list = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> selectApp(visibleApps.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectedInfo = new TextView(this);
        selectedInfo.setText("No app selected");
        selectedInfo.setPadding(0, dp(8), 0, dp(4));
        root.addView(selectedInfo, fullWrap());

        exportButton = new Button(this);
        exportButton.setText("CREATE RECOVERY PACKAGE");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> chooseDestination());
        root.addView(exportButton, fullWrap());

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, 0);
        root.addView(status, fullWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void loadApps() {
        status.setText("Scanning installed apps…");
        worker.execute(() -> {
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppRow> rows = new ArrayList<>();
            for (ApplicationInfo ai : installed) {
                String label;
                try { label = String.valueOf(pm.getApplicationLabel(ai)); }
                catch (Exception e) { label = ai.packageName; }
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                rows.add(new AppRow(label, ai.packageName, system));
            }
            rows.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(rows);
                refreshVisible();
                status.setText(rows.size() + " installed packages found.");
            });
        });
    }

    private void refreshVisible() {
        if (listAdapter == null) return;
        visibleApps.clear();
        listAdapter.clear();
        boolean systems = showSystem != null && showSystem.isChecked();
        for (AppRow row : allApps) {
            if (!systems && row.system) continue;
            visibleApps.add(row);
            listAdapter.add(row.label + "\n" + row.packageName + (row.system ? "  [system]" : ""));
        }
        listAdapter.notifyDataSetChanged();
    }

    private void selectApp(AppRow row) {
        selected = row;
        exportButton.setEnabled(true);
        try {
            PackageInfo pi = getFullInfo(row.packageName);
            ApplicationInfo ai = pi.applicationInfo;
            int apkCount = 1 + (ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length);
            selectedInfo.setText(row.label + "\n" + row.packageName + "\nVersion: " + versionName(pi) + " (" + versionCode(pi) + ")\nAPK files: " + apkCount + "\nPrivate /data/data contents: protected by Android");
        } catch (Exception e) {
            selectedInfo.setText(row.label + "\n" + row.packageName);
        }
    }

    private PackageInfo getFullInfo(String packageName) throws PackageManager.NameNotFoundException {
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS |
                PackageManager.GET_PERMISSIONS | PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        else flags |= PackageManager.GET_SIGNATURES;
        return pm.getPackageInfo(packageName, flags);
    }

    private void chooseDestination() {
        if (selected == null) return;
        String file = safe(selected.label) + "-Recovery-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, file);
        startActivityForResult(i, CREATE_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportZip(data.getData());
        }
    }

    private void exportZip(Uri destination) {
        AppRow app = selected;
        if (app == null) return;
        exportButton.setEnabled(false);
        status.setText("Building recovery ZIP for " + app.label + "…");
        worker.execute(() -> {
            try {
                PackageInfo pi = getFullInfo(app.packageName);
                List<ApkSource> apks = collectApks(pi.applicationInfo);
                try (OutputStream raw = getContentResolver().openOutputStream(destination, "w")) {
                    if (raw == null) throw new IOException("Could not open destination");
                    try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
                        addText(zip, "README.txt", readme(pi, apks.size()));
                        addText(zip, "metadata/package-info.txt", packageInfo(pi, apks));
                        addText(zip, "metadata/permissions.txt", permissions(pi));
                        addText(zip, "metadata/components.txt", components(pi));
                        addText(zip, "metadata/signing-certificates.txt", signing(pi));
                        addText(zip, "metadata/checksums-sha256.txt", checksums(apks));
                        addText(zip, "metadata/apk-entry-inventory.txt", inventory(apks));
                        addRawManifests(zip, apks);
                        for (ApkSource apk : apks) addFile(zip, "apk/" + apk.name, new File(apk.path));
                    }
                }
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    status.setText("Recovery package created successfully.");
                    Toast.makeText(this, "Recovery ZIP created", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    status.setText("Export failed: " + e.getMessage());
                    Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<ApkSource> collectApks(ApplicationInfo ai) {
        List<ApkSource> out = new ArrayList<>();
        out.add(new ApkSource("base.apk", ai.sourceDir));
        if (ai.splitSourceDirs != null) {
            for (String path : ai.splitSourceDirs) {
                String name = new File(path).getName();
                if (!name.endsWith(".apk")) name += ".apk";
                out.add(new ApkSource(safe(name), path));
            }
        }
        return out;
    }

    private String readme(PackageInfo pi, int apkCount) {
        return "APP RECOVERY EXTRACTOR\n======================\n\nApp: " + label(pi.applicationInfo) + "\nPackage: " + pi.packageName + "\nVersion: " + versionName(pi) + " (" + versionCode(pi) + ")\nAPK files recovered: " + apkCount + "\n\nThis ZIP preserves installed APK files and metadata useful for reconstruction. A normal Android app cannot recover another app's private /data/data files, original source comments, Git history, or the private signing key. Preserve this ZIP unchanged and use JADX/Apktool when rebuilding an app you own or are authorized to inspect.\n";
    }

    private String packageInfo(PackageInfo pi, List<ApkSource> apks) {
        ApplicationInfo ai = pi.applicationInfo;
        StringBuilder b = new StringBuilder();
        b.append("App label: ").append(label(ai)).append('\n');
        b.append("Package: ").append(pi.packageName).append('\n');
        b.append("Version: ").append(versionName(pi)).append(" (").append(versionCode(pi)).append(")\n");
        b.append("Min SDK: ").append(ai.minSdkVersion).append('\n');
        b.append("Target SDK: ").append(ai.targetSdkVersion).append('\n');
        b.append("First install: ").append(new Date(pi.firstInstallTime)).append('\n');
        b.append("Last update: ").append(new Date(pi.lastUpdateTime)).append('\n');
        b.append("Source path: ").append(ai.sourceDir).append('\n');
        b.append("Data path (contents not read): ").append(ai.dataDir).append('\n');
        b.append("APK count: ").append(apks.size()).append('\n');
        return b.toString();
    }

    private String permissions(PackageInfo pi) {
        if (pi.requestedPermissions == null) return "No requested permissions reported.\n";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pi.requestedPermissions.length; i++) {
            boolean granted = pi.requestedPermissionsFlags != null && i < pi.requestedPermissionsFlags.length &&
                    (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            b.append(granted ? "GRANTED  " : "NOT GRANTED  ").append(pi.requestedPermissions[i]).append('\n');
        }
        return b.toString();
    }

    private String components(PackageInfo pi) {
        StringBuilder b = new StringBuilder();
        if (pi.activities != null) for (ActivityInfo x : pi.activities) b.append("ACTIVITY  ").append(x.name).append(" exported=").append(x.exported).append('\n');
        if (pi.services != null) for (ServiceInfo x : pi.services) b.append("SERVICE   ").append(x.name).append(" exported=").append(x.exported).append('\n');
        if (pi.receivers != null) for (ActivityInfo x : pi.receivers) b.append("RECEIVER  ").append(x.name).append(" exported=").append(x.exported).append('\n');
        if (pi.providers != null) for (ProviderInfo x : pi.providers) b.append("PROVIDER  ").append(x.name).append(" exported=").append(x.exported).append('\n');
        return b.toString();
    }

    private String signing(PackageInfo pi) {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                signatures = pi.signingInfo.hasPastSigningCertificates() ? pi.signingInfo.getSigningCertificateHistory() : pi.signingInfo.getApkContentsSigners();
            } else {
                signatures = pi.signatures;
            }
            if (signatures == null || signatures.length == 0) return "No signing certificates reported.\n";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < signatures.length; i++) {
                b.append("Certificate ").append(i + 1).append('\n');
                b.append("SHA-256: ").append(digest(signatures[i].toByteArray(), "SHA-256")).append('\n');
                b.append("SHA-1: ").append(digest(signatures[i].toByteArray(), "SHA-1")).append("\n\n");
            }
            return b.toString();
        } catch (Exception e) {
            return "Signing information unavailable: " + e.getMessage() + "\n";
        }
    }

    private String checksums(List<ApkSource> apks) throws Exception {
        StringBuilder b = new StringBuilder();
        for (ApkSource a : apks) b.append(fileDigest(new File(a.path))).append("  apk/").append(a.name).append('\n');
        return b.toString();
    }

    private String inventory(List<ApkSource> apks) {
        StringBuilder b = new StringBuilder();
        for (ApkSource a : apks) {
            b.append("=== ").append(a.name).append(" ===\n");
            try (ZipFile zf = new ZipFile(a.path)) {
                zf.stream().sorted(Comparator.comparing(ZipEntry::getName)).forEach(e -> b.append(e.getName()).append(" | ").append(e.getSize()).append(" bytes\n"));
            } catch (Exception e) {
                b.append("Unable to inventory: ").append(e.getMessage()).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    private void addRawManifests(ZipOutputStream zip, List<ApkSource> apks) throws IOException {
        for (ApkSource a : apks) {
            try (ZipFile zf = new ZipFile(a.path)) {
                ZipEntry inEntry = zf.getEntry("AndroidManifest.xml");
                if (inEntry == null) continue;
                zip.putNextEntry(new ZipEntry("metadata/raw-manifests/" + safe(a.name) + "-AndroidManifest.xml"));
                try (InputStream in = zf.getInputStream(inEntry)) { copy(in, zip); }
                zip.closeEntry();
            }
        }
    }

    private void addText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void addFile(ZipOutputStream zip, String name, File file) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(file.lastModified());
        zip.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { copy(in, zip); }
        zip.closeEntry();
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private String fileDigest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        return hex(md.digest());
    }

    private String digest(byte[] bytes, String algorithm) throws Exception {
        return hex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) {
            if (b.length() > 0) b.append(':');
            b.append(String.format(Locale.US, "%02X", x));
        }
        return b.toString();
    }

    private String label(ApplicationInfo ai) {
        try { return String.valueOf(pm.getApplicationLabel(ai)); }
        catch (Exception e) { return ai.packageName; }
    }

    private String versionName(PackageInfo pi) {
        return pi.versionName == null ? "unknown" : pi.versionName;
    }

    private long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private String safe(String input) {
        String s = input == null ? "app" : input.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (s.isEmpty()) s = "app";
        return s;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static class AppRow {
        final String label;
        final String packageName;
        final boolean system;
        AppRow(String label, String packageName, boolean system) {
            this.label = label;
            this.packageName = packageName;
            this.system = system;
        }
    }

    private static class ApkSource {
        final String name;
        final String path;
        ApkSource(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }
}
