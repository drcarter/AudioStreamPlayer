package com.magimon.decoceaudio.decoder;

import java.io.IOException;
import java.nio.ByteBuffer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class AudioStreamPlayer
{
	private static final String TAG = "AudioStreamPlayer";

	private MediaExtractor mExtractor = null;
	private MediaCodec mMediaCodec = null;
	private AudioTrack mAudioTrack = null;

	private int mInputBufIndex = 0;

	private boolean isForceStop = false;
	private volatile boolean isPause = false;

	protected OnAudioStreamInterface mListener = null;

	public void setOnAudioStreamInterface(OnAudioStreamInterface listener)
	{
		this.mListener = listener;
	}

	public enum State
	{
		Stopped, Prepare, Buffering, Playing, Pause
	};

	State mState = State.Stopped;

	public State getState()
	{
		return mState;
	}

	private String mMediaPath;

	public void setUrlString(String mUrlString)
	{
		this.mMediaPath = mUrlString;
	}

	public AudioStreamPlayer()
	{
		mState = State.Stopped;
	}

	public void play() throws IOException
	{
		mState = State.Prepare;
		isForceStop = false;

		mAudioPlayerHandler.onAudioPlayerBuffering(AudioStreamPlayer.this);

		new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				decodeLoop();
			}
		}).start();
	}

	private DelegateHandler mAudioPlayerHandler = new DelegateHandler();

	class DelegateHandler extends Handler
	{
		@Override
		public void handleMessage(Message msg)
		{
		}

		public void onAudioPlayerPlayerStart(AudioStreamPlayer player)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerStart(player);
			}
		}

		public void onAudioPlayerStop(AudioStreamPlayer player)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerStop(player);
			}
		}

		public void onAudioPlayerError(AudioStreamPlayer player)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerError(player);
			}
		}

		public void onAudioPlayerBuffering(AudioStreamPlayer player)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerBuffering(player);
			}
		}

		public void onAudioPlayerDuration(int totalSec)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerDuration(totalSec);
			}
		}

		public void onAudioPlayerCurrentTime(int sec)
		{
			if (mListener != null)
			{
				mListener.onAudioPlayerCurrentTime(sec);
			}
		}

		public void onAudioPlayerPause()
		{
			if(mListener != null)
			{
				mListener.onAudioPlayerPause(AudioStreamPlayer.this);
			}
		}
	};

	private void decodeLoop()
	{
		ByteBuffer[] codecInputBuffers;
		ByteBuffer[] codecOutputBuffers;

		mExtractor = new MediaExtractor();
		try
		{
			mExtractor.setDataSource(this.mMediaPath);
		}
		catch (Exception e)
		{
			mAudioPlayerHandler.onAudioPlayerError(AudioStreamPlayer.this);
			return;
		}

		MediaFormat format = mExtractor.getTrackFormat(0);
		String mime = format.getString(MediaFormat.KEY_MIME);
		long duration = format.getLong(MediaFormat.KEY_DURATION);
		int totalSec = (int) (duration / 1000 / 1000);
		int min = totalSec / 60;
		int sec = totalSec % 60;

		mAudioPlayerHandler.onAudioPlayerDuration(totalSec);

		Log.d(TAG, "Time = " + min + " : " + sec);
		Log.d(TAG, "Duration = " + duration);

		mMediaCodec = MediaCodec.createDecoderByType(mime);
		mMediaCodec.configure(format, null, null, 0);
		mMediaCodec.start();
		codecInputBuffers = mMediaCodec.getInputBuffers();
		codecOutputBuffers = mMediaCodec.getOutputBuffers();

		int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);

		Log.i(TAG, "mime " + mime);
		Log.i(TAG, "sampleRate " + sampleRate);

		mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
						AudioFormat.ENCODING_PCM_16BIT), AudioTrack.MODE_STREAM);

		mAudioTrack.play();
		mExtractor.selectTrack(0);

		final long kTimeOutUs = 10000;
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		boolean sawInputEOS = false;
		int noOutputCounter = 0;
		int noOutputCounterLimit = 50;

		while (!sawInputEOS && noOutputCounter < noOutputCounterLimit && !isForceStop)
		{
			if (!sawInputEOS)
			{
				if(isPause)
				{
					if(mState != State.Pause)
					{
						mState = State.Pause;
						
						mAudioPlayerHandler.onAudioPlayerPause();
					}
					continue;
				}
				noOutputCounter++;
				if (isSeek)
				{
					mExtractor.seekTo(seekTime * 1000 * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
					isSeek = false;
				}

				mInputBufIndex = mMediaCodec.dequeueInputBuffer(kTimeOutUs);
				if (mInputBufIndex >= 0)
				{
					ByteBuffer dstBuf = codecInputBuffers[mInputBufIndex];

					int sampleSize = mExtractor.readSampleData(dstBuf, 0);

					long presentationTimeUs = 0;

					if (sampleSize < 0)
					{
						Log.d(TAG, "saw input EOS.");
						sawInputEOS = true;
						sampleSize = 0;
					}
					else
					{
						presentationTimeUs = mExtractor.getSampleTime();

						Log.d(TAG, "presentaionTime = " + (int) (presentationTimeUs / 1000 / 1000));

						mAudioPlayerHandler.onAudioPlayerCurrentTime((int) (presentationTimeUs / 1000 / 1000));
					}

					mMediaCodec.queueInputBuffer(mInputBufIndex, 0, sampleSize, presentationTimeUs,
							sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

					if (!sawInputEOS)
					{
						mExtractor.advance();
					}
				}
				else
				{
					Log.e(TAG, "inputBufIndex " + mInputBufIndex);
				}
			}

			int res = mMediaCodec.dequeueOutputBuffer(info, kTimeOutUs);

			if (res >= 0)
			{
				if (info.size > 0)
				{
					noOutputCounter = 0;
				}

				int outputBufIndex = res;
				ByteBuffer buf = codecOutputBuffers[outputBufIndex];

				final byte[] chunk = new byte[info.size];
				buf.get(chunk);
				buf.clear();
				if (chunk.length > 0)
				{
					mAudioTrack.write(chunk, 0, chunk.length);
					if (this.mState != State.Playing)
					{
						mAudioPlayerHandler.onAudioPlayerPlayerStart(AudioStreamPlayer.this);
					}
					this.mState = State.Playing;
				}
				mMediaCodec.releaseOutputBuffer(outputBufIndex, false);
			}
			else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)
			{
				codecOutputBuffers = mMediaCodec.getOutputBuffers();

				Log.d(TAG, "output buffers have changed.");
			}
			else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
			{
				MediaFormat oformat = mMediaCodec.getOutputFormat();

				Log.d(TAG, "output format has changed to " + oformat);
			}
			else
			{
				Log.d(TAG, "dequeueOutputBuffer returned " + res);
			}
		}

		Log.d(TAG, "stopping...");

		releaseResources(true);

		this.mState = State.Stopped;
		isForceStop = true;

		if (noOutputCounter >= noOutputCounterLimit)
		{
			mAudioPlayerHandler.onAudioPlayerError(AudioStreamPlayer.this);
		}
		else
		{
			mAudioPlayerHandler.onAudioPlayerStop(AudioStreamPlayer.this);
		}
	}

	public void release()
	{
		stop();
		releaseResources(false);
	}

	private void releaseResources(Boolean release)
	{
		if (mExtractor != null)
		{
			mExtractor.release();
			mExtractor = null;
		}

		if (mMediaCodec != null)
		{
			if (release)
			{
				mMediaCodec.stop();
				mMediaCodec.release();
				mMediaCodec = null;
			}

		}
		if (mAudioTrack != null)
		{
			mAudioTrack.flush();
			mAudioTrack.release();
			mAudioTrack = null;
		}
	}

	public void pause()
	{
		isPause = true;
	}

	public void stop()
	{
		isForceStop = true;
	}

	boolean isSeek = false;
	int seekTime = 0;

	public void seekTo(int progress)
	{
		isSeek = true;
		seekTime = progress;
	}

	public void pauseToPlay()
	{
		isPause = false;
	}
}
