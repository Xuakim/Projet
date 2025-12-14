package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

public class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {

    private final Context context;

    public DeviceListAdapter(Context context, List<BluetoothDevice> devices) {
        super(context, android.R.layout.simple_list_item_1, devices);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        BluetoothDevice device = getItem(position);
        TextView view = (TextView) super.getView(position, convertView, parent);

        String name = "Appareil inconnu";
        String address = "";

        // Vérification de la permission BLUETOOTH_CONNECT
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                if (device.getName() != null) {
                    name = device.getName();
                }
                address = device.getAddress();
            } catch (SecurityException e) {
                // Si jamais la permission est refusée
                name = "Permission refusée";
                address = "";
            }
        } else {
            name = "Permission manquante";
        }

        view.setText(name + (address.isEmpty() ? "" : " (" + address + ")"));
        return view;
    }
}
