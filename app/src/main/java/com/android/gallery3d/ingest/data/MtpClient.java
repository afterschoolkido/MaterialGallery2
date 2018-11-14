/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.ingest.data;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDevice;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This class helps an application manage a list of connected MTP or PTP devices.
 * It listens for MTP devices being attached and removed from the USB host bus
 * and notifies the application when the MTP device list changes.
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
public class MtpClient {

  private static final String TAG = "MtpClient";

  private static final String ACTION_USB_PERMISSION =
      "com.android.gallery3d.ingest.action.USB_PERMISSION";

  private final Context mContext;
  private final UsbManager mUsbManager;
  private final ArrayList<Listener> mListeners = new ArrayList<Listener>();
  // mDevices contains all MtpDevices that have been seen by our client,
  // so we can inform when the device has been detached.
  // mDevices is also used for synchronization in this class.
  private final HashMap<String, MtpDevice> mDevices = new HashMap<String, MtpDevice>();
  // List of MTP devices we should not try to open for which we are currently
  // asking for permission to open.
  private final ArrayList<String> mRequestPermissionDevices = new ArrayList<String>();
  // List of MTP devices we should not try to open.
  // We add devices to this list if the user canceled a permission request or we were
  // unable to open the device.
  private final ArrayList<String> mIgnoredDevices = new ArrayList<String>();

  private final PendingIntent mPermissionIntent;

  private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      UsbDevice usbDevice = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
      String deviceName = usbDevice.getDeviceName();

      synchronized (mDevices) {
        MtpDevice mtpDevice = mDevices.get(deviceName);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
          if (mtpDevice == null) {
            mtpDevice = openDeviceLocked(usbDevice);
          }
          if (mtpDevice != null) {
            for (Listener listener : mListeners) {
              listener.deviceAdded(mtpDevice);
            }
          }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
          if (mtpDevice != null) {
            mDevices.remove(deviceName);
            mRequestPermissionDevices.remove(deviceName);
            mIgnoredDevices.remove(deviceName);
            for (Listener listener : mListeners) {
              listener.deviceRemoved(mtpDevice);
            }
          }
        } else if (ACTION_USB_PERMISSION.equals(action)) {
          mRequestPermissionDevices.remove(deviceName);
          boolean permission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
              false);
          Log.d(TAG, "ACTION_USB_PERMISSION: " + permission);
          if (permission) {
            if (mtpDevice == null) {
              mtpDevice = openDeviceLocked(usbDevice);
            }
            if (mtpDevice != null) {
              for (Listener listener : mListeners) {
                listener.deviceAdded(mtpDevice);
              }
            }
          } else {
            // so we don't ask for permission again
            mIgnoredDevices.add(deviceName);
          }
        }
      }
    }
  };

  /**
   * An interface for being notified when MTP or PTP devices are attached
   * or removed.  In the current implementation, only PTP devices are supported.
   */
  public interface Listener {
    /**
     * Called when a new device has been added
     *
     * @param device the new device that was added
     */
    public void deviceAdded(MtpDevice device);

    /**
     * Called when a new device has been removed
     *
     * @param device the device that was removed
     */
    public void deviceRemoved(MtpDevice device);
  }

  /**
   * Tests to see if a {@link UsbDevice}
   * supports the PTP protocol (typically used by digital cameras)
   *
   * @param device the device to test
   * @return true if the device is a PTP device.
   */
  public static boolean isCamera(UsbDevice device) {
    int count = device.getInterfaceCount();
    for (int i = 0; i < count; i++) {
      UsbInterface intf = device.getInterface(i);
      if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE &&
          intf.getInterfaceSubclass() == 1 &&
          intf.getInterfaceProtocol() == 1) {
        return true;
      }
    }
    return false;
  }

  /**
   * MtpClient constructor
   *
   * @param context the {@link Context} to use for the MtpClient
   */
  public MtpClient(Context context) {
    mContext = context;
    mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    mPermissionIntent = PendingIntent.getBroadcast(mContext, 0,
        new Intent(ACTION_USB_PERMISSION), 0);
    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    filter.addAction(ACTION_USB_PERMISSION);
    context.registerReceiver(mUsbReceiver, filter);
  }

  /**
   * Opens the {@link UsbDevice} for an MTP or PTP
   * device and return an {@link MtpDevice} for it.
   *
   * @param usbDevice the device to open
   * @return an MtpDevice for the device.
   */
  private MtpDevice openDeviceLocked(UsbDevice usbDevice) {
    String deviceName = usbDevice.getDeviceName();

    // don't try to open devices that we have decided to ignore
    // or are currently asking permission for
    if (isCamera(usbDevice) && !mIgnoredDevices.contains(deviceName)
        && !mRequestPermissionDevices.contains(deviceName)) {
      if (!mUsbManager.hasPermission(usbDevice)) {
        mUsbManager.requestPermission(usbDevice, mPermissionIntent);
        mRequestPermissionDevices.add(deviceName);
      } else {
        UsbDeviceConnection connection = mUsbManager.openDevice(usbDevice);
        if (connection != null) {
          MtpDevice mtpDevice = new MtpDevice(usbDevice);
          if (mtpDevice.open(connection)) {
            mDevices.put(usbDevice.getDeviceName(), mtpDevice);
            return mtpDevice;
          } else {
            // so we don't try to open it again
            mIgnoredDevices.add(deviceName);
          }
        } else {
          // so we don't try to open it again
          mIgnoredDevices.add(deviceName);
        }
      }
    }
    return null;
  }

  /**
   * Closes all resources related to the MtpClient object
   */
  public void close() {
    mContext.unregisterReceiver(mUsbReceiver);
  }

  /**
   * Registers a {@link com.android.gallery3d.data.MtpClient.Listener} interface to receive
   * notifications when MTP or PTP devices are added or removed.
   *
   * @param listener the listener to register
   */
  public void addListener(Listener listener) {
    synchronized (mDevices) {
      if (!mListeners.contains(listener)) {
        mListeners.add(listener);
      }
    }
  }

  /**
   * Unregisters a {@link com.android.gallery3d.data.MtpClient.Listener} interface.
   *
   * @param listener the listener to unregister
   */
  public void removeListener(Listener listener) {
    synchronized (mDevices) {
      mListeners.remove(listener);
    }
  }


  /**
   * Retrieves a list of all currently connected {@link MtpDevice}.
   *
   * @return the list of MtpDevices
   */
  public List<MtpDevice> getDeviceList() {
    synchronized (mDevices) {
      // Query the USB manager since devices might have attached
      // before we added our listener.
      for (UsbDevice usbDevice : mUsbManager.getDeviceList().values()) {
        if (mDevices.get(usbDevice.getDeviceName()) == null) {
          openDeviceLocked(usbDevice);
        }
      }

      return new ArrayList<MtpDevice>(mDevices.values());
    }
  }


}
