package com.example.projet;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PermissionsActivity extends AppCompatActivity {

    private static final String TAG = "PermissionsActivity";

    private ActivityResultLauncher<String[]> requestMultipleLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register the launcher
        requestMultipleLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionsResult
        );

        // Immediately start the permission flow
        startPermissionsFlow();
    }

    private void startPermissionsFlow() {
        List<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // At least FINE_LOCATION is needed on M+ (for old devices)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Optional background location (only request if app needs it). We'll request if declared.
            // We won't force it here; leave for specific flows.
            // perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ needs BLUETOOTH_SCAN and BLUETOOTH_CONNECT for BLE scanning/connecting
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            // BLUETOOTH_ADVERTISE not necessary for client, but include if declared
            // perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Optional: notification permission (if you use foreground service notifications)
            // perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Filter already granted
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (p == null) continue;
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }

        if (toRequest.isEmpty()) {
            Log.d(TAG, "All permissions already granted");
            finish();
            return;
        }

        // Decide whether to show rationale for the first permission
        String first = toRequest.get(0);
        boolean showRationale = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showRationale = shouldShowRequestPermissionRationale(first);
        }

        if (showRationale) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission requise")
                    .setMessage("L'application a besoin des permissions Bluetooth et/ou localisation pour scanner et se connecter aux périphériques BLE. Veux-tu autoriser ?")
                    .setPositiveButton("OK", (d, w) -> requestMultipleLauncher.launch(toRequest.toArray(new String[0])))
                    .setNegativeButton("Annuler", (d, w) -> finish())
                    .show();
        } else {
            // Launch request directly
            requestMultipleLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        boolean allGranted = true;
        boolean anyDeniedPermanently = false;

        for (Map.Entry<String, Boolean> e : result.entrySet()) {
            String perm = e.getKey();
            boolean granted = e.getValue() != null && e.getValue();
            if (!granted) {
                allGranted = false;
                // If shouldShowRequestPermissionRationale returns false and the permission is not granted,
                // then the user probably selected "Don't ask again"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !shouldShowRequestPermissionRationale(perm)) {
                    anyDeniedPermanently = true;
                }
            }
        }

        if (allGranted) {
            Log.d(TAG, "PermissionsActivity: all granted");
            finish();
            return;
        }

        if (anyDeniedPermanently) {
            // Show dialog with option to open app settings (MIUI-specific settings handled by existing helper in MainActivity/DeviceControlActivity)
            new AlertDialog.Builder(this)
                    .setTitle("Permissions manquantes")
                    .setMessage("Certaines permissions ont été refusées définitivement. Ouvrir les paramètres de l'application pour les activer ?")
                    .setPositiveButton("Paramètres", (d, w) -> openAppSettings())
                    .setNegativeButton("Annuler", (d, w) -> finish())
                    .show();
            return;
        }

        // Some denied but not permanently — inform user
        new AlertDialog.Builder(this)
                .setTitle("Permissions requises")
                .setMessage("Sans ces permissions l'application ne pourra pas scanner/connecter les périphériques BLE.")
                .setPositiveButton("Réessayer", (d, w) -> startPermissionsFlow())
                .setNegativeButton("Annuler", (d, w) -> finish())
                .show();
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'ouvrir les paramètres de l'application", e);
        }
    }
}

