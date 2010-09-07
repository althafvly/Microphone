package net.bitplane.android.microphone;

import java.nio.ByteBuffer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

public class MicrophoneService extends Service implements OnSharedPreferenceChangeListener {
	
	private static final String APP_TAG = "Microphone";
	private static final int mSampleRate = 44100;
	private static final int mFormat     = AudioFormat.ENCODING_PCM_16BIT;
	
	private AudioTrack              mAudioOutput;
	private AudioRecord             mAudioInput;
	private int                     mInBufferSize;
	private int                     mOutBufferSize;
	SharedPreferences               mSharedPreferences;
	private static boolean          mActive = false;
	private NotificationManager     mNotificationManager;
	
	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}
	
    @Override
    public void onCreate() {
    	
    	Log.d(APP_TAG, "Creating mic service"); 
    	
    	// notification service
    	mNotificationManager  = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    	
    	// create input and output streams
        mInBufferSize  = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO, mFormat);
        mOutBufferSize = AudioTrack.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO, mFormat);
        mAudioInput = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO, mFormat, mInBufferSize);
        mAudioOutput = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO, mFormat, mOutBufferSize, AudioTrack.MODE_STREAM);
    	
    	// listen for preference changes
    	mSharedPreferences = getSharedPreferences(APP_TAG, MODE_PRIVATE);
    	mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
    	mActive = mSharedPreferences.getBoolean("active", false);
    	
    	if (mActive)
    		record();
    }
    
    @Override
    public void onDestroy() {
    	Log.d(APP_TAG, "Stopping mic service"); 
    	
    	// close the service
    	SharedPreferences.Editor e = mSharedPreferences.edit();
    	e.putBoolean("active", false);
    	e.commit();
    	
    	// disable the listener
    	mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    	
    	mAudioInput.release();
    	mAudioOutput.release();
    }
    
	@Override
    public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		Log.d(APP_TAG, "Service sent intent");
		
		// if this is a stop request, cancel the recording
		if (intent != null && intent.getAction() != null) {
			if (intent.getAction().equals("net.bitplane.android.microphone.STOP")) {
				Log.d(APP_TAG, "Cancelling recording via notification click");
				SharedPreferences.Editor e = mSharedPreferences.edit();
	        	e.putBoolean("active", false);
	        	e.commit();
			}
		}
	}
    
	
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		// intercept the preference change.
		
		if (!key.equals("active"))
			return;
		
		boolean bActive = sharedPreferences.getBoolean("active", false);
		
		Log.d(APP_TAG, "Mic state changing (from " + mActive + " to " + bActive + ")"); 
		
		if (bActive != mActive) {
		
			mActive = bActive;
			
			if (mActive)
				record();
		}
	}
	
	public void record() {
		Thread t = new Thread() {
			public void run() {
				
				Context       context             = getApplicationContext();
				CharSequence  titleText           = getString(R.string.mic_active);
				CharSequence  statusText          = getString(R.string.cancel_mic);
		        long          when                = System.currentTimeMillis();
		        Intent        cancelIntent        = new Intent();
		        cancelIntent.setAction("net.bitplane.android.microphone.STOP");
		        cancelIntent.setData(Uri.parse("null://null"));
		        cancelIntent.setFlags(cancelIntent.getFlags() | Notification.FLAG_AUTO_CANCEL);
		        PendingIntent pendingCancelIntent = PendingIntent.getService(context, 0, cancelIntent, 0);
		        Notification notification         = new Notification(R.drawable.status, titleText, when);
				notification.setLatestEventInfo(context, titleText, statusText, pendingCancelIntent);
				mNotificationManager.notify(0, notification);
				
				Log.d(APP_TAG, "Entered record loop");
				
				if ( mAudioOutput.getState() != AudioTrack.STATE_INITIALIZED || mAudioInput.getState() != AudioTrack.STATE_INITIALIZED) {
					Log.d(APP_TAG, "Can't start. Race condition?");
				}
				else {
					
					try {
					
						try { mAudioOutput.play(); }          catch (Exception e) { Log.d(APP_TAG, "Failed to start playback"); return; }
						try { mAudioInput.startRecording(); } catch (Exception e) { Log.d(APP_TAG, "Failed to start recording"); mAudioOutput.stop(); return; }
						
						try {
					
					        ByteBuffer bytes = ByteBuffer.allocateDirect(mInBufferSize);
					        int o = 0;
						        
					        while(mActive) {
					        	o = mAudioInput.read(bytes, mInBufferSize);
					        	byte b[] = new byte[o];
					        	bytes.get(b);
					        	bytes.rewind();
					        	mAudioOutput.write(b, 0, o);
					        }
					        
					        Log.d(APP_TAG, "Finished recording");
						}
						catch (Exception e) {
							Log.d(APP_TAG, "Error while recording, aborting.");
						}
			        
				        try { mAudioOutput.stop(); } catch (Exception e) { Log.d(APP_TAG, "Can't stop playback"); mAudioInput.stop(); return; }
				        try { mAudioInput.stop();  } catch (Exception e) { Log.d(APP_TAG, "Can't stop recording"); return; }
					}
					catch (Exception e) {
						Log.d(APP_TAG, "Error somewhere in record loop.");				
					}
				}
				// cancel notification
				mNotificationManager.cancel(0);

				Log.d(APP_TAG, "Record loop finished");
			}
		};
		
		t.start();
		
	}
}
