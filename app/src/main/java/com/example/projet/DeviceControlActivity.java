package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class DeviceControlActivity extends AppCompatActivity {

    private BleManager bleManager;
    private UiController uiController;
    private TextView statusText;
    private ImageView microphoneIcon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_control);

        microphoneIcon = findViewById(R.id.microphoneIcon);
        Button muteButton = findViewById(R.id.muteButton);
        Button unmuteButton = findViewById(R.id.unmuteButton);
        statusText = findViewById(R.id.statusText);

        uiController = new UiController(this, statusText, null);
        bleManager = new BleManager(this, uiController);

        TextView deviceName = findViewById(R.id.deviceName);
        BluetoothDevice device = getIntent().getParcelableExtra("device");

        if (device != null) {
            // Vérification de la permission avant getName()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    String name = (device.getName() != null) ? device.getName() : "Appareil inconnu";
                    deviceName.setText("Appareil : " + name);
                } catch (SecurityException e) {
                    deviceName.setText("Appareil : permission refusée");
                }

                try {
                    bleManager.connectToDevice(device);
                } catch (SecurityException e) {
                    uiController.showMessage("Connexion refusée : permission manquante");
                }
            } else {
                deviceName.setText("Appareil : permission manquante");
                uiController.showMessage("Permission BLUETOOTH_CONNECT non accordée");
            }
        }

        // Action du bouton Mute
        muteButton.setOnClickListener(v -> {
            statusText.setText("Muted");
            microphoneIcon.setImageResource(R.drawable.mic_off);
            // MicServiceHandler: mute
        });

        // Action du bouton Unmute
        unmuteButton.setOnClickListener(v -> {
            statusText.setText("Unmuted");
            microphoneIcon.setImageResource(R.drawable.mic_on);
            // MicServiceHandler: unmute
        });

        Button disconnectButton = findViewById(R.id.disconnectButton);
        disconnectButton.setOnClickListener(v -> {
            bleManager.disconnect();
            finish(); // revient à MainActivity
        });
    }
}
