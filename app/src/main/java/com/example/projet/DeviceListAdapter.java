package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    private final List<BluetoothDevice> devices;
    private final OnDeviceClickListener onDeviceClickListener;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceListAdapter(List<BluetoothDevice> devices, OnDeviceClickListener onDeviceClickListener) {
        this.devices = devices;
        this.onDeviceClickListener = onDeviceClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDevice device = devices.get(position);
        holder.bind(device, onDeviceClickListener);
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView deviceName;
        private final TextView deviceAddress;

        ViewHolder(View view) {
            super(view);
            deviceName = view.findViewById(R.id.device_name);
            deviceAddress = view.findViewById(R.id.device_address);
        }

        void bind(final BluetoothDevice device, final OnDeviceClickListener listener) {
            if (ActivityCompat.checkSelfPermission(itemView.getContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                 deviceName.setText("Permission requise");
            } else {
                deviceName.setText(device.getName() != null ? device.getName() : "Appareil inconnu");
            }
            deviceAddress.setText(device.getAddress());
            itemView.setOnClickListener(v -> listener.onDeviceClick(device));
        }
    }
}
