/* Copyright (C) 2013 Peter Brinkmann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.noisepages.nettoyeur.midi.player;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.noisepages.nettoyeur.bluetooth.BluetoothDisabledException;
import com.noisepages.nettoyeur.bluetooth.BluetoothSppObserver;
import com.noisepages.nettoyeur.bluetooth.BluetoothUnavailableException;
import com.noisepages.nettoyeur.bluetooth.midi.BluetoothMidiDevice;
import com.noisepages.nettoyeur.bluetooth.util.DeviceListActivity;
import com.noisepages.nettoyeur.midi.MidiDevice;
import com.noisepages.nettoyeur.midi.MidiReceiver;
import com.noisepages.nettoyeur.midi.file.InvalidMidiDataException;
import com.noisepages.nettoyeur.midi.player.MidiPlayerService.ConnectionType;
import com.noisepages.nettoyeur.usb.ConnectionFailedException;
import com.noisepages.nettoyeur.usb.DeviceNotConnectedException;
import com.noisepages.nettoyeur.usb.InterfaceNotAvailableException;
import com.noisepages.nettoyeur.usb.UsbBroadcastHandler;
import com.noisepages.nettoyeur.usb.midi.UsbMidiDevice;
import com.noisepages.nettoyeur.usb.midi.UsbMidiDevice.UsbMidiOutput;
import com.noisepages.nettoyeur.usb.midi.util.UsbMidiOutputSelector;
import com.noisepages.nettoyeur.usb.util.AsyncDeviceInfoLookup;
import com.noisepages.nettoyeur.usb.util.UsbDeviceSelector;


/**
 * Simple activity for playing MIDI files over Bluetooth or USB.
 * 
 * @author Peter Brinkmann
 */
public class MidiPlayer extends Activity implements BluetoothSppObserver, OnClickListener {

	private static final int CONNECT = 1;

	private MidiPlayerService midiService = null;
	private MidiDevice midiDevice = null;
	private Uri uri = null;
	private Toast toast = null;
	private Button connectBluetoothButton;
	private Button connectUsbButton;
	private ImageButton playButton;
	private ImageButton rewindButton;
	private TextView uriView;

	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			midiService = ((MidiPlayerService.MidiPlayerServiceBinder) service).getService();
			uri = getIntent().getData();
			if (uri != null) {
				try {
					InputStream is = getContentResolver().openInputStream(uri);
					midiService.setMidiSequence(new MidiSequence(is) {
						@Override
						protected void onPlaybackFinished() {
							toast("Playback finished");
							updateWidgets();
						}
					});
				} catch (FileNotFoundException e) {
					toast(e.getMessage());
					finish();
				} catch (InvalidMidiDataException e) {
					toast(e.getMessage());
					finish();
				} catch (IOException e) {
					toast(e.getMessage());
					finish();
				}
			} else {
				toast("No URI to read MIDI data from");
				finish();
			}
			updateWidgets();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			// this method will never be called
		}
	};

	private void toast(final String msg) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (toast == null) {
					toast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_SHORT);
				}
				toast.setText(msg);
				toast.show();
			}
		});
	}

	private boolean usbAvailable() {
		return Build.VERSION.SDK_INT >= 12;
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (usbAvailable()) {
			setContentView(R.layout.main_usb);
			connectUsbButton = (Button) findViewById(R.id.connectUsbButton);
			connectUsbButton.setOnClickListener(this);
			installBroadcastHandler();
		} else {
			setContentView(R.layout.main);
		}
		connectBluetoothButton = (Button) findViewById(R.id.connectButton);
		playButton = (ImageButton) findViewById(R.id.playButton);
		rewindButton = (ImageButton) findViewById(R.id.rewindButton);
		uriView = (TextView) findViewById(R.id.uriView);
		connectBluetoothButton.setOnClickListener(this);
		playButton.setOnClickListener(this);
		rewindButton.setOnClickListener(this);
		uriView.setText(R.string.loading);
		bindService(new Intent(this, MidiPlayerService.class), connection, BIND_AUTO_CREATE);
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
	private void installBroadcastHandler() {
		UsbMidiDevice.installBroadcastHandler(this, new UsbBroadcastHandler() {
			@Override
			public void onPermissionGranted(UsbDevice device) {
				try {
					((UsbMidiDevice) midiDevice).open(MidiPlayer.this);
				} catch (ConnectionFailedException e) {
					toast("Connection failed");
					return;
				}
				new UsbMidiOutputSelector((UsbMidiDevice) midiDevice) {
					@Override
					protected void onOutputSelected(UsbMidiOutput output, UsbMidiDevice device, int iface, int index) {
						toast("Output selection: Interface " + iface + ", Output " + index);
						try {
							midiService.connectUsb(device, output.getMidiOut());
						} catch (DeviceNotConnectedException e) {
							midiService.reset();
							toast("Device not connected");
						} catch (InterfaceNotAvailableException e) {
							midiService.reset();
							toast("Interface not available");
						}
						updateWidgets();
					}
					
					@Override
					protected void onNoSelection(UsbMidiDevice device) {
						toast("No output selected");
					}
				}.show(getFragmentManager(), null);
			}

			@Override
			public void onPermissionDenied(UsbDevice device) {
				toast("USB permission denied");
			}

			@Override
			public void onDeviceDetached(UsbDevice device) {
				if (midiService.getConnectionType() == ConnectionType.USB) {
					midiService.reset();
					updateWidgets();
				}
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		updateWidgets();
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (midiService != null) {
			midiService.reset();
			unbindService(connection);
			midiService = null;
		}
		if (usbAvailable()) {
			UsbMidiDevice.uninstallBroadcastHandler(this);
		}
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case CONNECT:
			if (resultCode == Activity.RESULT_OK) {
				connectBluetoothMidi(data.getExtras().getString(DeviceListActivity.DEVICE_ADDRESS));
			}
			break;
		}
	}

	private void connectBluetoothMidi(String address) {
		try {
			BluetoothMidiDevice device;
			device = new BluetoothMidiDevice(this, new MidiReceiver.DummyReceiver());
			device.connect(address);
			midiDevice = device;
		} catch (BluetoothUnavailableException e) {
			toast(e.getMessage());
		} catch (BluetoothDisabledException e) {
			toast(e.getMessage());
		} catch (IOException e) {
			toast(e.getMessage());
		}
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void connectUsbDevice() {
		final List<UsbMidiDevice> devices = UsbMidiDevice.getMidiDevices(this);
		new AsyncDeviceInfoLookup() {
			@Override
			protected void onLookupComplete() {
				new UsbDeviceSelector<UsbMidiDevice>(devices) {
					
					@Override
					protected void onDeviceSelected(UsbMidiDevice device) {
						midiDevice = device;
						device.requestPermission(MidiPlayer.this);
					}
					
					@Override
					protected void onNoSelection() {
						toast("No device selected");
					}
				}.show(getFragmentManager(), null);
			}
		}.execute(devices.toArray(new UsbMidiDevice[devices.size()]));
	}

	@Override
	public void onDeviceConnected(BluetoothDevice device) {
		toast("Device connected: " + device);
		midiService.connectBluetooth(midiDevice, ((BluetoothMidiDevice) midiDevice).getMidiOut());
		updateWidgets();
	}

	@Override
	public void onConnectionFailed() {
		toast("Connection failed");
		midiService.reset();
		updateWidgets();
	}

	@Override
	public void onConnectionLost() {
		toast("Connection terminated");
		midiService.reset();
		updateWidgets();
	}

	@Override
	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.connectButton:
			if (midiService.getConnectionType() != ConnectionType.BLUETOOTH) {
				startActivityForResult(new Intent(MidiPlayer.this, DeviceListActivity.class), CONNECT);
			} else {
				midiService.reset();
			}
			break;
		case R.id.connectUsbButton:
			if (midiService.getConnectionType() != ConnectionType.USB) {
				connectUsbDevice();
			} else {
				midiService.reset();
			}
			break;
		case R.id.playButton:
			if (midiService.getConnectionType() != ConnectionType.NONE && !midiService.isPlaying()) {
				midiService.start();
			} else {
				midiService.pause();
			}
			break;
		case R.id.rewindButton:
			midiService.rewind();
			break;
		default:
			break;
		}
		updateWidgets();
	}

	private void updateWidgets() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				boolean connected = midiService != null;
				ConnectionType ct = connected ? midiService.getConnectionType() : ConnectionType.NONE;
				connectBluetoothButton.setText(ct == ConnectionType.BLUETOOTH ? R.string.disconnect : R.string.connect);
				connectBluetoothButton.setEnabled(connected);
				if (connectUsbButton != null) {
					connectUsbButton.setText(ct == ConnectionType.USB ? R.string.disconnect_usb : R.string.connect_usb);
					connectUsbButton.setEnabled(connected);
				}
				playButton.setEnabled(ct != ConnectionType.NONE);
				rewindButton.setEnabled(ct != ConnectionType.NONE);
				playButton.setImageResource(connected && midiService.isPlaying() ?
						android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
				uriView.setText(uri == null ? "---" : uri.toString());
			}
		});
	}
}